//
//  CDWatchProgress.swift
//  Edendale
//
//  CoreData managed object for iCloud-synced watch progress.
//

import CoreData

@objc(CDWatchProgress)
public class CDWatchProgress: NSManagedObject {
    @NSManaged public var tmdbId: Int32
    @NSManaged public var mediaType: String
    @NSManaged public var position: Double
    @NSManaged public var watchedSeconds: Double
    @NSManaged public var lastWatchedAt: Date?
    @NSManaged public var isCompleted: Bool
    @NSManaged public var showTmdbId: Int32
    @NSManaged public var seasonNumber: Int32
    @NSManaged public var episodeNumber: Int32
}

// MARK: - DTO Conversion

extension CDWatchProgress {

    /// Convert managed object → value-type DTO.
    func toDTO() -> WatchProgress {
        WatchProgress(
            tmdbId: Int(tmdbId),
            mediaType: WatchMediaType(rawValue: mediaType) ?? .movie,
            position: position,
            watchedSeconds: watchedSeconds,
            isCompleted: isCompleted,
            showTmdbId: showTmdbId != 0 ? Int(showTmdbId) : nil,
            seasonNumber: seasonNumber != 0 ? Int(seasonNumber) : nil,
            episodeNumber: episodeNumber != 0 ? Int(episodeNumber) : nil,
            lastWatchedAt: lastWatchedAt ?? Date()
        )
    }

    /// Apply values from a DTO onto this managed object.
    func apply(_ dto: WatchProgress) {
        tmdbId = Int32(dto.tmdbId)
        mediaType = dto.mediaType.rawValue
        position = dto.position
        watchedSeconds = dto.watchedSeconds
        lastWatchedAt = dto.lastWatchedAt
        isCompleted = dto.isCompleted
        showTmdbId = Int32(dto.showTmdbId ?? 0)
        seasonNumber = Int32(dto.seasonNumber ?? 0)
        episodeNumber = Int32(dto.episodeNumber ?? 0)
    }

    /// Fetch request matching a specific tmdbId + mediaType pair.
    static func fetchRequest(
        tmdbId: Int,
        mediaType: WatchMediaType
    ) -> NSFetchRequest<CDWatchProgress> {
        let request = NSFetchRequest<CDWatchProgress>(entityName: "CDWatchProgress")
        request.predicate = NSPredicate(
            format: "tmdbId == %d AND mediaType == %@",
            Int32(tmdbId),
            mediaType.rawValue
        )
        request.fetchLimit = 1
        return request
    }
}
