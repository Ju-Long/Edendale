using System.Collections.Concurrent;
using System.Net;
using System.Text.Json;
using Edendale.Windows.Core;
using Edendale.Windows.Models;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// TMDB's answers for the library sweep, without the network (apple-27.1
/// EdendaleTests/LibraryMetadataAndSourcesTests.swift FakeMetadata). The sweep
/// asks on its own task, so everything here is safe across threads.
/// </summary>
internal sealed class FakeMetadata
{
    private readonly List<string> _requests = [];
    private readonly ConcurrentDictionary<string, (TaskCompletionSource Asked, Task Release)> _holds = new();

    public ConcurrentDictionary<string, MovieMatch> Movies { get; } = new();
    public ConcurrentDictionary<string, ShowMatch> Shows { get; } = new();

    /// <summary>"showId-season-episode" → match; anything else answers 404.</summary>
    public ConcurrentDictionary<string, EpisodeMatch> Episodes { get; } = new();

    /// <summary>Thrown by every lookup while set, as an unreachable TMDB would.</summary>
    public Exception? Failure { get; set; }

    /// <summary>Thrown by one lookup, by its request ("movie:Heat").</summary>
    public ConcurrentDictionary<string, Exception> Failures { get; } = new();

    /// <summary>Every lookup made, in order: "movie:Heat", "tv:Severance", "episode:95396-1-2".</summary>
    public IReadOnlyList<string> Requests
    {
        get { lock (_requests) return [.. _requests]; }
    }

    public void ResetRequests()
    {
        lock (_requests) _requests.Clear();
    }

    /// <summary>
    /// Holds the lookup for <paramref name="request"/> open: Asked completes
    /// once the sweep is waiting on it, and the lookup answers once Release is
    /// completed.
    /// </summary>
    public (Task Asked, TaskCompletionSource Release) Hold(string request)
    {
        var asked = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        _holds[request] = (asked, release.Task);
        return (asked.Task, release);
    }

    public MetadataLookup Lookup => new()
    {
        SearchMovie = async (title, _) =>
        {
            await AskAsync($"movie:{title}");
            return Movies.GetValueOrDefault(title);
        },
        SearchShow = async name =>
        {
            await AskAsync($"tv:{name}");
            return Shows.GetValueOrDefault(name);
        },
        FetchEpisode = async (showId, season, episode) =>
        {
            var key = $"{showId}-{season}-{episode}";
            await AskAsync($"episode:{key}");
            return Episodes.TryGetValue(key, out var match) ? match : throw Status(404);
        },
    };

    /// <summary>TmdbClient's failure for an unsuccessful status.</summary>
    public static WindowsCoreException Status(int code) =>
        new($"TMDB request failed (HTTP {code}).", TmdbFailure.HttpStatus, code);

    public static MovieMatch Movie(int id) => new(id, null, null, "", 1995, null);

    public static ShowMatch Show(int id) => new(id, null, null, "", 2022);

    public static EpisodeMatch Episode(int id, int number) => new(id, $"Episode {number}", null, null);

    private async Task AskAsync(string request)
    {
        lock (_requests) _requests.Add(request);
        if (_holds.TryGetValue(request, out var hold))
        {
            hold.Asked.TrySetResult();
            await hold.Release.ConfigureAwait(false);
        }
        var failure = Failures.TryGetValue(request, out var own) ? own : Failure;
        if (failure is not null) throw failure;
    }
}

/// <summary>
/// The library-wide TMDB sweep (apple-27.1 94a8b4f LibraryEnrichmentTests,
/// ported, plus the Windows cases): it finishes what an earlier sweep left
/// (new episodes of a matched show, lookups made offline), asks about titles
/// with no match again only after a week or on a forced rescan, ends a pass
/// when TMDB can't be reached, and queues one more pass for requests made
/// while it runs. No request reaches TMDB: the lookups are answered here.
/// </summary>
[TestClass]
public sealed class LibraryEnrichmentTests
{
    private static readonly TimeSpan Patience = TimeSpan.FromSeconds(10);

    private readonly LibraryData _library = new();
    private readonly object _gate = new();
    private readonly FakeMetadata _fake = new();
    private readonly MetadataSweep _sweep;
    private DateTimeOffset _clock = new(2026, 11, 12, 9, 0, 0, TimeSpan.Zero);
    private int _persisted;
    private int _added;

    public LibraryEnrichmentTests()
    {
        _sweep = new MetadataSweep(_library, _gate, _fake.Lookup, () => _clock, () => Interlocked.Increment(ref _persisted));
    }

