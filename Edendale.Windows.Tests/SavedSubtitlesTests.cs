using System.Globalization;
using System.Text.Json.Nodes;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Saved subtitles: downloads kept per title and removed after a month unused.
/// The store runs against a temporary folder and a fake clock.
/// </summary>
[TestClass]
public sealed class SavedSubtitlesTests
{
    private static readonly DateTimeOffset Start = new(2026, 10, 1, 12, 0, 0, TimeSpan.Zero);

    private string _root = "";
    private string _folder = "";
    private string _index = "";
    private DateTimeOffset _now;

    [TestInitialize]
    public void Init()
    {
        _root = Path.Combine(Path.GetTempPath(), "edendale-saved-" + Guid.NewGuid().ToString("N"));
        _folder = Path.Combine(_root, "Subtitles");
        Directory.CreateDirectory(_folder);
        _index = Path.Combine(_root, "saved-subtitles.json");
        _now = Start;
    }

    [TestCleanup]
    public void Cleanup()
    {
        if (Directory.Exists(_root)) Directory.Delete(_root, recursive: true);
    }

    private SavedSubtitleStore Store(PlayerSettingsStore? settings = null) =>
        new(_index, () => _folder, settings, () => _now);

    private static PlaybackRequest Movie(int id = 603) => new()
    {
        FilePath = @"C:\Films\Matrix.mkv",
        Title = "The Matrix",
        TmdbId = id,
        MediaType = "movie",
    };

    private static PlaybackRequest Episode(int season, int episode) => new()
    {
        FilePath = $@"C:\Shows\S{season:D2}E{episode:D2}.mkv",
        Title = "Episode",
        TmdbId = 9000 + episode,
        MediaType = "episode",
        ShowTmdbId = 1399,
        SeasonNumber = season,
        EpisodeNumber = episode,
    };

    private DownloadedSubtitle Download(string id, string language = "en", string? release = "Release.1080p")
    {
        var candidate = new SubtitleCandidate
        {
            Id = id,
            Url = "https://example.invalid/" + id,
            Language = language,
            Release = release,
        };
        var path = Path.Combine(_folder, SubtitleService.CacheFileName(candidate));
        File.WriteAllText(path, "1\n00:00:01,000 --> 00:00:02,000\nHello\n");
        return new DownloadedSubtitle { FilePath = path, Candidate = candidate };
    }

    private static SavedSubtitle Entry(string title, string file, DateTimeOffset lastUsed, bool selected = false) => new()
    {
        TitleKey = title,
        FileName = file,
        DownloadedAt = lastUsed,
        LastUsedAt = lastUsed,
        Selected = selected,
    };

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    [TestMethod]
    public void MoviesKeyByTmdbIdAndEpisodesByShowSeasonAndEpisode()
    {
        Assert.AreEqual("movie:603", SavedSubtitleRules.TitleKey("movie", 603, null, null, null));
        Assert.AreEqual("episode:1399:1:2", SavedSubtitleRules.TitleKey("episode", 9002, 1399, 1, 2));
        // Specials keep their season 0.
        Assert.AreEqual("episode:1399:0:1", SavedSubtitleRules.TitleKey("episode", 9001, 1399, 0, 1));
        // Without numbers, the episode's own id.
        Assert.AreEqual("episode:9002", SavedSubtitleRules.TitleKey("episode", 9002, 1399, null, null));
        Assert.IsNull(SavedSubtitleRules.TitleKey("movie", null, null, null, null));
        Assert.IsNull(SavedSubtitleRules.TitleKey("movie", 0, null, null, null));
        Assert.IsNull(SavedSubtitleRules.TitleKey("episode", null, null, 1, 2));
    }

    [TestMethod]
    public void ASubtitleExpiresThirtyDaysAfterItsLastUse()
    {
        var entry = Entry("movie:1", "a.srt", Start);
        var retention = SavedSubtitleRules.RetentionPeriod;
        Assert.AreEqual(TimeSpan.FromDays(30), retention);
        Assert.IsFalse(SavedSubtitleRules.IsExpired(entry, Start.AddDays(29.9), retention));
        Assert.IsTrue(SavedSubtitleRules.IsExpired(entry, Start.AddDays(30), retention));
        Assert.IsTrue(SavedSubtitleRules.IsExpired(entry, Start.AddDays(45), retention));
    }

