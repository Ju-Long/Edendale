using Edendale.Windows.Core;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// The Watchlist and Downloaded section rows, the sidebar's fallback when a
/// section empties, and the season heading scrubber's geometry (DIFF.md
/// §3.15), ported from Apple's LibrarySectionsTests.
/// </summary>
[TestClass]
public sealed class LibrarySectionsTests
{
    [TestMethod]
    public void DownloadedSectionsListOnlyWhatHasContent()
    {
        Assert.AreEqual(0, LibrarySections.AvailableDownloaded(false, 0, 0).Count);
        CollectionAssert.AreEqual(
            new[] { DownloadedSection.ContinueWatching, DownloadedSection.Movies, DownloadedSection.Shows },
            LibrarySections.AvailableDownloaded(true, 1, 1).ToArray());
        CollectionAssert.AreEqual(new[] { DownloadedSection.Shows }, LibrarySections.AvailableDownloaded(false, 0, 1).ToArray());
    }

    [TestMethod]
    public void WatchlistSectionsFollowMediaTypes()
    {
        Assert.AreEqual(0, LibrarySections.AvailableWatchlist([]).Count);
        CollectionAssert.AreEqual(new[] { WatchlistSection.Movies }, LibrarySections.AvailableWatchlist(["movie"]).ToArray());
        CollectionAssert.AreEqual(
            new[] { WatchlistSection.Movies, WatchlistSection.Shows },
            LibrarySections.AvailableWatchlist(["tv", "movie"]).ToArray());
    }

    [TestMethod]
    public void SidebarTagsRoundTrip()
    {
        Assert.AreEqual(new SidebarItem("downloaded"), SidebarItem.Parse("downloaded"));
        Assert.AreEqual(SidebarItem.For(DownloadedSection.Movies), SidebarItem.Parse("downloaded:movies"));
        Assert.AreEqual(DownloadedSection.ContinueWatching, SidebarItem.Parse("downloaded:continue").DownloadedSection);
        Assert.AreEqual(WatchlistSection.Shows, SidebarItem.Parse("watchlist:shows").WatchlistSection);
        Assert.IsNull(SidebarItem.Parse("watchlist:shows").DownloadedSection);
        Assert.AreEqual("watchlist:movies", SidebarItem.For(WatchlistSection.Movies).NavTag);
        Assert.AreEqual("search", new SidebarItem("search").NavTag);
    }

    [TestMethod]
    public void EmptiedSectionRowFallsBackToItsPage()
    {
        var continueWatching = SidebarItem.For(DownloadedSection.ContinueWatching);
        Assert.AreEqual(new SidebarItem("downloaded"), continueWatching.Resolved([], [DownloadedSection.Movies]));
        Assert.AreEqual(continueWatching, continueWatching.Resolved([], [DownloadedSection.ContinueWatching]));
        Assert.AreEqual(new SidebarItem("watchlist"), SidebarItem.For(WatchlistSection.Shows).Resolved([WatchlistSection.Movies], []));
        Assert.AreEqual(new SidebarItem("search"), new SidebarItem("search").Resolved([], []));
        Assert.AreEqual(new SidebarItem("downloaded"), new SidebarItem("downloaded").Resolved([], []));
    }

    [TestMethod]
    public void ShelfMetricsMirrorScrollGeometry()
    {
        var metrics = new ShelfScrollMetrics(Offset: 250, ContentWidth: 2000, ViewportWidth: 1000);
        Assert.AreEqual(1000, metrics.Range);
        Assert.AreEqual(0.25, metrics.Progress);
        Assert.AreEqual(0.5, metrics.VisibleFraction);
        Assert.IsTrue(metrics.IsScrollable);
        Assert.AreEqual(500, metrics.OffsetFor(0.5));
        Assert.AreEqual(1000, metrics.OffsetFor(3));
        Assert.AreEqual(0.5, metrics.Step);

        // The thumb's centre follows the pointer; a 400 px rule with a 200 px thumb has 200 px of travel.
        Assert.AreEqual(200, metrics.ThumbWidth(400));
        Assert.AreEqual(0.5, ShelfScrollMetrics.FractionAt(200, 400, 200));
        Assert.AreEqual(0, ShelfScrollMetrics.FractionAt(10, 400, 200));
        Assert.AreEqual(1, ShelfScrollMetrics.FractionAt(390, 400, 200));
        // A long shelf still gets a thumb you can grab.
        Assert.AreEqual(28, new ShelfScrollMetrics(0, 100_000, 1000).ThumbWidth(400));
        Assert.AreEqual(0.1, new ShelfScrollMetrics(0, 100_000, 1000).Step);
    }

