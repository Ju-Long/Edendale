using Edendale.Windows.Core;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Next-episode order, the Up Next window, Continue Watching next-up, and
/// the auto-advance transitions. Ports EpisodeProgressionTests,
/// UpcomingEpisodePreviewTests, ContinueWatchingTests, and the request-order
/// cases of PlayerSessionTransitionTests.
/// </summary>
[TestClass]
public sealed class EpisodeProgressionTests
{
    private static LibraryShow Show(string name = "Test Show", int? tmdbId = null) => new() { Name = name, TmdbId = tmdbId };

    private static LibraryEpisode Episode(int season, int episode, LibraryShow show, int? tmdbId = null, string? title = null)
    {
        var record = new LibraryEpisode
        {
            Season = season,
            Episode = episode,
            TmdbId = tmdbId,
            Title = title,
            FilePath = $@"C:\videos\{show.Name}\S{season}E{episode}-{Guid.NewGuid():N}.mkv",
        };
        show.Episodes.Add(record);
        return record;
    }

    // ------------------------------------------------------------------
    // Next episode
    // ------------------------------------------------------------------

    [TestMethod]
    public void NextEpisodeWithinSameSeason()
    {
        var show = Show();
        var first = Episode(1, 1, show);
        var second = Episode(1, 2, show);
        Episode(1, 3, show);
        Assert.AreEqual(second.Id, EpisodeProgression.NextEpisode(first, show)?.Id);
    }

    [TestMethod]
    public void NextEpisodeCrossesSeasonBoundary()
    {
        var show = Show();
        var s1e3 = Episode(1, 3, show);
        var s2e1 = Episode(2, 1, show);
        Episode(1, 1, show);
        Assert.AreEqual(s2e1.Id, EpisodeProgression.NextEpisode(s1e3, show)?.Id);
    }

    [TestMethod]
    public void LastAndOnlyEpisodesHaveNoSuccessor()
    {
        var show = Show();
        Episode(1, 1, show);
        var last = Episode(1, 2, show);
        Assert.IsNull(EpisodeProgression.NextEpisode(last, show));

        var single = Show("Single");
        var only = Episode(1, 1, single);
        Assert.IsNull(EpisodeProgression.NextEpisode(only, single));
    }

    [TestMethod]
    public void OrderingBySeasonAndEpisodeNotInsertion()
    {
        var show = Show();
        var s2e1 = Episode(2, 1, show);
        var s1e1 = Episode(1, 1, show);
        var s1e2 = Episode(1, 2, show);
        Assert.AreEqual(s1e2.Id, EpisodeProgression.NextEpisode(s1e1, show)?.Id);
        Assert.AreEqual(s2e1.Id, EpisodeProgression.NextEpisode(s1e2, show)?.Id);
        Assert.IsNull(EpisodeProgression.NextEpisode(s2e1, show));
    }

    [TestMethod]
    public void UnknownOrForeignEpisodeReturnsNull()
    {
        var show = Show("Show A");
        Episode(1, 1, show);
        Episode(1, 2, show);
        var orphan = new LibraryEpisode { Season = 1, Episode = 99, FilePath = @"C:\orphan.mkv" };
        Assert.IsNull(EpisodeProgression.NextEpisode(orphan, show));

        var other = Show("Show B");
        var foreign = Episode(1, 1, other);
        Assert.IsNull(EpisodeProgression.NextEpisode(foreign, show));
    }

    [TestMethod]
    public void ProgressionSkipsGapsInSeasonNumbers()
    {
        var show = Show();
        var s1e1 = Episode(1, 1, show);
        var s3e1 = Episode(3, 1, show);
        Assert.AreEqual(s3e1.Id, EpisodeProgression.NextEpisode(s1e1, show)?.Id);
    }

    [TestMethod]
    public void MainSeasonNeverRegressesToSeasonZero()
    {
        var show = Show();
        var s1e2 = Episode(1, 2, show);
        Episode(0, 1, show, title: "Behind the Scenes");
        Episode(0, 2, show, title: "Bloopers");
        Assert.IsNull(EpisodeProgression.NextEpisode(s1e2, show));
    }

