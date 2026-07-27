using Edendale.Windows.Core;
using Edendale.Windows.Models;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Deterministic tests for the Windows-owned parser, progress rules, release
/// calendar, and replica merges. They make no network calls.
/// </summary>
[TestClass]
public sealed class WindowsCoreTests
{
    // ------------------------------------------------------------------
    // Classification (the import fast path — no network by design)
    // ------------------------------------------------------------------

    [TestMethod]
    public void ParseMediaFile_ClassifiesAMovie()
    {
        var parsed = WindowsCore.ParseMediaFile("The.Matrix.1999.1080p.BluRay.x264.mkv");

        Assert.IsFalse(parsed.IsEpisode);
        Assert.AreEqual("The Matrix", parsed.Title);
        Assert.AreEqual(1999, parsed.Year);
    }

    [TestMethod]
    public void ParseMediaFile_ClassifiesAnEpisode()
    {
        var parsed = WindowsCore.ParseMediaFile("Severance.S02E05.1080p.WEB-DL.mkv");

        Assert.IsTrue(parsed.IsEpisode);
        Assert.AreEqual("Severance", parsed.ShowName);
        Assert.AreEqual(2, parsed.Season);
        Assert.AreEqual(5, parsed.Episode);
    }

    // ------------------------------------------------------------------
    // Watch progress
    // ------------------------------------------------------------------

    [TestMethod]
    public void NormalizeProgress_ClampsToTheSharedDomain()
    {
        Assert.AreEqual(0.0, WindowsCore.NormalizeProgress(-1.5), 1e-9);
        Assert.AreEqual(0.5, WindowsCore.NormalizeProgress(0.5), 1e-9);
        Assert.AreEqual(1.0, WindowsCore.NormalizeProgress(4.2), 1e-9);
    }

    [TestMethod]
    public void ProgressStorageKey_MatchesTheKeyEveryPlatformWrites()
    {
        Assert.AreEqual("movie:603", WindowsCore.ProgressStorageKey(603, "movie"));
        Assert.AreEqual("episode:62085", WindowsCore.ProgressStorageKey(62085, "episode"));
    }

    [TestMethod]
    public void MergeWatchProgress_KeepsTheNewerWrite()
    {
        var local = new WatchProgress
        {
            TmdbId = 603,
            MediaType = "movie",
            Position = 0.2,
            LastWatchedEpochMillis = 100,
        };
        var replica = new WatchProgress
        {
            TmdbId = 603,
            MediaType = "movie",
            Position = 0.8,
            LastWatchedEpochMillis = 500,
        };

        var merged = WindowsCore.MergeWatchProgress([local], [replica]).Single();

        Assert.AreEqual(0.8, merged.Position, 1e-9);
        Assert.AreEqual(500, merged.LastWatchedEpochMillis);
    }

    [TestMethod]
    public void MergeWatchProgress_UnionsRecordsFromBothDevices()
    {
        var here = new WatchProgress { TmdbId = 603, MediaType = "movie", Position = 0.3 };
        var there = new WatchProgress { TmdbId = 11, MediaType = "movie", Position = 0.6 };

        var merged = WindowsCore.MergeWatchProgress([here], [there]);

        CollectionAssert.AreEquivalent(
            new[] { "movie:603", "movie:11" },
            merged.Select(record => record.StorageKey).ToArray());
    }

    // ------------------------------------------------------------------
    // User media
    // ------------------------------------------------------------------

    [TestMethod]
    public void MergeUserMedia_ResolvesFieldByField()
    {
        // The favourite was set here later; the watchlist was set on the other
        // device later. A field-wise merge keeps one of each.
        var here = new UserMediaRecord
        {
            TmdbId = 603,
            MediaType = "movie",
            Favourite = true,
            FavouriteUpdatedAt = 900,
            Watchlist = false,
            WatchlistUpdatedAt = 100,
        };
        var there = new UserMediaRecord
        {
            TmdbId = 603,
            MediaType = "movie",
            Favourite = false,
            FavouriteUpdatedAt = 200,
            Watchlist = true,
            WatchlistUpdatedAt = 800,
        };

        var merged = WindowsCore.MergeUserMedia([here], [there]).Single();

        Assert.IsTrue(merged.Favourite, "the newer favourite write should win");
        Assert.IsTrue(merged.Watchlist, "the newer watchlist write should win");
        Assert.AreEqual("movie:603", merged.StorageKey);
    }

    // ------------------------------------------------------------------
    // Release heatmap
    // ------------------------------------------------------------------

    [TestMethod]
    public void ReleaseYearGrid_IsSevenSlotsPerWeekColumn()
    {
        var grid = WindowsCore.ReleaseYearGrid(2026);

        Assert.AreEqual(2026, grid.Year);
        Assert.IsTrue(grid.Columns.Count is >= 52 and <= 54, $"unexpected column count {grid.Columns.Count}");
        foreach (var column in grid.Columns) Assert.AreEqual(7, column.Slots.Count);
    }

    [TestMethod]
    public void ReleaseYearGrid_PadsTheLeadingBlanksBeforeJanuaryFirst()
    {
        // 2026-01-01 is a Thursday, so slots 0–2 (Sun–Wed) of the first column
        // are blank and the month label sits on that column.
        var first = WindowsCore.ReleaseYearGrid(2026).Columns[0];

        Assert.AreEqual("Jan", first.MonthLabel);
        Assert.IsNull(first.Slots[0]);
        Assert.IsNull(first.Slots[3]);
        Assert.IsNotNull(first.Slots[4]);
        Assert.AreEqual("2026-01-01", first.Slots[4]!.DateKey);
        Assert.IsTrue(first.Slots[4]!.IsFirstOfMonth);
    }

    [TestMethod]
    public void ReleaseYearGrid_CoversEveryDayIncludingLeapDays()
    {
        var days = WindowsCore.ReleaseYearGrid(2024).Columns
            .SelectMany(column => column.Slots)
            .Where(slot => slot is not null)
            .Select(slot => slot!.DateKey)
            .ToList();

        Assert.AreEqual(366, days.Count);
        Assert.AreEqual(366, days.Distinct().Count());
        Assert.IsTrue(days.Contains("2024-02-29"), "the leap day should have a cell");
        Assert.AreEqual("2024-01-01", days.First());
        Assert.AreEqual("2024-12-31", days.Last());
    }

    [TestMethod]
    public void SelectionSummary_MatchesTheWordingEveryPlatformShows()
    {
        Assert.AreEqual("12 Mar 2026 · 1 day", WindowsCore.SelectionSummary("2026-03-12", "2026-03-12"));
        Assert.AreEqual("12 – 18 Mar 2026 · 7 days", WindowsCore.SelectionSummary("2026-03-12", "2026-03-18"));
        Assert.AreEqual("28 Feb – 2 Mar 2026 · 3 days", WindowsCore.SelectionSummary("2026-02-28", "2026-03-02"));
        Assert.AreEqual("20 Dec 2025 – 4 Jan 2026 · 16 days", WindowsCore.SelectionSummary("2025-12-20", "2026-01-04"));
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    [TestMethod]
    public void AFailureEnvelopeSurfacesAsAnException()
    {
        // Only "movie" and "episode" are valid watch media types.
        var failure = Assert.ThrowsException<WindowsCoreException>(
            () => WindowsCore.ProgressStorageKey(603, "sasquatch"));
        StringAssert.Contains(failure.Message, "sasquatch");
    }
}
