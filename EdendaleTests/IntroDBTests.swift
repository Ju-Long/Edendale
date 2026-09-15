import Foundation
import Testing
@testable import Edendale

struct IntroDBTests {
    private let movie = IntroDBMedia(tmdbID: 278)!

    @Test func requestsContainOnlyCanonicalIdentityAndRuntime() throws {
        let media = try #require(IntroDBMedia(tmdbID: 1396, season: 2, episode: 8))
        let request = try #require(IntroDBRequest(media: media, duration: 2700.125))
        let url = try #require(request.urlRequest.url)
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        #expect(components.host == "api.theintrodb.org")
        #expect(components.path == "/v3/media")
        #expect(components.queryItems == [
            URLQueryItem(name: "tmdb_id", value: "1396"),
            URLQueryItem(name: "season", value: "2"),
            URLQueryItem(name: "episode", value: "8"),
            URLQueryItem(name: "duration_ms", value: "2700125")
        ])
        #expect(request.urlRequest.value(forHTTPHeaderField: "Authorization") == nil)
        let movieRequest = try #require(IntroDBRequest(media: movie, duration: 7200))
        #expect(movieRequest.urlRequest.url?.query == "tmdb_id=278&duration_ms=7200000")
    }

    @Test func unsupportedIdentityAndDurationNeverMakeARequest() {
        #expect(IntroDBMedia(tmdbID: 0) == nil)
        #expect(IntroDBMedia(tmdbID: 1396, season: 0, episode: 1) == nil)
        #expect(IntroDBMedia(tmdbID: 1396, season: 1) == nil)
        #expect(IntroDBMedia(tmdbID: 1396, episode: 1) == nil)
        for duration in [0, -1, Double.nan, Double.infinity, 21_601] {
            #expect(IntroDBRequest(media: movie, duration: duration) == nil)
        }
    }

    @MainActor
    @Test func playbackUsesShowIDAndRejectsUnidentifiedFilesAndSpecials() throws {
        let scope = PlaybackScope(playURL: URL(fileURLWithPath: "/example.mkv"), accessedURL: nil)
        let show = TVShow(name: "Example")
        show.tmdbId = 1396
        let episode = Episode(localTitle: "Episode", filePath: scope.url.path, seasonNumber: 2, episodeNumber: 8)
        episode.tmdbId = 62085
        episode.show = show
        let item = PlaybackItem(scope: scope, episode: episode)
        #expect(item.segmentLookup == IntroDBMedia(tmdbID: 1396, season: 2, episode: 8))
        episode.seasonNumber = 0
        #expect(item.segmentLookup == nil)
        #expect(PlaybackItem(scope: scope).segmentLookup == nil)
        let film = Movie(localTitle: "Movie", filePath: scope.url.path)
        film.tmdbId = 278
        #expect(PlaybackItem(scope: scope, movie: film).segmentLookup == movie)
    }

    @Test func decodesMultipleRangesAndPreservesCreditSceneGaps() throws {
        let request = try #require(IntroDBRequest(media: movie, duration: 120))
        let json = #"""
        {"tmdb_id":278,"type":"movie",
         "intro":[{"start_ms":null,"end_ms":10000}],
         "recap":[{"start_ms":20000,"end_ms":30000}],
         "credits":[{"start_ms":90000,"end_ms":100000},{"start_ms":110000,"end_ms":null}],
         "preview":[{"start_ms":30000,"end_ms":40000}]}
        """#
        let segments = try IntroDBService.decode(Data(json.utf8), for: request)
        #expect(segments.map(\.kind) == [.intro, .recap, .credits, .credits])
        #expect(segments.map(\.start) == [0, 20, 90, 110])
        #expect(segments.map(\.end) == [10, 30, 100, 120])
        #expect(segments.map(\.reachesEnd) == [false, false, false, true])
        #expect(!segments.contains { $0.contains(105) })
        #expect(segments[0].contains(0))
        #expect(!segments[0].contains(10))
    }

    @Test func ignoresNoSegmentInvalidAndOverlappingRanges() throws {
        let request = try #require(IntroDBRequest(media: movie, duration: 120))
        let json = #"""
        {"tmdb_id":278,"type":"movie",
         "intro":[{"start_ms":null,"end_ms":null},{"start_ms":null,"end_ms":0},
                  {"start_ms":-1000,"end_ms":3000},{"start_ms":5000,"end_ms":4000},
                  {"start_ms":10000,"end_ms":20000},{"start_ms":30000,"end_ms":40000},
                  {"start_ms":30000,"end_ms":40000},{"start_ms":100000,"end_ms":121000}],
         "recap":[{"start_ms":15000,"end_ms":25000}],
         "credits":[{"start_ms":null,"end_ms":110000},{"start_ms":0,"end_ms":null}]}
        """#
        let segments = try IntroDBService.decode(Data(json.utf8), for: request)
        #expect(segments == [PlaybackSegment(kind: .intro, start: 30, end: 40, reachesEnd: false)])
    }

    @Test func missingArraysAndMismatchedResponses() throws {
        let request = try #require(IntroDBRequest(media: movie, duration: 120))
        #expect(try IntroDBService.decode(Data(#"{"tmdb_id":278,"type":"movie"}"#.utf8), for: request).isEmpty)
        for json in [
            #"{"tmdb_id":279,"type":"movie"}"#,
            #"{"tmdb_id":278,"type":"tv","season":1,"episode":1}"#,
            #"{"tmdb_id":278,"type":"movie","season":1}"#,
            #"{"tmdb_id":278,"type":"movie","intro":"invalid"}"#
        ] {
            #expect(throws: (any Error).self) { try IntroDBService.decode(Data(json.utf8), for: request) }
        }
        let episode = IntroDBRequest(media: IntroDBMedia(tmdbID: 1396, season: 1, episode: 2)!, duration: 120)!
        #expect(throws: (any Error).self) {
            try IntroDBService.decode(Data(#"{"tmdb_id":1396,"type":"tv","season":1,"episode":3}"#.utf8), for: episode)
        }
    }

    @Test func notFoundProducesNoSegmentsAndRateLimitPreventsImmediateRetry() async throws {
        let request = try #require(IntroDBRequest(media: movie, duration: 120))
        let missing = IntroDBService { request in
            (Data(), HTTPURLResponse(url: request.url!, statusCode: 404, httpVersion: nil, headerFields: nil)!)
        }
        #expect(try await missing.segments(for: request).isEmpty)
        let counter = RequestCounter()
        let limited = IntroDBService { request in
            await counter.record(request)
            return (Data(), HTTPURLResponse(url: request.url!, statusCode: 429, httpVersion: nil,
                                            headerFields: ["X-UsageLimit-Reset": "3600"])!)
        }
        for _ in 0..<2 {
            do {
                _ = try await limited.segments(for: request)
                Issue.record("Expected rate limiting")
            } catch IntroDBError.rateLimited {} catch { Issue.record("Unexpected error: \(error)") }
        }
        #expect(await counter.count == 1)
    }

    private actor RequestCounter {
        private(set) var count = 0
        func record(_ request: URLRequest) { count += 1 }
    }
}

@MainActor
final class PlayerSegmentControllerTests {
    private var defaultsDomains: [String] = []

    deinit {
        for name in defaultsDomains { UserDefaults.standard.removePersistentDomain(forName: name) }
    }
    private let media = IntroDBMedia(tmdbID: 278)!
    private let intro = PlaybackSegment(kind: .intro, start: 5, end: 20, reachesEnd: false)

    @Test func legacyAutoSkipDoesNotEnableNetworkAndLookupWaitsForDuration() async throws {
        let defaults = makeDefaults()
        defaults.set(true, forKey: "player.skipRecap")
        defaults.set(true, forKey: "player.skipCredits")
        let stub = LookupStub(result: [intro])
        let controller = PlayerSegmentController(defaults: defaults) { try await stub.lookup($0) }
        defer { controller.end() }
        controller.begin(itemID: UUID(), media: media)
        controller.update(time: 10, duration: 120, isSeekable: true)
        #expect(!controller.isEnabled)
        #expect(await stub.requests.isEmpty)
        controller.update(time: 10, duration: nil, isSeekable: true)
        controller.isEnabled = true
        #expect(await stub.requests.isEmpty)
        controller.update(time: 10, duration: 120, isSeekable: true)
        try await waitForLookup(controller)
        #expect(controller.activeSegment == intro)
        #expect(defaults.bool(forKey: PlayerSegmentController.preferenceKey))
        #expect(await stub.requests.count == 1)
    }

    @Test func manualSkipRevalidatesTimeAndAllowsRewindWithoutRepeatedPresses() async throws {
        let intro = self.intro
        let controller = enabledController { _ in [intro] }
        defer { controller.end() }
        controller.begin(itemID: UUID(), media: media)
        controller.update(time: 10, duration: 120, isSeekable: true)
        try await waitForLookup(controller)
        #expect(controller.consumeSkip(at: 30, duration: 120, isSeekable: true) == nil)
        #expect(controller.consumeSkip(at: 10, duration: 120, isSeekable: false) == nil)
        #expect(controller.consumeSkip(at: 10, duration: 120, isSeekable: true) == .seek(20))
        #expect(controller.activeSegment == nil)
        #expect(controller.consumeSkip(at: 10, duration: 120, isSeekable: true) == nil)
        controller.update(time: 21, duration: 120, isSeekable: true)
        controller.update(time: 10, duration: 120, isSeekable: true)
        #expect(controller.activeSegment == intro)
    }

    @Test func onlyTerminalCreditsCompletePlayback() async throws {
        let controller = enabledController { _ in
            [PlaybackSegment(kind: .credits, start: 90, end: 100, reachesEnd: false),
             PlaybackSegment(kind: .credits, start: 110, end: 120, reachesEnd: true)]
        }
        defer { controller.end() }
        controller.begin(itemID: UUID(), media: media)
        controller.update(time: 95, duration: 120, isSeekable: true)
        try await waitForLookup(controller)
        #expect(controller.consumeSkip(at: 95, duration: 120, isSeekable: true) == .seek(100))
        #expect(controller.consumeSkip(at: 105, duration: 120, isSeekable: true) == nil)
        #expect(controller.consumeSkip(at: 115, duration: 120, isSeekable: true) == .finish)
    }

    @Test func lookupIsDeduplicatedAndCacheSeparatesRuntimesAndEndsWithSession() async throws {
        let stub = LookupStub(result: [])
        let controller = enabledController { try await stub.lookup($0) }
        for duration in [120.0, 120.0, 125.0] {
            controller.begin(itemID: UUID(), media: media)
            for time in 0..<30 { controller.update(time: Double(time), duration: duration, isSeekable: true) }
            try await waitForLookup(controller)
        }
        #expect(await stub.requests.count == 2)
        controller.end()
        controller.begin(itemID: UUID(), media: media)
        controller.update(time: 10, duration: 120, isSeekable: true)
        try await waitForLookup(controller)
        #expect(await stub.requests.count == 3)
        controller.end()
    }

    @Test func failuresDoNotRetryOnTimeEventsOrLeavePrompts() async throws {
        let stub = LookupStub(result: [], fails: true)
        let controller = enabledController { try await stub.lookup($0) }
        defer { controller.end() }
        controller.begin(itemID: UUID(), media: media)
        controller.update(time: 10, duration: 120, isSeekable: true)
        try await waitForLookup(controller)
        for time in 11..<30 { controller.update(time: Double(time), duration: 120, isSeekable: true) }
        #expect(await stub.requests.count == 1)
        #expect(controller.activeSegment == nil)
    }

    @Test func lateResponsesCannotAffectNewItemsDisabledSettingsOrEndedSessions() async throws {
        for action in ["switch", "disable", "end"] {
            let gate = LookupGate()
            let controller = enabledController { await gate.lookup($0) }
            controller.begin(itemID: UUID(), media: media)
            controller.update(time: 10, duration: 120, isSeekable: true)
            for _ in 0..<200 {
                if await gate.started { break }
                try await Task.sleep(for: .milliseconds(5))
            }
            #expect(await gate.started)
            switch action {
            case "switch": controller.begin(itemID: UUID(), media: nil)
            case "disable": controller.isEnabled = false
            default: controller.end()
            }
            await gate.finish([intro]) // Deliberately ignores cancellation.
            for _ in 0..<20 { await Task.yield() }
            #expect(controller.segments.isEmpty)
            #expect(controller.activeSegment == nil)
            controller.end()
        }
    }

    private func makeDefaults() -> UserDefaults {
        let name = "IntroDBTests-\(UUID())"
        defaultsDomains.append(name)
        return UserDefaults(suiteName: name)!
    }

    private func enabledController(_ lookup: @escaping PlayerSegmentController.Lookup) -> PlayerSegmentController {
        let controller = PlayerSegmentController(defaults: makeDefaults(), lookup: lookup)
        controller.isEnabled = true
        return controller
    }

    private func waitForLookup(_ controller: PlayerSegmentController) async throws {
        for _ in 0..<200 {
            if !controller.isLoading { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        Issue.record("Segment lookup did not finish")
    }

    private actor LookupStub {
        let result: [PlaybackSegment]
        let fails: Bool
        private(set) var requests: [IntroDBRequest] = []
        init(result: [PlaybackSegment], fails: Bool = false) {
            self.result = result
            self.fails = fails
        }
        func lookup(_ request: IntroDBRequest) throws -> [PlaybackSegment] {
            requests.append(request)
            if fails { throw URLError(.notConnectedToInternet) }
            return result
        }
    }

    private actor LookupGate {
        private var continuation: CheckedContinuation<[PlaybackSegment], Never>?
        var started: Bool { continuation != nil }
        func lookup(_ request: IntroDBRequest) async -> [PlaybackSegment] {
            await withCheckedContinuation { continuation = $0 }
        }
        func finish(_ result: [PlaybackSegment]) {
            continuation?.resume(returning: result)
            continuation = nil
        }
    }
}
