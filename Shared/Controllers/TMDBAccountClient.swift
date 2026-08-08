//
//  TMDBAccountClient.swift
//  Edendale
//
//  Account-scoped TMDB endpoints for the connected user (TMDBAccountStore):
//
//    Reads  — v4 `/account/{account_object_id}/…` (favorites, watchlist,
//             rated, recommendations), authenticated with the user access
//             token. These are the only account lists v4 offers.
//    Writes — v3 `/account/{account_id}/favorite`, `…/watchlist` and
//             `/{movie|tv}/{id}/rating`, since v4 has no write endpoints for
//             them. v3 writes need a session: the v4 user access token is
//             converted once via `/3/authentication/session/convert/4` and
//             the resulting session (+ v3 account id) is cached in the
//             iCloud keychain next to the user token.
//
//  Browse/content endpoints stay in TMDBService/TMDBBrowse — this file is
//  only for data that belongs to the signed-in account.
//

import Foundation

struct TMDBAccountClient: Sendable {

    private static let v3BaseURL = "https://api.themoviedb.org/3"
    private static let v4BaseURL = "https://api.themoviedb.org/4"

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()

    // MARK: - v4 reads

    /// Titles TMDB recommends to this user, based on their ratings and
    /// favorites — drives the signed-in hero.
    func recommendations(_ type: TMDBMediaType) async throws -> [TMDBMediaItem] {
        try await fetchV4List("/\(type.rawValue)/recommendations", as: TMDBMediaItemRaw.self)
            .compactMap { $0.item(defaultType: type) }
    }

    func favorites(_ type: TMDBMediaType) async throws -> [MediaRef] {
        try await fetchV4List("/\(type.rawValue)/favorites", as: IDOnly.self)
            .map { MediaRef(id: $0.id, mediaType: type) }
    }

    func watchlist(_ type: TMDBMediaType) async throws -> [MediaRef] {
        try await watchlistItems(type).map(\.ref)
    }

    /// Full list snapshots are persisted locally so Watchlist remains useful
    /// offline and does not need one detail request per title after a pull.
    func watchlistItems(_ type: TMDBMediaType) async throws -> [TMDBMediaItem] {
        try await fetchV4List("/\(type.rawValue)/watchlist", as: TMDBMediaItemRaw.self)
            .compactMap { $0.item(defaultType: type) }
    }

    /// Titles the user rated on TMDB, with their rating value.
    func rated(_ type: TMDBMediaType) async throws -> [(ref: MediaRef, rating: Double)] {
        try await fetchV4List("/\(type.rawValue)/rated", as: RatedItem.self)
            .compactMap { item in
                item.accountRating.map { (MediaRef(id: item.id, mediaType: type), $0.value) }
            }
    }

    // MARK: - v3 per-title read

    /// The account's current state for one title — favourite, watchlist
    /// membership, and rating — in a single request. v4 has no per-title
    /// equivalent, so this reads v3 `/{movie|tv}/{id}/account_states`.
    func accountState(for ref: MediaRef) async throws -> TMDBAccountState {
        let session = try await ensureV3Session()
        guard let user = TMDBUserSession.current else { throw TMDBError.missingCredentials }
        let raw: AccountStatesResponse = try await send(
            "GET",
            url: "\(Self.v3BaseURL)/\(ref.mediaType.rawValue)/\(ref.id)/account_states?session_id=\(session.sessionId)",
            bearer: user.accessToken
        )
        return TMDBAccountState(
            isFavorite: raw.favorite ?? false,
            inWatchlist: raw.watchlist ?? false,
            rating: raw.rated?.value
        )
    }

    // MARK: - v3 writes

    func setFavorite(_ favorite: Bool, for ref: MediaRef) async throws {
        let session = try await ensureV3Session()
        try await postV3(
            "/account/\(session.accountId)/favorite",
            sessionId: session.sessionId,
            body: MediaFlagBody(mediaType: ref.mediaType.rawValue, mediaId: ref.id, favorite: favorite)
        )
    }

    func setWatchlist(_ inWatchlist: Bool, for ref: MediaRef) async throws {
        let session = try await ensureV3Session()
        try await postV3(
            "/account/\(session.accountId)/watchlist",
            sessionId: session.sessionId,
            body: MediaFlagBody(mediaType: ref.mediaType.rawValue, mediaId: ref.id, watchlist: inWatchlist)
        )
    }

    /// Sets (0.5...10) or clears (nil) the user's rating for a title.
    func setRating(_ value: Double?, for ref: MediaRef) async throws {
        let session = try await ensureV3Session()
        let path = "/\(ref.mediaType.rawValue)/\(ref.id)/rating"
        if let value {
            try await postV3(path, sessionId: session.sessionId, body: RatingBody(value: value))
        } else {
            try await postV3(path, sessionId: session.sessionId, body: nil as RatingBody?, method: "DELETE")
        }
    }

    // MARK: - v3 session

