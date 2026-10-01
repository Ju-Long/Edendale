// A dependency-free OAuth 2.0 client for public (secret-less) apps
// (OAuthClient.swift): the authorization-code flow with PKCE (RFC 7636, S256)
// through the system browser and a loopback redirect (RFC 8252), refresh, and
// a single-flight token provider. No SDKs and no client secrets: client IDs
// and app keys come from the gitignored secrets.json (tools/Edendale.Secrets),
// and an empty value hides that provider. Nothing here logs tokens or puts
// them into error messages.

using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services.Remote;

public static class Pkce
{
    /// <summary>A 43-character verifier from 32 random bytes, in the unreserved alphabet.</summary>
    public static string MakeVerifier() => Base64Url(RandomNumberGenerator.GetBytes(32));

    /// <summary>BASE64URL(SHA256(ASCII(verifier))), the S256 challenge.</summary>
    public static string Challenge(string verifier) => Base64Url(SHA256.HashData(Encoding.ASCII.GetBytes(verifier)));

    /// <summary>An unguessable state value tying the callback to this request.</summary>
    public static string MakeState() => Base64Url(RandomNumberGenerator.GetBytes(16));

    public static string Base64Url(byte[] data) =>
        Convert.ToBase64String(data).Replace('+', '-').Replace('/', '_').TrimEnd('=');
}

public sealed record OAuthConfiguration
{
    public required MediaSourceKind Kind { get; init; }
    public required string ClientId { get; init; }
    public required string AuthorizationEndpoint { get; init; }
    public required string TokenEndpoint { get; init; }
    public required IReadOnlyList<string> Scopes { get; init; }

    /// <summary>Extra authorization-request parameters.</summary>
    public IReadOnlyDictionary<string, string> AdditionalAuthorizationParameters { get; init; } = new Dictionary<string, string>();

    /// <summary>Microsoft wants the scopes repeated when redeeming and refreshing.</summary>
    public bool SendsScopeToTokenEndpoint { get; init; }

    /// <summary>
    /// The loopback port the provider's app registration names, or null for
    /// any free port (Microsoft ignores the port of a loopback redirect).
    /// </summary>
    public int? LoopbackPort { get; init; }

    public string ProviderName => Kind.DisplayName();
}

public sealed record OAuthTokenResponse
{
    [JsonPropertyName("access_token")] public string AccessToken { get; init; } = "";
    [JsonPropertyName("token_type")] public string? TokenType { get; init; }
    [JsonPropertyName("expires_in")] public int? ExpiresIn { get; init; }
    [JsonPropertyName("refresh_token")] public string? RefreshToken { get; init; }
    [JsonPropertyName("scope")] public string? Scope { get; init; }
    /// <summary>Dropbox returns the account with the token.</summary>
    [JsonPropertyName("account_id")] public string? AccountId { get; init; }

    public IReadOnlyList<string>? GrantedScopes => Scope?.Split(' ', StringSplitOptions.RemoveEmptyEntries);
}

public enum OAuthFailure
{
    NotConfigured,
    Cancelled,
    StateMismatch,
    MissingAuthorizationCode,
    /// <summary>The user declined on the consent page.</summary>
    AuthorizationDenied,
    /// <summary>The refresh token was revoked or expired: sign in again.</summary>
    InvalidGrant,
    Server,
    Http,
    MalformedResponse,
}

public sealed class OAuthException(OAuthFailure failure, string provider, string? code = null, string? description = null, int? status = null)
    : Exception(Describe(failure, provider, code, description, status))
{
    public OAuthFailure Failure { get; } = failure;

    private static string Describe(OAuthFailure failure, string provider, string? code, string? description, int? status) => failure switch
    {
        OAuthFailure.NotConfigured => AppText.Format("Connector_NotConfigured", provider),
        OAuthFailure.Cancelled => AppText.Get("OAuth_Cancelled"),
        OAuthFailure.StateMismatch or OAuthFailure.MissingAuthorizationCode => AppText.Get("OAuth_Unverified"),
        OAuthFailure.AuthorizationDenied => AppText.Format("OAuth_Denied", provider),
        OAuthFailure.InvalidGrant => AppText.Format("OAuth_InvalidGrant", provider),
        OAuthFailure.Server when !string.IsNullOrEmpty(description) => AppText.Format("OAuth_ServerDetail", provider, code, description),
        OAuthFailure.Server => AppText.Format("OAuth_Server", provider, code),
        OAuthFailure.Http => AppText.Format("Connector_ServerError", provider, status),
        _ => AppText.Format("OAuth_Malformed", provider),
    };
}

