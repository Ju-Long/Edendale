//
//  TMDBService.swift
//  Edendale
//
//  TMDB credential precedence, best first:
//    1. A connected account's user access token (Settings → TMDB Account, kept in
//       the iCloud-synchronized keychain — see TMDBAccountStore), as a Bearer header.
//    2. The build-time "API Read Access Token" (Secrets.xcconfig TMDB_READ_ACCESS_TOKEN
//       → Info.plist TMDBReadAccessToken), as a Bearer header.
//    3. The legacy v3 key (TMDB_API_KEY → TMDBAPIKey), as an api_key query param.
//  .secret/Secrets.xcconfig is gitignored — copy Shared/Example.xcconfig there to create it.
//

import Foundation

struct TMDBService {

    static let shared = TMDBService()
    private init() {}

    nonisolated private static let baseURL = "https://api.themoviedb.org/3"

    // convertFromSnakeCase handles all snake_case → camelCase mappings automatically.
    nonisolated private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()

    // The credentials are read from the bundle and the keychain, both safe
    // off the main actor, so library matching can look them up there.

    nonisolated var apiKey: String {
        (Bundle.main.object(forInfoDictionaryKey: "TMDBAPIKey") as? String) ?? ""
    }

    /// TMDB "API Read Access Token" (a long JWT from the same settings page as the key).
    /// Tolerates a pasted "Bearer " prefix — the header is always built here, not in the config.
    nonisolated var readAccessToken: String {
        let raw = (Bundle.main.object(forInfoDictionaryKey: "TMDBReadAccessToken") as? String) ?? ""
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        return trimmed.hasPrefix("Bearer ") ? String(trimmed.dropFirst("Bearer ".count)) : trimmed
    }

    /// The Bearer credential for requests: a connected account's user token wins
    /// over the build-time app token. Empty when only the legacy api_key exists.
    nonisolated private var bearerToken: String {
        let userToken = TMDBUserSession.current?.accessToken ?? ""
        return userToken.isEmpty ? readAccessToken : userToken
    }

    /// False when neither a connected account nor Secrets.xcconfig provides a
    /// credential — browse UIs show a setup state instead of calling out.
    var isConfigured: Bool { !bearerToken.isEmpty || !apiKey.isEmpty }

    // MARK: - Library matching

    // These three run off the main actor (`@concurrent`): library
    // enrichment makes one per title, and the keychain read, the request,
    // and the decoding stay off the main thread the viewer scrolls on.

    /// Returns the best-matching movie result for the given title and optional year.
    @concurrent nonisolated func searchMovie(title: String, year: Int?) async throws -> TMDBMovieResult? {
        var query = [
            URLQueryItem(name: "query", value: title),
            URLQueryItem(name: "include_adult", value: "false")
        ]
        if let year { query.append(URLQueryItem(name: "year", value: "\(year)")) }
        return try await load(TMDBPagedResponse<TMDBMovieResult>.self, path: "/search/movie", query: query).results.first
    }

    /// Returns the best-matching TV show for the given name.
    @concurrent nonisolated func searchTV(name: String) async throws -> TMDBTVResult? {
        let query = [URLQueryItem(name: "query", value: name)]
        return try await load(TMDBPagedResponse<TMDBTVResult>.self, path: "/search/tv", query: query).results.first
    }

    /// Fetches full details for a specific episode.
    @concurrent nonisolated func fetchEpisode(showId: Int, season: Int, episode: Int) async throws -> TMDBEpisodeDetail {
        try await load(
            TMDBEpisodeDetail.self,
            path: "/tv/\(showId)/season/\(season)/episode/\(episode)"
        )
    }

    // MARK: - Private

    /// Authentication happens here for every endpoint: Bearer header when any access
    /// token is set, otherwise the legacy api_key query param. Call sites never pass credentials.
    func fetch<T: Decodable>(_ type: T.Type, path: String, query: [URLQueryItem] = []) async throws -> T {
        try await load(type, path: path, query: query)
    }

    /// `fetch` on whichever executor calls it: the main actor for browsing,
    /// the library-matching lookups' own off it.
    nonisolated private func load<T: Decodable>(_ type: T.Type, path: String, query: [URLQueryItem] = []) async throws -> T {
        let request = try authorizedRequest(path: path, query: query)
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw TMDBError.badStatus(http.statusCode)
        }
        return try Self.decoder.decode(T.self, from: data)
    }

    nonisolated private func authorizedRequest(path: String, query: [URLQueryItem]) throws -> URLRequest {
        let bearer = bearerToken
        guard !bearer.isEmpty || !apiKey.isEmpty else { throw TMDBError.missingCredentials }
        var components = URLComponents(string: "\(Self.baseURL)\(path)")!
        var query = query
        if bearer.isEmpty {
            query.append(URLQueryItem(name: "api_key", value: apiKey))
        }
        components.queryItems = query.isEmpty ? nil : query
        guard let url = components.url else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        if !bearer.isEmpty {
            request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        }
        return request
    }
}

nonisolated enum TMDBError: Error, LocalizedError {
    case missingCredentials
    case badStatus(Int)

    var errorDescription: String? {
        switch self {
        case .missingCredentials: String(localized: "No TMDB credentials configured.")
        case .badStatus(let code): String(localized: "TMDB request failed (HTTP \(code)).")
        }
    }
}

// MARK: - Response types

nonisolated struct TMDBPagedResponse<T: Decodable>: Decodable {
    let results: [T]
    /// Present on list endpoints (search/discover); nil on single-object appends.
    let totalPages: Int?
}

nonisolated struct TMDBMovieResult: Decodable, Sendable {
    let id: Int
    let title: String
    let releaseDate: String?
    let overview: String
    let posterPath: String?
    let backdropPath: String?
    let voteAverage: Double?
}

nonisolated struct TMDBTVResult: Decodable, Sendable {
    let id: Int
    let name: String
    let firstAirDate: String?
    let overview: String
    let posterPath: String?
    let backdropPath: String?
}

nonisolated struct TMDBEpisodeDetail: Decodable, Identifiable, Hashable, Sendable {
    let id: Int
    let name: String
    let overview: String?
    let stillPath: String?
    let episodeNumber: Int
    let seasonNumber: Int
    let airDate: String?
    let voteAverage: Double?
    /// Present on the season endpoint; absent from some episode payloads.
    let runtime: Int?

    var stillURL: URL? { TMDBImage.url(stillPath, size: .still) }

    /// "S01E02", matching the local library's `Episode.episodeCode`.
    var episodeCode: String {
        String(format: "S%02dE%02d", seasonNumber, episodeNumber)
    }
}
