//
//  AccountHandoffTests.swift
//  EdendaleTests
//
//  The Apple TV handoff's messages: length-prefixed frames round-trip,
//  unknown versions and malformed bodies are rejected, and an account whose
//  token fails to refresh is refused rather than stored. The picker and the
//  encrypted connection itself need hardware (see README).
//

import Foundation
import Testing
@testable import Edendale

@Suite struct AccountHandoffTests {

    private let account = CloudAccount(
        kind: .googleDrive, subject: "110169484474386276334", email: "me@example.com",
        displayName: "Me", refreshToken: "refresh-token", scopes: CloudProviders.googleScopes, driveID: nil
    )

    /// Splits a frame into its announced length and body.
    private func unframe(_ frame: Data) throws -> Data {
        let length = try AccountHandoff.bodyLength(ofHeader: frame.prefix(4))
        let body = frame.dropFirst(4)
        #expect(body.count == length)
        return Data(body)
    }

    @Test func requestsRoundTrip() throws {
        let request = AccountHandoff.Request(kind: .dropbox, deviceName: "Living Room")
        let decoded = try AccountHandoff.decodeRequest(unframe(AccountHandoff.frame(request)))
        #expect(decoded == request)
        #expect(decoded.version == AccountHandoff.version)
    }

    @Test func accountsRoundTripWithTheirKeyAndToken() throws {
        let response = AccountHandoff.Response(status: .approved, account: AccountHandoff.Account(account))
        let decoded = try AccountHandoff.decodeResponse(unframe(AccountHandoff.frame(response)))
        let handedOver = try #require(decoded.account?.cloudAccount)
        #expect(handedOver.key == account.key)
        #expect(handedOver.refreshToken == "refresh-token")
        #expect(handedOver.scopes == CloudProviders.googleScopes)
        #expect(handedOver.email == "me@example.com")
    }

    @Test func loginsRoundTripWithTheirHostKey() throws {
        let login = AccountHandoff.Login(
            kind: .sftp, host: "nas.local", credential: NetworkCredential(username: "me", password: "pw"),
            port: 22, hostKeyFingerprint: "SHA256:abc"
        )
        let response = AccountHandoff.Response(status: .approved, login: login)
        let decoded = try AccountHandoff.decodeResponse(unframe(AccountHandoff.frame(response)))
        #expect(decoded.login == login)
    }

