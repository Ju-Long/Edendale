// Application-level half of the Wyzie Subs integration: turns a
// PlaybackRequest into a search, and caches downloaded files so re-selecting
// one costs nothing.
//
// Wyzie matches on a TMDB or IMDB id, so only items the library has matched to
// TMDB can be searched — see CanSearch. There is no account and no session to
// keep, only the build's API key.
//
// Nothing here runs on import or on the playback fast path: a search happens
// only when the reader opens the subtitle browser, and a download only when
// they pick a result.

using System.Globalization;
using System.Text;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services;

/// <summary>A downloaded subtitle sitting on disk, ready to attach to the player.</summary>
public sealed record DownloadedSubtitle
{
    public required string FilePath { get; init; }
    public required SubtitleCandidate Candidate { get; init; }

    /// <summary>True when the file was already cached, so nothing was fetched.</summary>
    public bool FromCache { get; init; }
}

public sealed class SubtitleService
{
    private readonly WyzieClient _client = new();

    static SubtitleService()
    {
        // Subtitles are routinely published in a legacy code page — Cyrillic,
        // Greek, Turkish. .NET Core ships only Unicode by default, so register
        // the rest before any of them is looked up.
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
    }

    /// <summary>False when the build carries no API key; the UI hides the feature.</summary>
    public bool IsConfigured => _client.IsConfigured;

    /// <summary>Languages to search for, most wanted first.</summary>
    public static IReadOnlyList<string> PreferredLanguages =>
        SubtitleLanguages.Preferred(CultureInfo.CurrentUICulture);

    /// <summary>
    /// Whether <paramref name="request"/> can be searched at all. Wyzie looks
    /// items up by id, so a file the library never matched to TMDB — anything
    /// opened straight from the shell, or an import still awaiting metadata —
    /// has nothing to search by.
    /// </summary>
    public static bool CanSearch(PlaybackRequest request) => SearchId(request) is not null;

    /// <summary>
    /// The id to search by: the series for an episode, so the season and
    /// episode numbers narrow it, and the film itself otherwise.
    /// </summary>
    private static string? SearchId(PlaybackRequest request)
    {
        var isEpisode = IsEpisode(request);
        var id = isEpisode ? request.ShowTmdbId : request.TmdbId;
        return id is int value ? value.ToString(CultureInfo.InvariantCulture) : null;
    }

    private static bool IsEpisode(PlaybackRequest request) =>
        string.Equals(request.MediaType, "episode", StringComparison.Ordinal);

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /// <summary>Subtitles for the playing item, best match first.</summary>
    public async Task<IReadOnlyList<SubtitleCandidate>> SearchAsync(
        PlaybackRequest request,
        IReadOnlyList<string>? languages = null,
        CancellationToken cancellationToken = default)
    {
        if (SearchId(request) is not { } id) return [];

        var isEpisode = IsEpisode(request);
        var query = new SubtitleQuery
        {
            Id = id,
            Languages = languages is { Count: > 0 } ? languages : PreferredLanguages,
            // The service takes the pair or neither, so send it only when both
            // numbers are known.
            Season = isEpisode ? request.SeasonNumber : null,
            Episode = isEpisode ? request.EpisodeNumber : null,
        };

        return await _client.SearchAsync(query, cancellationToken);
    }

    // ------------------------------------------------------------------
    // Download
    // ------------------------------------------------------------------

    /// <summary>
    /// Fetches <paramref name="candidate"/> to the local cache and returns its
    /// path, reusing a file already there.
    /// </summary>
    public async Task<DownloadedSubtitle> DownloadAsync(
        SubtitleCandidate candidate, CancellationToken cancellationToken = default)
    {
        var destination = CachePath(candidate);
        if (File.Exists(destination))
        {
            return new DownloadedSubtitle
            {
                FilePath = destination,
                Candidate = candidate,
                FromCache = true,
            };
        }

        if (!Uri.TryCreate(candidate.Url, UriKind.Absolute, out var url))
        {
            throw new SubtitleServiceException(AppText.Get("Subtitles_DownloadFailed"));
        }

        var bytes = await _client.DownloadAsync(url, cancellationToken);
        var text = Decode(bytes, candidate.Encoding);

        // Re-written as UTF-8 with a BOM so the player's timed-text reader has
        // nothing left to guess about, whatever the upload's original code page.
        await File.WriteAllTextAsync(destination, text, new UTF8Encoding(true), cancellationToken);

        return new DownloadedSubtitle { FilePath = destination, Candidate = candidate };
    }

    /// <summary>
    /// Decodes a subtitle body using the character set the search reported. A
    /// byte-order mark in the file wins over the declared name, and anything
    /// unrecognised falls back to UTF-8 rather than failing the download.
    /// </summary>
    internal static string Decode(byte[] bytes, string? declaredEncoding)
    {
        if (HasUtf8Bom(bytes)) return new UTF8Encoding(true).GetString(bytes, 3, bytes.Length - 3);

        var encoding = ResolveEncoding(declaredEncoding);
        return encoding.GetString(bytes);
    }

    private static bool HasUtf8Bom(byte[] bytes) =>
        bytes.Length >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF;

    /// <summary>
    /// Maps the service's encoding name onto a .NET one. Providers use several
    /// spellings for the same code page, and a name .NET does not know must
    /// not take the download down with it.
    /// </summary>
    internal static Encoding ResolveEncoding(string? name)
    {
        if (string.IsNullOrWhiteSpace(name)) return Encoding.UTF8;

        var normalized = name.Trim().ToLowerInvariant().Replace("_", "-");
        normalized = normalized switch
        {
            "latin-1" or "latin1" => "iso-8859-1",
            "cp1250" or "windows1250" => "windows-1250",
            "cp1251" or "windows1251" => "windows-1251",
            "cp1252" or "windows1252" => "windows-1252",
            "cp1253" or "windows1253" => "windows-1253",
            "cp1254" or "windows1254" => "windows-1254",
            "cp1255" or "windows1255" => "windows-1255",
            "cp1256" or "windows1256" => "windows-1256",
            "cp1257" or "windows1257" => "windows-1257",
            "utf8" => "utf-8",
            "ascii" or "us-ascii" => "utf-8",   // UTF-8 is a superset; avoids mangling stray bytes
            _ => normalized,
        };

        try
        {
            return Encoding.GetEncoding(normalized);
        }
        catch (ArgumentException)
        {
            return Encoding.UTF8;
        }
    }

    /// <summary>Stable per-file name, so the cache hits on a second selection.</summary>
    private static string CachePath(SubtitleCandidate candidate)
    {
        var name = Sanitize(candidate.Id);
        if (name.Length == 0) name = Math.Abs(candidate.Url.GetHashCode()).ToString(CultureInfo.InvariantCulture);

        var language = Sanitize(candidate.Language);
        var leaf = language.Length == 0 ? $"{name}.srt" : $"{name}.{language}.srt";
        return Path.Combine(AppPaths.SubtitleCacheDirectory, leaf);
    }

    private static string Sanitize(string value)
    {
        var invalid = Path.GetInvalidFileNameChars();
        var builder = new StringBuilder(value.Length);
        foreach (var character in value)
        {
            if (!invalid.Contains(character) && character != '.') builder.Append(character);
        }
        return builder.ToString();
    }
}
