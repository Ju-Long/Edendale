using Edendale.Windows.Core;
using Edendale.Windows.Models;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Cloud-priority reconciliation: TMDB's complete lists win over confirmed local
/// rows, but a pending local add/remove holds until TMDB reflects it. Mirrors the
/// Apple WatchlistStore reconcile tests, without a store or network.
/// </summary>
[TestClass]
public sealed class WatchlistReconcilerTests
{
    private static MediaItem Item(int id, string mediaType = "movie", string title = "Title") => new()
    {
        Id = id,
        MediaType = mediaType,
        Title = title,
        PosterPath = "/p.jpg",
        ReleaseDate = "2026-01-01",
    };

    private static WatchlistRecord Record(
        int id,
        string mediaType = "movie",
        bool inWatchlist = true,
        WatchlistPendingAction pending = WatchlistPendingAction.None) =>
        WatchlistRecord.Create(
            new MediaRef { Id = id, MediaType = mediaType },
            new WatchlistMetadata { Title = "Local" },
            inWatchlist: inWatchlist,
            pending: pending);

    [TestMethod]
    public void Reconcile_ImportsRemoteMetadataForNewRows()
    {
        var result = WatchlistReconciler.Reconcile([], [Item(603, title: "The Matrix")]);

        var record = result.Single();
        Assert.AreEqual("movie:603", record.StorageKey);
        Assert.AreEqual("The Matrix", record.Title);
        Assert.IsTrue(record.InWatchlist);
        Assert.AreEqual(WatchlistPendingAction.None, record.PendingAction);
    }

    [TestMethod]
    public void Reconcile_KeepsMovieAndTvWithTheSameIdDistinct()
    {
        var result = WatchlistReconciler.Reconcile(
            [],
            [Item(5, "movie", "Movie Five"), Item(5, "tv", "Show Five")]);

        Assert.AreEqual(2, result.Count);
        Assert.IsTrue(result.Any(r => r.StorageKey == "movie:5" && r.Title == "Movie Five"));
        Assert.IsTrue(result.Any(r => r.StorageKey == "tv:5" && r.Title == "Show Five"));
    }

    [TestMethod]
    public void Reconcile_PendingAddSurvivesAStaleRemoteList()
    {
        // Added locally but TMDB's list has not caught up yet.
        var local = Record(120, pending: WatchlistPendingAction.Add);

        var result = WatchlistReconciler.Reconcile([local], []);

        var record = result.Single();
        Assert.IsTrue(record.InWatchlist, "a pending add must not be dropped by a stale list");
        Assert.AreEqual(WatchlistPendingAction.Add, record.PendingAction);
    }

    [TestMethod]
    public void Reconcile_RemotePresenceConfirmsAndClearsAPendingAdd()
    {
        var local = Record(120, pending: WatchlistPendingAction.Add);

        var result = WatchlistReconciler.Reconcile([local], [Item(120)]);

        var record = result.Single();
        Assert.IsTrue(record.InWatchlist);
        Assert.AreEqual(WatchlistPendingAction.None, record.PendingAction, "TMDB now lists it — pending clears");
    }

    [TestMethod]
    public void Reconcile_PendingRemovalIsDeletedWhenRemoteConfirmsAbsence()
    {
        var local = Record(77, inWatchlist: false, pending: WatchlistPendingAction.Remove);

        var result = WatchlistReconciler.Reconcile([local], []);

        Assert.AreEqual(0, result.Count, "a removal absent from TMDB is now confirmed and dropped");
    }

    [TestMethod]
    public void Reconcile_PendingRemovalHeldWhileStillOnRemote()
    {
        var local = Record(77, inWatchlist: false, pending: WatchlistPendingAction.Remove);

        var result = WatchlistReconciler.Reconcile([local], [Item(77)]);

        var record = result.Single();
        Assert.IsFalse(record.InWatchlist, "the local removal has not reached TMDB yet");
        Assert.AreEqual(WatchlistPendingAction.Remove, record.PendingAction);
    }

    [TestMethod]
    public void Reconcile_ConfirmedRowAbsentRemotelyIsRemoved()
    {
        // No pending action: a confirmed row gone from TMDB was removed elsewhere.
        var local = Record(50, pending: WatchlistPendingAction.None);

        var result = WatchlistReconciler.Reconcile([local], []);

        Assert.AreEqual(0, result.Count);
    }

    [TestMethod]
    public void PendingPushOrder_IsOldestEditFirstAndSkipsConfirmedRows()
    {
        var confirmed = Record(1, pending: WatchlistPendingAction.None);
        var newer = Record(2, pending: WatchlistPendingAction.Add);
        newer.UpdatedAt = 200;
        var older = Record(3, pending: WatchlistPendingAction.Remove);
        older.UpdatedAt = 100;

        var order = WatchlistReconciler.PendingPushOrder([confirmed, newer, older]);

        CollectionAssert.AreEqual(new[] { "movie:3", "movie:2" }, order.Select(r => r.StorageKey).ToArray());
    }
}
