//
//  OAuthClient.swift
//  Edendale
//
//  A dependency-free OAuth 2.0 client for public (secret-less) apps, in the
//  spirit of KeychainStore: the authorization-code flow with PKCE (RFC 7636,
//  S256 via CryptoKit), refresh, and the device authorization grant (RFC
//  8628) for Apple TV where a provider allows it. GoogleSignIn, MSAL, and
//  SwiftyDropbox aren't needed. No client secret ships anywhere: client IDs
//  come from Info.plist (see CloudProviders).
//
//  Nothing here logs or puts tokens into error messages.
//

import CryptoKit
import Foundation
import Security

// MARK: - PKCE

nonisolated enum PKCE {
    /// A 43-character verifier from 32 random bytes (the RFC's recommended
    /// entropy), in the unreserved alphabet.
    static func makeVerifier() -> String {
        base64URL(randomBytes(count: 32))
    }

    /// `BASE64URL(SHA256(ASCII(verifier)))`, the S256 challenge.
    static func challenge(for verifier: String) -> String {
        base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    /// An unguessable `state` value tying the callback to this request.
    static func makeState() -> String {
        base64URL(randomBytes(count: 16))
    }

    static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func decodeBase64URL(_ string: String) -> Data? {
        var base64 = string
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while base64.count % 4 != 0 { base64 += "=" }
        return Data(base64Encoded: base64)
    }

    private static func randomBytes(count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return Data(bytes)
    }
}

// MARK: - Configuration

nonisolated struct OAuthConfiguration: Sendable, Equatable {
    let kind: MediaSourceKind
    let clientID: String
    let authorizationEndpoint: URL
    let tokenEndpoint: URL
    /// RFC 8628 endpoint, when the provider allows the scopes Edendale needs
    /// through it (Microsoft yes; Google no, see ENHANCEMENT.md J.7).
    let deviceAuthorizationEndpoint: URL?
    let redirectURI: String
    /// The custom scheme ASWebAuthenticationSession captures.
    let callbackScheme: String
    let scopes: [String]
    /// Extra authorization-request parameters, in order.
    var additionalAuthorizationParameters: [String: String] = [:]
    /// Microsoft wants the scopes repeated when redeeming and refreshing.
    var sendsScopeToTokenEndpoint = false

    var providerName: String { kind.displayName }
}

// MARK: - Responses

nonisolated struct OAuthTokenResponse: Decodable, Sendable, Equatable {
    let accessToken: String
    let tokenType: String?
    let expiresIn: Int?
    let refreshToken: String?
    let scope: String?
    let idToken: String?
    /// Dropbox returns the account with the token.
    let accountId: String?

    init(
        accessToken: String,
        tokenType: String? = "bearer",
        expiresIn: Int? = nil,
        refreshToken: String? = nil,
        scope: String? = nil,
        idToken: String? = nil,
        accountId: String? = nil
    ) {
        self.accessToken = accessToken
        self.tokenType = tokenType
        self.expiresIn = expiresIn
        self.refreshToken = refreshToken
        self.scope = scope
        self.idToken = idToken
        self.accountId = accountId
    }

    /// Granted scopes, when the provider says (space-separated).
    var grantedScopes: [String]? {
        scope.map { $0.split(separator: " ").map(String.init) }
    }
}

/// An RFC 8628 device authorization: what the TV shows while the user
/// approves on another device.
nonisolated struct DeviceAuthorization: Sendable, Equatable {
    let deviceCode: String
    let userCode: String
    let verificationURI: URL
    /// Not every provider sends one (Microsoft doesn't).
    let verificationURIComplete: URL?
    let expiresAt: Date
    /// Seconds between polls; `slow_down` raises it by five.
    var interval: Int
}

private nonisolated struct OAuthErrorBody: Decodable {
    let error: String
    let errorDescription: String?
}

private nonisolated struct DeviceAuthorizationBody: Decodable {
    let deviceCode: String
    let userCode: String
    let verificationUri: String?
    /// Google spells it `verification_url`.
    let verificationUrl: String?
    let verificationUriComplete: String?
    let expiresIn: Int
    let interval: Int?
}

// MARK: - Errors

nonisolated enum OAuthError: Error, LocalizedError, Equatable {
    case notConfigured(provider: String)
    case cancelled
    case stateMismatch
    case missingAuthorizationCode
    /// The user declined on the consent page or the device-code page.
    case authorizationDenied(provider: String)
    /// The refresh token was revoked or expired: sign in again.
    case invalidGrant(provider: String)
    case deviceCodeExpired
    case server(provider: String, code: String, description: String?)
    case http(provider: String, status: Int)
    case malformedResponse(provider: String)

    var errorDescription: String? {
        switch self {
        case .notConfigured(let provider):
            String(localized: "\(provider) isn't set up in this build of Edendale.")
        case .cancelled:
            String(localized: "Sign-in was cancelled.")
        case .stateMismatch, .missingAuthorizationCode:
            String(localized: "The sign-in response couldn't be verified. Try again.")
        case .authorizationDenied(let provider):
            String(localized: "Access to \(provider) wasn't allowed.")
        case .invalidGrant(let provider):
            String(localized: "Your \(provider) sign-in has expired or was revoked. Sign in again.")
        case .deviceCodeExpired:
            String(localized: "The code expired before it was approved. Get a new code and try again.")
        case .server(let provider, let code, let description):
            if let description, !description.isEmpty {
                String(localized: "\(provider) couldn't complete sign-in (\(code)): \(description)")
            } else {
                String(localized: "\(provider) couldn't complete sign-in (\(code)).")
            }
        case .http(let provider, let status):
            String(localized: "\(provider) returned an error (HTTP \(status)).")
        case .malformedResponse(let provider):
            String(localized: "\(provider) sent a response Edendale couldn't read.")
        }
    }
}

// MARK: - Client

nonisolated struct OAuthClient: Sendable {
    let configuration: OAuthConfiguration
    var session: URLSession = .shared

    private var provider: String { configuration.providerName }

    // MARK: Authorization code + PKCE

    func authorizationURL(state: String, codeChallenge: String) -> URL {
        var components = URLComponents(url: configuration.authorizationEndpoint, resolvingAgainstBaseURL: false)!
        var items = [
            URLQueryItem(name: "client_id", value: configuration.clientID),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "redirect_uri", value: configuration.redirectURI),
            URLQueryItem(name: "scope", value: configuration.scopes.joined(separator: " ")),
            URLQueryItem(name: "state", value: state),
            URLQueryItem(name: "code_challenge", value: codeChallenge),
            URLQueryItem(name: "code_challenge_method", value: "S256")
        ]
        for (name, value) in configuration.additionalAuthorizationParameters.sorted(by: { $0.key < $1.key }) {
            items.append(URLQueryItem(name: name, value: value))
        }
        components.queryItems = items
        // `+` in a query value means a space to OAuth servers; URLComponents
        // leaves it alone, so encode it explicitly.
        components.percentEncodedQuery = components.percentEncodedQuery?
            .replacingOccurrences(of: "+", with: "%2B")
        return components.url!
    }

    /// The authorization code from the redirect, after checking `state`
    /// and any error the provider reported.
    static func authorizationCode(
        from callback: URL,
        expectedState: String,
        provider: String
    ) throws -> String {
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func value(_ name: String) -> String? { items.first { $0.name == name }?.value }

        if let error = value("error") {
            if error == "access_denied" { throw OAuthError.authorizationDenied(provider: provider) }
            throw OAuthError.server(provider: provider, code: error, description: value("error_description"))
        }
        guard value("state") == expectedState else { throw OAuthError.stateMismatch }
        guard let code = value("code"), !code.isEmpty else { throw OAuthError.missingAuthorizationCode }
        return code
    }

    func exchange(code: String, verifier: String) async throws -> OAuthTokenResponse {
        var parameters = [
            ("grant_type", "authorization_code"),
            ("code", code),
            ("client_id", configuration.clientID),
            ("redirect_uri", configuration.redirectURI),
            ("code_verifier", verifier)
        ]
        if configuration.sendsScopeToTokenEndpoint {
            parameters.append(("scope", configuration.scopes.joined(separator: " ")))
        }
        return try await tokenRequest(parameters)
    }

    func refresh(refreshToken: String) async throws -> OAuthTokenResponse {
        var parameters = [
            ("grant_type", "refresh_token"),
            ("refresh_token", refreshToken),
            ("client_id", configuration.clientID)
        ]
        if configuration.sendsScopeToTokenEndpoint {
            parameters.append(("scope", configuration.scopes.joined(separator: " ")))
        }
        return try await tokenRequest(parameters)
    }

    // MARK: Device authorization (RFC 8628)

    func startDeviceAuthorization(now: Date = Date()) async throws -> DeviceAuthorization {
        guard let endpoint = configuration.deviceAuthorizationEndpoint else {
            throw OAuthError.notConfigured(provider: provider)
        }
        let (data, status) = try await post(endpoint, form: [
            ("client_id", configuration.clientID),
            ("scope", configuration.scopes.joined(separator: " "))
        ])
        guard (200..<300).contains(status) else { throw error(from: data, status: status) }
        guard let body = try? Self.decoder.decode(DeviceAuthorizationBody.self, from: data),
              let uriString = body.verificationUri ?? body.verificationUrl,
              let uri = URL(string: uriString)
        else { throw OAuthError.malformedResponse(provider: provider) }
        return DeviceAuthorization(
            deviceCode: body.deviceCode,
            userCode: body.userCode,
            verificationURI: uri,
            verificationURIComplete: body.verificationUriComplete.flatMap(URL.init(string:)),
            expiresAt: now.addingTimeInterval(TimeInterval(body.expiresIn)),
            interval: max(body.interval ?? 5, 1)
        )
    }

    enum DevicePollResult: Equatable, Sendable {
        case pending
        case slowDown
        case approved(OAuthTokenResponse)
    }

    /// One poll of the token endpoint. Declined and expired codes throw.
    func pollDeviceAuthorization(_ authorization: DeviceAuthorization) async throws -> DevicePollResult {
        let (data, status) = try await post(configuration.tokenEndpoint, form: [
            ("grant_type", "urn:ietf:params:oauth:grant-type:device_code"),
            ("device_code", authorization.deviceCode),
            ("client_id", configuration.clientID)
        ])
        if (200..<300).contains(status) {
            guard let tokens = try? Self.decoder.decode(OAuthTokenResponse.self, from: data) else {
                throw OAuthError.malformedResponse(provider: provider)
            }
            return .approved(tokens)
        }
        let body = try? Self.decoder.decode(OAuthErrorBody.self, from: data)
        switch body?.error {
        case "authorization_pending": return .pending
        case "slow_down": return .slowDown
        case "access_denied", "authorization_declined": throw OAuthError.authorizationDenied(provider: provider)
        case "expired_token", "code_expired": throw OAuthError.deviceCodeExpired
        default: throw error(from: data, status: status)
        }
    }

    /// Polls until the user approves, declines, or the code expires.
    func waitForDeviceAuthorization(
        _ authorization: DeviceAuthorization,
        now: @Sendable () -> Date = { Date() },
        sleep: @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) async throws -> OAuthTokenResponse {
        var interval = authorization.interval
        while true {
            try await sleep(.seconds(interval))
            guard now() < authorization.expiresAt else { throw OAuthError.deviceCodeExpired }
            switch try await pollDeviceAuthorization(authorization) {
            case .pending: continue
            case .slowDown: interval += 5
            case .approved(let tokens): return tokens
            }
        }
    }

    // MARK: Plumbing

    private func tokenRequest(_ parameters: [(String, String)]) async throws -> OAuthTokenResponse {
        let (data, status) = try await post(configuration.tokenEndpoint, form: parameters)
        guard (200..<300).contains(status) else { throw error(from: data, status: status) }
        guard let tokens = try? Self.decoder.decode(OAuthTokenResponse.self, from: data),
              !tokens.accessToken.isEmpty
        else { throw OAuthError.malformedResponse(provider: provider) }
        return tokens
    }

    private func post(_ url: URL, form: [(String, String)]) async throws -> (Data, Int) {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = Data(Self.formEncode(form).utf8)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, response) = try await session.data(for: request)
        return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
    }

    private func error(from data: Data, status: Int) -> OAuthError {
        guard let body = try? Self.decoder.decode(OAuthErrorBody.self, from: data) else {
            return .http(provider: provider, status: status)
        }
        switch body.error {
        case "invalid_grant": return .invalidGrant(provider: provider)
        case "access_denied": return .authorizationDenied(provider: provider)
        default: return .server(provider: provider, code: body.error, description: body.errorDescription)
        }
    }

    static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }()

    /// `application/x-www-form-urlencoded`, escaping everything outside the
    /// unreserved set (so `+`, `&`, `=`, and spaces survive intact).
    static func formEncode(_ parameters: [(String, String)]) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return parameters.map { name, value in
            let name = name.addingPercentEncoding(withAllowedCharacters: allowed) ?? name
            let value = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
            return "\(name)=\(value)"
        }
        .joined(separator: "&")
    }
}