    [TestMethod]
    public void SpecialsAdvanceAmongThemselvesThenIntoSeasonOne()
    {
        var show = Show();
        var s0e1 = Episode(0, 1, show, title: "OVA 1");
        var s0e2 = Episode(0, 2, show, title: "OVA 2");
        var s1e1 = Episode(1, 1, show);
        Assert.AreEqual(s0e2.Id, EpisodeProgression.NextEpisode(s0e1, show)?.Id);
        Assert.AreEqual(s1e1.Id, EpisodeProgression.NextEpisode(s0e2, show)?.Id);
    }

    [TestMethod]
    public void MainSeasonSkipsOverSeasonZero()
    {
        var show = Show();
        var s1e1 = Episode(1, 1, show);
        Episode(0, 1, show, title: "OVA");
        var s1e2 = Episode(1, 2, show);
        Assert.AreEqual(s1e2.Id, EpisodeProgression.NextEpisode(s1e1, show)?.Id);
    }

    [TestMethod]
    public void DuplicateEncodesDoNotRepeatTheSameEpisode()
    {
        var show = Show();
        Episode(1, 1, show, title: "S1E1 720p");
        var duplicate = Episode(1, 1, show, title: "S1E1 1080p");
        var s1e2 = Episode(1, 2, show);
        Assert.AreEqual(s1e2.Id, EpisodeProgression.NextEpisode(duplicate, show)?.Id);

        var finale = Show("Finale");
        Episode(2, 5, finale, title: "Finale 720p");
        var finaleDuplicate = Episode(2, 5, finale, title: "Finale 1080p");
        Episode(1, 1, finale);
        Assert.IsNull(EpisodeProgression.NextEpisode(finaleDuplicate, finale));
    }

    [TestMethod]
    public void NeverAdvancesBackwards()
    {
        var show = Show();
        Episode(1, 1, show);
        var s2e3 = Episode(2, 3, show);
        Episode(2, 1, show);
        Assert.IsNull(EpisodeProgression.NextEpisode(s2e3, show));
    }

    [TestMethod]
    public void ComplexLibraryProgressesCorrectly()
    {
        var show = Show("Anime");
        Episode(0, 1, show, title: "OVA 1");
        var s1e1 = Episode(1, 1, show);
        Episode(1, 1, show, title: "S1E1 alt");
        var s1e2 = Episode(1, 2, show);
        Episode(0, 2, show, title: "OVA 2");
        var s1e3 = Episode(1, 3, show);
        var s2e1 = Episode(2, 1, show);
        Episode(0, 3, show, title: "Recap");
        Assert.AreEqual(s1e2.Id, EpisodeProgression.NextEpisode(s1e1, show)?.Id);
        Assert.AreEqual(s1e3.Id, EpisodeProgression.NextEpisode(s1e2, show)?.Id);
        Assert.AreEqual(s2e1.Id, EpisodeProgression.NextEpisode(s1e3, show)?.Id);
        Assert.IsNull(EpisodeProgression.NextEpisode(s2e1, show));
    }

    [TestMethod]
    public void PreviousEpisodeMirrorsNextWithoutReturningToSpecials()
    {
        var show = Show();
        var s0e1 = Episode(0, 1, show, title: "OVA");
        var s0e2 = Episode(0, 2, show, title: "OVA 2");
        var s1e1 = Episode(1, 1, show);
        Episode(1, 1, show, title: "S1E1 alt");
        var s1e2 = Episode(1, 2, show);
        var s2e1 = Episode(2, 1, show);

        Assert.AreEqual(s1e2.Id, EpisodeProgression.PreviousEpisode(s2e1, show)?.Id);
        Assert.AreEqual(1, EpisodeProgression.PreviousEpisode(s1e2, show)?.Episode);
        Assert.IsNull(EpisodeProgression.PreviousEpisode(s1e1, show), "a main season never steps back into specials");
        Assert.AreEqual(s0e1.Id, EpisodeProgression.PreviousEpisode(s0e2, show)?.Id);
        Assert.IsNull(EpisodeProgression.PreviousEpisode(s0e1, show));
        Assert.IsNull(EpisodeProgression.PreviousEpisode(new LibraryEpisode { Season = 1, Episode = 1 }, show));
    }

