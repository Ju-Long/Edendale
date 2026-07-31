//
//  WyzieSubtitleTests.swift
//  EdendaleTests
//

import Foundation
import SwiftData
import Testing
@testable import Edendale

@MainActor
struct WyzieSubtitleTests {
    @Test func movieQueryContainsOnlyIdentifierAndKey() {
        let query = WyzieSubtitleQuery(
            id: "278",
            season: nil,
            episode: nil,
            language: nil,
            format: nil,
            hearingImpaired: nil
        )

        let items = WyzieSubtitleService.queryItems(for: query, key: "test-key")

        #expect(items.map(\.name) == ["id", "key"])
        #expect(items.first?.value == "278")
        #expect(items.last?.value == "test-key")
    }

    @Test func episodeQueryContainsSeasonEpisodeAndFilters() {
        let query = WyzieSubtitleQuery(
            id: "1396",
            season: 2,
            episode: 8,
            language: "en",
            format: "srt",
            hearingImpaired: false
        )

        let items = WyzieSubtitleService.queryItems(for: query, key: "test-key")
        let values = Dictionary(
            uniqueKeysWithValues: items.compactMap { item in
                item.value.map { (item.name, $0) }
            }
        )

        #expect(items.map(\.name) == [
            "id", "season", "episode", "language", "format", "hi", "key"
        ])
        #expect(values["id"] == "1396")
        #expect(values["season"] == "2")
        #expect(values["episode"] == "8")
        #expect(values["language"] == "en")
        #expect(values["format"] == "srt")
        #expect(values["hi"] == "false")
        #expect(items.last?.name == "key")
    }

    @Test func queryOmitsEpisodeWithoutSeason() {
        let query = WyzieSubtitleQuery(
            id: "1396",
            season: nil,
            episode: 8,
            language: nil,
            format: nil,
            hearingImpaired: nil
        )

        let names = WyzieSubtitleService.queryItems(
            for: query,
            key: "test-key"
        ).map(\.name)

        #expect(names == ["id", "key"])
    }

    @Test func successDecodingNormalizesSourceShapesAndAllowsMissingOptionals() throws {
        let data = Data(
            #"""
            [
              {
                "id": "single-source",
                "url": "https://cdn.example/one.srt",
                "format": "srt",
                "encoding": "UTF-8",
                "isHearingImpaired": false,
                "flagUrl": "https://cdn.example/en.png",
                "media": "Example",
                "display": "English",
                "language": "en",
                "source": "lima",
                "release": "Example.1080p",
                "releases": ["Example.1080p"],
                "fileName": "example.srt",
                "downloadCount": 123,
                "origin": "WEB",
                "matchedRelease": "Example.1080p",
                "matchedFilter": "title"
              },
              {
                "id": "array-source",
                "url": "https://cdn.example/two.srt",
                "format": "srt",
                "encoding": "UTF-8",
                "isHearingImpaired": true,
                "flagUrl": "https://cdn.example/es.png",
                "media": "Example",
                "display": "Spanish",
                "language": "es",
                "source": ["lima", "opensubtitles"]
              },
              {
                "id": "no-optionals",
                "url": "https://cdn.example/three.vtt",
                "format": "vtt",
                "encoding": "UTF-8",
                "isHearingImpaired": false,
                "flagUrl": "https://cdn.example/fr.png",
                "media": "Example",
                "display": "French",
                "language": "fr"
              }
            ]
            """#.utf8
        )

        let subtitles = try WyzieSubtitleService.decodeSearchResponse(
            data,
            statusCode: 200
        )

        #expect(subtitles.count == 3)
        #expect(subtitles[0].source == ["lima"])
        #expect(subtitles[1].source == ["lima", "opensubtitles"])
        #expect(subtitles[2].source == nil)
        #expect(subtitles[2].release == nil)
        #expect(subtitles[2].releases == nil)
        #expect(subtitles[2].fileName == nil)
        #expect(subtitles[2].downloadCount == nil)
        #expect(subtitles[2].origin == nil)
        #expect(subtitles[2].matchedRelease == nil)
        #expect(subtitles[2].matchedFilter == nil)
    }

    @Test func errorEnvelopePreservesServerMessage() {
        let data = Data(
            #"""
            {
              "code": 401,
              "message": "API key required",
              "details": "Include a valid API key.",
              "notice": "https://sub.wyzie.io/notice"
            }
            """#.utf8
        )

        do {
            _ = try WyzieSubtitleService.decodeSearchResponse(data, statusCode: 401)
            Issue.record("Expected a Wyzie status error")
        } catch WyzieError.badStatus(let code, let message) {
            #expect(code == 401)
            #expect(message == "API key required")
        } catch {
            Issue.record("Unexpected error: \(error)")
        }
    }

    @Test func cacheFileNameIsASafeSinglePathComponent() {
        let subtitle = WyzieSubtitle(
            id: "../Sub ID",
            url: "https://cdn.example/subtitle",
            format: "   ",
            encoding: "UTF-8",
            isHearingImpaired: false,
            flagUrl: "https://cdn.example/en.png",
            media: "Example",
            display: "English",
            language: "EN/../US"
        )

        let fileName = WyzieSubtitleService.cacheFileName(for: subtitle)

        #expect(fileName == "wyzie-subid-enus.srt")
        #expect(
            URL(fileURLWithPath: fileName).lastPathComponent == fileName
        )
        #expect(!fileName.contains("/"))
        #expect(!fileName.contains(" "))
    }

    @Test func playbackSubtitleLookupUsesMovieAndShowIdentifiers() async throws {
        let library = try makeLibrary()
        let fileURL = try makeTemporaryFile(named: "Lookup.mkv")
        defer {
            try? FileManager.default.removeItem(
                at: fileURL.deletingLastPathComponent()
            )
        }

        let movie = Movie(localTitle: "Movie", filePath: fileURL.path)
        movie.tmdbId = 278
        let movieItem = await library.preparePlayback(movie: movie)

        let show = TVShow(name: "Show")
        show.tmdbId = 1396
        let episode = Episode(
            localTitle: "Episode",
            filePath: fileURL.path,
            seasonNumber: 2,
            episodeNumber: 8
        )
        episode.tmdbId = 62085
        episode.show = show
        let episodeItem = await library.preparePlayback(episode: episode)

        let unmatched = await library.preparePlayback(fileURL: fileURL)

        #expect(movieItem.subtitleLookup?.id == "278")
        #expect(movieItem.subtitleLookup?.season == nil)
        #expect(movieItem.subtitleLookup?.episode == nil)
        #expect(episodeItem.subtitleLookup?.id == "1396")
        #expect(episodeItem.subtitleLookup?.season == 2)
        #expect(episodeItem.subtitleLookup?.episode == 8)
        #expect(episodeItem.subtitleLookup?.id != "62085")
        #expect(unmatched.subtitleLookup == nil)
    }

    private func makeLibrary() throws -> LibraryController {
        let schema = Schema([
            VideoFolder.self,
            Movie.self,
            TVShow.self,
            Episode.self
        ])
        let configuration = ModelConfiguration(
            schema: schema,
            isStoredInMemoryOnly: true,
            cloudKitDatabase: .none
        )
        let container = try ModelContainer(
            for: schema,
            configurations: [configuration]
        )
        return LibraryController(modelContext: container.mainContext)
    }

    private func makeTemporaryFile(named name: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("WyzieSubtitleTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        let fileURL = directory.appendingPathComponent(name)
        try Data().write(to: fileURL)
        return fileURL
    }
}
