//
//  CloudListingTests.swift
//  EdendaleTests
//
//  Recorded-shape Google Drive, Microsoft Graph, Dropbox, WebDAV, and S3
//  responses served by a URLProtocol stub: pagination, shortcuts, folders,
//  shared items, filtering, and how each provider's files are fetched. The
//  provider API hosts are stubbed per test, so this suite runs serially.
//  No real credentials appear anywhere.
//

import Foundation
import Testing
@testable import Edendale

@Suite(.serialized)
struct CloudListingTests {

    // MARK: - Fixtures

    private func account(_ kind: MediaSourceKind, driveID: String? = nil) -> CloudAccount {
        CloudAccount(
            kind: kind, subject: "subject-\(kind.rawValue)", email: "me@example.com", displayName: nil,
            refreshToken: "refresh", scopes: [], driveID: driveID
        )
    }

    /// A token provider that already holds a valid token for `account`.
    private func tokens(for account: CloudAccount) async -> CloudTokenProvider {
        let vault = CloudAccountVault(store: InMemorySecretStore())
        try? vault.save(account)
        let provider = CloudTokenProvider(vault: vault, session: HTTPStub.session(), configuration: { _ in nil })
        await provider.store(OAuthTokenResponse(accessToken: "token", expiresIn: 3600), for: account)
        return provider
    }

    // MARK: - Google Drive

    @Test func listsDriveFoldersFollowingShortcutsAndPages() async throws {
        let host = "www.googleapis.com"
        let pages = Counter()
        HTTPStub.register(host: host) { _ in
            if pages.increment() == 1 {
                return StubResponse.json([
                    "nextPageToken": "page-2",
                    "files": [
                        ["id": "f1", "name": "Movies", "mimeType": "application/vnd.google-apps.folder"],
                        ["id": "v1", "name": "The.Matrix.1999.mkv", "mimeType": "video/x-matroska",
                         "size": "123456", "modifiedTime": "2024-05-01T12:34:56.789Z",
                         "videoMediaMetadata": ["durationMillis": "8160000"]],
                        ["id": "doc", "name": "Notes", "mimeType": "application/vnd.google-apps.document"],
                        ["id": "s1", "name": "Shows", "mimeType": "application/vnd.google-apps.shortcut",
                         "shortcutDetails": ["targetId": "f2", "targetMimeType": "application/vnd.google-apps.folder"]],
                        ["id": "s2", "name": "Alien.1979.mp4", "mimeType": "application/vnd.google-apps.shortcut",
                         "shortcutDetails": ["targetId": "v2", "targetMimeType": "video/mp4"]]
                    ]
                ])
            }
            return StubResponse.json(["files": [["id": "v3", "name": "Heat.1995.mkv", "mimeType": "video/x-matroska"]]])
        }
        defer { HTTPStub.unregister(host: host) }
        let account = account(.googleDrive)
        let connector = GoogleDriveConnector(account: account, tokens: await tokens(for: account), session: HTTPStub.session())

        let folder = connector.folderURL(id: "f0", name: "Films")
        let entries = try await connector.list(directory: folder)

        #expect(entries.map(\.name) == ["Movies", "Shows", "Alien.1979.mp4", "Heat.1995.mkv", "The.Matrix.1999.mkv"])
        let shows = try #require(entries.first { $0.name == "Shows" })
        #expect(shows.isDirectory)
        #expect(SourceURL.parseAccountItem(shows.url)?.ids == ["f2"])
        let alien = try #require(entries.first { $0.name == "Alien.1979.mp4" })
        #expect(SourceURL.parseAccountItem(alien.url)?.ids == ["v2"])
        let matrix = try #require(entries.first { $0.name == "The.Matrix.1999.mkv" })
        #expect(matrix.size == 123_456)
        #expect(matrix.duration == 8160)
        #expect(matrix.url.absoluteString == "gdrive://\(account.key)/v1/The.Matrix.1999.mkv")
        #expect(matrix.isVideo)

        let requests = HTTPStub.requests(to: host)
        #expect(requests.count == 2)
        #expect(requests[0].url.path() == "/drive/v3/files")
        #expect(requests[0].queryValue("q") == "'f0' in parents and trashed = false")
        #expect(requests[0].queryValue("supportsAllDrives") == "true")
        #expect(requests[0].queryValue("includeItemsFromAllDrives") == "true")
        #expect(requests[0].queryValue("fields")?.contains("videoMediaMetadata(durationMillis)") == true)
        #expect(requests[0].queryValue("corpora") == nil)
        #expect(requests[1].queryValue("pageToken") == "page-2")
        #expect(requests.allSatisfy { $0.header("Authorization") == "Bearer token" })
    }

