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

/// How bright a theater's room is. The rooms' light is baked into them, so
/// this scales all of it, the screen's glow on the room included; the
/// picture keeps its own brightness.
enum TheaterLights: String, CaseIterable, Identifiable, Sendable {
    case normal
    case dim
    case dark

    var id: String { rawValue }

    /// The share of the baked light the room is drawn with. Light adds up
    /// linearly, so these look brighter than they read: dim is about half
    /// as bright to the eye, dark leaves the room just visible.
    var brightness: Float {
        switch self {
        case .normal: 1
        case .dim: 0.3
        case .dark: 0.06
        }
    }

    var title: String {
        switch self {
        case .normal:
            String(localized: "Normal", table: "Theater",
                   comment: "Theater lights choice: the room as lit by default.")
        case .dim:
            String(localized: "Dim", table: "Theater",
                   comment: "Theater lights choice: the room's lights turned down.")
        case .dark:
            String(localized: "Dark", table: "Theater",
                   comment: "Theater lights choice: the room's lights turned almost off, leaving it just visible.")
        }
    }
}