    [TestMethod]
    public void OnlyBareFileNamesAreSafe()
    {
        Assert.IsTrue(SavedSubtitleRules.IsSafeFileName("12345.en.srt"));
        foreach (var name in new[] { "", " ", ".", "..", @"..\x.srt", "../x.srt", @"C:\x.srt", "a/b.srt", "a:b", "trailing.", " lead.srt", "x\0.srt" })
        {
            Assert.IsFalse(SavedSubtitleRules.IsSafeFileName(name), name);
        }
    }

    [TestMethod]
    public void SizesReadInKilobytesThenMegabytes()
    {
        var culture = CultureInfo.InvariantCulture;
        string Format(long bytes) => SavedSubtitleRules.FormatSize(bytes, "{0} KB", "{0} MB", culture);
        Assert.AreEqual("0 KB", Format(0));
        Assert.AreEqual("1 KB", Format(10));
        Assert.AreEqual("120 KB", Format(120 * 1024));
        Assert.AreEqual("1 MB", Format(1000 * 1024));
        Assert.AreEqual("1.4 MB", Format((long)(1.4 * 1024 * 1024)));
        Assert.AreEqual("1,4 Mo", SavedSubtitleRules.FormatSize((long)(1.4 * 1024 * 1024), "{0} Ko", "{0} Mo", new CultureInfo("fr-FR")));
    }

    [TestMethod]
    public void LabelsNameTheLanguageAndAddTheReleaseOnlyWhenNeeded()
    {
        var subtitle = Entry("movie:1", "a.srt", Start) with { Language = "pt", LanguageLabel = "Brazilian Portuguese", Release = "Film.2019.1080p" };
        Assert.AreEqual("Brazilian Portuguese · Saved", SavedSubtitleRules.Label(subtitle, "Saved", includeRelease: false));
        Assert.AreEqual("Brazilian Portuguese · Film.2019.1080p · Saved", SavedSubtitleRules.Label(subtitle, "Saved", includeRelease: true));
        // No language at all: the release names it.
        var bare = Entry("movie:1", "b.srt", Start) with { Release = "Film.2019" };
        Assert.AreEqual("Film.2019 · Saved", SavedSubtitleRules.Label(bare, "Saved", includeRelease: false));
    }

    // ------------------------------------------------------------------
    // Index
    // ------------------------------------------------------------------

    [TestMethod]
    public void UsingASubtitleSelectsItAndOnlyIt()
    {
        var index = new SavedSubtitleIndex([
            Entry("movie:1", "a.srt", Start, selected: true),
            Entry("movie:1", "b.srt", Start),
            Entry("movie:2", "c.srt", Start, selected: true),
        ]);
        Assert.IsTrue(index.MarkUsed("movie:1", "b.srt", Start.AddDays(3)));

        var title = index.ForTitle("movie:1");
        Assert.AreEqual("b.srt", title[0].FileName, "most recently used first");
        Assert.IsTrue(title[0].Selected);
        Assert.AreEqual(Start.AddDays(3), title[0].LastUsedAt);
        Assert.IsFalse(title[1].Selected);
        Assert.IsTrue(index.Find("movie:2", "c.srt")!.Selected, "other titles keep their own choice");
        Assert.IsFalse(index.MarkUsed("movie:1", "missing.srt", Start));
    }

    [TestMethod]
    public void ClearingASelectionLeavesTheUseTimesAlone()
    {
        var index = new SavedSubtitleIndex([Entry("movie:1", "a.srt", Start, selected: true)]);
        Assert.IsTrue(index.ClearSelection("movie:1"));
        Assert.IsFalse(index.ClearSelection("movie:1"));
        var entry = index.Find("movie:1", "a.srt")!;
        Assert.IsFalse(entry.Selected);
        Assert.AreEqual(Start, entry.LastUsedAt);
    }

