// Random-access bytes of a remote file for LibVLC's custom input
// (ByteSourceMediaInput), over HttpClient with system TLS validation, no
// cookies, and no cache (DIFF.md §3.12 "Streaming").
//
// The file is read in 4 MiB Range chunks. While reads are sequential the next
// chunk is prefetched, and up to 8 chunks (32 MiB) stay cached, so the MKV
// cues or MP4 moov at the end of a file stay cached through the open. LibVLC
// reads on its own thread and blocks here until a chunk arrives; the wait
// checks its abort callback, and Cancel() fails it at once.
//
// Responses:
//   206                        serve the range
//   200 at offset 0            accept (the server ignored Range; Graph does)
//   200 at any other offset    retry once, then fail the read
//   401                        refresh the token (or link) once, then retry
//   403 rate limit, 429, 5xx   back off 0.5 s, 1 s, 2 s with jitter, then fail
//   403 expired link, 410      resolve a new link once, then retry
//   404                        fail: "This file is no longer in <provider>"
//
// Pre-authorized URLs, tokens, and request headers are never logged.

using System.Net;
using System.Net.Http.Headers;
using System.Security.Authentication;
using System.Text;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services.Remote;

/// <summary>
/// Random-access bytes for LibVLC. <see cref="Read"/> blocks; it returns the
/// count copied, 0 at the end of the file, or -1 on failure (with
/// <see cref="FailureReason"/> saying why, unless the read was aborted).
/// </summary>
public interface IByteSource
{
    /// <summary>The file size, or -1 while unknown.</summary>
    long Length { get; }

    /// <summary>Why the last read failed, for the player's error message.</summary>
    string? FailureReason { get; }

    int Read(long offset, Span<byte> buffer, Func<bool>? shouldAbort = null);

    /// <summary>Fails blocked and later reads at once and stops background work.</summary>
    void Cancel();
}

/// <summary>How a byte source asks for a remote file's bytes (one per HTTP provider).</summary>
public interface IRemoteContentResolver
{
    MediaSourceKind Kind { get; }

    /// <summary>
    /// Requests are pre-authorized links (OneDrive, Dropbox, S3), so a
    /// refresh fetches a new link rather than a new access token.
    /// </summary>
    bool UsesPreauthorizedLinks { get; }

    /// <summary>The client to send content requests with (WebDAV's carries its login).</summary>
    HttpMessageInvoker Client { get; }

    /// <summary>A GET for the file's bytes. <paramref name="refresh"/> forces a new token or link.</summary>
    Task<HttpRequestMessage> ContentRequestAsync(bool refresh, CancellationToken cancellation);
}

/// <summary>What to do about a non-2xx response to a listing or content request.</summary>
public sealed record ProviderAction(ProviderActionKind Kind, TimeSpan? RetryAfter = null, ConnectorException? Error = null)
{
    public static readonly ProviderAction Refresh = new(ProviderActionKind.Refresh);
    public static ProviderAction Backoff(TimeSpan? retryAfter) => new(ProviderActionKind.Backoff, retryAfter);
    public static ProviderAction Fail(ConnectorException error) => new(ProviderActionKind.Fail, Error: error);
}

public enum ProviderActionKind
{
    /// <summary>Get a new access token or link, once, then retry.</summary>
    Refresh,
    /// <summary>Wait (for Retry-After when given), then retry.</summary>
    Backoff,
    Fail,
}

