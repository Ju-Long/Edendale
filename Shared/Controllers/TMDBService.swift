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
//  Secrets.xcconfig is gitignored — run the root setup script to generate it with secrets.json.
//

import Foundation

struct TMDBService {

    static let shared = TMDBService()
    private init() {}

    private static let baseURL = "https://api.themoviedb.org/3"

    // convertFromSnakeCase handles all snake_case → camelCase mappings automatically.
    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()

    var apiKey: String {
        (Bundle.main.object(forInfoDictionaryKey: "TMDBAPIKey") as? String) ?? ""
    }

    /// TMDB "API Read Access Token" (a long JWT from the same settings page as the key).
    /// Tolerates a pasted "Bearer " prefix — the header is always built here, not in the config.
    var readAccessToken: String {
        let raw = (Bundle.main.object(forInfoDictionaryKey: "TMDBReadAccessToken") as? String) ?? ""
        let trimmed = raw.trimmingCharacters(in: .whitespaces)
        return trimmed.hasPrefix("Bearer ") ? String(trimmed.dropFirst("Bearer ".count)) : trimmed
    }

    /// The Bearer credential for requests: a connected account's user token wins
    /// over the build-time app token. Empty when only the legacy api_key exists.
    private var bearerToken: String {
        let userToken = TMDBUserSession.current?.accessToken ?? ""
        return userToken.isEmpty ? readAccessToken : userToken
    }

    /// False when neither a connected account nor Secrets.xcconfig provides a
    /// credential — browse UIs show a setup state instead of calling out.
    var isConfigured: Bool { !bearerToken.isEmpty || !apiKey.isEmpty }

    // MARK: - Movie

    /// Returns the best-matching movie result for the given title and optional year.
    func searchMovie(title: String, year: Int?) async throws -> TMDBMovieResult? {
        var query = [
            URLQueryItem(name: "query", value: title),
            URLQueryItem(name: "include_adult", value: "false")
        ]
        if let year { query.append(URLQueryItem(name: "year", value: "\(year)")) }
        return try await fetch(TMDBPagedResponse<TMDBMovieResult>.self, path: "/search/movie", query: query).results.first
    }

    // MARK: - TV

    /// Returns the best-matching TV show for the given name.
    func searchTV(name: String) async throws -> TMDBTVResult? {
        let query = [URLQueryItem(name: "query", value: name)]
        return try await fetch(TMDBPagedResponse<TMDBTVResult>.self, path: "/search/tv", query: query).results.first
    }

    /// Fetches full details for a specific episode.
    func fetchEpisode(showId: Int, season: Int, episode: Int) async throws -> TMDBEpisodeDetail {
        try await fetch(
            TMDBEpisodeDetail.self,
            path: "/tv/\(showId)/season/\(season)/episode/\(episode)"
        )
    }

    // MARK: - Private

    /// Authentication happens here for every endpoint: Bearer header when any access
    /// token is set, otherwise the legacy api_key query param. Call sites never pass credentials.
    func fetch<T: Decodable>(_ type: T.Type, path: String, query: [URLQueryItem] = []) async throws -> T {
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
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw TMDBError.badStatus(http.statusCode)
        }
        return try Self.decoder.decode(T.self, from: data)
    }
}

enum TMDBError: Error, LocalizedError {
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

struct TMDBPagedResponse<T: Decodable>: Decodable {
    let results: [T]
    /// Present on list endpoints (search/discover); nil on single-object appends.
    let totalPages: Int?
}

struct TMDBMovieResult: Decodable, Sendable {
    let id: Int
    let title: String
    let releaseDate: String?
    let overview: String
    let posterPath: String?
    let backdropPath: String?
    let voteAverage: Double?
}

struct TMDBTVResult: Decodable, Sendable {
    let id: Int
    let name: String
    let firstAirDate: String?
    let overview: String
    let posterPath: String?
    let backdropPath: String?
}

struct TMDBEpisodeDetail: Decodable, Identifiable, Hashable, Sendable {
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
