//
//  UserMediaState.swift
//  Edendale
//
//  Lightweight value-type DTO for favourite and personal rating state.
//  Persisted to iCloud via CoreData + CloudKit (see CDUserMedia) and mirrored
//  to the connected TMDB account by UserMediaStore. Watchlist is SwiftData.
//

import Foundation

struct UserMediaState: Sendable {
    let ref: MediaRef

    var isFavorite: Bool
    /// The user's own TMDB-style rating (0.5...10); nil when unrated.
    var rating: Double?
    var updatedAt: Date

    init(
        ref: MediaRef,
        isFavorite: Bool = false,
        rating: Double? = nil,
        updatedAt: Date = Date()
    ) {
        self.ref = ref
        self.isFavorite = isFavorite
        self.rating = rating
        self.updatedAt = updatedAt
    }

    /// True when nothing is stored anymore — the backing row can be deleted.
    var isEmpty: Bool { !isFavorite && rating == nil }
}
