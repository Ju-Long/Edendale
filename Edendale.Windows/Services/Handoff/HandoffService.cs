// The app's handoff wiring: the real stores and the checks a received item
// must pass before it is kept (SYNC-PROTOCOL.md §9.6 step 4): a refresh for
// a cloud account, a connection for a server login, and for SFTP the host key
// the sender approved. Consecutive wrong codes lock pairing for a minute
// (§9.4). The dialogs in Controls/HandoffDialog drive this.

using Edendale.Windows.Core;
using Edendale.Windows.Core.Handoff;
using Edendale.Windows.Services.Remote;

namespace Edendale.Windows.Services.Handoff;

public static class HandoffService
{
    /// <summary>§9.4: after this many failures in a row the host waits before showing another code.</summary>
    public const int MaxFailuresInRow = 5;
    public static readonly TimeSpan Lockout = TimeSpan.FromSeconds(60);

    private static readonly object Gate = new();
    private static int _failuresInRow;
    private static DateTimeOffset _lockedUntil;

    public static HandoffStores Stores => new()
    {
        Sync = AppServices.SyncState,
        Accounts = AppServices.CloudAccounts,
        ServerLogins = AppServices.ServerLogins,
        HostKeys = AppServices.HostKeys,
        SmbLogins = () => AppServices.SmbCredentials.Logins
            .Select(login => AppServices.SmbCredentials.Get(login.Host))
            .OfType<SmbCredentials>()
            .ToList(),
        SaveSmbLogin = login => AppServices.SmbCredentials.Save(login.Host, login.Username, login.Password),
    };

    public static HandoffValidators Validators => new()
    {
        AdoptAccount = (account, cancellation) => HandoffOffers.RefreshAndStoreAsync(
            account, AppServices.CloudAccounts, AppServices.CloudTokens, CloudProviders.Configuration, null, cancellation),
        ValidateLogin = ValidateLoginAsync,
        SetLocation = (settings, cancellation) => AppServices.FileSync.SetLocationAsync(settings, cancellation),
    };

    /// <summary>How long pairing stays locked after too many failures; zero when it isn't.</summary>
    public static TimeSpan LockedFor
    {
        get
        {
            lock (Gate)
            {
                var left = _lockedUntil - DateTimeOffset.UtcNow;
                return left > TimeSpan.Zero ? left : TimeSpan.Zero;
            }
        }
    }

    /// <summary>Counts a wrong code or QR secret; the fifth in a row starts the lockout.</summary>
    public static void RecordFailure()
    {
        lock (Gate)
        {
            _failuresInRow++;
            if (_failuresInRow >= MaxFailuresInRow)
            {
                _failuresInRow = 0;
                _lockedUntil = DateTimeOffset.UtcNow + Lockout;
            }
        }
    }

    public static void RecordSuccess()
    {
        lock (Gate) _failuresInRow = 0;
    }

    private static async Task ValidateLoginAsync(HandoffLogin login, string? address, CancellationToken cancellation)
    {
        var kind = MediaSourceKinds.FromRawValue(login.Kind) ?? throw new HandoffException(HandoffError.Malformed, "kind");
        switch (kind)
        {
            case MediaSourceKind.WebDav:
            {
                // Without a folder there is nothing that proves the login; it is kept as sent.
                if (address is null) return;
                var server = new ServerLogin { Kind = login.Kind, Host = login.Host, Username = login.User, Password = login.Password };
                var connector = WebDavConnector.FromAddress(WebDavConnector.HttpUrl(address) ?? address, server)
                    ?? throw new ConnectorException(ConnectorFailure.InvalidAddress);
                await connector.ValidateAsync(cancellation);
                return;
            }
            case MediaSourceKind.Sftp:
            {
                var port = login.Port ?? 22;
                var presented = await SftpConnector.ProbeHostKeyAsync(login.Host, port, cancellation);
                if (presented.Fingerprint != login.HostKeyFingerprint) throw new ConnectorException(ConnectorFailure.HostKeyMismatch, login.Host);
                // Prove the password against a throwaway pin, so nothing is kept if it fails.
                var pins = Path.Combine(Path.GetTempPath(), "edendale-handoff-" + Guid.NewGuid().ToString("N") + ".json");
                try
                {
                    var keys = new HostKeyStore(pins);
                    keys.Pin(presented.Fingerprint, login.Host, port);
                    var server = new ServerLogin { Kind = login.Kind, Host = login.Host, Username = login.User, Password = login.Password, Port = port };
                    using var connector = new SftpConnector(login.Host, port, server, keys);
                    await connector.ValidateAsync(cancellation);
                }
                finally
                {
                    File.Delete(pins);
                }
                return;
            }
            case MediaSourceKind.Smb:
            {
                if (address is null || SmbCredentialsStore.ShareFromUncPath(address) is not { } share) return;
                await Task.Run(() => NetworkShare.Connect(share, login.User, login.Password), cancellation);
                return;
            }
            case MediaSourceKind.S3:
            {
                if (login.S3 is not { } s3) throw new HandoffException(HandoffError.Malformed, "s3");
                var configuration = new S3Configuration(s3.Endpoint, s3.Region, s3.Bucket, s3.PathStyle);
                await new S3Connector(configuration, login.User, login.Password).ValidateAsync(cancellation);
                return;
            }
            default:
                throw new HandoffException(HandoffError.Malformed, "kind");
        }
    }
}
