// S3-compatible buckets: AWS, Backblaze B2, Cloudflare R2, Wasabi, MinIO
// (S3Connector.swift). Requests are signed with AWS Signature Version 4;
// listing is ListObjectsV2 with delimiter=/, so prefixes read as folders;
// files stream through pre-signed GET URLs, re-signed after a 403 for expiry.
// Item URLs are s3://<account>/<bucket>/<key>, where the account key hashes
// the endpoint, bucket, and access key ID; the endpoint, region, and
// addressing style are stored with the key pair in the DPAPI login store.

using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Xml.Linq;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services.Remote;

public sealed class S3Connector : IMediaConnector
{
    public S3Connector(S3Configuration configuration, string accessKeyId, string secretAccessKey, HttpMessageInvoker? client = null)
    {
        Configuration = configuration;
        AccessKeyId = accessKeyId;
        SecretAccessKey = secretAccessKey;
        Client = client ?? RemoteHttp.Shared;
    }

    /// <summary>Rebuilds the connector for a stored source from its saved login.</summary>
    public static S3Connector? FromSource(string sourceUrl, ServerLoginStore logins)
    {
        if (SourceUrl.ParseS3(sourceUrl) is not { } item) return null;
        if (logins.Get(MediaSourceKind.S3, item.Account) is not { S3: { } configuration } login) return null;
        return new S3Connector(configuration, login.Username, login.Password);
    }

    public S3Configuration Configuration { get; }
    public string AccessKeyId { get; }
    private string SecretAccessKey { get; }
    public HttpMessageInvoker Client { get; }
    public Func<DateTimeOffset> Now { get; init; } = () => DateTimeOffset.UtcNow;

    public MediaSourceKind Kind => MediaSourceKind.S3;
    public string AccountKey => SourceUrl.S3AccountKey(Configuration.Endpoint, Configuration.Bucket, AccessKeyId);
    public string Root => SourceUrl.S3(AccountKey, Configuration.Bucket, "");
    public string? AccountLabel => Configuration.Bucket;

    private S3Signer Signer => new(AccessKeyId, SecretAccessKey, Configuration.Region);

    /// <summary>The login to save for this connector's sources.</summary>
    public ServerLogin ToLogin() => new()
    {
        Kind = MediaSourceKind.S3.RawValue(),
        Host = AccountKey,
        Username = AccessKeyId,
        Password = SecretAccessKey,
        S3 = Configuration,
    };

    public Task ValidateAsync(CancellationToken cancellation) => ListAsync(Root, cancellation);

    public Task<IReadOnlyList<ConnectorEntry>> EnumerateVideosAsync(string folder, CancellationToken cancellation) =>
        ConnectorWalk.VideosAsync(folder, ListAsync, cancellation);

    public bool CanIndex(string directory) => true;

