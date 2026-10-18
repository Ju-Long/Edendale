using System.Diagnostics;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services.Remote;

/// <summary>A finished sign-in, with the Drive files picked in Google's picker along the way (none for other providers).</summary>
public sealed record CloudSignInResult(CloudAccount Account, IReadOnlyList<string> PickedFileIds);

/// <summary>
/// Links a Google Drive, OneDrive, or Dropbox account (DIFF.md §3.12): the system browser
/// opens the provider's consent page, the loopback redirect receives the
/// code, PKCE redeems it, and only the refresh token is kept (DPAPI). The
/// account stays on this device (D11) and is separate from the OneDrive
/// folder Edendale replicates watch state into (D12). Google's page also
/// opens Google's picker, where the user ticks the videos Edendale may play
/// (ENHANCEMENT.md 3.20).
/// </summary>
public static class CloudSignIn
{
    /// <summary>How long the browser step may take before it is abandoned; Google's picker happens inside it.</summary>
    public static readonly TimeSpan BrowserTimeout = TimeSpan.FromMinutes(10);

    public static async Task<CloudSignInResult> SignInAsync(MediaSourceKind kind, string completedPage, CancellationToken cancellation)
    {
        var (tokens, picked) = await AuthorizeAsync(kind, null, completedPage, cancellation).ConfigureAwait(false);
        CloudProviders.CheckGrantedScopes(kind, tokens);
        var identity = await CloudProviders.IdentityAsync(kind, tokens, null, cancellation).ConfigureAwait(false);
        var account = CloudProviders.NewAccount(kind, tokens, identity);
        AppServices.CloudAccounts.Save(account);
        AppServices.CloudTokens.Store(tokens, account);
        return new CloudSignInResult(account, picked);
    }

    /// <summary>
    /// Choose More Videos (3.20 R7): reruns Google's picker for a linked account.
    /// Every run returns a new refresh token; the newest is kept and the old
    /// one is left alone, because Google's revoke would end the whole grant,
    /// the new token included. Fails if the picker was used as another
    /// Google account.
    /// </summary>
    public static async Task<CloudSignInResult> ChooseMoreVideosAsync(CloudAccount account, string completedPage, CancellationToken cancellation)
    {
        var kind = account.SourceKind;
        if (kind != MediaSourceKind.GoogleDrive) throw new OAuthException(OAuthFailure.NotConfigured, kind.DisplayName());
        var (tokens, picked) = await AuthorizeAsync(kind, CloudProviders.GoogleChooseMoreParameters(account.Email), completedPage, cancellation)
            .ConfigureAwait(false);
        CloudProviders.CheckGrantedScopes(kind, tokens);
        var identity = await CloudProviders.IdentityAsync(kind, tokens, null, cancellation).ConfigureAwait(false);
        var current = AppServices.CloudAccounts.Account(kind, account.Key) ?? account;
        var updated = CloudProviders.ChosenVideosAccount(current, tokens, identity);
        AppServices.CloudAccounts.Save(updated);
        AppServices.CloudTokens.Store(tokens, updated);
        return new CloudSignInResult(updated, picked);
    }

    /// <summary>The provider's page in the system browser, then the code exchange.</summary>
    private static async Task<(OAuthTokenResponse Tokens, IReadOnlyList<string> PickedFileIds)> AuthorizeAsync(
        MediaSourceKind kind, IReadOnlyDictionary<string, string>? parameters, string completedPage, CancellationToken cancellation)
    {
        var provider = kind.DisplayName();
        var configuration = CloudProviders.Configuration(kind) ?? throw new OAuthException(OAuthFailure.NotConfigured, provider);
        var client = new OAuthClient(configuration);
        var verifier = Pkce.MakeVerifier();
        var state = Pkce.MakeState();

        using var redirect = new LoopbackRedirect(configuration.LoopbackPort);
        var url = client.AuthorizationUrl(redirect.RedirectUri, state, Pkce.Challenge(verifier), parameters);
        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });

        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        timeout.CancelAfter(BrowserTimeout);
        string query;
        try
        {
            query = await redirect.WaitForQueryAsync(completedPage, timeout.Token).ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
            throw new OAuthException(OAuthFailure.Cancelled, provider);
        }

        var authorization = OAuthClient.AuthorizationResponse(query, state, provider);
        var tokens = await client.ExchangeAsync(authorization.Code, verifier, redirect.RedirectUri, cancellation).ConfigureAwait(false);
        return (tokens, authorization.PickedFileIds);
    }

    /// <summary>Signs an account out: Google's and Dropbox's grants are revoked (best effort) and the tokens forgotten.</summary>
    public static async Task SignOutAsync(MediaSourceKind kind, string accountKey)
    {
        var cached = AppServices.CloudTokens.CachedToken(kind, accountKey);
        var refreshToken = AppServices.CloudAccounts.Account(kind, accountKey)?.RefreshToken;
        AppServices.CloudTokens.Forget(kind, accountKey);
        AppServices.CloudAccounts.Remove(kind, accountKey);
        await CloudProviders.RevokeAsync(kind, refreshToken, cached).ConfigureAwait(false);
    }
}
