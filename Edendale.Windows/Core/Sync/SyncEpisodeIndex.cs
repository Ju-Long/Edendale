// Episode TMDB ids by sync key (`tv:<show>:<season>:<episode>`), so an
// episode another device wrote is matched to this PC's own id without a scan
// of the library per record. Ports SyncCoordinator.EpisodeIndex and
// libraryEpisodeIndex() on origin/apple-27.1 (K.7): the store first (the
// engine's own map of the records it holds), then the library, built once
// per sync and only when a record needs it.
//
// Apple then asks TMDB for season lists, a few per sync, because its store
// keys an episode by the episode's own id and can't hold one without it.
// Windows doesn't port that: WatchProgressStore keeps an unmatched episode
// under its sync key with TmdbId 0, still writes it out, and the UI finds it
// by show, season, and episode (DetailPage's TMDB episode cards, Home's
// Continue Watching by show). Its id is filled in from the library when the
// library learns it, so nothing waits on TMDB.

using Edendale.Windows.Services;

namespace Edendale.Windows.Core.Sync;

public sealed class SyncEpisodeIndex
{
    private static readonly IReadOnlyDictionary<string, int> Empty = new Dictionary<string, int>();

    private readonly Func<IReadOnlyDictionary<string, int>>? _loadLibrary;
    private IReadOnlyDictionary<string, int>? _library;

    /// <param name="loadLibrary">The library's index; called at most once, on the first lookup.</param>
    public SyncEpisodeIndex(Func<IReadOnlyDictionary<string, int>>? loadLibrary) => _loadLibrary = loadLibrary;

    /// <summary>The library's id for the episode with <paramref name="syncKey"/>, or null.</summary>
    public int? Id(string syncKey)
    {
        _library ??= _loadLibrary?.Invoke() ?? Empty;
        return _library.TryGetValue(syncKey, out var id) && id > 0 ? id : null;
    }

    /// <summary>
    /// Every identified episode of every identified show, by sync key, in
    /// one pass. Copies of one episode share its id; the first one listed
    /// is kept.
    /// </summary>
    public static Dictionary<string, int> FromLibrary(IEnumerable<LibraryShow> shows)
    {
        var index = new Dictionary<string, int>(StringComparer.Ordinal);
        foreach (var show in shows)
        {
            if (show.TmdbId is not int showId || showId <= 0) continue;
            foreach (var episode in show.Episodes)
            {
                if (episode.TmdbId is not int id || id <= 0) continue;
                index.TryAdd(SyncProgressRecord.EpisodeKey(showId, episode.Season, episode.Episode), id);
            }
        }
        return index;
    }
}
