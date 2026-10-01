using System.Diagnostics;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services.Remote;

/// <summary>
/// Links a OneDrive or Dropbox account (DIFF.md §3.12): the system browser
/// opens the provider's consent page, the loopback redirect receives the
/// code, PKCE redeems it, and only the refresh token is kept (DPAPI). The
/// account stays on this device (D11) and is separate from the OneDrive
/// folder Edendale replicates watch state into (D12).
/// </summary>
public static class CloudSignIn
{
    /// <summary>How long the browser step may take before it is abandoned.</summary>
    public static readonly TimeSpan BrowserTimeout = TimeSpan.FromMinutes(5);

    public static async Task<CloudAccount> SignInAsync(MediaSourceKind kind, string completedPage, CancellationToken cancellation)
    {
        var provider = kind.DisplayName();
        var configuration = CloudProviders.Configuration(kind) ?? throw new OAuthException(OAuthFailure.NotConfigured, provider);
        var client = new OAuthClient(configuration);
        var verifier = Pkce.MakeVerifier();
        var state = Pkce.MakeState();

        using var redirect = new LoopbackRedirect(configuration.LoopbackPort);
        var url = client.AuthorizationUrl(redirect.RedirectUri, state, Pkce.Challenge(verifier));
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

        var code = OAuthClient.AuthorizationCode(query, state, provider);
        var tokens = await client.ExchangeAsync(code, verifier, redirect.RedirectUri, cancellation).ConfigureAwait(false);
        var identity = await CloudProviders.IdentityAsync(kind, tokens.AccessToken, null, cancellation).ConfigureAwait(false);
        var refreshToken = tokens.RefreshToken is { Length: > 0 } value
            ? value
            : throw new OAuthException(OAuthFailure.MalformedResponse, provider);

        var account = new CloudAccount
        {
            Kind = kind.RawValue(),
            Subject = identity.Subject,
            Email = identity.Email,
            DisplayName = identity.DisplayName,
            RefreshToken = refreshToken,
            DriveId = identity.DriveId,
        };
        AppServices.CloudAccounts.Save(account);
        AppServices.CloudTokens.Store(tokens, account);
        return account;
    }

    /// <summary>Signs an account out: Dropbox's grant is revoked (best effort) and the tokens forgotten.</summary>
    public static async Task SignOutAsync(MediaSourceKind kind, string accountKey)
    {
        var cached = AppServices.CloudTokens.CachedToken(kind, accountKey);
        AppServices.CloudTokens.Forget(kind, accountKey);
        AppServices.CloudAccounts.Remove(kind, accountKey);
        await CloudProviders.RevokeAsync(kind, cached).ConfigureAwait(false);
    }
}
