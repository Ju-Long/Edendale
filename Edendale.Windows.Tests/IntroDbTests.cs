using System.IO;
using System.Net;
using System.Net.Http;
using Edendale.Windows.Core;
using Edendale.Windows.Services;
using Microsoft.VisualStudio.TestTools.UnitTesting;

namespace Edendale.Windows.Tests;

/// <summary>
/// TheIntroDB requests, decoding, overlap rejection, the 429 cooldown, and
/// the prompt rules. Ports IntroDBTests.swift with a stub HttpMessageHandler.
/// </summary>
[TestClass]
public sealed class IntroDbTests
{
    private static readonly IntroDbMedia Movie = IntroDbMedia.Create(278)!;

    // ------------------------------------------------------------------
    // Requests
    // ------------------------------------------------------------------

    [TestMethod]
    public void RequestsContainOnlyCanonicalIdentityAndRuntime()
    {
        var media = IntroDbMedia.Create(1396, 2, 8)!;
        var request = IntroDbRequest.Create(media, 2700.125)!;
        Assert.AreEqual("api.theintrodb.org", request.Uri.Host);
        Assert.AreEqual("/v3/media", request.Uri.AbsolutePath);
        Assert.AreEqual("?tmdb_id=1396&season=2&episode=8&duration_ms=2700125", request.Uri.Query);
        Assert.AreEqual("tv", media.Type);

        Assert.AreEqual("?tmdb_id=278&duration_ms=7200000", IntroDbRequest.Create(Movie, 7200)!.Uri.Query);
        Assert.AreEqual("movie", Movie.Type);
    }

    [TestMethod]
    public void UnsupportedIdentityAndDurationNeverMakeARequest()
    {
        Assert.IsNull(IntroDbMedia.Create(0));
        Assert.IsNull(IntroDbMedia.Create(10_000_001));
        Assert.IsNull(IntroDbMedia.Create(1396, 0, 1));
        Assert.IsNull(IntroDbMedia.Create(1396, 1, 0));
        Assert.IsNull(IntroDbMedia.Create(1396, 1, null));
        Assert.IsNull(IntroDbMedia.Create(1396, null, 1));
        foreach (var duration in new[] { 0, -1, double.NaN, double.PositiveInfinity, 21_601 })
        {
            Assert.IsNull(IntroDbRequest.Create(Movie, duration), duration.ToString());
        }
        Assert.IsNotNull(IntroDbRequest.Create(Movie, 21_600));
    }

