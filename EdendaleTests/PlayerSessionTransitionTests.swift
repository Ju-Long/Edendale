import Foundation
import SwiftData
import Testing
import SwiftVLC
@testable import Edendale

// Exercise real session requests without attaching a video surface or starting
// media output. Native transport tests are separate from this request ordering.
@MainActor
struct PlayerSessionTransitionTests {
    @Test func manualSelectionWinsOverQueuedAutomaticAdvance() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.advanceToNextOrEnd()
        await fixture.session.play(fileURL: fixture.manualURL)
        for _ in 0..<20 { await Task.yield() }
        #expect(fixture.session.item?.url == fixture.manualURL)
        #expect(fixture.session.item?.episode == nil)
    }

    @Test func endingSessionCancelsQueuedAutomaticAdvance() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.advanceToNextOrEnd()
        fixture.session.end()
        for _ in 0..<20 { await Task.yield() }
        #expect(fixture.session.item == nil)
        #expect(fixture.session.player == nil)
    }

    @Test func duplicateAdvanceRequestsReachOnlyTheNextSeason() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.advanceToNextOrEnd()
        fixture.session.advanceToNextOrEnd()
        for _ in 0..<100 {
            if fixture.session.item?.episode?.id == fixture.second.id { break }
            try await Task.sleep(for: .milliseconds(5))
        }
        #expect(fixture.session.item?.episode?.id == fixture.second.id)
        #expect(fixture.session.item?.errorMessage == nil)
    }

    @Test func naturalEndStartsNextEpisodeAndPreservesCompletion() async throws {
        let fixture = try Fixture(nativeMedia: true)
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        for _ in 0..<200 {
            if fixture.session.item?.episode?.id == fixture.second.id { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        #expect(fixture.session.item?.episode?.id == fixture.second.id)
        let completed = fixture.watchStore.progress(for: fixture.first.tmdbId!, mediaType: .episode)
        #expect(completed?.isCompleted == true)
        #expect(completed?.position == 1)
        // Ending the next item must not rewrite the previous completion.
        fixture.session.end()
        #expect(fixture.watchStore.progress(for: fixture.first.tmdbId!, mediaType: .episode)?.isCompleted == true)
    }

    @Test func terminalCreditsSkipAdvancesOnceAndPreservesCompletion() async throws {
        let name = "SegmentSessionTests-\(UUID())"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let segments = PlayerSegmentController(defaults: defaults) { request in
            [PlaybackSegment(kind: .credits, start: 0.1, end: request.duration, reachesEnd: true)]
        }
        segments.isEnabled = true
        let fixture = try Fixture(nativeMedia: true, segmentSkipping: segments)
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        for _ in 0..<100 {
            if segments.activeSegment != nil { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(segments.activeSegment != nil)
        fixture.session.skipCurrentSegment()
        fixture.session.skipCurrentSegment()
        for _ in 0..<100 {
            if fixture.session.item?.episode?.id == fixture.second.id { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(fixture.session.item?.episode?.id == fixture.second.id)
        let progress = fixture.watchStore.progress(for: fixture.first.tmdbId!, mediaType: .episode)
        #expect(progress?.isCompleted == true)
        #expect(progress?.position == 1)
        fixture.session.end()
        #expect(fixture.watchStore.progress(for: fixture.first.tmdbId!, mediaType: .episode)?.isCompleted == true)
    }

    @Test func boundedCreditsSeekKeepsPausedPlaybackAndResumePosition() async throws {
        let name = "SegmentSessionTests-\(UUID())"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let segments = PlayerSegmentController(defaults: defaults) { _ in
            [PlaybackSegment(kind: .credits, start: 0.1, end: 10, reachesEnd: false)]
        }
        segments.isEnabled = true
        let fixture = try Fixture(nativeMedia: true, segmentSkipping: segments)
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.second)
        fixture.session.surfaceDidAttach()
        for _ in 0..<100 {
            if segments.activeSegment != nil { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(segments.activeSegment != nil)
        let player = try #require(fixture.session.player)
        player.pause()
        fixture.session.skipCurrentSegment()
        #expect(player.currentTime == .seconds(10))
        #expect(!player.isPlaying)
        #expect(fixture.session.item?.episode?.id == fixture.second.id)
        fixture.session.end()
        let progress = fixture.watchStore.progress(for: fixture.second.tmdbId!, mediaType: .episode)
        #expect(progress?.isCompleted == false)
        #expect(abs((progress?.position ?? 0) - 1.0 / 3.0) < 0.01)
    }

    @Test func terminalCreditsSkipHonorsLoopWithoutAdvancingEpisode() async throws {
        let name = "SegmentSessionTests-\(UUID())"
        let defaults = UserDefaults(suiteName: name)!
        defer { defaults.removePersistentDomain(forName: name) }
        let segments = PlayerSegmentController(defaults: defaults) { request in
            [PlaybackSegment(kind: .credits, start: 0.1, end: request.duration, reachesEnd: true)]
        }
        segments.isEnabled = true
        let fixture = try Fixture(nativeMedia: true, segmentSkipping: segments)
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        fixture.session.chrome?.loopEnabled = true
        for _ in 0..<100 {
            if segments.activeSegment != nil { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(segments.activeSegment != nil)
        fixture.session.skipCurrentSegment()
        for _ in 0..<20 { await Task.yield() }
        #expect(fixture.session.item?.episode?.id == fixture.first.id)
        #expect(fixture.session.chrome?.loopEnabled == true)
        #expect(segments.activeSegment == nil)
    }

    @MainActor
    private final class Fixture {
        let directory: URL
        let manualURL: URL
        let first: Episode
        let second: Episode
        let session: PlayerSession
        let watchStore: WatchProgressStore
        // Keep the model container and show alive throughout async preparation.
        let container: ModelContainer
        let show: TVShow

        init(nativeMedia: Bool = false, segmentSkipping: PlayerSegmentController? = nil) throws {
            directory = FileManager.default.temporaryDirectory
                .appendingPathComponent("SessionTransition-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let firstURL = directory.appendingPathComponent("S01E02.mkv")
            let secondURL = directory.appendingPathComponent("S02E01.mkv")
            manualURL = directory.appendingPathComponent("Manual.mkv")
            for url in [firstURL, secondURL, manualURL] { try Data().write(to: url) }
            if nativeMedia {
                try Self.silentWave(seconds: 3).write(to: firstURL)
                try Self.silentWave(seconds: 30).write(to: secondURL)
            }
            let schema = Schema([VideoFolder.self, Movie.self, TVShow.self, Episode.self])
            let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
            container = try ModelContainer(for: schema, configurations: [configuration])
            show = TVShow(name: "Session tests")
            first = Episode(localTitle: "First", filePath: firstURL.path, seasonNumber: 1, episodeNumber: 2)
            second = Episode(localTitle: "Second", filePath: secondURL.path, seasonNumber: 2, episodeNumber: 1)
            show.tmdbId = Int.random(in: 1_000_000...9_000_000)
            first.tmdbId = show.tmdbId! * 10 + 1
            second.tmdbId = show.tmdbId! * 10 + 2
            show.episodes = [second, first]
            first.show = show
            second.show = show
            let instance = try VLCInstance(arguments: [
                "--ignore-config", "--no-video", "--aout=dummy", "--no-stats"
            ])
            watchStore = WatchProgressStore()
            session = PlayerSession(
                library: LibraryController(modelContext: container.mainContext),
                watchStore: watchStore,
                playerFactory: { Player(instance: instance) },
                segmentSkipping: segmentSkipping ?? PlayerSegmentController(lookup: { _ in [] })
            )
        }

        private static func silentWave(seconds: Int) -> Data {
            let samples = seconds * 8_000
            var data = Data("RIFF".utf8)
            func append<T: FixedWidthInteger>(_ value: T) {
                var littleEndian = value.littleEndian
                withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
            }
            append(UInt32(36 + samples * 2))
            data.append(Data("WAVEfmt ".utf8))
            append(UInt32(16))
            append(UInt16(1))
            append(UInt16(1))
            append(UInt32(8_000))
            append(UInt32(16_000))
            append(UInt16(2))
            append(UInt16(16))
            data.append(Data("data".utf8))
            append(UInt32(samples * 2))
            data.append(Data(repeating: 0, count: samples * 2))
            return data
        }

        func cleanup() {
            session.end()
            try? FileManager.default.removeItem(at: directory)
        }
    }
}
