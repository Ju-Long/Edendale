//
//  WidgetSnapshotTests.swift
//  EdendaleTests
//

import Foundation
import Testing
@testable import Edendale

@MainActor
struct WidgetSnapshotTests {
    @Test func privacySafeSnapshotRoundTrips() throws {
        let route = try #require(AppRoute.playMovie(tmdbId: 78).url)
        let item = WidgetMediaItem(
            id: "tmdb:movie:78",
            title: "Blade Runner",
            subtitle: "1982",
            posterURL: URL(string: "https://image.tmdb.org/t/p/w500/poster.jpg"),
            progress: 0.42,
            deepLink: route
        )
        let snapshot = WidgetSnapshot(
            updatedAt: Date(timeIntervalSince1970: 1234),
            trending: [item],
            continueWatching: [item],
            catalog: [item]
        )

        let data = try JSONEncoder().encode(snapshot)
        let decoded = try JSONDecoder().decode(WidgetSnapshot.self, from: data)

        #expect(decoded == snapshot)
        let payload = try #require(String(data: data, encoding: .utf8))
        #expect(!payload.contains("filePath"))
        #expect(!payload.contains("bookmarkData"))
        #expect(!payload.contains("TMDBReadAccessToken"))
    }

    /// The tvOS Top Shelf fields are optional: snapshots written before they
    /// existed still decode, and items without them encode no extra keys.
    @Test func topShelfFieldsAreOptionalAndRoundTrip() throws {
        let olderPayload = Data("""
        {"updatedAt":1234,"trending":[{"id":"tmdb:movie:78","title":"Blade Runner",
        "deepLink":"edendale://media/movie/78"}],"continueWatching":[]}
        """.utf8)
        let older = try JSONDecoder().decode(WidgetSnapshot.self, from: olderPayload)
        #expect(older.trending.first?.backdropURL == nil)
        #expect(older.trending.first?.overview == nil)
        #expect(older.catalog.isEmpty)

        let item = WidgetMediaItem(
            id: "tmdb:movie:78",
            title: "Blade Runner",
            subtitle: "1982",
            posterURL: nil,
            progress: 0.42,
            deepLink: try #require(AppRoute.playMovie(tmdbId: 78).url),
            backdropURL: URL(string: "https://image.tmdb.org/t/p/w1280/backdrop.jpg"),
            overview: "A blade runner must pursue replicants.",
            releaseDate: "1982-06-25",
            duration: 7020,
            detailLink: AppRoute.media(MediaRef(id: 78, mediaType: .movie)).url
        )
        let data = try JSONEncoder().encode(item)
        #expect(try JSONDecoder().decode(WidgetMediaItem.self, from: data) == item)

        let plain = try makeItem(id: "local:movie:1", title: "Plain")
        let plainPayload = try #require(String(data: JSONEncoder().encode(plain), encoding: .utf8))
        #expect(!plainPayload.contains("backdropURL"))
        #expect(!plainPayload.contains("overview"))
    }

    @Test func siriCatalogIsBoundedAndKeepsPrimaryTitles() throws {
        let primary = try (0..<3).map { index in
            try makeItem(id: "local:movie:\(index)", title: "Primary \(index)")
        }
        let episodes = try (0..<(WidgetSnapshotStore.maximumCatalogItems + 40)).map { index in
            try makeItem(id: "local:episode:\(index)", title: "Episode \(index)")
        }

        let catalog = WidgetSnapshotStore.cappedCatalog(
            primary: Array(primary.reversed()),
            secondary: Array(episodes.reversed())
        )
        let repeated = WidgetSnapshotStore.cappedCatalog(
            primary: primary,
            secondary: episodes
        )

        #expect(catalog.count == WidgetSnapshotStore.maximumCatalogItems)
        #expect(Set(primary.map(\.id)).isSubset(of: Set(catalog.map(\.id))))
        #expect(catalog == repeated)
    }

    private func makeItem(id: String, title: String) throws -> WidgetMediaItem {
        WidgetMediaItem(
            id: id,
            title: title,
            subtitle: nil,
            posterURL: nil,
            progress: nil,
            deepLink: try #require(AppRoute.search(title).url)
        )
    }
}