    /// v3 writes authenticate with a session id. One is created from the v4
    /// user access token on first use and cached in the iCloud keychain, so
    /// the conversion happens once per account, not per device.
    private func ensureV3Session() async throws -> TMDBV3Session {
        if let cached = TMDBV3Session.current { return cached }
        guard let user = TMDBUserSession.current else { throw TMDBError.missingCredentials }

        struct ConvertResponse: Decodable { let sessionId: String }
        let convert: ConvertResponse = try await send(
            "POST",
            url: "\(Self.v3BaseURL)/authentication/session/convert/4",
            bearer: user.accessToken,
            body: try JSONEncoder().encode(["access_token": user.accessToken])
        )

        struct AccountResponse: Decodable { let id: Int }
        let account: AccountResponse = try await send(
            "GET",
            url: "\(Self.v3BaseURL)/account?session_id=\(convert.sessionId)",
            bearer: user.accessToken
        )

        let session = TMDBV3Session(sessionId: convert.sessionId, accountId: account.id)
        try? TMDBV3Session.save(session)
        return session
    }

    // MARK: - Requests

    /// Pulls every page of a v4 account list.
    private func fetchV4List<T: Decodable>(_ path: String, as type: T.Type) async throws -> [T] {
        guard let user = TMDBUserSession.current else { throw TMDBError.missingCredentials }
        let base = "\(Self.v4BaseURL)/account/\(user.accountId)\(path)"

        var items: [T] = []
        var page = 1
        while true {
            let response: Page<T> = try await send(
                "GET", url: "\(base)?page=\(page)", bearer: user.accessToken
            )
            items += response.results
            guard page < (response.totalPages ?? 1) else { break }
            page += 1
        }
        return items
    }

    private func postV3<Body: Encodable>(
        _ path: String, sessionId: String, body: Body?, method: String = "POST"
    ) async throws {
        guard let user = TMDBUserSession.current else { throw TMDBError.missingCredentials }
        let encoder = JSONEncoder()
        encoder.keyEncodingStrategy = .convertToSnakeCase
        let _: StatusResponse = try await send(
            method,
            url: "\(Self.v3BaseURL)\(path)?session_id=\(sessionId)",
            bearer: user.accessToken,
            body: body.map { try encoder.encode($0) }
        )
    }

    private func send<T: Decodable>(
        _ method: String, url: String, bearer: String, body: Data? = nil
    ) async throws -> T {
        guard let url = URL(string: url) else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization")
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw TMDBError.badStatus(http.statusCode)
        }
        return try Self.decoder.decode(T.self, from: data)
    }

    // MARK: - Wire types

    private struct Page<T: Decodable>: Decodable {
        let results: [T]
        let totalPages: Int?
    }

    private struct IDOnly: Decodable { let id: Int }

    private struct RatedItem: Decodable {
        let id: Int
        let accountRating: AccountRating?
        struct AccountRating: Decodable { let value: Double }
    }

    private struct MediaFlagBody: Encodable {
        let mediaType: String
        let mediaId: Int
        var favorite: Bool?
        var watchlist: Bool?

        init(mediaType: String, mediaId: Int, favorite: Bool? = nil, watchlist: Bool? = nil) {
            self.mediaType = mediaType
            self.mediaId = mediaId
            self.favorite = favorite
            self.watchlist = watchlist
        }
    }

    private struct RatingBody: Encodable { let value: Double }

    private struct StatusResponse: Decodable { let statusCode: Int? }

    /// v3 `/account_states` payload. `rated` is `false` when unrated and an
    /// object `{ "value": N }` when rated, so it decodes leniently.
    private struct AccountStatesResponse: Decodable {
        let favorite: Bool?
        let watchlist: Bool?
        let rated: Rated?

        struct Rated: Decodable {
            let value: Double?
            init(from decoder: Decoder) throws {
                let container = try decoder.singleValueContainer()
                if let object = try? container.decode([String: Double].self) {
                    value = object["value"]
                } else {
                    value = nil // `false` — unrated
                }
            }
        }
    }
}

// MARK: - Per-title account state

/// The connected account's state for a single title, read in one request.
struct TMDBAccountState: Sendable {
    let isFavorite: Bool
    let inWatchlist: Bool
    /// The user's TMDB rating (0.5...10), or nil when unrated.
    let rating: Double?
}

// MARK: - Cached v3 session

/// The v3 session derived from the v4 user access token, plus the v3 account
/// id the write endpoints address. Kept in the iCloud keychain so it follows
/// the user token everywhere and dies with it on sign-out.
struct TMDBV3Session: Codable, Sendable {
    let sessionId: String
    let accountId: Int

    static var current: Self? {
        KeychainStore.shared.data(for: .tmdbV3Session)
            .flatMap { try? JSONDecoder().decode(Self.self, from: $0) }
    }

    static func save(_ session: Self) throws {
        try KeychainStore.shared.set(JSONEncoder().encode(session), for: .tmdbV3Session)
    }

    static func clear() {
        KeychainStore.shared.remove(.tmdbV3Session)
    }
}
