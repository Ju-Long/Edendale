// SMB credentials keyed by server host. Passwords are secrets, so the whole
// store is persisted DPAPI-protected (current user) in
// %LOCALAPPDATA%\Edendale\smb-credentials.bin — same treatment as the TMDB
// session — and never enters the OneDrive replica.

using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace Edendale.Windows.Services;

public sealed record SmbCredentials(string Host, string Username, string Password);

public sealed class SmbCredentialsStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    private readonly object _gate = new();
    private readonly Dictionary<string, SmbCredentials> _byHost = new(StringComparer.OrdinalIgnoreCase);

    public SmbCredentialsStore()
    {
        try
        {
            if (!File.Exists(AppPaths.SmbCredentialsFile)) return;
            var protectedBytes = File.ReadAllBytes(AppPaths.SmbCredentialsFile);
            var json = Encoding.UTF8.GetString(
                ProtectedData.Unprotect(protectedBytes, null, DataProtectionScope.CurrentUser));
            var records = JsonSerializer.Deserialize<List<SmbCredentials>>(json, JsonOptions) ?? [];
            foreach (var record in records) _byHost[record.Host] = record;
        }
        catch
        {
            // Unreadable store (or another user's DPAPI blob) — start empty.
        }
    }

    /// <summary>Server host of a UNC path (\\server\share\… → server), or null.</summary>
    public static string? HostFromUncPath(string path)
    {
        if (!path.StartsWith(@"\\", StringComparison.Ordinal)) return null;
        var host = path.TrimStart('\\').Split('\\', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault();
        return string.IsNullOrWhiteSpace(host) ? null : host;
    }

    /// <summary>\\server\share root of a UNC path, or null for local paths.</summary>
    public static string? ShareFromUncPath(string path)
    {
        if (!path.StartsWith(@"\\", StringComparison.Ordinal)) return null;
        var parts = path.TrimStart('\\').Split('\\', StringSplitOptions.RemoveEmptyEntries);
        return parts.Length >= 2 ? $@"\\{parts[0]}\{parts[1]}" : null;
    }

    public SmbCredentials? Get(string host)
    {
        lock (_gate) return _byHost.GetValueOrDefault(host);
    }

    public void Save(string host, string username, string password)
    {
        lock (_gate) _byHost[host] = new SmbCredentials(host, username, password);
        Persist();
    }

    public void Remove(string host)
    {
        lock (_gate) _byHost.Remove(host);
        Persist();
    }

    private void Persist()
    {
        try
        {
            List<SmbCredentials> snapshot;
            lock (_gate) snapshot = [.. _byHost.Values];
            var json = JsonSerializer.Serialize(snapshot, JsonOptions);
            var protectedBytes = ProtectedData.Protect(
                Encoding.UTF8.GetBytes(json), null, DataProtectionScope.CurrentUser);
            File.WriteAllBytes(AppPaths.SmbCredentialsFile, protectedBytes);
        }
        catch
        {
            // Credentials stay usable in-memory for this session.
        }
    }
}
