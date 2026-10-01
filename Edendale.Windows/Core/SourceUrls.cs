using System.Security.Cryptography;
using System.Text;

namespace Edendale.Windows.Core;

/// <summary>
/// Where a library source's files live (DIFF.md §3.12). The raw values are
/// persisted on <c>LibraryFolder.Kind</c> and match every other branch, so a
/// case must never be renamed.
/// </summary>
public enum MediaSourceKind
{
    /// <summary>A folder on this PC.</summary>
    Local,
    /// <summary>An SMB share, stored as a UNC path and read by Windows' SMB client.</summary>
    Smb,
    /// <summary>An NFS export, played through LibVLC's NFS module.</summary>
    Nfs,
    /// <summary>A folder on an SSH server, read over SFTP.</summary>
    Sftp,
    /// <summary>A WebDAV server: Nextcloud, ownCloud, Synology, QNAP, or rclone.</summary>
    WebDav,
    /// <summary>An S3-compatible bucket: AWS, Backblaze B2, Cloudflare R2, Wasabi, MinIO.</summary>
    S3,
    GoogleDrive,
    OneDrive,
    Dropbox,
}

public static class MediaSourceKinds
{
    /// <summary>Every kind, in the persisted order.</summary>
    public static readonly MediaSourceKind[] All =
    [
        MediaSourceKind.Local, MediaSourceKind.Smb, MediaSourceKind.Nfs, MediaSourceKind.Sftp,
        MediaSourceKind.WebDav, MediaSourceKind.S3, MediaSourceKind.GoogleDrive,
        MediaSourceKind.OneDrive, MediaSourceKind.Dropbox,
    ];

    public static string RawValue(this MediaSourceKind kind) => kind switch
    {
        MediaSourceKind.Local => "local",
        MediaSourceKind.Smb => "smb",
        MediaSourceKind.Nfs => "nfs",
        MediaSourceKind.Sftp => "sftp",
        MediaSourceKind.WebDav => "webdav",
        MediaSourceKind.S3 => "s3",
        MediaSourceKind.GoogleDrive => "gdrive",
        MediaSourceKind.OneDrive => "onedrive",
        MediaSourceKind.Dropbox => "dropbox",
        _ => "local",
    };

    public static MediaSourceKind? FromRawValue(string? raw) =>
        All.Cast<MediaSourceKind?>().FirstOrDefault(kind => kind!.Value.RawValue() == raw);

    /// <summary>The kind an item URL belongs to, from its scheme.</summary>
    public static MediaSourceKind? FromScheme(string? scheme) => scheme?.ToLowerInvariant() switch
    {
        "file" => MediaSourceKind.Local,
        "smb" or "smb2" => MediaSourceKind.Smb,
        "nfs" => MediaSourceKind.Nfs,
        "sftp" => MediaSourceKind.Sftp,
        "dav" or "davs" => MediaSourceKind.WebDav,
        "s3" => MediaSourceKind.S3,
        "gdrive" => MediaSourceKind.GoogleDrive,
        "onedrive" => MediaSourceKind.OneDrive,
        "dropbox" => MediaSourceKind.Dropbox,
        _ => null,
    };

    /// <summary>
    /// The kind of a stored path: a canonical URL by its scheme, a UNC path
    /// as SMB, and anything else as a local folder.
    /// </summary>
    public static MediaSourceKind FromPath(string path)
    {
        if (SourceUrl.TrySplit(path, out var parts) && FromScheme(parts.Scheme) is { } kind) return kind;
        return path.StartsWith(@"\\", StringComparison.Ordinal) ? MediaSourceKind.Smb : MediaSourceKind.Local;
    }