    [TestMethod]
    public void CreditsStartIsNotANaturalEnd()
    {
        Assert.IsTrue(PlayerLogic.IsNaturalEnd(TimeSpan.FromSeconds(2350), TimeSpan.FromSeconds(2400)));
        Assert.IsFalse(PlayerLogic.IsNaturalEnd(TimeSpan.FromSeconds(1140), TimeSpan.FromSeconds(1320)));
        Assert.IsFalse(PlayerLogic.IsNaturalEnd(TimeSpan.FromSeconds(2220), TimeSpan.FromSeconds(2400)));
        Assert.IsTrue(PlayerLogic.IsNaturalEnd(TimeSpan.FromSeconds(2400), TimeSpan.FromSeconds(2400)));
    }

    // ------------------------------------------------------------------
    // Up Next window
    // ------------------------------------------------------------------

    private sealed class UpNextFixture
    {
        public LibraryShow Show { get; } = Show("Upcoming", 77);
        public LibraryEpisode First { get; }
        public LibraryEpisode Second { get; }
        public LibraryEpisode Last { get; }

        public UpNextFixture()
        {
            First = Episode(1, 1, Show);
            Second = Episode(1, 2, Show);
            Last = Episode(1, 3, Show);
        }
    }

    private static LibraryEpisode? Upcoming(double time, double? duration, bool loop, LibraryEpisode? episode, LibraryShow? show) =>
        EpisodeProgression.UpcomingEpisode(
            TimeSpan.FromSeconds(time),
            duration is double seconds ? TimeSpan.FromSeconds(seconds) : null,
            loop,
            episode,
            show);

    [TestMethod]
    public void UpNextAppearsWithinThirtySecondsOfTheEnd()
    {
        var f = new UpNextFixture();
        Assert.AreEqual(f.Second.Id, Upcoming(272, 300, false, f.First, f.Show)?.Id);
        Assert.IsNull(Upcoming(269, 300, false, f.First, f.Show));
    }

    [TestMethod]
    public void UpNextClearsWhenSeekingBackAndReturns()
    {
        var f = new UpNextFixture();
        Assert.AreEqual(f.Second.Id, Upcoming(275, 300, false, f.First, f.Show)?.Id);
        Assert.IsNull(Upcoming(200, 300, false, f.First, f.Show));
        Assert.AreEqual(f.Second.Id, Upcoming(280, 300, false, f.First, f.Show)?.Id);
    }

    [TestMethod]
    public void UpNextIsSuppressedWithLoopForMoviesAndWithoutASuccessor()
    {
        var f = new UpNextFixture();
        Assert.IsNull(Upcoming(280, 300, true, f.First, f.Show));
        Assert.AreEqual(f.Second.Id, Upcoming(280, 300, false, f.First, f.Show)?.Id);
        Assert.IsNull(Upcoming(280, 300, false, null, null));
        Assert.IsNull(Upcoming(280, 300, false, f.Last, f.Show));
    }

    [TestMethod]
    public void UpNextNeedsAKnownDuration()
    {
        var f = new UpNextFixture();
        Assert.IsNull(Upcoming(280, null, false, f.First, f.Show));
        Assert.IsNull(Upcoming(0, 0, false, f.First, f.Show));
    }

    [TestMethod]
    public void UpNextBoundaries()
    {
        var f = new UpNextFixture();
        Assert.AreEqual(f.Second.Id, Upcoming(270, 300, false, f.First, f.Show)?.Id);
        Assert.IsNull(Upcoming(269.9, 300, false, f.First, f.Show));
        Assert.IsNull(Upcoming(300, 300, false, f.First, f.Show));
    }

    [TestMethod]
    public void ShortEpisodesStillShowUpNext()
    {
        var f = new UpNextFixture();
        Assert.AreEqual(f.Second.Id, Upcoming(35, 60, false, f.First, f.Show)?.Id);
    }

    // ------------------------------------------------------------------
    // Continue Watching next-up
    // ------------------------------------------------------------------

    private static WatchProgress Completed(int tmdbId, int showTmdbId, int season, int episode, long at = 1_000) => new()
    {
        TmdbId = tmdbId,
        MediaType = "episode",
        Position = 1,
        IsCompleted = true,
        ShowTmdbId = showTmdbId,
        SeasonNumber = season,
        EpisodeNumber = episode,
        LastWatchedEpochMillis = at,
    };

