//
//  WatchProgress.swift
//  Edendale
//
//  Lightweight value-type DTO for watch progress.
//  Persisted to iCloud via CoreData + CloudKit (see CDWatchProgress).
//

import Foundation

enum WatchMediaType: String, Sendable {
    case movie
    case episode
}

struct WatchProgress: Sendable {
    let tmdbId: Int
    let mediaType: WatchMediaType

    /// Playback position as a fraction of total duration, in [0, 1].
    var position: Double
    var watchedSeconds: TimeInterval
    var lastWatchedAt: Date
    var isCompleted: Bool

    // Episode-specific context (nil for movies)
    var showTmdbId: Int?
    var seasonNumber: Int?
    var episodeNumber: Int?

    init(
        tmdbId: Int,
        mediaType: WatchMediaType,
        position: Double = 0,
        watchedSeconds: TimeInterval = 0,
        isCompleted: Bool = false,
        showTmdbId: Int? = nil,
        seasonNumber: Int? = nil,
        episodeNumber: Int? = nil,
        lastWatchedAt: Date = Date()
    ) {
        self.tmdbId = tmdbId
        self.mediaType = mediaType
        self.position = position
        self.watchedSeconds = watchedSeconds
        self.lastWatchedAt = lastWatchedAt
        self.isCompleted = isCompleted
        self.showTmdbId = showTmdbId
        self.seasonNumber = seasonNumber
        self.episodeNumber = episodeNumber
    }
}
