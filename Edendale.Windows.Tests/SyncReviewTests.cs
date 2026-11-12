using Edendale.Windows.Core.Sync;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Edendale.Windows.Services.Sync;
using Microsoft.VisualStudio.TestTools.UnitTesting;
using Device = Edendale.Windows.Tests.SyncEngineTests.Device;

namespace Edendale.Windows.Tests;

/// <summary>
/// The sync review fixes of apple-27.1 (ENHANCEMENT.md K.7) on Windows: a
/// sync asked for during a sync runs after it, devices whose files are gone
/// leave the list and a missing own file comes back, far-future stamps stay
/// local until their time, episodes resolve through the library's index,
/// and folder locations name a refused cloud download and follow a moved
/// folder. Ports SyncCoordinatorTests.swift where Apple has a case.
/// </summary>
[TestClass]
public sealed class SyncReviewTests
{
    private const long T0 = 1762000000000;
    private const long Day = 86_400_000;

    private string _root = "";

    [TestInitialize]
    public void Setup()
    {
        _root = Path.Combine(Path.GetTempPath(), "edendale-k7-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_root);
    }

    [TestCleanup]
    public void Cleanup()
    {
        try { Directory.Delete(_root, recursive: true); } catch { /* best effort */ }
    }

    private static string SpaceFolder(string location) => Path.Combine(location, "Edendale", "Family");

    private static string FileOf(string location, string deviceId) => Path.Combine(SpaceFolder(location), deviceId + ".json");

    private static SyncDeviceFile Read(string location, string deviceId) =>
        SyncDeviceFileCodec.Decode(File.ReadAllBytes(FileOf(location, deviceId)), deviceId + ".json", "Family").File!;

    private static async Task Eventually(Func<bool> condition, string what)
    {
        var deadline = DateTime.UtcNow.AddSeconds(10);
        while (!condition())
        {
            if (DateTime.UtcNow > deadline) Assert.Fail("timed out waiting: " + what);
            await Task.Delay(10);
        }
    }

    /// <summary>A folder location whose listings can be held (GatedSyncStorage) or fail once, and whose reads can be refused.</summary>
    private sealed class TestLocation(string root) : ISyncLocation
    {
        private readonly FolderSyncLocation _folder = new(root);
        private TaskCompletionSource _gate = Open();
        private int _listings;

        public int Listings => Volatile.Read(ref _listings);
        public Exception? FailNextListing { get; set; }
        public Exception? RefuseReads { get; set; }
        public string Description => "test";

        private static TaskCompletionSource Open()
        {
            var gate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
            gate.SetResult();
            return gate;
        }

        public void Hold() => _gate = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);

        public void Release() => _gate.TrySetResult();

        public async Task<IReadOnlyList<SyncFileEntry>> ListAsync(string space, CancellationToken cancellation)
        {
            Interlocked.Increment(ref _listings);
            await _gate.Task.WaitAsync(cancellation);
            if (FailNextListing is { } failure)
            {
                FailNextListing = null;
                throw failure;
            }
            return await _folder.ListAsync(space, cancellation);
        }

        public Task<byte[]> ReadAsync(string space, string name, CancellationToken cancellation) =>
            RefuseReads is { } refusal ? Task.FromException<byte[]>(refusal) : _folder.ReadAsync(space, name, cancellation);

        public Task WriteAsync(string space, string name, ReadOnlyMemory<byte> bytes, CancellationToken cancellation) =>
            _folder.WriteAsync(space, name, bytes, cancellation);

        public Task DeleteAsync(string space, string name, CancellationToken cancellation) =>
            _folder.DeleteAsync(space, name, cancellation);
    }

    private (Device Device, TestLocation Location, string Folder) DeviceWithTestLocation(string name, long now)
    {
        var folder = Path.Combine(_root, "location");
        Directory.CreateDirectory(folder);
        var location = new TestLocation(folder);
        return (new Device(_root, name, now, folder, locations: _ => location), location, folder);
    }