    private static WatchProgress InProgress(int tmdbId, int showTmdbId, int season, int episode) => new()
    {
        TmdbId = tmdbId,
        MediaType = "episode",
        Position = 0.4,
        ShowTmdbId = showTmdbId,
        SeasonNumber = season,
        EpisodeNumber = episode,
    };

    private static List<NextUpEpisode> NextUp(IEnumerable<WatchProgress> progress, params LibraryShow[] shows) =>
        EpisodeProgression.NextUpEpisodes(
            progress,
            progress.Where(entry => !entry.IsCompleted && entry.ShowTmdbId is not null)
                .Select(entry => entry.ShowTmdbId!.Value).ToHashSet(),
            shows);

    [TestMethod]
    public void HighestCompletedPerShowSelectsTheFurthest()
    {
        var result = EpisodeProgression.HighestCompletedPerShow(
        [
            Completed(101, 1, 1, 1),
            Completed(103, 1, 1, 3),
            Completed(102, 1, 1, 2),
            InProgress(104, 1, 1, 4),
            Completed(201, 2, 1, 1),
            new WatchProgress { TmdbId = 999, MediaType = "movie", IsCompleted = true, Position = 1 },
        ]);
        Assert.AreEqual(2, result.Count);
        Assert.AreEqual((1, 3), (result[1].Season, result[1].Episode));
        Assert.AreEqual((1, 1), (result[2].Season, result[2].Episode));
    }

    [TestMethod]
    public void CompletedEpisodeSurfacesNextUpAcrossSeasons()
    {
        var anime = Show("Anime", 1);
        Episode(1, 1, anime, 101);
        var second = Episode(1, 2, anime, 102);
        var result = NextUp([Completed(101, 1, 1, 1)], anime);
        Assert.AreEqual(1, result.Count);
        Assert.AreEqual(second.Id, result[0].Episode.Id);

        var drama = Show("Drama", 2);
        Episode(1, 3, drama, 201);
        var s2e1 = Episode(2, 1, drama, 202);
        Assert.AreEqual(s2e1.Id, NextUp([Completed(201, 2, 1, 3)], drama).Single().Episode.Id);
    }

    [TestMethod]
    public void NoNextUpAfterTheLastEpisodeOrWhileOneIsInProgress()
    {
        var finished = Show("Short", 3);
        Episode(1, 1, finished, 301);
        Assert.AreEqual(0, NextUp([Completed(301, 3, 1, 1)], finished).Count);

        var sitcom = Show("Sitcom", 4);
        Episode(1, 1, sitcom, 401);
        Episode(1, 2, sitcom, 402);
        Episode(1, 3, sitcom, 403);
        Assert.AreEqual(0, NextUp([Completed(401, 4, 1, 1), InProgress(402, 4, 1, 2)], sitcom).Count);
    }

    [TestMethod]
    public void ShowsWithoutATmdbIdAreExcluded()
    {
        var unknown = Show("Unknown");
        Episode(1, 1, unknown, 501);
        Episode(1, 2, unknown, 502);
        Assert.AreEqual(0, NextUp([Completed(501, 5, 1, 1)], unknown).Count);
    }

    [TestMethod]
    public void ShowsAdvanceIndependently()
    {
        var a = Show("Show A", 10);
        Episode(1, 1, a, 1001);
        var a2 = Episode(1, 2, a, 1002);
        var b = Show("Show B", 20);
        Episode(1, 1, b, 2001);
        var b2 = Episode(1, 2, b, 2002);
        var ids = NextUp([Completed(1001, 10, 1, 1), Completed(2001, 20, 1, 1)], a, b)
            .Select(entry => entry.Episode.Id).ToHashSet();
        Assert.AreEqual(2, ids.Count);
        Assert.IsTrue(ids.Contains(a2.Id) && ids.Contains(b2.Id));
    }