    [TestMethod]
    public void RecordingAgainKeepsTheFirstDownloadTime()
    {
        var index = new SavedSubtitleIndex();
        index.Record(Entry("movie:1", "a.srt", Start, selected: true));
        index.Record(Entry("movie:1", "b.srt", Start.AddDays(1), selected: true));
        index.Record(Entry("movie:1", "a.srt", Start.AddDays(2), selected: true));

        Assert.AreEqual(2, index.Entries.Count);
        var a = index.Find("movie:1", "a.srt")!;
        Assert.AreEqual(Start, a.DownloadedAt);
        Assert.AreEqual(Start.AddDays(2), a.LastUsedAt);
        Assert.IsTrue(a.Selected);
        Assert.IsFalse(index.Find("movie:1", "b.srt")!.Selected);
        Assert.ThrowsException<ArgumentException>(() => index.Record(Entry("movie:1", @"..\escape.srt", Start)));
    }

    [TestMethod]
    public void PruneRemovesExpiredSubtitlesButNotOnesInUse()
    {
        var index = new SavedSubtitleIndex([
            Entry("movie:1", "old.srt", Start),
            Entry("movie:1", "recent.srt", Start.AddDays(20)),
            Entry("movie:2", "playing.srt", Start),
        ]);
        var removal = index.Prune(Start.AddDays(31), SavedSubtitleRules.RetentionPeriod,
            new HashSet<string>(["playing.srt"], StringComparer.OrdinalIgnoreCase));

        CollectionAssert.AreEqual(new[] { "old.srt" }, removal.FilesToDelete.ToArray());
        CollectionAssert.AreEquivalent(new[] { "recent.srt", "playing.srt" }, index.Entries.Select(entry => entry.FileName).ToArray());
    }

    [TestMethod]
    public void AFileSharedByTwoTitlesStaysUntilBothAreGone()
    {
        var index = new SavedSubtitleIndex([
            Entry("movie:1", "shared.srt", Start),
            Entry("movie:2", "shared.srt", Start.AddDays(20)),
        ]);
        var first = index.Prune(Start.AddDays(31), SavedSubtitleRules.RetentionPeriod);
        Assert.AreEqual(1, first.Removed.Count);
        Assert.AreEqual(0, first.FilesToDelete.Count);

        var second = index.Remove("movie:2", "shared.srt");
        CollectionAssert.AreEqual(new[] { "shared.srt" }, second.FilesToDelete.ToArray());
    }

    [TestMethod]
    public void OrphanFilesGoOnceTheyAreAMonthOld()
    {
        var index = new SavedSubtitleIndex([Entry("movie:1", "kept.srt", Start)]);
        var files = new[]
        {
            ("kept.srt", Start.AddDays(-90)),
            ("old-orphan.srt", Start.AddDays(-31)),
            ("new-orphan.srt", Start.AddDays(-2)),
            ("attached.srt", Start.AddDays(-60)),
        };
        var orphans = index.OrphanFiles(files, Start, SavedSubtitleRules.RetentionPeriod,
            new HashSet<string>(["attached.srt"], StringComparer.OrdinalIgnoreCase));
        CollectionAssert.AreEqual(new[] { "old-orphan.srt" }, orphans.ToArray());
    }

    [TestMethod]
    public void DamagedEntriesAreDropped()
    {
        var index = new SavedSubtitleIndex(
            [
                Entry("movie:1", "fine.srt", Start),
                Entry("movie:1", "fine.srt", Start.AddDays(1)),
                Entry("movie:1", @"..\..\Windows\win.ini", Start),
                Entry("", "untitled.srt", Start),
            ],
            ["pending.srt", @"C:\elsewhere.srt"]);
        Assert.AreEqual(1, index.Entries.Count);
        CollectionAssert.AreEqual(new[] { "pending.srt" }, index.PendingDeletes.ToArray());
    }

    // ------------------------------------------------------------------
    // Store
    // ------------------------------------------------------------------

    [TestMethod]
    public void ADownloadComesBackForTheSameTitleOnly()
    {
        var store = Store();
        var download = Download("101");
        store.Record(Movie(), download);

        var again = Store().ForTitle(Movie());
        Assert.AreEqual(1, again.Count, "the index survives a new launch");
        Assert.AreEqual(download.FilePath, again[0].FilePath);
        Assert.IsTrue(again[0].Subtitle.Selected, "the download was on, so it comes back on");
        Assert.AreEqual("101", again[0].Subtitle.CandidateId);
        Assert.AreEqual("Release.1080p", again[0].Subtitle.Release);
        Assert.AreEqual(new Uri(download.FilePath).AbsoluteUri, again[0].FileUri);

        Assert.AreEqual(0, store.ForTitle(Movie(604)).Count);
        Assert.IsTrue(store.IsSaved(Movie(), "101"));
        Assert.IsFalse(store.IsSaved(Movie(604), "101"));
    }

