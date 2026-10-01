// WebDAV servers: Nextcloud and ownCloud (/remote.php/dav/files/<user>/),
// Synology, QNAP, pCloud, Koofr, or `rclone serve webdav` in front of another
// service (WebDAVConnector.swift). URLs are davs://host[:port]/path for HTTPS
// and dav:// for plain HTTP, which is allowed only for local addresses (.local
// names, unqualified names, and private IP addresses). Anything else must use
// valid HTTPS: self-signed certificates are refused (D10).
//
// Listing is PROPFIND with Depth: 1 (most servers disable infinity), so
// enumeration walks breadth-first. Basic and Digest logins answer the
// server's challenge; the login lives in the DPAPI server-login store.

using System.Globalization;
using System.Net;
using System.Text;
using System.Xml.Linq;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services.Remote;

public sealed class WebDavConnector : IMediaConnector
{
    public const string PropfindBody =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:displayname/></d:prop></d:propfind>";

    private WebDavConnector(string root, ServerLogin? login, HttpMessageInvoker client)
    {
        Root = root;
        Login = login;
        Client = client;
    }

    public MediaSourceKind Kind => MediaSourceKind.WebDav;

    /// <summary>The folder the user entered, canonical (davs:// or dav://, trailing slash).</summary>
    public string Root { get; }

    public ServerLogin? Login { get; }

    /// <summary>The client for this login: each login gets its own (see <see cref="LoginClients"/>).</summary>
    public HttpMessageInvoker Client { get; }

    public string? AccountLabel => Login is { IsGuest: false } login ? login.Username : null;

    public string Host => SourceUrl.TrySplit(Root, out var parts) ? parts.Host : Root;

    /// <summary>Builds a connector from what the user typed: an https:// or http:// address, or a bare host and path.</summary>
    public static WebDavConnector? FromAddress(string address, ServerLogin? login, HttpMessageInvoker? client = null) =>
        CanonicalRoot(address) is { } root ? new WebDavConnector(root, login, client ?? LoginClients.For(root, login)) : null;

    /// <summary>Rebuilds the connector for a stored source with its saved login (a guest when there is none).</summary>
    public static WebDavConnector? FromSource(string sourceUrl, ServerLoginStore logins)
    {
        if (MediaSourceKinds.FromPath(sourceUrl) != MediaSourceKind.WebDav || SourceUrl.CredentialHost(sourceUrl) is not { } host) return null;
        var root = DirectoryUrl(sourceUrl);
        var login = logins.Get(MediaSourceKind.WebDav, host);
        return new WebDavConnector(root, login, LoginClients.For(root, login));
    }

    public Task ValidateAsync(CancellationToken cancellation) => ListAsync(Root, cancellation);

    public Task<IReadOnlyList<ConnectorEntry>> EnumerateVideosAsync(string folder, CancellationToken cancellation) =>
        ConnectorWalk.VideosAsync(folder, ListAsync, cancellation);

    public bool CanIndex(string directory) => true;

    public async Task<IReadOnlyList<ConnectorEntry>> ListAsync(string directory, CancellationToken cancellation)
    {
        directory = DirectoryUrl(directory);
        var httpUrl = HttpUrl(directory) ?? throw new ConnectorException(ConnectorFailure.InvalidAddress);
        var host = SourceUrl.TrySplit(directory, out var parts) ? parts.Host : directory;
        if (httpUrl.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !LocalNetwork.IsLocalHost(host))
        {
            throw new ConnectorException(ConnectorFailure.InsecureConnection);
        }

        using var request = new HttpRequestMessage(new HttpMethod("PROPFIND"), httpUrl)
        {
            Content = new StringContent(PropfindBody, Encoding.UTF8, "application/xml"),
        };
        request.Headers.Add("Depth", "1");

        int status;
        byte[] body;
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        timeout.CancelAfter(TimeSpan.FromSeconds(30));
        try
        {
            using var response = await Client.SendAsync(request, timeout.Token).ConfigureAwait(false);
            status = (int)response.StatusCode;
            body = await response.Content.ReadAsByteArrayAsync(timeout.Token).ConfigureAwait(false);
        }
        catch (Exception error) when (!cancellation.IsCancellationRequested)
        {
            throw ConnectorError(error, host);
        }

        var path = SourceUrl.Decode(parts.EncodedPath);
        switch (status)
        {
            case 207:
                break;
            case 401 or 403:
                throw new ConnectorException(ConnectorFailure.AuthenticationFailed, host);
            default:
                // 200 or 405 here means the address isn't a WebDAV folder.
                throw new ConnectorException(ConnectorFailure.ListingFailed, path);
        }
        var entries = ParseMultistatus(body, httpUrl, directory)
            ?? throw new ConnectorException(ConnectorFailure.ListingFailed, path);
        return ConnectorWalk.Sorted(entries.Where(entry => !entry.IsHidden));
    }