    // ------------------------------------------------------------------
    // A sync asked for during a sync (aSyncRequestedDuringASyncRunsAfterIt)
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task ASyncRequestedDuringASyncRunsOnceAfterIt()
    {
        var (a, location, folder) = DeviceWithTestLocation("A", T0);
        using var _ = a;
        await a.SyncAsync();
        Assert.AreEqual(1, location.Listings);

        location.Hold();
        var running = a.Engine.SyncNowAsync();
        await Eventually(() => location.Listings == 2, "the second listing");

        a.Now += 1000;
        a.Progress.Update(603, "movie", 0.5, 500, durationSeconds: 1000);
        var write = a.Engine.WriteNowAsync();
        var user = a.Engine.SyncNowAsync();
        Assert.IsFalse(write.IsCompleted, "the write waits for the sync running");
        Assert.IsFalse(user.IsCompleted, "so does Sync Now");
        var current = a.Engine.Current!;
        Assert.IsFalse(current.IsCompleted, "Current is the queued sync");

        location.Release();
        await Task.WhenAll(running, write, user, current).WaitAsync(TimeSpan.FromSeconds(10));
        Assert.AreEqual(3, location.Listings, "both requests ran as one sync after the first");
        Assert.AreEqual(500, Read(folder, a.State.DeviceId).WatchProgress.Single(record => record.Key == "movie:603").Position);
    }

    [TestMethod]
    public async Task DuringABackoff_OnlyAUsersRequestIsQueued()
    {
        var (a, location, _) = DeviceWithTestLocation("A", T0);
        using var device = a;

        // A failure starts a one-minute backoff.
        location.FailNextListing = new IOException("offline");
        await a.SyncAsync();
        Assert.IsNotNull(a.Engine.Status.LastError);

        // Automatic requests during a user's sync are dropped, as they would be with nothing running.
        location.Hold();
        var running = a.Engine.SyncNowAsync();
        await Eventually(() => location.Listings == 2, "the user's listing");
        var automatic = a.Engine.WriteNowAsync();
        Assert.AreSame(running, automatic, "the automatic request is dropped; it only waits for the sync running");
        location.Release();
        await running.WaitAsync(TimeSpan.FromSeconds(10));
        if (a.Engine.Current is { } after) await after.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.AreEqual(2, location.Listings, "nothing ran after the user's sync");

        // Back in a backoff, a user's request during a sync is queued.
        location.FailNextListing = new IOException("offline");
        await a.SyncAsync();
        Assert.AreEqual(3, location.Listings);
        location.Hold();
        var again = a.Engine.SyncNowAsync();
        await Eventually(() => location.Listings == 4, "the next user's listing");
        var queued = a.Engine.SyncNowAsync();
        Assert.AreNotSame(again, queued, "the user's request is queued");
        location.Release();
        await Task.WhenAll(again, queued).WaitAsync(TimeSpan.FromSeconds(10));
        Assert.AreEqual(5, location.Listings, "the user's request ran once more after the sync");
    }

    [TestMethod]
    public async Task ALocationChosenDuringASync_GetsTheQueuedSync()
    {
        var first = Path.Combine(_root, "first");
        var second = Path.Combine(_root, "second");
        Directory.CreateDirectory(first);
        Directory.CreateDirectory(second);
        var gated = new TestLocation(first);
        ISyncLocation? Locations(SyncLocationSettings settings) =>
            settings.Path == first ? gated : new FolderSyncLocation(settings.Path!);
        using var a = new Device(_root, "A", T0, first, locations: Locations);

        // Another device already wrote to the first location.
        using (var other = new Device(_root, "B", T0, first))
        {
            other.Progress.Update(603, "movie", 0.3, 300, durationSeconds: 1000);
            await other.SyncAsync();
        }
        var otherFile = Directory.EnumerateFiles(SpaceFolder(first)).Single();

        // The launch sync holds at the first location's listing.
        gated.Hold();
        a.Engine.Start();
        var running = a.Engine.Current!;
        await Eventually(() => gated.Listings == 1, "the first location's listing");
        var switching = a.Engine.SetLocationAsync(
            new SyncLocationSettings { Kind = SyncLocationKind.Folder, Path = second, Space = "Family" }, CancellationToken.None);
        await Eventually(() => a.State.Location?.Path == second, "the new location");
        gated.Release();
        await Task.WhenAll(running, switching).WaitAsync(TimeSpan.FromSeconds(10));
        if (a.Engine.Current is { } current) await current.WaitAsync(TimeSpan.FromSeconds(10));

        Assert.IsNull(a.State.ReadStamp(Path.GetFileName(otherFile)), "nothing read at the old location is recorded against the new one");
        Assert.AreEqual(0, a.State.Peers.Count);
        Assert.IsFalse(File.Exists(FileOf(first, a.State.DeviceId)), "the old location isn't written");
        Assert.IsTrue(File.Exists(FileOf(second, a.State.DeviceId)), "the queued sync wrote to the new one");
    }