    [TestMethod]
    public void EachEpisodeKeepsItsOwnSubtitles()
    {
        var store = Store();
        store.Record(Episode(1, 1), Download("201"));
        store.Record(Episode(1, 2), Download("202"));

        Assert.AreEqual("201", store.ForTitle(Episode(1, 1)).Single().Subtitle.CandidateId);
        Assert.AreEqual("202", store.ForTitle(Episode(1, 2)).Single().Subtitle.CandidateId);
        Assert.AreEqual(0, store.ForTitle(Episode(2, 1)).Count);
    }

    [TestMethod]
    public void FilesOutsideTheFolderAreNeverSaved()
    {
        var store = Store();
        var outside = Path.Combine(_root, "beside-the-video.srt");
        File.WriteAllText(outside, "x");
        store.Record(Movie(), new DownloadedSubtitle
        {
            FilePath = outside,
            Candidate = new SubtitleCandidate { Id = "x", Url = "https://example.invalid/x", Language = "en" },
        });
        Assert.AreEqual(0, store.ForTitle(Movie()).Count);
        Assert.IsNull(store.Find(Movie(), outside));
    }

    [TestMethod]
    public void AMissingFileIsForgotten()
    {
        var store = Store();
        var download = Download("301");
        store.Record(Movie(), download);
        File.Delete(download.FilePath);

        Assert.AreEqual(0, store.ForTitle(Movie()).Count);
        Assert.AreEqual(0, Store().ForTitle(Movie()).Count, "and the index forgets it too");
    }

    [TestMethod]
    public void UseIsTrackedByUriAndSelectionCanBeCleared()
    {
        var store = Store();
        var first = Download("401");
        var second = Download("402", "fr");
        store.Record(Movie(), first);
        store.Record(Movie(), second);

        _now = Start.AddDays(5);
        store.MarkUsed(Movie(), new Uri(first.FilePath).AbsoluteUri);
        var saved = store.ForTitle(Movie());
        Assert.AreEqual(first.FilePath, saved[0].FilePath);
        Assert.IsTrue(saved[0].Subtitle.Selected);
        Assert.AreEqual(Start.AddDays(5), saved[0].Subtitle.LastUsedAt);
        Assert.IsFalse(saved[1].Subtitle.Selected);

        store.ClearSelection(Movie());
        Assert.IsTrue(Store().ForTitle(Movie()).All(file => !file.Subtitle.Selected));
    }

    [TestMethod]
    public void PruneDeletesSubtitlesUnusedForAMonth()
    {
        var store = Store();
        var stale = Download("501");
        var fresh = Download("502");
        store.Record(Movie(1), stale);
        _now = Start.AddDays(20);
        store.Record(Movie(2), fresh);

        _now = Start.AddDays(30);
        Assert.AreEqual(1, store.Prune());
        Assert.IsFalse(File.Exists(stale.FilePath));
        Assert.IsTrue(File.Exists(fresh.FilePath));
        Assert.AreEqual(0, store.ForTitle(Movie(1)).Count);
        Assert.AreEqual(1, store.ForTitle(Movie(2)).Count);
    }

    [TestMethod]
    public void AnExpiredSubtitleIsNotAttachedBeforeThePruneRuns()
    {
        var settings = new PlayerSettingsStore(Path.Combine(_root, "player-settings.json"));
        var store = Store(settings);
        var download = Download("550");
        store.Record(Movie(), download);

        _now = Start.AddDays(31);
        Assert.AreEqual(0, store.ForTitle(Movie()).Count);
        Assert.IsTrue(File.Exists(download.FilePath), "only the prune deletes");

        store.RemoveUnusedEnabled = false;
        Assert.AreEqual(1, store.ForTitle(Movie()).Count, "kept while the switch is off");
    }

    [TestMethod]
    public void UsingASubtitleRestartsItsMonth()
    {
        var store = Store();
        var download = Download("601");
        store.Record(Movie(), download);

        _now = Start.AddDays(25);
        store.MarkUsed(Movie(), download.FilePath);
        _now = Start.AddDays(40);
        Assert.AreEqual(0, store.Prune());
        Assert.IsTrue(File.Exists(download.FilePath));

        _now = Start.AddDays(55);
        Assert.AreEqual(1, store.Prune());
        Assert.IsFalse(File.Exists(download.FilePath));
    }

