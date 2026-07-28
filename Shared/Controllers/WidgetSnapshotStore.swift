//
//  WidgetSnapshotStore.swift
//  Edendale
//
//  Privacy-safe, read-only projection for widgets and App Intents. This is
//  deliberately separate from both persistence stores: extensions receive
//  display metadata and stable routes, never file paths, bookmarks, tokens,
//  credentials, or CloudKit objects.
//

import Foundation
#if canImport(WidgetKit) && !os(tvOS)
import WidgetKit
#endif
#if canImport(AppIntents)
import AppIntents
#endif

struct WidgetMediaItem: Codable, Hashable, Identifiable, Sendable {
    let id: String
    let parentID: String?
    let title: String
    let subtitle: String?
    let posterURL: URL?
    let progress: Double?
    let deepLink: URL

    init(
        id: String,
        parentID: String? = nil,
        title: String,
        subtitle: String?,
        posterURL: URL?,
        progress: Double?,
        deepLink: URL
    ) {
        self.id = id
        self.parentID = parentID
        self.title = title
        self.subtitle = subtitle
        self.posterURL = posterURL
        self.progress = progress
        self.deepLink = deepLink
    }
}

struct WidgetSnapshot: Codable, Hashable, Sendable {
    var updatedAt: Date
    var trending: [WidgetMediaItem]
    var continueWatching: [WidgetMediaItem]
    /// Broader local + current-TMDB set used for dynamic Siri parameters.
    /// The widget extension safely ignores this extra JSON field.
    var catalog: [WidgetMediaItem]

    init(
        updatedAt: Date,
        trending: [WidgetMediaItem],
        continueWatching: [WidgetMediaItem],
        catalog: [WidgetMediaItem]
    ) {
        self.updatedAt = updatedAt
        self.trending = trending
        self.continueWatching = continueWatching
        self.catalog = catalog
    }

    private enum CodingKeys: String, CodingKey {
        case updatedAt, trending, continueWatching, catalog
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        updatedAt = try container.decode(Date.self, forKey: .updatedAt)
        trending = try container.decodeIfPresent([WidgetMediaItem].self, forKey: .trending) ?? []
        continueWatching = try container.decodeIfPresent(
            [WidgetMediaItem].self,
            forKey: .continueWatching
        ) ?? []
        catalog = try container.decodeIfPresent([WidgetMediaItem].self, forKey: .catalog) ?? []
    }
}

enum WidgetSnapshotStore {
    static let defaultsKey = "edendale.widget.snapshot.v1"
    /// Keeps the App Group defaults payload bounded even for large TV libraries.
    static let maximumCatalogItems = 500

    static func load() -> WidgetSnapshot? {
        guard let data = AppIdentifiers.defaults.data(forKey: defaultsKey) else { return nil }
        return try? JSONDecoder().decode(WidgetSnapshot.self, from: data)
    }

    @MainActor
    static func publish(
        trending sourceTrending: [TMDBMediaItem],
        movies: [Movie],
        shows: [TVShow],
        episodes: [Episode],
        progress: [WatchProgress]
    ) {
        let snapshot = makeSnapshot(
            trending: sourceTrending,
            movies: movies,
            shows: shows,
            episodes: episodes,
            progress: progress
        )

        if let current = load(),
           current.trending == snapshot.trending,
           current.continueWatching == snapshot.continueWatching,
           current.catalog == snapshot.catalog {
            return
        }

        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        AppIdentifiers.defaults.set(data, forKey: defaultsKey)

        #if canImport(WidgetKit) && !os(tvOS)
        if #available(visionOS 26.0, *) {
            WidgetCenter.shared.reloadTimelines(
                ofKind: "com.BaBaSaMa.Edendale.widget.trending"
            )
            WidgetCenter.shared.reloadTimelines(
                ofKind: "com.BaBaSaMa.Edendale.widget.continue-watching"
            )
        }
        #endif

