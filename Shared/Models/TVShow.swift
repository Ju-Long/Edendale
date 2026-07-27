//
//  TVShow.swift
//  Edendale
//

import Foundation
import SwiftData

@Model
final class TVShow {
    var id: UUID
    /// Show name parsed from episode filenames before TMDB enrichment.
    var name: String

    // TMDB metadata — nil until enriched
    var tmdbId: Int?
    var tmdbName: String?
    var overview: String?
    var posterPath: String?
    var backdropPath: String?
    var firstAirDate: String?

    var dateAdded: Date
    var folder: VideoFolder?

    @Relationship(deleteRule: .cascade, inverse: \Episode.show)
    var episodes: [Episode]

    init(name: String) {
        self.id = UUID()
        self.name = name
        self.dateAdded = Date()
        self.episodes = []
    }

    var displayName: String { tmdbName ?? name }

    var posterURL: URL? { TMDBImage.url(posterPath, size: .posterLarge) }

    var backdropURL: URL? { TMDBImage.url(backdropPath, size: .backdrop) }

    /// Sorted, unique season numbers present in local files.
    var availableSeasons: [Int] {
        Array(Set(episodes.map(\.seasonNumber))).sorted()
    }

    func episodes(for season: Int) -> [Episode] {
        episodes
            .filter { $0.seasonNumber == season }
            .sorted { $0.episodeNumber < $1.episodeNumber }
    }

    /// The imported show matching a TMDB id, if the local library holds one.
    static func first(tmdbId: Int, in context: ModelContext) -> TVShow? {
        var descriptor = FetchDescriptor<TVShow>(predicate: #Predicate { $0.tmdbId == tmdbId })
        descriptor.fetchLimit = 1
        return try? context.fetch(descriptor).first
    }
}
