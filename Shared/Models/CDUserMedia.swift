//
//  CDUserMedia.swift
//  Edendale
//
//  CoreData managed object for iCloud-synced favourite/rating state, keyed by
//  TMDB id + media type. `inWatchlist` remains only to migrate older stores;
//  `watchlist` is the 27.1 portable flag. Each row carries its sync stamp
//  and writing device (SYNC-PROTOCOL.md §6.2), and an all-empty row is a
//  tombstone rather than a deletion.
//

import CoreData

@objc(CDUserMedia)
public class CDUserMedia: NSManagedObject {
    @NSManaged public var tmdbId: Int32
    @NSManaged public var mediaType: String
    @NSManaged public var isFavorite: Bool
    @NSManaged public var inWatchlist: Bool
    /// 0 means unrated — CloudKit forbids optional scalars with constraints,
    /// and TMDB's own scale starts at 0.5, so 0 is safely out of band.
    @NSManaged public var rating: Double
    @NSManaged public var updatedAt: Date?
    @NSManaged public var updateTime: Int64
    @NSManaged public var deviceID: String?
    @NSManaged public var review: String?
    @NSManaged public var watchlist: Bool
}

// MARK: - DTO Conversion

extension CDUserMedia {

    /// Convert managed object → value-type DTO.
    func toDTO() -> UserMediaState {
        UserMediaState(
            ref: MediaRef(
                id: Int(tmdbId),
                mediaType: TMDBMediaType(rawValue: mediaType) ?? .movie
            ),
            isFavorite: isFavorite,
            isInWatchlist: watchlist,
            rating: rating > 0 ? rating : nil,
            review: review,
            updatedAt: updatedAt ?? Date(),
            updateTime: updateTime,
            deviceID: deviceID
        )
    }

    /// Apply values from a DTO onto this managed object.
    func apply(_ dto: UserMediaState) {
        tmdbId = Int32(dto.ref.id)
        mediaType = dto.ref.mediaType.rawValue
        isFavorite = dto.isFavorite
        inWatchlist = false
        rating = dto.rating ?? 0
        updatedAt = dto.updatedAt
        updateTime = dto.updateTime
        deviceID = dto.deviceID
        review = dto.review
        watchlist = dto.isInWatchlist
    }

    /// Fetch request matching a specific title.
    static func fetchRequest(ref: MediaRef) -> NSFetchRequest<CDUserMedia> {
        let request = NSFetchRequest<CDUserMedia>(entityName: "CDUserMedia")
        request.predicate = NSPredicate(
            format: "tmdbId == %d AND mediaType == %@",
            Int32(ref.id),
            ref.mediaType.rawValue
        )
        request.fetchLimit = 1
        return request
    }
}
