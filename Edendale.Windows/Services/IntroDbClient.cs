// TheIntroDB lookup for the opt-in skip prompts (DIFF.md §3.5). Only public
// metadata identifiers leave the device: the TMDB id (the show's id for an
// episode, with TMDB season and episode numbers) and the file's duration —
// plus, unavoidably, the device's IP address. No key, cookies, or disk cache
// are involved, and nothing is written anywhere. WinUI-free so the domain
// tests can drive it through a stub HttpMessageHandler.

using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;

namespace Edendale.Windows.Services;

/// <summary>What a lookup identifies: a movie, or one numbered episode of a show.</summary>
public sealed record IntroDbMedia
{
    private IntroDbMedia(int tmdbId, int? season, int? episode)
    {
        TmdbId = tmdbId;
        Season = season;
        Episode = episode;
    }

    public int TmdbId { get; }
    public int? Season { get; }
    public int? Episode { get; }

    /// <summary>"movie", or "tv" for an episode.</summary>
    public string Type => Season is null ? "movie" : "tv";

    /// <summary>
    /// Null when the id is outside 1…10,000,000, or when only one of season and
    /// episode is given, or either is ≤ 0 (season 0 specials get no lookup).
    /// </summary>
    public static IntroDbMedia? Create(int tmdbId, int? season = null, int? episode = null)
    {
        if (tmdbId is < 1 or > 10_000_000) return null;
        return (season, episode) switch
        {
            (null, null) => new IntroDbMedia(tmdbId, null, null),
            (int s, int e) when s > 0 && e > 0 => new IntroDbMedia(tmdbId, s, e),
            _ => null,
        };
    }

    /// <summary>The lookup identity for a play request: the show's id for episodes; null when unidentified.</summary>
    public static IntroDbMedia? For(PlaybackRequest request) => request.MediaType switch
    {
        "movie" when request.TmdbId is int movieId => Create(movieId),
        "episode" when request.ShowTmdbId is int showId => request.SeasonNumber is int season && request.EpisodeNumber is int episode
            ? Create(showId, season, episode)
            : null,
        _ => null,
    };
}

/// <summary>One lookup: the identity plus the file's duration in whole milliseconds.</summary>
public sealed record IntroDbRequest
{
    public const string Endpoint = "https://api.theintrodb.org/v3/media";

    /// <summary>The provider documents timestamps up to six hours.</summary>
    public const double MaximumDurationSeconds = 21_600;

    private IntroDbRequest(IntroDbMedia media, long durationMilliseconds)
    {
        Media = media;
        DurationMilliseconds = durationMilliseconds;
    }

    public IntroDbMedia Media { get; }
    public long DurationMilliseconds { get; }

    /// <summary>Null for an unknown, non-positive, or over-six-hour duration.</summary>
    public static IntroDbRequest? Create(IntroDbMedia media, double durationSeconds)
    {
        if (!double.IsFinite(durationSeconds) || durationSeconds <= 0 || durationSeconds > MaximumDurationSeconds) return null;
        var milliseconds = (long)Math.Round(durationSeconds * 1000, MidpointRounding.AwayFromZero);
        return milliseconds > 0 ? new IntroDbRequest(media, milliseconds) : null;
    }

    public Uri Uri
    {
        get
        {
            var query = new List<string> { Pair("tmdb_id", Media.TmdbId) };
            if (Media.Season is int season && Media.Episode is int episode)
            {
                query.Add(Pair("season", season));
                query.Add(Pair("episode", episode));
            }
            query.Add(Pair("duration_ms", DurationMilliseconds));
            return new Uri($"{Endpoint}?{string.Join('&', query)}");
        }
    }

    private static string Pair(string name, long value) =>
        $"{name}={value.ToString(CultureInfo.InvariantCulture)}";
}

public enum IntroDbFailure
{
    InvalidResponse,
    BadStatus,
    RateLimited,
}

public sealed class IntroDbException(IntroDbFailure failure, int statusCode = 0)
    : Exception($"TheIntroDB lookup failed ({failure}{(statusCode > 0 ? $", HTTP {statusCode}" : "")}).")
{
    public IntroDbFailure Failure { get; } = failure;
    public int StatusCode { get; } = statusCode;
}

/// <summary>Anonymous, cache-free reads from TheIntroDB.</summary>
public sealed class IntroDbClient
{
    private readonly HttpClient _http;
    private readonly Func<DateTimeOffset> _clock;
    private readonly object _gate = new();
    private DateTimeOffset? _retryAfter;

    /// <param name="handler">A stub in tests; by default a handler with cookies off.</param>
    /// <param name="clock">The time source for the 429 cooldown.</param>
    public IntroDbClient(HttpMessageHandler? handler = null, Func<DateTimeOffset>? clock = null)
    {
        _http = new HttpClient(handler ?? new SocketsHttpHandler
        {
            UseCookies = false,
            AllowAutoRedirect = false,
        })
        {
            Timeout = TimeSpan.FromSeconds(8),
        };
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
    }

    public async Task<IReadOnlyList<MediaSegment>> SegmentsAsync(IntroDbRequest request, CancellationToken cancellation = default)
    {
        lock (_gate)
        {
            if (_retryAfter is { } until && until > _clock()) throw new IntroDbException(IntroDbFailure.RateLimited);
        }

        using var message = new HttpRequestMessage(HttpMethod.Get, request.Uri);
        message.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        message.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true, NoStore = true };

        using var response = await _http.SendAsync(message, cancellation).ConfigureAwait(false);
        cancellation.ThrowIfCancellationRequested();

