//
//  SyncStorageTests.swift
//  EdendaleTests
//
//  The sync locations of SYNC-PROTOCOL.md §4 against a temporary folder
//  and recorded API responses: folders are created when missing, only
//  `<uuid>.json` files are listed, stamps change when a file does, writes
//  replace in one step, and each API is called the way its table says
//  (Drive `files.list` on the space folder, Graph's app folder, Dropbox's
//  `files/upload` with `overwrite`, WebDAV `PROPFIND` and `MKCOL`).
//

import Foundation
import Testing
@testable import Edendale

@Suite struct SyncStorageTests {

    private let device = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"

    private func oauth(host: String) -> (CloudAccountVault, CloudTokenProvider) {
        let secrets = InMemorySecretStore()
        let vault = CloudAccountVault(store: secrets)
        let configuration = OAuthConfiguration(
            kind: .googleDrive, clientID: "client", authorizationEndpoint: URL(string: "https://\(host)/auth")!,
            tokenEndpoint: URL(string: "https://\(host)/token")!, deviceAuthorizationEndpoint: nil,
            redirectURI: "x:/y", callbackScheme: "x", scopes: []
        )
        let tokens = CloudTokenProvider(vault: vault, session: HTTPStub.session(), configuration: { _ in configuration })
        return (vault, tokens)
    }