    /// <summary>The scheme of this kind's item URLs; null for local folders and UNC shares.</summary>
    public static string? Scheme(this MediaSourceKind kind) => kind switch
    {
        MediaSourceKind.Nfs => "nfs",
        MediaSourceKind.Sftp => "sftp",
        MediaSourceKind.WebDav => "davs",
        MediaSourceKind.S3 => "s3",
        MediaSourceKind.GoogleDrive => "gdrive",
        MediaSourceKind.OneDrive => "onedrive",
        MediaSourceKind.Dropbox => "dropbox",
        _ => null,
    };

    /// <summary>The provider's name as rows show it ("SMB", "Local Folder").</summary>
    public static string DisplayName(this MediaSourceKind kind) => kind switch
    {
        MediaSourceKind.Local => AppText.Get("SourceKind_Local"),
        MediaSourceKind.Smb => "SMB",
        MediaSourceKind.Nfs => "NFS",
        MediaSourceKind.Sftp => "SFTP",
        MediaSourceKind.WebDav => "WebDAV",
        MediaSourceKind.S3 => AppText.Get("SourceKind_S3"),
        MediaSourceKind.GoogleDrive => "Google Drive",
        MediaSourceKind.OneDrive => "OneDrive",
        MediaSourceKind.Dropbox => "Dropbox",
        _ => kind.ToString(),
    };

    public static bool IsRemote(this MediaSourceKind kind) => kind != MediaSourceKind.Local;

    /// <summary>Linked through an OAuth account rather than a server login.</summary>
    public static bool IsCloudAccount(this MediaSourceKind kind) =>
        kind is MediaSourceKind.GoogleDrive or MediaSourceKind.OneDrive or MediaSourceKind.Dropbox;

    /// <summary>Reached with a saved server login.</summary>
    public static bool UsesServerLogin(this MediaSourceKind kind) =>
        kind is MediaSourceKind.Smb or MediaSourceKind.Sftp or MediaSourceKind.WebDav or MediaSourceKind.S3;

    /// <summary>Streams through the HTTP range reader rather than a file-sharing client.</summary>
    public static bool StreamsOverHttp(this MediaSourceKind kind) =>
        kind is MediaSourceKind.WebDav or MediaSourceKind.S3
            or MediaSourceKind.GoogleDrive or MediaSourceKind.OneDrive or MediaSourceKind.Dropbox;

    /// <summary>
    /// Listing a remote source is network traffic (and metered API calls for
    /// cloud accounts), so the automatic rescan on each library visit is
    /// throttled for every remote kind.
    /// </summary>
    public static bool ThrottlesAutomaticRescans(this MediaSourceKind kind) => kind.IsRemote();
}

/// <summary>
/// Canonical, credential-free URLs for remote library items and folders
/// (SourceURL.swift). These are what <c>LibraryMovie.FilePath</c>,
/// <c>LibraryEpisode.FilePath</c>, and <c>LibraryFolder.Path</c> persist for
/// remote kinds, so their shapes must stay stable:
/// <code>
///   nfs://host/export/path/Name.ext
///   sftp://host[:port]/path/Name.ext
///   davs://host[:port]/path/Name.ext        (dav:// for plain HTTP)
///   s3://&lt;account&gt;/&lt;bucket&gt;/&lt;key path&gt;/Name.ext
///   gdrive://&lt;account&gt;/&lt;fileId&gt;/Name.ext
///   onedrive://&lt;account&gt;/&lt;driveId&gt;/&lt;itemId&gt;/Name.ext
///   dropbox://&lt;account&gt;/&lt;fileId&gt;/Name.ext    (the percent-encoded id:…)
/// </code>
/// SMB stays a UNC path on Windows, which Windows' own SMB client reads.
/// Every item URL ends with the real file name, so the filename parser and
/// the extension filter work unchanged, and the parser still runs before any
/// metadata lookup. For account providers the host is an account key, which
/// is also the key their login is stored under.
/// </summary>
public static class SourceUrl
{
    // ------------------------------------------------------------------
    // Account keys
    // ------------------------------------------------------------------

