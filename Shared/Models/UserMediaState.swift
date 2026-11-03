//
//  UserMediaState.swift
//  Edendale
//
//  Lightweight value-type DTO for favourite, watchlist flag, personal
//  rating, and review state. Persisted to iCloud via CoreData + CloudKit
//  (see CDUserMedia), mirrored to the connected TMDB account by
//  UserMediaStore, and since 27.1 carried between devices as one sync
//  record (SYNC-PROTOCOL.md §6.2). The watchlist's cards and TMDB mirror
//  stay in WatchlistStore; the flag here is the portable copy.
//

import Foundation

struct UserMediaState: Sendable, Equatable {
    let ref: MediaRef

    var isFavorite: Bool
    var isInWatchlist: Bool
    /// The user's own TMDB-style rating (0.5...10); nil when unrated.
    var rating: Double?
    /// Review text written on another platform; Apple shows none yet.
    var review: String?
    var updatedAt: Date
    /// The record's `updateTime`: UTC milliseconds, 0 until stamped.
    var updateTime: Int64
    var deviceID: String?

    init(
        ref: MediaRef,
        isFavorite: Bool = false,
        isInWatchlist: Bool = false,
        rating: Double? = nil,
        review: String? = nil,
        updatedAt: Date = Date(),
        updateTime: Int64 = 0,
        deviceID: String? = nil
    ) {
        self.ref = ref
        self.isFavorite = isFavorite
        self.isInWatchlist = isInWatchlist
        self.rating = rating
        self.review = review
        self.updatedAt = updatedAt
        self.updateTime = updateTime
        self.deviceID = deviceID
    }

    /// True when nothing is set: the row is a tombstone, kept so an older
    /// copy elsewhere cannot bring the values back.
    var isEmpty: Bool { !isFavorite && !isInWatchlist && rating == nil && review == nil }
}
