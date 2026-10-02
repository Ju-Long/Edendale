//
//  SubtitleCacheTests.swift
//  EdendaleTests
//
//  Downloaded subtitles kept per video: a key that doesn't depend on the
//  address the video is read from, reuse of kept files, and the launch
//  pruning of subtitles unused for more than a month.
//

import Foundation
import SwiftData
import Testing
@testable import Edendale

@MainActor
struct SubtitleCacheTests {

    // MARK: - Video key

    @Test func videoKeyIgnoresTheAddressTheFileIsReadFrom() throws {
        let name = "Inception.2010.1080p.BluRay.mkv"
        let lan = try movieItem(tmdbID: 27205, url: "smb://192.168.1.20/Media/Movies/\(name)")
        let tailscale = try movieItem(tmdbID: 27205, url: "smb://100.101.102.103:445/Media/Movies/\(name)")
        let hostName = try movieItem(tmdbID: 27205, url: "smb://nas.local/Share/Other%20Folder/\(name)")
        let sftp = try movieItem(tmdbID: 27205, url: "sftp://nas.tail1234.ts.net:22/volume1/Movies/\(name)")
        let local = movieItem(tmdbID: 27205, fileURL: URL(fileURLWithPath: "/Volumes/Media/\(name)"))

        let key = try #require(lan.subtitleVideoKey)
        #expect(tailscale.subtitleVideoKey == key)
        #expect(hostName.subtitleVideoKey == key)
        #expect(sftp.subtitleVideoKey == key)
        #expect(local.subtitleVideoKey == key)
        // Nothing about the server is kept.
        #expect(!key.contains("192.168"))
        #expect(!key.contains("100.101"))
    }

    @Test func videoKeyIgnoresCaseAndUnicodeForm() throws {
        // "Amélie" with a precomposed é, then "AMÉLIE" with a combining accent.
        let precomposed = try movieItem(tmdbID: 194, url: "smb://10.0.0.2/Films/Am%C3%A9lie.2001.mkv")
        let decomposed = try movieItem(tmdbID: 194, url: "smb://10.0.0.3/Films/AME%CC%81LIE.2001.MKV")
        #expect(precomposed.subtitleVideoKey != nil)
        #expect(precomposed.subtitleVideoKey == decomposed.subtitleVideoKey)
    }

    @Test func videoKeySeparatesReleasesTitlesAndEpisodes() throws {
        let release = try movieItem(tmdbID: 27205, url: "smb://nas/Movies/Inception.1080p.mkv")
        let otherRelease = try movieItem(tmdbID: 27205, url: "smb://nas/Movies/Inception.2160p.mkv")
        let otherTitle = try movieItem(tmdbID: 155, url: "smb://nas/Movies/Inception.1080p.mkv")
        #expect(release.subtitleVideoKey != otherRelease.subtitleVideoKey)
        #expect(release.subtitleVideoKey != otherTitle.subtitleVideoKey)

        let first = try episodeItem(showID: 1396, season: 1, episode: 1, url: "smb://nas/TV/S01E01.mkv")
        let firstElsewhere = try episodeItem(showID: 1396, season: 1, episode: 1, url: "smb://100.64.0.9/TV/S01E01.mkv")
        let second = try episodeItem(showID: 1396, season: 1, episode: 2, url: "smb://nas/TV/S01E01.mkv")
        #expect(first.subtitleVideoKey == firstElsewhere.subtitleVideoKey)
        #expect(first.subtitleVideoKey != second.subtitleVideoKey)
        // A show and a movie can share a TMDB number.
        let movie = try movieItem(tmdbID: 1396, url: "smb://nas/TV/S01E01.mkv")
        #expect(first.subtitleVideoKey != movie.subtitleVideoKey)
    }

    @Test func unmatchedFilesHaveNoVideoKey() {
        let movie = Movie(localTitle: "Unknown", filePath: "/tmp/Unknown.mkv")
        let item = PlaybackItem(
            scope: PlaybackScope(playURL: URL(fileURLWithPath: "/tmp/Unknown.mkv"), accessedURL: nil),
            movie: movie
        )
        #expect(item.subtitleVideoKey == nil)
        #expect(PlaybackItem(failed: "No file").subtitleVideoKey == nil)
    }

    // MARK: - Keeping and reusing