    // ------------------------------------------------------------------
    // Removed devices (removedDevicesDisappearFromTheList, §8.3)
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task RemovedDevicesLeaveTheList_AndARemovedDeviceComesBack()
    {
        var location = Path.Combine(_root, "location");
        using var a = new Device(_root, "A", T0, location);
        using var b = new Device(_root, "B", T0 + 1000, location);
        a.Progress.Update(603, "movie", 0.2, 200, durationSeconds: 1000);
        await a.SyncAsync();
        await b.SyncAsync();
        Assert.AreEqual(1, b.State.Peers.Count);

        // A's file is deleted elsewhere: B forgets A at its next listing.
        File.Delete(FileOf(location, a.State.DeviceId));
        b.Now += 1000;
        await b.SyncAsync();
        Assert.AreEqual(0, b.State.Peers.Count);
        Assert.IsNull(b.State.ReadStamp(a.State.DeviceId + ".json"));

        // A syncs again with nothing changed and writes its file again.
        a.Now += 2000;
        await a.SyncAsync();
        Assert.IsTrue(File.Exists(FileOf(location, a.State.DeviceId)));
        b.Now += 3000;
        await b.SyncAsync();
        Assert.AreEqual(1, b.State.Peers.Count, "A is back");

        // An empty listing clears nothing, and B's own file comes back.
        foreach (var file in Directory.GetFiles(SpaceFolder(location))) File.Delete(file);
        b.Now += 4000;
        await b.SyncAsync();
        Assert.AreEqual(1, b.State.Peers.Count, "an empty listing keeps the devices");
        Assert.IsTrue(File.Exists(FileOf(location, b.State.DeviceId)));
    }

    // ------------------------------------------------------------------
    // Far-future stamps (farFutureRecordsFromCloudKitStayLocalUntilTheClockPassesThem)
    // ------------------------------------------------------------------

    [TestMethod]
    public void TheClockIgnoresStampsMoreThanADayAhead()
    {
        var now = T0;
        var clock = new SyncClock(() => now);
        clock.Observe(now + 3_600_000);
        Assert.AreEqual(now + 3_600_001, clock.Stamp(), "an hour ahead is followed");

        var mark = clock.HighWater;
        clock.Observe(now + 2 * Day);
        Assert.AreEqual(mark, clock.HighWater, "two days ahead is not");
        clock.Restore(now + 2 * Day);
        Assert.AreEqual(mark, clock.HighWater, "nor restored");
        Assert.AreEqual(0, new SyncClock(() => now, highWater: now + 2 * Day).HighWater, "nor taken at construction");

        var fresh = new SyncClock(() => now);
        fresh.ObserveAll([now + 2 * Day, now + 10, 5]);
        Assert.AreEqual(now + 10, fresh.HighWater, "one far-future stamp doesn't hide the others");
    }

    [TestMethod]
    public async Task FarFutureRecordsStayLocalUntilTheClockPassesThem()
    {
        var location = Path.Combine(_root, "location");
        using var a = new Device(_root, "A", T0, location);
        var ahead = T0 + 7 * Day;
        a.Progress.ReplaceAll([new WatchProgress
        {
            TmdbId = 603,
            MediaType = "movie",
            Position = 0.4,
            WatchedSeconds = 400,
            DurationSeconds = 1000,
            LastWatchedEpochMillis = ahead,
            Device = "0d0d0d0d-0d0d-4d0d-8d0d-0d0d0d0d0d0d",
        }]);
        Assert.IsTrue(a.Clock.HighWater < ahead, "the store's far-future stamp doesn't raise the clock");

        a.Media.SetFavourite(1, "movie", true);
        await a.SyncAsync();
        var file = Read(location, a.State.DeviceId);
        Assert.AreEqual(0, file.WatchProgress.Count, "the far-future record stays out of the file");
        Assert.AreEqual(1, file.UserMedia.Count);
        Assert.IsTrue(file.UpdateTime < ahead);
        Assert.IsNotNull(a.Progress.Get(603, "movie"), "and stays in the store");

        // Once the clock passes it, the next sync writes it with no local change.
        a.Now += 8 * Day;
        await a.SyncAsync();
        Assert.AreEqual(1, Read(location, a.State.DeviceId).WatchProgress.Count);
        Assert.AreEqual(0, a.State.HeldBackUntil);
    }