public static class ProviderResponse
{
    /// <summary>Classifies a failed response (RemoteContentResolver.swift).</summary>
    public static ProviderAction Action(int status, string body, string? retryAfterHeader, MediaSourceKind kind, bool preauthorizedLink)
    {
        var provider = kind.DisplayName();
        switch (status)
        {
            case 401:
                return ProviderAction.Refresh;
            case 403:
            {
                var text = body.Length > 16_384 ? body[..16_384] : body;
                text = text.ToLowerInvariant();
                if (text.Contains("ratelimitexceeded") || text.Contains("rate_limit_exceeded")
                    || text.Contains("slowdown") || text.Contains("too_many_requests"))
                {
                    return ProviderAction.Backoff(RetryAfter(retryAfterHeader));
                }
                if (text.Contains("downloadquotaexceeded"))
                {
                    return ProviderAction.Fail(new ConnectorException(ConnectorFailure.RateLimited, provider));
                }
                if (text.Contains("cannotdownloadabusivefile"))
                {
                    return ProviderAction.Fail(new ConnectorException(ConnectorFailure.AbusiveFile, provider));
                }
                // An expired signed link (S3's "Request has expired") or a
                // OneDrive download URL past its lifetime: resolve a new one.
                return preauthorizedLink
                    ? ProviderAction.Refresh
                    : ProviderAction.Fail(new ConnectorException(ConnectorFailure.AccessDenied, provider));
            }
            case 404:
                return ProviderAction.Fail(new ConnectorException(ConnectorFailure.NotFound, provider));
            case 409:
                // Dropbox reports endpoint errors as 409 with a summary.
                return ProviderAction.Fail(body.Contains("not_found", StringComparison.Ordinal)
                    ? new ConnectorException(ConnectorFailure.NotFound, provider)
                    : new ConnectorException(ConnectorFailure.ServerError, provider, status));
            case 410:
                // Dropbox temporary links expire after four hours.
                return ProviderAction.Refresh;
            case 408 or 429 or 500 or 502 or 503 or 504:
                return ProviderAction.Backoff(RetryAfter(retryAfterHeader));
            default:
                return ProviderAction.Fail(new ConnectorException(ConnectorFailure.ServerError, provider, status));
        }
    }

    /// <summary>Retry-After in seconds, capped so a hostile value can't stall playback.</summary>
    public static TimeSpan? RetryAfter(string? header)
    {
        if (string.IsNullOrWhiteSpace(header)) return null;
        if (!double.TryParse(header.Trim(), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var seconds)
            || seconds < 0 || double.IsNaN(seconds))
        {
            return null;
        }
        return TimeSpan.FromSeconds(Math.Min(seconds, 10));
    }

    public static string? RetryAfterHeader(HttpResponseMessage response) =>
        response.Headers.TryGetValues("Retry-After", out var values) ? values.FirstOrDefault() : null;

    /// <summary>Transport failures worth retrying with backoff: not TLS, not our own cancellation.</summary>
    public static bool IsTransient(Exception error, CancellationToken cancellation) => error switch
    {
        _ when IsCertificateFailure(error) => false,
        HttpRequestException => true,
        TaskCanceledException when !cancellation.IsCancellationRequested => true, // a request timeout
        IOException => true,
        _ => false,
    };

    /// <summary>The server's certificate wasn't trusted (D10: no exceptions for self-signed ones).</summary>
    public static bool IsCertificateFailure(Exception error)
    {
        for (var current = error; current is not null; current = current.InnerException)
        {
            if (current is AuthenticationException) return true;
        }
        return false;
    }

    /// <summary>
    /// A message for the player. Transport errors say which provider was
    /// unreachable without repeating the URL.
    /// </summary>
    public static string Describe(Exception error, string provider) => error switch
    {
        ConnectorException connector => connector.Message,
        _ when IsCertificateFailure(error) => new ConnectorException(ConnectorFailure.CertificateInvalid, provider).Message,
        HttpRequestException or IOException or TaskCanceledException => AppText.Format("Remote_Unreachable", provider),
        _ => AppText.Format("Remote_ReadFailed", provider),
    };
}

/// <summary>A parsed <c>Content-Range</c>: "bytes 0-499/1234", "bytes 0-499/*", or "bytes */1234".</summary>
public readonly record struct ContentRange(long Start, long? End, long? Total)
{
    public static ContentRange? Parse(string? header)
    {
        var text = header?.Trim();
        if (text is null || !text.StartsWith("bytes ", StringComparison.OrdinalIgnoreCase)) return null;
        var parts = text[6..].Split('/', 2);
        if (parts.Length != 2) return null;
        long? total = long.TryParse(parts[1], out var parsedTotal) ? parsedTotal : null;
        if (parts[0] == "*") return new ContentRange(0, null, total);
        var bounds = parts[0].Split('-', 2);
        if (bounds.Length != 2 || !long.TryParse(bounds[0], out var start) || !long.TryParse(bounds[1], out var end)) return null;
        return new ContentRange(start, end, total);
    }
}

