//
//  OAuthTests.swift
//  EdendaleTests
//
//  The dependency-free OAuth client: the RFC 7636 PKCE vector, each
//  provider's authorization URL, token and device-code responses (RFC
//  8628: pending, slow_down, declined, expired), and CloudTokenProvider's
//  single-flight refresh. Token endpoints are URLProtocol stubs; no real
//  credential is used anywhere.
//

import Foundation
import Testing
@testable import Edendale

@Suite struct PKCETests {

    @Test func matchesTheRFC7636AppendixBVector() {
        #expect(PKCE.challenge(for: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
            == "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    @Test func verifiersAreLongUnreservedAndUnique() {
        let verifier = PKCE.makeVerifier()
        #expect(verifier.count == 43)
        let unreserved = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        #expect(verifier.unicodeScalars.allSatisfy { unreserved.contains($0) })
        #expect(PKCE.makeVerifier() != verifier)
        #expect(PKCE.makeState() != PKCE.makeState())
    }

    @Test func decodesJWTClaims() throws {
        let payload = PKCE.base64URL(Data(#"{"sub":"110169484474386276334","email":"me@example.com"}"#.utf8))
        let claims = try #require(CloudProviders.decodeJWTClaims("eyJhbGciOiJSUzI1NiJ9.\(payload).signature"))
        #expect(claims["sub"] as? String == "110169484474386276334")
        #expect(claims["email"] as? String == "me@example.com")
        #expect(CloudProviders.decodeJWTClaims("not-a-jwt") == nil)
    }
}

@Suite struct OAuthClientTests {

    /// A configuration whose endpoints point at a stub host.
    private func configuration(host: String, kind: MediaSourceKind = .oneDrive, scopeToToken: Bool = true) -> OAuthConfiguration {
        OAuthConfiguration(
            kind: kind,
            clientID: "client-123",
            authorizationEndpoint: URL(string: "https://\(host)/authorize")!,
            tokenEndpoint: URL(string: "https://\(host)/token")!,
            deviceAuthorizationEndpoint: URL(string: "https://\(host)/devicecode")!,
            redirectURI: "test.edendale://auth",
            callbackScheme: "test.edendale",
            scopes: ["Files.Read", "offline_access"],
            sendsScopeToTokenEndpoint: scopeToToken
        )
    }

    @Test func buildsEachProvidersAuthorizationURL() throws {
        let google = try #require(CloudProviders.configuration(for: .googleDrive, clientID: "1234-abc.apps.googleusercontent.com"))
        #expect(google.callbackScheme == "com.googleusercontent.apps.1234-abc")
        #expect(google.redirectURI == "com.googleusercontent.apps.1234-abc:/oauth2redirect")
        #expect(google.deviceAuthorizationEndpoint == nil)
        #expect(google.scopes.contains("https://www.googleapis.com/auth/drive.readonly"))

        let url = OAuthClient(configuration: google).authorizationURL(state: "state-1", codeChallenge: "challenge-1")
        let items = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
        #expect(url.host() == "accounts.google.com")
        #expect(value("client_id") == "1234-abc.apps.googleusercontent.com")
        #expect(value("response_type") == "code")
        #expect(value("redirect_uri") == google.redirectURI)
        #expect(value("scope") == "openid email https://www.googleapis.com/auth/drive.readonly")
        #expect(value("state") == "state-1")
        #expect(value("code_challenge") == "challenge-1")
        #expect(value("code_challenge_method") == "S256")
        #expect(value("prompt") == "select_account")
        // No client secret, ever.
        #expect(value("client_secret") == nil)

        let dropbox = try #require(CloudProviders.configuration(for: .dropbox, clientID: "appkey"))
        let dropboxURL = OAuthClient(configuration: dropbox).authorizationURL(state: "s", codeChallenge: "c")
        #expect(dropboxURL.absoluteString.contains("token_access_type=offline"))
        #expect(dropbox.redirectURI == "db-appkey://2/token")

        let microsoft = try #require(CloudProviders.configuration(for: .oneDrive, clientID: "guid"))
        #expect(microsoft.deviceAuthorizationEndpoint?.absoluteString == "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode")
        #expect(microsoft.scopes == ["Files.Read", "User.Read", "offline_access"])
        #expect(CloudProviders.supportsDeviceCode(.oneDrive))
        #expect(!CloudProviders.supportsDeviceCode(.googleDrive))
    }

    @Test func readsTheAuthorizationCodeOnlyWithTheMatchingState() throws {
        let callback = try #require(URL(string: "test.edendale://auth?code=abc&state=s1"))
        #expect(try OAuthClient.authorizationCode(from: callback, expectedState: "s1", provider: "OneDrive") == "abc")
        #expect(throws: OAuthError.stateMismatch) {
            try OAuthClient.authorizationCode(from: callback, expectedState: "other", provider: "OneDrive")
        }
        let denied = try #require(URL(string: "test.edendale://auth?error=access_denied&state=s1"))
        #expect(throws: OAuthError.authorizationDenied(provider: "OneDrive")) {
            try OAuthClient.authorizationCode(from: denied, expectedState: "s1", provider: "OneDrive")
        }
        let empty = try #require(URL(string: "test.edendale://auth?state=s1"))
        #expect(throws: OAuthError.missingAuthorizationCode) {
            try OAuthClient.authorizationCode(from: empty, expectedState: "s1", provider: "OneDrive")
        }
    }

    @Test func exchangesTheCodeWithTheVerifier() async throws {
        let host = HTTPStub.uniqueHost("oauth")
        HTTPStub.register(host: host) { _ in
            StubResponse.json([
                "access_token": "access-1", "token_type": "Bearer", "expires_in": 3600,
                "refresh_token": "refresh-1", "scope": "Files.Read offline_access"
            ])
        }
        defer { HTTPStub.unregister(host: host) }
        let client = OAuthClient(configuration: configuration(host: host), session: HTTPStub.session())

        let tokens = try await client.exchange(code: "the-code", verifier: "the-verifier")
        #expect(tokens.accessToken == "access-1")
        #expect(tokens.refreshToken == "refresh-1")
        #expect(tokens.expiresIn == 3600)
        #expect(tokens.grantedScopes == ["Files.Read", "offline_access"])

        let request = try #require(HTTPStub.requests(to: host).first)
        #expect(request.method == "POST")
        #expect(request.header("Content-Type") == "application/x-www-form-urlencoded")
        let form = request.formFields
        #expect(form["grant_type"] == "authorization_code")
        #expect(form["code"] == "the-code")
        #expect(form["code_verifier"] == "the-verifier")
        #expect(form["client_id"] == "client-123")
        #expect(form["redirect_uri"] == "test.edendale://auth")
        #expect(form["scope"] == "Files.Read offline_access")
        #expect(form["client_secret"] == nil)
    }

    @Test func mapsAnInvalidGrantToSignInAgain() async throws {
        let host = HTTPStub.uniqueHost("oauth")
        HTTPStub.register(host: host) { _ in
            StubResponse.json(["error": "invalid_grant", "error_description": "Token has been expired or revoked."], status: 400)
        }
        defer { HTTPStub.unregister(host: host) }
        let client = OAuthClient(configuration: configuration(host: host), session: HTTPStub.session())
        await #expect(throws: OAuthError.invalidGrant(provider: "OneDrive")) {
            _ = try await client.refresh(refreshToken: "old")
        }
        let form = try #require(HTTPStub.requests(to: host).first).formFields
        #expect(form["grant_type"] == "refresh_token")
        #expect(form["refresh_token"] == "old")
    }

    @Test func formEncodingEscapesReservedCharacters() {
        #expect(OAuthClient.formEncode([("a b", "x+y&z=1/2")]) == "a%20b=x%2By%26z%3D1%2F2")
    }

    // MARK: - Device code

    @Test func startsADeviceAuthorization() async throws {
        let host = HTTPStub.uniqueHost("oauth")
        HTTPStub.register(host: host) { _ in
            StubResponse.json([
                "device_code": "device-1", "user_code": "ABCD-EFGH",
                "verification_uri": "https://microsoft.com/devicelogin",
                "expires_in": 900, "interval": 5, "message": "To sign in…"
            ])
        }
        defer { HTTPStub.unregister(host: host) }
        let now = Date(timeIntervalSince1970: 1000)
        let client = OAuthClient(configuration: configuration(host: host), session: HTTPStub.session())

        let authorization = try await client.startDeviceAuthorization(now: now)
        #expect(authorization.deviceCode == "device-1")
        #expect(authorization.userCode == "ABCD-EFGH")
        #expect(authorization.verificationURI.absoluteString == "https://microsoft.com/devicelogin")
        // Microsoft sends no verification_uri_complete.
        #expect(authorization.verificationURIComplete == nil)
        #expect(authorization.expiresAt == now.addingTimeInterval(900))
        #expect(authorization.interval == 5)
        let form = try #require(HTTPStub.requests(to: host).first).formFields
        #expect(form["client_id"] == "client-123")
        #expect(form["scope"] == "Files.Read offline_access")
    }

    @Test func acceptsGooglesVerificationURLSpelling() async throws {
        let host = HTTPStub.uniqueHost("oauth")
        HTTPStub.register(host: host) { _ in
            StubResponse.json([
                "device_code": "d", "user_code": "U", "verification_url": "https://www.google.com/device",
                "expires_in": 1800
            ])
        }
        defer { HTTPStub.unregister(host: host) }
        let client = OAuthClient(configuration: configuration(host: host), session: HTTPStub.session())
        let authorization = try await client.startDeviceAuthorization()
        #expect(authorization.verificationURI.absoluteString == "https://www.google.com/device")
        #expect(authorization.interval == 5)
    }

    @Test func pollsThroughPendingAndSlowDownToApproval() async throws {
        let host = HTTPStub.uniqueHost("oauth")
        let polls = Counter()
        HTTPStub.register(host: host) { _ in
            switch polls.increment() {
            case 1: return StubResponse.json(["error": "authorization_pending"], status: 400)
            case 2: return StubResponse.json(["error": "slow_down"], status: 400)
            default: return StubResponse.json(["access_token": "a", "refresh_token": "r", "expires_in": 3600])
            }
        }
        defer { HTTPStub.unregister(host: host) }
        let client = OAuthClient(configuration: configuration(host: host), session: HTTPStub.session())
        let authorization = DeviceAuthorization(
            deviceCode: "device-1", userCode: "U", verificationURI: URL(string: "https://example.com")!,
            verificationURIComplete: nil, expiresAt: .distantFuture, interval: 5
        )
        let waits = IntervalLog()
        let tokens = try await client.waitForDeviceAuthorization(authorization) { interval in
            waits.append(interval)
        }
        #expect(tokens.refreshToken == "r")
        let polled = HTTPStub.requests(to: host)
        #expect(polled.count == 3)
        #expect(polled.allSatisfy { $0.formFields["grant_type"] == "urn:ietf:params:oauth:grant-type:device_code" })
        #expect(polled.allSatisfy { $0.formFields["device_code"] == "device-1" })
        // slow_down adds five seconds to the interval.
        #expect(waits.values == [.seconds(5), .seconds(5), .seconds(10)])
    }

    @Test func reportsADeclinedOrExpiredCode() async throws {
        let host = HTTPStub.uniqueHost("oauth")
        let responses = Counter()
        HTTPStub.register(host: host) { _ in
            responses.increment() == 1
                ? StubResponse.json(["error": "authorization_declined"], status: 400)
                : StubResponse.json(["error": "expired_token"], status: 400)
        }
        defer { HTTPStub.unregister(host: host) }
        let client = OAuthClient(configuration: configuration(host: host), session: HTTPStub.session())
        let authorization = DeviceAuthorization(
            deviceCode: "d", userCode: "U", verificationURI: URL(string: "https://example.com")!,
            verificationURIComplete: nil, expiresAt: .distantFuture, interval: 1
        )
        await #expect(throws: OAuthError.authorizationDenied(provider: "OneDrive")) {
            _ = try await client.pollDeviceAuthorization(authorization)
        }
        await #expect(throws: OAuthError.deviceCodeExpired) {
            _ = try await client.pollDeviceAuthorization(authorization)
        }
    }

    @Test func stopsPollingOnceTheCodeHasExpired() async throws {
        let host = HTTPStub.uniqueHost("oauth")
        HTTPStub.register(host: host) { _ in StubResponse.json(["error": "authorization_pending"], status: 400) }
        defer { HTTPStub.unregister(host: host) }
        let client = OAuthClient(configuration: configuration(host: host), session: HTTPStub.session())
        let start = Date(timeIntervalSince1970: 0)
        let clock = FakeClock(start)
        let authorization = DeviceAuthorization(
            deviceCode: "d", userCode: "U", verificationURI: URL(string: "https://example.com")!,
            verificationURIComplete: nil, expiresAt: start.addingTimeInterval(12), interval: 5
        )
        await #expect(throws: OAuthError.deviceCodeExpired) {
            _ = try await client.waitForDeviceAuthorization(authorization, now: { clock.now }) { interval in
                clock.advance(by: interval)
            }
        }
        // Polled at 5 s and 10 s; at 15 s the code had expired.
        #expect(HTTPStub.requests(to: host).count == 2)
    }
}