    @Test func driveRootsAndSharedDrives() async throws {
        let host = "www.googleapis.com"
        HTTPStub.register(host: host) { request in
            if request.url.path() == "/drive/v3/drives" {
                return StubResponse.json(["drives": [["id": "team1", "name": "Team Films"]]])
            }
            return StubResponse.json(["files": [
                ["id": "sub", "name": "Season 1", "mimeType": "application/vnd.google-apps.folder"]
            ]])
        }
        defer { HTTPStub.unregister(host: host) }
        let account = account(.googleDrive)
        let connector = GoogleDriveConnector(account: account, tokens: await tokens(for: account), session: HTTPStub.session())

        // The picker's root needs no request and can't itself be linked.
        let roots = try await connector.list(directory: connector.root)
        #expect(roots.map(\.name) == ["My Drive", "Shared with me", "Shared drives"])
        #expect(HTTPStub.requests(to: host).isEmpty)
        #expect(!connector.canIndex(connector.root))
        let sharedDrives = try #require(roots.last)
        #expect(!connector.canIndex(sharedDrives.url))
        #expect(connector.canIndex(roots[0].url))

        let drives = try await connector.list(directory: sharedDrives.url)
        let team = try #require(drives.first)
        #expect(team.name == "Team Films")
        #expect(SourceURL.parseAccountItem(team.url)?.queryValue("drive") == "team1")

        // Listing inside a shared drive names it, and its folders keep it.
        let inside = try await connector.list(directory: team.url)
        let request = try #require(HTTPStub.requests(to: host).last)
        #expect(request.queryValue("corpora") == "drive")
        #expect(request.queryValue("driveId") == "team1")
        #expect(SourceURL.parseAccountItem(try #require(inside.first).url)?.queryValue("drive") == "team1")
    }

    @Test func driveStreamsWithABearerTokenAndAltMedia() async throws {
        let account = account(.googleDrive)
        let resolver = GoogleDriveContentResolver(fileID: "v1", accountKey: account.key, tokens: await tokens(for: account))
        let request = try await resolver.contentRequest(refresh: false)
        let url = try #require(request.url)
        #expect(url.host() == "www.googleapis.com")
        #expect(url.path() == "/drive/v3/files/v1")
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        #expect(items.contains(URLQueryItem(name: "alt", value: "media")))
        #expect(items.contains(URLQueryItem(name: "supportsAllDrives", value: "true")))
        #expect(!items.contains { $0.name == "acknowledgeAbuse" })
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer token")
        #expect(!resolver.usesPreauthorizedLinks)
    }

    // MARK: - OneDrive

