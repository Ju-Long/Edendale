// Young Audience certification rules. List responses do not carry
// certifications, so these operate on the dedicated TMDB payloads
// (/movie/{id}/release_dates and /tv/{id}/content_ratings). Pure and testable;
// ports YoungAudienceCertificationPolicy + the payload parsing from the Apple
// branch's YoungAudienceFilter.swift.

using System.Text.Json;
using Edendale.Windows.Services;

namespace Edendale.Windows.Core;

public static class YoungAudienceCertificationPolicy
{
    /// <summary>
    /// PG and PG-13 are accepted exactly after normalizing punctuation and
    /// spacing. Television services use TV-PG / TV-14 in regions such as the
    /// United States, while regions such as Singapore use PG / PG13 for both.
    /// </summary>
    public static bool Allows(string? certification, string mediaType)
    {
        var normalized = Normalize(certification);
        if (normalized is "PG" or "PG13") return true;
        return mediaType == "tv" && normalized is "TVPG" or "TV14";
    }

    internal static string Normalize(string? certification) =>
        new([.. (certification ?? "").ToUpperInvariant().Where(char.IsLetterOrDigit)]);
}

/// <summary>Extracts a region's certification from a TMDB certification payload.</summary>
public static class ContentCertification
{
    // Movie release type → precedence (lower wins). Theatrical (3) is preferred,
    // then limited theatrical (2), digital (4), physical (5), TV (6), premiere (1).
    private static readonly Dictionary<int, int> MoviePriority = new()
    {
        [3] = 0, [2] = 1, [4] = 2, [5] = 3, [6] = 4, [1] = 5,
    };

    /// <summary>The certification for <paramref name="regionCode"/> in a /release_dates response.</summary>
    public static string? Movie(JsonElement response, string regionCode)
    {
        var region = response.Array("results").FirstOrDefault(entry =>
            string.Equals(entry.String("iso_3166_1"), regionCode, StringComparison.OrdinalIgnoreCase));
        if (region.ValueKind != JsonValueKind.Object) return null;

        var rated = region.Array("release_dates")
            .Where(release => !string.IsNullOrWhiteSpace(release.String("certification")))
            .ToList();
        if (rated.Count == 0) return null;

        static int Priority(JsonElement release) =>
            release.Int("type") is int type && MoviePriority.TryGetValue(type, out var priority)
                ? priority
                : int.MaxValue;

        var best = rated.Min(Priority);
        var preferred = rated.Where(release => Priority(release) == best).ToList();

        var distinct = preferred
            .Select(release => YoungAudienceCertificationPolicy.Normalize(release.String("certification")))
            .ToHashSet(StringComparer.Ordinal);
        // Different certifications at the same preferred tier can represent
        // separate edits/cuts. Never pick the more permissive one — fail closed.
        if (distinct.Count != 1) return null;

        return preferred
            .OrderBy(release => release.String("release_date") ?? "9999", StringComparer.Ordinal)
            .First()
            .String("certification")?
            .Trim();
    }

    /// <summary>The rating for <paramref name="regionCode"/> in a /content_ratings response.</summary>
    public static string? Tv(JsonElement response, string regionCode)
    {
        var match = response.Array("results").FirstOrDefault(entry =>
            string.Equals(entry.String("iso_3166_1"), regionCode, StringComparison.OrdinalIgnoreCase)
                && !string.IsNullOrWhiteSpace(entry.String("rating")));
        return match.ValueKind == JsonValueKind.Object ? match.String("rating")?.Trim() : null;
    }
}
