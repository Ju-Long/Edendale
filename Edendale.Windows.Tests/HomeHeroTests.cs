using Edendale.Windows.Core;
using Edendale.Windows.Models;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// Home's Continue Watching scene following watch progress after launch (a
/// merge from another device, or playback here) without reloading the
/// shelves, and the hero pager keeping its place. No network.
/// </summary>
[TestClass]
public sealed class HomeHeroTests
{
    private static WatchProgress Movie(int id, double position, long stamp, bool completed = false) => new()
    {
        TmdbId = id,
        MediaType = "movie",
        Position = position,
        LastWatchedEpochMillis = stamp,
        IsCompleted = completed,
    };

    private static MediaDetail Detail(int id, string mediaType = "movie", int? runtimeMinutes = null) => new()
    {
        Ref = new MediaRef { Id = id, MediaType = mediaType },
        Title = $"Title {id}",
        RuntimeMinutes = runtimeMinutes,
    };

    private static HeroScene Scene(int id, string mediaType = "movie", bool continueWatching = false) => new()
    {
        Detail = Detail(id, mediaType),
        IsContinueWatching = continueWatching,
    };

    private static int[] Ids(IEnumerable<HeroScene> scenes) => scenes.Select(scene => scene.Detail.Ref.Id).ToArray();

    // ------------------------------------------------------------------
    // Which title is Continue Watching
    // ------------------------------------------------------------------

    [TestMethod]
    public void ContinueProgressIsTheNewestUnfinishedTitleWithAPage()
    {
        var finished = Movie(1, 1, stamp: 500, completed: true);
        var cleared = new WatchProgress { TmdbId = 2, MediaType = "movie", IsCleared = true, LastWatchedEpochMillis = 400 };
        var episodeWithoutShow = new WatchProgress { TmdbId = 62085, MediaType = "episode", Position = 0.3, LastWatchedEpochMillis = 300 };
        var older = Movie(3085, 0.2, stamp: 100);
        var newer = Movie(603, 0.4, stamp: 200);

        Assert.AreSame(newer, HomeHero.ContinueProgress([finished, cleared, episodeWithoutShow, older, newer]));
        Assert.IsNull(HomeHero.ContinueProgress([finished, cleared, episodeWithoutShow]));
    }

    [TestMethod]
    public void ContinueRefOpensTheMovieOrTheEpisodesShow()
    {
        var movie = HomeHero.ContinueRef(Movie(3085, 0.5, stamp: 1))!;
        Assert.AreEqual(3085, movie.Id);
        Assert.AreEqual("movie", movie.MediaType);

        // An episode from another device may not know its own id yet; its show does.
        var episode = HomeHero.ContinueRef(new WatchProgress
        {
            MediaType = "episode",
            ShowTmdbId = 1399,
            SeasonNumber = 1,
            EpisodeNumber = 2,
        })!;
        Assert.AreEqual(1399, episode.Id);
        Assert.AreEqual("tv", episode.MediaType);

        Assert.IsNull(HomeHero.ContinueRef(null));
    }

    // ------------------------------------------------------------------
    // Refreshing the scene without reloading Home
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task AMovedPositionReusesTheKnownDetailsWithoutARequest()
    {
        // A merge moves His Girl Friday (92 minutes) a quarter of the way in.
        var detail = Detail(3085, runtimeMinutes: 92);
        var merged = Movie(3085, 0.25, stamp: 2_000);

        var scene = await WindowsCore.LoadContinueSceneAsync([merged, Movie(603, 0.5, stamp: 1_000)], detail);

        Assert.IsNotNull(scene);
        Assert.AreSame(detail, scene.Detail);
        Assert.AreSame(merged, scene.Progress);
        Assert.IsTrue(scene.IsContinueWatching);
        Assert.AreEqual("01:09:00 left", scene.RemainingText);
    }

    [TestMethod]
    public async Task AnotherEpisodeOfTheSameShowReusesTheShowsDetails()
    {
        var show = Detail(1399, "tv", runtimeMinutes: 60);
        var next = new WatchProgress
        {
            TmdbId = 63056,
            MediaType = "episode",
            Position = 0.5,
            LastWatchedEpochMillis = 5_000,
            ShowTmdbId = 1399,
            SeasonNumber = 1,
            EpisodeNumber = 2,
        };

        var scene = await WindowsCore.LoadContinueSceneAsync([next], show);

        Assert.IsNotNull(scene);
        Assert.AreSame(show, scene.Detail);
        Assert.AreEqual("00:30:00 left", scene.RemainingText);
    }

    [TestMethod]
    public async Task NothingLeftToContinueHasNoScene()
    {
        var finished = Movie(3085, 1, stamp: 3_000, completed: true);

        Assert.IsNull(await WindowsCore.LoadContinueSceneAsync([finished], Detail(3085, runtimeMinutes: 92)));
        Assert.IsNull(await WindowsCore.LoadContinueSceneAsync([], null));
    }

    [TestMethod]
    public void ANewContinueSceneReplacesTheOldOneAndItsSpotlight()
    {
        var scenes = new List<HeroScene> { Scene(3085, continueWatching: true), Scene(10), Scene(603), Scene(11) };

        var moved = HomeHero.WithContinueScene(scenes, Scene(603, continueWatching: true));
        CollectionAssert.AreEqual(new[] { 603, 10, 11 }, Ids(moved));
        Assert.IsTrue(moved[0].IsContinueWatching);

        var finished = HomeHero.WithContinueScene(scenes, null);
        CollectionAssert.AreEqual(new[] { 10, 603, 11 }, Ids(finished));
        Assert.IsFalse(finished.Any(scene => scene.IsContinueWatching));

        var started = HomeHero.WithContinueScene(finished, Scene(42, continueWatching: true));
        CollectionAssert.AreEqual(new[] { 42, 10, 603, 11 }, Ids(started));
    }

    [TestMethod]
    public void ThePagerStaysOnTheTitleItShows()
    {
        var before = new List<HeroScene> { Scene(3085, continueWatching: true), Scene(10), Scene(603), Scene(11) };
        var moved = HomeHero.WithContinueScene(before, Scene(603, continueWatching: true));

        Assert.AreEqual(1, HomeHero.StableIndex(before, 1, moved));
        Assert.AreEqual(0, HomeHero.StableIndex(before, 2, moved), "the spotlight on screen became Continue Watching");
        Assert.AreEqual(2, HomeHero.StableIndex(before, 3, moved));
        Assert.AreEqual(0, HomeHero.StableIndex(before, 0, moved), "the old title is gone; its place shows the new one");

        var spotlights = before.Skip(1).ToList();
        var started = HomeHero.WithContinueScene(spotlights, Scene(42, continueWatching: true));
        Assert.AreEqual(2, HomeHero.StableIndex(spotlights, 1, started), "a new Continue Watching scene does not take over the pager");

        Assert.AreEqual(0, HomeHero.StableIndex(before, 2, []));
    }

    [TestMethod]
    public void SameTitlesComparesOrderAndMediaType()
    {
        Assert.IsTrue(HomeHero.SameTitles([Scene(1), Scene(2)], [Scene(1), Scene(2, continueWatching: true)]));
        Assert.IsFalse(HomeHero.SameTitles([Scene(1), Scene(2)], [Scene(2), Scene(1)]));
        Assert.IsFalse(HomeHero.SameTitles([Scene(1)], [Scene(1, "tv")]));
        Assert.IsFalse(HomeHero.SameTitles([Scene(1)], [Scene(1), Scene(2)]));
    }
}