    /// <summary>
    /// The first 32 hex digits of SHA-256("kind:subject"), where the subject
    /// is Google's <c>sub</c>, the Microsoft user <c>id</c>, Dropbox's
    /// <c>account_id</c>, or for S3 the endpoint, bucket, and access key ID.
    /// Hostname-safe, identical on every device, and never an email address.
    /// </summary>
    public static string AccountKey(MediaSourceKind kind, string subject)
    {
        var digest = SHA256.HashData(Encoding.UTF8.GetBytes($"{kind.RawValue()}:{subject}"));
        return Convert.ToHexString(digest, 0, 16).ToLowerInvariant();
    }

    /// <summary>The account key for an S3 source.</summary>
    public static string S3AccountKey(string endpoint, string bucket, string accessKeyId)
    {
        var normalized = endpoint.ToLowerInvariant().Trim('/');
        return AccountKey(MediaSourceKind.S3, $"{normalized}|{bucket}|{accessKeyId}");
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    /// <summary>
    /// An account-provider URL: <c>scheme://account/id/…/name</c>. <paramref name="ids"/>
    /// are the provider identifiers before the name (one for Drive and
    /// Dropbox, drive then item for OneDrive). <paramref name="query"/>
    /// carries listing hints on folder URLs only.
    /// </summary>
    public static string AccountItem(
        MediaSourceKind kind,
        string account,
        IReadOnlyList<string> ids,
        string name,
        IReadOnlyList<KeyValuePair<string, string>>? query = null)
    {
        if (!kind.IsCloudAccount() && kind != MediaSourceKind.S3)
        {
            throw new ArgumentException("AccountItem is for account-keyed kinds.", nameof(kind));
        }
        var path = "/" + string.Join("/", ids.Append(name).Select(EncodeSegment));
        var url = $"{kind.Scheme()}://{account}{path}";
        if (query is { Count: > 0 })
        {
            url += "?" + string.Join("&", query.Select(item =>
                $"{Uri.EscapeDataString(item.Key)}={Uri.EscapeDataString(item.Value)}"));
        }
        return url;
    }

    /// <summary>
    /// An S3 object or prefix URL: <c>s3://account/bucket/key</c>. A prefix
    /// (folder) key ends in <c>/</c>, as S3 itself spells it.
    /// </summary>
    public static string S3(string account, string bucket, string key)
    {
        var segments = new[] { bucket }.Concat(key.Split('/'));
        return $"s3://{account}/" + string.Join("/", segments.Select(EncodeSegment));
    }

    /// <summary>
    /// A server URL (NFS, SFTP, WebDAV): each path segment is percent-encoded
    /// on its own. Returns null for an empty host.
    /// </summary>
    public static string? Server(
        string scheme,
        string host,
        int? port,
        IEnumerable<string> pathSegments,
        bool isDirectory = false)
    {
        host = host.Trim();
        if (host.Length == 0 || host.Any(c => c is '/' or '?' or '#' or '@' or ' ')) return null;
        if (host.Contains(':') && !host.StartsWith('[')) host = $"[{host}]";
        var path = "/" + string.Join("/", pathSegments.Where(segment => segment.Length > 0).Select(EncodeSegment));
        if (isDirectory && !path.EndsWith('/')) path += "/";
        var authority = port is int value ? $"{host}:{value}" : host;
        return $"{scheme}://{authority}{path}";
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    /// <summary>The pieces of a <c>scheme://host[:port]/path?query</c> string, still percent-encoded.</summary>
    public readonly record struct Parts(string Scheme, string Host, int? Port, string EncodedPath, string? Query);

    /// <summary>
    /// Splits a URL without <see cref="Uri"/>, which rewrites paths and
    /// lowercases hosts in ways that would change the stored shape.
    /// </summary>
    public static bool TrySplit(string url, out Parts parts)
    {
        parts = default;
        var schemeEnd = url.IndexOf("://", StringComparison.Ordinal);
        if (schemeEnd <= 0) return false;
        var scheme = url[..schemeEnd];
        if (!scheme.All(c => char.IsAsciiLetterOrDigit(c) || c is '+' or '-' or '.') || !char.IsAsciiLetter(scheme[0])) return false;

        var rest = url[(schemeEnd + 3)..];
        string? query = null;
        var queryStart = rest.IndexOf('?');
        if (queryStart >= 0)
        {
            query = rest[(queryStart + 1)..];
            rest = rest[..queryStart];
        }
        var fragment = rest.IndexOf('#');
        if (fragment >= 0) rest = rest[..fragment];

        var pathStart = rest.IndexOf('/');
        var authority = pathStart >= 0 ? rest[..pathStart] : rest;
        var path = pathStart >= 0 ? rest[pathStart..] : "";
        var at = authority.LastIndexOf('@');
        if (at >= 0) authority = authority[(at + 1)..];

        string host = authority;
        int? port = null;
        if (authority.StartsWith('['))
        {
            var close = authority.IndexOf(']');
            if (close < 0) return false;
            host = authority[1..close];
            var after = authority[(close + 1)..];
            if (after.StartsWith(':') && int.TryParse(after[1..], out var bracketPort)) port = bracketPort;
        }
        else
        {
            var colon = authority.LastIndexOf(':');
            if (colon >= 0)
            {
                if (!int.TryParse(authority[(colon + 1)..], out var value)) return false;
                host = authority[..colon];
                port = value;
            }
        }

        parts = new Parts(scheme, host, port, path, query);
        return true;
    }

    /// <summary>Whether a stored path is a canonical URL rather than a file-system or UNC path.</summary>
    public static bool IsUrl(string path) => TrySplit(path, out _);

    /// <summary>The parts of an account-provider URL.</summary>
    public sealed record AccountItemParts(
        MediaSourceKind Kind,
        string Account,
        IReadOnlyList<string> Ids,
        string Name,
        IReadOnlyList<KeyValuePair<string, string>> Query)
    {
        public string? QueryValue(string name) =>
            Query.FirstOrDefault(item => item.Key == name) is { Key: not null } match ? match.Value : null;
    }

    /// <summary>Parses gdrive:, onedrive:, and dropbox: URLs; null for anything else.</summary>
    public static AccountItemParts? ParseAccountItem(string url)
    {
        if (!TrySplit(url, out var parts)) return null;
        if (MediaSourceKinds.FromScheme(parts.Scheme) is not { } kind || !kind.IsCloudAccount()) return null;
        if (parts.Host.Length == 0) return null;

        var segments = parts.EncodedPath.Split('/').Skip(1).Select(Decode).ToList();
        var idCount = kind == MediaSourceKind.OneDrive ? 2 : 1;
        if (segments.Count != idCount + 1 || segments.Any(segment => segment.Length == 0)) return null;

        return new AccountItemParts(kind, parts.Host, segments.Take(idCount).ToList(), segments[idCount], ParseQuery(parts.Query));
    }

    /// <summary>The parts of an S3 URL. A prefix ends in "/"; the bucket root is "".</summary>
    public sealed record S3Parts(string Account, string Bucket, string Key)
    {
        public bool IsPrefix => Key.Length == 0 || Key.EndsWith('/');
    }

    public static S3Parts? ParseS3(string url)
    {
        if (!TrySplit(url, out var parts) || MediaSourceKinds.FromScheme(parts.Scheme) != MediaSourceKind.S3) return null;
        if (parts.Host.Length == 0) return null;
        var segments = parts.EncodedPath.Split('/').Skip(1).Select(Decode).ToList();
        if (segments.Count == 0 || segments[0].Length == 0) return null;
        return new S3Parts(parts.Host, segments[0], string.Join("/", segments.Skip(1)));
    }

    /// <summary>The decoded path segments of a server URL (NFS, SFTP, WebDAV).</summary>
    public static IReadOnlyList<string> PathSegments(string url) =>
        TrySplit(url, out var parts)
            ? parts.EncodedPath.Split('/', StringSplitOptions.RemoveEmptyEntries).Select(Decode).ToList()
            : [];

    /// <summary>
    /// The key a source's login is stored under: the host for server kinds,
    /// the account key for account kinds (also the URL host). UNC paths give
    /// their server, as the SMB login store keys them.
    /// </summary>
    public static string? CredentialHost(string path)
    {
        if (TrySplit(path, out var parts)) return parts.Host.Length > 0 ? parts.Host.ToLowerInvariant() : null;
        if (!path.StartsWith(@"\\", StringComparison.Ordinal)) return null;
        var host = path.TrimStart('\\').Split('\\', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault();
        return string.IsNullOrWhiteSpace(host) ? null : host.ToLowerInvariant();
    }

    /// <summary>
    /// The real file name at the end of a stored path: the decoded last URL
    /// segment, or the file name of a local or UNC path.
    /// </summary>
    public static string FileName(string path)
    {
        if (TrySplit(path, out var parts))
        {
            var segments = parts.EncodedPath.Split('/', StringSplitOptions.RemoveEmptyEntries);
            return segments.Length > 0 ? Decode(segments[^1]) : parts.Host;
        }
        var trimmed = path.TrimEnd('\\', '/');
        var cut = trimmed.LastIndexOfAny(['\\', '/']);
        return cut >= 0 ? trimmed[(cut + 1)..] : trimmed;
    }

    /// <summary>The folder a stored item path sits in: its parent URL or directory.</summary>
    public static string? Parent(string path)
    {
        if (TrySplit(path, out var parts))
        {
            var trimmed = parts.EncodedPath.TrimEnd('/');
            var cut = trimmed.LastIndexOf('/');
            if (cut < 0) return null;
            var authority = parts.Port is int port ? $"{FormatHost(parts.Host)}:{port}" : FormatHost(parts.Host);
            return $"{parts.Scheme}://{authority}{trimmed[..(cut + 1)]}";
        }
        var local = path.TrimEnd('\\', '/');
        var index = local.LastIndexOfAny(['\\', '/']);
        return index > 0 ? local[..index] : null;
    }

    private static string FormatHost(string host) => host.Contains(':') ? $"[{host}]" : host;

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    /// <summary>
    /// RFC 3986 unreserved characters plus the sub-delimiters that are safe
    /// inside one path segment, ASCII only. "/", ":", ";", "?", "#", "%", and
    /// spaces are always escaped, so a name can never split a segment.
    /// </summary>
    private const string SegmentAllowed =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,=@";

    public static string EncodeSegment(string segment)
    {
        var builder = new StringBuilder(segment.Length);
        foreach (var value in Encoding.UTF8.GetBytes(segment))
        {
            var c = (char)value;
            if (value < 0x80 && SegmentAllowed.Contains(c)) builder.Append(c);
            else builder.Append('%').Append(value.ToString("X2"));
        }
        return builder.ToString();
    }

    /// <summary>Percent-decodes one segment; malformed escapes are kept as written.</summary>
    public static string Decode(string encoded)
    {
        if (!encoded.Contains('%')) return encoded;
        try
        {
            return Uri.UnescapeDataString(encoded);
        }
        catch (UriFormatException)
        {
            return encoded;
        }
    }

    private static IReadOnlyList<KeyValuePair<string, string>> ParseQuery(string? query)
    {
        if (string.IsNullOrEmpty(query)) return [];
        return query.Split('&', StringSplitOptions.RemoveEmptyEntries)
            .Select(pair =>
            {
                var equals = pair.IndexOf('=');
                return equals < 0
                    ? new KeyValuePair<string, string>(Decode(pair), "")
                    : new KeyValuePair<string, string>(Decode(pair[..equals]), Decode(pair[(equals + 1)..]));
            })
            .ToList();
    }
}