public sealed class OAuthClient(OAuthConfiguration configuration, HttpMessageInvoker? client = null)
{
    private readonly HttpMessageInvoker _client = client ?? RemoteHttp.Shared;
    private string Provider => configuration.ProviderName;

    public OAuthConfiguration Configuration => configuration;

    /// <summary>The consent page's address. No client secret, ever.</summary>
    public string AuthorizationUrl(string redirectUri, string state, string codeChallenge)
    {
        var parameters = new List<(string, string)>
        {
            ("client_id", configuration.ClientId),
            ("response_type", "code"),
            ("redirect_uri", redirectUri),
            ("scope", string.Join(' ', configuration.Scopes)),
            ("state", state),
            ("code_challenge", codeChallenge),
            ("code_challenge_method", "S256"),
        };
        parameters.AddRange(configuration.AdditionalAuthorizationParameters
            .OrderBy(pair => pair.Key, StringComparer.Ordinal)
            .Select(pair => (pair.Key, pair.Value)));
        return $"{configuration.AuthorizationEndpoint}?{FormEncode(parameters)}";
    }

    /// <summary>The authorization code from the redirect's query, after checking state and any error.</summary>
    public static string AuthorizationCode(string query, string expectedState, string provider)
    {
        var values = ParseQuery(query);
        string? Value(string name) => values.TryGetValue(name, out var value) ? value : null;
        if (Value("error") is { } error)
        {
            if (error == "access_denied") throw new OAuthException(OAuthFailure.AuthorizationDenied, provider);
            throw new OAuthException(OAuthFailure.Server, provider, error, Value("error_description"));
        }
        if (Value("state") != expectedState) throw new OAuthException(OAuthFailure.StateMismatch, provider);
        if (Value("code") is not { Length: > 0 } code) throw new OAuthException(OAuthFailure.MissingAuthorizationCode, provider);
        return code;
    }

    public Task<OAuthTokenResponse> ExchangeAsync(string code, string verifier, string redirectUri, CancellationToken cancellation)
    {
        var parameters = new List<(string, string)>
        {
            ("grant_type", "authorization_code"),
            ("code", code),
            ("client_id", configuration.ClientId),
            ("redirect_uri", redirectUri),
            ("code_verifier", verifier),
        };
        if (configuration.SendsScopeToTokenEndpoint) parameters.Add(("scope", string.Join(' ', configuration.Scopes)));
        return TokenRequestAsync(parameters, cancellation);
    }

    public Task<OAuthTokenResponse> RefreshAsync(string refreshToken, CancellationToken cancellation)
    {
        var parameters = new List<(string, string)>
        {
            ("grant_type", "refresh_token"),
            ("refresh_token", refreshToken),
            ("client_id", configuration.ClientId),
        };
        if (configuration.SendsScopeToTokenEndpoint) parameters.Add(("scope", string.Join(' ', configuration.Scopes)));
        return TokenRequestAsync(parameters, cancellation);
    }

    private async Task<OAuthTokenResponse> TokenRequestAsync(List<(string, string)> parameters, CancellationToken cancellation)
    {
        using var request = new HttpRequestMessage(HttpMethod.Post, configuration.TokenEndpoint)
        {
            Content = new StringContent(FormEncode(parameters), Encoding.ASCII, "application/x-www-form-urlencoded"),
        };
        request.Content.Headers.ContentType!.CharSet = null;
        request.Headers.Accept.ParseAdd("application/json");
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        timeout.CancelAfter(TimeSpan.FromSeconds(30));
        using var response = await _client.SendAsync(request, timeout.Token).ConfigureAwait(false);
        var body = await response.Content.ReadAsByteArrayAsync(timeout.Token).ConfigureAwait(false);
        var status = (int)response.StatusCode;
        if (status is < 200 or >= 300) throw ErrorFrom(body, status);
        OAuthTokenResponse? tokens;
        try
        {
            tokens = JsonSerializer.Deserialize<OAuthTokenResponse>(body);
        }
        catch (JsonException)
        {
            tokens = null;
        }
        if (tokens is null || tokens.AccessToken.Length == 0) throw new OAuthException(OAuthFailure.MalformedResponse, Provider);
        return tokens;
    }

