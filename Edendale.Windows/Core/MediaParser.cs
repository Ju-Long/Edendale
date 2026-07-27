using System.Text.RegularExpressions;
using Edendale.Windows.Models;

namespace Edendale.Windows.Core;

/// <summary>
/// Classifies a filename locally before any metadata lookup. Import never
/// waits for the network and an unrecognised name remains a playable movie.
/// </summary>
internal static partial class MediaParser
{
    [GeneratedRegex(@"^(.+?)[. _\-][Ss](\d{1,2})[Ee](\d{1,2})", RegexOptions.IgnoreCase)]
    private static partial Regex SeasonEpisodePattern();

    [GeneratedRegex(@"^(.+?)[. _\-](\d{1,2})x(\d{2})(?:[. _\-]|$)", RegexOptions.IgnoreCase)]
    private static partial Regex NumberXNumberPattern();

    [GeneratedRegex(@"[. _\[(]((19|20)\d{2})(?:[. _\])]|$)")]
    private static partial Regex YearPattern();

    [GeneratedRegex(@"\s+")]
    private static partial Regex WhitespacePattern();

    public static ParsedMedia Parse(string fileName)
    {
        var leaf = fileName
            .Replace('\\', '/')
            .Split('/', StringSplitOptions.RemoveEmptyEntries)
            .LastOrDefault() ?? fileName;
        var extension = leaf.LastIndexOf('.');
        var baseName = extension > 0 ? leaf[..extension] : leaf;

        foreach (var pattern in new[] { SeasonEpisodePattern(), NumberXNumberPattern() })
        {
            var match = pattern.Match(baseName);
            if (!match.Success) continue;

            return new ParsedMedia
            {
                Kind = "episode",
                ShowName = CleanTitle(match.Groups[1].Value),
                Season = int.TryParse(match.Groups[2].Value, out var season) ? season : 1,
                Episode = int.TryParse(match.Groups[3].Value, out var episode) ? episode : 1,
            };
        }

        var yearMatch = YearPattern().Match(baseName);
        if (!yearMatch.Success)
        {
            return new ParsedMedia { Kind = "movie", Title = CleanTitle(baseName) };
        }

        return new ParsedMedia
        {
            Kind = "movie",
            Title = CleanTitle(baseName[..yearMatch.Index]),
            Year = int.TryParse(yearMatch.Groups[1].Value, out var year) ? year : null,
        };
    }

    private static string CleanTitle(string value) =>
        WhitespacePattern()
            .Replace(value.Replace('.', ' ').Replace('_', ' ').Replace('-', ' '), " ")
            .Trim();
}