    @Test func listsOneDriveChildrenAcrossPagesAndSharedFolders() async throws {
        let host = "graph.microsoft.com"
        HTTPStub.register(host: host) { request in
            if request.url.query()?.contains("skiptoken") == true {
                return StubResponse.json(["value": [
                    ["id": "a1", "name": "Alien.1979.mp4", "size": 20, "file": ["mimeType": "video/mp4"]]
                ]])
            }
            return StubResponse.json([
                "value": [
                    ["id": "m1", "name": "Movies", "folder": ["childCount": 3]],
                    ["id": "h1", "name": "Heat.1995.mkv", "size": 1000, "file": ["mimeType": "video/x-matroska"],
                     "video": ["duration": 6_000_000], "lastModifiedDateTime": "2024-01-02T03:04:05Z"],
                    ["id": "n1", "name": "Notebook", "package": ["type": "oneNote"]],
                    ["id": "x1", "name": "Shared", "remoteItem": [
                        "id": "r1", "folder": ["childCount": 1], "parentReference": ["driveId": "d2"]
                    ]]
                ],
                "@odata.nextLink": "https://graph.microsoft.com/v1.0/drives/d1/root/children?$skiptoken=abc"
            ])
        }
        defer { HTTPStub.unregister(host: host) }
        let account = account(.oneDrive, driveID: "d1")
        let connector = try #require(OneDriveConnector(account: account, tokens: await tokens(for: account), session: HTTPStub.session()))

        #expect(connector.root.absoluteString == "onedrive://\(account.key)/d1/root/OneDrive")
        let entries = try await connector.list(directory: connector.root)
        #expect(entries.map(\.name) == ["Movies", "Shared", "Alien.1979.mp4", "Heat.1995.mkv"])
        let heat = try #require(entries.first { $0.name == "Heat.1995.mkv" })
        #expect(heat.duration == 6000)
        #expect(heat.size == 1000)
        #expect(SourceURL.parseAccountItem(heat.url)?.ids == ["d1", "h1"])
        let shared = try #require(entries.first { $0.name == "Shared" })
        #expect(shared.isDirectory)
        #expect(SourceURL.parseAccountItem(shared.url)?.ids == ["d2", "r1"])

        let requests = HTTPStub.requests(to: host)
        #expect(requests.count == 2)
        #expect(requests[0].url.path() == "/v1.0/drives/d1/root/children")
        #expect(requests[0].queryValue("$select")?.contains("video") == true)
        #expect(requests[1].url.absoluteString.contains("$skiptoken=abc"))

        // An account from before the drive was known can't browse.
        #expect(OneDriveConnector(account: self.account(.oneDrive)) == nil)
    }

    @Test func oneDriveStreamsFromItsPreauthenticatedDownloadURL() async throws {
        let host = "graph.microsoft.com"
        let lookups = Counter()
        HTTPStub.register(host: host) { _ in
            StubResponse.json(["id": "h1", "@microsoft.graph.downloadUrl": "https://download.example/link-\(lookups.increment())"])
        }
        defer { HTTPStub.unregister(host: host) }
        let account = account(.oneDrive, driveID: "d1")
        let resolver = OneDriveContentResolver(
            driveID: "d1", itemID: "h1", accountKey: account.key,
            tokens: await tokens(for: account), session: HTTPStub.session()
        )
        #expect(resolver.usesPreauthorizedLinks)
        let first = try await resolver.contentRequest(refresh: false)
        #expect(first.url?.absoluteString == "https://download.example/link-1")
        #expect(first.value(forHTTPHeaderField: "Authorization") == nil)
        // Reused until it fails, then resolved again.
        #expect(try await resolver.contentRequest(refresh: false).url == first.url)
        #expect(try await resolver.contentRequest(refresh: true).url?.absoluteString == "https://download.example/link-2")
        #expect(HTTPStub.requests(to: host).first?.url.path() == "/v1.0/drives/d1/items/h1")
    }

    // MARK: - Dropbox

    @Test func listsADropboxFolderAcrossPages() async throws {
        let host = "api.dropboxapi.com"
        HTTPStub.register(host: host) { request in
            if request.url.path().hasSuffix("/continue") {
                return StubResponse.json(["entries": [
                    [".tag": "file", "name": "Alien.1979.mp4", "id": "id:a", "size": 5]
                ], "cursor": "c2", "has_more": false])
            }
            return StubResponse.json(["entries": [
                [".tag": "folder", "name": "Movies", "id": "id:m"],
                [".tag": "file", "name": "Heat.1995.mkv", "id": "id:h", "size": 10,
                 "server_modified": "2015-05-12T15:50:38Z", "is_downloadable": true],
                [".tag": "file", "name": ".hidden.mkv", "id": "id:x"],
                [".tag": "deleted", "name": "Gone.mkv"]
            ], "cursor": "c1", "has_more": true])
        }
        defer { HTTPStub.unregister(host: host) }
        let account = account(.dropbox)
        let connector = DropboxConnector(account: account, tokens: await tokens(for: account), session: HTTPStub.session())

        let entries = try await connector.list(directory: connector.root)
        #expect(entries.map(\.name) == ["Movies", "Alien.1979.mp4", "Heat.1995.mkv"])
        let heat = try #require(entries.first { $0.name == "Heat.1995.mkv" })
        #expect(heat.url.absoluteString == "dropbox://\(account.key)/id%3Ah/Heat.1995.mkv")
        #expect(SourceURL.parseAccountItem(heat.url)?.ids == ["id:h"])

        let requests = HTTPStub.requests(to: host)
        #expect(requests[0].url.path() == "/2/files/list_folder")
        #expect(requests[0].jsonBody?["path"] as? String == "")
        #expect(requests[0].jsonBody?["recursive"] as? Bool == false)
        #expect(requests[1].jsonBody?["cursor"] as? String == "c1")
    }