    @Test func folderStorageCreatesTheSpaceAndReplacesFilesInOneStep() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent("sync-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let storage = FolderSyncStorage(root: root, space: "Family", securityScoped: false, coordinated: false)

        #expect(try await storage.listSpace().isEmpty)
        #expect(FileManager.default.fileExists(atPath: root.appendingPathComponent("Edendale/Family").path))

        try await storage.write("\(device).json", data: Data("one".utf8))
        try Data("ignored".utf8).write(to: root.appendingPathComponent("Edendale/Family/notes.json"))
        try Data("ignored".utf8).write(to: root.appendingPathComponent("Edendale/Family/\(device).json.tmp"))
        let first = try await storage.listSpace()
        #expect(first.map(\.name) == ["\(device).json"])
        #expect(try await storage.read("\(device).json") == Data("one".utf8))

        try await Task.sleep(for: .milliseconds(20))
        try await storage.write("\(device).json", data: Data("two".utf8))
        let second = try await storage.listSpace()
        #expect(second.first?.stamp != first.first?.stamp)
        #expect(try await storage.read("\(device).json") == Data("two".utf8))
        #expect(!FileManager.default.fileExists(atPath: root.appendingPathComponent("Edendale/Family/\(device).json.tmp.tmp").path))

        try await storage.delete("\(device).json")
        try await storage.delete("\(device).json")
        #expect(try await storage.listSpace().isEmpty)
        await #expect(throws: SyncStorageError.notFound("\(device).json")) {
            try await storage.read("\(device).json")
        }
    }

    @Test func googleDriveFindsOrCreatesFoldersAndUploadsInPlace() async throws {
        let host = HTTPStub.uniqueHost("drive")
        let api = "https://\(host)/drive/v3/"
        HTTPStub.register(host: host) { request in
            let path = request.url.path
            if path.hasSuffix("/token") { return StubResponse.json(["access_token": "t", "expires_in": 3600]) }
            if path == "/drive/v3/files", request.method == "GET" {
                let q = request.queryValue("q") ?? ""
                if q.contains("name = 'Edendale'") { return StubResponse.json(["files": []]) }
                if q.contains("name = 'Family'") { return StubResponse.json(["files": [["id": "space1", "name": "Family"]]]) }
                if q.contains("'space1' in parents") {
                    return StubResponse.json(["files": [
                        ["id": "f1", "name": "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b.json", "modifiedTime": "2026-11-02T00:00:00.000Z", "size": "120"],
                        ["id": "f2", "name": "readme.txt", "modifiedTime": "2026-11-02T00:00:00.000Z", "size": "3"]
                    ]])
                }
                return StubResponse.json(["files": []])
            }
            if path == "/drive/v3/files", request.method == "POST" {
                return StubResponse.json(["id": "root-folder"])
            }
            if path == "/drive/v3/files/f1" { return StubResponse.text("{\"format\":1}", status: 200) }
            if path == "/upload/drive/v3/files", request.method == "POST" { return StubResponse.json(["id": "new1"]) }
            if path == "/upload/drive/v3/files/new1", request.method == "PATCH" { return StubResponse.json(["id": "new1"]) }
            if path == "/drive/v3/files/new1", request.method == "DELETE" { return StubResponse.text("", status: 204) }
            return StubResponse.json(["error": "unexpected"], status: 500)
        }
        defer { HTTPStub.unregister(host: host) }
        let (vault, tokens) = oauth(host: host)
        let account = CloudAccount(kind: .googleDrive, subject: "s", email: nil, displayName: nil, refreshToken: "r", scopes: [], driveID: nil)
        try vault.save(account)
        // Point the connector at the stub by rewriting its base through the session.
        let storage = GoogleDriveSyncStorage(account: account, space: "Family", tokens: tokens, session: HTTPStub.session())
        _ = api

        // GoogleDriveConnector's base is googleapis.com; stub that host too.
        HTTPStub.register(host: "www.googleapis.com", handler: HTTPStub.handler(for: host))
        defer { HTTPStub.unregister(host: "www.googleapis.com") }

        let entries = try await storage.listSpace()
        #expect(entries.map(\.name) == ["0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b.json"])
        #expect(entries.first?.stamp == "2026-11-02T00:00:00.000Z:120")
        #expect(await storage.resolvedFolderID == "space1")
        #expect(try await storage.read("0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b.json") == Data("{\"format\":1}".utf8))

        try await storage.write("\(device).json", data: Data("{}".utf8))
        let upload = try #require(HTTPStub.requests(to: "www.googleapis.com").last { $0.url.path == "/upload/drive/v3/files" })
        #expect(upload.queryValue("uploadType") == "multipart")
        #expect(upload.header("Content-Type")?.hasPrefix("multipart/related") == true)
        #expect(String(decoding: upload.body ?? Data(), as: UTF8.self).contains("\"parents\":[\"space1\"]"))
        try await storage.write("\(device).json", data: Data("{\"format\":1}".utf8))
        let patch = try #require(HTTPStub.requests(to: "www.googleapis.com").last { $0.url.path == "/upload/drive/v3/files/new1" })
        #expect(patch.method == "PATCH")
        #expect(patch.queryValue("uploadType") == "media")
        try await storage.delete("\(device).json")
        // The folder search escaped the name and made the missing root.
        let created = HTTPStub.requests(to: "www.googleapis.com").first { $0.url.path == "/drive/v3/files" && $0.method == "POST" }
        #expect(created?.jsonBody?["name"] as? String == "Edendale")
        #expect(GoogleDriveSyncStorage.escaped("Ju's \\ Family") == "Ju\\'s \\\\ Family")
    }

    @Test func oneDriveUsesTheAppFolder() async throws {
        let host = HTTPStub.uniqueHost("graph")
        let listed = StubCounter()
        HTTPStub.register(host: "graph.microsoft.com") { request in
            let path = request.url.path.removingPercentEncoding ?? request.url.path
            if path == "/v1.0/me/drive/special/approot:/Family:/children" {
                if listed.next() == 1 { return StubResponse.json(["error": ["code": "itemNotFound"]], status: 404) }
                return StubResponse.json(["value": [
                    ["id": "i1", "name": "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b.json", "size": 40, "eTag": "\"e1\"", "lastModifiedDateTime": "2026-11-02T00:00:00Z", "file": [:]],
                    ["id": "i2", "name": "Sub", "folder": [:]]
                ]])
            }
            if path == "/v1.0/me/drive/special/approot/children", request.method == "POST" {
                return StubResponse.json(["id": "folder"], status: 201)
            }
            if path == "/v1.0/me/drive/special/approot:/Family/0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b.json", request.method == "GET" {
                return StubResponse.json(["@microsoft.graph.downloadUrl": "https://\(host)/download/x"])
            }
            if path.hasSuffix(":/content"), request.method == "PUT" { return StubResponse.json(["id": "new"], status: 201) }
            if request.method == "DELETE" { return StubResponse.text("", status: 204) }
            return StubResponse.json(["error": "unexpected \(path)"], status: 500)
        }
        HTTPStub.register(host: host) { request in
            if request.url.path.hasSuffix("/token") { return StubResponse.json(["access_token": "t", "expires_in": 3600]) }
            #expect(request.header("Authorization") == nil)
            return StubResponse.text("{\"format\":1}", status: 200)
        }
        defer {
            HTTPStub.unregister(host: "graph.microsoft.com")
            HTTPStub.unregister(host: host)
        }
        let (vault, tokens) = oauth(host: host)
        let account = CloudAccount(kind: .oneDrive, subject: "s", email: nil, displayName: nil, refreshToken: "r", scopes: [], driveID: "d")
        try vault.save(account)
        let storage = OneDriveSyncStorage(account: account, space: "Family", tokens: tokens, session: HTTPStub.session())

        #expect(try await storage.listSpace().isEmpty)
        let create = try #require(HTTPStub.requests(to: "graph.microsoft.com").first { $0.method == "POST" })
        #expect(create.jsonBody?["name"] as? String == "Family")
        #expect(create.jsonBody?["@microsoft.graph.conflictBehavior"] as? String == "fail")
        let entries = try await storage.listSpace()
        #expect(entries.map(\.name) == ["0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b.json"])
        #expect(entries.first?.stamp == "\"e1\":40")
        #expect(try await storage.read("0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b.json") == Data("{\"format\":1}".utf8))
        try await storage.write("\(device).json", data: Data("{}".utf8))
        let put = try #require(HTTPStub.requests(to: "graph.microsoft.com").last { $0.method == "PUT" })
        #expect(put.url.path.removingPercentEncoding == "/v1.0/me/drive/special/approot:/Family/\(device).json:/content")
        try await storage.delete("\(device).json")
    }

    @Test func dropboxListsUploadsWithOverwriteAndMakesTheFolder() async throws {
        let host = HTTPStub.uniqueHost("dropbox")
        let listed = StubCounter()
        HTTPStub.register(host: "api.dropboxapi.com") { request in
            switch request.url.path {
            case "/2/files/list_folder":
                if listed.next() == 1 { return StubResponse.json(["error_summary": "path/not_found/...", "error": [:]], status: 409) }
                return StubResponse.json(["entries": [
                    [".tag": "file", "name": "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b.json", "rev": "rev1", "size": 12],
                    [".tag": "folder", "name": "Sub"]
                ], "cursor": "c", "has_more": false])
            case "/2/files/create_folder_v2":
                return StubResponse.json(["metadata": ["name": "Family"]])
            case "/2/files/delete_v2":
                return StubResponse.json(["error_summary": "path_lookup/not_found/", "error": [:]], status: 409)
            default:
                return StubResponse.json(["error": "unexpected"], status: 500)
            }
        }
        HTTPStub.register(host: "content.dropboxapi.com") { request in
            switch request.url.path {
            case "/2/files/download": return StubResponse.text("{\"format\":1}", status: 200)
            case "/2/files/upload": return StubResponse.json(["rev": "rev2"])
            default: return StubResponse.json(["error": "unexpected"], status: 500)
            }
        }
        HTTPStub.register(host: host) { _ in StubResponse.json(["access_token": "t", "expires_in": 3600]) }
        defer {
            HTTPStub.unregister(host: "api.dropboxapi.com")
            HTTPStub.unregister(host: "content.dropboxapi.com")
            HTTPStub.unregister(host: host)
        }
        let (vault, tokens) = oauth(host: host)
        let account = CloudAccount(kind: .dropbox, subject: "s", email: nil, displayName: nil, refreshToken: "r", scopes: [], driveID: nil)
        try vault.save(account)
        let storage = DropboxSyncStorage(account: account, space: "Fämily", tokens: tokens, session: HTTPStub.session())

        #expect(try await storage.listSpace().isEmpty)
        let created = try #require(HTTPStub.requests(to: "api.dropboxapi.com").first { $0.url.path == "/2/files/create_folder_v2" })
        #expect(created.jsonBody?["path"] as? String == "/Edendale/Fämily")
        let entries = try await storage.listSpace()
        #expect(entries.map(\.stamp) == ["rev1"])
        #expect(try await storage.read("0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b.json") == Data("{\"format\":1}".utf8))
        let download = try #require(HTTPStub.requests(to: "content.dropboxapi.com").first { $0.url.path == "/2/files/download" })
        let argument = try #require(download.header("Dropbox-API-Arg"))
        let isASCII = argument.unicodeScalars.allSatisfy { $0.isASCII }
        #expect(isASCII)
        #expect(argument.contains("\\u00e4mily"))
        try await storage.write("\(device).json", data: Data("{}".utf8))
        let upload = try #require(HTTPStub.requests(to: "content.dropboxapi.com").first { $0.url.path == "/2/files/upload" })
        #expect(upload.header("Dropbox-API-Arg")?.contains("\"mode\":\"overwrite\"") == true)
        #expect(upload.header("Content-Type") == "application/octet-stream")
        try await storage.delete("\(device).json")
    }

    @Test func webDAVListsWithETagsAndMakesCollections() async throws {
        let host = HTTPStub.uniqueHost("dav")
        let propfinds = StubCounter()
        HTTPStub.register(host: host) { request in
            switch request.method {
            case "PROPFIND":
                if propfinds.next() == 1 { return StubResponse.text("", status: 404) }
                #expect(request.header("Depth") == "1")
                return StubResponse.text("""
                <?xml version="1.0"?>
                <d:multistatus xmlns:d="DAV:">
                  <d:response><d:href>/dav/Edendale/Family/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                  <d:response><d:href>/dav/Edendale/Family/0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b.json</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontentlength>77</d:getcontentlength><d:getetag>"abc"</d:getetag><d:getlastmodified>Mon, 02 Nov 2026 00:00:00 GMT</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                  <d:response><d:href>/dav/Edendale/Family/notes.txt</d:href><d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
                </d:multistatus>
                """, status: 207, headers: ["Content-Type": "application/xml"])
            case "MKCOL": return StubResponse.text("", status: request.url.path.hasSuffix("/Edendale/") ? 405 : 201)
            case "GET": return StubResponse.text("{\"format\":1}", status: 200)
            case "PUT": return StubResponse.text("", status: 201)
            case "DELETE": return StubResponse.text("", status: 204)
            default: return StubResponse.text("", status: 500)
            }
        }
        defer { HTTPStub.unregister(host: host) }
        let root = try #require(WebDAVConnector.canonicalRoot(from: "https://\(host)/dav/"))
        let storage = WebDAVSyncStorage(root: root, space: "Family", credential: NetworkCredential(username: "me", password: "pw"), session: HTTPStub.session())

        #expect(try await storage.listSpace().isEmpty)
        let collections = HTTPStub.requests(to: host).filter { $0.method == "MKCOL" }.map { $0.url.path() }
        #expect(collections == ["/dav/Edendale/", "/dav/Edendale/Family/"])
        let entries = try await storage.listSpace()
        #expect(entries.map(\.name) == ["0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b.json"])
        #expect(entries.first?.stamp == "\"abc\":77")
        #expect(try await storage.read("0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b.json") == Data("{\"format\":1}".utf8))
        try await storage.write("\(device).json", data: Data("{}".utf8))
        let put = try #require(HTTPStub.requests(to: host).first { $0.method == "PUT" })
        #expect(put.url.path == "/dav/Edendale/Family/\(device).json")
        #expect(put.header("Content-Type") == "application/json")
        try await storage.delete("\(device).json")
    }

    @Test func sftpWritePacketsFollowVersion3() throws {
        let write = SFTPProtocol.write(id: 7, handle: Data([0x01]), offset: 32, data: Data("ab".utf8))
        // length, type 6, id, handle string, offset, data string.
        #expect(write == Data([0, 0, 0, 24, 6, 0, 0, 0, 7, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 32, 0, 0, 0, 2, 0x61, 0x62]))
        let open = SFTPProtocol.open(id: 1, path: "/f", flags: [.write, .create, .truncate])
        #expect(open[open.startIndex + 4] == 3)
        #expect(open.suffix(8).prefix(4) == Data([0, 0, 0, 0x1a]))
        #expect(SFTPProtocol.mkdir(id: 2, path: "/d")[4] == 14)
        #expect(SFTPProtocol.remove(id: 3, path: "/f")[4] == 13)
        #expect(SFTPProtocol.rename(id: 4, oldPath: "/a", newPath: "/b")[4] == 18)
    }

    @Test func locationsDescribeThemselvesWithoutSecrets() {
        let folder = SyncLocation(provider: .folder, space: "Family", folderPath: "/Users/me/Sync")
        #expect(folder.detail() == "/Users/me/Sync" || folder.detail()?.hasSuffix("/Sync") == true)
        let dav = SyncLocation(provider: .webdav, space: "Family", serverURL: URL(string: "davs://nas.local/dav/"))
        #expect(dav.detail() == "davs://nas.local/dav/")
        #expect(dav.serverHost == "nas.local")
        #expect(SyncLocation.Provider(rawValue: "icloudDrive") == .iCloudDrive)
        #expect(SyncLocation.Provider.googleDrive.rawValue == "googleDrive")
        #expect(SyncLocation.Provider(mediaSourceKind: .sftp) == .sftp)
        #expect(SyncLocation.Provider(mediaSourceKind: .local) == nil)
        #expect(!folder.sameStorage(as: dav))
        #expect(dav.sameStorage(as: SyncLocation(provider: .webdav, space: "Other", serverURL: URL(string: "davs://nas.local/dav/"))))
    }
}

extension HTTPStub {
    /// The handler registered for `host`, to reuse for another host.
    static func handler(for host: String) -> Handler {
        { request in
            var components = URLComponents(url: request.url, resolvingAgainstBaseURL: false)!
            components.host = host
            let rewritten = RecordedRequest(url: components.url!, method: request.method, headers: request.headers, body: request.body)
            let (status, headers, data) = try registeredHandler(for: host)!(rewritten)
            return (status, headers, data)
        }
    }
}