    private OAuthException ErrorFrom(byte[] body, int status)
    {
        try
        {
            using var document = JsonDocument.Parse(body);
            var root = document.RootElement;
            if (root.ValueKind == JsonValueKind.Object && root.TryGetProperty("error", out var errorElement)
                && errorElement.ValueKind == JsonValueKind.String)
            {
                var error = errorElement.GetString()!;
                var description = root.TryGetProperty("error_description", out var detail) && detail.ValueKind == JsonValueKind.String
                    ? detail.GetString()
                    : null;
                return error switch
                {
                    "invalid_grant" => new OAuthException(OAuthFailure.InvalidGrant, Provider),
                    "access_denied" => new OAuthException(OAuthFailure.AuthorizationDenied, Provider),
                    _ => new OAuthException(OAuthFailure.Server, Provider, error, description),
                };
            }
        }
        catch (JsonException)
        {
            // Not a JSON error body.
        }
        return new OAuthException(OAuthFailure.Http, Provider, status: status);
    }

    /// <summary>application/x-www-form-urlencoded, escaping everything outside the unreserved set.</summary>
    public static string FormEncode(IEnumerable<(string Name, string Value)> parameters) =>
        string.Join("&", parameters.Select(pair => $"{Uri.EscapeDataString(pair.Name)}={Uri.EscapeDataString(pair.Value)}"));

    public static Dictionary<string, string> ParseQuery(string query)
    {
        var values = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var pair in query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var equals = pair.IndexOf('=');
            var name = Uri.UnescapeDataString((equals < 0 ? pair : pair[..equals]).Replace('+', ' '));
            var value = equals < 0 ? "" : Uri.UnescapeDataString(pair[(equals + 1)..].Replace('+', ' '));
            values.TryAdd(name, value);
        }
        return values;
    }
}

/// <summary>
/// Per-provider OAuth settings (CloudProviders.swift). Google Drive is not
/// offered on Windows (D9): its desktop clients need a client secret, so
/// Drive is reached through WebDAV (for example rclone serve webdav).
/// </summary>
public static class CloudProviders
{
    public static readonly IReadOnlyList<string> OneDriveScopes = ["Files.Read", "User.Read", "offline_access"];
    public static readonly IReadOnlyList<string> DropboxScopes = ["files.metadata.read", "files.content.read", "account_info.read"];

    /// <summary>Personal and work/school Microsoft accounts both sign in through the common authority.</summary>
    public const string MicrosoftAuthority = "https://login.microsoftonline.com/common/oauth2/v2.0/";

    /// <summary>The fixed loopback port Dropbox's app registration names (http://127.0.0.1:49735/).</summary>
    public const int DropboxLoopbackPort = 49735;

    /// <summary>The configuration for <paramref name="kind"/>, or null when this build has no client ID for it.</summary>
    public static OAuthConfiguration? Configuration(MediaSourceKind kind) =>
        CloudClientIds.For(kind) is { } clientId ? Configuration(kind, clientId) : null;

    public static bool IsConfigured(MediaSourceKind kind) => Configuration(kind) is not null;

    public static OAuthConfiguration? Configuration(MediaSourceKind kind, string clientId) => kind switch
    {
        MediaSourceKind.OneDrive => new OAuthConfiguration
        {
            Kind = kind,
            ClientId = clientId,
            AuthorizationEndpoint = MicrosoftAuthority + "authorize",
            TokenEndpoint = MicrosoftAuthority + "token",
            Scopes = OneDriveScopes,
            AdditionalAuthorizationParameters = new Dictionary<string, string> { ["prompt"] = "select_account" },
            SendsScopeToTokenEndpoint = true,
        },
        MediaSourceKind.Dropbox => new OAuthConfiguration
        {
            Kind = kind,
            ClientId = clientId,
            AuthorizationEndpoint = "https://www.dropbox.com/oauth2/authorize",
            TokenEndpoint = "https://api.dropboxapi.com/oauth2/token",
            Scopes = DropboxScopes,
            AdditionalAuthorizationParameters = new Dictionary<string, string> { ["token_access_type"] = "offline" },
            LoopbackPort = DropboxLoopbackPort,
        },
        _ => null,
    };

