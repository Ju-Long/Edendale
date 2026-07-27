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
