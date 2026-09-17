import Foundation
import SwiftData
import Testing
import SwiftVLC
@testable import Edendale

@MainActor
struct PlayerTransportStateTests {

    // MARK: - BUG-05: play/pause icon stays correct across file switches

    @Test func playerReportsPlayingAfterStartAndTimeAdvances() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        try await fixture.waitForPlaying()
        let initialTime = fixture.session.player!.currentTime
        try await fixture.waitForTime(after: initialTime)
        let laterTime = fixture.session.player!.currentTime
        #expect(laterTime > initialTime)
    }

    @Test func pauseAndResumeReflectsInIsPlaying() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        try await fixture.waitForPlaying()
        #expect(fixture.session.player?.isPlaying == true)

        fixture.session.chrome?.togglePlayPause()
        try await fixture.waitForPaused()
        #expect(fixture.session.player?.isPlaying == false)

        fixture.session.chrome?.togglePlayPause()
        try await fixture.waitForPlaying()
        #expect(fixture.session.player?.isPlaying == true)
    }

    @Test func playerShowsPlayingAfterFileSwitch() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        try await fixture.waitForPlaying()

        // Switch to second episode on same player
        await fixture.session.play(episode: fixture.second)
        try await fixture.waitForPlaying()
        #expect(fixture.session.player?.isPlaying == true)

        // Time should advance on the new file
        let t1 = fixture.session.player!.currentTime
        try await fixture.waitForTime(after: t1)
        #expect(fixture.session.player!.currentTime > t1)
    }

    @Test func pauseWorksAfterFileSwitch() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        try await fixture.waitForPlaying()

        await fixture.session.play(episode: fixture.second)
        try await fixture.waitForPlaying()

        fixture.session.chrome?.togglePlayPause()
        try await fixture.waitForPaused()
        #expect(fixture.session.player?.isPlaying == false)

        fixture.session.chrome?.togglePlayPause()
        try await fixture.waitForPlaying()
        #expect(fixture.session.player?.isPlaying == true)
    }

    @Test func switchFromPausedShowsPlaying() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        try await fixture.waitForPlaying()

        fixture.session.chrome?.togglePlayPause()
        try await fixture.waitForPaused()

        await fixture.session.play(episode: fixture.second)
        try await fixture.waitForPlaying()
        #expect(fixture.session.player?.isPlaying == true)
    }

    // MARK: - Player reuse preserves identity

    @Test func samePlayerReusedAcrossSwitch() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        let firstPlayer = fixture.session.player
        #expect(firstPlayer != nil)

        await fixture.session.play(episode: fixture.second)
        #expect(fixture.session.player === firstPlayer)
    }

    // MARK: - Session settings carry over

    @Test func volumeAndMutePreservedAcrossSwitch() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        try await fixture.waitForPlaying()

        fixture.session.player?.volume = 0.42
        fixture.session.player?.isMuted = true

        await fixture.session.play(episode: fixture.second)
        try await fixture.waitForPlaying()
        let player = try #require(fixture.session.player)
        #expect(abs(player.volume - 0.42) < 0.05)
        #expect(player.isMuted == true)
    }

    @Test func loopSettingPreservedAcrossSwitch() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.chrome?.loopEnabled = true

        await fixture.session.play(episode: fixture.second)
        #expect(fixture.session.chrome?.loopEnabled == true)
    }

    @Test func ratePreservedAcrossSwitch() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.chrome?.setBaseRate(1.5)

        await fixture.session.play(episode: fixture.second)
        #expect(fixture.session.chrome?.baseRate == 1.5)
    }

    // MARK: - Progress saved on switch

    @Test func progressSavedBeforeSwitch() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        try await fixture.waitForPlaying()
        // Let some time pass for progress
        try await Task.sleep(for: .milliseconds(500))

        await fixture.session.play(episode: fixture.second)
        let progress = fixture.watchStore.progress(
            for: fixture.first.tmdbId!, mediaType: .episode
        )
        #expect(progress != nil)
        #expect(progress!.position > 0)
    }

    // MARK: - Natural auto-next

    @Test func naturalEndAdvancesToNextEpisode() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        for _ in 0..<200 {
            if fixture.session.item?.episode?.id == fixture.second.id { break }
            try await Task.sleep(for: .milliseconds(50))
        }
        #expect(fixture.session.item?.episode?.id == fixture.second.id)
        let completed = fixture.watchStore.progress(
            for: fixture.first.tmdbId!, mediaType: .episode
        )
        #expect(completed?.isCompleted == true)
        try await fixture.waitForPlaying()
        let nextTime = fixture.session.player!.currentTime
        try await fixture.waitForTime(after: nextTime)
    }

    // MARK: - Rapid switch safety

    @Test func rapidSwitchesEndInCorrectItem() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        try await fixture.waitForPlaying()

        await fixture.session.play(episode: fixture.second)
        await fixture.session.play(episode: fixture.first)
        await fixture.session.play(episode: fixture.second)
        try await fixture.waitForPlaying()
        #expect(fixture.session.item?.episode?.id == fixture.second.id)
    }

    @Test func endDuringStopWaitCleansUp() async throws {
        let fixture = try Fixture()
        defer { fixture.cleanup() }
        await fixture.session.play(episode: fixture.first)
        fixture.session.surfaceDidAttach()
        try await fixture.waitForPlaying()

        fixture.session.end()
        #expect(fixture.session.item == nil)
        #expect(fixture.session.player == nil)
    }

    // MARK: - Fixture

    @MainActor
    private final class Fixture {
        let directory: URL
        let first: Episode
        let second: Episode
        let session: PlayerSession
        let watchStore: WatchProgressStore
        let container: ModelContainer
        let show: TVShow

        init() throws {
            directory = FileManager.default.temporaryDirectory
                .appendingPathComponent("TransportState-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let firstURL = directory.appendingPathComponent("S01E01.wav")
            let secondURL = directory.appendingPathComponent("S01E02.wav")
            try Self.silentWave(seconds: 3).write(to: firstURL)
            try Self.silentWave(seconds: 30).write(to: secondURL)

            let schema = Schema([VideoFolder.self, Movie.self, TVShow.self, Episode.self])
            let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
            container = try ModelContainer(for: schema, configurations: [config])
            show = TVShow(name: "Transport tests")
            first = Episode(localTitle: "Ep 1", filePath: firstURL.path, seasonNumber: 1, episodeNumber: 1)
            second = Episode(localTitle: "Ep 2", filePath: secondURL.path, seasonNumber: 1, episodeNumber: 2)
            show.tmdbId = Int.random(in: 1_000_000...9_000_000)
            first.tmdbId = show.tmdbId! * 10 + 1
            second.tmdbId = show.tmdbId! * 10 + 2
            show.episodes = [first, second]
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
                segmentSkipping: PlayerSegmentController(lookup: { _ in [] })
            )
        }

        func waitForPlaying(timeout: Int = 300) async throws {
            for _ in 0..<timeout {
                let durationMatches = session.item?.episode?.id == second.id
                    ? (session.player?.duration?.playbackSeconds ?? 0) > 10
                    : (session.player?.duration?.playbackSeconds ?? 0) > 0
                if session.player?.isPlaying == true,
                   session.player?.state == .playing,
                   (session.player?.currentTime ?? .zero) > .zero,
                   durationMatches { return }
                try await Task.sleep(for: .milliseconds(10))
            }
            try #require(session.player?.state == .playing)
            try #require(session.player?.isPlaying == true)
            try #require((session.player?.currentTime ?? .zero) > .zero)
            if session.item?.episode?.id == second.id {
                try #require((session.player?.duration?.playbackSeconds ?? 0) > 10)
            }
        }

        func waitForTime(after previous: Duration) async throws {
            for _ in 0..<200 {
                if (session.player?.currentTime ?? .zero) > previous { return }
                try await Task.sleep(for: .milliseconds(10))
            }
            try #require((session.player?.currentTime ?? .zero) > previous)
        }

        func waitForPaused(timeout: Int = 300) async throws {
            for _ in 0..<timeout {
                if session.player?.isPlaying == false,
                   session.player?.state == .paused { return }
                try await Task.sleep(for: .milliseconds(10))
            }
            try #require(session.player?.state == .paused)
            try #require(session.player?.isPlaying == false)
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