    public async Task<IReadOnlyList<ConnectorEntry>> ListAsync(string directory, CancellationToken cancellation)
    {
        if (SourceUrl.ParseS3(directory) is not { IsPrefix: true } item) throw new ConnectorException(ConnectorFailure.InvalidAddress);
        var host = Configuration.EndpointHost;
        if (Configuration.Endpoint.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !LocalNetwork.IsLocalHost(host))
        {
            throw new ConnectorException(ConnectorFailure.InsecureConnection);
        }

        var entries = new List<ConnectorEntry>();
        string? continuation = null;
        do
        {
            var query = new List<KeyValuePair<string, string>>
            {
                new("list-type", "2"),
                new("delimiter", "/"),
                new("max-keys", "1000"),
            };
            if (item.Key.Length > 0) query.Add(new("prefix", item.Key));
            if (continuation is not null) query.Add(new("continuation-token", continuation));

            using var request = new HttpRequestMessage(HttpMethod.Get, BucketUrl(Configuration, "", query));
            Signer.Sign(request, Now());

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
                throw WebDavConnector.ConnectorError(error, host);
            }
            if (status != 200) throw Error(status, body, host);
            var page = ParseListing(body, AccountKey, Configuration.Bucket, item.Key)
                ?? throw new ConnectorException(ConnectorFailure.ListingFailed, item.Key.Length == 0 ? Configuration.Bucket : item.Key);
            entries.AddRange(page.Entries);
            continuation = page.NextContinuationToken;
        }
        while (continuation is not null);
        return ConnectorWalk.Sorted(entries.Where(entry => !entry.IsHidden));
    }

    public sealed record ListingPage(IReadOnlyList<ConnectorEntry> Entries, string? NextContinuationToken);

    /// <summary>
    /// Parses a ListBucketResult: CommonPrefixes are folders and Contents
    /// files; the prefix's own placeholder object is skipped.
    /// </summary>
    public static ListingPage? ParseListing(byte[] data, string account, string bucket, string prefix)
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
        if (root.Name.LocalName != "ListBucketResult") return null;
        var entries = new List<ConnectorEntry>();
        foreach (var common in Children(root, "CommonPrefixes"))
        {
            if (Value(common, "Prefix") is not { } key || key == prefix) continue;
            var name = key[prefix.Length..].TrimEnd('/');
            if (name.Length == 0) continue;
            entries.Add(new ConnectorEntry(name, SourceUrl.S3(account, bucket, key), IsDirectory: true));
        }
        foreach (var content in Children(root, "Contents"))
        {
            if (Value(content, "Key") is not { } key || key == prefix || key.EndsWith('/')) continue;
            if (!key.StartsWith(prefix, StringComparison.Ordinal)) continue;
            var name = key[prefix.Length..];
            if (name.Length == 0 || name.Contains('/')) continue;
            entries.Add(new ConnectorEntry(name, SourceUrl.S3(account, bucket, key), IsDirectory: false)
            {
                Size = long.TryParse(Value(content, "Size"), out var size) ? size : null,
                Modified = DateTimeOffset.TryParse(Value(content, "LastModified"), CultureInfo.InvariantCulture,
                    DateTimeStyles.AssumeUniversal, out var modified) ? modified : null,
            });
        }
        var truncated = Value(root, "IsTruncated") == "true";
        return new ListingPage(entries, truncated ? Value(root, "NextContinuationToken") : null);
    }

    public static ConnectorException Error(int status, byte[] body, string host)
    {
        XElement? root = null;
        try
        {
            root = XDocument.Parse(Encoding.UTF8.GetString(body)).Root;
        }
        catch (Exception)
        {
            // Not an XML error body.
        }
        var code = root is null ? "" : Value(root, "Code") ?? "";
        if (code is "PermanentRedirect" or "AuthorizationHeaderMalformed" || status == 301)
        {
            // The bucket lives in another region than the one entered.
            return new ConnectorException(ConnectorFailure.BucketInAnotherRegion, root is null ? null : Value(root, "Region"));
        }
        if (code == "NoSuchBucket") return new ConnectorException(ConnectorFailure.ListingFailed, host);
        if (status == 403 || code is "InvalidAccessKeyId" or "SignatureDoesNotMatch")
        {
            return new ConnectorException(ConnectorFailure.AuthenticationFailed, host);
        }
        if (status == 404) return new ConnectorException(ConnectorFailure.NotFound, MediaSourceKind.S3.DisplayName());
        return new ConnectorException(ConnectorFailure.ServerError, MediaSourceKind.S3.DisplayName(), status);
    }

    private static IEnumerable<XElement> Children(XElement element, string localName) =>
        element.Elements().Where(child => child.Name.LocalName == localName);

    private static string? Value(XElement element, string localName) =>
        Children(element, localName).FirstOrDefault()?.Value;

    // ------------------------------------------------------------------
    // Addressing
    // ------------------------------------------------------------------

    /// <summary>The HTTPS (or local HTTP) URL of <paramref name="key"/> in the bucket.</summary>
    public static string BucketUrl(S3Configuration configuration, string key, IReadOnlyList<KeyValuePair<string, string>>? query = null)
    {
        var endpoint = configuration.Endpoint.TrimEnd('/');
        var schemeEnd = endpoint.IndexOf("://", StringComparison.Ordinal);
        var scheme = schemeEnd > 0 ? endpoint[..schemeEnd] : "https";
        var rest = schemeEnd > 0 ? endpoint[(schemeEnd + 3)..] : endpoint;
        var slash = rest.IndexOf('/');
        var authority = slash >= 0 ? rest[..slash] : rest;
        var basePath = slash >= 0 ? rest[slash..] : "";
        var encodedKey = string.Join("/", key.Split('/').Select(S3Signer.UriEncode));
        var url = configuration.UsesPathStyle
            ? $"{scheme}://{authority}{basePath}/{S3Signer.UriEncode(configuration.Bucket)}/{encodedKey}"
            : $"{scheme}://{configuration.Bucket}.{authority}{basePath}/{encodedKey}";
        if (query is { Count: > 0 })
        {
            url += "?" + string.Join("&", query.Select(item => $"{S3Signer.UriEncode(item.Key)}={S3Signer.UriEncode(item.Value)}"));
        }
        return url;
    }

    /// <summary>
    /// Path-style addressing unless the endpoint is AWS itself, and always
    /// for bucket names with dots (they break virtual-hosted TLS).
    /// </summary>
    public static bool DefaultUsesPathStyle(string endpoint, string bucket)
    {
        if (bucket.Contains('.')) return true;
        var host = SourceUrl.TrySplit(endpoint, out var parts) ? parts.Host : endpoint;
        return !host.EndsWith("amazonaws.com", StringComparison.OrdinalIgnoreCase);
    }
}

