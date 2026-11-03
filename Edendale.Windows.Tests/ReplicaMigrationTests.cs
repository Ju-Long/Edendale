using System.Text.Json;
using Edendale.Windows.Core.Sync;
using Edendale.Windows.Services;
using Edendale.Windows.Services.Sync;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// SYNC-PROTOCOL.md §8.4 and FEATURES-27.1.md §5.5: the OneDrive replica of
/// builds before 27.1 is read once, stamped with its existing timestamps and
/// this device's ID, merged, and left in place.
/// </summary>
[TestClass]
public sealed class ReplicaMigrationTests
{
    private string _root = "";

    [TestInitialize]
    public void Setup()
    {
        _root = Path.Combine(Path.GetTempPath(), "edendale-migration-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    [TestCleanup]
    public void Cleanup() => Directory.Delete(_root, recursive: true);

    /// <summary>The pre-27.1 replica: the old field set, no updateTime or device.</summary>
    private string WriteReplica()
    {
        var replica = Path.Combine(_root, "OneDrive", "Apps", "Edendale");
        Directory.CreateDirectory(replica);
        File.WriteAllText(Path.Combine(replica, "user-media.json"), """
            [
              {"tmdbId":603,"mediaType":"movie","title":"The Matrix","favourite":true,"favouriteUpdatedAt":1700000000000,"favouriteDirty":false,"rating":9.0,"ratingUpdatedAt":1700000500000,"ratingDirty":false},
              {"tmdbId":1399,"mediaType":"tv","favourite":false,"favouriteUpdatedAt":1600000000000,"favouriteDirty":false,"rating":7.0,"ratingUpdatedAt":1600000000000,"ratingDirty":false}
            ]
            """);
        File.WriteAllText(Path.Combine(replica, "watch-progress.json"), """
            [
              {"tmdbId":603,"mediaType":"movie","position":0.8,"normalizedPosition":0.8,"watchedSeconds":6528,"lastWatchedEpochMillis":1700001000000,"isCompleted":false},
              {"tmdbId":62085,"mediaType":"episode","position":1,"normalizedPosition":1,"watchedSeconds":3000,"lastWatchedEpochMillis":1700002000000,"isCompleted":true,"showTmdbId":1399,"seasonNumber":1,"episodeNumber":2}
            ]
            """);
        return replica;
    }

    [TestMethod]
    public void ImportsOnce_StampsWithExistingTimes_AndLeavesTheFilesInPlace()
    {
        var replica = WriteReplica();
        var clock = new SyncClock(() => 1762000000000);
        var state = new SyncState(Path.Combine(_root, "sync-state.json"));
        var progress = new WatchProgressStore(Path.Combine(_root, "watch-progress.json"), clock, () => state.DeviceId);
        var media = new UserMediaStore(Path.Combine(_root, "user-media.json"), clock, () => state.DeviceId);

        // A newer local rating for the show wins over the replica's older one.
        media.SetRating(1399, "tv", 8.5);
        var localStamp = media.Get(1399, "tv")!.UpdateTime;

        using var engine = new SyncEngine(state, clock, progress, media, null, _ => null) { ReplicaDirectory = replica };
        engine.Start();

        Assert.IsTrue(state.ReplicaImported);
        Assert.IsNotNull(engine.LastImport);
        Assert.AreEqual(2, engine.LastImport!.UserMediaRead);
        Assert.AreEqual(2, engine.LastImport.ProgressRead);

        var matrix = media.Get(603, "movie")!;
        Assert.IsTrue(matrix.Favourite);
        Assert.AreEqual(9.0, matrix.Rating);
        Assert.AreEqual(1700000500000, matrix.UpdateTime, "the newest existing field time is the stamp");
        Assert.AreEqual(state.DeviceId, matrix.Device, "stamped with this device's ID");
        Assert.AreEqual(8.5, media.Get(1399, "tv")!.Rating, "the newer local write wins");
        Assert.AreEqual(localStamp, media.Get(1399, "tv")!.UpdateTime);

        var movie = progress.Get(603, "movie")!;
        Assert.AreEqual(0.8, movie.Position, 1e-9);
        Assert.AreEqual(1700001000000, movie.LastWatchedEpochMillis);
        Assert.AreEqual(state.DeviceId, movie.Device);
        Assert.IsTrue(progress.IsEpisodeWatched(1399, 1, 2));
        Assert.IsTrue(progress.IsWatched(62085, "episode"));

        Assert.IsTrue(File.Exists(Path.Combine(replica, "user-media.json")), "the old files are left in place");
        Assert.IsTrue(File.Exists(Path.Combine(replica, "watch-progress.json")));
        var untouched = JsonDocument.Parse(File.ReadAllText(Path.Combine(replica, "user-media.json")));
        Assert.AreEqual(2, untouched.RootElement.GetArrayLength());

        // A second launch does not import again, even after the local record changed.
        media.SetRating(603, "movie", null);
        using var second = new SyncEngine(state, clock, progress, media, null, _ => null) { ReplicaDirectory = replica };
        second.Start();
        Assert.IsNull(second.LastImport);
        Assert.IsNull(media.RatingFor(603, "movie"), "the import did not bring the rating back");
    }

    [TestMethod]
    public void NoReplica_MarksTheImportDone_WithoutTouchingTheStores()
    {
        var clock = new SyncClock(() => 1762000000000);
        var state = new SyncState(Path.Combine(_root, "sync-state.json"));
        var progress = new WatchProgressStore(Path.Combine(_root, "watch-progress.json"), clock, () => state.DeviceId);
        var media = new UserMediaStore(Path.Combine(_root, "user-media.json"), clock, () => state.DeviceId);
        using var engine = new SyncEngine(state, clock, progress, media, null, _ => null) { ReplicaDirectory = Path.Combine(_root, "missing") };
        engine.Start();
        Assert.IsTrue(state.ReplicaImported);
        Assert.IsNull(engine.LastImport);
        Assert.AreEqual(0, progress.All.Count);
    }

    [TestMethod]
    public void TheMergeKeepsTombstones_AndBreaksTiesOnDevice()
    {
        var older = new Models.UserMediaRecord { TmdbId = 1, MediaType = "movie", Favourite = true, UpdateTime = 100, Device = SyncMergeTests.B };
        var tombstone = new Models.UserMediaRecord { TmdbId = 1, MediaType = "movie", UpdateTime = 200, Device = SyncMergeTests.A };
        var merged = Core.WindowsCore.MergeUserMedia([older], [tombstone]);
        Assert.AreEqual(1, merged.Count);
        Assert.IsTrue(merged[0].IsTombstone);

        var tieA = new Models.UserMediaRecord { TmdbId = 2, MediaType = "movie", Rating = 5, UpdateTime = 300, Device = SyncMergeTests.A };
        var tieB = new Models.UserMediaRecord { TmdbId = 2, MediaType = "movie", Rating = 6, UpdateTime = 300, Device = SyncMergeTests.B };
        Assert.AreEqual(5, Core.WindowsCore.MergeUserMedia([tieB], [tieA]).Single().Rating);
        Assert.AreEqual(5, Core.WindowsCore.MergeUserMedia([tieA], [tieB]).Single().Rating);
    }
}
