//
//  HandoffInteropTests.swift
//  EdendaleTests
//
//  What another platform sends must decode here, and what this one sends
//  must carry what another platform reads (SYNC-PROTOCOL.md §9.6–§9.7):
//  Android's flat `Login` (`user`, `password`, `s3.pathStyle`) and its
//  `null` failure reasons, an Android-shaped `offer`, the iCloud Drive
//  location only Apple devices can open, and the deadlines that end a
//  handoff wait the network would never resume.
//

import Foundation
import Testing
@testable import Edendale

@Suite struct HandoffInteropTests {

    @Test func loginsReadAndroidsFlatShape() throws {
        let sftp = #"{"kind":"sftp","host":"nas.local","port":2222,"user":"me","password":"pw","hostKeyFingerprint":"SHA256:abc","certificateFingerprint":null,"addresses":["192.168.1.2"]}"#
        let login = try JSONDecoder().decode(AccountHandoff.Login.self, from: Data(sftp.utf8))
        #expect(login.kind == .sftp)
        #expect(login.host == "nas.local")
        #expect(login.port == 2222)
        #expect(login.credential.username == "me")
        #expect(login.credential.password == "pw")
        #expect(login.credential.port == 2222)
        #expect(login.hostKeyFingerprint == "SHA256:abc")

        let s3 = #"{"kind":"s3","host":"acct","port":null,"user":"AKIA","password":"secret","s3":{"endpoint":"https://s3.us-east-1.amazonaws.com","region":"us-east-1","bucket":"films","pathStyle":true},"addresses":[]}"#
        let s3Login = try JSONDecoder().decode(AccountHandoff.Login.self, from: Data(s3.utf8))
        #expect(s3Login.credential.s3?.bucket == "films")
        #expect(s3Login.credential.s3?.usesPathStyle == true)
        #expect(s3Login.credential.s3?.endpoint.absoluteString == "https://s3.us-east-1.amazonaws.com")
        #expect(s3Login.port == nil)
    }

    @Test func loginsWriteBothShapesAndRoundTrip() throws {
        var credential = NetworkCredential(username: "me", password: "pw")
        credential.port = 22
        let login = AccountHandoff.Login(kind: .sftp, host: "nas.local", credential: credential, port: 22, hostKeyFingerprint: "SHA256:abc")
        let data = try JSONEncoder().encode(login)
        let json = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        // Android's fields…
        #expect(json["user"] as? String == "me")
        #expect(json["password"] as? String == "pw")
        #expect(json["port"] as? Int == 22)
        #expect(json["hostKeyFingerprint"] as? String == "SHA256:abc")
        // …and Apple's, which older Apple builds read.
        let nested = try #require(json["credential"] as? [String: Any])
        #expect(nested["username"] as? String == "me")
        #expect(try JSONDecoder().decode(AccountHandoff.Login.self, from: data) == login)

        let bucket = S3Configuration(endpoint: URL(string: "https://minio.local:9000")!, region: "us-east-1", bucket: "b", usesPathStyle: true)
        let s3 = AccountHandoff.Login(kind: .s3, host: "acct", credential: NetworkCredential(username: "AKIA", password: "s", s3: bucket))
        let s3JSON = try #require(try JSONSerialization.jsonObject(with: JSONEncoder().encode(s3)) as? [String: Any])
        let flat = try #require(s3JSON["s3"] as? [String: Any])
        #expect(flat["endpoint"] as? String == "https://minio.local:9000")
        #expect(flat["pathStyle"] as? Bool == true)
    }

    @Test func androidOffersAndResultsDecode() throws {
        let offer = """
        {"type":"offer","status":"approved",
         "location":{"provider":"webdav","space":"Family","url":"davs://cloud.example.com/dav/",
                     "login":{"kind":"webdav","host":"cloud.example.com","port":null,"user":"me","password":"pw","hostKeyFingerprint":null,"certificateFingerprint":null,"addresses":[]}},
         "accounts":[{"kind":"onedrive","subject":"abc","email":null,"displayName":"Me","refreshToken":"r","scopes":["Files.Read","Files.ReadWrite.AppFolder"],"driveId":"d1"}],
         "logins":[]}
        """
        let decoded = try HandoffV2.Message.decode(HandoffV2.Offer.self, from: Data(offer.utf8))
        #expect(decoded.status == .approved)
        #expect(decoded.location?.provider == .webdav)
        #expect(decoded.location?.url == "davs://cloud.example.com/dav/")
        #expect(decoded.location?.login?.credential.username == "me")
        #expect(decoded.accounts?.first?.cloudAccount.driveID == "d1")
        #expect(decoded.accounts?.first?.cloudAccount.email == nil)

        let result = #"{"type":"result","stored":["location"],"failed":[{"item":"login:smb:nas.local","reason":null},{"item":"account:gdrive:x","reason":"rejected"}]}"#
        let decodedResult = try HandoffV2.Message.decode(HandoffV2.ResultMessage.self, from: Data(result.utf8))
        #expect(decodedResult.stored == ["location"])
        #expect(decodedResult.failed.map(\.reason) == [nil, "rejected"])
    }

