// Device-local stores for remote-source logins (DIFF.md §3.12, D11). Server
// passwords, S3 secret keys, and cloud refresh tokens are encrypted with DPAPI
// for the current Windows user, like the SMB logins and the TMDB session, and
// none of these files enters the OneDrive replica (D12). Access tokens are
// never written anywhere: they live in memory for the session.

using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services;

/// <summary>
/// One saved login or linked account, as Settings → Accounts lists it.
/// <see cref="Key"/> is what the credential is stored under: the server for
/// SMB, SFTP, and WebDAV, the account key for S3 and cloud accounts.
/// </summary>
public sealed record SavedLogin(MediaSourceKind Kind, string Key, string Title, string? Detail);

/// <summary>Encrypts a store's bytes at rest.</summary>
public interface ISecretProtector
{
    byte[] Protect(byte[] data);
    byte[] Unprotect(byte[] data);
}

/// <summary>DPAPI for the current Windows user.</summary>
public sealed class DpapiProtector : ISecretProtector
{
    public static readonly DpapiProtector CurrentUser = new();

    public byte[] Protect(byte[] data) => ProtectedData.Protect(data, null, DataProtectionScope.CurrentUser);

    public byte[] Unprotect(byte[] data) => ProtectedData.Unprotect(data, null, DataProtectionScope.CurrentUser);
}

/// <summary>A JSON document on disk, optionally encrypted, written atomically.</summary>
internal sealed class ProtectedJsonFile<T> where T : class, new()
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly string _path;
    private readonly ISecretProtector? _protector;

    public ProtectedJsonFile(string path, ISecretProtector? protector)
    {
        _path = path;
        _protector = protector;
    }

    /// <summary>The stored value, or an empty one when the file is missing or unreadable (another user's DPAPI blob, say).</summary>
    public T Load()
    {
        try
        {
            if (!File.Exists(_path)) return new T();
            var bytes = File.ReadAllBytes(_path);
            if (_protector is not null) bytes = _protector.Unprotect(bytes);
            return JsonSerializer.Deserialize<T>(bytes, JsonOptions) ?? new T();
        }
        catch
        {
            return new T();
        }
    }

    public void Save(T value)
    {
        try
        {
            var bytes = JsonSerializer.SerializeToUtf8Bytes(value, JsonOptions);
            if (_protector is not null) bytes = _protector.Protect(bytes);
            var temporary = _path + ".tmp";
            File.WriteAllBytes(temporary, bytes);
            File.Move(temporary, _path, overwrite: true);
        }
        catch
        {
            // The value stays usable in memory for this session.
        }
    }
}

// ----------------------------------------------------------------------
// Server logins: SFTP, WebDAV, and S3
// ----------------------------------------------------------------------

/// <summary>Where an S3-compatible bucket lives.</summary>
public sealed record S3Configuration(string Endpoint, string Region, string Bucket, bool UsesPathStyle)
{
    /// <summary>The endpoint's host, for display ("films @ s3.example.com").</summary>
    [JsonIgnore]
    public string EndpointHost => SourceUrl.TrySplit(Endpoint, out var parts) ? parts.Host : Endpoint;
}

/// <summary>
/// A server login. An empty username and password mean a guest. For S3 the
/// username is the access key ID and the password the secret access key.
/// </summary>
public sealed record ServerLogin
{
    /// <summary><see cref="MediaSourceKind"/> raw value.</summary>
    public required string Kind { get; init; }

    /// <summary>The server's host, or for S3 the account key in the source's URL.</summary>
    public required string Host { get; init; }

    public string Username { get; init; } = "";
    public string Password { get; init; } = "";

    /// <summary>SFTP only: the SSH port the login was made on.</summary>
    public int? Port { get; init; }

    /// <summary>S3 only: the bucket's location.</summary>
    public S3Configuration? S3 { get; init; }

    [JsonIgnore]
    public bool IsGuest => Username.Length == 0 && Password.Length == 0;

    [JsonIgnore]
    public MediaSourceKind SourceKind => MediaSourceKinds.FromRawValue(Kind) ?? MediaSourceKind.WebDav;
}

public sealed class ServerLoginStore
{
    private readonly object _gate = new();
    private readonly ProtectedJsonFile<List<ServerLogin>> _file;
    private readonly List<ServerLogin> _logins;

    public ServerLoginStore(string path, ISecretProtector protector)
    {
        _file = new ProtectedJsonFile<List<ServerLogin>>(path, protector);
        _logins = _file.Load();
    }

    public event EventHandler? Changed;

    private static bool Matches(ServerLogin login, MediaSourceKind kind, string host) =>
        login.Kind == kind.RawValue() && string.Equals(login.Host, host, StringComparison.OrdinalIgnoreCase);

    public ServerLogin? Get(MediaSourceKind kind, string host)
    {
        lock (_gate) return _logins.FirstOrDefault(login => Matches(login, kind, host));
    }

