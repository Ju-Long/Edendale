// Managed HTTP boundary for the Wyzie Subs API (https://docs.wyzie.io).
//
// One endpoint, one call: GET /search returns the matching subtitles with a
// direct link to each file, so there is no login, no session, and no
// per-download ticket. Authentication is a single API key carried as a query
// parameter, which is what the service specifies.

using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services;

/// <summary>What to search for. Wyzie matches on an id, so one is required.</summary>
internal sealed record SubtitleQuery
{
    /// <summary>
    /// TMDB id, or an IMDB id in its "tt…" form — the service tells them apart
    /// by the prefix. For an episode this is the series, narrowed by
    /// <see cref="Season"/> and <see cref="Episode"/>.
    /// </summary>
    public required string Id { get; init; }

    public int? Season { get; init; }
    public int? Episode { get; init; }

    /// <summary>ISO 639-1 codes; empty searches every language.</summary>
    public IReadOnlyList<string> Languages { get; init; } = [];
}

/// <summary>Raised when the service refuses a call; the message is reader-facing.</summary>
public sealed class SubtitleServiceException(string message, bool quotaExhausted = false)
    : Exception(message)
{
    /// <summary>The key's daily request allowance is spent.</summary>
    public bool QuotaExhausted { get; } = quotaExhausted;
}

internal sealed class WyzieClient
{
    private const string SearchEndpoint = "https://sub.wyzie.io/search";

    /// <summary>
    /// Only formats the Windows timed-text reader can render. Asking for the
    /// rest would list results that download fine and then show nothing.
    /// </summary>
    private const string SupportedFormats = "srt";

    private static readonly HttpClient Http = CreateHttpClient();

    private readonly string _apiKey;

    public WyzieClient() => _apiKey = WyzieCredentials.LoadApiKey();

    /// <summary>False when no API key was supplied at build time; the feature stays hidden.</summary>
    public bool IsConfigured => !string.IsNullOrWhiteSpace(_apiKey);

    /// <summary>GET /search — ordered best-first by <see cref="SubtitleRanking"/>.</summary>
    public async Task<IReadOnlyList<SubtitleCandidate>> SearchAsync(
        SubtitleQuery query, CancellationToken cancellationToken = default)
    {
        if (!IsConfigured) throw new SubtitleServiceException(AppText.Get("Subtitles_NotConfigured"));
        if (string.IsNullOrWhiteSpace(query.Id)) return [];

        var parameters = new List<KeyValuePair<string, string>>
        {
            new("id", query.Id),
            new("format", SupportedFormats),
            new("key", _apiKey),
        };

        // The service requires the pair or neither.
        if (query.Season is int season && query.Episode is int episode)
        {
            parameters.Add(new("season", season.ToString()));
            parameters.Add(new("episode", episode.ToString()));
        }

        if (query.Languages.Count > 0)
        {
            parameters.Add(new("language", string.Join(",", query.Languages)));
        }

        var target = SearchEndpoint + "?" + string.Join(
            "&",
            parameters.Select(pair =>
                $"{Uri.EscapeDataString(pair.Key)}={Uri.EscapeDataString(pair.Value)}"));

        var payload = await GetStringAsync(new Uri(target), cancellationToken);

        List<SubtitleCandidate> candidates = [];
        try
        {
            using var document = JsonDocument.Parse(payload);
            var root = document.RootElement;

            // Results come back as a bare array; an error object is the shape
            // the service uses when something is wrong with the request.
            if (root.ValueKind == JsonValueKind.Object)
            {
                throw new SubtitleServiceException(
                    root.NonBlankString("message")
                    ?? root.NonBlankString("error")
                    ?? AppText.Get("Subtitles_RequestFailed"));
            }

            if (root.ValueKind != JsonValueKind.Array) return [];

            foreach (var entry in root.EnumerateArray())
            {
                if (ReadCandidate(entry) is { } candidate) candidates.Add(candidate);
            }
        }
        catch (JsonException)
        {
            throw new SubtitleServiceException(AppText.Get("Subtitles_RequestFailed"));
        }

        return SubtitleRanking.Order(candidates, query.Languages);
    }