public sealed class RemoteByteSource : IByteSource
{
    public sealed record Configuration
    {
        public long ChunkSize { get; init; } = 4 << 20;
        public int CachedChunks { get; init; } = 8;
        public TimeSpan RequestTimeout { get; init; } = TimeSpan.FromSeconds(30);

        /// <summary>Delays before each retry of a rate-limited or failed request; the count is the number of retries.</summary>
        public IReadOnlyList<TimeSpan> BackoffDelays { get; init; } =
            [TimeSpan.FromSeconds(0.5), TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(2)];

        /// <summary>Jitter applied to each backoff delay, as a fraction.</summary>
        public double BackoffJitter { get; init; } = 0.2;
    }

    private readonly IRemoteContentResolver _resolver;
    private readonly Configuration _configuration;
    private readonly CancellationTokenSource _cancellation = new();

    // Everything below is guarded by _gate.
    private readonly object _gate = new();
    private long _knownLength;
    private readonly Dictionary<long, byte[]> _chunks = [];
    /// <summary>Chunk indices, least recently used first.</summary>
    private readonly List<long> _recency = [];
    private readonly Dictionary<long, Task> _loads = [];
    /// <summary>Failed loads a reader was waiting for; a failed prefetch nobody waited on is dropped.</summary>
    private readonly Dictionary<long, Exception> _failures = [];
    private readonly Dictionary<long, int> _waiters = [];
    private long _lastReadEnd = -1;
    private bool _isCancelled;
    private string? _reason;

    /// <param name="length">The file size when the listing reported it; otherwise the first Content-Range gives it.</param>
    public RemoteByteSource(IRemoteContentResolver resolver, long? length = null, Configuration? configuration = null)
    {
        _resolver = resolver;
        _configuration = configuration ?? new Configuration();
        _knownLength = length ?? -1;
    }

    public long Length
    {
        get { lock (_gate) return _knownLength; }
    }

    public string? FailureReason
    {
        get { lock (_gate) return _reason; }
    }

    public int Read(long offset, Span<byte> buffer, Func<bool>? shouldAbort = null)
    {
        if (buffer.Length == 0 || offset < 0) return 0;
        var chunkSize = _configuration.ChunkSize;
        var index = offset / chunkSize;
        lock (_gate)
        {
            while (true)
            {
                if (_isCancelled) return -1;
                if (_knownLength >= 0 && offset >= _knownLength) return 0;

                if (_chunks.TryGetValue(index, out var data))
                {
                    Touch(index);
                    var start = (int)(offset - index * chunkSize);
                    // A chunk shorter than the offset ends the file.
                    if (start >= data.Length) return 0;
                    var count = Math.Min(buffer.Length, data.Length - start);
                    data.AsSpan(start, count).CopyTo(buffer);
                    var sequential = offset == _lastReadEnd;
                    _lastReadEnd = offset + count;
                    if (sequential || start + count == data.Length) Prefetch(index + 1);
                    return count;
                }

                if (_failures.Remove(index, out var error))
                {
                    _reason = ProviderResponse.Describe(error, _resolver.Kind.DisplayName());
                    return -1;
                }
                if (!_loads.ContainsKey(index)) StartLoading(index);

                // Wake at least every 50 ms to notice an abort.
                _waiters[index] = _waiters.GetValueOrDefault(index) + 1;
                Monitor.Wait(_gate, 50);
                if (--_waiters[index] == 0) _waiters.Remove(index);
                if (shouldAbort?.Invoke() == true) return -1;
            }
        }
    }

    public void Cancel()
    {
        lock (_gate)
        {
            if (_isCancelled) return;
            _isCancelled = true;
            _loads.Clear();
            _chunks.Clear();
            _recency.Clear();
            Monitor.PulseAll(_gate);
        }
        _cancellation.Cancel();
    }

    // ------------------------------------------------------------------
    // Chunk loading (called with _gate held)
    // ------------------------------------------------------------------

    private void Prefetch(long index)
    {
        if (_chunks.ContainsKey(index) || _loads.ContainsKey(index) || _failures.ContainsKey(index)) return;
        if (_knownLength >= 0 && index * _configuration.ChunkSize >= _knownLength) return;
        // One chunk ahead hides a request's latency; more would spend
        // bandwidth on data a seek may throw away.
        if (_loads.Count >= 2) return;
        StartLoading(index);
    }