    @Test func downloadedSubtitleIsKeptForItsVideo() throws {
        let harness = try Harness()
        defer { harness.cleanUp() }
        let subtitle = wyzie(id: "abc123", release: "Inception.2010.1080p.BluRay")
        let data = Data("1\n00:00:01,000 --> 00:00:02,000\nHello\n".utf8)

        #expect(harness.store.existingFile(for: subtitle) == nil)
        let url = try harness.store.save(data, for: subtitle)
        harness.store.remember(subtitle, videoKey: "movie:27205/inception.mkv")
        harness.store.remember(subtitle, videoKey: "movie:27205/inception.mkv")

        let kept = harness.store.subtitles(for: "movie:27205/inception.mkv")
        #expect(kept.count == 1)
        let record = try #require(kept.first)
        #expect(record.subtitleID == "abc123")
        #expect(record.displayName == "English · Inception.2010.1080p.BluRay")
        // The record holds a name inside the folder, not a container path.
        #expect(record.fileName == url.lastPathComponent)
        #expect(harness.store.fileURL(for: record) == url)
        #expect(try Data(contentsOf: harness.store.fileURL(for: record)) == data)
        #expect(harness.store.existingFile(for: subtitle) == url)
        #expect(harness.store.subtitles(for: "movie:27205/other.mkv").isEmpty)
    }

    @Test func mostRecentlyUsedSubtitleComesFirst() throws {
        let harness = try Harness()
        defer { harness.cleanUp() }
        let key = "tv:1396:s1e1/s01e01.mkv"
        let older = wyzie(id: "older")
        let newer = wyzie(id: "newer")
        _ = try harness.store.save(Data("a".utf8), for: older)
        _ = try harness.store.save(Data("b".utf8), for: newer)
        harness.store.remember(older, videoKey: key)
        harness.clock.advance(days: 1)
        harness.store.remember(newer, videoKey: key)
        #expect(harness.store.subtitles(for: key).map(\.subtitleID) == ["newer", "older"])

        harness.clock.advance(days: 1)
        let olderRecord = try #require(harness.store.subtitles(for: key).last)
        harness.store.markUsed([olderRecord])
        #expect(harness.store.subtitles(for: key).map(\.subtitleID) == ["older", "newer"])
    }

    @Test func subtitleWhoseFileWasPurgedIsNotOffered() throws {
        let harness = try Harness()
        defer { harness.cleanUp() }
        let subtitle = wyzie(id: "purged")
        let url = try harness.store.save(Data("x".utf8), for: subtitle)
        harness.store.remember(subtitle, videoKey: "movie:1/a.mkv")
        try FileManager.default.removeItem(at: url)
        #expect(harness.store.subtitles(for: "movie:1/a.mkv").isEmpty)
        #expect(harness.store.existingFile(for: subtitle) == nil)
    }

    // MARK: - Pruning

    @Test func pruneDeletesSubtitlesUnusedForMoreThanAMonth() throws {
        let harness = try Harness()
        defer { harness.cleanUp() }
        let stale = wyzie(id: "stale")
        let recent = wyzie(id: "recent")
        let staleURL = try harness.store.save(Data("s".utf8), for: stale)
        let recentURL = try harness.store.save(Data("r".utf8), for: recent)
        harness.store.remember(stale, videoKey: "movie:1/a.mkv")
        harness.store.remember(recent, videoKey: "movie:2/b.mkv")

        // Exactly a month unused is kept.
        harness.clock.advance(months: 1)
        harness.store.markUsed(harness.store.subtitles(for: "movie:2/b.mkv"))
        #expect(harness.store.prune() == 0)

        harness.clock.advance(days: 1)
        #expect(harness.store.prune() == 1)
        #expect(!FileManager.default.fileExists(atPath: staleURL.path))
        #expect(FileManager.default.fileExists(atPath: recentURL.path))
        #expect(harness.store.subtitles(for: "movie:1/a.mkv").isEmpty)
        #expect(harness.store.subtitles(for: "movie:2/b.mkv").count == 1)
        #expect(try harness.recordCount() == 1)
    }

    @Test func pruneKeepsAFileAnotherVideoStillUses() throws {
        let harness = try Harness()
        defer { harness.cleanUp() }
        let shared = wyzie(id: "shared")
        let url = try harness.store.save(Data("s".utf8), for: shared)
        harness.store.remember(shared, videoKey: "movie:1/release-a.mkv")
        harness.clock.advance(days: 20)
        harness.store.remember(shared, videoKey: "movie:1/release-b.mkv")

        harness.clock.advance(days: 20)
        #expect(harness.store.prune() == 1)
        #expect(FileManager.default.fileExists(atPath: url.path))
        #expect(harness.store.subtitles(for: "movie:1/release-a.mkv").isEmpty)
        #expect(harness.store.subtitles(for: "movie:1/release-b.mkv").count == 1)
    }