    public void Save(ServerLogin login)
    {
        var normalized = login with { Host = login.Host.ToLowerInvariant() };
        lock (_gate)
        {
            _logins.RemoveAll(existing => Matches(existing, normalized.SourceKind, normalized.Host));
            _logins.Add(normalized);
            _file.Save(_logins);
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public void Remove(MediaSourceKind kind, string host)
    {
        lock (_gate)
        {
            _logins.RemoveAll(login => Matches(login, kind, host));
            _file.Save(_logins);
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>What Settings → Accounts shows: never a password or secret key.</summary>
    public IReadOnlyList<SavedLogin> Logins
    {
        get
        {
            lock (_gate)
            {
                return _logins
                    .Select(login => login.S3 is { } s3
                        ? new SavedLogin(login.SourceKind, login.Host, $"{s3.Bucket} @ {s3.EndpointHost}", null)
                        : new SavedLogin(
                            login.SourceKind,
                            login.Host,
                            login.Port is int port && port != 22 && login.SourceKind == MediaSourceKind.Sftp ? $"{login.Host}:{port}" : login.Host,
                            login.IsGuest ? AppText.Get("Account_Guest") : login.Username))
                    .ToList();
            }
        }
    }
}

// ----------------------------------------------------------------------
// Cloud accounts: Google Drive, OneDrive, and Dropbox
// ----------------------------------------------------------------------

/// <summary>
/// A linked cloud account. Only the refresh token is stored; access tokens
/// are fetched as needed and kept in memory.
/// </summary>
public sealed record CloudAccount
{
    /// <summary><see cref="MediaSourceKind"/> raw value.</summary>
    public required string Kind { get; init; }

    /// <summary>The provider's stable user id (Google sub, Microsoft user id, Dropbox account_id).</summary>
    public required string Subject { get; init; }

    public string? Email { get; init; }
    public string? DisplayName { get; init; }
    public required string RefreshToken { get; init; }

    /// <summary>OneDrive: the user's default drive id.</summary>
    public string? DriveId { get; init; }

    [JsonIgnore]
    public MediaSourceKind SourceKind => MediaSourceKinds.FromRawValue(Kind) ?? MediaSourceKind.OneDrive;

    /// <summary>The account key: also the host of the account's source URLs.</summary>
    [JsonIgnore]
    public string Key => SourceUrl.AccountKey(SourceKind, Subject);

    /// <summary>What rows show for the account; never a token.</summary>
    [JsonIgnore]
    public string Label => Email ?? DisplayName ?? SourceKind.DisplayName();
}

public sealed class CloudAccountVault
{
    private readonly object _gate = new();
    private readonly ProtectedJsonFile<List<CloudAccount>> _file;
    private readonly List<CloudAccount> _accounts;

    public CloudAccountVault(string path, ISecretProtector protector)
    {
        _file = new ProtectedJsonFile<List<CloudAccount>>(path, protector);
        _accounts = _file.Load();
    }

    public event EventHandler? Changed;

    /// <summary>Every account, by provider name, then label.</summary>
    public IReadOnlyList<CloudAccount> All
    {
        get
        {
            lock (_gate)
            {
                return _accounts
                    .OrderBy(account => account.SourceKind.DisplayName(), StringComparer.OrdinalIgnoreCase)
                    .ThenBy(account => account.Label, StringComparer.OrdinalIgnoreCase)
                    .ToList();
            }
        }
    }

    public IReadOnlyList<CloudAccount> AccountsOf(MediaSourceKind kind) =>
        All.Where(account => account.SourceKind == kind).ToList();

    public CloudAccount? Account(MediaSourceKind kind, string key)
    {
        lock (_gate) return _accounts.FirstOrDefault(account => account.SourceKind == kind && account.Key == key);
    }

    /// <summary>Adds the account, or replaces the one with the same provider and subject.</summary>
    public void Save(CloudAccount account)
    {
        lock (_gate)
        {
            _accounts.RemoveAll(existing => existing.SourceKind == account.SourceKind && existing.Key == account.Key);
            _accounts.Add(account);
            _file.Save(_accounts);
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>Stores a rotated refresh token (Dropbox and Microsoft may issue a new one on refresh).</summary>
    public void UpdateRefreshToken(MediaSourceKind kind, string key, string refreshToken)
    {
        lock (_gate)
        {
            var index = _accounts.FindIndex(account => account.SourceKind == kind && account.Key == key);
            if (index < 0 || _accounts[index].RefreshToken == refreshToken) return;
            _accounts[index] = _accounts[index] with { RefreshToken = refreshToken };
            _file.Save(_accounts);
        }
    }

    public void Remove(MediaSourceKind kind, string key)
    {
        lock (_gate)
        {
            _accounts.RemoveAll(account => account.SourceKind == kind && account.Key == key);
            _file.Save(_accounts);
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }

    public IReadOnlyList<SavedLogin> Logins =>
        All.Select(account => new SavedLogin(account.SourceKind, account.Key, account.Label,
            account.Email is not null && account.DisplayName is not null ? account.DisplayName : null)).ToList();
}

// ----------------------------------------------------------------------
// SSH host keys (trust on first use)
// ----------------------------------------------------------------------

/// <summary>
/// The SSH host key fingerprint pinned for each host and port. A changed key
/// is refused until the user approves it again by linking the server.
/// </summary>
public sealed class HostKeyStore
{
    private readonly object _gate = new();
    private readonly ProtectedJsonFile<Dictionary<string, string>> _file;
    private readonly Dictionary<string, string> _pins;

    public HostKeyStore(string path)
    {
        _file = new ProtectedJsonFile<Dictionary<string, string>>(path, protector: null);
        _pins = new Dictionary<string, string>(_file.Load(), StringComparer.OrdinalIgnoreCase);
    }

    private static string KeyFor(string host, int port) => $"{host.ToLowerInvariant()}:{port}";

    public string? PinnedFingerprint(string host, int port)
    {
        lock (_gate) return _pins.GetValueOrDefault(KeyFor(host, port));
    }

    public void Pin(string fingerprint, string host, int port)
    {
        lock (_gate)
        {
            _pins[KeyFor(host, port)] = fingerprint;
            _file.Save(_pins);
        }
    }

    public void Remove(string host, int port)
    {
        lock (_gate)
        {
            _pins.Remove(KeyFor(host, port));
            _file.Save(_pins);
        }
    }
}