    private void StartLoading(long index)
    {
        var token = _cancellation.Token;
        _loads[index] = Task.Run(() => LoadAsync(index, token));
    }

    private void Touch(long index)
    {
        _recency.Remove(index);
        _recency.Add(index);
    }

    private async Task LoadAsync(long index, CancellationToken cancellation)
    {
        var start = index * _configuration.ChunkSize;
        byte[]? data = null;
        Exception? failure = null;
        try
        {
            data = await FillChunkAsync(start, cancellation).ConfigureAwait(false);
        }
        catch (Exception error)
        {
            failure = error;
        }

        lock (_gate)
        {
            _loads.Remove(index);
            if (!_isCancelled)
            {
                if (data is not null)
                {
                    _chunks[index] = data;
                    Touch(index);
                    // A short chunk without a stated total is the end of the file.
                    if (data.Length < _configuration.ChunkSize && _knownLength < 0) _knownLength = start + data.Length;
                    while (_recency.Count > _configuration.CachedChunks && _recency[0] != index)
                    {
                        _chunks.Remove(_recency[0]);
                        _recency.RemoveAt(0);
                    }
                }
                else if (failure is not null && failure is not OperationCanceledException { CancellationToken.IsCancellationRequested: true }
                    && _waiters.ContainsKey(index))
                {
                    _failures[index] = failure;
                }
            }
            Monitor.PulseAll(_gate);
        }
    }

    /// <summary>Fetches one chunk, continuing where a server returned less of a range than asked for.</summary>
    private async Task<byte[]> FillChunkAsync(long start, CancellationToken cancellation)
    {
        using var buffer = new MemoryStream();
        var position = start;
        var chunkEnd = start + _configuration.ChunkSize;
        while (position < chunkEnd)
        {
            var end = chunkEnd;
            var known = Length;
            if (known >= 0) end = Math.Min(end, known);
            if (position >= end) break;

            var (piece, total) = await FetchAsync(position, end, cancellation).ConfigureAwait(false);
            if (total is long size)
            {
                lock (_gate)
                {
                    if (_knownLength < 0) _knownLength = size;
                }
            }
            buffer.Write(piece);
            position += piece.Length;
            // An empty or short piece without a larger stated total ends the file.
            if (piece.Length == 0) break;
            if (position < end && (total is not long stated || position >= stated)) break;
        }
        return buffer.ToArray();
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /// <summary>One range [start, end), following the response table at the top of this file.</summary>
    private async Task<(byte[] Data, long? Total)> FetchAsync(long start, long end, CancellationToken cancellation)
    {
        var provider = _resolver.Kind.DisplayName();
        var refreshNext = false;
        var refreshed = false;
        var retried200 = false;
        var backoffs = 0;

        while (true)
        {
            cancellation.ThrowIfCancellationRequested();
            using var request = await _resolver.ContentRequestAsync(refreshNext, cancellation).ConfigureAwait(false);
            refreshNext = false;
            request.Headers.Range = new RangeHeaderValue(start, end - 1);
            // Byte offsets must refer to the file itself, never to a compressed rendition of it.
            request.Headers.AcceptEncoding.Clear();
            request.Headers.AcceptEncoding.Add(new StringWithQualityHeaderValue("identity"));
            request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true };

            int status;
            byte[] body;
            string? contentRange;
            long? contentLength;
            string? retryAfter;
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
            timeout.CancelAfter(_configuration.RequestTimeout);
            try
            {
                using var response = await _resolver.Client.SendAsync(request, timeout.Token).ConfigureAwait(false);
                status = (int)response.StatusCode;
                var success = status is >= 200 and < 300;
                // At most the range of a success body (a server that ignores
                // Range would otherwise send the whole file), 64 KiB of an error.
                body = await ReadLimitedAsync(response.Content, success ? (int)(end - start) : 65_536, timeout.Token).ConfigureAwait(false);
                contentRange = response.Content.Headers.TryGetValues("Content-Range", out var ranges) ? ranges.FirstOrDefault() : null;
                contentLength = response.Content.Headers.ContentLength;
                retryAfter = ProviderResponse.RetryAfterHeader(response);
            }
            catch (Exception error) when (ProviderResponse.IsTransient(error, cancellation))
            {
                if (backoffs >= _configuration.BackoffDelays.Count) throw;
                await BackoffAsync(backoffs++, null, cancellation).ConfigureAwait(false);
                continue;
            }

            switch (status)
            {
                case 206:
                {
                    var range = ContentRange.Parse(contentRange);
                    if (range is { } parsed && parsed.Start != start)
                    {
                        throw new ConnectorException(ConnectorFailure.RangeRequestsUnsupported, provider);
                    }
                    return (body, range?.Total);
                }
                case 200:
                    if (start == 0) return (body, contentLength is > 0 ? contentLength : null);
                    if (retried200) throw new ConnectorException(ConnectorFailure.RangeRequestsUnsupported, provider);
                    retried200 = true;
                    continue;
                case 416:
                    // The range starts at or past the end of the file.
                    return ([], ContentRange.Parse(contentRange)?.Total ?? start);
            }

            var action = ProviderResponse.Action(status, Encoding.UTF8.GetString(body), retryAfter,
                _resolver.Kind, _resolver.UsesPreauthorizedLinks);
            switch (action.Kind)
            {
                case ProviderActionKind.Refresh:
                    if (refreshed)
                    {
                        throw _resolver.UsesPreauthorizedLinks
                            ? new ConnectorException(ConnectorFailure.AccessDenied, provider)
                            : new ConnectorException(ConnectorFailure.SignInRequired, provider);
                    }
                    refreshed = true;
                    refreshNext = true;
                    break;
                case ProviderActionKind.Backoff:
                    if (backoffs >= _configuration.BackoffDelays.Count)
                    {
                        throw new ConnectorException(ConnectorFailure.RateLimited, provider);
                    }
                    await BackoffAsync(backoffs++, action.RetryAfter, cancellation).ConfigureAwait(false);
                    break;
                default:
                    throw action.Error!;
            }
        }
    }