    @Test func pruneDeletesMissingFilesAndFilesWithoutARecord() throws {
        let harness = try Harness()
        defer { harness.cleanUp() }
        let missing = wyzie(id: "missing")
        let missingURL = try harness.store.save(Data("m".utf8), for: missing)
        harness.store.remember(missing, videoKey: "movie:1/a.mkv")
        try FileManager.default.removeItem(at: missingURL)
        // A download saved before records were kept.
        let orphan = harness.directory.appendingPathComponent("wyzie-old-en.srt")
        try Data("o".utf8).write(to: orphan)

        #expect(harness.store.prune() == 1)
        #expect(try harness.recordCount() == 0)
        #expect(!FileManager.default.fileExists(atPath: orphan.path))
    }

    @Test func pruneWithoutAFolderDoesNothing() throws {
        let harness = try Harness()
        defer { harness.cleanUp() }
        try FileManager.default.removeItem(at: harness.directory)
        #expect(harness.store.prune() == 0)
    }

    // MARK: - Player

    @Test func reattachedTrackIsListedUnderItsName() throws {
        let harness = try Harness()
        defer { harness.cleanUp() }
        let subtitle = wyzie(id: "named", release: "Release.Name")
        let url = try harness.store.save(Data("1\n00:00:01,000 --> 00:00:02,000\nHi\n".utf8), for: subtitle)
        let player = PlaybackEngine()
        defer { player.close() }

        try player.addExternalTrack(from: url, name: SubtitleCacheStore.displayName(for: subtitle), select: false)

        #expect(player.subtitleTracks.last?.name == "English · Release.Name")
        #expect(player.selectedSubtitleTrack == nil)
    }

    // MARK: - Helpers

    private func movieItem(tmdbID: Int, url string: String) throws -> PlaybackItem {
        movieItem(tmdbID: tmdbID, fileURL: try #require(URL(string: string)))
    }

    private func movieItem(tmdbID: Int, fileURL: URL) -> PlaybackItem {
        let movie = Movie(localTitle: "Movie", filePath: fileURL.absoluteString)
        movie.tmdbId = tmdbID
        return PlaybackItem(scope: PlaybackScope(playURL: fileURL, accessedURL: nil), movie: movie)
    }

    private func episodeItem(showID: Int, season: Int, episode number: Int, url string: String) throws -> PlaybackItem {
        let url = try #require(URL(string: string))
        let show = TVShow(name: "Show")
        show.tmdbId = showID
        let episode = Episode(
            localTitle: "Episode",
            filePath: url.absoluteString,
            seasonNumber: season,
            episodeNumber: number
        )
        episode.show = show
        return PlaybackItem(scope: PlaybackScope(playURL: url, accessedURL: nil), episode: episode)
    }

    private func wyzie(id: String, release: String? = nil) -> WyzieSubtitle {
        WyzieSubtitle(
            id: id,
            url: "https://cdn.example/\(id).srt",
            format: "srt",
            encoding: "UTF-8",
            isHearingImpaired: false,
            flagUrl: "https://cdn.example/en.png",
            media: "Example",
            display: "English",
            language: "en",
            release: release
        )
    }

    private final class Clock {
        var date: Date
        let calendar: Calendar

        init(calendar: Calendar) {
            self.calendar = calendar
            self.date = calendar.date(from: DateComponents(year: 2026, month: 1, day: 15, hour: 12))!
        }

        func advance(days: Int = 0, months: Int = 0) {
            date = calendar.date(byAdding: DateComponents(month: months, day: days), to: date)!
        }
    }

    @MainActor
    private struct Harness {
        let container: ModelContainer
        let directory: URL
        let clock: Clock
        let store: SubtitleCacheStore

        init() throws {
            let schema = Schema([CachedSubtitle.self])
            container = try ModelContainer(
                for: schema,
                configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)]
            )
            directory = FileManager.default.temporaryDirectory
                .appendingPathComponent("SubtitleCacheTests-\(UUID().uuidString)", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(identifier: "UTC")!
            let clock = Clock(calendar: calendar)
            self.clock = clock
            store = SubtitleCacheStore(
                modelContext: container.mainContext,
                directory: directory,
                calendar: calendar,
                now: { clock.date }
            )
        }

        func recordCount() throws -> Int {
            try container.mainContext.fetchCount(FetchDescriptor<CachedSubtitle>())
        }

        func cleanUp() {
            try? FileManager.default.removeItem(at: directory)
        }
    }
}