    [TestMethod]
    public void TheFurthestCompletedEpisodeDecides()
    {
        var show = Show("Long", 30);
        Episode(1, 1, show, 3001);
        Episode(1, 2, show, 3002);
        Episode(1, 3, show, 3003);
        var ep4 = Episode(1, 4, show, 3004);
        var result = NextUp([Completed(3001, 30, 1, 1), Completed(3002, 30, 1, 2), Completed(3003, 30, 1, 3)], show);
        Assert.AreEqual(ep4.Id, result.Single().Episode.Id);

        var done = Show("Finished", 40);
        Episode(1, 1, done, 4001);
        Episode(1, 2, done, 4002);
        Assert.AreEqual(0, NextUp([Completed(4001, 40, 1, 1), Completed(4002, 40, 1, 2)], done).Count);
    }

    [TestMethod]
    public void DeletedCompletedEpisodeStillFindsItsStoredSuccessor()
    {
        var show = Show("Pruned", 50);
        var successor = Episode(1, 2, show, 5002);
        Assert.AreEqual(successor.Id, NextUp([Completed(5001, 50, 1, 1)], show).Single().Episode.Id);
    }

    [TestMethod]
    public void DuplicateShowRecordsGiveOneCard()
    {
        var local = Show("Split", 60);
        Episode(1, 1, local, 6001);
        var nas = Show("Split", 60);
        var nasSecond = Episode(1, 2, nas, 6002);
        var localThird = Episode(1, 3, local, 6003);

        var result = NextUp([Completed(6001, 60, 1, 1)], local, nas);
        Assert.AreEqual(1, result.Count);
        Assert.AreEqual(nasSecond.Id, result[0].Episode.Id);
        Assert.AreSame(nas, result[0].Show);
        Assert.AreNotEqual(localThird.Id, result[0].Episode.Id);
    }

    [TestMethod]
    public void NextUpNeverWritesProgress()
    {
        var show = Show("Untouched", 70);
        Episode(1, 1, show, 7001);
        Episode(1, 2, show, 7002);
        var progress = new List<WatchProgress> { Completed(7001, 70, 1, 1) };
        var before = progress.Select(entry => (entry.TmdbId, entry.Position, entry.IsCompleted)).ToList();

        NextUp(progress, show);

        CollectionAssert.AreEqual(before, progress.Select(entry => (entry.TmdbId, entry.Position, entry.IsCompleted)).ToList());
    }

    [TestMethod]
    public void NextUpIsOrderedByMostRecentlyWatched()
    {
        var older = Show("Older", 80);
        Episode(1, 1, older, 8001);
        Episode(1, 2, older, 8002);
        var newer = Show("Newer", 90);
        Episode(1, 1, newer, 9001);
        Episode(1, 2, newer, 9002);
        var result = NextUp([Completed(8001, 80, 1, 1, at: 100), Completed(9001, 90, 1, 1, at: 200)], older, newer);
        Assert.AreEqual("Newer", result[0].Show.Name);
    }

    // ------------------------------------------------------------------
    // Transitions
    // ------------------------------------------------------------------

    [TestMethod]
    public void ManualSelectionWinsOverAQueuedAutomaticAdvance()
    {
        var transitions = new PlaybackTransitions();
        transitions.Present();
        var ticket = transitions.RequestAdvance();
        transitions.Present();
        Assert.IsFalse(transitions.Claim(ticket));
    }

    [TestMethod]
    public void EndingTheSessionCancelsAQueuedAdvance()
    {
        var transitions = new PlaybackTransitions();
        transitions.Present();
        var ticket = transitions.RequestAdvance();
        transitions.End();
        Assert.IsFalse(transitions.Claim(ticket));
    }

    [TestMethod]
    public void DuplicateAdvanceRequestsMoveOnOnlyOnce()
    {
        var transitions = new PlaybackTransitions();
        transitions.Present();
        var first = transitions.RequestAdvance();
        var second = transitions.RequestAdvance();
        Assert.IsTrue(transitions.Claim(first));
        Assert.IsFalse(transitions.Claim(second));
    }

    [TestMethod]
    public void ACompletedItemIsNeverRewrittenByALateProgressWrite()
    {
        var transitions = new PlaybackTransitions();
        transitions.Present();
        Assert.IsTrue(transitions.ShouldWriteProgress);
        transitions.MarkCurrentCompleted();
        Assert.IsFalse(transitions.ShouldWriteProgress);

        // The next episode writes its own progress again.
        transitions.Present();
        Assert.IsTrue(transitions.ShouldWriteProgress);
    }
}
