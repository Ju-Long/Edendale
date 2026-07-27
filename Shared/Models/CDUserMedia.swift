//
//  CDUserMedia.swift
//  Edendale
//
//  CoreData managed object for iCloud-synced per-title account state
//  (favourite / watchlist / rating), keyed by TMDB id + media type.
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
            inWatchlist: inWatchlist,
            rating: rating > 0 ? rating : nil,
            updatedAt: updatedAt ?? Date()
        )
    }

    /// Apply values from a DTO onto this managed object.
    func apply(_ dto: UserMediaState) {
        tmdbId = Int32(dto.ref.id)
        mediaType = dto.ref.mediaType.rawValue
        isFavorite = dto.isFavorite
        inWatchlist = dto.inWatchlist
        rating = dto.rating ?? 0
        updatedAt = dto.updatedAt
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
