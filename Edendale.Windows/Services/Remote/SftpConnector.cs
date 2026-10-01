// SFTP sources through SSH.NET (DIFF.md §3.12). Only modern algorithms are
// offered: curve25519 and ECDH key exchange (plus the post-quantum hybrids),
// Ed25519, ECDSA, and SHA-2 RSA host keys, and AES-GCM, ChaCha20-Poly1305, or
// AES-CTR with SHA-2 MACs. SHA-1, CBC, 3DES, and DSA are removed.
//
// Host keys are trusted on first use: the link flow shows the SHA-256
// fingerprint and key type, pins it, and a changed key is refused until the
// user approves it again by linking the server. Password login first (also
// answered for keyboard-interactive prompts); key login later.
//
// Item URLs are sftp://host[:port]/path/Name.ext. Files stream through a
// BufferedByteSource, each with its own connection.

using Edendale.Windows.Core;
using Renci.SshNet;
using Renci.SshNet.Common;
using Renci.SshNet.Sftp;

namespace Edendale.Windows.Services.Remote;

/// <summary>A host key a server presented, for the trust-on-first-use prompt.</summary>
public sealed record SshHostKey(string Algorithm, string Fingerprint)
{
    public string TypeName => SshFingerprint.TypeName(Algorithm);
}

public sealed class SftpConnector : IMediaConnector, IDisposable
{
    private readonly HostKeyStore _hostKeys;
    private readonly object _gate = new();
    private SftpClient? _client;

    public SftpConnector(string host, int port, ServerLogin login, HostKeyStore hostKeys, string? startPath = null)
    {
        Host = host;
        Port = port;
        Login = login;
        _hostKeys = hostKeys;
        StartPath = startPath;
    }

    /// <summary>Rebuilds the connector for a stored source; null without a saved login.</summary>
    public static SftpConnector? FromSource(string sourceUrl, ServerLoginStore logins, HostKeyStore hostKeys)
    {
        if (!SourceUrl.TrySplit(sourceUrl, out var parts) || MediaSourceKinds.FromScheme(parts.Scheme) != MediaSourceKind.Sftp) return null;
        if (logins.Get(MediaSourceKind.Sftp, parts.Host) is not { } login) return null;
        var path = "/" + string.Join("/", SourceUrl.PathSegments(sourceUrl));
        return new SftpConnector(parts.Host, parts.Port ?? login.Port ?? 22, login, hostKeys, path);
    }

    public string Host { get; }
    public int Port { get; }
    public ServerLogin Login { get; }

    /// <summary>The folder browsing starts in; the account's home directory when null.</summary>
    public string? StartPath { get; private set; }

    public MediaSourceKind Kind => MediaSourceKind.Sftp;
    public string? AccountLabel => Login.Username;

    public string Root => UrlFor(StartPath ?? "/", isDirectory: true);

    /// <summary>The canonical URL of a server path.</summary>
    public string UrlFor(string path, bool isDirectory) =>
        SourceUrl.Server("sftp", Host, Port == 22 ? null : Port, path.Split('/', StringSplitOptions.RemoveEmptyEntries), isDirectory)
            ?? throw new ConnectorException(ConnectorFailure.InvalidAddress);

    public static string PathOf(string url) => "/" + string.Join("/", SourceUrl.PathSegments(url));

    // ------------------------------------------------------------------
    // Connections
    // ------------------------------------------------------------------

    /// <summary>
    /// The host key the server presents, without trusting it or logging in,
    /// for the link flow's approval prompt.
    /// </summary>
    public static Task<SshHostKey> ProbeHostKeyAsync(string host, int port, CancellationToken cancellation) => Task.Run(() =>
    {
        SshHostKey? presented = null;
        using var client = new SftpClient(ConnectionInfo(host, port, new ServerLogin { Kind = "sftp", Host = host, Username = "probe" }));
        client.HostKeyReceived += (_, e) =>
        {
            presented = new SshHostKey(e.HostKeyName, SshFingerprint.FromKeyBlob(e.HostKey));
            e.CanTrust = false;
        };
        try
        {
            client.Connect();
        }
        catch (Exception) when (presented is not null)
        {
            // Refusing the key ends the handshake; the key is what we wanted.
        }
        catch (Exception error)
        {
            throw Translate(error, host);
        }
        return presented ?? throw new ConnectorException(ConnectorFailure.SecureConnectionFailed, host);
    }, cancellation);