        #if canImport(AppIntents)
        EdendaleShortcuts.updateAppShortcutParameters()
        #endif
    }

    @MainActor
    private static func makeSnapshot(
        trending sourceTrending: [TMDBMediaItem],
        movies: [Movie],
        shows: [TVShow],
        episodes: [Episode],
        progress: [WatchProgress]
    ) -> WidgetSnapshot {
        let trending = sourceTrending.prefix(10).compactMap { item -> WidgetMediaItem? in
            guard let deepLink = AppRoute.media(item.ref).url else { return nil }
            return WidgetMediaItem(
                id: "tmdb:\(item.mediaType.rawValue):\(item.id)",
                title: item.title,
                subtitle: item.year.map(String.init)
                    ?? (item.mediaType == .movie
                        ? String(localized: "Movie")
                        : String(localized: "Series")),
                posterURL: item.posterURL,
                progress: nil,
                deepLink: deepLink
            )
        }

        let continueWatching = progress.prefix(8).compactMap { entry -> WidgetMediaItem? in
            switch entry.mediaType {
            case .movie:
                guard let movie = movies.first(where: { $0.tmdbId == entry.tmdbId }),
                      let deepLink = AppRoute.playMovie(tmdbId: entry.tmdbId).url
                else { return nil }
                return WidgetMediaItem(
                    id: "tmdb:movie:\(entry.tmdbId)",
                    title: movie.displayTitle,
                    subtitle: String(localized: "\(Int((entry.position * 100).rounded()))% watched"),
                    posterURL: movie.posterURL,
                    progress: entry.position,
                    deepLink: deepLink
                )

            case .episode:
                guard let episode = episodes.first(where: { $0.tmdbId == entry.tmdbId }),
                      let deepLink = AppRoute.playEpisode(tmdbId: entry.tmdbId).url
                else { return nil }
                let showName = episode.show?.displayName ?? String(localized: "TV Episode")
                return WidgetMediaItem(
                    id: "tmdb:episode:\(entry.tmdbId)",
                    title: showName,
                    subtitle: "\(episode.episodeCode) · \(episode.displayTitle)",
                    posterURL: episode.show?.posterURL,
                    progress: entry.position,
                    deepLink: deepLink
                )
            }
        }

        let localMovies = movies.compactMap { movie -> WidgetMediaItem? in
            guard let deepLink = AppRoute.localMovie(movie.id).url else { return nil }
            return WidgetMediaItem(
                id: "local:movie:\(movie.id.uuidString.lowercased())",
                title: movie.displayTitle,
                subtitle: movie.releaseYear.map(String.init) ?? String(localized: "Local movie"),
                posterURL: movie.posterURL,
                progress: nil,
                deepLink: deepLink
            )
        }

        let localShows = shows.compactMap { show -> WidgetMediaItem? in
            guard let deepLink = AppRoute.localShow(show.id).url else { return nil }
            return WidgetMediaItem(
                id: "local:show:\(show.id.uuidString.lowercased())",
                title: show.displayName,
                subtitle: show.episodes.count == 1
                    ? String(localized: "1 local episode")
                    : String(localized: "\(show.episodes.count) local episodes"),
                posterURL: show.posterURL,
                progress: nil,
                deepLink: deepLink
            )
        }

        let localEpisodes = episodes.compactMap { episode -> WidgetMediaItem? in
            guard let deepLink = AppRoute.playLocalEpisode(episode.id).url else { return nil }
            let showName = episode.show?.displayName ?? String(localized: "TV Episode")
            let showID = episode.show.map {
                "local:show:\($0.id.uuidString.lowercased())"
            }
            let episodeProgress = episode.tmdbId.flatMap { tmdbID in
                progress.first {
                    $0.mediaType == .episode && $0.tmdbId == tmdbID
                }?.position
            }
            return WidgetMediaItem(
                id: "local:episode:\(episode.id.uuidString.lowercased())",
                parentID: showID,
                title: "\(showName) — \(episode.displayTitle)",
                subtitle: episode.episodeCode,
                posterURL: episode.show?.posterURL,
                progress: episodeProgress,
                deepLink: deepLink
            )
        }

        let catalog = cappedCatalog(
            primary: localMovies + localShows + trending,
            secondary: localEpisodes
        )

        return WidgetSnapshot(
            updatedAt: Date(),
            trending: trending,
            continueWatching: continueWatching,
            catalog: catalog
        )
    }

    /// Preserves movie/show/trending entities before filling remaining capacity
    /// with episodes, then applies a stable title/id order for Siri resolution.
    static func cappedCatalog(
        primary: [WidgetMediaItem],
        secondary: [WidgetMediaItem]
    ) -> [WidgetMediaItem] {
        let primaryItems = Array(stablySorted(primary).prefix(maximumCatalogItems))
        let remainingCount = maximumCatalogItems - primaryItems.count
        let episodeItems = Array(stablySorted(secondary).prefix(remainingCount))
        return stablySorted(primaryItems + episodeItems)
    }

    private static func stablySorted(_ items: [WidgetMediaItem]) -> [WidgetMediaItem] {
        items.sorted {
            let titleOrder = $0.title.localizedStandardCompare($1.title)
            if titleOrder != .orderedSame { return titleOrder == .orderedAscending }
            return $0.id < $1.id
        }
    }
}
