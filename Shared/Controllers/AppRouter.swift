//
//  AppRouter.swift
//  Edendale
//
//  One typed entry point for links from widgets, App Intents, Finder/Files,
//  and future Spotlight results. The router carries intent only; RootView and
//  ContentView perform navigation/playback through their existing stores.
//

import Foundation
import Observation

enum AppRoute: Equatable, Sendable {
    case search(String)
    case media(MediaRef)
    case localMovie(UUID)
    case localShow(UUID)
    case playMovie(tmdbId: Int)
    case playEpisode(tmdbId: Int)
    case playLocalMovie(UUID)
    case playLocalEpisode(UUID)

    private static let scheme = "edendale"

    init?(url: URL) {
        let isCustomScheme = url.scheme?.lowercased() == Self.scheme
        let isUniversalLink = url.scheme?.lowercased() == "https" && url.host?.lowercased() == AppIdentifiers.linkHost
        
        guard isCustomScheme || isUniversalLink else { return nil }

        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        let segments = url.pathComponents.filter { $0 != "/" }
        
        let head: String?
        let tail: [String]
        
        if isCustomScheme {
            head = url.host?.lowercased()
            tail = segments
        } else {
            head = segments.first?.lowercased()
            tail = segments.isEmpty ? [] : Array(segments.dropFirst())
        }

        switch head {
        case "search":
            let rawQuery = components?.queryItems?.first { $0.name == "q" }?.value ?? ""
            let query = rawQuery.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !query.isEmpty else { return nil }
            self = .search(query)

        case "media":
            guard tail.count == 2,
                  let mediaType = TMDBMediaType(rawValue: tail[0]),
                  let id = Int(tail[1])
            else { return nil }
            self = .media(MediaRef(id: id, mediaType: mediaType))

        case "library":
            guard tail.count == 2, let id = UUID(uuidString: tail[1]) else { return nil }
            switch tail[0] {
            case "movie": self = .localMovie(id)
            case "show": self = .localShow(id)
            default: return nil
            }

        case "play":
            guard tail.count == 2 else { return nil }
            switch tail[0] {
            case "movie":
                guard let id = Int(tail[1]) else { return nil }
                self = .playMovie(tmdbId: id)
            case "episode":
                guard let id = Int(tail[1]) else { return nil }
                self = .playEpisode(tmdbId: id)
            case "local-movie":
                guard let id = UUID(uuidString: tail[1]) else { return nil }
                self = .playLocalMovie(id)
            case "local-episode":
                guard let id = UUID(uuidString: tail[1]) else { return nil }
                self = .playLocalEpisode(id)
            default:
                return nil
            }

        default:
            return nil
        }
    }

    var url: URL? {
        var components = URLComponents()
        components.scheme = Self.scheme

        switch self {
        case .search(let query):
            components.host = "search"
            components.queryItems = [URLQueryItem(name: "q", value: query)]
        case .media(let ref):
            components.host = "media"
            components.path = "/\(ref.mediaType.rawValue)/\(ref.id)"
        case .localMovie(let id):
            components.host = "library"
            components.path = "/movie/\(id.uuidString.lowercased())"
        case .localShow(let id):
            components.host = "library"
            components.path = "/show/\(id.uuidString.lowercased())"
        case .playMovie(let id):
            components.host = "play"
            components.path = "/movie/\(id)"
        case .playEpisode(let id):
            components.host = "play"
            components.path = "/episode/\(id)"
        case .playLocalMovie(let id):
            components.host = "play"
            components.path = "/local-movie/\(id.uuidString.lowercased())"
        case .playLocalEpisode(let id):
            components.host = "play"
            components.path = "/local-episode/\(id.uuidString.lowercased())"
        }
        return components.url
    }
}

@MainActor
@Observable
final class AppRouter {
    struct Request: Identifiable, Equatable {
        let id = UUID()
        let route: AppRoute
    }

    static let shared = AppRouter()

    private(set) var request: Request?
    private(set) var rejectedURLMessage: String?

    private init() {}

    func open(_ route: AppRoute) {
        rejectedURLMessage = nil
        request = Request(route: route)
    }

    @discardableResult
    func open(_ url: URL) -> Bool {
        guard let route = AppRoute(url: url) else {
            rejectedURLMessage = String(localized: "Edendale could not understand this link.")
            return false
        }
        open(route)
        return true
    }

    func consume(_ requestID: UUID) {
        guard request?.id == requestID else { return }
        request = nil
    }
}