    @MainActor
    @Test func iCloudDriveGoesOnlyWhereItCanBeOpened() {
        let location = SyncLocation(provider: .iCloudDrive, space: "Family")
        let selection = SyncHandoff.Selection(includeLocation: true)
        func offer(to platform: String?) -> HandoffV2.Offer {
            SyncHandoff.makeOffer(location: location, selection: selection, accounts: [], logins: [], peerPlatform: platform,
                                  secrets: InMemorySecretStore())
        }
        #expect(offer(to: "ipados").location?.provider == .iCloudDrive)
        #expect(offer(to: "tvos").location?.provider == .iCloudDrive)
        // iCloud for Windows shows the folder; the PC picks it like any other.
        #expect(offer(to: "windows").location == HandoffV2.OfferLocation(provider: .folder, space: "Family"))
        #expect(offer(to: "android").location == nil)
        #expect(offer(to: nil).location == nil)
    }

    @MainActor
    @Test func googleLoginsStayInTheAppleEcosystemInOffers() {
        let google = CloudAccount(kind: .googleDrive, subject: "g", email: "me@example.com", displayName: nil,
                                  refreshToken: "r", scopes: CloudProviders.googleScopes, driveID: nil)
        let dropbox = CloudAccount(kind: .dropbox, subject: "d", email: nil, displayName: nil,
                                   refreshToken: "r2", scopes: CloudProviders.dropboxScopes, driveID: nil)
        let location = SyncLocation(provider: .googleDrive, space: "Family", accountKey: google.key, googleFolderID: "1AbC")
        let selection = SyncHandoff.Selection(includeLocation: true, accountKeys: [google.key, dropbox.key])
        let toTV = SyncHandoff.makeOffer(location: location, selection: selection, accounts: [google, dropbox], logins: [],
                                         peerPlatform: "tvos", secrets: InMemorySecretStore())
        #expect(toTV.location?.account?.refreshToken == "r")
        #expect(toTV.accounts?.map(\.kind) == [.googleDrive, .dropbox])
        let toAndroid = SyncHandoff.makeOffer(location: location, selection: selection, accounts: [google, dropbox], logins: [],
                                              peerPlatform: "android", secrets: InMemorySecretStore())
        #expect(toAndroid.location?.account == nil)
        #expect(toAndroid.location?.googleAccountHint == "me@example.com")
        #expect(toAndroid.location?.googleFolderId == "1AbC")
        #expect(toAndroid.accounts?.map(\.kind) == [.dropbox])
    }

    // MARK: - Deadlines

    /// A continuation only `resume()` ends, whichever comes first.
    final class Hold: @unchecked Sendable {
        private let lock = NSLock()
        private var continuation: CheckedContinuation<Void, Never>?
        private var resumed = false

        func wait() async {
            await withCheckedContinuation { continuation in
                let resumeNow = lock.withLock { () -> Bool in
                    if resumed { return true }
                    self.continuation = continuation
                    return false
                }
                if resumeNow { continuation.resume() }
            }
        }

        func resume() {
            let waiting = lock.withLock { () -> CheckedContinuation<Void, Never>? in
                resumed = true
                defer { continuation = nil }
                return continuation
            }
            waiting?.resume()
        }
    }

    @Test func deadlinesEndWaitsTheNetworkWouldNeverResume() async throws {
        let hold = Hold()
        await #expect(throws: HandoffV2.PairingError.timedOut) {
            try await HandoffV2Session.withDeadline(.milliseconds(50), expire: { hold.resume() }) {
                await hold.wait()
            }
        }
        let value = try await HandoffV2Session.withDeadline(.seconds(5), expire: {}) { 42 }
        #expect(value == 42)
        await #expect(throws: HandoffV2.PairingError.secret) {
            try await HandoffV2Session.withDeadline(.seconds(5), expire: {}) { throw HandoffV2.PairingError.secret }
        }
    }

    // MARK: - iCloud placeholders

    @Test func iCloudPlaceholdersListUnderTheirFileNames() async throws {
        let id = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"
        #expect(FolderSyncStorage.syncFileName(forListedName: "\(id).json") == "\(id).json")
        #expect(FolderSyncStorage.syncFileName(forListedName: ".\(id).json.icloud") == "\(id).json")
        #expect(FolderSyncStorage.syncFileName(forListedName: ".DS_Store") == nil)
        #expect(FolderSyncStorage.syncFileName(forListedName: "\(id).json.tmp") == nil)
        #expect(FolderSyncStorage.syncFileName(forListedName: ".notes.json.icloud") == nil)

        let root = FileManager.default.temporaryDirectory.appendingPathComponent("sync-\(UUID().uuidString)", isDirectory: true)
        let space = root.appendingPathComponent("Edendale/Family", isDirectory: true)
        try FileManager.default.createDirectory(at: space, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let other = "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b"
        try Data("placeholder".utf8).write(to: space.appendingPathComponent(".\(other).json.icloud"))
        try Data("{}".utf8).write(to: space.appendingPathComponent("\(id).json"))
        let storage = FolderSyncStorage(root: root, space: "Family", securityScoped: false, coordinated: false)
        let entries = try await storage.listSpace()
        #expect(entries.map(\.name) == ["\(id).json", "\(other).json"])
        // A placeholder's size is the placeholder's, so none is claimed.
        #expect(entries.last?.size == nil)
    }
}
