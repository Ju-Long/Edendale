using Edendale.Windows.Core.Sync;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// SYNC-PROTOCOL.md Appendix A.1 (main 915b40d): the merge vectors M1–M8,
/// implemented natively, plus the property of §7.4 that any number of
/// devices converge whatever the order their files are read in.
/// </summary>
[TestClass]
public sealed class SyncMergeTests
{
    internal const string A = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a";
    internal const string B = "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b";
    internal const long Now = 1793577600000; // 2026-11-02T00:00:00Z

    internal static SyncClock ClockAt(long now, long highWater = 0) => new(() => now, highWater);

    internal static SyncProgressRecord Movie(int tmdbId, double position, long updateTime, string device, bool completed = false, double duration = 8160) => new()
    {
        Type = "movie", TmdbId = tmdbId, Position = position, Duration = duration, Completed = completed, Device = device, UpdateTime = updateTime,
    };

    internal static SyncUserMediaRecord Media(string type, int tmdbId, long updateTime, string device, bool favourite = false, bool watchlist = false, double? rating = null, string? review = null) => new()
    {
        Type = type, TmdbId = tmdbId, Favourite = favourite, Watchlist = watchlist, Rating = rating, Review = review, Device = device, UpdateTime = updateTime,
    };

    [TestMethod]
    public void M1_NewestWins()
    {
        var store = new Dictionary<string, SyncProgressRecord> { ["movie:603"] = Movie(603, 100, 1762000000000, A) };
        var clock = ClockAt(Now);
        var outcome = SyncMerge.Apply(store, [Movie(603, 200, 1762000001000, B)], clock);

        Assert.AreEqual(1, outcome.Applied);
        var record = store["movie:603"];
        Assert.AreEqual(200, record.Position);
        Assert.AreEqual(B, record.Device);
        Assert.AreEqual(1762000001000, record.UpdateTime);
        Assert.AreEqual(1762000001000, clock.HighWater);
    }

    [TestMethod]
    public void M2_TieBreaksOnTheLexicallySmallerDevice_InEitherOrder()
    {
        var fromB = Media("tv", 1399, 1762000002000, B, favourite: true);
        var fromA = Media("tv", 1399, 1762000002000, A, favourite: false, watchlist: true);

        var store = new Dictionary<string, SyncUserMediaRecord> { ["tv:1399"] = fromB };
        SyncMerge.Apply(store, [fromA], ClockAt(Now));
        Assert.AreEqual(A, store["tv:1399"].Device);
        Assert.IsTrue(store["tv:1399"].Watchlist);
        Assert.IsFalse(store["tv:1399"].Favourite);

        var other = new Dictionary<string, SyncUserMediaRecord> { ["tv:1399"] = fromA };
        var outcome = SyncMerge.Apply(other, [fromB], ClockAt(Now));
        Assert.AreEqual(0, outcome.Applied);
        Assert.AreEqual(A, other["tv:1399"].Device);
    }

    [TestMethod]
    public void M3_TombstoneWins_AndIsKeptNotRemoved()
    {
        var store = new Dictionary<string, SyncUserMediaRecord> { ["tv:1399"] = Media("tv", 1399, 1762000003000, A, rating: 8.0) };
        var tombstone = Media("tv", 1399, 1762000004000, B);
        SyncMerge.Apply(store, [tombstone], ClockAt(Now));

        Assert.IsTrue(store.ContainsKey("tv:1399"));
        Assert.IsTrue(store["tv:1399"].IsTombstone);
        Assert.IsNull(store["tv:1399"].Rating);

        // Writing the file out includes the tombstone.
        var file = new SyncDeviceFile { Space = "Family", Device = new SyncDevice(A, "PC", "windows", "27.1"), UserMedia = [.. store.Values] };
        var decoded = SyncDeviceFileCodec.Decode(SyncDeviceFileCodec.Encode(file), A + ".json", "Family");
        Assert.IsTrue(decoded.IsValid);
        Assert.AreEqual(1, decoded.File!.UserMedia.Count);
        Assert.IsTrue(decoded.File.UserMedia[0].IsTombstone);
    }

    [TestMethod]
    public void M4_FarFutureGuard()
    {
        var clock = ClockAt(Now, highWater: 1762000000000);
        var store = new Dictionary<string, SyncProgressRecord>();
        var outcome = SyncMerge.Apply(store, [Movie(603, 300, 1793750400000, B)], clock);
        Assert.AreEqual(0, outcome.Applied);
        Assert.AreEqual(1, outcome.FarFuture);
        Assert.IsFalse(store.ContainsKey("movie:603"));
        Assert.AreEqual(1762000000000, clock.HighWater);

        // Exactly 24 hours ahead is applied; one millisecond more is not.
        Assert.AreEqual(1, SyncMerge.Apply(store, [Movie(603, 300, 1793664000000, B)], clock).Applied);
        Assert.AreEqual(1793664000000, clock.HighWater);
        Assert.AreEqual(1, SyncMerge.Apply(store, [Movie(604, 300, 1793664000000 + 1, B)], clock).FarFuture);
        Assert.AreEqual(1793664000000, clock.HighWater);
    }