@Suite struct CloudTokenProviderTests {

    private func account(kind: MediaSourceKind = .oneDrive, refreshToken: String = "refresh-1") -> CloudAccount {
        CloudAccount(
            kind: kind, subject: "user-1", email: "me@example.com", displayName: nil,
            refreshToken: refreshToken, scopes: [], driveID: "drive-1"
        )
    }

    private func provider(host: String, vault: CloudAccountVault) -> CloudTokenProvider {
        let configuration = OAuthConfiguration(
            kind: .oneDrive, clientID: "client", authorizationEndpoint: URL(string: "https://\(host)/authorize")!,
            tokenEndpoint: URL(string: "https://\(host)/token")!, deviceAuthorizationEndpoint: nil,
            redirectURI: "x://y", callbackScheme: "x", scopes: ["Files.Read"]
        )
        return CloudTokenProvider(vault: vault, session: HTTPStub.session(), configuration: { _ in configuration })
    }

    @Test func concurrentRequestsShareOneRefresh() async throws {
        let host = HTTPStub.uniqueHost("tokens")
        let refreshes = Counter()
        HTTPStub.register(host: host) { _ in
            let count = refreshes.increment()
            Thread.sleep(forTimeInterval: 0.2)
            return StubResponse.json(["access_token": "access-\(count)", "expires_in": 3600])
        }
        defer { HTTPStub.unregister(host: host) }
        let vault = CloudAccountVault(store: InMemorySecretStore())
        let account = account()
        try vault.save(account)
        let tokens = provider(host: host, vault: vault)

        let results = await withTaskGroup(of: String?.self) { group in
            for _ in 0..<10 {
                group.addTask { try? await tokens.accessToken(kind: .oneDrive, accountKey: account.key) }
            }
            return await group.reduce(into: [String?]()) { $0.append($1) }
        }
        #expect(results.allSatisfy { $0 == "access-1" })
        #expect(refreshes.value == 1)
        // Cached afterwards.
        #expect(try await tokens.accessToken(kind: .oneDrive, accountKey: account.key) == "access-1")
        #expect(refreshes.value == 1)
    }