    /// <summary>Entries of a multistatus response, without the listed folder itself.</summary>
    public static List<ConnectorEntry>? ParseMultistatus(byte[] data, string requestUrl, string canonicalDirectory)
    {
        XElement root;
        try
        {
            root = XDocument.Parse(Encoding.UTF8.GetString(data)).Root!;
        }
        catch (Exception)
        {
            return null;
        }
        if (root.Name.LocalName != "multistatus" || !Uri.TryCreate(requestUrl, UriKind.Absolute, out var baseUri)) return null;
        if (!SourceUrl.TrySplit(canonicalDirectory, out var canonical)) return null;
        var listedPath = NormalizedPath(Uri.UnescapeDataString(baseUri.AbsolutePath));

        var entries = new List<ConnectorEntry>();
        foreach (var response in Children(root, "response"))
        {
            if (Value(response, "href") is not { } href || Resolve(href, baseUri) is not { } resolved) continue;
            var path = Uri.UnescapeDataString(resolved.AbsolutePath);
            if (NormalizedPath(path) == listedPath) continue;

            // Properties can be split across several propstat blocks; take the 200 ones.
            var props = Children(response, "propstat")
                .Where(propstat => Value(propstat, "status") is not { } state || state.Contains(" 200", StringComparison.Ordinal))
                .SelectMany(propstat => Children(propstat, "prop"))
                .ToList();
            var isCollection = props.Any(prop => Children(prop, "resourcetype").Any(type => Children(type, "collection").Any()));
            var size = props.Select(prop => Value(prop, "getcontentlength"))
                .Select(text => long.TryParse(text, out var value) ? value : (long?)null)
                .FirstOrDefault(value => value is not null);
            var modified = props.Select(prop => Value(prop, "getlastmodified"))
                .Select(ParseHttpDate)
                .FirstOrDefault(value => value is not null);

            var segments = path.Split('/', StringSplitOptions.RemoveEmptyEntries);
            if (segments.Length == 0) continue;
            var url = SourceUrl.Server(canonical.Scheme, canonical.Host, canonical.Port, segments, isCollection);
            if (url is null) continue;
            entries.Add(new ConnectorEntry(segments[^1], url, isCollection)
            {
                Size = isCollection ? null : size,
                Modified = modified,
            });
        }
        return entries;
    }

    private static IEnumerable<XElement> Children(XElement element, string localName) =>
        element.Elements().Where(child => child.Name.LocalName == localName);

    private static string? Value(XElement element, string localName) =>
        Children(element, localName).FirstOrDefault() is { } child ? child.Value.Trim() : null;

    /// <summary>An href is an absolute URL or an absolute path; some servers send raw spaces.</summary>
    private static Uri? Resolve(string href, Uri baseUri) =>
        Uri.TryCreate(baseUri, href.Trim(), out var resolved) ? resolved : null;

    private static string NormalizedPath(string path)
    {
        var trimmed = path.TrimEnd('/');
        return trimmed.Length == 0 ? "/" : trimmed;
    }

    /// <summary>RFC 1123 dates ("Tue, 15 Nov 1994 12:45:26 GMT"), the WebDAV format.</summary>
    public static DateTimeOffset? ParseHttpDate(string? text) =>
        DateTimeOffset.TryParseExact(text?.Trim(), "r", CultureInfo.InvariantCulture,
            DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var date)
            ? date
            : null;

    // ------------------------------------------------------------------
    // Addresses
    // ------------------------------------------------------------------

    /// <summary>https://host/path → davs://host/path/; http://… → dav://…. A bare host means HTTPS. Credentials are dropped.</summary>
    public static string? CanonicalRoot(string address)
    {
        var text = address.Trim();
        if (text.Length == 0) return null;
        if (!text.Contains("://", StringComparison.Ordinal)) text = "https://" + text;
        var schemeEnd = text.IndexOf("://", StringComparison.Ordinal);
        var scheme = text[..schemeEnd].ToLowerInvariant() switch
        {
            "https" or "davs" or "webdavs" => "davs",
            "http" or "dav" or "webdav" => "dav",
            _ => null,
        };
        if (scheme is null) return null;
        // Parse as HTTP so the default port and path normalize the usual way.
        var httpText = (scheme == "davs" ? "https" : "http") + text[schemeEnd..];
        if (!Uri.TryCreate(httpText, UriKind.Absolute, out var uri) || uri.Host.Length == 0) return null;
        var host = uri.HostNameType == UriHostNameType.IPv6 ? $"[{uri.IdnHost.Trim('[', ']')}]" : uri.IdnHost;
        var authority = uri.IsDefaultPort ? host : $"{host}:{uri.Port}";
        var path = uri.AbsolutePath;
        if (!path.EndsWith('/')) path += "/";
        return $"{scheme}://{authority}{path}";
    }

    /// <summary>Collections are always addressed with a trailing slash; servers redirect the bare form.</summary>
    public static string DirectoryUrl(string url)
    {
        var query = url.IndexOf('?');
        var path = query >= 0 ? url[..query] : url;
        return path.EndsWith('/') ? url : path + "/" + (query >= 0 ? url[query..] : "");
    }