    // Each title is dated a minute before the one added ahead of it. A pass
    // goes newest first, so it asks about them in the order the test adds them.
    private DateTimeOffset NextAdded() => _clock.AddMinutes(-++_added);

    private LibraryMovie AddMovie(string title, int? year = null)
    {
        var movie = new LibraryMovie { Title = title, Year = year, FilePath = $@"C:\Films\{title}.mkv", DateAdded = NextAdded() };
        lock (_gate) _library.Movies.Add(movie);
        return movie;
    }

    private LibraryShow AddShow(string name, int? tmdbId = null)
    {
        var show = new LibraryShow { Name = name, TmdbId = tmdbId, DateAdded = NextAdded() };
        lock (_gate) _library.Shows.Add(show);
        return show;
    }

    private LibraryEpisode AddEpisode(LibraryShow show, int season, int number, int? tmdbId = null)
    {
        var episode = new LibraryEpisode
        {
            FilePath = $@"C:\Shows\{show.Name}\S{season}E{number}.mkv",
            Season = season,
            Episode = number,
            TmdbId = tmdbId,
        };
        lock (_gate) show.Episodes.Add(episode);
        return episode;
    }

    private Task SweepAsync(bool retryingUnmatched = false) => _sweep.Request(retryingUnmatched).WaitAsync(Patience);

    // ------------------------------------------------------------------
    // Apple's cases
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task MatchesMoviesShowsAndTheirEpisodes()
    {
        var heat = AddMovie("Heat");
        var severance = AddShow("Severance");
        var pilot = AddEpisode(severance, 1, 1);
        _fake.Movies["Heat"] = FakeMetadata.Movie(949);
        _fake.Shows["Severance"] = FakeMetadata.Show(95396);
        _fake.Episodes["95396-1-1"] = FakeMetadata.Episode(500, 1);
        Assert.AreEqual(2, _sweep.PendingCount(_clock), "the episode waits for its show");

        await SweepAsync();

        Assert.AreEqual(949, heat.TmdbId);
        Assert.AreEqual(95396, severance.TmdbId);
        Assert.AreEqual(500, pilot.TmdbId);
        Assert.AreEqual("Episode 1", pilot.Title);
        Assert.AreEqual(0, _sweep.PendingCount(_clock));
        Assert.IsFalse(_sweep.IsRunning);
        // The show just matched has its episode looked up in the same pass.
        CollectionAssert.AreEqual(
            new[] { "movie:Heat", "tv:Severance", "episode:95396-1-1" },
            _fake.Requests.ToArray());
    }

    [TestMethod]
    public async Task EpisodesAddedToAMatchedShowAreMatched()
    {
        var severance = AddShow("Severance", tmdbId: 95396);
        AddEpisode(severance, 1, 1, tmdbId: 500);
        // A rescan found the next file after the show was matched.
        var added = AddEpisode(severance, 1, 2);
        _fake.Episodes["95396-1-2"] = FakeMetadata.Episode(501, 2);
        Assert.AreEqual(1, _sweep.PendingCount(_clock));

        await SweepAsync();

        Assert.AreEqual(501, added.TmdbId);
        CollectionAssert.AreEqual(new[] { "episode:95396-1-2" }, _fake.Requests.ToArray());
    }

    [TestMethod]
    public async Task UnreachableTmdbLeavesItemsForTheNextSweep()
    {
        var heat = AddMovie("Heat");
        var ronin = AddMovie("Ronin");
        _fake.Failure = new HttpRequestException("No such host is known.");

        await SweepAsync();

        // One failed request ends the pass; nothing is marked unmatched.
        Assert.AreEqual(1, _fake.Requests.Count);
        Assert.IsNull(heat.TmdbId);
        Assert.IsNull(heat.MetadataCheckedAt);
        Assert.IsNull(ronin.TmdbId);
        Assert.IsNull(ronin.MetadataCheckedAt);
        Assert.AreEqual(2, _sweep.PendingCount(_clock));
        Assert.AreEqual(0, _persisted, "nothing changed, so library.json isn't rewritten");

        _fake.Failure = null;
        _fake.Movies["Heat"] = FakeMetadata.Movie(949);
        _fake.Movies["Ronin"] = FakeMetadata.Movie(8195);
        await SweepAsync();

        Assert.AreEqual(949, heat.TmdbId);
        Assert.AreEqual(8195, ronin.TmdbId);
    }