    /// <summary>Opens a connected client after checking the pinned host key.</summary>
    internal SftpClient Connect()
    {
        var client = new SftpClient(ConnectionInfo(Host, Port, Login));
        ConnectorException? keyFailure = null;
        client.HostKeyReceived += (_, e) =>
        {
            var fingerprint = SshFingerprint.FromKeyBlob(e.HostKey);
            var pinned = _hostKeys.PinnedFingerprint(Host, Port);
            e.CanTrust = pinned == fingerprint;
            if (!e.CanTrust)
            {
                keyFailure = new ConnectorException(pinned is null ? ConnectorFailure.HostKeyUnverified : ConnectorFailure.HostKeyMismatch, Host);
            }
        };
        try
        {
            client.Connect();
            return client;
        }
        catch (Exception error)
        {
            client.Dispose();
            throw keyFailure ?? Translate(error, Host);
        }
    }

    private static ConnectionInfo ConnectionInfo(string host, int port, ServerLogin login)
    {
        var password = new PasswordAuthenticationMethod(login.Username, login.Password);
        var interactive = new KeyboardInteractiveAuthenticationMethod(login.Username);
        interactive.AuthenticationPrompt += (_, e) =>
        {
            foreach (var prompt in e.Prompts) prompt.Response = login.Password;
        };
        var info = new ConnectionInfo(host, port, login.Username, password, interactive)
        {
            Timeout = TimeSpan.FromSeconds(15),
        };
        Harden(info);
        return info;
    }

    /// <summary>Removes the algorithms DIFF.md rules out: SHA-1, CBC, 3DES, and DSA.</summary>
    internal static void Harden(ConnectionInfo info)
    {
        foreach (var name in info.KeyExchangeAlgorithms.Keys
                     .Where(name => name.Contains("sha1", StringComparison.Ordinal) || name.StartsWith("diffie-hellman-group1-", StringComparison.Ordinal))
                     .ToList())
        {
            info.KeyExchangeAlgorithms.Remove(name);
        }
        foreach (var name in info.Encryptions.Keys.Where(name => name.EndsWith("-cbc", StringComparison.Ordinal)).ToList())
        {
            info.Encryptions.Remove(name);
        }
        foreach (var name in info.HmacAlgorithms.Keys.Where(name => name.StartsWith("hmac-sha1", StringComparison.Ordinal)).ToList())
        {
            info.HmacAlgorithms.Remove(name);
        }
        foreach (var name in info.HostKeyAlgorithms.Keys
                     .Where(name => name.StartsWith("ssh-dss", StringComparison.Ordinal) || name.StartsWith("ssh-rsa", StringComparison.Ordinal))
                     .ToList())
        {
            info.HostKeyAlgorithms.Remove(name);
        }
    }

    internal static ConnectorException Translate(Exception error, string host) => error switch
    {
        ConnectorException connector => connector,
        SshAuthenticationException authentication when authentication.Message.Contains("publickey", StringComparison.OrdinalIgnoreCase)
            && !authentication.Message.Contains("password", StringComparison.OrdinalIgnoreCase)
            && !authentication.Message.Contains("keyboard", StringComparison.OrdinalIgnoreCase)
            => new ConnectorException(ConnectorFailure.PasswordLoginUnavailable, host, inner: error),
        SshAuthenticationException => new ConnectorException(ConnectorFailure.AuthenticationFailed, host, inner: error),
        SshConnectionException { DisconnectReason: Renci.SshNet.Messages.Transport.DisconnectReason.KeyExchangeFailed } =>
            new ConnectorException(ConnectorFailure.SecureConnectionFailed, host, inner: error),
        SshException when error.Message.Contains("algorithm", StringComparison.OrdinalIgnoreCase) =>
            new ConnectorException(ConnectorFailure.SecureConnectionFailed, host, inner: error),
        SftpPermissionDeniedException => new ConnectorException(ConnectorFailure.ListingFailed, host, inner: error),
        SshException when error.Message.Contains("subsystem", StringComparison.OrdinalIgnoreCase) =>
            new ConnectorException(ConnectorFailure.SftpUnavailable, host, inner: error),
        _ => new ConnectorException(ConnectorFailure.Unreachable, host, inner: error),
    };

