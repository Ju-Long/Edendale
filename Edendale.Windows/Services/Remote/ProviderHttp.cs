using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services.Remote;

/// <summary>
/// Authorized requests to a cloud provider's API for one linked account
/// (ProviderHTTP.swift): a Bearer token from the token provider, one refresh
/// after a 401, and backoff on rate limits and server errors. Tokens and
/// URLs are never logged.
/// </summary>
public sealed class ProviderHttp(MediaSourceKind kind, string accountKey, CloudTokenProvider tokens, HttpMessageInvoker? client = null)
{
    private readonly HttpMessageInvoker _client = client ?? RemoteHttp.Shared;

    /// <summary>Delays before each retry of a rate-limited request.</summary>
    public IReadOnlyList<TimeSpan> BackoffDelays { get; init; } =
        [TimeSpan.FromSeconds(0.5), TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(2)];

    public MediaSourceKind Kind => kind;

    /// <summary>Sends the request <paramref name="build"/> makes and returns a 2xx body; other statuses throw.</summary>
    public async Task<byte[]> SendAsync(Func<HttpRequestMessage> build, CancellationToken cancellation)
    {
        string? rejected = null;
        var refreshed = false;
        var backoffs = 0;
        var provider = kind.DisplayName();

        while (true)
        {
            cancellation.ThrowIfCancellationRequested();
            var token = await tokens.AccessTokenAsync(kind, accountKey, rejected).ConfigureAwait(false);
            using var request = build();
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };

            int status;
            byte[] body;
            string? retryAfter;
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            timeout.CancelAfter(TimeSpan.FromSeconds(30));
            try
            {
                using var response = await _client.SendAsync(request, timeout.Token).ConfigureAwait(false);
                status = (int)response.StatusCode;
                body = await response.Content.ReadAsByteArrayAsync(timeout.Token).ConfigureAwait(false);
                retryAfter = ProviderResponse.RetryAfterHeader(response);
            }
            catch (Exception error) when (ProviderResponse.IsTransient(error, cancellation))
            {
                if (backoffs >= BackoffDelays.Count) throw new ConnectorException(ConnectorFailure.Unreachable, provider, inner: error);
                await Task.Delay(BackoffDelays[backoffs++], cancellation).ConfigureAwait(false);
                continue;
            }
            catch (Exception error) when (ProviderResponse.IsCertificateFailure(error))
            {
                throw new ConnectorException(ConnectorFailure.CertificateInvalid, provider, inner: error);
            }

            if (status is >= 200 and < 300) return body;

            var action = ProviderResponse.Action(status, Encoding.UTF8.GetString(body), retryAfter, kind, preauthorizedLink: false);
            switch (action.Kind)
            {
                case ProviderActionKind.Refresh:
                    if (refreshed) throw new ConnectorException(ConnectorFailure.SignInRequired, provider);
                    refreshed = true;
                    rejected = token;
                    break;
                case ProviderActionKind.Backoff:
                    if (backoffs >= BackoffDelays.Count) throw new ConnectorException(ConnectorFailure.RateLimited, provider);
                    await Task.Delay(action.RetryAfter ?? BackoffDelays[backoffs], cancellation).ConfigureAwait(false);
                    backoffs++;
                    break;
                default:
                    throw action.Error!;
            }
        }
    }

    /// <summary><see cref="SendAsync"/>, parsing a JSON body.</summary>
    public async Task<JsonDocument> JsonAsync(Func<HttpRequestMessage> build, CancellationToken cancellation)
    {
        var body = await SendAsync(build, cancellation).ConfigureAwait(false);
        try
        {
            return JsonDocument.Parse(body);
        }
        catch (JsonException)
        {
            throw new ConnectorException(ConnectorFailure.ServerError, kind.DisplayName(), 200);
        }
    }

    /// <summary>A JSON POST, typed exactly "application/json" (Dropbox checks).</summary>
    public static HttpRequestMessage JsonRequest(string url, object body)
    {
        var content = new StringContent(JsonSerializer.Serialize(body), Encoding.UTF8, "application/json");
        content.Headers.ContentType!.CharSet = null;
        return new HttpRequestMessage(HttpMethod.Post, url) { Content = content };
    }
}