        if (response.StatusCode == HttpStatusCode.TooManyRequests)
        {
            lock (_gate) _retryAfter = _clock() + TimeSpan.FromSeconds(Cooldown(response));
            throw new IntroDbException(IntroDbFailure.RateLimited, 429);
        }
        if (response.StatusCode == HttpStatusCode.NotFound) return [];
        if (response.StatusCode != HttpStatusCode.OK) throw new IntroDbException(IntroDbFailure.BadStatus, (int)response.StatusCode);

        var json = await response.Content.ReadAsStringAsync(cancellation).ConfigureAwait(false);
        return Decode(json, request);
    }

    /// <summary>
    /// The largest of Retry-After, X-RateLimit-Reset, and X-UsageLimit-Reset
    /// (all seconds remaining, not timestamps), and at least 60 s, so a daily
    /// limit is never retried on the shorter burst schedule.
    /// </summary>
    internal static double Cooldown(HttpResponseMessage response)
    {
        var delays = new List<double>();
        if (response.Headers.RetryAfter is { } retry)
        {
            if (retry.Delta is { } delta) delays.Add(delta.TotalSeconds);
        }
        foreach (var name in new[] { "Retry-After", "X-RateLimit-Reset", "X-UsageLimit-Reset" })
        {
            if (!response.Headers.TryGetValues(name, out var values)) continue;
            foreach (var value in values)
            {
                if (double.TryParse(value, NumberStyles.Float, CultureInfo.InvariantCulture, out var seconds)) delays.Add(seconds);
            }
        }
        var valid = delays.Where(seconds => double.IsFinite(seconds) && seconds > 0).ToList();
        return Math.Max(60, valid.Count > 0 ? valid.Max() : 60);
    }

    /// <summary>
    /// Decodes the intro, recap, and credits arrays. A response for another
    /// title is rejected. Entries with both values null are "no segment"; a
    /// credits range needs a start above 0 and a null end runs to the end of
    /// the file; intro and recap need an end. A range is kept only when
    /// 0 ≤ start &lt; end ≤ duration. Duplicates collapse, ranges that overlap
    /// each other are all dropped (never deciding which content to cut), and
    /// the result is sorted by start.
    /// </summary>
    public static IReadOnlyList<MediaSegment> Decode(string json, IntroDbRequest request)
    {
        JsonElement root;
        try
        {
            using var document = JsonDocument.Parse(json);
            root = document.RootElement.Clone();
        }
        catch (JsonException)
        {
            throw new IntroDbException(IntroDbFailure.InvalidResponse);
        }
        if (root.ValueKind != JsonValueKind.Object) throw new IntroDbException(IntroDbFailure.InvalidResponse);

        if (Integer(root, "tmdb_id") != request.Media.TmdbId
            || String(root, "type") != request.Media.Type
            || Integer(root, "season") != request.Media.Season
            || Integer(root, "episode") != request.Media.Episode)
        {
            throw new IntroDbException(IntroDbFailure.InvalidResponse);
        }

        var duration = request.DurationMilliseconds;
        var segments = new List<MediaSegment>();
        foreach (var (name, kind) in new[]
        {
            ("intro", MediaSegmentKind.Intro),
            ("recap", MediaSegmentKind.Recap),
            ("credits", MediaSegmentKind.Credits),
        })
        {
            if (!root.TryGetProperty(name, out var array) || array.ValueKind == JsonValueKind.Null) continue;
            if (array.ValueKind != JsonValueKind.Array) throw new IntroDbException(IntroDbFailure.InvalidResponse);

            foreach (var entry in array.EnumerateArray())
            {
                if (entry.ValueKind != JsonValueKind.Object) throw new IntroDbException(IntroDbFailure.InvalidResponse);
                var start = OptionalLong(entry, "start_ms");
                var end = OptionalLong(entry, "end_ms");
                if (start is null && end is null) continue;
                if (kind == MediaSegmentKind.Credits)
                {
                    if (start is not > 0) continue;
                }
                else if (end is null)
                {
                    continue;
                }

                var from = start ?? 0;
                var to = end ?? duration;
                if (from < 0 || to <= from || to > duration) continue;
                segments.Add(new MediaSegment(kind, from, to, ReachesEnd: kind == MediaSegmentKind.Credits && to == duration));
            }
        }

        var unique = segments.Distinct().ToList();
        return [.. unique
            .Where(segment => !unique.Any(other => !other.Equals(segment)
                && Math.Max(segment.StartMilliseconds, other.StartMilliseconds) < Math.Min(segment.EndMilliseconds, other.EndMilliseconds)))
            .OrderBy(segment => segment.StartMilliseconds)];
    }

    private static int? Integer(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || value.ValueKind == JsonValueKind.Null) return null;
        if (value.ValueKind != JsonValueKind.Number || !value.TryGetInt32(out var number)) throw new IntroDbException(IntroDbFailure.InvalidResponse);
        return number;
    }

    private static string? String(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || value.ValueKind == JsonValueKind.Null) return null;
        if (value.ValueKind != JsonValueKind.String) throw new IntroDbException(IntroDbFailure.InvalidResponse);
        return value.GetString();
    }

    private static long? OptionalLong(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value) || value.ValueKind == JsonValueKind.Null) return null;
        if (value.ValueKind != JsonValueKind.Number || !value.TryGetInt64(out var number)) throw new IntroDbException(IntroDbFailure.InvalidResponse);
        return number;
    }
}