    [TestMethod]
    public async Task UnmatchedTitlesWaitBeforeBeingAskedAgain()
    {
        var home = AddMovie("Birthday 2019");
        var severance = AddShow("Severance", tmdbId: 95396);
        // Numbering TMDB doesn't have: a 404 is an answer, not a failure.
        var special = AddEpisode(severance, 7, 40);

        await SweepAsync();

        Assert.AreEqual(_clock, home.MetadataCheckedAt);
        Assert.AreEqual(_clock, special.MetadataCheckedAt);
        Assert.AreEqual(0, _sweep.PendingCount(_clock));

        // The automatic sweep (every Downloaded visit) doesn't ask again…
        _fake.ResetRequests();
        await SweepAsync();
        Assert.AreEqual(0, _fake.Requests.Count);

        // …until the interval passes, or a rescan forces it.
        var interval = LibraryEnrichment.UnmatchedRetryInterval;
        Assert.AreEqual(0, _sweep.PendingCount(_clock + interval - TimeSpan.FromSeconds(1)));
        Assert.AreEqual(2, _sweep.PendingCount(_clock + interval + TimeSpan.FromSeconds(1)));
        _fake.Movies["Birthday 2019"] = FakeMetadata.Movie(1);
        await SweepAsync(retryingUnmatched: true);

        Assert.AreEqual(2, _fake.Requests.Count);
        Assert.AreEqual(1, home.TmdbId);
        Assert.IsNull(home.MetadataCheckedAt);
        Assert.AreEqual(_clock, special.MetadataCheckedAt, "TMDB still has no such episode");
    }

    [TestMethod]
    public void ClassifiesLookupFailures()
    {
        // TMDB can't be reached, refuses the credential, or rate-limits: the sweep ends.
        Assert.IsTrue(LibraryEnrichment.HaltsEnrichment(new TaskCanceledException("HttpClient.Timeout", new TimeoutException())));
        Assert.IsTrue(LibraryEnrichment.HaltsEnrichment(new OperationCanceledException()));
        Assert.IsTrue(LibraryEnrichment.HaltsEnrichment(new HttpRequestException("No such host is known.")));
        Assert.IsTrue(LibraryEnrichment.HaltsEnrichment(new IOException("The response ended prematurely.")));
        Assert.IsTrue(LibraryEnrichment.HaltsEnrichment(new WindowsCoreException("Not configured.", TmdbFailure.MissingCredentials)));
        foreach (var code in new[] { 401, 403, 429, 500, 503 })
        {
            Assert.IsTrue(LibraryEnrichment.HaltsEnrichment(FakeMetadata.Status(code)), $"HTTP {code}");
        }
        Assert.IsTrue(LibraryEnrichment.HaltsEnrichment(new HttpRequestException("Too many", null, HttpStatusCode.TooManyRequests)));

        // An answer without a usable match: the title is marked and asked about later.
        foreach (var code in new[] { 400, 404, 422 })
        {
            Assert.IsFalse(LibraryEnrichment.HaltsEnrichment(FakeMetadata.Status(code)), $"HTTP {code}");
        }
        Assert.IsFalse(LibraryEnrichment.HaltsEnrichment(new HttpRequestException("Not found", null, HttpStatusCode.NotFound)));
        Assert.IsFalse(LibraryEnrichment.HaltsEnrichment(new WindowsCoreException("Not JSON.", TmdbFailure.InvalidJson)));
        Assert.IsFalse(LibraryEnrichment.HaltsEnrichment(new WindowsCoreException("Unsupported media type \"x\".")));
        Assert.IsFalse(LibraryEnrichment.HaltsEnrichment(new JsonException()));
        // S01E00: numbering TMDB can't hold, refused before any request.
        Assert.IsFalse(LibraryEnrichment.HaltsEnrichment(new ArgumentOutOfRangeException("episode")));
    }

    [TestMethod]
    public async Task RequestsDuringASweepQueueOneMorePass()
    {
        var heat = AddMovie("Heat");
        _fake.Movies["Heat"] = FakeMetadata.Movie(949);
        var (asked, release) = _fake.Hold("movie:Heat");
        var first = _sweep.Request();
        await asked.WaitAsync(Patience);
        Assert.IsTrue(_sweep.IsRunning);

        // Stored while the pass waits on TMDB, as another source's scan would.
        var ronin = AddMovie("Ronin");
        _fake.Movies["Ronin"] = FakeMetadata.Movie(8195);
        var second = _sweep.Request();
        _sweep.Request();
        _sweep.Request();
        Assert.AreSame(first, second, "a request while a sweep runs joins it");
        release.SetResult();
        await second.WaitAsync(Patience);

        Assert.AreEqual(949, heat.TmdbId);
        Assert.AreEqual(8195, ronin.TmdbId);
        // Each title was asked about once.
        CollectionAssert.AreEqual(new[] { "movie:Heat", "movie:Ronin" }, _fake.Requests.Order().ToArray());
        Assert.IsFalse(_sweep.IsRunning);
    }