    [TestMethod]
    public void FittingShelfHasNothingToScrub()
    {
        var metrics = new ShelfScrollMetrics(Offset: 0, ContentWidth: 800, ViewportWidth: 1000);
        Assert.AreEqual(0, metrics.Progress);
        Assert.IsFalse(metrics.IsScrollable);
        Assert.AreEqual(0, metrics.OffsetFor(0.7));
        Assert.IsFalse(default(ShelfScrollMetrics).IsScrollable);
    }

    // ------------------------------------------------------------------
    // The Downloaded page's section picker (apple-27.1 94a8b4f)
    // ------------------------------------------------------------------

    /// <summary>Ports tabBarSectionPickerOffersTheWholePageThenEachSection (without iOS's short titles).</summary>
    [TestMethod]
    public void SectionPickerOffersTheWholePageThenEachSection()
    {
        DownloadedSection[] all = [DownloadedSection.ContinueWatching, DownloadedSection.Movies, DownloadedSection.Shows];
        CollectionAssert.AreEqual(
            new DownloadedSection?[] { null, DownloadedSection.ContinueWatching, DownloadedSection.Movies, DownloadedSection.Shows },
            LibrarySections.PickerChoices(all).ToArray());
        CollectionAssert.AreEqual(new DownloadedSection?[] { null, DownloadedSection.Shows }, LibrarySections.PickerChoices([DownloadedSection.Shows]).ToArray());

        Assert.IsTrue(LibrarySections.IsPickerOffered(all));
        Assert.IsTrue(LibrarySections.IsPickerOffered([DownloadedSection.Movies, DownloadedSection.Shows]));
        Assert.IsFalse(LibrarySections.IsPickerOffered([DownloadedSection.Movies]), "one section lists what the whole page does");
        Assert.IsFalse(LibrarySections.IsPickerOffered([]));

        Assert.AreEqual("All", ((DownloadedSection?)null).PickerTitle());
        Assert.AreEqual("Continue Watching", ((DownloadedSection?)DownloadedSection.ContinueWatching).PickerTitle());
        Assert.AreEqual("Movies", ((DownloadedSection?)DownloadedSection.Movies).PickerTitle());
        Assert.AreEqual("TV Shows", ((DownloadedSection?)DownloadedSection.Shows).PickerTitle());
    }

    [TestMethod]
    public void APickedSectionThatEmptiesFallsBackToTheWholePage()
    {
        DownloadedSection[] available = [DownloadedSection.Movies, DownloadedSection.Shows];
        Assert.AreEqual(DownloadedSection.Movies, LibrarySections.Resolved(DownloadedSection.Movies, available));
        Assert.IsNull(LibrarySections.Resolved(DownloadedSection.ContinueWatching, available));
        Assert.IsNull(LibrarySections.Resolved(null, available));
    }

    [TestMethod]
    public void PickerChoicesSelectTheirSidebarRows()
    {
        Assert.AreEqual(new SidebarItem("downloaded"), SidebarItem.ForDownloaded(null));
        Assert.AreEqual("downloaded", SidebarItem.ForDownloaded(null).NavTag);
        foreach (var section in new[] { DownloadedSection.ContinueWatching, DownloadedSection.Movies, DownloadedSection.Shows })
        {
            var row = SidebarItem.ForDownloaded(section);
            Assert.AreEqual("downloaded:" + section.Tag(), row.NavTag);
            Assert.AreEqual(section, row.DownloadedSection);
        }
    }
}