    private T WithClient<T>(Func<SftpClient, T> action)
    {
        lock (_gate)
        {
            for (var attempt = 0; ; attempt++)
            {
                if (_client is not { IsConnected: true })
                {
                    _client?.Dispose();
                    _client = Connect();
                    StartPath ??= _client.WorkingDirectory;
                }
                try
                {
                    return action(_client);
                }
                catch (SshConnectionException) when (attempt == 0)
                {
                    // A dropped connection reconnects once.
                    _client.Dispose();
                    _client = null;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Listing
    // ------------------------------------------------------------------

    public Task ValidateAsync(CancellationToken cancellation) => Task.Run(() =>
    {
        WithClient(client => client.WorkingDirectory);
    }, cancellation);

    public bool CanIndex(string directory) => true;

    public Task<IReadOnlyList<ConnectorEntry>> EnumerateVideosAsync(string folder, CancellationToken cancellation) =>
        ConnectorWalk.VideosAsync(folder, ListAsync, cancellation);

    public Task<IReadOnlyList<ConnectorEntry>> ListAsync(string directory, CancellationToken cancellation) => Task.Run(() =>
    {
        var path = PathOf(directory);
        try
        {
            var files = WithClient(client => client.ListDirectory(path).ToList());
            var entries = new List<ConnectorEntry>();
            foreach (var file in files)
            {
                if (file.Name is "." or "..") continue;
                var isDirectory = file.IsDirectory;
                if (file.IsSymbolicLink)
                {
                    // Follow a link to see whether it names a folder; a broken one is skipped.
                    try
                    {
                        isDirectory = WithClient(client => client.GetAttributes(file.FullName).IsDirectory);
                    }
                    catch (SftpPathNotFoundException)
                    {
                        continue;
                    }
                }
                else if (!isDirectory && !file.IsRegularFile)
                {
                    continue;
                }
                entries.Add(new ConnectorEntry(file.Name, UrlFor(file.FullName, isDirectory), isDirectory)
                {
                    Size = isDirectory ? null : file.Length,
                    Modified = new DateTimeOffset(file.LastWriteTimeUtc, TimeSpan.Zero),
                });
            }
            return ConnectorWalk.Sorted(entries.Where(entry => !entry.IsHidden));
        }
        catch (SftpPathNotFoundException error)
        {
            throw new ConnectorException(ConnectorFailure.ListingFailed, path, inner: error);
        }
        catch (Exception error)
        {
            throw Translate(error, Host);
        }
    }, cancellation);

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    /// <summary>A buffered byte source for a file, with its own connection.</summary>
    public IByteSource ByteSource(string itemUrl)
    {
        var path = PathOf(itemUrl);
        return new BufferedByteSource(Host, () => new SftpBufferedFile(Connect(), path, Host));
    }

    public void Dispose()
    {
        lock (_gate)
        {
            _client?.Dispose();
            _client = null;
        }
    }
}

/// <summary>One SFTP file open on its own connection, read by the buffer's worker thread.</summary>
internal sealed class SftpBufferedFile : IBufferedFile
{
    private readonly SftpClient _client;
    private readonly SftpFileStream _stream;
    private readonly string _path;

    public SftpBufferedFile(SftpClient client, string path, string host)
    {
        _client = client;
        _path = path;
        try
        {
            _stream = client.Open(path, FileMode.Open, FileAccess.Read);
            Size = _stream.Length;
        }
        catch (Exception error)
        {
            client.Dispose();
            throw error is SftpPathNotFoundException
                ? new ConnectorException(ConnectorFailure.NotFound, "SFTP", inner: error)
                : SftpConnector.Translate(error, host);
        }
    }

    public long Size { get; }

    public int Read(long offset, Span<byte> buffer)
    {
        if (_stream.Position != offset) _stream.Seek(offset, SeekOrigin.Begin);
        return _stream.Read(buffer);
    }

    /// <summary>A stat of the open file: a real SFTP round trip, so a dead connection shows.</summary>
    public bool KeepAlive()
    {
        if (!_client.IsConnected) return false;
        _client.GetAttributes(_path);
        return true;
    }

    public void Abort() => _client.Disconnect();

    public void Dispose()
    {
        try
        {
            _stream.Dispose();
        }
        finally
        {
            _client.Dispose();
        }
    }
}
