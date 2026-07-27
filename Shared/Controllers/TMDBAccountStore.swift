//
//  TMDBAccountStore.swift
//  Edendale
//
//  Connects a personal TMDB account through the v4 auth flow: create a request
//  token → the user approves it on themoviedb.org (signing in or signing up
//  there) → exchange it for a permanent user access token. The token lives in
//  the iCloud-synchronized keychain (KeychainStore), so other devices on the
//  same iCloud account are signed in automatically, and TMDBService prefers it
//  over the build-time credentials.
//
//  Content data still comes from v3 (the only version with content endpoints);
//  besides auth, v4 also serves the signed-in account's lists — favorites,
//  watchlist, ratings, recommendations — through TMDBAccountClient.
//

import Foundation

@Observable @MainActor
final class TMDBAccountStore {

    enum Phase: Equatable {
        case signedOut
        /// The approval page is open in the user's browser; we hold the request
        /// token until they come back and complete the exchange.
        case awaitingApproval(requestToken: String)
        case exchanging
        case signedIn
    }

    private(set) var phase: Phase
    private(set) var lastError: String?

    init() {
        phase = TMDBUserSession.current == nil ? .signedOut : .signedIn
    }

    var isSignedIn: Bool { phase == .signedIn }

    /// The short-lived TMDB approval page shown in the browser and encoded in
    /// the QR code. It can be opened on another device because approval is
    /// tied to the request token rather than to the browser that opens it.
    var pendingApprovalURL: URL? {
        guard case .awaitingApproval(let requestToken) = phase else { return nil }
        return Self.approvalURL(for: requestToken)
    }

    /// The v4 auth endpoints only accept Bearer auth, so sign-in needs the
    /// app-level read access token — a legacy api_key alone cannot start it.
    var canSignIn: Bool { !TMDBService.shared.readAccessToken.isEmpty }

    // MARK: - Flow

    /// Step 1: create a request token and return the themoviedb.org page where
    /// the user signs in (or creates an account) and approves Edendale.
    func beginSignIn() async -> URL? {
        lastError = nil
        do {
            let token = try await TMDBAuthClient().createRequestToken()
            phase = .awaitingApproval(requestToken: token)
            return Self.approvalURL(for: token)
        } catch {
            lastError = error.localizedDescription
            phase = .signedOut
            return nil
        }
    }

    /// Step 2, after the user approved in the browser: exchange the request
    /// token for the permanent user access token and persist it.
    func completeSignIn() async {
        guard case .awaitingApproval(let requestToken) = phase else { return }
        phase = .exchanging
        lastError = nil
        do {
            let session = try await TMDBAuthClient().createAccessToken(requestToken: requestToken)
            try TMDBUserSession.save(session)
            phase = .signedIn
        } catch TMDBError.badStatus(let code) where code == 400 || code == 401 {
            // TMDB answers 4xx while the request token is still unapproved.
            lastError = String(localized: "Not approved yet — finish approving Edendale in your browser, then try again.")
            phase = .awaitingApproval(requestToken: requestToken)
        } catch {
            lastError = error.localizedDescription
            phase = .awaitingApproval(requestToken: requestToken)
        }
    }

    func cancelSignIn() {
        phase = .signedOut
        lastError = nil
    }

    private static func approvalURL(for requestToken: String) -> URL? {
        var components = URLComponents(string: "https://www.themoviedb.org/auth/access")
        components?.queryItems = [URLQueryItem(name: "request_token", value: requestToken)]
        return components?.url
    }

    /// Revokes the token with TMDB (best effort) and forgets it everywhere,
    /// along with the derived v3 session (see TMDBAccountClient).
    func signOut() async {
        if let session = TMDBUserSession.current {
            try? await TMDBAuthClient().deleteAccessToken(session.accessToken)
        }
        TMDBUserSession.clear()
        TMDBV3Session.clear()
        phase = .signedOut
        lastError = nil
    }
}

// MARK: - Persisted session

/// The credential for a connected TMDB account. Stored as one keychain item so
/// token and account id sync atomically through iCloud Keychain.
struct TMDBUserSession: Codable, Sendable {
    let accessToken: String
    /// TMDB v4 account object id — unused today, kept for future account
    /// features (lists, favorites) so signing in again isn't required.
    let accountId: String

    /// Reads the keychain every time on purpose: a token that arrives via
    /// iCloud sync (e.g. signed in on iPhone, browsing on Apple TV) is picked
    /// up by the next request without a relaunch.
    static var current: Self? {
        KeychainStore.shared.data(for: .tmdbUserSession)
            .flatMap { try? JSONDecoder().decode(Self.self, from: $0) }
    }

    static func save(_ session: Self) throws {
        try KeychainStore.shared.set(JSONEncoder().encode(session), for: .tmdbUserSession)
    }

    static func clear() {
        KeychainStore.shared.remove(.tmdbUserSession)
    }
}

// MARK: - v4 auth client

private struct TMDBAuthClient {

    private static let baseURL = "https://api.themoviedb.org/4/auth"

    func createRequestToken() async throws -> String {
        struct Response: Decodable { let requestToken: String }
        let response: Response = try await send("POST", path: "/request_token")
        return response.requestToken
    }

    func createAccessToken(requestToken: String) async throws -> TMDBUserSession {
        struct Response: Decodable { let accessToken: String; let accountId: String }
        let response: Response = try await send(
            "POST", path: "/access_token", body: ["request_token": requestToken]
        )
        return TMDBUserSession(accessToken: response.accessToken, accountId: response.accountId)
    }

    func deleteAccessToken(_ accessToken: String) async throws {
        struct Response: Decodable { let success: Bool? }
        let _: Response = try await send(
            "DELETE", path: "/access_token", body: ["access_token": accessToken]
        )
    }

    /// All three auth endpoints authenticate with the app-level read access token.
    private func send<T: Decodable>(
        _ method: String, path: String, body: [String: String] = [:]
    ) async throws -> T {
        let appToken = TMDBService.shared.readAccessToken
        guard !appToken.isEmpty else { throw TMDBError.missingCredentials }
        var request = URLRequest(url: URL(string: Self.baseURL + path)!)
        request.httpMethod = method
        request.setValue("Bearer \(appToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw TMDBError.badStatus(http.statusCode)
        }
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return try decoder.decode(T.self, from: data)
    }
}