    [TestMethod]
    public void AFarFutureMarkIsLoweredAtStartup()
    {
        var location = Path.Combine(_root, "location");
        var statePath = Path.Combine(_root, "A", "sync-state.json");
        Directory.CreateDirectory(Path.GetDirectoryName(statePath)!);
        var state = new SyncState(statePath);
        state.HighWater = T0 + 5 * Day;
        state.WrittenHighWater = T0 + 5 * Day;

        using var a = new Device(_root, "A", T0, location);
        Assert.IsTrue(a.State.HighWater < T0 + Day, "the persisted mark came down");
        a.Media.SetFavourite(1, "movie", true);
        Assert.IsTrue(a.Media.Get(1, "movie")!.UpdateTime < T0 + Day, "new writes are stamped near now");
    }

    // ------------------------------------------------------------------
    // Episodes through the library's index (libraryEpisodesResolveWithoutTMDB)
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task LibraryEpisodesResolve_WithTheIndexLoadedOncePerSyncAndOnlyWhenNeeded()
    {
        var location = Path.Combine(_root, "location");
        var loads = 0;
        using var a = new Device(_root, "A", T0, location);
        using var b = new Device(_root, "B", T0 + 1000, location, episodes: () =>
        {
            loads++;
            return new Dictionary<string, int> { ["tv:1399:2:4"] = 7 };
        });

        a.Progress.Update(99001, "episode", 0.5, 1500, showTmdbId: 1399, seasonNumber: 2, episodeNumber: 4, durationSeconds: 3000);
        a.Progress.Update(99002, "episode", 0.2, 600, showTmdbId: 1399, seasonNumber: 2, episodeNumber: 5, durationSeconds: 3000);
        a.Progress.Update(603, "movie", 0.3, 300, durationSeconds: 1000);
        await a.SyncAsync();
        await b.SyncAsync();
        Assert.AreEqual(1, loads, "one load for the whole sync");
        Assert.AreEqual(0.5, b.Progress.Get(7, "episode")!.Position, 1e-9);
        Assert.AreEqual(0, b.Progress.GetEpisode(1399, 2, 5)!.TmdbId, "an episode the library doesn't know waits");

        // A movie, and an episode the store already placed, need no index.
        a.Now += 10_000;
        a.Progress.Update(603, "movie", 0.6, 600, durationSeconds: 1000);
        a.Progress.Update(99001, "episode", 0.7, 2100, showTmdbId: 1399, seasonNumber: 2, episodeNumber: 4, durationSeconds: 3000);
        await a.SyncAsync();
        b.Now += 20_000;
        await b.SyncAsync();
        Assert.AreEqual(1, loads);
        Assert.AreEqual(0.7, b.Progress.Get(7, "episode")!.Position, 1e-9);
    }

    [TestMethod]
    public void TheLibraryIndexKeepsTheFirstIdentifiedCopy()
    {
        LibraryShow Show(int? id, params (int Season, int Episode, int? Id)[] episodes) => new()
        {
            TmdbId = id,
            Episodes = [.. episodes.Select(e => new LibraryEpisode { Season = e.Season, Episode = e.Episode, TmdbId = e.Id })],
        };
        var index = SyncEpisodeIndex.FromLibrary(
        [
            Show(1399, (2, 4, 7), (2, 5, null)),
            Show(1399, (2, 4, 8)),
            Show(null, (1, 1, 9)),
            Show(1400, (1, 1, 0)),
        ]);
        Assert.AreEqual(1, index.Count);
        Assert.AreEqual(7, index["tv:1399:2:4"]);

        var loads = 0;
        var lazy = new SyncEpisodeIndex(() =>
        {
            loads++;
            return index;
        });
        Assert.AreEqual(0, loads, "nothing loads until a lookup");
        Assert.AreEqual(7, lazy.Id("tv:1399:2:4"));
        Assert.IsNull(lazy.Id("tv:1:1:1"));
        Assert.AreEqual(1, loads);
        Assert.IsNull(new SyncEpisodeIndex(null).Id("tv:1399:2:4"));
    }

    // ------------------------------------------------------------------
    // Folder locations: a refused download, a moved folder
    // ------------------------------------------------------------------

