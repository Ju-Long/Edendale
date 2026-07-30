// Local half of the Wyzie Subs integration: the candidate record the rest of
// the app passes around, the ranking that decides what the reader sees first,
// and the language mapping. All of it is pure and offline, so it lives in Core
// and is covered by Edendale.Windows.Tests.

using System.Globalization;

namespace Edendale.Windows.Core;

/// <summary>One downloadable subtitle from a Wyzie search.</summary>
public sealed record SubtitleCandidate
{
    /// <summary>Service-assigned id; also the cache key for the downloaded file.</summary>
    public required string Id { get; init; }

    /// <summary>Direct link to the subtitle file — Wyzie needs no download ticket.</summary>
    public required string Url { get; init; }

    /// <summary>ISO 639-1 code as returned.</summary>
    public required string Language { get; init; }

    /// <summary>The provider's own label for the language, e.g. "Brazilian Portuguese".</summary>
    public string? Display { get; init; }

    /// <summary>Release the upload was timed against ("The.Movie.2019.1080p.BluRay").</summary>
    public string? Release { get; init; }

    public string? FileName { get; init; }

    /// <summary>"srt", "ass", … — only formats the player can render are requested.</summary>
    public string? Format { get; init; }

    /// <summary>Character set of the file, used to decode it correctly on download.</summary>
    public string? Encoding { get; init; }

    /// <summary>Which provider the upload came from.</summary>
    public string? Source { get; init; }

    /// <summary>WEB, BLURAY, DVD — where the release was ripped from.</summary>
    public string? Origin { get; init; }

    public bool IsHearingImpaired { get; init; }

    /// <summary>Machine-generated rather than human-authored.</summary>
    public bool IsAiTranslated { get; init; }

    public int DownloadCount { get; init; }

    /// <summary>The provider's label when it has one, otherwise the bare code.</summary>
    public string LanguageLabel =>
        string.IsNullOrWhiteSpace(Display) ? Language : Display;
}

/// <summary>Orders search results so the most likely subtitle is the first one.</summary>
internal static class SubtitleRanking
{
    /// <summary>
    /// The reader's language preference leads, then human-authored uploads
    /// over machine ones, and popularity only as the tie-break. Wyzie matches
    /// on the TMDB id rather than a file hash, so there is no exact-timing
    /// signal to rank above any of this.
    /// </summary>
    public static IReadOnlyList<SubtitleCandidate> Order(
        IEnumerable<SubtitleCandidate> candidates,
        IReadOnlyList<string> preferredLanguages)
    {
        return
        [
            .. candidates
                .OrderBy(candidate => LanguageRank(candidate.Language, preferredLanguages))
                .ThenBy(candidate => candidate.IsAiTranslated)
                .ThenByDescending(candidate => candidate.DownloadCount)
                .ThenBy(candidate => candidate.Id, StringComparer.Ordinal)
        ];
    }

    /// <summary>
    /// Position in the preferred list, or one past the end for anything absent
    /// — an unlisted language sorts last but is never dropped.
    /// </summary>
    private static int LanguageRank(string language, IReadOnlyList<string> preferredLanguages)
    {
        for (var index = 0; index < preferredLanguages.Count; index++)
        {
            if (LanguageMatches(language, preferredLanguages[index])) return index;
        }
        return preferredLanguages.Count;
    }

    /// <summary>
    /// "pt-BR" matches "pt" regionlessly, so a preference for Portuguese still
    /// ranks a Brazilian upload ahead of an unrelated language even when a
    /// provider returns a regional code.
    /// </summary>
    public static bool LanguageMatches(string language, string preference)
    {
        if (string.IsNullOrWhiteSpace(language) || string.IsNullOrWhiteSpace(preference)) return false;
        if (string.Equals(language, preference, StringComparison.OrdinalIgnoreCase)) return true;
        return string.Equals(BaseLanguage(language), BaseLanguage(preference), StringComparison.OrdinalIgnoreCase);
    }

    private static string BaseLanguage(string code)
    {
        var separator = code.IndexOfAny(['-', '_']);
        return separator < 0 ? code : code[..separator];
    }
}

/// <summary>
/// Maps a .NET culture onto the ISO 639-1 codes the Wyzie search expects.
/// The service takes no region, so every culture collapses to two letters and
/// regional variants are told apart afterwards by each result's own label.
/// </summary>
internal static class SubtitleLanguages
{
    /// <summary>The search code for <paramref name="culture"/>.</summary>
    public static string ForCulture(CultureInfo culture)
    {
        var code = culture.TwoLetterISOLanguageName;
        return string.IsNullOrWhiteSpace(code) || code == "iv" ? "en" : code.ToLowerInvariant();
    }

    /// <summary>
    /// Languages to search for, most wanted first: the reader's UI language,
    /// then English as the near-universal fallback.
    /// </summary>
    public static IReadOnlyList<string> Preferred(CultureInfo culture)
    {
        var primary = ForCulture(culture);
        return primary == "en" ? ["en"] : [primary, "en"];
    }

    /// <summary>
    /// Codes offered in the language filter: every locale Edendale ships in,
    /// plus the largest subtitle languages on the service.
    /// </summary>
    public static readonly IReadOnlyList<string> Offered =
    [
        "en", "es", "pt", "fr", "de", "it", "nl", "sv",
        "ru", "pl", "tr", "ar", "he", "el", "cs", "hu", "ro", "fi", "da", "no",
        "ja", "ko", "zh", "hi", "id", "th", "vi", "uk",
    ];

    /// <summary>
    /// The language's own name in the reader's UI language, so the filter
    /// needs no translated copy of its own. Falls back to the raw code for
    /// anything .NET does not recognise.
    /// </summary>
    public static string DisplayName(string code)
    {
        try
        {
            var name = CultureInfo.GetCultureInfo(code).DisplayName;
            return string.IsNullOrWhiteSpace(name) ? code : name;
        }
        catch (CultureNotFoundException)
        {
            return code;
        }
    }
}