    @Test func savedSFTPLoginsTravelWithTheirPortAndHostKey() throws {
        let store = InMemorySecretStore()
        var login = NetworkCredential(username: "me", password: "pw")
        login.port = 2222
        try NetworkCredentialStore.save(login, kind: .sftp, host: "media.local", store: store)
        try HostKeyStore.pin("SHA256:on-2222", host: "media.local", port: 2222, store: store)
        try HostKeyStore.pin("SHA256:on-22", host: "media.local", port: 22, store: store)
        try NetworkCredentialStore.save(
            NetworkCredential(username: "me", password: "pw"), kind: .webdav, host: "cloud.example.com", store: store
        )

        let saved = NetworkCredentialStore.savedLogins(store: store)
        let savedSFTP = try #require(saved.first { $0.kind == .sftp })
        let sftp = try #require(AccountHandoff.Login.handing(savedSFTP, store: store))
        #expect(sftp.port == 2222)
        #expect(sftp.hostKeyFingerprint == "SHA256:on-2222")
        #expect(sftp.credential.password == "pw")

        let savedDAV = try #require(saved.first { $0.kind == .webdav })
        let dav = try #require(AccountHandoff.Login.handing(savedDAV, store: store))
        #expect(dav.port == nil)
        #expect(dav.hostKeyFingerprint == nil)

        // A login saved without a port is on SSH's default one.
        try NetworkCredentialStore.save(NetworkCredential(username: "me", password: "pw"), kind: .sftp, host: "media.local", store: store)
        let legacy = try #require(AccountHandoff.Login.handing(
            SavedServerLogin(kind: .sftp, host: "media.local", username: "me", detail: nil), store: store
        ))
        #expect(legacy.port == 22)
        #expect(legacy.hostKeyFingerprint == "SHA256:on-22")

        // A login removed since the list was read can't be handed over.
        NetworkCredentialStore.remove(kind: .sftp, host: "media.local", store: store)
        #expect(AccountHandoff.Login.handing(
            SavedServerLogin(kind: .sftp, host: "media.local", username: "me", detail: nil), store: store
        ) == nil)
    }

    @Test func rejectsUnknownVersions() throws {
        let future = Data(#"{"version":2,"kind":"gdrive","deviceName":"TV","somethingNew":true}"#.utf8)
        #expect(throws: AccountHandoff.HandoffError.unsupportedVersion(2)) {
            try AccountHandoff.decodeRequest(future)
        }
        let past = Data(#"{"version":0,"status":"approved"}"#.utf8)
        #expect(throws: AccountHandoff.HandoffError.unsupportedVersion(0)) {
            try AccountHandoff.decodeResponse(past)
        }
    }

    @Test func rejectsMalformedMessagesAndOversizedFrames() {
        #expect(throws: AccountHandoff.HandoffError.malformedMessage) {
            try AccountHandoff.decodeRequest(Data("not json".utf8))
        }
        #expect(throws: AccountHandoff.HandoffError.malformedMessage) {
            try AccountHandoff.decodeRequest(Data(#"{"version":1,"kind":"ftp","deviceName":"TV"}"#.utf8))
        }
        #expect(throws: AccountHandoff.HandoffError.messageTooLarge) {
            try AccountHandoff.bodyLength(ofHeader: Data([0x7F, 0xFF, 0xFF, 0xFF]))
        }
        #expect(throws: AccountHandoff.HandoffError.messageTooLarge) {
            try AccountHandoff.bodyLength(ofHeader: Data([0, 0, 0, 0]))
        }
        #expect(throws: AccountHandoff.HandoffError.malformedMessage) {
            try AccountHandoff.bodyLength(ofHeader: Data([0, 1]))
        }
    }

    @Test func declinesCarryNoAccount() throws {
        let decoded = try AccountHandoff.decodeResponse(unframe(AccountHandoff.frame(AccountHandoff.Response.declined)))
        #expect(decoded.status == .declined)
        #expect(decoded.account == nil)
        #expect(decoded.login == nil)
    }

    @MainActor
    @Test func refusesAnAccountWhoseTokenDoesntRefresh() async throws {
        let host = HTTPStub.uniqueHost("handoff")
        HTTPStub.register(host: host) { _ in StubResponse.json(["error": "invalid_grant"], status: 400) }
        defer { HTTPStub.unregister(host: host) }
        let secrets = InMemorySecretStore()
        let vault = CloudAccountVault(store: secrets)
        let configuration = OAuthConfiguration(
            kind: .googleDrive, clientID: "client", authorizationEndpoint: URL(string: "https://\(host)/auth")!,
            tokenEndpoint: URL(string: "https://\(host)/token")!, deviceAuthorizationEndpoint: nil,
            redirectURI: "x:/y", callbackScheme: "x", scopes: []
        )
        let tokens = CloudTokenProvider(vault: vault, session: HTTPStub.session(), configuration: { _ in configuration })
        let store = CloudAccountStore(vault: vault, secrets: secrets, tokens: tokens, session: HTTPStub.session())

        await #expect(throws: CloudAccountStore.SignInError.handoffRejected(provider: "Google Drive")) {
            _ = try await store.adoptHandedOffAccount(account)
        }
        #expect(vault.account(kind: .googleDrive, key: account.key) == nil)
        #expect(store.accounts.isEmpty)
    }

    @MainActor
    @Test func keepsAnAccountWhoseTokenRefreshes() async throws {
        let host = HTTPStub.uniqueHost("handoff")
        HTTPStub.register(host: host) { _ in StubResponse.json(["access_token": "a", "expires_in": 3600]) }
        defer { HTTPStub.unregister(host: host) }
        let secrets = InMemorySecretStore()
        let vault = CloudAccountVault(store: secrets)
        let configuration = OAuthConfiguration(
            kind: .googleDrive, clientID: "client", authorizationEndpoint: URL(string: "https://\(host)/auth")!,
            tokenEndpoint: URL(string: "https://\(host)/token")!, deviceAuthorizationEndpoint: nil,
            redirectURI: "x:/y", callbackScheme: "x", scopes: []
        )
        let tokens = CloudTokenProvider(vault: vault, session: HTTPStub.session(), configuration: { _ in configuration })
        let store = CloudAccountStore(vault: vault, secrets: secrets, tokens: tokens, session: HTTPStub.session())

        let adopted = try await store.adoptHandedOffAccount(account)
        #expect(adopted.key == account.key)
        #expect(store.accounts.map(\.key) == [account.key])
        // The phone's refresh token is reused, not exchanged for another.
        #expect(HTTPStub.requests(to: host).first?.formFields["refresh_token"] == "refresh-token")
    }
}