    [TestMethod]
    public void CloudFileErrorsAreTheCloudFilesCodesOnly()
    {
        Assert.IsTrue(CloudFileErrors.IsCloudFileError(unchecked((int)0x8007016A)), "provider not running");
        Assert.IsTrue(CloudFileErrors.IsCloudFileError(unchecked((int)0x8007018B)), "access denied");
        Assert.IsFalse(CloudFileErrors.IsCloudFileError(unchecked((int)0x80070020)), "sharing violation");
        Assert.IsFalse(CloudFileErrors.IsCloudFileError(unchecked((int)0x80070002)), "file not found");
        Assert.IsFalse(CloudFileErrors.IsCloudFileError(unchecked((int)0x80070178)), "not a cloud file");
        Assert.IsFalse(CloudFileErrors.IsCloudFileError(0x16A), "a bare Win32 code is not an HRESULT");
        Assert.IsTrue(CloudFileErrors.IsInUse(unchecked((int)0x80070020)));
        Assert.IsTrue(CloudFileErrors.IsInUse(unchecked((int)0x80070021)));
        Assert.IsFalse(CloudFileErrors.IsInUse(unchecked((int)0x8007016A)));
    }

    [TestMethod]
    public async Task ARefusedCloudDownloadIsNamed_AndReadAgainNextTime()
    {
        var (b, location, folder) = DeviceWithTestLocation("B", T0 + 1000);
        using var device = b;
        using var a = new Device(_root, "A", T0, folder);
        a.Progress.Update(603, "movie", 0.2, 200, durationSeconds: 1000);
        await a.SyncAsync();
        await b.SyncAsync();
        var stamp = b.State.ReadStamp(a.State.DeviceId + ".json");
        Assert.IsNotNull(stamp);

        a.Now += 10_000;
        a.Progress.Update(603, "movie", 0.6, 600, durationSeconds: 1000);
        await a.SyncAsync();
        location.RefuseReads = new IOException("The cloud sync provider failed to validate the downloaded data.", unchecked((int)0x8007016A));
        b.Now += 20_000;
        await b.SyncAsync();
        Assert.IsTrue(b.Engine.Status.Warnings.Any(warning => warning.Contains("from A couldn’t be downloaded")),
            string.Join(" | ", b.Engine.Status.Warnings));
        Assert.AreEqual(stamp, b.State.ReadStamp(a.State.DeviceId + ".json"), "the stamp isn't recorded");
        Assert.AreEqual(0.2, b.Progress.Get(603, "movie")!.Position, 1e-9);

        location.RefuseReads = null;
        b.Now += 30_000;
        await b.SyncAsync();
        Assert.AreEqual(0.6, b.Progress.Get(603, "movie")!.Position, 1e-9, "the next sync reads it");
    }

    [TestMethod]
    public async Task AMovedPickedFolderIsFollowed()
    {
        var picked = Path.Combine(_root, "picked");
        var renamed = Path.Combine(_root, "renamed");
        using var a = new Device(_root, "A", T0, picked, follow: (_, _) => Task.FromResult<string?>(renamed));
        a.Progress.Update(603, "movie", 0.2, 200, durationSeconds: 1000);
        await a.SyncAsync();
        Assert.IsTrue(File.Exists(FileOf(picked, a.State.DeviceId)));

        Directory.Move(picked, renamed);
        a.Now += 1000;
        a.Progress.Update(603, "movie", 0.4, 400, durationSeconds: 1000);
        await a.SyncAsync();
        Assert.IsNull(a.Engine.Status.LastError);
        Assert.AreEqual(renamed, a.State.Location!.Path);
        Assert.AreEqual(400, Read(renamed, a.State.DeviceId).WatchProgress.Single().Position);
    }

    // ------------------------------------------------------------------
    // The watchlist mirror after launch
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task AWatchlistChangeFromAnyThreadIsMirrored_ButNotOneTheMergeSet()
    {
        var location = Path.Combine(_root, "location");
        using var a = new Device(_root, "A", T0, location);
        a.Engine.Start();
        await a.Engine.Current!;

        await Task.Run(() => a.Watchlist!.SetWatchlist(true, new MediaRef { Id = 603, MediaType = "movie" }, new WatchlistMetadata { Title = "The Matrix" }));
        Assert.IsTrue(a.Media.Get(603, "movie")!.Watchlist);

        a.Watchlist!.SetWatchlist(true, new MediaRef { Id = 604, MediaType = "movie" }, fromSync: true);
        Assert.IsNull(a.Media.Get(604, "movie"), "a flag the merge set is already in the records it came from");
    }
}