    [TestMethod]
    public void PlaybackUsesTheShowIdAndRejectsUnidentifiedFilesAndSpecials()
    {
        var episode = new PlaybackRequest
        {
            FilePath = @"C:\example.mkv",
            Title = "Example",
            TmdbId = 62085,
            MediaType = "episode",
            ShowTmdbId = 1396,
            SeasonNumber = 2,
            EpisodeNumber = 8,
        };
        Assert.AreEqual(IntroDbMedia.Create(1396, 2, 8), IntroDbMedia.For(episode));

        var special = new PlaybackRequest
        {
            FilePath = @"C:\example.mkv",
            Title = "Example",
            MediaType = "episode",
            ShowTmdbId = 1396,
            SeasonNumber = 0,
            EpisodeNumber = 8,
        };
        Assert.IsNull(IntroDbMedia.For(special));
        Assert.IsNull(IntroDbMedia.For(new PlaybackRequest { FilePath = @"C:\loose.mkv", Title = "Loose" }));
        Assert.AreEqual(Movie, IntroDbMedia.For(new PlaybackRequest { FilePath = @"C:\m.mkv", Title = "M", TmdbId = 278 }));
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    [TestMethod]
    public void DecodesMultipleRangesAndPreservesCreditSceneGaps()
    {
        var request = IntroDbRequest.Create(Movie, 120)!;
        var segments = IntroDbClient.Decode("""
            {"tmdb_id":278,"type":"movie",
             "intro":[{"start_ms":null,"end_ms":10000}],
             "recap":[{"start_ms":20000,"end_ms":30000}],
             "credits":[{"start_ms":90000,"end_ms":100000},{"start_ms":110000,"end_ms":null}],
             "preview":[{"start_ms":30000,"end_ms":40000}]}
            """, request);

        CollectionAssert.AreEqual(
            new[] { MediaSegmentKind.Intro, MediaSegmentKind.Recap, MediaSegmentKind.Credits, MediaSegmentKind.Credits },
            segments.Select(segment => segment.Kind).ToArray());
        CollectionAssert.AreEqual(new long[] { 0, 20_000, 90_000, 110_000 }, segments.Select(s => s.StartMilliseconds).ToArray());
        CollectionAssert.AreEqual(new long[] { 10_000, 30_000, 100_000, 120_000 }, segments.Select(s => s.EndMilliseconds).ToArray());
        CollectionAssert.AreEqual(new[] { false, false, false, true }, segments.Select(s => s.ReachesEnd).ToArray());
        Assert.IsFalse(segments.Any(segment => segment.Contains(105_000)));
        Assert.IsTrue(segments[0].Contains(0));
        Assert.IsFalse(segments[0].Contains(10_000));
    }

    [TestMethod]
    public void IgnoresNoSegmentInvalidAndOverlappingRanges()
    {
        var request = IntroDbRequest.Create(Movie, 120)!;
        var segments = IntroDbClient.Decode("""
            {"tmdb_id":278,"type":"movie",
             "intro":[{"start_ms":null,"end_ms":null},{"start_ms":null,"end_ms":0},
                      {"start_ms":-1000,"end_ms":3000},{"start_ms":5000,"end_ms":4000},
                      {"start_ms":10000,"end_ms":20000},{"start_ms":30000,"end_ms":40000},
                      {"start_ms":30000,"end_ms":40000},{"start_ms":100000,"end_ms":121000}],
             "recap":[{"start_ms":15000,"end_ms":25000}],
             "credits":[{"start_ms":null,"end_ms":110000},{"start_ms":0,"end_ms":null}]}
            """, request);

        Assert.AreEqual(1, segments.Count);
        Assert.AreEqual(new MediaSegment(MediaSegmentKind.Intro, 30_000, 40_000), segments[0]);
    }

    [TestMethod]
    public void MissingArraysAndMismatchedResponses()
    {
        var request = IntroDbRequest.Create(Movie, 120)!;
        Assert.AreEqual(0, IntroDbClient.Decode("""{"tmdb_id":278,"type":"movie"}""", request).Count);

        foreach (var json in new[]
        {
            """{"tmdb_id":279,"type":"movie"}""",
            """{"tmdb_id":278,"type":"tv","season":1,"episode":1}""",
            """{"tmdb_id":278,"type":"movie","season":1}""",
            """{"tmdb_id":278,"type":"movie","intro":"invalid"}""",
            "not json",
        })
        {
            Assert.ThrowsException<IntroDbException>(() => IntroDbClient.Decode(json, request), json);
        }

        var episode = IntroDbRequest.Create(IntroDbMedia.Create(1396, 1, 2)!, 120)!;
        Assert.ThrowsException<IntroDbException>(() =>
            IntroDbClient.Decode("""{"tmdb_id":1396,"type":"tv","season":1,"episode":3}""", episode));
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private sealed class StubHandler(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
    {
        public List<HttpRequestMessage> Requests { get; } = [];

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests.Add(request);
            return Task.FromResult(respond(request));
        }
    }

    [TestMethod]
    public async Task RequestsAskForJsonWithoutCredentials()
    {
        var handler = new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StringContent("""{"tmdb_id":278,"type":"movie","intro":[{"start_ms":0,"end_ms":5000}]}"""),
        });
        var client = new IntroDbClient(handler);
        var segments = await client.SegmentsAsync(IntroDbRequest.Create(Movie, 120)!);

        Assert.AreEqual(1, segments.Count);
        var sent = handler.Requests.Single();
        Assert.AreEqual(HttpMethod.Get, sent.Method);
        Assert.AreEqual("application/json", sent.Headers.Accept.Single().MediaType);
        Assert.IsNull(sent.Headers.Authorization);
        Assert.IsFalse(sent.Headers.Contains("Cookie"));
    }