    private Task BackoffAsync(int attempt, TimeSpan? retryAfter, CancellationToken cancellation)
    {
        var baseDelay = retryAfter ?? _configuration.BackoffDelays[attempt];
        var jitter = _configuration.BackoffJitter;
        var factor = 1 - jitter + Random.Shared.NextDouble() * 2 * jitter;
        return Task.Delay(TimeSpan.FromMilliseconds(Math.Max(0, baseDelay.TotalMilliseconds * factor)), cancellation);
    }

    /// <summary>Reads up to <paramref name="limit"/> bytes of a body, then stops the transfer.</summary>
    internal static async Task<byte[]> ReadLimitedAsync(HttpContent content, int limit, CancellationToken cancellation)
    {
        await using var stream = await content.ReadAsStreamAsync(cancellation).ConfigureAwait(false);
        var buffer = new byte[Math.Max(limit, 0)];
        var filled = 0;
        while (filled < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(filled), cancellation).ConfigureAwait(false);
            if (read == 0) break;
            filled += read;
        }
        return filled == buffer.Length ? buffer : buffer[..filled];
    }
}

/// <summary>Shared HTTP plumbing: no cookies, no cache, no decompression, system TLS.</summary>
public static class RemoteHttp
{
    /// <summary>For requests that carry their own authorization (tokens, signatures, signed links).</summary>
    public static readonly HttpClient Shared = Create(credentials: null);

    public static HttpClient Create(ICredentials? credentials) => new(CreateHandler(credentials))
    {
        // Each request sets its own timeout.
        Timeout = Timeout.InfiniteTimeSpan,
        DefaultRequestHeaders = { { "User-Agent", "Edendale-Windows/27.0" } },
    };

    public static SocketsHttpHandler CreateHandler(ICredentials? credentials) => new()
    {
        UseCookies = false,
        AutomaticDecompression = DecompressionMethods.None,
        Credentials = credentials,
        PreAuthenticate = false,
        PooledConnectionLifetime = TimeSpan.FromMinutes(5),
        ConnectTimeout = TimeSpan.FromSeconds(15),
        MaxAutomaticRedirections = 5,
    };
}
