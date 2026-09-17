import Foundation
import SwiftData
import Testing
@testable import Edendale

@MainActor
struct UpcomingEpisodePreviewTests {

    // MARK: - Eligibility: appears within threshold

    @Test func showsPreviewWithin30SecondsOfEnd() throws {
        let f = try Fixture()
        let result = PlayerLogic.upcomingEpisode(
            time: .seconds(272), duration: .seconds(300),
            loopEnabled: false, episode: f.first, show: f.show
        )
        #expect(result?.id == f.second.id)
    }

    @Test func noPreviewBeforeThreshold() throws {
        let f = try Fixture()
        let result = PlayerLogic.upcomingEpisode(
            time: .seconds(269), duration: .seconds(300),
            loopEnabled: false, episode: f.first, show: f.show
        )
        #expect(result == nil)
    }

    @Test func clearsWhenSeekingBack() throws {
        let f = try Fixture()
        let inWindow = PlayerLogic.upcomingEpisode(
            time: .seconds(275), duration: .seconds(300),
            loopEnabled: false, episode: f.first, show: f.show
        )
        #expect(inWindow?.id == f.second.id)
        let seekedBack = PlayerLogic.upcomingEpisode(
            time: .seconds(200), duration: .seconds(300),
            loopEnabled: false, episode: f.first, show: f.show
        )
        #expect(seekedBack == nil)
    }

    @Test func reappearsAfterSeekingBackThenForward() throws {
        let f = try Fixture()
        let back = PlayerLogic.upcomingEpisode(
            time: .seconds(100), duration: .seconds(300),
            loopEnabled: false, episode: f.first, show: f.show
        )
        #expect(back == nil)
        let forward = PlayerLogic.upcomingEpisode(
            time: .seconds(280), duration: .seconds(300),
            loopEnabled: false, episode: f.first, show: f.show
        )
        #expect(forward?.id == f.second.id)
    }

    // MARK: - Suppression

    @Test func suppressedWhenLoopEnabled() throws {
        let f = try Fixture()
        let result = PlayerLogic.upcomingEpisode(
            time: .seconds(280), duration: .seconds(300),
            loopEnabled: true, episode: f.first, show: f.show
        )
        #expect(result == nil)
    }

    @Test func reappearsWhenLoopDisabled() throws {
        let f = try Fixture()
        let looping = PlayerLogic.upcomingEpisode(
            time: .seconds(280), duration: .seconds(300),
            loopEnabled: true, episode: f.first, show: f.show
        )
        #expect(looping == nil)
        let notLooping = PlayerLogic.upcomingEpisode(
            time: .seconds(280), duration: .seconds(300),
            loopEnabled: false, episode: f.first, show: f.show
        )
        #expect(notLooping?.id == f.second.id)
    }

    @Test func suppressedForMovies() throws {
        let result = PlayerLogic.upcomingEpisode(
            time: .seconds(280), duration: .seconds(300),
            loopEnabled: false, episode: nil, show: nil
        )
        #expect(result == nil)
    }

    @Test func suppressedForUnknownMedia() throws {
        let result = PlayerLogic.upcomingEpisode(
            time: .seconds(280), duration: .seconds(300),
            loopEnabled: false, episode: nil, show: nil
        )
        #expect(result == nil)
    }

    @Test func suppressedWhenNoSuccessor() throws {
        let f = try Fixture()
        let result = PlayerLogic.upcomingEpisode(
            time: .seconds(280), duration: .seconds(300),
            loopEnabled: false, episode: f.lastEpisode, show: f.show
        )
        #expect(result == nil)
    }

    @Test func suppressedWhenNilDuration() throws {
        let f = try Fixture()
        let result = PlayerLogic.upcomingEpisode(
            time: .seconds(280), duration: nil,
            loopEnabled: false, episode: f.first, show: f.show
        )
        #expect(result == nil)
    }

    @Test func suppressedWhenZeroDuration() throws {
        let f = try Fixture()
        let result = PlayerLogic.upcomingEpisode(
            time: .seconds(0), duration: .zero,
            loopEnabled: false, episode: f.first, show: f.show
        )
        #expect(result == nil)
    }

    // MARK: - Boundary: exactly at threshold

    @Test func exactlyAtThresholdBoundary() throws {
        let f = try Fixture()
        let result = PlayerLogic.upcomingEpisode(
            time: .seconds(270), duration: .seconds(300),
            loopEnabled: false, episode: f.first, show: f.show
        )
        #expect(result?.id == f.second.id)
    }

    @Test func justBeforeThreshold() throws {
        let f = try Fixture()
        let result = PlayerLogic.upcomingEpisode(
            time: .milliseconds(269_900), duration: .seconds(300),
            loopEnabled: false, episode: f.first, show: f.show
        )
        #expect(result == nil)
    }

    @Test func atExactEnd() throws {
        let f = try Fixture()
        let result = PlayerLogic.upcomingEpisode(
            time: .seconds(300), duration: .seconds(300),
            loopEnabled: false, episode: f.first, show: f.show
        )
        #expect(result == nil)
    }

    // MARK: - Short episodes still eligible

    @Test func shortEpisodeShowsPreview() throws {
        let f = try Fixture()
        let result = PlayerLogic.upcomingEpisode(
            time: .seconds(35), duration: .seconds(60),
            loopEnabled: false, episode: f.shortEpisode, show: f.shortShow
        )
        #expect(result?.id == f.shortSecond.id)
    }

