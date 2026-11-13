using Edendale.Windows.Core;
using Edendale.Windows.Core.Sync;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Clear Progress and hand-made Mark Watched (apple-27.1 94a8b4f,
/// MultipleSourcesTests.clearingProgressRemovesItFromContinueWatching and
/// markingAnEpisodeWatchedKeepsItsShowForNextUpAndSync): clearing writes a
/// stamped tombstone that takes the title out of Continue Watching and
/// travels as a removal; a mark records the episode's show and numbers, and
/// a row's own context is kept.
/// </summary>
[TestClass]
public sealed class ClearProgressTests
{
    private const long Now = 1_763_000_000_000;
    private string _directory = "";
    private long _clock = Now;

    [TestInitialize]
    public void Setup()
    {
        _directory = Path.Combine(Path.GetTempPath(), "edendale-clear-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_directory);
    }

    [TestCleanup]
    public void Cleanup()
    {
        try { Directory.Delete(_directory, recursive: true); } catch { /* best effort */ }
    }

    private WatchProgressStore NewStore() =>
        new(Path.Combine(_directory, "watch-progress.json"), new SyncClock(() => _clock), () => "0d0d0d0d-0d0d-4d0d-8d0d-0d0d0d0d0d0d");

    private static LibraryShow Show(int tmdbId, params (int Season, int Episode, int Id)[] episodes) => new()
    {
        Name = "Show",
        TmdbId = tmdbId,
        Episodes = [.. episodes.Select(e => new LibraryEpisode { Season = e.Season, Episode = e.Episode, TmdbId = e.Id, FilePath = $@"C:\S{e.Season}E{e.Episode}.mkv" })],
    };

    [TestMethod]
    public void ClearingProgressRemovesAMovieFromContinueWatching()
    {
        var store = NewStore();
        store.Update(603, "movie", 0.5, 100, durationSeconds: 200);
        Assert.IsTrue(store.InProgress.Any(record => record.TmdbId == 603));
        var live = store.Get(603, "movie")!.LastWatchedEpochMillis;
        var edits = 0;
        store.EditedLocally += (_, _) => edits++;

        _clock += 1000;
        store.Remove(603, "movie");
        Assert.IsNull(store.Get(603, "movie"));
        Assert.AreEqual(0, store.InProgress.Count);
        Assert.AreEqual(0, store.All.Count);
        var tombstone = store.AllIncludingCleared.Single();
        Assert.IsTrue(tombstone.IsCleared);
        Assert.IsTrue(tombstone.LastWatchedEpochMillis > live, "stamped after the position it clears");
        Assert.AreEqual(1, edits, "a local edit: the device file must say so");
        Assert.IsTrue(NewStore().AllIncludingCleared.Single().IsCleared, "the tombstone is kept");
    }

    [TestMethod]
    public void ClearingAnEpisodeUnderWayAfterAFinishedOneOffersItAsUpNext()
    {
        var store = NewStore();
        var show = Show(1399, (1, 1, 101), (1, 2, 102));
        store.MarkCompleted(101, "episode", 1399, 1, 1);
        store.Update(102, "episode", 0.4, 600, showTmdbId: 1399, seasonNumber: 1, episodeNumber: 2, durationSeconds: 1500);
        Assert.AreEqual(0, EpisodeProgression.NextUpEpisodes(store.All, new HashSet<int> { 1399 }, [show]).Count, "S1E2 is under way");

        store.Remove(102, "episode", 1399, 1, 2);
        var inProgress = store.InProgress.Where(record => record.ShowTmdbId is not null).Select(record => record.ShowTmdbId!.Value).ToHashSet();
        Assert.AreEqual(102, EpisodeProgression.NextUpEpisodes(store.All, inProgress, [show]).Single().Episode.TmdbId);
        var tombstone = store.AllIncludingCleared.Single(record => record.IsCleared);
        Assert.AreEqual("tv:1399:1:2", tombstone.SyncKey);
        Assert.IsTrue(SyncRecordMapping.ToSync(tombstone, "dev")!.Cleared, "it travels as a removal");
    }

    [TestMethod]
    public void MarkingAnEpisodeWatchedKeepsItsShowForNextUpAndSync()
    {
        var store = NewStore();
        var show = Show(95396, (1, 1, 7001), (1, 2, 7002));
        store.MarkCompleted(7001, "episode", 95396, 1, 1);
        var record = store.Get(7001, "episode")!;
        Assert.AreEqual("tv:95396:1:1", record.SyncKey);
        Assert.IsTrue(record.IsSyncable);
        var sync = SyncRecordMapping.ToSync(record, "dev")!;
        Assert.AreEqual("tv", sync.Type);
        Assert.AreEqual(95396, sync.TmdbId);
        Assert.AreEqual(1, sync.Season);
        Assert.AreEqual(1, sync.Episode);
        Assert.IsTrue(sync.Completed);
        Assert.AreEqual(7002, EpisodeProgression.NextUpEpisodes(store.All, new HashSet<int>(), [show]).Single().Episode.TmdbId);
    }

    [TestMethod]
    public void MarkWatchedKeepsTheContextARowAlreadyHas()
    {
        var store = NewStore();
        store.Update(62085, "episode", 0.3, 300, showTmdbId: 1399, seasonNumber: 1, episodeNumber: 2, durationSeconds: 1000);
        store.MarkCompleted(62085, "episode", 1399, 1, 3);
        Assert.IsTrue(store.GetEpisode(1399, 1, 2)!.IsCompleted);
        Assert.IsNull(store.GetEpisode(1399, 1, 3), "never re-keyed, so no sync key is left behind without a tombstone");
        Assert.AreEqual(1, store.AllIncludingCleared.Count);
    }

    [TestMethod]
    public void ALocalOnlyRowGainsContext()
    {
        var store = NewStore();
        store.MarkCompleted(99002, "episode");
        store.MarkCompleted(99002, "episode", 1399, 2, 1);
        Assert.AreEqual("tv:1399:2:1", store.AllIncludingCleared.Single().SyncKey);
    }

    [TestMethod]
    public void RemoveKeepsTheRowsKey()
    {
        var store = NewStore();
        store.Update(62085, "episode", 0.3, 300, showTmdbId: 1399, seasonNumber: 1, episodeNumber: 2, durationSeconds: 1000);
        store.Remove(62085, "episode", 1399, 1, 3);
        var tombstone = store.AllIncludingCleared.Single();
        Assert.IsTrue(tombstone.IsCleared);
        Assert.AreEqual("tv:1399:1:2", tombstone.SyncKey);
    }
}
