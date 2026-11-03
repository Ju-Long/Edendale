// What a Windows PC sends and keeps in a handoff (SYNC-PROTOCOL.md §9.6–§9.8,
// FEATURES-27.1.md §6.1, §6.4): the sync location with its login, and the
// other linked accounts and saved logins the user ticks. Google refresh
// tokens travel only to another Windows device (§9.8); everything else
// travels as the version-1 Account and Login shapes. The receiving side
// validates each item before it stores it: a token is refreshed, a server
// login connects, an SFTP host key is compared. WinUI-free: the transport
// and the dialogs are elsewhere, and the tests drive this with fake stores.

using Edendale.Windows.Core;
using Edendale.Windows.Core.Handoff;
using Edendale.Windows.Core.Sync;
using Edendale.Windows.Services.Remote;
using Edendale.Windows.Services.Sync;

namespace Edendale.Windows.Services.Handoff;

public enum HandoffItemKind
{
    Location,
    Account,
    Login,
}

/// <summary>
/// One thing the sending user can tick. <see cref="Id"/> is the item name the
/// result reports (`location`, `account:&lt;kind&gt;:&lt;subject&gt;`,
/// `login:&lt;kind&gt;:&lt;host&gt;`). <see cref="Transfers"/> is false for a Google
/// account the receiver's ecosystem can't use; the location then carries the
/// email and folder ID instead.
/// </summary>
public sealed record HandoffItem(string Id, HandoffItemKind Kind, string Title, string? Detail, bool Transfers = true);

/// <summary>The stores a handoff reads and writes; the app passes its own, the tests fakes.</summary>
public sealed class HandoffStores
{
    public required SyncState Sync { get; init; }
    public required CloudAccountVault Accounts { get; init; }
    public required ServerLoginStore ServerLogins { get; init; }
    public required HostKeyStore HostKeys { get; init; }
    public required Func<IReadOnlyList<SmbCredentials>> SmbLogins { get; init; }
    public required Action<SmbCredentials> SaveSmbLogin { get; init; }
    public string Platform { get; init; } = SyncFormat.WindowsPlatform;
}

/// <summary>What the receiver must do after the exchange, outside the 2-minute result window.</summary>
public abstract record HandoffFollowUp
{
    /// <summary>A folder location (Apple's `icloudDrive` included): this device picks its own folder for the space.</summary>
    public sealed record PickFolder(string Space, string Provider) : HandoffFollowUp;

    /// <summary>Google Drive from another ecosystem: sign in with the hint, then use the folder.</summary>
    public sealed record GoogleSignIn(string? Hint, string? FolderId, string Space) : HandoffFollowUp;
}

/// <summary>The receiver's outcome: the result to send back, and what follows.</summary>
public sealed record HandoffAdoption(HandoffResult Result, SyncLocationSettings? Location, HandoffFollowUp? FollowUp);

/// <summary>How the receiver proves an item works before storing it; the app connects for real, tests fake it.</summary>
public sealed class HandoffValidators
{
    /// <summary>Refreshes the token and stores the account; throws when the provider refuses it.</summary>
    public required Func<HandoffAccount, CancellationToken, Task<CloudAccount>> AdoptAccount { get; init; }

    /// <summary>Connects with the login (to <c>url</c> when known); throws when the server refuses it or its host key differs.</summary>
    public required Func<HandoffLogin, string?, CancellationToken, Task> ValidateLogin { get; init; }

    /// <summary>Makes the location this device's (lists the space once, then syncs).</summary>
    public required Func<SyncLocationSettings, CancellationToken, Task> SetLocation { get; init; }
}

public static class HandoffOffers
{
    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /// <summary>The items the sender can tick for a receiver on <paramref name="receiverPlatform"/>.</summary>
    public static IReadOnlyList<HandoffItem> Choices(HandoffStores stores, string? receiverPlatform)
    {
        var googleTransfers = HandoffEcosystem.SharesGoogleClient(stores.Platform, receiverPlatform);
        var items = new List<HandoffItem>();
        if (stores.Sync.Location is { } location)
        {
            items.Add(new HandoffItem(HandoffResult.LocationItem, HandoffItemKind.Location, location.Space, location.ProviderName));
        }
        foreach (var account in stores.Accounts.All)
        {
            var transfers = account.SourceKind != MediaSourceKind.GoogleDrive || googleTransfers;
            items.Add(new HandoffItem($"account:{account.Kind}:{account.Subject}", HandoffItemKind.Account, account.Label, account.SourceKind.DisplayName(), transfers));
        }
        foreach (var login in stores.ServerLogins.Logins)
        {
            items.Add(new HandoffItem($"login:{login.Kind.RawValue()}:{login.Key}", HandoffItemKind.Login, login.Title, login.Kind.DisplayName()));
        }
        foreach (var smb in stores.SmbLogins())
        {
            items.Add(new HandoffItem($"login:{MediaSourceKind.Smb.RawValue()}:{smb.Host.ToLowerInvariant()}", HandoffItemKind.Login, smb.Host, "SMB"));
        }
        return items;
    }

