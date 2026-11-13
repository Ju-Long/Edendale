//
//  Movie.swift
//  Edendale
//

import Foundation
import SwiftData

@Model
final class Movie {
    var id: UUID
    /// Title parsed from the filename before TMDB enrichment.
    var localTitle: String
    var filePath: String
    var bookmarkData: Data?

    // TMDB metadata — nil until enriched
    var tmdbId: Int?
    var tmdbTitle: String?
    var releaseYear: Int?
    var overview: String?
    var posterPath: String?
    var backdropPath: String?
    var voteAverage: Double?

    var duration: TimeInterval
    var dateAdded: Date
    /// True when the file lives in iCloud Drive and may not be locally present.
    var isICloudItem: Bool
    /// When TMDB last answered with no match for this file. The metadata
    /// sweep waits `LibraryController.unmatchedRetryInterval` before asking
    /// again; a failed request leaves it nil so the next sweep retries.
    var metadataCheckedAt: Date?
    /// When this copy last started playing. Of several copies of one title
    /// (different sources or endpoints), the one played last is the default.
    var lastPlayedAt: Date?

    var folder: VideoFolder?

    init(
        localTitle: String,
        filePath: String,
        bookmarkData: Data? = nil,
        releaseYear: Int? = nil,
        isICloudItem: Bool = false
    ) {
        self.id = UUID()
        self.localTitle = localTitle
        self.filePath = filePath
        self.bookmarkData = bookmarkData
        self.releaseYear = releaseYear
        self.isICloudItem = isICloudItem
        self.duration = 0
        self.dateAdded = Date()
    }

    var displayTitle: String { tmdbTitle ?? localTitle }

    var posterURL: URL? { TMDBImage.url(posterPath, size: .posterLarge) }

    var backdropURL: URL? { TMDBImage.url(backdropPath, size: .backdrop) }

    var formattedDuration: String {
        duration > 0 ? PlayerLogic.timestamp(duration) : "--:--"
    }

    /// The imported movie matching a TMDB id, if the local library holds one.
    static func first(tmdbId: Int, in context: ModelContext) -> Movie? {
        var descriptor = FetchDescriptor<Movie>(predicate: #Predicate { $0.tmdbId == tmdbId })
        descriptor.fetchLimit = 1
        return try? context.fetch(descriptor).first
    }

    /// The copy of a TMDB title to play when several sources hold one: the
    /// copy played last, otherwise any.
    static func preferredCopy(tmdbId: Int, in context: ModelContext) -> Movie? {
        let copies = copies(tmdbId: tmdbId, in: context)
        return PlaybackSources.mostRecentlyPlayed(copies, lastPlayed: \.lastPlayedAt) ?? copies.first
    }

    /// Every imported copy of a TMDB title, across sources.
    static func copies(tmdbId: Int, in context: ModelContext) -> [Movie] {
        let descriptor = FetchDescriptor<Movie>(predicate: #Predicate { $0.tmdbId == tmdbId })
        return (try? context.fetch(descriptor)) ?? []
    }
}
