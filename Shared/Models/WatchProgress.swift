//
//  WatchProgress.swift
//  Edendale
//
//  Lightweight value-type DTO for watch progress.
//  Persisted to iCloud via CoreData + CloudKit (see CDWatchProgress), and
//  since 27.1 carried between devices as a sync record (SYNC-PROTOCOL.md
//  §6.1) with one stamp and the device that wrote it.
//

import Foundation

enum WatchMediaType: String, Sendable {
    case movie
    case episode
}

struct WatchProgress: Sendable, Equatable {
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

    /// The record's `updateTime`: UTC milliseconds, 0 until stamped.
    var updateTime: Int64
    /// The sync device ID that wrote this version; nil for rows from
    /// before 27.1 until they are stamped.
    var deviceID: String?
    /// A tombstone: the progress was removed, and the row stays so an
    /// older copy elsewhere cannot bring it back.
    var cleared: Bool

    init(
        tmdbId: Int,
        mediaType: WatchMediaType,
        position: Double = 0,
        watchedSeconds: TimeInterval = 0,
        isCompleted: Bool = false,
        showTmdbId: Int? = nil,
        seasonNumber: Int? = nil,
        episodeNumber: Int? = nil,
        lastWatchedAt: Date = Date(),
        updateTime: Int64 = 0,
        deviceID: String? = nil,
        cleared: Bool = false
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
        self.updateTime = updateTime
        self.deviceID = deviceID
        self.cleared = cleared
    }

    /// The sync key this row maps to: a movie, or an episode by its show,
    /// season, and episode. Nil for an episode whose show is unknown.
    var syncKey: SyncRecordKey? {
        switch mediaType {
        case .movie:
            return .movie(tmdbId)
        case .episode:
            guard let showTmdbId, showTmdbId > 0, let seasonNumber, let episodeNumber else { return nil }
            return .episode(show: showTmdbId, season: seasonNumber, episode: episodeNumber)
        }
    }
}