    @Test func enumeratesDropboxRecursivelyInOneListing() async throws {
        let host = "api.dropboxapi.com"
        HTTPStub.register(host: host) { _ in
            StubResponse.json(["entries": [
                [".tag": "folder", "name": "Movies", "id": "id:m", "path_lower": "/movies"],
                [".tag": "file", "name": "Heat.1995.mkv", "id": "id:h", "path_lower": "/movies/heat.1995.mkv"],
                [".tag": "file", "name": "Old.2001.mkv", "id": "id:o", "path_lower": "/.trash/old.2001.mkv"],
                [".tag": "file", "name": "readme.txt", "id": "id:r", "path_lower": "/movies/readme.txt"]
            ], "cursor": "c", "has_more": false])
        }
        defer { HTTPStub.unregister(host: host) }
        let account = account(.dropbox)
        let connector = DropboxConnector(account: account, tokens: await tokens(for: account), session: HTTPStub.session())

        let folder = SourceURL.accountItem(kind: .dropbox, account: account.key, ids: ["id:films"], name: "Films")
        let videos = try await connector.enumerateVideos(under: folder)
        #expect(videos.map(\.name) == ["Heat.1995.mkv"])
        let request = try #require(HTTPStub.requests(to: host).first)
        #expect(request.jsonBody?["path"] as? String == "id:films")
        #expect(request.jsonBody?["recursive"] as? Bool == true)
    }

    @Test func dropboxStreamsFromATemporaryLink() async throws {
        let host = "api.dropboxapi.com"
        let links = Counter()
        HTTPStub.register(host: host) { _ in
            StubResponse.json(["link": "https://dl.dropboxusercontent.com/link-\(links.increment())", "metadata": [:]])
        }
        defer { HTTPStub.unregister(host: host) }
        let account = account(.dropbox)
        let resolver = DropboxContentResolver(
            fileID: "id:h", accountKey: account.key, tokens: await tokens(for: account), session: HTTPStub.session()
        )
        #expect(try await resolver.contentRequest(refresh: false).url?.absoluteString == "https://dl.dropboxusercontent.com/link-1")
        #expect(try await resolver.contentRequest(refresh: true).url?.absoluteString == "https://dl.dropboxusercontent.com/link-2")
        let request = try #require(HTTPStub.requests(to: host).first)
        #expect(request.url.path() == "/2/files/get_temporary_link")
        #expect(request.jsonBody?["path"] as? String == "id:h")
    }

    // MARK: - WebDAV