    [TestMethod]
    public async Task NotFoundProducesNoSegments()
    {
        var client = new IntroDbClient(new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.NotFound)));
        Assert.AreEqual(0, (await client.SegmentsAsync(IntroDbRequest.Create(Movie, 120)!)).Count);
    }

    [TestMethod]
    public async Task OtherStatusesFail()
    {
        var client = new IntroDbClient(new StubHandler(_ => new HttpResponseMessage(HttpStatusCode.InternalServerError)));
        var error = await Assert.ThrowsExceptionAsync<IntroDbException>(() => client.SegmentsAsync(IntroDbRequest.Create(Movie, 120)!));
        Assert.AreEqual(IntroDbFailure.BadStatus, error.Failure);
    }

    [TestMethod]
    public async Task RateLimitPreventsImmediateRetryForTheLongestReset()
    {
        var now = DateTimeOffset.Parse("2026-10-01T12:00:00Z");
        var handler = new StubHandler(_ =>
        {
            var response = new HttpResponseMessage((HttpStatusCode)429);
            response.Headers.TryAddWithoutValidation("X-UsageLimit-Reset", "3600");
            response.Headers.TryAddWithoutValidation("X-RateLimit-Reset", "30");
            return response;
        });
        var client = new IntroDbClient(handler, () => now);
        var request = IntroDbRequest.Create(Movie, 120)!;

        for (var attempt = 0; attempt < 2; attempt++)
        {
            var error = await Assert.ThrowsExceptionAsync<IntroDbException>(() => client.SegmentsAsync(request));
            Assert.AreEqual(IntroDbFailure.RateLimited, error.Failure);
        }
        Assert.AreEqual(1, handler.Requests.Count);

        now = now.AddSeconds(3599);
        await Assert.ThrowsExceptionAsync<IntroDbException>(() => client.SegmentsAsync(request));
        Assert.AreEqual(1, handler.Requests.Count);

        now = now.AddSeconds(2);
        await Assert.ThrowsExceptionAsync<IntroDbException>(() => client.SegmentsAsync(request));
        Assert.AreEqual(2, handler.Requests.Count);
    }

    [TestMethod]
    public void CooldownIsAtLeastAMinute()
    {
        using var bare = new HttpResponseMessage((HttpStatusCode)429);
        Assert.AreEqual(60, IntroDbClient.Cooldown(bare));

        using var short_ = new HttpResponseMessage((HttpStatusCode)429);
        short_.Headers.TryAddWithoutValidation("Retry-After", "5");
        Assert.AreEqual(60, IntroDbClient.Cooldown(short_));

        using var longer = new HttpResponseMessage((HttpStatusCode)429);
        longer.Headers.TryAddWithoutValidation("Retry-After", "120");
        longer.Headers.TryAddWithoutValidation("X-RateLimit-Reset", "garbage");
        Assert.AreEqual(120, IntroDbClient.Cooldown(longer));
    }

    // ------------------------------------------------------------------
    // Prompts
    // ------------------------------------------------------------------

    private sealed class Prompts : IDisposable
    {
        private readonly string _directory = Path.Combine(Path.GetTempPath(), $"eden-prompts-{Guid.NewGuid():N}");

        public Prompts(SegmentPrompts.Lookup lookup, bool enabled = true)
        {
            Directory.CreateDirectory(_directory);
            Store = new PlayerSettingsStore(Path.Combine(_directory, "player-settings.json"));
            Controller = new SegmentPrompts(Store, lookup);
            if (enabled) Controller.IsEnabled = true;
        }

        public PlayerSettingsStore Store { get; }
        public SegmentPrompts Controller { get; }

        public void Dispose()
        {
            Controller.End();
            try { Directory.Delete(_directory, recursive: true); } catch { /* best effort */ }
        }
    }

    private static readonly MediaSegment Intro = new(MediaSegmentKind.Intro, 5_000, 20_000);

    private static async Task Settle(SegmentPrompts controller)
    {
        for (var attempt = 0; attempt < 200 && controller.IsLoading; attempt++) await Task.Delay(5);
        Assert.IsFalse(controller.IsLoading, "lookup did not finish");
    }

    [TestMethod]
    public async Task PromptsAreOptInAndTheLookupWaitsForADuration()
    {
        var requests = new List<IntroDbRequest>();
        using var prompts = new Prompts((request, _) =>
        {
            requests.Add(request);
            return Task.FromResult<IReadOnlyList<MediaSegment>>([Intro]);
        }, enabled: false);
        var controller = prompts.Controller;

        controller.Begin(Movie);
        controller.Update(10_000, 120_000, isSeekable: true);
        Assert.IsFalse(controller.IsEnabled);
        Assert.AreEqual(0, requests.Count);

        controller.Update(10_000, null, isSeekable: true);
        controller.IsEnabled = true;
        Assert.AreEqual(0, requests.Count);

        controller.Update(10_000, 120_000, isSeekable: true);
        await Settle(controller);
        controller.Update(10_000, 120_000, isSeekable: true);
        Assert.AreEqual(Intro, controller.ActiveSegment);
        Assert.IsTrue(prompts.Store.GetBool(PlayerPreferences.SegmentPromptsEnabledKey, fallback: false));
        Assert.AreEqual(1, requests.Count);
    }

    [TestMethod]
    public async Task ManualSkipRevalidatesTimeAndAllowsRewindWithoutRepeatedPresses()
    {
        using var prompts = new Prompts((_, _) => Task.FromResult<IReadOnlyList<MediaSegment>>([Intro]));
        var controller = prompts.Controller;
        controller.Begin(Movie);
        controller.Update(10_000, 120_000, true);
        await Settle(controller);

        Assert.IsNull(controller.ConsumeSkip(30_000, 120_000, true));
        Assert.IsNull(controller.ConsumeSkip(10_000, 120_000, false));
        Assert.AreEqual(new SegmentSkipAction.Seek(20_000), controller.ConsumeSkip(10_000, 120_000, true));
        Assert.IsNull(controller.ActiveSegment);
        Assert.IsNull(controller.ConsumeSkip(10_000, 120_000, true));

        controller.Update(21_000, 120_000, true);
        controller.Update(10_000, 120_000, true);
        Assert.AreEqual(Intro, controller.ActiveSegment);
    }

    [TestMethod]
    public async Task APressedPromptStaysHiddenUntilPlaybackLeavesTheRange()
    {
        using var prompts = new Prompts((_, _) => Task.FromResult<IReadOnlyList<MediaSegment>>([Intro]));
        var controller = prompts.Controller;
        controller.Begin(Movie);
        controller.Update(6_000, 120_000, true);
        await Settle(controller);
        controller.ConsumeSkip(6_000, 120_000, true);

        controller.Update(7_000, 120_000, true);
        Assert.IsNull(controller.ActiveSegment);
    }

    [TestMethod]
    public async Task OnlyTerminalCreditsFinishPlayback()
    {
        using var prompts = new Prompts((_, _) => Task.FromResult<IReadOnlyList<MediaSegment>>(
        [
            new MediaSegment(MediaSegmentKind.Credits, 90_000, 100_000),
            new MediaSegment(MediaSegmentKind.Credits, 110_000, 120_000, ReachesEnd: true),
        ]));
        var controller = prompts.Controller;
        controller.Begin(Movie);
        controller.Update(95_000, 120_000, true);
        await Settle(controller);

        Assert.AreEqual(new SegmentSkipAction.Seek(100_000), controller.ConsumeSkip(95_000, 120_000, true));
        Assert.IsNull(controller.ConsumeSkip(105_000, 120_000, true));
        Assert.IsInstanceOfType(controller.ConsumeSkip(115_000, 120_000, true), typeof(SegmentSkipAction.Finish));
    }

    [TestMethod]
    public async Task LookupIsDeduplicatedCachedPerRuntimeAndClearedWithTheSession()
    {
        var requests = new List<IntroDbRequest>();
        using var prompts = new Prompts((request, _) =>
        {
            requests.Add(request);
            return Task.FromResult<IReadOnlyList<MediaSegment>>([]);
        });
        var controller = prompts.Controller;

        foreach (var duration in new long[] { 120_000, 120_000, 125_000 })
        {
            controller.Begin(Movie);
            for (var time = 0; time < 30; time++) controller.Update(time * 1000, duration, true);
            await Settle(controller);
        }
        Assert.AreEqual(2, requests.Count);

        controller.End();
        controller.Begin(Movie);
        controller.Update(10_000, 120_000, true);
        await Settle(controller);
        Assert.AreEqual(3, requests.Count);
    }

    [TestMethod]
    public async Task TheCacheHoldsAtMostTwelveEntries()
    {
        var requests = 0;
        using var prompts = new Prompts((_, _) =>
        {
            requests++;
            return Task.FromResult<IReadOnlyList<MediaSegment>>([]);
        });
        var controller = prompts.Controller;
        for (var id = 1; id <= 13; id++)
        {
            controller.Begin(IntroDbMedia.Create(id));
            controller.Update(0, 120_000, true);
            await Settle(controller);
        }
        controller.Begin(IntroDbMedia.Create(1));
        controller.Update(0, 120_000, true);
        await Settle(controller);
        Assert.AreEqual(14, requests, "the oldest entry was evicted");

        controller.Begin(IntroDbMedia.Create(13));
        controller.Update(0, 120_000, true);
        await Settle(controller);
        Assert.AreEqual(14, requests, "a recent entry is still cached");
    }

    [TestMethod]
    public async Task FailuresDoNotRetryOnTimeEventsOrLeavePrompts()
    {
        var requests = 0;
        using var prompts = new Prompts((_, _) =>
        {
            requests++;
            throw new HttpRequestException("offline");
        });
        var controller = prompts.Controller;
        controller.Begin(Movie);
        controller.Update(10_000, 120_000, true);
        await Settle(controller);
        for (var time = 11; time < 30; time++) controller.Update(time * 1000, 120_000, true);
        Assert.AreEqual(1, requests);
        Assert.IsNull(controller.ActiveSegment);
    }

    [TestMethod]
    public async Task LateResponsesCannotAffectNewItemsDisabledSettingsOrEndedSessions()
    {
        foreach (var action in new[] { "switch", "disable", "end" })
        {
            var gate = new TaskCompletionSource<IReadOnlyList<MediaSegment>>();
            using var prompts = new Prompts((_, _) => gate.Task);
            var controller = prompts.Controller;
            controller.Begin(Movie);
            controller.Update(10_000, 120_000, true);
            Assert.IsTrue(controller.IsLoading);

            switch (action)
            {
                case "switch": controller.Begin(null); break;
                case "disable": controller.IsEnabled = false; break;
                default: controller.End(); break;
            }

            gate.SetResult([Intro]);
            await Task.Delay(20);
            controller.Update(10_000, 120_000, true);
            Assert.AreEqual(0, controller.Segments.Count, action);
            Assert.IsNull(controller.ActiveSegment, action);
        }
    }
}
