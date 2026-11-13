using Edendale.Windows.Models;
using Edendale.Windows.Services;

namespace Edendale.Windows.Core;

/// <summary>A next-up suggestion for Continue Watching: the show record that holds the episode.</summary>
public sealed record NextUpEpisode(LibraryShow Show, LibraryEpisode Episode, long LastWatchedEpochMillis);

/// <summary>
/// One episode file and the show record holding it: what the player opens
/// next, since the next episode can sit in another record of the title.
/// </summary>
public sealed record ShowEpisode(LibraryShow Show, LibraryEpisode Episode);

/// <summary>
/// Episode order for auto-advance, the Up Next card, and the Continue
/// Watching next-up suggestion (DIFF.md §3.4). Ports PlayerLogic.swift's
/// episode rules; the cases come from EpisodeProgressionTests,
/// UpcomingEpisodePreviewTests, ContinueWatchingTests, and (for titles in
/// several sources) MultipleSourcesTests at apple-27.1 94a8b4f.
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
    public static LibraryEpisode? NextEpisode(LibraryEpisode current, LibraryShow show) =>
        NextEpisode(current, [show], _ => false, _ => null)?.Episode;

    /// <summary>
    /// The stored episode just before <paramref name="current"/>, for the
    /// Previous media key: the mirror of <see cref="NextEpisode(LibraryEpisode, LibraryShow)"/>.
    /// Main seasons never step back into season 0 specials; duplicates of the
    /// current episode are skipped.
    /// </summary>
    public static LibraryEpisode? PreviousEpisode(LibraryEpisode current, LibraryShow show) =>
        PreviousEpisode(current, [show], _ => false, _ => null)?.Episode;

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
        LibraryShow? show) =>
        show is null ? null : UpcomingEpisode(time, duration, loopEnabled, episode, [show], _ => false, _ => null)?.Episode;

    // ------------------------------------------------------------------
    // Across the copies of a show (27.1: titles in several sources)
    // ------------------------------------------------------------------

    /// <summary>
    /// The next stored episode across every copy of the show
    /// (PlayerLogic.nextEpisode(after:in:otherCopies:isOffline:), apple-27.1
    /// 94a8b4f). <paramref name="copies"/> are the records of the playing
    /// title; a record can hold several sources, so the playing copy is
    /// <paramref name="current"/>'s source (its folder). That source keeps
    /// playing whenever it holds the next (season, episode), even while it is
    /// flagged unavailable. Only an episode it lacks comes from another
    /// source: the first, local first and then by name and path, that isn't
    /// offline or waiting for sign-in, else the first. An episode only
    /// another source holds is never skipped, and the one after it comes back
    /// to the playing source when that source has it. Null unless a copy
    /// holds <paramref name="current"/>, or when it is the last.
    /// </summary>
    public static ShowEpisode? NextEpisode(
        LibraryEpisode current,
        IReadOnlyList<LibraryShow> copies,
        Func<Guid, bool> isUnavailable,
        Func<Guid, LibraryFolder?> folder)
    {
        if (Holding(current, copies) is not { } shows) return null;

        (int Season, int Episode)? next = null;
        foreach (var candidate in shows.SelectMany(show => show.Episodes))
        {
            if (!After(candidate.Season, candidate.Episode, current.Season, current.Episode)) continue;
            if (next is not { } best || After(best.Season, best.Episode, candidate.Season, candidate.Episode))
            {
                next = (candidate.Season, candidate.Episode);
            }
        }
        return next is { } slot ? ChooseSource(Slot(shows, slot), current.FolderId, isUnavailable, folder) : null;
    }

    /// <summary>
    /// The mirror of <see cref="NextEpisode(LibraryEpisode, IReadOnlyList{LibraryShow}, Func{Guid, bool}, Func{Guid, LibraryFolder?})"/>
    /// for the Previous key and button, a Windows addition: the latest stored
    /// episode before <paramref name="current"/> across every copy, from a
    /// source chosen the same way. Main seasons never step back into season 0.
    /// </summary>
    public static ShowEpisode? PreviousEpisode(
        LibraryEpisode current,
        IReadOnlyList<LibraryShow> copies,
        Func<Guid, bool> isUnavailable,
        Func<Guid, LibraryFolder?> folder)
    {
        if (Holding(current, copies) is not { } shows) return null;

        (int Season, int Episode)? previous = null;
        foreach (var candidate in shows.SelectMany(show => show.Episodes))
        {
            if (!After(current.Season, current.Episode, candidate.Season, candidate.Episode)) continue;
            if (current.Season > 0 && candidate.Season == 0) continue;
            if (previous is not { } best || After(candidate.Season, candidate.Episode, best.Season, best.Episode))
            {
                previous = (candidate.Season, candidate.Episode);
            }
        }
        return previous is { } slot ? ChooseSource(Slot(shows, slot), current.FolderId, isUnavailable, folder) : null;
    }

    /// <summary>
    /// The Up Next card's episode across every copy: the gates of
    /// <see cref="UpcomingEpisode(TimeSpan, TimeSpan?, bool, LibraryEpisode?, LibraryShow?)"/>,
    /// then the source rule of <see cref="NextEpisode(LibraryEpisode, IReadOnlyList{LibraryShow}, Func{Guid, bool}, Func{Guid, LibraryFolder?})"/>,
    /// so the card previews the episode auto-advance will play.
    /// </summary>
    public static ShowEpisode? UpcomingEpisode(
        TimeSpan time,
        TimeSpan? duration,
        bool loopEnabled,
        LibraryEpisode? episode,
        IReadOnlyList<LibraryShow> copies,
        Func<Guid, bool> isUnavailable,
        Func<Guid, LibraryFolder?> folder)
    {
        if (duration is not { } length || length <= TimeSpan.Zero) return null;
        if (loopEnabled || episode is null) return null;
        var remaining = length - time;
        if (remaining > UpcomingPreviewThreshold || remaining <= TimeSpan.Zero) return null;
        return NextEpisode(episode, copies, isUnavailable, folder);
    }

    /// <summary>
    /// The player's Episodes panel: one entry per (season, episode) across
    /// every copy, in airing order, so one share linked through two endpoints
    /// lists each episode once. The current slot is the playing file; every
    /// other slot comes from the playing source when it has the episode, else
    /// from another by the next-episode rule. Apple lists only the playing
    /// show copy; Windows also lists what other sources fill in, which
    /// auto-advance reaches anyway. Empty unless a copy holds <paramref name="current"/>.
    /// </summary>
    public static IReadOnlyList<ShowEpisode> PlaylistEpisodes(
        LibraryEpisode current,
        IReadOnlyList<LibraryShow> copies,
        Func<Guid, bool> isUnavailable,
        Func<Guid, LibraryFolder?> folder)
    {
        if (Holding(current, copies) is not { } shows) return [];

        var entries = shows.SelectMany(show => show.Episodes.Select(episode => new ShowEpisode(show, episode))).ToList();
        var playing = entries.First(entry => entry.Episode.Id == current.Id);
        return
        [
            .. entries
                .GroupBy(entry => (entry.Episode.Season, entry.Episode.Episode))
                .OrderBy(group => group.Key.Season)
                .ThenBy(group => group.Key.Episode)
                .Select(group => group.Key == (current.Season, current.Episode)
                    ? playing
                    : ChooseSource([.. group], current.FolderId, isUnavailable, folder)),
        ];
    }

    /// <summary>The copies once each, or null when none holds <paramref name="current"/>.</summary>
    private static IReadOnlyList<LibraryShow>? Holding(LibraryEpisode current, IReadOnlyList<LibraryShow> copies)
    {
        var shows = copies.DistinctBy(show => show.Id).ToList();
        return shows.Any(show => show.Episodes.Any(episode => episode.Id == current.Id)) ? shows : null;
    }

    /// <summary>Every file of one (season, episode), with its record, in record and file order.</summary>
    private static List<ShowEpisode> Slot(IEnumerable<LibraryShow> shows, (int Season, int Episode) slot) =>
    [
        .. shows.SelectMany(show => show.Episodes
            .Where(episode => episode.Season == slot.Season && episode.Episode == slot.Episode)
            .Select(episode => new ShowEpisode(show, episode))),
    ];

    /// <summary>
    /// The file of one episode to play: the playing source's (the first by
    /// path when it holds two) whether or not it is flagged unavailable, else
    /// the first other source that isn't, else the first. Other sources go
    /// local first, then by name and path, as Play From lists them.
    /// </summary>
    private static ShowEpisode ChooseSource(
        IReadOnlyList<ShowEpisode> candidates,
        Guid playingFolderId,
        Func<Guid, bool> isUnavailable,
        Func<Guid, LibraryFolder?> folder)
    {
        var ordered = candidates
            .Select(candidate => (Candidate: candidate, Folder: folder(candidate.Episode.FolderId)))
            .OrderByDescending(pair => pair.Candidate.Episode.FolderId == playingFolderId)
            .ThenByDescending(pair => pair.Folder is { IsRemote: false })
            .ThenBy(pair => pair.Folder?.Name ?? "", NaturalStringComparer.Instance)
            .ThenBy(pair => pair.Folder?.Path ?? "", NaturalStringComparer.Instance)
            .ThenBy(pair => pair.Candidate.Episode.FilePath, StringComparer.Ordinal)
            .Select(pair => pair.Candidate)
            .ToList();
        if (ordered[0].Episode.FolderId == playingFolderId) return ordered[0];
        return ordered.FirstOrDefault(candidate => !isUnavailable(candidate.Episode.FolderId)) ?? ordered[0];
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
    /// never writes progress. The episode is the earliest after the furthest
    /// completed on any record of the show (ResumeItem.items at apple-27.1
    /// 94a8b4f); <paramref name="sourcePreference"/>, given a show's records,
    /// orders their sources (PlaybackSources.SourcePreference) and picks the
    /// file from the first that holds it. Without one, the first file met wins.
    /// </summary>
    public static List<NextUpEpisode> NextUpEpisodes(
        IEnumerable<WatchProgress> allProgress,
        IReadOnlySet<int> inProgressShowTmdbIds,
        IEnumerable<LibraryShow> shows,
        Func<IReadOnlyList<LibraryShow>, IReadOnlyList<Guid>>? sourcePreference = null)
    {
        var highest = HighestCompletedPerShow(allProgress);

        // Every record of each show, in the given order.
        var records = new Dictionary<int, List<LibraryShow>>();
        var showIds = new List<int>();
        foreach (var show in shows)
        {
            if (show.TmdbId is not int showId || inProgressShowTmdbIds.Contains(showId)) continue;
            if (!highest.ContainsKey(showId)) continue;
            if (!records.TryGetValue(showId, out var copies))
            {
                records[showId] = copies = [];
                showIds.Add(showId);
            }
            copies.Add(show);
        }

        var suggestions = new List<NextUpEpisode>();
        foreach (var showId in showIds)
        {
            var copies = records[showId];
            var completed = highest[showId];
            (int Season, int Episode)? next = null;
            foreach (var episode in copies.SelectMany(show => show.Episodes))
            {
                if (!After(episode.Season, episode.Episode, completed.Season, completed.Episode)) continue;
                if (next is not { } best || After(best.Season, best.Episode, episode.Season, episode.Episode))
                {
                    next = (episode.Season, episode.Episode);
                }
            }
            if (next is not { } slot) continue;

            var candidates = Slot(copies, slot);
            var chosen = sourcePreference?.Invoke(copies) is { } preference
                ? candidates.OrderBy(candidate => PlaybackSources.Rank(preference, candidate.Episode.FolderId)).First()
                : candidates[0];
            suggestions.Add(new NextUpEpisode(chosen.Show, chosen.Episode, completed.LastWatchedEpochMillis));
        }

        return [.. suggestions.OrderByDescending(entry => entry.LastWatchedEpochMillis)];
    }
}
