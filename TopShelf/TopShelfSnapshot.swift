//
//  TopShelfSnapshot.swift
//  TopShelfExtension
//
//  Read-only copy of the snapshot the tvOS app publishes to the App Group
//  (Shared/Controllers/WidgetSnapshotStore.swift). Like the iOS widget
//  extension, this target never opens Edendale's SwiftData or CoreData stores
//  and receives no file paths, bookmarks, tokens, or credentials.
//

import Foundation

struct TopShelfSnapshot: Decodable, Sendable {
    let trending: [TopShelfMediaItem]
    let continueWatching: [TopShelfMediaItem]

    private enum CodingKeys: String, CodingKey {
        case trending, continueWatching
    }

    init(trending: [TopShelfMediaItem], continueWatching: [TopShelfMediaItem]) {
        self.trending = trending
        self.continueWatching = continueWatching
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        trending = try container.decodeIfPresent([TopShelfMediaItem].self, forKey: .trending) ?? []
        continueWatching = try container.decodeIfPresent(
            [TopShelfMediaItem].self,
            forKey: .continueWatching
        ) ?? []
    }
}

/// The display fields of the app's `WidgetMediaItem`. Fields added for the
/// Top Shelf are optional, so snapshots written by older builds still decode.
struct TopShelfMediaItem: Decodable, Sendable {
    /// "tmdb:movie:<id>", "tmdb:tv:<id>", or "tmdb:episode:<id>".
    let id: String
    let title: String
    let subtitle: String?
    let posterURL: URL?
    let progress: Double?
    /// Detail page for Trending, playback for Continue Watching.
    let deepLink: URL
    let backdropURL: URL?
    let overview: String?
    let releaseDate: String?
    let duration: TimeInterval?
    let detailLink: URL?
}

enum TopShelfSnapshotStore {
    static let appGroup = "group.com.BaBaSaMa.Edendale"
    static let snapshotKey = "edendale.widget.snapshot.v1"

    static func load(
        from defaults: UserDefaults? = UserDefaults(suiteName: appGroup)
    ) -> TopShelfSnapshot? {
        guard let data = defaults?.data(forKey: snapshotKey) else { return nil }
        return try? JSONDecoder().decode(TopShelfSnapshot.self, from: data)
    }
}