    // ------------------------------------------------------------------
    // Windows cases
    // ------------------------------------------------------------------

    [TestMethod]
    public async Task ATitleRemovedWhileTmdbAnsweredIsNotMarkedOrRestored()
    {
        var heat = AddMovie("Heat");
        var home = AddMovie("Birthday 2019");
        _fake.Movies["Heat"] = FakeMetadata.Movie(949);
        var (asked, release) = _fake.Hold("movie:Heat");
        var sweep = _sweep.Request();
        await asked.WaitAsync(Patience);

        // The source holding both was removed while TMDB answered.
        lock (_gate) _library.Movies.Clear();
        release.SetResult();
        await sweep.WaitAsync(Patience);

        Assert.AreEqual(0, _library.Movies.Count);
        Assert.IsNull(heat.TmdbId);
        Assert.IsNull(home.MetadataCheckedAt);
        Assert.AreEqual(0, _persisted);
    }

    [TestMethod]
    public async Task RateLimitHaltsTheSweep()
    {
        var heat = AddMovie("Heat");
        var ronin = AddMovie("Ronin");
        _fake.Failure = FakeMetadata.Status(429);

        await SweepAsync();

        Assert.AreEqual(1, _fake.Requests.Count, "no second request after a 429");
        Assert.IsNull(heat.MetadataCheckedAt);
        Assert.IsNull(ronin.MetadataCheckedAt);
        Assert.AreEqual(2, _sweep.PendingCount(_clock));
    }

    [TestMethod]
    public async Task AHaltKeepsWhatThePassAlreadyMatched()
    {
        var heat = AddMovie("Heat");
        var ronin = AddMovie("Ronin");
        var casino = AddMovie("Casino");
        _fake.Movies["Heat"] = FakeMetadata.Movie(949);
        _fake.Failures["movie:Ronin"] = FakeMetadata.Status(503);

        await SweepAsync();

        Assert.AreEqual(949, heat.TmdbId);
        Assert.IsNull(ronin.MetadataCheckedAt);
        Assert.IsNull(casino.MetadataCheckedAt);
        CollectionAssert.AreEqual(new[] { "movie:Heat", "movie:Ronin" }, _fake.Requests.ToArray());
        Assert.AreEqual(1, _persisted, "the match made before the halt is saved");
    }

    [TestMethod]
    public async Task AWeekLaterTheAutomaticSweepAsksAgain()
    {
        var home = AddMovie("Birthday 2019");
        await SweepAsync();
        Assert.AreEqual(_clock, home.MetadataCheckedAt);

        _clock += LibraryEnrichment.UnmatchedRetryInterval;
        _fake.ResetRequests();
        await SweepAsync();

        CollectionAssert.AreEqual(new[] { "movie:Birthday 2019" }, _fake.Requests.ToArray());
        Assert.AreEqual(_clock, home.MetadataCheckedAt, "marked again from now");
    }

    [TestMethod]
    public void IsDueFollowsTheRetryInterval()
    {
        var marked = _clock;
        var week = LibraryEnrichment.UnmatchedRetryInterval;
        Assert.AreEqual(TimeSpan.FromDays(7), week);
        Assert.IsTrue(LibraryEnrichment.IsDue(null, marked, retryingUnmatched: false), "never asked");
        Assert.IsFalse(LibraryEnrichment.IsDue(marked, marked + week - TimeSpan.FromSeconds(1), retryingUnmatched: false));
        Assert.IsTrue(LibraryEnrichment.IsDue(marked, marked + week, retryingUnmatched: false));
        Assert.IsTrue(LibraryEnrichment.IsDue(marked, marked, retryingUnmatched: true), "a forced rescan asks at once");
    }