/// <summary>AWS Signature Version 4 for S3.</summary>
public sealed record S3Signer(string AccessKeyId, string SecretAccessKey, string Region, string Service = "s3")
{
    public const string EmptyPayloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    /// <summary>Signs a request with an Authorization header (listing).</summary>
    public void Sign(HttpRequestMessage request, DateTimeOffset date, string payloadHash = EmptyPayloadHash)
    {
        if (request.RequestUri is not { } url || HostHeader(url) is not { } host) return;
        var timestamp = Timestamp(date);
        var day = timestamp[..8];
        // HttpClient sends this Host itself; it is signed, not set.
        request.Headers.Remove("x-amz-date");
        request.Headers.Remove("x-amz-content-sha256");
        request.Headers.TryAddWithoutValidation("x-amz-date", timestamp);
        request.Headers.TryAddWithoutValidation("x-amz-content-sha256", payloadHash);

        var headers = new[] { ("host", host), ("x-amz-content-sha256", payloadHash), ("x-amz-date", timestamp) };
        var signedHeaders = string.Join(";", headers.Select(header => header.Item1));
        var canonical = CanonicalRequest(request.Method.Method, url, headers, signedHeaders, payloadHash);
        var scope = $"{day}/{Region}/{Service}/aws4_request";
        var signature = Signature(StringToSign(timestamp, scope, canonical), day);
        request.Headers.TryAddWithoutValidation("Authorization",
            $"AWS4-HMAC-SHA256 Credential={AccessKeyId}/{scope}, SignedHeaders={signedHeaders}, Signature={signature}");
    }