    /// <summary>
    /// The offer for the ticked items (§9.6 step 3). A Google account goes
    /// only to a receiver of the same ecosystem; otherwise the location
    /// carries the account's email and the space folder's Drive ID (§9.8).
    /// </summary>
    public static HandoffOffer Build(HandoffStores stores, IReadOnlySet<string> ticked, string? receiverPlatform)
    {
        var googleTransfers = HandoffEcosystem.SharesGoogleClient(stores.Platform, receiverPlatform);
        HandoffLocation? location = null;
        if (ticked.Contains(HandoffResult.LocationItem) && stores.Sync.Location is { } settings)
        {
            location = LocationPayload(stores, settings, googleTransfers);
        }

        var accounts = new List<HandoffAccount>();
        foreach (var account in stores.Accounts.All)
        {
            if (!ticked.Contains($"account:{account.Kind}:{account.Subject}")) continue;
            if (account.SourceKind == MediaSourceKind.GoogleDrive && !googleTransfers) continue;
            accounts.Add(AccountPayload(account));
        }

        var logins = new List<HandoffLogin>();
        foreach (var saved in stores.ServerLogins.Logins)
        {
            if (!ticked.Contains($"login:{saved.Kind.RawValue()}:{saved.Key}")) continue;
            if (stores.ServerLogins.Get(saved.Kind, saved.Key) is { } login) logins.Add(LoginPayload(stores, login));
        }
        foreach (var smb in stores.SmbLogins())
        {
            if (!ticked.Contains($"login:{MediaSourceKind.Smb.RawValue()}:{smb.Host.ToLowerInvariant()}")) continue;
            logins.Add(new HandoffLogin(MediaSourceKind.Smb.RawValue(), smb.Host, null, smb.Username, smb.Password));
        }
        return new HandoffOffer(true, location, accounts, logins);
    }

    private static HandoffLocation LocationPayload(HandoffStores stores, SyncLocationSettings settings, bool googleTransfers)
    {
        var provider = settings.ProviderName;
        var url = SyncLocationUrls.ForHandoff(settings);
        switch (settings.Kind)
        {
            case SyncLocationKind.Folder:
                // The receiver picks its own folder (§9.6).
                return new HandoffLocation(provider, settings.Space);
            case SyncLocationKind.GoogleDrive or SyncLocationKind.OneDrive or SyncLocationKind.Dropbox:
            {
                var kind = settings.SourceKind!.Value;
                var account = settings.AccountKey is { } key ? stores.Accounts.Account(kind, key) : null;
                if (kind == MediaSourceKind.GoogleDrive && !googleTransfers)
                {
                    return new HandoffLocation(provider, settings.Space, GoogleAccountHint: account?.Email, GoogleFolderId: settings.GoogleFolderId);
                }
                return new HandoffLocation(provider, settings.Space, account is null ? null : AccountPayload(account),
                    GoogleFolderId: kind == MediaSourceKind.GoogleDrive ? settings.GoogleFolderId : null);
            }
            case SyncLocationKind.Smb:
            {
                var host = url is not null && SourceUrl.TrySplit(url, out var parts) ? parts.Host : null;
                var smb = host is null ? null : stores.SmbLogins().FirstOrDefault(login => login.Host.Equals(host, StringComparison.OrdinalIgnoreCase));
                var login = smb is null ? null : new HandoffLogin(MediaSourceKind.Smb.RawValue(), smb.Host, null, smb.Username, smb.Password, Addresses: url is null ? null : [url]);
                return new HandoffLocation(provider, settings.Space, Login: login, Url: url);
            }
            default:
            {
                var kind = settings.SourceKind!.Value;
                var host = url is not null && SourceUrl.TrySplit(url, out var parts) ? parts.Host : null;
                var saved = host is null ? null : stores.ServerLogins.Get(kind, host);
                var login = saved is null ? null : LoginPayload(stores, saved, url);
                return new HandoffLocation(provider, settings.Space, Login: login, Url: url);
            }
        }
    }

    public static HandoffAccount AccountPayload(CloudAccount account) =>
        new(account.Kind, account.Subject, account.Email, account.DisplayName, account.RefreshToken, account.Scopes ?? [], account.DriveId);