    /// <summary>Who a fresh token belongs to.</summary>
    public sealed record Identity(string Subject, string? Email, string? DisplayName, string? DriveId = null);

    /// <summary>Microsoft Graph /me and /me/drive, or Dropbox get_current_account.</summary>
    public static async Task<Identity> IdentityAsync(MediaSourceKind kind, string accessToken, HttpMessageInvoker? client, CancellationToken cancellation)
    {
        client ??= RemoteHttp.Shared;
        var provider = kind.DisplayName();
        switch (kind)
        {
            case MediaSourceKind.OneDrive:
            {
                using var me = await GetJsonAsync("https://graph.microsoft.com/v1.0/me?$select=id,displayName,mail,userPrincipalName", accessToken, provider, client, cancellation);
                using var drive = await GetJsonAsync("https://graph.microsoft.com/v1.0/me/drive?$select=id", accessToken, provider, client, cancellation);
                var root = me.RootElement;
                var id = root.String("id") ?? throw new OAuthException(OAuthFailure.MalformedResponse, provider);
                return new Identity(id, root.String("mail") ?? root.String("userPrincipalName"), root.String("displayName"), drive.RootElement.String("id"));
            }
            case MediaSourceKind.Dropbox:
            {
                using var request = new HttpRequestMessage(HttpMethod.Post, "https://api.dropboxapi.com/2/users/get_current_account");
                request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", accessToken);
                using var response = await client.SendAsync(request, cancellation).ConfigureAwait(false);
                if (!response.IsSuccessStatusCode) throw new OAuthException(OAuthFailure.Http, provider, status: (int)response.StatusCode);
                using var document = await ParseAsync(response, provider, cancellation);
                var root = document.RootElement;
                var id = root.String("account_id") ?? throw new OAuthException(OAuthFailure.MalformedResponse, provider);
                var name = root.Property("name") is { } names ? names.String("display_name") : null;
                return new Identity(id, root.String("email"), name);
            }
            default:
                throw new OAuthException(OAuthFailure.NotConfigured, provider);
        }
    }

    /// <summary>Best effort: Dropbox can end the grant (revoking an access token disables its refresh token).</summary>
    public static async Task RevokeAsync(MediaSourceKind kind, string? accessToken, HttpMessageInvoker? client = null)
    {
        if (kind != MediaSourceKind.Dropbox || accessToken is null) return;
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Post, "https://api.dropboxapi.com/2/auth/token/revoke");
            request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", accessToken);
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(10));
            using var _ = await (client ?? RemoteHttp.Shared).SendAsync(request, timeout.Token).ConfigureAwait(false);
        }
        catch
        {
            // The account is forgotten here either way.
        }
    }

    private static async Task<JsonDocument> GetJsonAsync(string url, string token, string provider, HttpMessageInvoker client, CancellationToken cancellation)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
        request.Headers.Accept.ParseAdd("application/json");
        using var response = await client.SendAsync(request, cancellation).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode) throw new OAuthException(OAuthFailure.Http, provider, status: (int)response.StatusCode);
        return await ParseAsync(response, provider, cancellation);
    }

    private static async Task<JsonDocument> ParseAsync(HttpResponseMessage response, string provider, CancellationToken cancellation)
    {
        try
        {
            return JsonDocument.Parse(await response.Content.ReadAsByteArrayAsync(cancellation).ConfigureAwait(false));
        }
        catch (JsonException)
        {
            throw new OAuthException(OAuthFailure.MalformedResponse, provider);
        }
    }
}

/// <summary>
/// Client IDs and app keys, from the environment or the gitignored
/// secrets.json the build embeds (tools/Edendale.Secrets). They aren't
/// secrets for PKCE clients, but they stay out of source control like the
/// TMDB credentials. An empty value hides that provider.
/// </summary>
public static class CloudClientIds
{
    private const string ResourceName = "Edendale.LocalSecrets";

    /// <summary>Tests replace this to configure providers without a secrets file.</summary>
    public static Func<string, string?> Source { get; set; } = Load;

