//
//  CloudTokenProvider.swift
//  Edendale
//
//  Hands out access tokens for linked accounts. Tokens live only in memory;
//  the refresh token stays in the Keychain (CloudAccountVault). At most one
//  refresh runs per account, and every caller waiting on it gets its result,
//  so a burst of 401s from parallel listing or streaming requests triggers
//  a single refresh.
//

import Foundation

actor CloudTokenProvider {

    static let shared = CloudTokenProvider()

    struct Token: Sendable, Equatable {
        let value: String
        let expiresAt: Date
    }

    private let vault: CloudAccountVault
    private let session: URLSession
    private let configuration: @Sendable (MediaSourceKind) -> OAuthConfiguration?
    private let now: @Sendable () -> Date

    private var cache: [String: Token] = [:]
    private var refreshes: [String: Task<Token, Error>] = [:]

    /// Refreshes this long before a token's stated expiry.
    private static let expiryMargin: TimeInterval = 120

    init(
        vault: CloudAccountVault = .shared,
        session: URLSession = .shared,
        configuration: @escaping @Sendable (MediaSourceKind) -> OAuthConfiguration? = {
            CloudProviders.configuration(for: $0)
        },
        now: @escaping @Sendable () -> Date = { Date() }
    ) {
        self.vault = vault
        self.session = session
        self.configuration = configuration
        self.now = now
    }

    /// A valid access token for the account. Pass the token a provider just
    /// refused (HTTP 401) as `rejecting`: it is refreshed, unless another
    /// caller has already replaced it.
    func accessToken(
        kind: MediaSourceKind,
        accountKey: String,
        rejecting rejected: String? = nil
    ) async throws -> String {
        let cacheKey = Self.cacheKey(kind: kind, accountKey: accountKey)
        if let token = cache[cacheKey], token.value != rejected,
           token.expiresAt.timeIntervalSince(now()) > Self.expiryMargin {
            return token.value
        }
        if let running = refreshes[cacheKey] {
            return try await running.value.value
        }

        let task = Task { try await self.refresh(kind: kind, accountKey: accountKey) }
        refreshes[cacheKey] = task
        do {
            let token = try await task.value
            refreshes[cacheKey] = nil
            cache[cacheKey] = token
            return token.value
        } catch {
            refreshes[cacheKey] = nil
            throw error
        }
    }

    /// Seeds the cache with the token a sign-in just produced.
    func store(_ tokens: OAuthTokenResponse, for account: CloudAccount) {
        cache[Self.cacheKey(kind: account.kind, accountKey: account.key)] = Token(
            value: tokens.accessToken,
            expiresAt: now().addingTimeInterval(TimeInterval(tokens.expiresIn ?? 3600))
        )
    }

    /// Drops the cached token after a sign-out.
    func forget(kind: MediaSourceKind, accountKey: String) {
        let cacheKey = Self.cacheKey(kind: kind, accountKey: accountKey)
        cache[cacheKey] = nil
        refreshes[cacheKey]?.cancel()
        refreshes[cacheKey] = nil
    }

    // MARK: - Refresh

    private func refresh(kind: MediaSourceKind, accountKey: String) async throws -> Token {
        guard let account = vault.account(kind: kind, key: accountKey) else {
            throw ConnectorError.signInRequired(provider: kind.displayName)
        }
        guard let configuration = configuration(kind) else {
            throw ConnectorError.notConfigured(provider: kind.displayName)
        }
        let client = OAuthClient(configuration: configuration, session: session)
        let response: OAuthTokenResponse
        do {
            response = try await client.refresh(refreshToken: account.refreshToken)
        } catch OAuthError.invalidGrant {
            throw ConnectorError.signInRequired(provider: kind.displayName)
        }
        // Microsoft rotates refresh tokens; keep the newest one.
        if let rotated = response.refreshToken, rotated != account.refreshToken {
            var updated = account
            updated.refreshToken = rotated
            try? vault.save(updated)
        }
        return Token(
            value: response.accessToken,
            expiresAt: now().addingTimeInterval(TimeInterval(response.expiresIn ?? 3600))
        )
    }

    private static func cacheKey(kind: MediaSourceKind, accountKey: String) -> String {
        "\(kind.rawValue):\(accountKey)"
    }
}