    private static HandoffLogin LoginPayload(HandoffStores stores, ServerLogin login, string? address = null)
    {
        var kind = login.SourceKind;
        int? port = kind == MediaSourceKind.Sftp ? login.Port ?? (address is not null && SourceUrl.TrySplit(address, out var parts) ? parts.Port : null) ?? 22 : null;
        var fingerprint = kind == MediaSourceKind.Sftp ? stores.HostKeys.PinnedFingerprint(login.Host, port ?? 22) : null;
        var s3 = login.S3 is { } configuration ? new HandoffS3(configuration.Endpoint, configuration.Region, configuration.Bucket, configuration.UsesPathStyle) : null;
        return new HandoffLogin(kind.RawValue(), login.Host, port, login.Username, login.Password, fingerprint, null, s3, address is null ? null : [address]);
    }

    // ------------------------------------------------------------------
    // Receiving
    // ------------------------------------------------------------------

    /// <summary>
    /// Validates and stores what was offered (§9.6 step 4) and says what was
    /// kept. Nothing is stored that failed validation. The location becomes
    /// this device's when it can (a cloud account or a server login); a
    /// folder, and Google Drive from another ecosystem, need the user after
    /// the exchange (<see cref="HandoffAdoption.FollowUp"/>).
    /// </summary>
    public static async Task<HandoffAdoption> AdoptAsync(HandoffOffer offer, HandoffStores stores, HandoffValidators validators, CancellationToken cancellation)
    {
        var stored = new List<string>();
        var failed = new List<HandoffFailure>();

        foreach (var account in offer.AccountList)
        {
            var item = HandoffResult.AccountItem(account);
            try
            {
                if (MediaSourceKinds.FromRawValue(account.Kind) is not { } kind || !kind.IsCloudAccount())
                {
                    failed.Add(new HandoffFailure(item, "unsupported"));
                    continue;
                }
                await validators.AdoptAccount(account, cancellation).ConfigureAwait(false);
                stored.Add(item);
            }
            catch (Exception error) when (error is not OperationCanceledException)
            {
                failed.Add(new HandoffFailure(item, Reason(error)));
            }
        }

        foreach (var login in offer.LoginList)
        {
            var item = HandoffResult.LoginItem(login);
            try
            {
                await StoreLoginAsync(login, login.Addresses?.FirstOrDefault(), stores, validators, cancellation).ConfigureAwait(false);
                stored.Add(item);
            }
            catch (Exception error) when (error is not OperationCanceledException)
            {
                failed.Add(new HandoffFailure(item, Reason(error)));
            }
        }

        SyncLocationSettings? settings = null;
        HandoffFollowUp? followUp = null;
        if (offer.Location is { } location)
        {
            try
            {
                (settings, followUp) = await AdoptLocationAsync(location, stores, validators, cancellation).ConfigureAwait(false);
                stored.Add(HandoffResult.LocationItem);
            }
            catch (Exception error) when (error is not OperationCanceledException)
            {
                failed.Add(new HandoffFailure(HandoffResult.LocationItem, Reason(error)));
            }
        }

        SyncLog.Info("handoff", $"adopted: {stored.Count} stored, {failed.Count} failed");
        return new HandoffAdoption(new HandoffResult(stored, failed), settings, followUp);
    }

    private static async Task<(SyncLocationSettings?, HandoffFollowUp?)> AdoptLocationAsync(
        HandoffLocation location, HandoffStores stores, HandoffValidators validators, CancellationToken cancellation)
    {
        var kind = SyncLocationSettings.KindFromProvider(location.Provider) ?? throw new HandoffException(HandoffError.Malformed, "unsupported provider");
        var space = SyncSpaceName.Normalize(location.Space) ?? throw new HandoffException(HandoffError.Malformed, "space");
        switch (kind)
        {
            case SyncLocationKind.Folder:
                return (null, new HandoffFollowUp.PickFolder(space, location.Provider));

            case SyncLocationKind.GoogleDrive or SyncLocationKind.OneDrive or SyncLocationKind.Dropbox:
            {
                if (location.Account is not { } account)
                {
                    if (kind == SyncLocationKind.GoogleDrive) return (null, new HandoffFollowUp.GoogleSignIn(location.GoogleAccountHint, location.GoogleFolderId, space));
                    throw new HandoffException(HandoffError.Malformed, "no account");
                }
                var stored = await validators.AdoptAccount(account, cancellation).ConfigureAwait(false);
                // An account whose scopes are listed and lack the write scope can't hold the files (§10.5).
                if (stored.Scopes is { Count: > 0 } && !CloudProviders.CanWriteSyncFiles(stored))
                {
                    throw new ConnectorException(ConnectorFailure.SignInRequired, stored.SourceKind.DisplayName());
                }
                var settings = new SyncLocationSettings
                {
                    Kind = kind,
                    Space = space,
                    AccountKey = stored.Key,
                    GoogleFolderId = kind == SyncLocationKind.GoogleDrive ? location.GoogleFolderId : null,
                };
                await validators.SetLocation(settings, cancellation).ConfigureAwait(false);
                return (settings, null);
            }

            default:
            {
                var path = SyncLocationUrls.FromHandoff(kind, location.Url ?? location.Login?.Addresses?.FirstOrDefault())
                    ?? throw new HandoffException(HandoffError.Malformed, "no url");
                if (location.Login is { } login)
                {
                    var address = kind == SyncLocationKind.Smb ? path : location.Url;
                    await StoreLoginAsync(login, address, stores, validators, cancellation).ConfigureAwait(false);
                }
                var settings = new SyncLocationSettings { Kind = kind, Space = space, Path = path };
                await validators.SetLocation(settings, cancellation).ConfigureAwait(false);
                return (settings, null);
            }
        }
    }

