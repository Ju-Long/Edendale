//
//  TheaterEnvironment.swift
//  Edendale
//
//  The rooms a visionOS viewer can watch in. Each one is a USDZ exported
//  from the `scenes` branch with its lighting baked in; it ships in
//  `Shared/Theaters/` and only the visionOS app bundles it.
//

import Foundation

enum TheaterEnvironment: String, CaseIterable, Identifiable, Sendable {
    case movieTheatre = "movie-theatre"
    case projectorRoom = "projector-room"
    case performanceTheatre = "performance-theatre"

    var id: String { rawValue }

    /// The USDZ's name in the app bundle, which is also the scene's folder
    /// on the `scenes` branch.
    var resourceName: String { rawValue }

    var title: String {
        switch self {
        case .movieTheatre:
            String(localized: "Movie Theatre", table: "Theater",
                   comment: "A stadium cinema the viewer can watch in on Apple Vision Pro.")
        case .projectorRoom:
            String(localized: "Projector Room", table: "Theater",
                   comment: "A 1920s hall with a reel projector the viewer can watch in on Apple Vision Pro.")
        case .performanceTheatre:
            String(localized: "Performance Theatre", table: "Theater",
                   comment: "A proscenium theatre with drapes and balconies the viewer can watch in on Apple Vision Pro.")
        }
    }
}