    public static string? For(MediaSourceKind kind)
    {
        var name = kind switch
        {
            MediaSourceKind.OneDrive => "ONEDRIVE_CLIENT_ID",
            MediaSourceKind.Dropbox => "DROPBOX_APP_KEY",
            _ => null,
        };
        return name is null ? null : Source(name) is { Length: > 0 } value ? value : null;
    }

    private static string? Load(string name)
    {
        var fromEnvironment = Environment.GetEnvironmentVariable(name);
        if (!string.IsNullOrWhiteSpace(fromEnvironment)) return fromEnvironment.Trim();
        try
        {
            using var stream = typeof(CloudClientIds).Assembly.GetManifestResourceStream(ResourceName);
            if (stream is null) return null;
            using var document = JsonDocument.Parse(stream);
            return document.RootElement.String(name)?.Trim();
        }
        catch (JsonException)
        {
            return null;
        }
    }
}

/// <summary>
/// Hands out access tokens for linked accounts (CloudTokenProvider.swift).
/// Tokens live only in memory; the refresh token stays in the DPAPI vault.
/// At most one refresh runs per account, and every caller waiting on it gets
/// its result, so a burst of 401s triggers a single refresh.
/// </summary>
public sealed class CloudTokenProvider
{
    private sealed record Token(string Value, DateTimeOffset ExpiresAt);

    /// <summary>Refreshes this long before a token's stated expiry.</summary>
    private static readonly TimeSpan ExpiryMargin = TimeSpan.FromSeconds(120);

    private readonly CloudAccountVault _vault;
    private readonly HttpMessageInvoker? _client;
    private readonly Func<MediaSourceKind, OAuthConfiguration?> _configuration;
    private readonly Func<DateTimeOffset> _now;
    private readonly object _gate = new();
    private readonly Dictionary<string, Token> _cache = [];
    private readonly Dictionary<string, Task<Token>> _refreshes = [];

    public CloudTokenProvider(
        CloudAccountVault vault,
        HttpMessageInvoker? client = null,
        Func<MediaSourceKind, OAuthConfiguration?>? configuration = null,
        Func<DateTimeOffset>? now = null)
    {
        _vault = vault;
        _client = client;
        _configuration = configuration ?? CloudProviders.Configuration;
        _now = now ?? (() => DateTimeOffset.UtcNow);
    }

    private static string CacheKey(MediaSourceKind kind, string accountKey) => $"{kind.RawValue()}:{accountKey}";

    /// <summary>
    /// A valid access token. Pass the token a provider just refused (HTTP
    /// 401) as <paramref name="rejected"/>: it is refreshed, unless another
    /// caller has already replaced it.
    /// </summary>
    public async Task<string> AccessTokenAsync(MediaSourceKind kind, string accountKey, string? rejected = null)
    {
        var key = CacheKey(kind, accountKey);
        Task<Token> refresh;
        lock (_gate)
        {
            if (_cache.TryGetValue(key, out var token) && token.Value != rejected && token.ExpiresAt - _now() > ExpiryMargin)
            {
                return token.Value;
            }
            // A task in _refreshes is always still running: it removes itself
            // (and caches its token) under this lock before it completes.
            if (!_refreshes.TryGetValue(key, out refresh!))
            {
                refresh = RefreshAndRecordAsync(kind, accountKey, key);
                _refreshes[key] = refresh;
            }
        }
        return (await refresh.ConfigureAwait(false)).Value;
    }

    private async Task<Token> RefreshAndRecordAsync(MediaSourceKind kind, string accountKey, string key)
    {
        try
        {
            var token = await RefreshAsync(kind, accountKey).ConfigureAwait(false);
            lock (_gate)
            {
                // An account signed out while its refresh ran keeps no token.
                if (_vault.Account(kind, accountKey) is not null) _cache[key] = token;
                _refreshes.Remove(key);
            }
            return token;
        }
        catch
        {
            lock (_gate) _refreshes.Remove(key);
            throw;
        }
    }

    /// <summary>Seeds the cache with the token a sign-in just produced.</summary>
    public void Store(OAuthTokenResponse tokens, CloudAccount account)
    {
        lock (_gate)
        {
            _cache[CacheKey(account.SourceKind, account.Key)] =
                new Token(tokens.AccessToken, _now() + TimeSpan.FromSeconds(tokens.ExpiresIn ?? 3600));
        }
    }