    [TestMethod]
    public void PruneSparesFilesAttachedToThePlaybackOnScreen()
    {
        var store = Store();
        var download = Download("701");
        store.Record(Movie(), download);
        _now = Start.AddDays(60);

        Assert.AreEqual(0, store.Prune([new Uri(download.FilePath).AbsoluteUri]));
        Assert.IsTrue(File.Exists(download.FilePath));
        Assert.AreEqual(1, store.Prune());
    }

    [TestMethod]
    public void TurningTheSwitchOffKeepsEverything()
    {
        var settings = new PlayerSettingsStore(Path.Combine(_root, "player-settings.json"));
        var store = Store(settings);
        Assert.IsTrue(store.RemoveUnusedEnabled, "on by default");
        var download = Download("801");
        store.Record(Movie(), download);
        store.RemoveUnusedEnabled = false;
        Assert.IsFalse(settings.GetBool(SavedSubtitleStore.RemoveUnusedKey, fallback: true));

        _now = Start.AddDays(90);
        Assert.AreEqual(0, store.Prune());
        Assert.IsTrue(File.Exists(download.FilePath));
    }

    [TestMethod]
    public void OldUnindexedFilesArePrunedAndNewOnesKept()
    {
        var store = Store();
        var old = Path.Combine(_folder, "legacy.en.srt");
        var recent = Path.Combine(_folder, "recent.en.srt");
        File.WriteAllText(old, "x");
        File.WriteAllText(recent, "x");
        File.SetLastWriteTimeUtc(old, Start.AddDays(-45).UtcDateTime);
        File.SetLastWriteTimeUtc(recent, Start.AddDays(-1).UtcDateTime);

        Assert.AreEqual(1, store.Prune());
        Assert.IsFalse(File.Exists(old));
        Assert.IsTrue(File.Exists(recent));
    }

    [TestMethod]
    public void RemoveAllEmptiesTheFolderAndTheSummary()
    {
        var store = Store();
        store.Record(Movie(1), Download("901"));
        store.Record(Movie(2), Download("902"));
        File.WriteAllText(Path.Combine(_folder, "unindexed.srt"), "x");

        var (count, bytes) = store.Summary();
        Assert.AreEqual(3, count);
        Assert.IsTrue(bytes > 0);

        var changes = 0;
        store.Changed += (_, _) => changes++;
        store.RemoveAll();
        Assert.AreEqual(1, changes);
        Assert.AreEqual((0, 0L), store.Summary());
        Assert.AreEqual(0, Directory.GetFiles(_folder).Length);
        Assert.AreEqual(0, Store().ForTitle(Movie(1)).Count);
    }

    [TestMethod]
    public void AnUnreadableIndexStartsEmptyAndADamagedEntryCostsOnlyItself()
    {
        File.WriteAllText(_index, "{ not json");
        Assert.AreEqual(0, Store().ForTitle(Movie()).Count);

        var download = Download("1001");
        var root = new JsonObject
        {
            ["version"] = 1,
            ["subtitles"] = new JsonArray
            {
                new JsonObject { ["file"] = "no-title.srt" },
                new JsonObject
                {
                    ["title"] = "movie:603",
                    ["file"] = Path.GetFileName(download.FilePath),
                    ["lastUsedAt"] = Start.ToString("O"),
                    ["selected"] = true,
                },
            },
        };
        File.WriteAllText(_index, root.ToJsonString());
        var saved = Store().ForTitle(Movie());
        Assert.AreEqual(1, saved.Count);
        Assert.IsTrue(saved[0].Subtitle.Selected);
    }

    [TestMethod]
    public void CacheNamesAreStableWithoutAnId()
    {
        var candidate = new SubtitleCandidate { Id = "", Url = "https://example.invalid/sub.srt", Language = "en" };
        var name = SubtitleService.CacheFileName(candidate);
        Assert.AreEqual(name, SubtitleService.CacheFileName(candidate with { }));
        StringAssert.EndsWith(name, ".en.srt");
        Assert.IsTrue(SavedSubtitleRules.IsSafeFileName(name));
    }
}
