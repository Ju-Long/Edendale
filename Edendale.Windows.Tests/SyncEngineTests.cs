using Edendale.Windows.Core.Sync;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Edendale.Windows.Services.Sync;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// SYNC-PROTOCOL.md §8 end to end over a folder location: two devices in one
/// space converge, a read repeated changes nothing, tombstones travel,
/// episodes map between Windows' keys and the format's, the watchlist flag
/// follows the watchlist store, and a failing location backs off and leaves
/// the local store alone.
/// </summary>
[TestClass]
public sealed class SyncEngineTests
{
    private string _root = "";

    [TestInitialize]
    public void Setup()
    {
        _root = Path.Combine(Path.GetTempPath(), "edendale-sync-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    [TestCleanup]
    public void Cleanup() => Directory.Delete(_root, recursive: true);

    private sealed class FakeRemote : IWatchlistRemote
    {
        public Task<IReadOnlyList<MediaItem>> WatchlistItemsAsync(string mediaType) => Task.FromResult<IReadOnlyList<MediaItem>>([]);
        public Task SetWatchlistAsync(MediaRef reference, bool inWatchlist) => Task.CompletedTask;
    }

    /// <summary>One simulated device: its own stores, state, and engine, sharing the folder location.</summary>
    internal sealed class Device : IDisposable
    {
        public Device(string root, string name, long now, string location, Func<int, int, int, int?>? resolver = null, bool withWatchlist = true, Func<SyncLocationSettings, ISyncLocation?>? locations = null)
        {
            Directory.CreateDirectory(Path.Combine(root, name));
            // A picked folder exists before it is chosen; the location never creates its own root.
            if (locations is null) Directory.CreateDirectory(location);
            Now = now;
            Clock = new SyncClock(() => Now);
            State = new SyncState(Path.Combine(root, name, "sync-state.json"));
            Progress = new WatchProgressStore(Path.Combine(root, name, "watch-progress.json"), Clock, () => State.DeviceId);
            Media = new UserMediaStore(Path.Combine(root, name, "user-media.json"), Clock, () => State.DeviceId);
            Watchlist = withWatchlist ? new WatchlistStore(new FakeRemote(), () => false, Path.Combine(root, name, "watchlist.json"), Path.Combine(root, name, "none.json")) : null;
            Engine = new SyncEngine(State, Clock, Progress, Media, Watchlist, locations ?? (settings => new FolderSyncLocation(settings.Path!)), resolver, "27.1-test")
            {
                ReplicaDirectory = null,
            };
            State.DeviceName = name;
            State.SetLocation(new SyncLocationSettings { Kind = SyncLocationKind.Folder, Path = location, Space = "Family" });
        }

        public long Now { get; set; }
        public SyncClock Clock { get; }
        public SyncState State { get; }
        public WatchProgressStore Progress { get; }
        public UserMediaStore Media { get; }
        public WatchlistStore? Watchlist { get; }
        public SyncEngine Engine { get; }

        public async Task SyncAsync()
        {
            await Engine.SyncNowAsync();
        }

        public void Dispose() => Engine.Dispose();
    }

    private const long T0 = 1762000000000;

    [TestMethod]
    public async Task TwoDevicesConverge_AndASecondReadChangesNothing()
    {
        var location = Path.Combine(_root, "location");
        Directory.CreateDirectory(location);
        using var a = new Device(_root, "A", T0, location);
        using var b = new Device(_root, "B", T0 + 5_000, location);

        a.Progress.Update(603, "movie", 0.25, 1000, durationSeconds: 4000);
        a.Media.SetFavourite(1399, "tv", true, "Game of Thrones");
        await a.SyncAsync();

        var spaceFolder = Path.Combine(location, "Edendale", "Family");
        Assert.IsTrue(File.Exists(Path.Combine(spaceFolder, a.State.DeviceId + ".json")), "A wrote its own file");
        Assert.IsFalse(File.Exists(Path.Combine(spaceFolder, a.State.DeviceId + ".json.tmp")), "the temporary file was renamed");

        b.Progress.Update(11, "movie", 0.5, 3000, durationSeconds: 6000);
        await b.SyncAsync();
        Assert.AreEqual(0.25, b.Progress.Get(603, "movie")!.Position, 1e-9, "B received A's movie");
        Assert.AreEqual(1000, b.Progress.Get(603, "movie")!.WatchedSeconds, 1e-9);
        Assert.AreEqual(4000, b.Progress.Get(603, "movie")!.DurationSeconds, 1e-9);
        Assert.AreEqual(a.State.DeviceId, b.Progress.Get(603, "movie")!.Device, "received records keep their device");
        Assert.IsTrue(b.Media.IsFavourite(1399, "tv"));

        await a.SyncAsync();
        Assert.AreEqual(0.5, a.Progress.Get(11, "movie")!.Position, 1e-9, "A received B's movie");

        var before = File.ReadAllBytes(Path.Combine(spaceFolder, a.State.DeviceId + ".json"));
        var lastWrite = a.State.LastWriteMillis;
        await a.SyncAsync();
        CollectionAssert.AreEqual(before, File.ReadAllBytes(Path.Combine(spaceFolder, a.State.DeviceId + ".json")), "nothing changed, nothing rewritten differently");
        Assert.AreEqual(lastWrite, a.State.LastWriteMillis, "no write when nothing changed");

        // Both files decode to the same merged set.
        var fileA = SyncDeviceFileCodec.Decode(File.ReadAllBytes(Path.Combine(spaceFolder, a.State.DeviceId + ".json")), a.State.DeviceId + ".json", "Family").File!;
        var fileB = SyncDeviceFileCodec.Decode(File.ReadAllBytes(Path.Combine(spaceFolder, b.State.DeviceId + ".json")), b.State.DeviceId + ".json", "Family").File!;
        CollectionAssert.AreEquivalent(fileA.WatchProgress.ToList(), fileB.WatchProgress.ToList());
        CollectionAssert.AreEquivalent(fileA.UserMedia.ToList(), fileB.UserMedia.ToList());
        Assert.AreEqual("windows", fileA.Device.Platform);
        Assert.AreEqual("A", fileA.Device.Name);
    }

    [TestMethod]
    public async Task ClearingTravelsAsATombstone_AndNewerWins()
    {
        var location = Path.Combine(_root, "location");
        using var a = new Device(_root, "A", T0, location);
        using var b = new Device(_root, "B", T0 + 1000, location);

        a.Media.SetRating(603, "movie", 8.0);
        await a.SyncAsync();
        await b.SyncAsync();
        Assert.AreEqual(8.0, b.Media.RatingFor(603, "movie"));

        b.Now += 10_000;
        b.Media.SetRating(603, "movie", null);
        Assert.IsNotNull(b.Media.Get(603, "movie"), "the cleared record stays as a tombstone");
        Assert.IsTrue(b.Media.Get(603, "movie")!.IsTombstone);
        await b.SyncAsync();

        a.Now += 20_000;
        await a.SyncAsync();
        Assert.IsNull(a.Media.RatingFor(603, "movie"), "A applied B's tombstone");
        Assert.IsTrue(a.Media.Get(603, "movie")!.IsTombstone);

        // A clears progress: a progress tombstone goes out and B drops the position.
        a.Progress.Update(603, "movie", 0.5, 100, durationSeconds: 200);
        await a.SyncAsync();
        await b.SyncAsync();
        Assert.IsNotNull(b.Progress.Get(603, "movie"));
        a.Now += 1000;
        a.Progress.Remove(603, "movie");
        Assert.IsNull(a.Progress.Get(603, "movie"));
        await a.SyncAsync();
        b.Now += 5000;
        await b.SyncAsync();
        Assert.IsNull(b.Progress.Get(603, "movie"), "B applied the progress tombstone");
        Assert.IsTrue(b.Progress.AllIncludingCleared.Single(record => record.TmdbId == 603).IsCleared);
    }

    [TestMethod]
    public async Task EpisodesMapByShowSeasonEpisode_AndResolveToLocalIds()
    {
        var location = Path.Combine(_root, "location");
        using var a = new Device(_root, "A", T0, location);
        // B knows episode 62085 is show 1399 S1E2 from its library.
        using var b = new Device(_root, "B", T0 + 1000, location, resolver: (show, season, episode) => show == 1399 && season == 1 && episode == 2 ? 62085 : null);

        a.Progress.Update(99001, "episode", 0.4, 1200, showTmdbId: 1399, seasonNumber: 1, episodeNumber: 2, durationSeconds: 3000);
        a.Progress.MarkCompleted(99002, "episode"); // no show info: local only, never written
        await a.SyncAsync();

        var fileA = SyncDeviceFileCodec.Decode(File.ReadAllBytes(Path.Combine(location, "Edendale", "Family", a.State.DeviceId + ".json")), a.State.DeviceId + ".json", "Family").File!;
        Assert.AreEqual(1, fileA.WatchProgress.Count, "the episode without show info stays local");
        Assert.AreEqual("tv:1399:1:2", fileA.WatchProgress[0].Key);
        Assert.AreEqual(1200, fileA.WatchProgress[0].Position);
        Assert.AreEqual(3000, fileA.WatchProgress[0].Duration);

        await b.SyncAsync();
        var episode = b.Progress.GetEpisode(1399, 1, 2);
        Assert.IsNotNull(episode);
        Assert.AreEqual(62085, episode!.TmdbId, "resolved through the library");
        Assert.AreEqual(0.4, episode.Position, 1e-9);
        Assert.IsNotNull(b.Progress.Get(62085, "episode"), "and reachable by the episode id");
        Assert.IsTrue(b.Progress.IsEpisodeWatched(1399, 1, 2) == false);

        // A device without a library keeps the record with TmdbId 0 and still answers by show/season/episode.
        using var c = new Device(_root, "C", T0 + 2000, location);
        await c.SyncAsync();
        Assert.AreEqual(0, c.Progress.GetEpisode(1399, 1, 2)!.TmdbId);
        Assert.AreEqual(1, c.Progress.ResolveEpisodeIds((show, season, ep) => 62085));
        Assert.AreEqual(62085, c.Progress.Get(62085, "episode")!.TmdbId);
    }

    [TestMethod]
    public async Task WatchlistFlagFollowsTheWatchlistStore_BothWays()
    {
        var location = Path.Combine(_root, "location");
        using var a = new Device(_root, "A", T0, location);
        using var b = new Device(_root, "B", T0 + 1000, location);
        a.Engine.Start();
        b.Engine.Start();
        await a.Engine.Current!;
        await b.Engine.Current!;

        a.Watchlist!.SetWatchlist(true, new MediaRef { Id = 603, MediaType = "movie" }, new WatchlistMetadata { Title = "The Matrix" });
        Assert.IsTrue(a.Media.Get(603, "movie")!.Watchlist, "the mirror follows a local add");
        await a.SyncAsync();
        await b.SyncAsync();
        Assert.IsTrue(b.Watchlist!.IsInWatchlist(new MediaRef { Id = 603, MediaType = "movie" }), "B's watchlist store follows the merged flag");

        b.Now += 10_000;
        b.Watchlist.SetWatchlist(false, new MediaRef { Id = 603, MediaType = "movie" });
        await b.SyncAsync();
        a.Now += 20_000;
        await a.SyncAsync();
        Assert.IsFalse(a.Watchlist.IsInWatchlist(new MediaRef { Id = 603, MediaType = "movie" }));
        Assert.IsFalse(a.Media.Get(603, "movie")!.Watchlist);
    }

    private sealed class FailingLocation : ISyncLocation
    {
        public int Lists;
        public string Description => "failing";
        public Task<IReadOnlyList<SyncFileEntry>> ListAsync(string space, CancellationToken cancellation)
        {
            Lists++;
            throw new IOException("offline");
        }
        public Task<byte[]> ReadAsync(string space, string name, CancellationToken cancellation) => throw new IOException("offline");
        public Task WriteAsync(string space, string name, ReadOnlyMemory<byte> bytes, CancellationToken cancellation) => throw new IOException("offline");
        public Task DeleteAsync(string space, string name, CancellationToken cancellation) => Task.CompletedTask;
    }

    [TestMethod]
    public async Task AFailingLocation_LeavesTheStoreAlone_AndBacksOff()
    {
        var failing = new FailingLocation();
        using var a = new Device(_root, "A", T0, Path.Combine(_root, "unused"), locations: _ => failing);
        a.Progress.Update(603, "movie", 0.3, 300, durationSeconds: 1000);
        a.Engine.Start();
        await a.Engine.Current!;
        Assert.AreEqual(1, failing.Lists, "the launch sync listed once");
        Assert.AreEqual(0.3, a.Progress.Get(603, "movie")!.Position, 1e-9, "the store is untouched");
        Assert.IsNotNull(a.Engine.Status.LastError);

        // Within the first minute nothing retries on an automatic trigger; a user request does.
        a.Now += 30_000;
        a.Engine.OnForeground();
        if (a.Engine.Current is { } current) await current;
        Assert.AreEqual(1, failing.Lists, "backoff holds automatic triggers for a minute");
        await a.SyncAsync();
        Assert.AreEqual(2, failing.Lists, "a user request ignores the backoff");

        a.Now += 90_000;
        a.Engine.OnForeground();
        if (a.Engine.Current is { } second) await second;
        Assert.AreEqual(2, failing.Lists, "the second failure doubled the wait to two minutes");
        a.Now += 60_000;
        a.Engine.OnForeground();
        if (a.Engine.Current is { } third) await third;
        Assert.AreEqual(3, failing.Lists, "after two minutes the next automatic trigger runs");
    }

    [TestMethod]
    public async Task SkippedFilesAreNamedInAWarning_AndNotReReadUntilTheyChange()
    {
        var location = Path.Combine(_root, "location");
        using var a = new Device(_root, "A", T0, location);
        var folder = Path.Combine(location, "Edendale", "Family");
        Directory.CreateDirectory(folder);
        var other = SyncFormat.NewDeviceId();
        File.WriteAllText(Path.Combine(folder, other + ".json"),
            $$"""{"format":2,"space":"Family","device":{"id":"{{other}}","name":"Future Phone","platform":"ios","app":"28.0"},"updateTime":0,"watchProgress":[],"userMedia":[]}""");
        await a.SyncAsync();
        Assert.IsTrue(a.Engine.Status.Warnings.Any(warning => warning.Contains("Future Phone")), string.Join(" | ", a.Engine.Status.Warnings));
        Assert.AreEqual(0, a.Progress.All.Count);
        var peer = a.State.Peers.Single();
        Assert.AreEqual(SyncSkipReason.NewerFormat, peer.Skipped);
        Assert.IsNotNull(a.State.ReadStamp(other + ".json"), "the stamp is recorded so the bad file is not re-read");
    }

    [TestMethod]
    public async Task DeviceFilesCarryNoTitlesLoginsOrPaths()
    {
        var location = Path.Combine(_root, "location");
        using var a = new Device(_root, "A", T0, location);
        a.Media.SetFavourite(603, "movie", true, "The Matrix", "/poster.jpg");
        await a.SyncAsync();
        var text = File.ReadAllText(Path.Combine(location, "Edendale", "Family", a.State.DeviceId + ".json"));
        Assert.IsFalse(text.Contains("Matrix"), "titles stay local");
        Assert.IsFalse(text.Contains("poster"), "posters stay local");
        Assert.IsFalse(text.Contains("Title", StringComparison.OrdinalIgnoreCase));
    }

    [TestMethod]
    public async Task RemovingADevice_DeletesItsFile_AndTurningOffKeepsTheLocalStore()
    {
        var location = Path.Combine(_root, "location");
        using var a = new Device(_root, "A", T0, location);
        using var b = new Device(_root, "B", T0 + 1000, location);
        a.Progress.Update(603, "movie", 0.2, 200, durationSeconds: 1000);
        await a.SyncAsync();
        await b.SyncAsync();
        Assert.AreEqual(1, b.State.Peers.Count);

        await b.Engine.RemoveDeviceAsync(a.State.DeviceId, CancellationToken.None);
        Assert.IsFalse(File.Exists(Path.Combine(location, "Edendale", "Family", a.State.DeviceId + ".json")));
        Assert.AreEqual(0, b.State.Peers.Count);
        Assert.IsNotNull(b.Progress.Get(603, "movie"), "the removed device's records are already here");

        await b.Engine.TurnOffAsync(deleteOwnFile: true, CancellationToken.None);
        Assert.IsFalse(b.State.IsOn);
        Assert.IsFalse(File.Exists(Path.Combine(location, "Edendale", "Family", b.State.DeviceId + ".json")));
        Assert.IsNotNull(b.Progress.Get(603, "movie"), "turning off keeps the local store");
    }

    [TestMethod]
    public void HighWaterSurvivesARestart_ThroughTheStateAndTheRecords()
    {
        var location = Path.Combine(_root, "location");
        string deviceId;
        long stamp;
        using (var a = new Device(_root, "A", T0, location))
        {
            a.Progress.Update(603, "movie", 0.2, 200, durationSeconds: 1000);
            stamp = a.Progress.Get(603, "movie")!.LastWatchedEpochMillis;
            deviceId = a.State.DeviceId;
            Assert.AreEqual(stamp, a.Clock.HighWater);
        }
        // Reopen with a clock that runs behind: the next stamp still moves forward.
        using var again = new Device(_root, "A", T0 - 100_000, location);
        Assert.AreEqual(deviceId, again.State.DeviceId, "the device ID is kept");
        Assert.IsTrue(again.Clock.HighWater >= stamp);
        again.Media.SetFavourite(1, "movie", true);
        Assert.IsTrue(again.Media.Get(1, "movie")!.UpdateTime > stamp);
    }
}