    /// <summary>A pre-signed GET URL, valid for <paramref name="expires"/> seconds.</summary>
    public string? Presign(string url, DateTimeOffset date, int expires = 3600)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || HostHeader(uri) is not { } host) return null;
        var timestamp = Timestamp(date);
        var day = timestamp[..8];
        var scope = $"{day}/{Region}/{Service}/aws4_request";
        var query = new List<(string, string)>
        {
            ("X-Amz-Algorithm", "AWS4-HMAC-SHA256"),
            ("X-Amz-Credential", $"{AccessKeyId}/{scope}"),
            ("X-Amz-Date", timestamp),
            ("X-Amz-Expires", expires.ToString(CultureInfo.InvariantCulture)),
            ("X-Amz-SignedHeaders", "host"),
        };
        var withoutQuery = url.Split('?')[0];
        var unsigned = new Uri($"{withoutQuery}?{CanonicalQuery(query)}");
        var canonical = CanonicalRequest("GET", unsigned, [("host", host)], "host", "UNSIGNED-PAYLOAD");
        query.Add(("X-Amz-Signature", Signature(StringToSign(timestamp, scope, canonical), day)));
        return $"{withoutQuery}?{CanonicalQuery(query)}";
    }

    public static string CanonicalRequest(string method, Uri url, IReadOnlyList<(string Name, string Value)> headers, string signedHeaders, string payloadHash)
    {
        // The path is already encoded once, segment by segment; S3 signs it as sent.
        var path = url.GetComponents(UriComponents.Path | UriComponents.KeepDelimiter, UriFormat.UriEscaped);
        var rawQuery = url.GetComponents(UriComponents.Query, UriFormat.UriEscaped);
        var canonicalQuery = CanonicalQuery(rawQuery.Split('&', StringSplitOptions.RemoveEmptyEntries).Select(pair =>
        {
            var equals = pair.IndexOf('=');
            var name = Uri.UnescapeDataString(equals < 0 ? pair : pair[..equals]);
            var value = equals < 0 ? "" : Uri.UnescapeDataString(pair[(equals + 1)..]);
            return (name, value);
        }));
        var canonicalHeaders = string.Concat(headers.Select(header => $"{header.Name}:{header.Value.Trim()}\n"));
        return string.Join("\n", method, path.Length == 0 ? "/" : path, canonicalQuery, canonicalHeaders, signedHeaders, payloadHash);
    }

    public static string StringToSign(string timestamp, string scope, string canonicalRequest) =>
        string.Join("\n", "AWS4-HMAC-SHA256", timestamp, scope, Hex(SHA256.HashData(Encoding.UTF8.GetBytes(canonicalRequest))));

    public string Signature(string stringToSign, string day)
    {
        var key = Encoding.UTF8.GetBytes("AWS4" + SecretAccessKey);
        foreach (var part in new[] { day, Region, Service, "aws4_request" })
        {
            key = HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(part));
        }
        return Hex(HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(stringToSign)));
    }

    /// <summary>Sorted by name, then value, with both URI-encoded.</summary>
    public static string CanonicalQuery(IEnumerable<(string Name, string Value)> items) =>
        string.Join("&", items
            .Select(item => (Name: UriEncode(item.Name), Value: UriEncode(item.Value)))
            .OrderBy(item => item.Name, StringComparer.Ordinal)
            .ThenBy(item => item.Value, StringComparer.Ordinal)
            .Select(item => $"{item.Name}={item.Value}"));

    /// <summary>AWS URI encoding: everything but the unreserved characters, as UTF-8.</summary>
    public static string UriEncode(string text)
    {
        var builder = new StringBuilder(text.Length);
        foreach (var value in Encoding.UTF8.GetBytes(text))
        {
            var c = (char)value;
            if (char.IsAsciiLetterOrDigit(c) || c is '-' or '.' or '_' or '~') builder.Append(c);
            else builder.Append('%').Append(value.ToString("X2", CultureInfo.InvariantCulture));
        }
        return builder.ToString();
    }

    public static string? HostHeader(Uri url)
    {
        if (url.Host.Length == 0) return null;
        return url.IsDefaultPort ? url.Host : $"{url.Host}:{url.Port}";
    }

    public static string Timestamp(DateTimeOffset date) =>
        date.UtcDateTime.ToString("yyyyMMdd'T'HHmmss'Z'", CultureInfo.InvariantCulture);

    private static string Hex(byte[] digest) => Convert.ToHexString(digest).ToLowerInvariant();
}

/// <summary>Streams an object through a pre-signed URL, re-signing on refresh.</summary>
public sealed class S3ContentResolver(S3Configuration configuration, string accessKeyId, string secretAccessKey, string key) : IRemoteContentResolver
{
    public MediaSourceKind Kind => MediaSourceKind.S3;
    public bool UsesPreauthorizedLinks => true;
    public HttpMessageInvoker Client => RemoteHttp.Shared;

    public Task<HttpRequestMessage> ContentRequestAsync(bool refresh, CancellationToken cancellation)
    {
        var signer = new S3Signer(accessKeyId, secretAccessKey, configuration.Region);
        var url = signer.Presign(S3Connector.BucketUrl(configuration, key), DateTimeOffset.UtcNow)
            ?? throw new ConnectorException(ConnectorFailure.InvalidAddress);
        return Task.FromResult(new HttpRequestMessage(HttpMethod.Get, url));
    }
}