    /// <summary>The cached token, if any (for revoking at sign-out).</summary>
    public string? CachedToken(MediaSourceKind kind, string accountKey)
    {
        lock (_gate) return _cache.TryGetValue(CacheKey(kind, accountKey), out var token) ? token.Value : null;
    }

    /// <summary>Drops the cached token after a sign-out.</summary>
    public void Forget(MediaSourceKind kind, string accountKey)
    {
        lock (_gate)
        {
            _cache.Remove(CacheKey(kind, accountKey));
            _refreshes.Remove(CacheKey(kind, accountKey));
        }
    }

    private async Task<Token> RefreshAsync(MediaSourceKind kind, string accountKey)
    {
        await Task.Yield();
        var provider = kind.DisplayName();
        var account = _vault.Account(kind, accountKey)
            ?? throw new ConnectorException(ConnectorFailure.SignInRequired, provider);
        var configuration = _configuration(kind)
            ?? throw new ConnectorException(ConnectorFailure.NotConfigured, provider);
        OAuthTokenResponse response;
        try
        {
            response = await new OAuthClient(configuration, _client).RefreshAsync(account.RefreshToken, CancellationToken.None).ConfigureAwait(false);
        }
        catch (OAuthException error) when (error.Failure == OAuthFailure.InvalidGrant)
        {
            throw new ConnectorException(ConnectorFailure.SignInRequired, provider);
        }
        // Microsoft and Dropbox may rotate refresh tokens; keep the newest one.
        if (response.RefreshToken is { Length: > 0 } rotated && rotated != account.RefreshToken)
        {
            _vault.UpdateRefreshToken(kind, accountKey, rotated);
        }
        return new Token(response.AccessToken, _now() + TimeSpan.FromSeconds(response.ExpiresIn ?? 3600));
    }
}

/// <summary>
/// The loopback redirect (RFC 8252 §7.3): a one-shot listener on 127.0.0.1
/// that receives the provider's redirect from the system browser. A raw
/// socket, not HttpListener, so no URL reservation or elevation is needed.
/// </summary>
public sealed class LoopbackRedirect : IDisposable
{
    private readonly TcpListener _listener;

    public LoopbackRedirect(int? port)
    {
        _listener = new TcpListener(IPAddress.Loopback, port ?? 0);
        _listener.Start(1);
        Port = ((IPEndPoint)_listener.LocalEndpoint).Port;
    }

    public int Port { get; }

    public string RedirectUri => $"http://127.0.0.1:{Port}/";

    /// <summary>Waits for the browser's redirect and returns its query string.</summary>
    public async Task<string> WaitForQueryAsync(string completedPage, CancellationToken cancellation)
    {
        while (true)
        {
            using var connection = await _listener.AcceptTcpClientAsync(cancellation).ConfigureAwait(false);
            await using var stream = connection.GetStream();
            using var reader = new StreamReader(stream, Encoding.ASCII, false, 4096, leaveOpen: true);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            timeout.CancelAfter(TimeSpan.FromSeconds(10));
            var requestLine = await reader.ReadLineAsync(timeout.Token).ConfigureAwait(false) ?? "";
            // "GET /?code=…&state=… HTTP/1.1"; anything else (a favicon) is ignored.
            var parts = requestLine.Split(' ');
            var target = parts.Length >= 2 ? parts[1] : "";
            var question = target.IndexOf('?');
            var query = question >= 0 ? target[(question + 1)..] : "";
            var isCallback = parts.Length >= 2 && parts[0] == "GET" && target.StartsWith("/?", StringComparison.Ordinal)
                && (query.Contains("code=") || query.Contains("error="));

            var page = isCallback ? completedPage : "";
            var body = Encoding.UTF8.GetBytes(page);
            var header = isCallback
                ? $"HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: {body.Length}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
                : "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            await stream.WriteAsync(Encoding.ASCII.GetBytes(header), cancellation).ConfigureAwait(false);
            await stream.WriteAsync(body, cancellation).ConfigureAwait(false);
            if (isCallback) return query;
        }
    }

    public void Dispose() => _listener.Stop();
}
