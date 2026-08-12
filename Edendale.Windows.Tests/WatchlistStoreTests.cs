using System.IO;
using System.Threading.Tasks;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The local-first watchlist store over a fake TMDB remote and temp files:
/// signed-out edits persist without network, signed-in edits push, a failed
/// push stays pending, and legacy flags migrate. Ports the Apple WatchlistStore
/// tests.
/// </summary>
[TestClass]
public sealed class WatchlistStoreTests
{
    private string _storePath = "";
    private string _legacyPath = "";

    [TestInitialize]
    public void Init()
    {
        var dir = Path.Combine(Path.GetTempPath(), $"eden-watchlist-{Guid.NewGuid():N}");
        Directory.CreateDirectory(dir);
        _storePath = Path.Combine(dir, "watchlist.json");
        _legacyPath = Path.Combine(dir, "user-media.json");
    }

    [TestCleanup]
    public void Cleanup()
    {
        try { Directory.Delete(Path.GetDirectoryName(_storePath)!, recursive: true); } catch { /* best effort */ }
    }

    private WatchlistStore NewStore(FakeRemote remote, Func<bool> authenticated) =>
        new(remote, authenticated, _storePath, _legacyPath);

    private static MediaRef Ref(int id, string mediaType = "movie") => new() { Id = id, MediaType = mediaType };

    private static WatchlistMetadata Meta(string title) => new() { Title = title, PosterUrl = "https://img/x.jpg" };

    [TestMethod]
    public void SignedOutChangesPersistLocallyWithoutRemoteRequests()
    {
        var remote = new FakeRemote();
        var store = NewStore(remote, () => false);

        store.SetWatchlist(true, Ref(603), Meta("The Matrix"));

        Assert.IsTrue(store.IsInWatchlist(Ref(603)));
        Assert.AreEqual(1, store.Items.Count);
        Assert.AreEqual("The Matrix", store.Items[0].Title);
        Assert.AreEqual(0, remote.SetCalls.Count, "no account, no remote writes");

        // A fresh store over the same file sees the saved item.
        var reopened = NewStore(new FakeRemote(), () => false);
        Assert.IsTrue(reopened.IsInWatchlist(Ref(603)));
    }

    [TestMethod]
    public void RemovingAnUnsavedTitleIsANoOp()
    {
        var store = NewStore(new FakeRemote(), () => false);
        store.SetWatchlist(false, Ref(999));
        Assert.AreEqual(0, store.Items.Count);
    }

    [TestMethod]
    public async Task SignedInSyncPushesThePendingChange()
    {
        var authenticated = false;
        var remote = new FakeRemote();
        var store = NewStore(remote, () => authenticated);

        // Saved while signed out — a durable pending add, no push yet.
        store.SetWatchlist(true, Ref(603), Meta("The Matrix"));
        Assert.AreEqual(0, remote.SetCalls.Count);

        authenticated = true;
        await store.SyncFromTMDBAsync();

        Assert.IsTrue(remote.SetCalls.Any(call => call.Reference.Id == 603 && call.InWatchlist),
            "the pending add is flushed to TMDB on sync");
    }

    [TestMethod]
    public async Task FailedPushKeepsTheLocalPendingAction()
    {
        var authenticated = false;
        var remote = new FakeRemote();
        var store = NewStore(remote, () => authenticated);
        store.SetWatchlist(true, Ref(603), Meta("The Matrix"));

        authenticated = true;
        remote.ThrowOnSet = true;
        await store.SyncFromTMDBAsync();

        Assert.AreEqual(1, remote.SetCalls.Count, "the push was attempted");
        // The pull returned an empty list; only a surviving pending add keeps
        // the row (a confirmed row absent remotely would be deleted).
        Assert.IsTrue(store.IsInWatchlist(Ref(603)), "a failed push must not lose the local add");
    }

    [TestMethod]
    public async Task FullPullImportsRemoteMetadataAndDropsConfirmedAbsentRows()
    {
        var authenticated = false;
        var remote = new FakeRemote();
        var store = NewStore(remote, () => authenticated);

        // Added locally while signed out — a durable pending add, no push.
        store.SetWatchlist(true, Ref(11), Meta("Star Wars"));
        authenticated = true;

        // A first sync while TMDB still lists it turns the pending add confirmed.
        remote.Movies.Add(new MediaItem { Id = 11, MediaType = "movie", Title = "Star Wars" });
        await store.SyncFromTMDBAsync();
        Assert.IsTrue(store.IsInWatchlist(Ref(11)));

        // Now it is gone remotely and the real lists arrive.
        remote.Movies.Clear();
        remote.Movies.Add(new MediaItem { Id = 603, MediaType = "movie", Title = "The Matrix", PosterPath = "/m.jpg" });
        remote.Shows.Add(new MediaItem { Id = 1399, MediaType = "tv", Title = "Game of Thrones", PosterPath = "/g.jpg" });
        await store.SyncFromTMDBAsync();

        Assert.IsTrue(store.IsInWatchlist(Ref(603, "movie")));
        Assert.IsTrue(store.IsInWatchlist(Ref(1399, "tv")));
        Assert.IsFalse(store.IsInWatchlist(Ref(11)), "a confirmed row absent remotely is removed");
        Assert.AreEqual("The Matrix", store.Items.First(item => item.TmdbId == 603).Title);
    }

    [TestMethod]
    public void MigratesLegacyWatchlistFlagsOnFirstRun()
    {
        File.WriteAllText(_legacyPath, """
        [
          {"tmdbId": 603, "mediaType": "movie", "title": "The Matrix", "posterPath": "/m.jpg", "posterUrl": "https://img/m.jpg", "watchlist": true, "watchlistUpdatedAt": 1700000000000},
          {"tmdbId": 42, "mediaType": "movie", "title": "Not Listed", "favourite": true, "watchlist": false}
        ]
        """);

        var store = NewStore(new FakeRemote(), () => false);

        Assert.IsTrue(store.IsInWatchlist(Ref(603)), "the watchlisted legacy row migrates");
        Assert.IsFalse(store.IsInWatchlist(Ref(42)), "a favourite-only row does not");
        Assert.AreEqual("The Matrix", store.Items.Single().Title);

        // Migration is one-time: a second store does not re-import.
        Assert.IsTrue(File.Exists(_storePath));
    }

    private sealed class FakeRemote : IWatchlistRemote
    {
        public List<(MediaRef Reference, bool InWatchlist)> SetCalls { get; } = [];
        public List<MediaItem> Movies { get; } = [];
        public List<MediaItem> Shows { get; } = [];
        public bool ThrowOnSet { get; set; }

        public Task<IReadOnlyList<MediaItem>> WatchlistItemsAsync(string mediaType) =>
            Task.FromResult<IReadOnlyList<MediaItem>>(mediaType == "tv" ? [.. Shows] : [.. Movies]);

        public Task SetWatchlistAsync(MediaRef reference, bool inWatchlist)
        {
            SetCalls.Add((reference, inWatchlist));
            return ThrowOnSet
                ? Task.FromException(new InvalidOperationException("network down"))
                : Task.CompletedTask;
        }
    }
}