    @Test func aRejectedTokenIsRefreshedOnceAndRotatedTokensAreKept() async throws {
        let host = HTTPStub.uniqueHost("tokens")
        let refreshes = Counter()
        HTTPStub.register(host: host) { _ in
            let count = refreshes.increment()
            return StubResponse.json(["access_token": "access-\(count)", "refresh_token": "refresh-\(count + 1)", "expires_in": 3600])
        }
        defer { HTTPStub.unregister(host: host) }
        let vault = CloudAccountVault(store: InMemorySecretStore())
        let account = account()
        try vault.save(account)
        let tokens = provider(host: host, vault: vault)

        let first = try await tokens.accessToken(kind: .oneDrive, accountKey: account.key)
        let second = try await tokens.accessToken(kind: .oneDrive, accountKey: account.key, rejecting: first)
        #expect(second == "access-2")
        // A caller holding the already-replaced token doesn't refresh again.
        let third = try await tokens.accessToken(kind: .oneDrive, accountKey: account.key, rejecting: first)
        #expect(third == "access-2")
        #expect(refreshes.value == 2)
        // Microsoft rotates refresh tokens; the newest one is stored.
        #expect(vault.account(kind: .oneDrive, key: account.key)?.refreshToken == "refresh-3")
        #expect(HTTPStub.requests(to: host).last?.formFields["refresh_token"] == "refresh-2")
    }