    [TestMethod]
    public void M5_MonotonicStamping()
    {
        var behind = ClockAt(1762000000000, highWater: 1762000005000);
        Assert.AreEqual(1762000005001, behind.Stamp());
        Assert.AreEqual(1762000005001, behind.HighWater);

        var ahead = ClockAt(1762000009000, highWater: 1762000005000);
        Assert.AreEqual(1762000009000, ahead.Stamp());
    }

    [TestMethod]
    public void M6_InvalidStampsCountAsZero()
    {
        // A stamp in seconds (below 2000-01-01 in milliseconds) and a missing one both read as 0.
        var json = $$"""
            {"format":1,"space":"Family","device":{"id":"{{B}}","name":"B","platform":"android","app":"27.1"},"updateTime":0,
             "watchProgress":[
               {"type":"movie","tmdbId":603,"position":1,"duration":2,"completed":false,"device":"{{B}}","updateTime":1500000000},
               {"type":"movie","tmdbId":604,"position":1,"duration":2,"completed":false,"device":"{{B}}"}],
             "userMedia":[]}
            """;
        var decoded = SyncDeviceFileCodec.Decode(System.Text.Encoding.UTF8.GetBytes(json), B + ".json", "Family");
        Assert.IsTrue(decoded.IsValid, decoded.Skipped?.ToString());
        Assert.IsTrue(decoded.File!.WatchProgress.All(record => record.UpdateTime == 0));

        var clock = ClockAt(Now, highWater: 1762000000000);
        var store = new Dictionary<string, SyncProgressRecord> { ["movie:603"] = Movie(603, 100, 1762000000000, A) };
        var outcome = SyncMerge.Apply(store, decoded.File.WatchProgress, clock);
        Assert.AreEqual(A, store["movie:603"].Device, "a valid local record beats a zero stamp");
        Assert.IsTrue(store.ContainsKey("movie:604"), "an absent key is stored");
        Assert.AreEqual(1, outcome.Applied);
        Assert.AreEqual(1762000000000, clock.HighWater, "zero stamps never raise the high-water mark");
    }

    [TestMethod]
    public void M7_SkippedFilesLeaveStoreAndHighWaterUnchanged()
    {
        string Body(string deviceId, int format, string space) => $$"""
            {"format":{{format}},"space":"{{space}}","device":{"id":"{{deviceId}}","name":"B","platform":"android","app":"27.1"},"updateTime":1762000001000,
             "watchProgress":[{"type":"movie","tmdbId":603,"position":200,"duration":8160,"completed":false,"device":"{{B}}","updateTime":1762000001000}],"userMedia":[]}
            """;
        var wrongId = SyncDeviceFileCodec.Decode(System.Text.Encoding.UTF8.GetBytes(Body(A, 1, "Family")), B + ".json", "Family");
        Assert.AreEqual(SyncSkipReason.WrongDeviceId, wrongId.Skipped);
        var newer = SyncDeviceFileCodec.Decode(System.Text.Encoding.UTF8.GetBytes(Body(B, 2, "Family")), B + ".json", "Family");
        Assert.AreEqual(SyncSkipReason.NewerFormat, newer.Skipped);
        Assert.AreEqual("B", newer.DeviceName, "the warning names the device");
        var otherSpace = SyncDeviceFileCodec.Decode(System.Text.Encoding.UTF8.GetBytes(Body(B, 1, "Other")), B + ".json", "Family");
        Assert.AreEqual(SyncSkipReason.WrongSpace, otherSpace.Skipped);
        var huge = SyncDeviceFileCodec.Decode(new byte[9 * 1024 * 1024], B + ".json", "Family");
        Assert.AreEqual(SyncSkipReason.TooLarge, huge.Skipped);
        Assert.IsFalse(wrongId.IsValid || newer.IsValid || otherSpace.IsValid || huge.IsValid);
    }

    [TestMethod]
    public void M8_OrderIndependentAndIdempotent()
    {
        var fileA = new[] { Movie(603, 100, 1762000000000, A) };
        var fileB = new[] { Movie(603, 200, 1762000001000, B) };
        var mediaA = new[] { Media("tv", 1399, 1762000003000, A, rating: 8.0) };
        var mediaB = new[] { Media("tv", 1399, 1762000004000, B) };

        var first = new Dictionary<string, SyncRecord>();
        var clock1 = ClockAt(Now);
        foreach (var file in new IEnumerable<SyncRecord>[] { fileA, mediaA, fileB, mediaB, fileA, mediaA, fileB, mediaB }) SyncMerge.Apply(first, file, clock1);

        var second = new Dictionary<string, SyncRecord>();
        var clock2 = ClockAt(Now);
        foreach (var file in new IEnumerable<SyncRecord>[] { fileB, mediaB, fileA, mediaA }) SyncMerge.Apply(second, file, clock2);
        var again = SyncMerge.Apply(second, fileB.Cast<SyncRecord>().Concat(mediaB).Concat(fileA).Concat(mediaA), clock2);

        Assert.AreEqual(0, again.Applied, "the second read changes nothing");
        CollectionAssert.AreEquivalent(first.Keys.ToList(), second.Keys.ToList());
        foreach (var key in first.Keys) Assert.AreEqual(first[key], second[key]);
        Assert.AreEqual(B, first["movie:603"].Device);
        Assert.IsTrue(first["tv:1399"].IsTombstone);
        Assert.AreEqual(clock1.HighWater, clock2.HighWater);
    }