    /// <summary>
    /// Fetches a subtitle file as bytes. The text is decoded by the caller
    /// using the character set the search reported, which is rarely UTF-8.
    /// </summary>
    public async Task<byte[]> DownloadAsync(Uri url, CancellationToken cancellationToken = default)
    {
        try
        {
            using var response = await Http.GetAsync(url, cancellationToken);
            if (!response.IsSuccessStatusCode)
            {
                throw new SubtitleServiceException(AppText.Get("Subtitles_DownloadFailed"));
            }
            return await response.Content.ReadAsByteArrayAsync(cancellationToken);
        }
        catch (HttpRequestException)
        {
            throw new SubtitleServiceException(AppText.Get("Subtitles_Offline"));
        }
        catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new SubtitleServiceException(AppText.Get("Subtitles_Offline"));
        }
    }

    private static async Task<string> GetStringAsync(Uri target, CancellationToken cancellationToken)
    {
        HttpResponseMessage response;
        string payload;
        try
        {
            response = await Http.GetAsync(target, cancellationToken);
            payload = await response.Content.ReadAsStringAsync(cancellationToken);
        }
        catch (HttpRequestException)
        {
            throw new SubtitleServiceException(AppText.Get("Subtitles_Offline"));
        }
        catch (TaskCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new SubtitleServiceException(AppText.Get("Subtitles_Offline"));
        }

        using (response)
        {
            if (!response.IsSuccessStatusCode) throw Failure(response.StatusCode, payload);
            return payload;
        }
    }

    /// <summary>
    /// Turns a refusal into copy the reader can act on. The service's own
    /// message is preferred when it sends one, since it explains cases this
    /// app cannot enumerate.
    /// </summary>
    private static SubtitleServiceException Failure(HttpStatusCode status, string payload)
    {
        var detail = TryReadMessage(payload);

        return status switch
        {
            HttpStatusCode.TooManyRequests => new SubtitleServiceException(
                detail ?? AppText.Get("Subtitles_QuotaReached"), quotaExhausted: true),

            HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden => new SubtitleServiceException(
                detail ?? AppText.Get("Subtitles_KeyRejected")),

            HttpStatusCode.NotFound => new SubtitleServiceException(
                AppText.Get("Subtitles_NoResults")),

            _ => new SubtitleServiceException(
                detail ?? AppText.Format("Subtitles_RequestFailedCode", (int)status)),
        };
    }

    private static string? TryReadMessage(string payload)
    {
        try
        {
            using var document = JsonDocument.Parse(payload);
            if (document.RootElement.ValueKind != JsonValueKind.Object) return null;
            return document.RootElement.NonBlankString("message")
                ?? document.RootElement.NonBlankString("error");
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static SubtitleCandidate? ReadCandidate(JsonElement entry)
    {
        if (entry.ValueKind != JsonValueKind.Object) return null;

        var url = entry.NonBlankString("url");
        if (url is null) return null;

        var id = entry.NonBlankString("id") ?? url;

        return new SubtitleCandidate
        {
            Id = id,
            Url = url,
            Language = entry.NonBlankString("language") ?? "",
            Display = entry.NonBlankString("display"),
            Release = entry.NonBlankString("release") ?? FirstRelease(entry),
            FileName = entry.NonBlankString("fileName"),
            Format = entry.NonBlankString("format"),
            Encoding = entry.NonBlankString("encoding"),
            Source = ReadSource(entry),
            Origin = entry.NonBlankString("origin"),
            IsHearingImpaired = Flag(entry, "isHearingImpaired"),
            IsAiTranslated = Flag(entry, "ai"),
            DownloadCount = entry.Int("downloadCount") ?? 0,
        };
    }

    /// <summary>Falls back to the release list when the single field is absent.</summary>
    private static string? FirstRelease(JsonElement entry) =>
        entry.Array("releases")
            .Where(value => value.ValueKind == JsonValueKind.String)
            .Select(value => value.GetString())
            .FirstOrDefault(value => !string.IsNullOrWhiteSpace(value));

    /// <summary>The service sends a provider name, or a list of them.</summary>
    private static string? ReadSource(JsonElement entry) => entry.Property("source") switch
    {
        { ValueKind: JsonValueKind.String } value => value.GetString(),
        { ValueKind: JsonValueKind.Array } value => string.Join(
            ", ",
            value.EnumerateArray()
                .Where(item => item.ValueKind == JsonValueKind.String)
                .Select(item => item.GetString())),
        _ => null,
    };

    /// <summary>Reads a flag the service sends as a bool, a 0/1 number, or omits.</summary>
    private static bool Flag(JsonElement element, string name) =>
        element.Property(name) switch
        {
            { ValueKind: JsonValueKind.True } => true,
            { ValueKind: JsonValueKind.Number } value => value.TryGetInt32(out var number) && number != 0,
            _ => false,
        };

    private static HttpClient CreateHttpClient()
    {
        var client = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        client.DefaultRequestHeaders.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Edendale-Windows/1.0");
        return client;
    }
}

/// <summary>
/// The application's Wyzie API key. Read from the environment, then from the
/// gitignored local secrets file that tools/Edendale.Secrets writes and the
/// build embeds — never from source control.
/// </summary>
internal static class WyzieCredentials
{
    private const string ResourceName = "Edendale.LocalSecrets";

    public static string LoadApiKey()
    {
        var fromEnvironment = Environment.GetEnvironmentVariable("WYZIE_API_KEY");
        if (!string.IsNullOrWhiteSpace(fromEnvironment)) return fromEnvironment.Trim();

        try
        {
            var assembly = typeof(WyzieCredentials).Assembly;
            using var stream = assembly.GetManifestResourceStream(ResourceName);
            if (stream is null) return "";
            using var document = JsonDocument.Parse(stream);
            return document.RootElement.String("WYZIE_API_KEY")?.Trim() ?? "";
        }
        catch (JsonException)
        {
            return "";
        }
    }
}