    private static let multistatus = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
          <d:response>
            <d:href>/remote.php/dav/files/me/Movies/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/me/Movies/Heat%20(1995).mkv</d:href>
            <d:propstat><d:prop><d:resourcetype/><d:getcontentlength>1234</d:getcontentlength>
              <d:getlastmodified>Tue, 15 Nov 1994 12:45:26 GMT</d:getlastmodified></d:prop>
              <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>https://HOST/remote.php/dav/files/me/Movies/Season%201/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/remote.php/dav/files/me/Movies/.DS_Store</d:href>
            <d:propstat><d:prop><d:resourcetype/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
        </d:multistatus>
        """

    @Test func parsesWebDAVMultistatusFromDifferentServers() throws {
        let request = try #require(URL(string: "https://cloud.example.com/remote.php/dav/files/me/Movies/"))
        let canonical = try #require(URL(string: "davs://cloud.example.com/remote.php/dav/files/me/Movies/"))
        let body = Self.multistatus.replacingOccurrences(of: "HOST", with: "cloud.example.com")
        let entries = try #require(WebDAVConnector.parseMultistatus(Data(body.utf8), requestURL: request, canonicalDirectory: canonical))
        #expect(entries.map(\.name) == ["Heat (1995).mkv", "Season 1", ".DS_Store"])
        let heat = entries[0]
        #expect(heat.url.absoluteString == "davs://cloud.example.com/remote.php/dav/files/me/Movies/Heat%20(1995).mkv")
        #expect(heat.size == 1234)
        #expect(heat.modified == Date(timeIntervalSince1970: 784_903_526))
        #expect(!heat.isDirectory)
        #expect(entries[1].isDirectory)
        #expect(entries[1].url.absoluteString.hasSuffix("/Season%201/"))

        // Apache mod_dav: uppercase prefix and properties in another prefix.
        let apache = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:"><D:response xmlns:lp1="DAV:">
            <D:href>/dav/Alien.1979.mp4</D:href>
            <D:propstat><D:prop><lp1:resourcetype/><lp1:getcontentlength>5</lp1:getcontentlength></D:prop>
            <D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>
            """
        let apacheEntries = try #require(WebDAVConnector.parseMultistatus(
            Data(apache.utf8),
            requestURL: URL(string: "http://nas.local/dav/")!,
            canonicalDirectory: URL(string: "dav://nas.local/dav/")!
        ))
        #expect(apacheEntries.map(\.name) == ["Alien.1979.mp4"])
        #expect(apacheEntries.first?.size == 5)
        #expect(apacheEntries.first?.url.absoluteString == "dav://nas.local/dav/Alien.1979.mp4")
    }

    @Test func listsAWebDAVFolderWithPropfind() async throws {
        let host = HTTPStub.uniqueHost("dav")
        let body = Self.multistatus.replacingOccurrences(of: "HOST", with: host)
        HTTPStub.register(host: host) { _ in (207, ["Content-Type": "application/xml"], Data(body.utf8)) }
        defer { HTTPStub.unregister(host: host) }
        var connector = try #require(WebDAVConnector(
            address: "https://\(host)/remote.php/dav/files/me/Movies",
            credential: NetworkCredential(username: "me", password: "secret")
        ))
        connector.session = HTTPStub.session()
        #expect(connector.root.absoluteString == "davs://\(host)/remote.php/dav/files/me/Movies/")

        let entries = try await connector.list(directory: connector.root)
        // Hidden files are left out; folders come first.
        #expect(entries.map(\.name) == ["Season 1", "Heat (1995).mkv"])
        let request = try #require(HTTPStub.requests(to: host).first)
        #expect(request.method == "PROPFIND")
        #expect(request.header("Depth") == "1")
        #expect(request.url.absoluteString == "https://\(host)/remote.php/dav/files/me/Movies/")
        #expect(String(decoding: request.body ?? Data(), as: UTF8.self).contains("getcontentlength"))
    }

    @Test func aRefusedWebDAVLoginSaysSo() async throws {
        let host = HTTPStub.uniqueHost("dav")
        HTTPStub.register(host: host) { _ in StubResponse.text("", status: 401, headers: ["WWW-Authenticate": "Basic realm=\"x\""]) }
        defer { HTTPStub.unregister(host: host) }
        var connector = try #require(WebDAVConnector(address: "https://\(host)/dav/", credential: nil))
        connector.session = HTTPStub.session()
        await #expect(throws: ConnectorError.authenticationFailed(host: host)) {
            try await connector.validate()
        }
    }

    /// A session reuses the first login a server accepts for every later
    /// request, so connectors share one only when their logins match: a
    /// wrong or missing password must never ride on an earlier login.
    @Test func webDAVLoginsGetTheirOwnSessions() throws {
        let login = NetworkCredential(username: "me", password: "right")
        let first = try #require(WebDAVConnector(address: "https://nas.local/dav/", credential: login))
        let second = try #require(WebDAVConnector(address: "https://nas.local/dav/Movies/", credential: login))
        let mistyped = try #require(WebDAVConnector(
            address: "https://nas.local/dav/", credential: NetworkCredential(username: "me", password: "wrong")
        ))
        let guest = try #require(WebDAVConnector(address: "https://nas.local/dav/", credential: nil))
        let emptyLogin = try #require(WebDAVConnector(
            address: "https://nas.local/dav/", credential: NetworkCredential(username: "", password: "")
        ))

        #expect(first.session === second.session)
        #expect(first.session !== mistyped.session)
        #expect(first.session !== guest.session)
        #expect(guest.session === emptyLogin.session)
        #expect(first.session !== WebDAVConnector.session)
    }

    @Test func normalizesWebDAVAddresses() {
        #expect(WebDAVConnector.canonicalRoot(from: "https://cloud.example.com/remote.php/dav/files/me")?.absoluteString
            == "davs://cloud.example.com/remote.php/dav/files/me/")
        #expect(WebDAVConnector.canonicalRoot(from: "http://nas.local:5005")?.absoluteString == "dav://nas.local:5005/")
        #expect(WebDAVConnector.canonicalRoot(from: "nas.local/dav")?.absoluteString == "davs://nas.local/dav/")
        #expect(WebDAVConnector.canonicalRoot(from: "https://me:pw@nas.local/")?.absoluteString == "davs://nas.local/")
        #expect(WebDAVConnector.canonicalRoot(from: "ftp://nas.local/") == nil)
        #expect(WebDAVConnector.canonicalRoot(from: "  ") == nil)
        #expect(WebDAVConnector.httpURL(for: URL(string: "davs://nas.local:5006/a%20b/")!)?.absoluteString == "https://nas.local:5006/a%20b/")
        #expect(WebDAVConnector.httpURL(for: URL(string: "dav://nas.local/x.mkv")!)?.absoluteString == "http://nas.local/x.mkv")
    }

    // MARK: - S3

    private let signer = S3Signer(
        accessKeyID: "AKIAIOSFODNN7EXAMPLE",
        secretAccessKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        region: "us-east-1"
    )
    private let exampleDate = Date(timeIntervalSince1970: 1_369_353_600) // 2013-05-24T00:00:00Z

    @Test func signsListRequestsLikeAWSsExample() throws {
        // "GET Bucket (List Objects)" from the AWS Signature Version 4 docs.
        var request = URLRequest(url: try #require(URL(string: "https://examplebucket.s3.amazonaws.com/?max-keys=2&prefix=J")))
        signer.sign(&request, date: exampleDate)
        #expect(request.value(forHTTPHeaderField: "x-amz-date") == "20130524T000000Z")
        #expect(request.value(forHTTPHeaderField: "Authorization")
            == "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7")
    }

    @Test func presignsURLsLikeAWSsExample() throws {
        // The query-string authentication example from the AWS docs.
        let url = try #require(signer.presign(URL(string: "https://examplebucket.s3.amazonaws.com/test.txt")!, date: exampleDate, expires: 86400))
        let items = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
        #expect(items.first { $0.name == "X-Amz-Signature" }?.value
            == "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404")
        #expect(items.first { $0.name == "X-Amz-Credential" }?.value == "AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request")
    }

    @Test func addressesBucketsByPathOrHost() throws {
        let endpoint = try #require(URL(string: "http://minio.local:9000"))
        let pathStyle = S3Configuration(endpoint: endpoint, region: "us-east-1", bucket: "films", usesPathStyle: true)
        #expect(S3Connector.bucketURL(configuration: pathStyle, key: "Heat (1995)/Heat.mkv").absoluteString
            == "http://minio.local:9000/films/Heat%20%281995%29/Heat.mkv")
        let aws = S3Configuration(endpoint: URL(string: "https://s3.us-east-1.amazonaws.com")!, region: "us-east-1", bucket: "films", usesPathStyle: false)
        #expect(S3Connector.bucketURL(configuration: aws, key: "a b.mkv", query: [URLQueryItem(name: "list-type", value: "2")]).absoluteString
            == "https://films.s3.us-east-1.amazonaws.com/a%20b.mkv?list-type=2")
        #expect(S3Connector.defaultUsesPathStyle(endpoint: aws.endpoint, bucket: "films") == false)
        #expect(S3Connector.defaultUsesPathStyle(endpoint: aws.endpoint, bucket: "my.films") == true)
        #expect(S3Connector.defaultUsesPathStyle(endpoint: endpoint, bucket: "films") == true)
    }

    @Test func listsAnS3PrefixAcrossPages() async throws {
        let host = HTTPStub.uniqueHost("s3")
        let pages = Counter()
        HTTPStub.register(host: host) { _ in
            if pages.increment() == 1 {
                return StubResponse.text("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                      <Name>films</Name><Prefix>Movies/</Prefix><IsTruncated>true</IsTruncated>
                      <NextContinuationToken>next-1</NextContinuationToken>
                      <Contents><Key>Movies/</Key><Size>0</Size></Contents>
                      <Contents><Key>Movies/Heat.1995.mkv</Key><Size>1000</Size><LastModified>2024-01-02T03:04:05.000Z</LastModified></Contents>
                      <CommonPrefixes><Prefix>Movies/Classics/</Prefix></CommonPrefixes>
                    </ListBucketResult>
                    """, status: 200)
            }
            return StubResponse.text("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                  <IsTruncated>false</IsTruncated>
                  <Contents><Key>Movies/Alien.1979.mp4</Key><Size>5</Size></Contents>
                </ListBucketResult>
                """, status: 200)
        }
        defer { HTTPStub.unregister(host: host) }
        let configuration = S3Configuration(endpoint: URL(string: "https://\(host)")!, region: "us-east-1", bucket: "films", usesPathStyle: true)
        var connector = S3Connector(configuration: configuration, credential: NetworkCredential(username: "AKID", password: "secret", s3: configuration))
        connector.session = HTTPStub.session()

        let folder = SourceURL.s3(account: connector.accountKey, bucket: "films", key: "Movies/")
        let entries = try await connector.list(directory: folder)
        #expect(entries.map(\.name) == ["Classics", "Alien.1979.mp4", "Heat.1995.mkv"])
        let classics = try #require(entries.first)
        #expect(classics.isDirectory)
        #expect(SourceURL.parseS3(classics.url)?.key == "Movies/Classics/")
        #expect(entries.last?.size == 1000)

        let requests = HTTPStub.requests(to: host)
        #expect(requests.count == 2)
        #expect(requests[0].url.path() == "/films/")
        #expect(requests[0].queryValue("list-type") == "2")
        #expect(requests[0].queryValue("delimiter") == "/")
        #expect(requests[0].queryValue("prefix") == "Movies/")
        #expect(requests[1].queryValue("continuation-token") == "next-1")
        #expect(requests.allSatisfy { $0.header("Authorization")?.hasPrefix("AWS4-HMAC-SHA256 Credential=AKID/") == true })
    }

    @Test func explainsS3LoginAndRegionErrors() {
        let signature = Data("<Error><Code>SignatureDoesNotMatch</Code></Error>".utf8)
        #expect(S3Connector.error(status: 403, body: signature, host: "s3.example.com") == .authenticationFailed(host: "s3.example.com"))
        let region = Data("<Error><Code>AuthorizationHeaderMalformed</Code><Region>eu-west-1</Region></Error>".utf8)
        #expect(S3Connector.error(status: 400, body: region, host: "s3.example.com") == .bucketInAnotherRegion(region: "eu-west-1"))
    }

    @Test func s3StreamsThroughFreshlySignedURLs() async throws {
        let configuration = S3Configuration(endpoint: URL(string: "https://s3.example.com")!, region: "auto", bucket: "films", usesPathStyle: true)
        let resolver = S3ContentResolver(
            configuration: configuration,
            credential: NetworkCredential(username: "AKID", password: "secret", s3: configuration),
            key: "Movies/Heat 1995.mkv"
        )
        #expect(resolver.usesPreauthorizedLinks)
        let url = try #require(try await resolver.contentRequest(refresh: false).url)
        #expect(url.path(percentEncoded: true) == "/films/Movies/Heat%201995.mkv")
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        #expect(items.contains { $0.name == "X-Amz-Signature" })
        #expect(items.first { $0.name == "X-Amz-Credential" }?.value?.hasSuffix("/auto/s3/aws4_request") == true)
    }
}