    @Test func aRevokedGrantMeansSignInAgain() async throws {
        let host = HTTPStub.uniqueHost("tokens")
        HTTPStub.register(host: host) { _ in StubResponse.json(["error": "invalid_grant"], status: 400) }
        defer { HTTPStub.unregister(host: host) }
        let vault = CloudAccountVault(store: InMemorySecretStore())
        let account = account()
        try vault.save(account)
        let tokens = provider(host: host, vault: vault)
        await #expect(throws: ConnectorError.signInRequired(provider: "OneDrive")) {
            _ = try await tokens.accessToken(kind: .oneDrive, accountKey: account.key)
        }
        // So does an account that isn't there at all.
        await #expect(throws: ConnectorError.signInRequired(provider: "OneDrive")) {
            _ = try await tokens.accessToken(kind: .oneDrive, accountKey: "missing")
        }
    }

    @Test func expiringTokensAreRefreshedEarly() async throws {
        let host = HTTPStub.uniqueHost("tokens")
        let refreshes = Counter()
        HTTPStub.register(host: host) { _ in
            let count = refreshes.increment()
            return StubResponse.json(["access_token": "access-\(count)", "expires_in": 60])
        }
        defer { HTTPStub.unregister(host: host) }
        let vault = CloudAccountVault(store: InMemorySecretStore())
        let account = account()
        try vault.save(account)
        let tokens = provider(host: host, vault: vault)
        // A 60-second token is inside the two-minute refresh margin.
        _ = try await tokens.accessToken(kind: .oneDrive, accountKey: account.key)
        _ = try await tokens.accessToken(kind: .oneDrive, accountKey: account.key)
        #expect(refreshes.value == 2)
    }
}

final class IntervalLog: @unchecked Sendable {
    private let lock = NSLock()
    private var intervals: [Duration] = []

    func append(_ interval: Duration) { lock.withLock { intervals.append(interval) } }
    var values: [Duration] { lock.withLock { intervals } }
}

final class FakeClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Date

    init(_ start: Date) { current = start }

    var now: Date { lock.withLock { current } }

    func advance(by interval: Duration) {
        lock.withLock { current = current.addingTimeInterval(Double(interval.components.seconds)) }
    }
}