    /// <summary>The HTTP address behind a canonical dav(s):// URL.</summary>
    public static string? HttpUrl(string url)
    {
        if (url.StartsWith("davs://", StringComparison.OrdinalIgnoreCase)) return "https://" + url[7..];
        if (url.StartsWith("dav://", StringComparison.OrdinalIgnoreCase)) return "http://" + url[6..];
        return null;
    }

    internal static Exception ConnectorError(Exception error, string host) => error switch
    {
        ConnectorException => error,
        OperationCanceledException => new ConnectorException(ConnectorFailure.Unreachable, host, inner: error),
        _ when ProviderResponse.IsCertificateFailure(error) => new ConnectorException(ConnectorFailure.CertificateInvalid, host, inner: error),
        _ => new ConnectorException(ConnectorFailure.Unreachable, host, inner: error),
    };

    /// <summary>
    /// One client per login (guests share one). A client keeps the login a
    /// server accepted, so with one shared client a wrong or missing password
    /// would still "connect" with an earlier login. Credentials are scoped to
    /// the server's origin, so a redirect elsewhere never receives them.
    /// </summary>
    internal static class LoginClients
    {
        private const int Limit = 8;
        private static readonly object Gate = new();
        private static readonly Dictionary<string, HttpClient> Clients = [];
        private static readonly List<string> Order = [];

        public static HttpMessageInvoker For(string canonicalUrl, ServerLogin? login)
        {
            var origin = HttpUrl(canonicalUrl) is { } http && Uri.TryCreate(http, UriKind.Absolute, out var uri)
                ? uri.GetLeftPart(UriPartial.Authority)
                : canonicalUrl;
            var guest = login is null || login.IsGuest;
            var key = guest ? "guest" : $"{origin}\n{login!.Username}\n{login.Password}";
            lock (Gate)
            {
                if (Clients.TryGetValue(key, out var existing)) return existing;
                ICredentials? credentials = null;
                if (!guest)
                {
                    var credential = new NetworkCredential(login!.Username, login.Password);
                    var cache = new CredentialCache();
                    var originUri = new Uri(origin + "/");
                    cache.Add(originUri, "Basic", credential);
                    cache.Add(originUri, "Digest", credential);
                    credentials = cache;
                }
                var client = RemoteHttp.Create(credentials);
                Clients[key] = client;
                Order.Add(key);
                if (Order.Count > Limit)
                {
                    var dropped = Order[0];
                    Order.RemoveAt(0);
                    // Requests under way on a dropped client still finish; it
                    // is left for the garbage collector rather than disposed.
                    Clients.Remove(dropped);
                }
                return client;
            }
        }
    }
}

/// <summary>Streams a WebDAV file with Range GETs, answering login challenges.</summary>
public sealed class WebDavContentResolver(string fileUrl, HttpMessageInvoker client) : IRemoteContentResolver
{
    public MediaSourceKind Kind => MediaSourceKind.WebDav;
    public bool UsesPreauthorizedLinks => false;
    public HttpMessageInvoker Client => client;

    public Task<HttpRequestMessage> ContentRequestAsync(bool refresh, CancellationToken cancellation)
    {
        var url = WebDavConnector.HttpUrl(fileUrl) ?? throw new ConnectorException(ConnectorFailure.InvalidAddress);
        if (url.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
            && !LocalNetwork.IsLocalHost(SourceUrl.CredentialHost(fileUrl) ?? ""))
        {
            throw new ConnectorException(ConnectorFailure.InsecureConnection);
        }
        return Task.FromResult(new HttpRequestMessage(HttpMethod.Get, url));
    }
}

/// <summary>Which hosts plain HTTP may reach: the local network only.</summary>
public static class LocalNetwork
{
    /// <summary>
    /// .local and .home.arpa names, unqualified names (no dot), loopback,
    /// and private, link-local, or CGNAT (Tailscale) addresses.
    /// </summary>
    public static bool IsLocalHost(string host)
    {
        host = host.Trim('[', ']').TrimEnd('.').ToLowerInvariant();
        if (host.Length == 0) return false;
        if (IPAddress.TryParse(host, out var address)) return IsLocalAddress(address);
        return host == "localhost" || !host.Contains('.') || host.EndsWith(".local", StringComparison.Ordinal)
            || host.EndsWith(".home.arpa", StringComparison.Ordinal) || host.EndsWith(".lan", StringComparison.Ordinal);
    }

    private static bool IsLocalAddress(IPAddress address)
    {
        if (IPAddress.IsLoopback(address)) return true;
        if (address.IsIPv4MappedToIPv6) address = address.MapToIPv4();
        if (address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6)
        {
            return address.IsIPv6LinkLocal || address.IsIPv6SiteLocal || address.IsIPv6UniqueLocal;
        }
        var bytes = address.GetAddressBytes();
        return bytes[0] == 10
            || (bytes[0] == 172 && bytes[1] is >= 16 and <= 31)
            || (bytes[0] == 192 && bytes[1] == 168)
            || (bytes[0] == 169 && bytes[1] == 254)
            || (bytes[0] == 100 && bytes[1] is >= 64 and <= 127);
    }
}
