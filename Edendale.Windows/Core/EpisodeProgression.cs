using Edendale.Windows.Models;
using Edendale.Windows.Services;

namespace Edendale.Windows.Core;

/// <summary>A next-up suggestion for Continue Watching: the show record that holds the episode.</summary>
public sealed record NextUpEpisode(LibraryShow Show, LibraryEpisode Episode, long LastWatchedEpochMillis);

/// <summary>
/// Episode order for auto-advance, the Up Next card, and the Continue
/// Watching next-up suggestion (DIFF.md §3.4). Ports PlayerLogic.swift's
/// episode rules; the cases come from EpisodeProgressionTests,
/// UpcomingEpisodePreviewTests, and ContinueWatchingTests.
/// </summary>
public static class EpisodeProgression
{
    /// <summary>How close to the end the Up Next card appears.</summary>
    public static readonly TimeSpan UpcomingPreviewThreshold = TimeSpan.FromSeconds(30);

    private static bool After(int season, int episode, int otherSeason, int otherEpisode) =>
        season > otherSeason || season == otherSeason && episode > otherEpisode;

    /// <summary>
    /// The stored episode with the smallest (season, episode) strictly after
    /// <paramref name="current"/>, crossing seasons. Duplicate files of the
    /// current episode are skipped; specials (season 0) advance among
    /// themselves and then into season 1, while main seasons never fall back to
    /// season 0. Null when <paramref name="current"/> isn't in the show or is last.
    /// </summary>
    public static LibraryEpisode? NextEpisode(LibraryEpisode current, LibraryShow show)
    {
        if (!show.Episodes.Any(episode => episode.Id == current.Id)) return null;

        LibraryEpisode? best = null;
        foreach (var candidate in show.Episodes)
        {
            if (!After(candidate.Season, candidate.Episode, current.Season, current.Episode)) continue;
            if (best is null || After(best.Season, best.Episode, candidate.Season, candidate.Episode))
            {
                best = candidate;
            }
        }
        return best;
    }

    /// <summary>
    /// The episode the Up Next card offers, or null when it should be hidden:
    /// only in the last 30 s of an episode with a stored successor, never with
    /// Loop on, for movies, or when the duration is unknown. Recomputed on
    /// every tick, so seeking back hides it again.
    /// </summary>
    public static LibraryEpisode? UpcomingEpisode(
        TimeSpan time,
        TimeSpan? duration,
        bool loopEnabled,
        LibraryEpisode? episode,
        LibraryShow? show)
    {
        if (duration is not { } length || length <= TimeSpan.Zero) return null;
        if (loopEnabled || episode is null || show is null) return null;
        var remaining = length - time;
        if (remaining > UpcomingPreviewThreshold || remaining <= TimeSpan.Zero) return null;
        return NextEpisode(episode, show);
    }

    /// <summary>The furthest completed (season, episode) per show TMDB id; movies are ignored.</summary>
    public static Dictionary<int, (int Season, int Episode, long LastWatchedEpochMillis)> HighestCompletedPerShow(
        IEnumerable<WatchProgress> entries)
    {
        var result = new Dictionary<int, (int Season, int Episode, long LastWatchedEpochMillis)>();
        foreach (var entry in entries)
        {
            if (entry.MediaType != "episode" || !entry.IsCompleted) continue;
            if (entry.ShowTmdbId is not int showId
                || entry.SeasonNumber is not int season
                || entry.EpisodeNumber is not int number)
            {
                continue;
            }

            if (!result.TryGetValue(showId, out var existing) || After(season, number, existing.Season, existing.Episode))
            {
                result[showId] = (season, number, entry.LastWatchedEpochMillis);
            }
        }
        return result;
    }

    /// <summary>
    /// For every show with a completed episode and nothing in progress, the
    /// stored episode after the furthest completed one. Works after the
    /// watched file was deleted (the season/episode numbers still locate the
    /// successor), merges duplicate show records into one suggestion, and
    /// never writes progress.
    /// </summary>
    public static List<NextUpEpisode> NextUpEpisodes(
        IEnumerable<WatchProgress> allProgress,
        IReadOnlySet<int> inProgressShowTmdbIds,
        IEnumerable<LibraryShow> shows)
    {
        var highest = HighestCompletedPerShow(allProgress);
        var candidates = new Dictionary<int, (LibraryShow Show, LibraryEpisode Episode)>();
        foreach (var show in shows)
        {
            if (show.TmdbId is not int showId || inProgressShowTmdbIds.Contains(showId)) continue;
            if (!highest.TryGetValue(showId, out var completed)) continue;

            foreach (var episode in show.Episodes)
            {
                if (!After(episode.Season, episode.Episode, completed.Season, completed.Episode)) continue;
                if (candidates.TryGetValue(showId, out var existing)
                    && !After(existing.Episode.Season, existing.Episode.Episode, episode.Season, episode.Episode))
                {
                    continue;
                }
                candidates[showId] = (show, episode);
            }
        }

        return [.. candidates
            .Select(pair => new NextUpEpisode(pair.Value.Show, pair.Value.Episode, highest[pair.Key].LastWatchedEpochMillis))
            .OrderByDescending(entry => entry.LastWatchedEpochMillis)];
    }
}
