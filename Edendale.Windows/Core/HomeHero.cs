using Edendale.Windows.Models;

namespace Edendale.Windows.Core;

/// <summary>
/// Home's hero pager rules: which title is Continue Watching, and how the
/// scenes change when watch progress moves (playback here, or a merge from
/// another device) without reloading the shelves.
/// </summary>
public static class HomeHero
{
    /// <summary>
    /// The newest unfinished record Home can open a page for: a movie, or an
    /// episode whose show is known.
    /// </summary>
    public static WatchProgress? ContinueProgress(IEnumerable<WatchProgress> progress) =>
        progress
            .Where(item => !item.IsCompleted && !item.IsCleared)
            .OrderByDescending(item => item.LastWatchedEpochMillis)
            .FirstOrDefault(item =>
                item.MediaType == "movie" || (item.MediaType == "episode" && item.ShowTmdbId is not null));

    /// <summary>The page a Continue Watching record opens: the movie, or the episode's show.</summary>
    public static MediaRef? ContinueRef(WatchProgress? progress) => progress switch
    {
        { MediaType: "movie" } => new MediaRef { Id = progress.TmdbId, MediaType = "movie" },
        { MediaType: "episode", ShowTmdbId: int showId } => new MediaRef { Id = showId, MediaType = "tv" },
        _ => null,
    };

    public static bool SameTitle(MediaRef first, MediaRef second) =>
        first.Id == second.Id && first.MediaType == second.MediaType;

    /// <summary>Whether two scene lists show the same titles in the same order.</summary>
    public static bool SameTitles(IReadOnlyList<HeroScene> first, IReadOnlyList<HeroScene> second) =>
        first.Count == second.Count
        && first.Zip(second).All(pair => SameTitle(pair.First.Detail.Ref, pair.Second.Detail.Ref));

    /// <summary>
    /// The scenes with <paramref name="continueScene"/> first in place of the
    /// previous one, or with none when nothing is left to continue. A spotlight
    /// for the same title steps aside, as it does when Home loads.
    /// </summary>
    public static List<HeroScene> WithContinueScene(IEnumerable<HeroScene> scenes, HeroScene? continueScene)
    {
        var spotlights = scenes.Where(scene => !scene.IsContinueWatching
            && (continueScene is null || !SameTitle(scene.Detail.Ref, continueScene.Detail.Ref)));
        return continueScene is null ? [.. spotlights] : [continueScene, .. spotlights];
    }

    /// <summary>
    /// Where the pager lands when its scenes change: on the title it showed,
    /// wherever that moved, or at the same place when that title is gone.
    /// </summary>
    public static int StableIndex(IReadOnlyList<HeroScene> before, int index, IReadOnlyList<HeroScene> after)
    {
        if (after.Count == 0) return 0;
        if (index >= 0 && index < before.Count)
        {
            var shown = before[index].Detail.Ref;
            for (var position = 0; position < after.Count; position++)
            {
                if (SameTitle(after[position].Detail.Ref, shown)) return position;
            }
        }
        return Math.Clamp(index, 0, after.Count - 1);
    }
}
