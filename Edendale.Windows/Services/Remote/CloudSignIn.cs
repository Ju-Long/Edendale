using System.Diagnostics;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services.Remote;

/// <summary>A finished sign-in, with the Drive files picked in Google's picker along the way (none for other providers).</summary>
public sealed record CloudSignInResult(CloudAccount Account, IReadOnlyList<string> PickedFileIds);

/// <summary>
/// Links a Google Drive, OneDrive, or Dropbox account (DIFF.md §3.12): the
/// system browser opens the provider's consent page, the loopback redirect
/// receives the code, PKCE redeems it, and only the refresh token is kept
/// (DPAPI). The account stays on this device (D11) unless the person sends it
/// to another of their devices with the handoff. Since 27.1 the grant also
/// covers the sync files, which Edendale writes only once the account is
/// chosen as the sync location (FEATURES-27.1.md §10). From Link Source,
/// Google's page also opens Google's picker, where the person ticks the
/// videos Edendale may play (ENHANCEMENT.md 3.20); a sign-in for the sync
/// location has nothing to choose and skips it (K.8 on apple-27.1).
/// </summary>
public static class CloudSignIn
{
    /// <summary>How long the browser step may take before it is abandoned; Google's picker happens inside it.</summary>
    public static readonly TimeSpan BrowserTimeout = TimeSpan.FromMinutes(10);

    public static async Task<CloudSignInResult> SignInAsync(MediaSourceKind kind, string completedPage, CancellationToken cancellation)
    {
        var (tokens, picked) = await AuthorizeAsync(kind, SignInPurpose.Library, null, completedPage, cancellation).ConfigureAwait(false);
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
        var (tokens, picked) = await AuthorizeAsync(kind, SignInPurpose.Library, CloudProviders.GoogleChooseMoreParameters(account.Email), completedPage, cancellation)
            .ConfigureAwait(false);
        CloudProviders.CheckGrantedScopes(kind, tokens);
        var identity = await CloudProviders.IdentityAsync(kind, tokens, null, cancellation).ConfigureAwait(false);
        var current = AppServices.CloudAccounts.Account(kind, account.Key) ?? account;
        var updated = CloudProviders.ChosenVideosAccount(current, tokens, identity);
        AppServices.CloudAccounts.Save(updated);
        AppServices.CloudTokens.Store(tokens, updated);
        return new CloudSignInResult(updated, picked);
    }

    /// <summary>
    /// Signs in an account for the sync location (FEATURES-27.1.md §5.2,
    /// ENHANCEMENT.md K.8 on apple-27.1): OneDrive and Dropbox with their
    /// write scopes, Google Drive with drive.file but without the picker.
    /// <paramref name="loginHint"/> is the email a handoff from another
    /// ecosystem names (§6.4), or the linked account signing in again;
    /// Dropbox gets none (<see cref="CloudProviders.SyncLoginHint"/>). An
    /// account that is already linked is replaced with the new grant, so its
    /// sources keep working. A grant that still can't write the sync files
    /// is kept for those sources, but fails here with
    /// <see cref="OAuthFailure.SyncWriteMissing"/>, so it never becomes the
    /// location.
    /// </summary>
    public static async Task<CloudAccount> SignInForSyncAsync(MediaSourceKind kind, string? loginHint, string completedPage, CancellationToken cancellation)
    {
        var (tokens, _) = await AuthorizeAsync(kind, SignInPurpose.Sync, CloudProviders.SyncLoginHint(kind, loginHint), completedPage, cancellation)
            .ConfigureAwait(false);
        CloudProviders.CheckGrantedScopes(kind, tokens);
        var identity = await CloudProviders.IdentityAsync(kind, tokens, null, cancellation).ConfigureAwait(false);
        var account = CloudProviders.NewAccount(kind, tokens, identity);
        AppServices.CloudAccounts.Save(account);
        AppServices.CloudTokens.Store(tokens, account);
        CloudProviders.CheckSyncWriteScope(account);
        return account;
    }

    /// <summary>The provider's page in the system browser, then the code exchange.</summary>
    private static async Task<(OAuthTokenResponse Tokens, IReadOnlyList<string> PickedFileIds)> AuthorizeAsync(
        MediaSourceKind kind, SignInPurpose purpose, IReadOnlyDictionary<string, string>? parameters, string completedPage, CancellationToken cancellation)
    {
        var provider = kind.DisplayName();
        var configuration = CloudProviders.Configuration(kind, purpose) ?? throw new OAuthException(OAuthFailure.NotConfigured, provider);
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
