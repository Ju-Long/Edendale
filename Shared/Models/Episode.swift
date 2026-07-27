//
//  Episode.swift
//  Edendale
//

import Foundation
import SwiftData

@Model
final class Episode {
    var id: UUID
    /// Title derived from the filename before TMDB enrichment.
    var localTitle: String
    var filePath: String
    var bookmarkData: Data?

    var seasonNumber: Int
    var episodeNumber: Int

    // TMDB metadata — nil until enriched
    var tmdbId: Int?
    var episodeTitle: String?
    var overview: String?
    var stillPath: String?
    var airDate: String?
    var voteAverage: Double?

    var duration: TimeInterval
    var dateAdded: Date
    /// True when the file lives in iCloud Drive and may not be locally present.
    var isICloudItem: Bool

    var show: TVShow?

    init(
        localTitle: String,
        filePath: String,
        bookmarkData: Data? = nil,
        seasonNumber: Int,
        episodeNumber: Int,
        isICloudItem: Bool = false
    ) {
        self.id = UUID()
        self.localTitle = localTitle
        self.filePath = filePath
        self.bookmarkData = bookmarkData
        self.seasonNumber = seasonNumber
        self.episodeNumber = episodeNumber
        self.isICloudItem = isICloudItem
        self.duration = 0
        self.dateAdded = Date()
    }

    var displayTitle: String { episodeTitle ?? localTitle }

    var episodeCode: String { String(format: "S%02dE%02d", seasonNumber, episodeNumber) }

    var stillURL: URL? { TMDBImage.url(stillPath, size: .still) }

    var formattedDuration: String {
        duration > 0 ? PlayerLogic.timestamp(duration) : "--:--"
    }
}
