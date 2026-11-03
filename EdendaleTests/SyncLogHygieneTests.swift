//
//  SyncLogHygieneTests.swift
//  EdendaleTests
//
//  FEATURES-27.1.md H10: nothing that reaches the sync log is a pairing
//  secret, a session key, a proof, a refresh token, a password, or a
//  record's contents. The test captures every line the sync and handoff
//  code logs while a pairing, an authorized sync, and a failing sync run,
//  and looks for the secrets it planted.
//

import CryptoKit
import Foundation
import Testing
@testable import Edendale

@MainActor
@Suite(.serialized)
struct SyncLogHygieneTests {

    final class Capture: @unchecked Sendable {
        private let lock = NSLock()
        private(set) var lines: [String] = []
        func append(_ line: String) { lock.withLock { lines.append(line) } }
    }

    @Test func noSecretReachesTheLog() async throws {
        let capture = Capture()
        SyncLog.install { capture.append($0) }
        defer { SyncLog.install(sink: nil) }

        // A pairing with known key material.
        var host = HandoffV2.HostPairing(name: "Living Room TV")
        let guest = HandoffV2.GuestPairing(payload: host.payload)
        let (confirmBody, _) = try host.accept(helloBody: try guest.helloBody())
        _ = try guest.accept(confirmBody: confirmBody)
        let secretB64 = PKCE.base64URL(host.payload.secret)
        let secretHex = HandoffV2Tests.hex(host.payload.secret)
        let hostKeyB64 = PKCE.base64URL(host.payload.hostPublicKey)
        SyncLog.info("handoff paired with \(host.payload.name)")

        // A token refresh with a known refresh token, through the real
        // OAuth path, then a sync whose location fails and one that works.
        let hostName = HTTPStub.uniqueHost("sync-log")
        let refreshToken = "refresh-" + UUID().uuidString
        let accessToken = "access-" + UUID().uuidString
        HTTPStub.register(host: hostName) { _ in StubResponse.json(["access_token": accessToken, "expires_in": 3600]) }
        defer { HTTPStub.unregister(host: hostName) }
        let secrets = InMemorySecretStore()
        let vault = CloudAccountVault(store: secrets)
        let account = CloudAccount(
            kind: .dropbox, subject: "dbid:abc", email: "me@example.com", displayName: "Me",
            refreshToken: refreshToken, scopes: CloudProviders.dropboxScopes, driveID: nil
        )
        try vault.save(account)
        let configuration = OAuthConfiguration(
            kind: .dropbox, clientID: "client", authorizationEndpoint: URL(string: "https://\(hostName)/auth")!,
            tokenEndpoint: URL(string: "https://\(hostName)/token")!, deviceAuthorizationEndpoint: nil,
            redirectURI: "x:/y", callbackScheme: "x", scopes: []
        )
        let tokens = CloudTokenProvider(vault: vault, session: HTTPStub.session(), configuration: { _ in configuration })
        #expect(try await tokens.accessToken(kind: .dropbox, accountKey: account.key) == accessToken)
        let environment = ConnectorFactory.Environment(secrets: secrets, accounts: vault, tokens: tokens, session: HTTPStub.session())
        let device = SyncTestDevice(storage: MemorySyncStorage())
        let broken = MemorySyncStorage()
        await broken.fail(with: ConnectorError.signInRequired(provider: "Dropbox"))
        let failing = SyncCoordinator(
            watchStore: device.watchStore, userMediaStore: device.userMediaStore, stamper: device.stamper,
            defaults: device.defaults, environment: environment,
            episodeResolver: { _, _, _ in nil }, storageFactory: { _ in broken }
        )
        failing.setLocation(SyncLocation(provider: .dropbox, space: "Family", accountKey: account.key), startSync: false)
        let password = "hunter2-" + UUID().uuidString
        try NetworkCredentialStore.save(NetworkCredential(username: "me", password: password), kind: .sftp, host: "nas.local", store: secrets)
        device.userMediaStore.setRating(9.5, for: MediaRef(id: 603, mediaType: .movie))
        #expect(await failing.runSync(reason: "test") == false)
        #expect(await device.sync())
        SyncLog.info("error: \(SyncLog.describe(ConnectorError.authenticationFailed(host: "nas.local")))")
        SyncLog.info("login saved for \(NetworkCredentialStore.savedLogins(store: secrets).map(\.host))")
        // A careless call site is still redacted by the sink.
        SyncLog.info("Authorization: Bearer \(accessToken) refresh_token=\(refreshToken) s=\(secretB64) k=\(hostKeyB64)")

        let joined = capture.lines.joined(separator: "\n")
        #expect(!capture.lines.isEmpty)
        for secret in [refreshToken, accessToken, secretB64, secretHex, hostKeyB64, password, "9.5"] {
            #expect(!joined.contains(secret), "log leaked \(secret.prefix(4))…")
        }
        #expect(joined.contains("[redacted]"))
        #expect(joined.contains("sync failed"))
    }

    @Test func describeNamesErrorClassesOnly() {
        #expect(SyncLog.describe(ConnectorError.authenticationFailed(host: "secret.example")) == "ConnectorError.authenticationFailed")
        #expect(SyncLog.describe(OAuthError.invalidGrant(provider: "Dropbox")) == "OAuthError.invalidGrant")
        #expect(SyncLog.describe(SyncFileFormat.Rejection.invalidField("x")) == "Rejection.invalidField")
        #expect(SyncLog.describe(URLError(.timedOut)) == "URLError(-1001)")
        #expect(SyncLog.redacted("token=abc&x=1") == "[redacted]&x=1")
        #expect(SyncLog.redacted("https://user:pw@host/path") == "https[redacted]host/path")
        #expect(SyncLog.redacted("s=AAECAwQFBgcICQoLDA0ODw") == "[redacted]")
        #expect(SyncLog.redacted("merged 0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a.json").contains("0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"))
    }
}
