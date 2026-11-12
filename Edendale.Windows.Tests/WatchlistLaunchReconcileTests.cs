using Edendale.Windows.Core.Sync;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The watchlist flag at launch (UserMediaStore.reconcileWatchlist on
/// apple-27.1, K.7): unstamped rows take the watchlist's flag without a
/// fresh stamp, rows another device stamped win, nothing is ever stamped
/// lower, and a second run changes nothing. Ports
/// theWatchlistFlagComesFromTheLocalWatchlist, plus the Windows cases.
/// </summary>
[TestClass]
public sealed class WatchlistLaunchReconcileTests
{
    private const string OtherDevice = "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b";
    private const string ThisDevice = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a";
    private const long Now = 1_763_000_000_000;

    private string _directory = "";

    [TestInitialize]
    public void Setup()
    {
        _directory = Path.Combine(Path.GetTempPath(), "edendale-reconcile-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(_directory);
    }

    [TestCleanup]
    public void Cleanup()
    {
        try { Directory.Delete(_directory, recursive: true); } catch { /* best effort */ }
    }

    private UserMediaStore NewStore(params UserMediaRecord[] rows)
    {
        var store = new UserMediaStore(Path.Combine(_directory, "user-media.json"), new SyncClock(() => Now), () => ThisDevice);
        if (rows.Length > 0) store.ReplaceAll(rows); // verbatim, as the replica import loads them
        return store;
    }

    private static UserMediaRecord Row(int id, Action<UserMediaRecord> set)
    {
        var row = new UserMediaRecord { TmdbId = id, MediaType = "movie" };
        set(row);
        return row;
    }

    private static WatchlistListing Listed(int id, long at) => new(id, "movie", at);

    [TestMethod]
    public void ApplesCase_UnstampedRowsSeed_StampedRowsWin()
    {
        var favourite = Row(1, row => { row.Favourite = true; row.FavouriteUpdatedAt = 1760000000000; });
        var changedElsewhere = Row(2, row => { row.Watchlist = true; row.UpdateTime = 1762000000000; row.Device = OtherDevice; });
        var removedElsewhere = Row(3, row => { row.Rating = 7; row.UpdateTime = 1762000000001; row.Device = OtherDevice; });
        var store = NewStore(favourite, changedElsewhere, removedElsewhere);
        var plan = WatchlistLaunchReconcile.Plan(
            store.All,
            [Listed(1, 1761000000000), Listed(3, 1761000000000), Listed(4, 1761000000000)],
            ThisDevice, Now, firstRun: true);

        var seeded = plan.Seeds.Single(seed => seed.TmdbId == 1);
        Assert.IsTrue(seeded.InWatchlist);
        Assert.AreEqual(1761000000000, seeded.UpdateTime, "dated when the title joined the watchlist");
        var onlyListed = plan.Seeds.Single(seed => seed.TmdbId == 4);
        Assert.IsTrue(onlyListed.InWatchlist);
        Assert.AreEqual(1761000000000, onlyListed.UpdateTime);

        CollectionAssert.AreEquivalent(
            new[] { (2, true), (3, false) },
            plan.WatchlistFollows.Select(follow => (follow.TmdbId, follow.InWatchlist)).ToArray(),
            "the watchlist follows the rows another device stamped");
        Assert.AreEqual(0, plan.FreshStamps.Count);

        // In the store: the favourite gains the flag, unsigned, and the new row exists.
        store.ApplyLaunchSeed(plan.Seeds);
        var row1 = store.Get(1, "movie")!;
        Assert.IsTrue(row1.Watchlist && row1.Favourite);
        Assert.AreEqual(1761000000000, row1.UpdateTime);
        Assert.IsNull(row1.Device);
        Assert.AreEqual(1761000000000, store.Get(4, "movie")!.UpdateTime);
        Assert.AreEqual(1762000000001, store.Get(3, "movie")!.UpdateTime, "a row the watchlist follows is unchanged");
        Assert.IsFalse(store.Get(3, "movie")!.Watchlist);
    }

    [TestMethod]
    public void ASecondRunChangesNothing()
    {
        var store = NewStore(
            Row(1, row => { row.Favourite = true; row.FavouriteUpdatedAt = 1760000000000; }),
            Row(2, row => { row.Watchlist = true; row.UpdateTime = 1762000000000; row.Device = OtherDevice; }));
        var listed = new List<WatchlistListing> { Listed(1, 1761000000000), Listed(5, 1761500000000) };
        var plan = WatchlistLaunchReconcile.Plan(store.All, listed, ThisDevice, Now, firstRun: true);

        // Apply the plan as the engine does: seeds into the store, follows into the watchlist.
        store.ApplyLaunchSeed(plan.Seeds);
        foreach (var follow in plan.WatchlistFollows)
        {
            if (follow.InWatchlist) listed.Add(Listed(follow.TmdbId, Now));
        }
        var again = WatchlistLaunchReconcile.Plan(store.All, listed, ThisDevice, Now, firstRun: false);
        Assert.IsTrue(again.IsEmpty, $"{again.Seeds.Count} seeds, {again.WatchlistFollows.Count} follows, {again.FreshStamps.Count} fresh");
    }

    [TestMethod]
    public void A270FavouriteListedEarlierKeepsItsTime()
    {
        const long t3 = 1_755_000_000_000;
        const long t9 = 1_759_000_000_000;
        var store = NewStore(Row(1, row => { row.Favourite = true; row.FavouriteUpdatedAt = t9; }));
        var plan = WatchlistLaunchReconcile.Plan(store.All, [Listed(1, t3)], ThisDevice, Now, firstRun: true);
        var seed = plan.Seeds.Single();
        Assert.IsTrue(seed.InWatchlist);
        Assert.AreEqual(0, seed.UpdateTime, "the listing is older than the favourite: no stamp of its own");

        store.ApplyLaunchSeed(plan.Seeds);
        Assert.AreEqual(t9, store.Get(1, "movie")!.EffectiveUpdateTime, "never dated back to the watchlist add");
    }

    [TestMethod]
    public void ALegacyUnlistedFlagIsClearedWithoutAStamp()
    {
        var legacy = Row(1, row => row.Watchlist = true);
        var plan = WatchlistLaunchReconcile.Plan([legacy], [], ThisDevice, Now, firstRun: true);
        var seed = plan.Seeds.Single();
        Assert.IsFalse(seed.InWatchlist);
        Assert.AreEqual(0, seed.UpdateTime, "no fresh stamp, so no other device loses the title");

        var store = NewStore();
        store.ApplyLaunchSeed([new WatchlistFlagUpdate(1, "movie", true)]);
        Assert.IsTrue(store.Get(1, "movie")!.Watchlist);
        store.ApplyLaunchSeed(plan.Seeds);
        Assert.IsNull(store.Get(1, "movie"), "a row that held only the legacy flag goes, so the file never carries it");
    }

    [TestMethod]
    public void OnTheFirstRunThisPCsImportedRowsSeed_AfterwardsTheyTakeAFreshStamp()
    {
        // The replica import signed this PC's old rows with its own ID.
        var imported = Row(1, row => { row.Watchlist = true; row.UpdateTime = 1758000000000; row.Device = ThisDevice; });
        var first = WatchlistLaunchReconcile.Plan([imported], [], ThisDevice, Now, firstRun: true);
        Assert.AreEqual(1, first.Seeds.Count);
        Assert.AreEqual(0, first.Seeds[0].UpdateTime);
        Assert.AreEqual(0, first.FreshStamps.Count);

        // After that, an own row out of step means the watchlist changed after this PC's last mirror.
        var later = WatchlistLaunchReconcile.Plan([imported], [], ThisDevice, Now, firstRun: false);
        Assert.AreEqual(0, later.Seeds.Count);
        Assert.AreEqual(1, later.FreshStamps.Count);
        Assert.IsFalse(later.FreshStamps[0].InWatchlist);
    }

    [TestMethod]
    public void AListingTimeIsNeverAfterThisPCsClock()
    {
        var plan = WatchlistLaunchReconcile.Plan([], [Listed(1, Now + 86_400_000), Listed(2, 0)], ThisDevice, Now, firstRun: true);
        Assert.IsTrue(plan.Seeds.All(seed => seed.UpdateTime == Now));
    }

    [TestMethod]
    public void TheWatchlistMirrorNeverLowersAStamp()
    {
        var store = NewStore();
        store.SetFavourite(1, "movie", true);
        var stamped = store.Get(1, "movie")!.UpdateTime;
        store.SetWatchlistMirror(1, "movie", true, stamp: stamped - 10_000);
        Assert.AreEqual(stamped, store.Get(1, "movie")!.UpdateTime);
        Assert.IsTrue(store.Get(1, "movie")!.Watchlist);
    }
}