    /// <summary>§7.4: random record sets on random devices, read in random orders, converge.</summary>
    [TestMethod]
    public void Property_DevicesConvergeRegardlessOfReadOrder()
    {
        var random = new Random(27_1);
        for (var trial = 0; trial < 40; trial++)
        {
            var deviceCount = random.Next(2, 6);
            var devices = Enumerable.Range(0, deviceCount).Select(_ => Guid.NewGuid().ToString("D").ToLowerInvariant()).ToList();
            var keys = Enumerable.Range(0, random.Next(1, 8)).Select(i => 600 + i).ToList();

            // Each device starts with one record per key it holds. Stamps come from
            // a small range so ties across devices are common.
            var files = new Dictionary<string, List<SyncRecord>>();
            foreach (var device in devices)
            {
                var records = new List<SyncRecord>();
                foreach (var key in keys.Where(_ => random.Next(3) > 0))
                {
                    var stamp = 1762000000000 + random.Next(0, 5) * 1000;
                    records.Add(random.Next(2) == 0
                        ? Movie(key, random.Next(0, 5000), stamp, device, completed: random.Next(4) == 0)
                        : Media("movie", key, stamp, device, favourite: random.Next(2) == 0, rating: random.Next(3) == 0 ? 7.5 : null));
                }
                files[device] = records;
            }

            // Every device reads every other device's file in its own random order, some twice.
            var stores = new Dictionary<string, Dictionary<string, SyncRecord>>();
            foreach (var device in devices)
            {
                var store = new Dictionary<string, SyncRecord>();
                SyncMerge.Apply(store, files[device], ClockAt(Now));
                var others = devices.Where(d => d != device).OrderBy(_ => random.Next()).ToList();
                if (random.Next(2) == 0) others.AddRange(others.ToList());
                foreach (var other in others) SyncMerge.Apply(store, files[other], ClockAt(Now));
                stores[device] = store;
            }

            var reference = stores[devices[0]];
            foreach (var device in devices.Skip(1))
            {
                var store = stores[device];
                CollectionAssert.AreEquivalent(reference.Keys.ToList(), store.Keys.ToList(), $"trial {trial}: key sets differ");
                foreach (var key in reference.Keys) Assert.AreEqual(reference[key], store[key], $"trial {trial}: {key} differs");
            }
        }
    }

    [TestMethod]
    public void SpaceNames_FollowSection3()
    {
        Assert.AreEqual("Family", SyncSpaceName.Normalize("  Family "));
        Assert.IsNull(SyncSpaceName.Normalize(""));
        Assert.IsNull(SyncSpaceName.Normalize("."));
        Assert.IsNull(SyncSpaceName.Normalize(".."));
        Assert.IsNull(SyncSpaceName.Normalize("a/b"));
        Assert.IsNull(SyncSpaceName.Normalize("a\\b"));
        Assert.IsNull(SyncSpaceName.Normalize("a:b"));
        Assert.IsNull(SyncSpaceName.Normalize("tab\there"));
        Assert.IsNotNull(SyncSpaceName.Normalize(new string('x', 64)));
        Assert.IsNull(SyncSpaceName.Normalize(new string('x', 65)));
        Assert.IsNotNull(SyncSpaceName.Normalize("家族"));
    }

    [TestMethod]
    public void TombstoneRetention_IsAYearAndADay_SoM3IsStillWritten()
    {
        var fresh = Media("movie", 1, Now - 100L * 86_400_000, A);
        var stale = Media("movie", 2, Now - 367L * 86_400_000, A);
        var staleButLive = Media("movie", 3, Now - 400L * 86_400_000, A, favourite: true);
        Assert.IsFalse(SyncMerge.IsExpiredTombstone(fresh, Now));
        Assert.IsTrue(SyncMerge.IsExpiredTombstone(stale, Now));
        Assert.IsFalse(SyncMerge.IsExpiredTombstone(staleButLive, Now));

        // M3's tombstone is 365.48 days old at the vector's clock and must still be written.
        Assert.IsFalse(SyncMerge.IsExpiredTombstone(Media("tv", 1399, 1762000004000, B), Now));
    }
}