    /// <summary>Validates a login, then keeps it (and for SFTP pins the approved host key).</summary>
    private static async Task StoreLoginAsync(HandoffLogin login, string? address, HandoffStores stores, HandoffValidators validators, CancellationToken cancellation)
    {
        var kind = MediaSourceKinds.FromRawValue(login.Kind) ?? throw new HandoffException(HandoffError.Malformed, "kind");
        if (!kind.UsesServerLogin()) throw new HandoffException(HandoffError.Malformed, "kind");
        if (kind == MediaSourceKind.Sftp && string.IsNullOrEmpty(login.HostKeyFingerprint))
        {
            // Never trust an SFTP server on first use through a handoff: the sender's approved key is required.
            throw new ConnectorException(ConnectorFailure.HostKeyUnverified, login.Host);
        }
        await validators.ValidateLogin(login, address, cancellation).ConfigureAwait(false);
        switch (kind)
        {
            case MediaSourceKind.Smb:
                stores.SaveSmbLogin(new SmbCredentials(login.Host, login.User, login.Password));
                break;
            case MediaSourceKind.Sftp:
                stores.HostKeys.Pin(login.HostKeyFingerprint!, login.Host, login.Port ?? 22);
                stores.ServerLogins.Save(new ServerLogin { Kind = login.Kind, Host = login.Host, Username = login.User, Password = login.Password, Port = login.Port ?? 22 });
                break;
            case MediaSourceKind.S3:
                if (login.S3 is not { } s3) throw new HandoffException(HandoffError.Malformed, "s3");
                stores.ServerLogins.Save(new ServerLogin
                {
                    Kind = login.Kind,
                    Host = login.Host,
                    Username = login.User,
                    Password = login.Password,
                    S3 = new S3Configuration(s3.Endpoint, s3.Region, s3.Bucket, s3.PathStyle),
                });
                break;
            default:
                stores.ServerLogins.Save(new ServerLogin { Kind = login.Kind, Host = login.Host, Username = login.User, Password = login.Password });
                break;
        }
    }

    /// <summary>A short reason for the sender's screen: the readable message, which never carries a secret.</summary>
    private static string Reason(Exception error) => error switch
    {
        ConnectorException or OAuthException => error.Message,
        HandoffException handoff => handoff.Reason.ToString().ToLowerInvariant(),
        _ => error.GetType().Name,
    };

    /// <summary>
    /// The app's account check (§9.6 step 4): refreshes the handed-over token
    /// with this device's client, keeps the newest refresh token, and stores
    /// the account. A token the provider refuses is never stored.
    /// </summary>
    public static async Task<CloudAccount> RefreshAndStoreAsync(
        HandoffAccount account,
        CloudAccountVault vault,
        CloudTokenProvider tokens,
        Func<MediaSourceKind, OAuthConfiguration?> configuration,
        HttpMessageInvoker? client,
        CancellationToken cancellation)
    {
        var kind = MediaSourceKinds.FromRawValue(account.Kind) ?? throw new HandoffException(HandoffError.Malformed, "kind");
        var settings = configuration(kind) ?? throw new OAuthException(OAuthFailure.NotConfigured, kind.DisplayName());
        var cloud = new CloudAccount
        {
            Kind = kind.RawValue(),
            Subject = account.Subject,
            Email = account.Email,
            DisplayName = account.DisplayName,
            RefreshToken = account.RefreshToken,
            DriveId = account.DriveId,
            Scopes = account.Scopes.Count > 0 ? account.Scopes : null,
        };
        var response = await new OAuthClient(settings, client).RefreshAsync(account.RefreshToken, cancellation, CloudProviders.RefreshScopes(cloud)).ConfigureAwait(false);
        var stored = response.RefreshToken is { Length: > 0 } rotated && rotated != cloud.RefreshToken ? cloud with { RefreshToken = rotated } : cloud;
        vault.Save(stored);
        tokens.Store(response, stored);
        return stored;
    }
}