    // MARK: - Integration with chrome model

    @Test func chromeModelClearsOnMediaSwitch() throws {
        let f = try Fixture()
        f.session.present(f.firstItem)
        let chrome = try #require(f.session.chrome)
        f.session.present(f.lastEpisodeItem)
        #expect(chrome.upcomingEpisode == nil)
        f.session.end()
    }

    @Test func pausedSeeksAndLoopChangesRefreshPreviewImmediately() async throws {
        let f = try Fixture(nativeMedia: true)
        defer { f.session.end() }
        f.session.present(f.firstItem)
        f.session.surfaceDidAttach()
        let player = try #require(f.session.player)
        let chrome = try #require(f.session.chrome)
        for _ in 0..<300 {
            if player.state == .playing, player.isSeekable,
               player.currentTime > .zero, player.duration != nil { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        try #require(player.state == .playing)
        try #require(player.isSeekable)
        try #require(player.duration != nil)
        chrome.togglePlayPause()
        for _ in 0..<300 {
            if player.state == .paused { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        try #require(player.state == .paused)

        chrome.seek(toPosition: 0.8)
        #expect(chrome.upcomingEpisode?.id == f.second.id)
        chrome.loopEnabled = true
        #expect(chrome.upcomingEpisode == nil)
        chrome.loopEnabled = false
        #expect(chrome.upcomingEpisode?.id == f.second.id)
        chrome.seek(toPosition: 0.2)
        #expect(chrome.upcomingEpisode == nil)
        chrome.seek(bySeconds: 55)
        #expect(chrome.upcomingEpisode?.id == f.second.id)
        f.session.end()
        #expect(chrome.upcomingEpisode == nil)
        let progress = try #require(f.watchStore.progress(for: f.first.tmdbId!, mediaType: .episode))
        #expect(abs(progress.position - 73.0 / 90.0) < 0.02)
    }

    // MARK: - Fixture

    @MainActor
    final class Fixture {
        let first: Episode
        let second: Episode
        let lastEpisode: Episode
        let shortEpisode: Episode
        let shortSecond: Episode
        let show: TVShow
        let shortShow: TVShow
        let session: PlayerSession
        let watchStore: WatchProgressStore
        let firstItem: PlaybackItem
        let lastEpisodeItem: PlaybackItem
        private let container: ModelContainer
        private let directory: URL

        init(nativeMedia: Bool = false) throws {
            directory = FileManager.default.temporaryDirectory
                .appendingPathComponent("UpNext-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let firstURL = directory.appendingPathComponent("S01E01.mkv")
            let secondURL = directory.appendingPathComponent("S01E02.mkv")
            let lastURL = directory.appendingPathComponent("S01E10.mkv")
            let shortURL = directory.appendingPathComponent("Short.mkv")
            for url in [firstURL, secondURL, lastURL, shortURL] {
                try (nativeMedia ? Self.silentWave(seconds: 90) : Data()).write(to: url)
            }

            let schema = Schema([VideoFolder.self, Movie.self, TVShow.self, Episode.self])
            let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
            container = try ModelContainer(for: schema, configurations: [config])

            show = TVShow(name: "UpNext tests")
            show.tmdbId = Int.random(in: 1_000_000...9_000_000)
            first = Episode(localTitle: "First", filePath: firstURL.path, seasonNumber: 1, episodeNumber: 1)
            first.tmdbId = show.tmdbId! * 10 + 1
            second = Episode(localTitle: "Second", filePath: secondURL.path, seasonNumber: 1, episodeNumber: 2)
            second.tmdbId = show.tmdbId! * 10 + 2
            lastEpisode = Episode(localTitle: "Last", filePath: lastURL.path, seasonNumber: 1, episodeNumber: 10)
            lastEpisode.tmdbId = show.tmdbId! * 10 + 10
            show.episodes = [first, second, lastEpisode]
            first.show = show
            second.show = show
            lastEpisode.show = show

            shortShow = TVShow(name: "Short UpNext tests")
            shortShow.tmdbId = Int.random(in: 1_000_000...9_000_000)
            shortEpisode = Episode(localTitle: "Short Ep", filePath: shortURL.path, seasonNumber: 1, episodeNumber: 1)
            shortEpisode.tmdbId = shortShow.tmdbId! * 10 + 1
            shortSecond = Episode(localTitle: "Short Ep 2", filePath: secondURL.path, seasonNumber: 1, episodeNumber: 2)
            shortSecond.tmdbId = shortShow.tmdbId! * 10 + 2
            shortShow.episodes = [shortEpisode, shortSecond]
            shortEpisode.show = shortShow
            shortSecond.show = shortShow

            watchStore = WatchProgressStore()
            session = PlayerSession(
                library: LibraryController(modelContext: container.mainContext),
                watchStore: watchStore,
                segmentSkipping: PlayerSegmentController(lookup: { _ in [] })
            )
            firstItem = PlaybackItem(
                scope: PlaybackScope(playURL: firstURL, accessedURL: nil),
                episode: first
            )
            lastEpisodeItem = PlaybackItem(
                scope: PlaybackScope(playURL: lastURL, accessedURL: nil),
                episode: lastEpisode
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

        deinit {
            try? FileManager.default.removeItem(at: directory)
        }
    }
}
