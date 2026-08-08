//
//  WatchlistItem.swift
//  Edendale
//
//  Local-first SwiftData snapshot of a TMDB watchlist title. Presentation
//  metadata is stored with the reference so the watchlist stays useful
//  offline; pending mutations survive relaunches until TMDB confirms them.
//

import Foundation
import SwiftData

enum WatchlistPendingAction: String, Sendable {
    case none
    case add
    case remove
}

/// The metadata needed to render a watchlist card without another request.
struct WatchlistMetadata: Sendable {
    let title: String
    let overview: String?
    let posterPath: String?
    let backdropPath: String?
    let voteAverage: Double?
    let releaseDate: String?

    init(
        title: String,
        overview: String? = nil,
        posterPath: String? = nil,
        backdropPath: String? = nil,
        voteAverage: Double? = nil,
        releaseDate: String? = nil
    ) {
        self.title = title
        self.overview = overview
        self.posterPath = posterPath
        self.backdropPath = backdropPath
        self.voteAverage = voteAverage
        self.releaseDate = releaseDate
    }

    init(_ item: TMDBMediaItem) {
        self.init(
            title: item.title,
            overview: item.overview,
            posterPath: item.posterPath,
            backdropPath: item.backdropPath,
            voteAverage: item.voteAverage,
            releaseDate: item.releaseDate
        )
    }

    init(_ detail: MediaDetail) {
        self.init(
            title: detail.title,
            overview: detail.overview,
            posterPath: detail.posterPath,
            backdropPath: detail.backdropPath,
            voteAverage: detail.score,
            releaseDate: detail.releaseDate
        )
    }

    init(_ movie: Movie) {
        self.init(
            title: movie.displayTitle,
            overview: movie.overview,
            posterPath: movie.posterPath,
            backdropPath: movie.backdropPath,
            voteAverage: movie.voteAverage,
            releaseDate: movie.releaseYear.map(String.init)
        )
    }

    init(_ show: TVShow) {
        self.init(
            title: show.displayName,
            overview: show.overview,
            posterPath: show.posterPath,
            backdropPath: show.backdropPath,
            releaseDate: show.firstAirDate
        )
    }
}

@Model
final class WatchlistItem {
    /// Composite identity keeps movie 123 distinct from TV show 123.
    @Attribute(.unique) var storageKey: String
    var tmdbId: Int
    var mediaTypeRaw: String

    var title: String
    var overview: String?
    var posterPath: String?
    var backdropPath: String?
    var voteAverage: Double?
    var releaseDate: String?

    var dateAdded: Date
    var updatedAt: Date
    var isInWatchlist: Bool
    var pendingSyncRaw: String

    init(
        ref: MediaRef,
        metadata: WatchlistMetadata? = nil,
        isInWatchlist: Bool = true,
        pendingAction: WatchlistPendingAction = .add,
        dateAdded: Date = Date()
    ) {
        storageKey = Self.key(for: ref)
        tmdbId = ref.id
        mediaTypeRaw = ref.mediaType.rawValue
        title = metadata?.title.nonBlank ?? Self.fallbackTitle(for: ref.mediaType)
        overview = metadata?.overview?.nonBlank
        posterPath = metadata?.posterPath
        backdropPath = metadata?.backdropPath
        voteAverage = metadata?.voteAverage
        releaseDate = metadata?.releaseDate?.nonBlank
        self.dateAdded = dateAdded
        updatedAt = dateAdded
        self.isInWatchlist = isInWatchlist
        pendingSyncRaw = pendingAction.rawValue
    }

    var mediaType: TMDBMediaType {
        TMDBMediaType(rawValue: mediaTypeRaw) ?? .movie
    }

    var ref: MediaRef {
        MediaRef(id: tmdbId, mediaType: mediaType)
    }

    var pendingAction: WatchlistPendingAction {
        get { WatchlistPendingAction(rawValue: pendingSyncRaw) ?? .none }
        set { pendingSyncRaw = newValue.rawValue }
    }

    var posterURL: URL? {
        TMDBImage.url(posterPath, size: .posterLarge)
    }

    var detailedDateText: String? {
        snapshot.detailedDateText
    }

    var snapshot: TMDBMediaItem {
        TMDBMediaItem(
            id: tmdbId,
            mediaType: mediaType,
            title: title,
            overview: overview,
            posterPath: posterPath,
            backdropPath: backdropPath,
            voteAverage: voteAverage,
            releaseDate: releaseDate
        )
    }

    func apply(_ metadata: WatchlistMetadata) {
        if let title = metadata.title.nonBlank { self.title = title }
        if let overview = metadata.overview?.nonBlank { self.overview = overview }
        if let posterPath = metadata.posterPath { self.posterPath = posterPath }
        if let backdropPath = metadata.backdropPath { self.backdropPath = backdropPath }
        if let voteAverage = metadata.voteAverage { self.voteAverage = voteAverage }
        if let releaseDate = metadata.releaseDate?.nonBlank { self.releaseDate = releaseDate }
        updatedAt = Date()
    }

    static func key(for ref: MediaRef) -> String {
        "\(ref.mediaType.rawValue)_\(ref.id)"
    }

    private static func fallbackTitle(for type: TMDBMediaType) -> String {
        switch type {
        case .movie: String(localized: "Movie")
        case .tv: String(localized: "TV Show")
        }
    }
}

private extension String {
    var nonBlank: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}