    [TestMethod]
    public void NothingDueStartsNoSweep()
    {
        var changes = 0;
        _sweep.RunningChanged += (_, _) => Interlocked.Increment(ref changes);
        var heat = AddMovie("Heat");
        heat.TmdbId = 949;
        var home = AddMovie("Birthday 2019");
        home.MetadataCheckedAt = _clock;

        var request = _sweep.Request();

        Assert.IsTrue(request.IsCompleted);
        Assert.IsFalse(_sweep.IsRunning);
        Assert.AreEqual(0, changes, "the status row doesn't flicker on a visit with nothing to do");
        Assert.AreEqual(0, _fake.Requests.Count);
    }

    [TestMethod]
    public async Task ASweepSaysWhenItStartsAndEnds()
    {
        var changes = 0;
        _sweep.RunningChanged += (_, _) => Interlocked.Increment(ref changes);
        AddMovie("Heat");

        await SweepAsync();

        Assert.AreEqual(2, changes);
        Assert.IsFalse(_sweep.IsRunning);
    }

    [TestMethod]
    public async Task SavesEveryFewTitlesAndAtTheEndOfAPass()
    {
        for (var i = 0; i < 2 * MetadataSweep.PersistEvery + 5; i++) AddMovie($"Home Video {i}");

        await SweepAsync();

        Assert.AreEqual(3, _persisted);
        Assert.AreEqual(0, _sweep.PendingCount(_clock));
    }

    [TestMethod]
    public async Task AMatchFillsTheTitleAndKeepsTheParsedYear()
    {
        var heat = AddMovie("Heat", year: 1995);
        _fake.Movies["Heat"] = new MovieMatch(949, "poster", "backdrop", "A group of thieves.", 1996, 170);
        var severance = AddShow("Severance");
        _fake.Shows["Severance"] = new ShowMatch(95396, "show-poster", "show-backdrop", "Work and life, split.", 2022);
        var pilot = AddEpisode(severance, 1, 1);
        _fake.Episodes["95396-1-1"] = new EpisodeMatch(500, "Good News About Hell", "still", 57);

        await SweepAsync();

        Assert.AreEqual("poster", heat.PosterUrl);
        Assert.AreEqual("backdrop", heat.BackdropUrl);
        Assert.AreEqual("A group of thieves.", heat.Overview);
        Assert.AreEqual(1995, heat.Year, "the year parsed from the file stays");
        Assert.AreEqual(170, heat.RuntimeMinutes);
        Assert.AreEqual("show-poster", severance.PosterUrl);
        Assert.AreEqual("Work and life, split.", severance.Overview);
        Assert.AreEqual(2022, severance.FirstAirYear);
        Assert.AreEqual("Good News About Hell", pilot.Title);
        Assert.AreEqual("still", pilot.StillUrl);
        Assert.AreEqual(57, pilot.RuntimeMinutes);
    }

    [TestMethod]
    public void MetadataCheckedAtRoundTripsThroughLibraryJson()
    {
        // LibraryService's library.json options.
        var options = new JsonSerializerOptions
        {
            PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
            PropertyNameCaseInsensitive = true,
            WriteIndented = true,
        };
        var library = new LibraryData
        {
            Movies = [new LibraryMovie { Title = "Birthday 2019", MetadataCheckedAt = _clock }],
            Shows =
            [
                new LibraryShow
                {
                    Name = "Severance",
                    TmdbId = 95396,
                    Episodes = [new LibraryEpisode { Season = 7, Episode = 40, MetadataCheckedAt = _clock }],
                },
            ],
        };

        var json = JsonSerializer.Serialize(library, options);
        StringAssert.Contains(json, "\"metadataCheckedAt\"");
        var read = JsonSerializer.Deserialize<LibraryData>(json, options)!;
        Assert.AreEqual(_clock, read.Movies[0].MetadataCheckedAt);
        Assert.IsNull(read.Shows[0].MetadataCheckedAt);
        Assert.AreEqual(_clock, read.Shows[0].Episodes[0].MetadataCheckedAt);

        // A library.json from before 27.1 has no marks, so every unmatched title is due.
        var older = JsonSerializer.Deserialize<LibraryData>(
            """{"movies":[{"title":"Heat"}],"shows":[{"name":"Severance","episodes":[{"season":1,"episode":1}]}]}""",
            options)!;
        Assert.IsNull(older.Movies[0].MetadataCheckedAt);
        Assert.IsNull(older.Shows[0].MetadataCheckedAt);
        Assert.IsNull(older.Shows[0].Episodes[0].MetadataCheckedAt);
    }
}

/// <summary>
/// The matching the sweep's TMDB lookups keep from before 27.1: the movie
/// from the parsed year, details for its runtime, the first TV result for a
/// show, and the episode at the show's numbers.
/// </summary>
[TestClass]
public sealed class TmdbMetadataLookupTests
{
    private static Task<List<MediaItem>> Results(params MediaItem[] items) => Task.FromResult(items.ToList());

    private static Task<MediaDetail> Details(int id) =>
        Task.FromResult(new MediaDetail { Ref = new MediaRef { Id = id }, Overview = "From the details.", RuntimeMinutes = 170 });

    private static Task<EpisodeDetail> NoEpisodes(int show, int season, int episode) =>
        throw new AssertFailedException("no episode lookup expected");

    private static readonly MediaItem[] HeatResults =
    [
        new() { Id = 1396, MediaType = "tv", Title = "Heat", Year = 2005 },
        new() { Id = 99, MediaType = "movie", Title = "Heat", Year = 1986, Overview = "The 1986 one." },
        new() { Id = 949, MediaType = "movie", Title = "Heat", Year = 1995, PosterUrl = "poster", BackdropUrl = "backdrop", Overview = "From the search." },
    ];

    [TestMethod]
    public async Task AMovieIsTheResultFromItsParsedYear()
    {
        var lookup = MetadataLookup.FromTmdb(_ => Results(HeatResults), (id, _) => Details(id), NoEpisodes);

        Assert.AreEqual(
            new MovieMatch(949, "poster", "backdrop", "From the details.", 1995, 170),
            await lookup.SearchMovie("Heat", 1995));
        Assert.AreEqual(99, (await lookup.SearchMovie("Heat", null))!.TmdbId, "without a year, the first movie");
        Assert.AreEqual(99, (await lookup.SearchMovie("Heat", 2020))!.TmdbId, "no result from that year");
    }

    [TestMethod]
    public async Task AMovieKeepsItsMatchWhenItsDetailsDontLoad()
    {
        var lookup = MetadataLookup.FromTmdb(
            _ => Results(HeatResults),
            (_, _) => throw FakeMetadata.Status(404),
            NoEpisodes);

        Assert.AreEqual(
            new MovieMatch(949, "poster", "backdrop", "From the search.", 1995, null),
            await lookup.SearchMovie("Heat", 1995));
    }

    [TestMethod]
    public async Task AMovieLookupEndsTheSweepWhenItsDetailsCantBeReached()
    {
        var lookup = MetadataLookup.FromTmdb(
            _ => Results(HeatResults),
            (_, _) => throw new HttpRequestException("The connection was reset."),
            NoEpisodes);

        await Assert.ThrowsExceptionAsync<HttpRequestException>(() => lookup.SearchMovie("Heat", 1995));
    }

    [TestMethod]
    public async Task NoMovieResultIsNoMatch()
    {
        var lookup = MetadataLookup.FromTmdb(
            _ => Results(new MediaItem { Id = 1396, MediaType = "tv", Title = "Heat" }),
            (_, _) => throw new AssertFailedException("no details expected"),
            NoEpisodes);

        Assert.IsNull(await lookup.SearchMovie("Heat", 1995));
    }

    [TestMethod]
    public async Task AShowIsTheFirstTvResult()
    {
        var lookup = MetadataLookup.FromTmdb(
            _ => Results(
                new MediaItem { Id = 8, MediaType = "movie", Title = "Severance" },
                new MediaItem { Id = 95396, MediaType = "tv", Title = "Severance", PosterUrl = "poster", Overview = "Work and life.", Year = 2022 },
                new MediaItem { Id = 7, MediaType = "tv", Title = "Severance (UK)" }),
            (_, _) => throw new AssertFailedException("no details expected"),
            NoEpisodes);

        Assert.AreEqual(new ShowMatch(95396, "poster", null, "Work and life.", 2022), await lookup.SearchShow("Severance"));
    }

    [TestMethod]
    public async Task AnEpisodeWithoutAnIdIsNoMatch()
    {
        var lookup = MetadataLookup.FromTmdb(
            _ => Results(),
            (_, _) => throw new AssertFailedException("no details expected"),
            (show, season, episode) => Task.FromResult(episode == 1
                ? new EpisodeDetail { Id = 500, Name = "Good News About Hell", StillUrl = "still", RuntimeMinutes = 57 }
                : new EpisodeDetail { Id = 0 }));

        Assert.AreEqual(new EpisodeMatch(500, "Good News About Hell", "still", 57), await lookup.FetchEpisode(95396, 1, 1));
        Assert.IsNull(await lookup.FetchEpisode(95396, 1, 2));
    }
}
