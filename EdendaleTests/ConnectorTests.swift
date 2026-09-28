//
//  ConnectorTests.swift
//  EdendaleTests
//
//  Connector-layer tests: URL credential handling stays pure, canonical
//  source URLs round-trip for every kind, the factory rebuilds connectors
//  and playback sources from what the library stores, and the default
//  enumeration walks trees the way imports rely on. The libvlc-backed
//  directory listing is smoke-tested against a local temp directory —
//  libvlc's directory parsing is scheme-agnostic, so this exercises the
//  exact machinery SMB and NFS browsing use, minus the network leg.
//

import Testing
import Foundation
@testable import Edendale

struct ConnectorURLTests {

    @Test func authenticatedURLInjectsPercentEncodedUserinfo() throws {
        let url = try #require(URL(string: "smb://nas.local/Media/Films"))
        let credential = NetworkCredential(username: "long ju", password: "p@ss:w rd")

        let authed = try #require(VLCNetworkBrowser.authenticatedURL(url, credential: credential))
        let components = try #require(URLComponents(url: authed, resolvingAgainstBaseURL: false))

        #expect(components.user == "long ju")
        #expect(components.password == "p@ss:w rd")
        #expect(components.host == "nas.local")
        #expect(components.path == "/Media/Films")
        // The raw string must be a valid MRL: no bare spaces or colons leak in.
        #expect(!authed.absoluteString.contains(" "))
    }

    @Test func authenticatedURLPassesGuestThroughUntouched() throws {
        let url = try #require(URL(string: "smb://nas.local/Media"))
        #expect(VLCNetworkBrowser.authenticatedURL(url, credential: nil) == url)
        let guest = NetworkCredential(username: "", password: "")
        #expect(VLCNetworkBrowser.authenticatedURL(url, credential: guest) == url)
    }

    @Test func strippingUserinfoRemovesCredentials() throws {
        let stripped = try #require(
            VLCNetworkBrowser.strippingUserinfo(from: "smb://user:secret@nas.local/Media/file.mkv")
        )
        #expect(stripped.absoluteString == "smb://nas.local/Media/file.mkv")
    }

    @Test func smbConnectorBuildsRootFromHost() throws {
        let connector = try #require(SMBConnector(host: " nas.local ", credential: nil))
        #expect(connector.root.absoluteString == "smb://nas.local/")
        #expect(SMBConnector(host: "   ", credential: nil) == nil)
    }

    @Test func smbConnectorRebuildsFromStoredSourceURL() throws {
        let sourceURL = try #require(URL(string: "smb://nas.local:1445/Media/Films"))
        let connector = try #require(SMBConnector(sourceURL: sourceURL, store: InMemorySecretStore()))
        #expect(connector.host == "nas.local")
        #expect(connector.port == 1445)
    }
}

// MARK: - Canonical source URLs

struct SourceURLTests {

    @Test func accountKeysHashTheKindAndSubject() throws {
        // First 32 hex digits of SHA-256("kind:subject"), computed independently.
        #expect(SourceURL.accountKey(kind: .googleDrive, subject: "110169484474386276334") == "fc33299258dcfba4155a09b5ec6ea6c9")
        #expect(SourceURL.accountKey(kind: .oneDrive, subject: "48d31887-5fad-4d73-a9f5-3c356e68a038") == "efaa7701612dd41d3d0a949ca736bce4")
        #expect(SourceURL.accountKey(kind: .dropbox, subject: "dbid:AAH4f99T0taONIb-OurWxbNQ6ywGRopQngc") == "7e1576915dd882c6cd3cb73befcb3ddd")
        let endpoint = try #require(URL(string: "https://S3.us-east-1.amazonaws.com/"))
        #expect(SourceURL.s3AccountKey(endpoint: endpoint, bucket: "examplebucket", accessKeyID: "AKIAIOSFODNN7EXAMPLE") == "a8e9cfc310a2f9a91163345e524e29b4")
        // The same subject on another provider is another account.
        #expect(SourceURL.accountKey(kind: .googleDrive, subject: "x") != SourceURL.accountKey(kind: .dropbox, subject: "x"))
    }

    @Test(arguments: [
        "The.Matrix.1999.mkv",
        "Heat (1995) #1: Director's Cut? 50%.mkv",
        "Amélie.2001.mkv",
        "Slash/In/Name.S01E02.mkv",
        "  spaced  .mp4"
    ])
    func accountItemURLsRoundTrip(name: String) throws {
        let account = SourceURL.accountKey(kind: .googleDrive, subject: "subject")
        let drive = SourceURL.accountItem(kind: .googleDrive, account: account, ids: ["1a2B-c_3"], name: name)
        #expect(drive.scheme == "gdrive")
        #expect(drive.host() == account)
        let parsed = try #require(SourceURL.parseAccountItem(drive))
        #expect(parsed.kind == .googleDrive)
        #expect(parsed.account == account)
        #expect(parsed.ids == ["1a2B-c_3"])
        #expect(parsed.name == name)
        // MediaParser and the extension filter read the real file name.
        #expect(drive.lastPathComponent == name)
        #expect(drive.pathExtension == (name as NSString).pathExtension)

        let oneDrive = SourceURL.accountItem(kind: .oneDrive, account: account, ids: ["b!drive", "01ITEM"], name: name)
        #expect(SourceURL.parseAccountItem(oneDrive)?.ids == ["b!drive", "01ITEM"])
        #expect(SourceURL.parseAccountItem(oneDrive)?.name == name)

        let dropbox = SourceURL.accountItem(kind: .dropbox, account: account, ids: ["id:a4ayc_80_OEAAAAAAAAAXw"], name: name)
        #expect(dropbox.absoluteString.contains("/id%3Aa4ayc_80_OEAAAAAAAAAXw/"))
        #expect(SourceURL.parseAccountItem(dropbox)?.ids == ["id:a4ayc_80_OEAAAAAAAAAXw"])
    }

    @Test func providerURLsClassifyByFileName() {
        let account = SourceURL.accountKey(kind: .dropbox, subject: "s")
        let movie = SourceURL.accountItem(kind: .dropbox, account: account, ids: ["id:1"], name: "The.Matrix.1999.mkv")
        guard case .movie(let title, let year) = MediaParser.parse(fileURL: movie) else {
            Issue.record("Expected a movie")
            return
        }
        #expect(title == "The Matrix")
        #expect(year == 1999)

        let episode = SourceURL.accountItem(kind: .oneDrive, account: account, ids: ["d", "i"], name: "Show.Name.S01E02.mkv")
        guard case .episode(let show, let season, let number) = MediaParser.parse(fileURL: episode) else {
            Issue.record("Expected an episode")
            return
        }
        #expect(show == "Show Name")
        #expect(season == 1)
        #expect(number == 2)
    }

    @Test func folderURLsCarryListingHints() throws {
        let url = SourceURL.accountItem(
            kind: .googleDrive, account: "abc", ids: ["folder1"], name: "Team Films",
            query: [URLQueryItem(name: "drive", value: "0AB")]
        )
        let parsed = try #require(SourceURL.parseAccountItem(url))
        #expect(parsed.name == "Team Films")
        #expect(parsed.queryValue("drive") == "0AB")
    }

    @Test func rejectsMalformedAccountURLs() throws {
        #expect(SourceURL.parseAccountItem(try #require(URL(string: "gdrive://abc/onlyname.mkv"))) == nil)
        #expect(SourceURL.parseAccountItem(try #require(URL(string: "onedrive://abc/d/Name.mkv"))) == nil)
        #expect(SourceURL.parseAccountItem(try #require(URL(string: "smb://nas/share/Name.mkv"))) == nil)
        #expect(SourceURL.parseAccountItem(try #require(URL(string: "dropbox:///id/Name.mkv"))) == nil)
    }

    @Test func s3URLsRoundTripKeysAndPrefixes() throws {
        let object = SourceURL.s3(account: "acct", bucket: "films", key: "Movies/Heat (1995)/Heat 1995.mkv")
        #expect(object.absoluteString == "s3://acct/films/Movies/Heat%20(1995)/Heat%201995.mkv")
        let parsed = try #require(SourceURL.parseS3(object))
        #expect(parsed.bucket == "films")
        #expect(parsed.key == "Movies/Heat (1995)/Heat 1995.mkv")
        #expect(!parsed.isPrefix)
        #expect(object.lastPathComponent == "Heat 1995.mkv")

        let prefix = SourceURL.s3(account: "acct", bucket: "films", key: "Movies/")
        #expect(SourceURL.parseS3(prefix)?.key == "Movies/")
        #expect(SourceURL.parseS3(prefix)?.isPrefix == true)
        let root = SourceURL.s3(account: "acct", bucket: "films", key: "")
        #expect(root.absoluteString == "s3://acct/films/")
        #expect(SourceURL.parseS3(root)?.key == "")
    }

    @Test func serverURLsEncodeEachSegment() throws {
        let url = try #require(SourceURL.server(
            scheme: "sftp", host: "nas.local", port: 2222,
            pathSegments: ["home", "me", "Films & TV", "Heat #1.mkv"]
        ))
        #expect(url.absoluteString == "sftp://nas.local:2222/home/me/Films%20&%20TV/Heat%20%231.mkv")
        #expect(SourceURL.pathSegments(of: url) == ["home", "me", "Films & TV", "Heat #1.mkv"])
        let folder = try #require(SourceURL.server(scheme: "nfs", host: "nas", pathSegments: ["export", "video"], isDirectory: true))
        #expect(folder.absoluteString == "nfs://nas/export/video/")
    }

    @Test func schemesMapToPersistedKinds() {
        #expect(MediaSourceKind(scheme: "smb2") == .smb)
        #expect(MediaSourceKind(scheme: "DAV") == .webdav)
        #expect(MediaSourceKind(scheme: "davs") == .webdav)
        #expect(MediaSourceKind(scheme: "gdrive") == .googleDrive)
        #expect(MediaSourceKind(scheme: "https") == nil)
        // Raw values are persisted on VideoFolder: never rename a case.
        #expect(MediaSourceKind.allCases.map(\.rawValue)
            == ["local", "smb", "nfs", "sftp", "webdav", "s3", "gdrive", "onedrive", "dropbox"])
        #expect(MediaSourceKind.allCases.filter(\.isCloudAccount) == [.googleDrive, .oneDrive, .dropbox])
        #expect(MediaSourceKind.allCases.filter(\.usesServerLogin) == [.smb, .sftp, .webdav, .s3])
    }
}

// MARK: - Factory and stores

struct ConnectorFactoryTests {

    private func environment() -> (ConnectorFactory.Environment, InMemorySecretStore) {
        let secrets = InMemorySecretStore()
        let vault = CloudAccountVault(store: secrets)
        let tokens = CloudTokenProvider(vault: vault, session: HTTPStub.session(), configuration: { _ in nil })
        return (ConnectorFactory.Environment(secrets: secrets, accounts: vault, tokens: tokens), secrets)
    }

    @Test func rebuildsServerConnectorsWithTheirSavedLogins() throws {
        let (environment, secrets) = environment()
        let login = NetworkCredential(username: "me", password: "pw")
        try NetworkCredentialStore.save(login, kind: .smb, host: "NAS.local", store: secrets)

        let smb = try #require(ConnectorFactory.connector(
            forSource: URL(string: "smb://nas.local/Media")!, kind: .smb, environment: environment
        ) as? SMBConnector)
        #expect(smb.credential == login)

        // SFTP needs its login; without one the source must sign in again.
        let sftpURL = URL(string: "sftp://nas.local:2222/home/me/Films/")!
        #expect(ConnectorFactory.connector(forSource: sftpURL, kind: .sftp, environment: environment) == nil)
        try NetworkCredentialStore.save(login, kind: .sftp, host: "nas.local", store: secrets)
        let sftp = try #require(ConnectorFactory.connector(forSource: sftpURL, kind: .sftp, environment: environment) as? SFTPConnector)
        #expect(sftp.port == 2222)
        #expect(sftp.startPath == "/home/me/Films")

        // WebDAV works as a guest, S3 never does.
        #expect(ConnectorFactory.connector(forSource: URL(string: "davs://cloud.example.com/dav/")!, kind: .webdav, environment: environment) != nil)
        #expect(ConnectorFactory.connector(forSource: URL(string: "s3://acct/films/")!, kind: .s3, environment: environment) == nil)
        #expect(ConnectorFactory.connector(forSource: URL(string: "nfs://nas/export/")!, kind: .nfs, environment: environment) != nil)
        #expect(ConnectorFactory.connector(forSource: URL(fileURLWithPath: "/tmp"), kind: .local, environment: environment) == nil)
    }

    @Test func rebuildsCloudConnectorsOnlyWithTheirAccount() throws {
        let (environment, _) = environment()
        let account = CloudAccount(
            kind: .googleDrive, subject: "sub", email: "me@example.com", displayName: nil,
            refreshToken: "r", scopes: [], driveID: nil
        )
        let folder = SourceURL.accountItem(kind: .googleDrive, account: account.key, ids: ["f"], name: "Films")
        #expect(ConnectorFactory.connector(forSource: folder, kind: .googleDrive, environment: environment) == nil)
        #expect(throws: ConnectorError.signInRequired(provider: "Google Drive")) {
            try ConnectorFactory.byteSource(for: folder, environment: environment)
        }

        try environment.accounts.save(account)
        let connector = try #require(ConnectorFactory.connector(forSource: folder, kind: .googleDrive, environment: environment))
        #expect(connector is GoogleDriveConnector)
        #expect(connector.accountLabel == "me@example.com")
        #expect(try ConnectorFactory.byteSource(for: folder, environment: environment) is RemoteByteSource)
    }

    @Test func buildsByteSourcesForEveryStreamedKind() throws {
        let (environment, secrets) = environment()
        #expect(try ConnectorFactory.byteSource(for: URL(string: "nfs://nas/export/Heat.mkv")!, environment: environment) is RemoteFileByteSource)
        #expect(try ConnectorFactory.byteSource(for: URL(string: "sftp://nas/home/Heat.mkv")!, environment: environment) is RemoteFileByteSource)
        #expect(try ConnectorFactory.byteSource(for: URL(string: "davs://nas/dav/Heat.mkv")!, environment: environment) is RemoteByteSource)
        #expect(throws: ConnectorError.signInRequired(provider: "S3-Compatible Storage")) {
            try ConnectorFactory.byteSource(for: URL(string: "s3://acct/films/Heat.mkv")!, environment: environment)
        }
        let configuration = S3Configuration(endpoint: URL(string: "https://s3.example.com")!, region: "auto", bucket: "films", usesPathStyle: true)
        try NetworkCredentialStore.save(
            NetworkCredential(username: "AKID", password: "secret", s3: configuration), kind: .s3, host: "acct", store: secrets
        )
        #expect(try ConnectorFactory.byteSource(for: URL(string: "s3://acct/films/Heat.mkv")!, environment: environment) is RemoteByteSource)
        #expect(throws: ConnectorError.invalidAddress) {
            try ConnectorFactory.byteSource(for: URL(string: "smb://nas/share/Heat.mkv")!, environment: environment)
        }
    }

    @Test func storesLoginsPerKindAndHost() throws {
        let secrets = InMemorySecretStore()
        try NetworkCredentialStore.save(NetworkCredential(username: "a", password: "1"), kind: .smb, host: "NAS.local", store: secrets)
        try NetworkCredentialStore.save(NetworkCredential(username: "b", password: "2"), kind: .webdav, host: "nas.local", store: secrets)
        let s3 = S3Configuration(endpoint: URL(string: "https://s3.example.com")!, region: "auto", bucket: "films", usesPathStyle: true)
        try NetworkCredentialStore.save(NetworkCredential(username: "AKID", password: "3", s3: s3), kind: .s3, host: "acct", store: secrets)

        // SMB keeps the item name older builds use; other kinds don't collide.
        #expect(secrets.accounts(withPrefix: "network-credential-") == ["network-credential-nas.local"])
        #expect(NetworkCredentialStore.credential(kind: .smb, host: "nas.local", store: secrets)?.username == "a")
        #expect(NetworkCredentialStore.credential(kind: .webdav, host: "NAS.LOCAL", store: secrets)?.username == "b")
        #expect(NetworkCredentialStore.credential(kind: .s3, host: "acct", store: secrets)?.s3 == s3)

        let logins = NetworkCredentialStore.savedLogins(store: secrets)
        // Sorted by provider name: "S3-Compatible Storage", "SMB", "WebDAV".
        #expect(logins.map(\.kind) == [.s3, .smb, .webdav])
        #expect(logins.first { $0.kind == .s3 }?.detail == "films @ s3.example.com")
        // Listing never exposes a password.
        #expect(logins.allSatisfy { $0.username != "1" && $0.username != "2" && $0.username != "3" })

        NetworkCredentialStore.remove(kind: .webdav, host: "nas.local", store: secrets)
        #expect(NetworkCredentialStore.credential(kind: .webdav, host: "nas.local", store: secrets) == nil)
        #expect(NetworkCredentialStore.credential(kind: .smb, host: "nas.local", store: secrets) != nil)
    }

    @Test func pinsHostKeysPerHostAndPort() throws {
        let secrets = InMemorySecretStore()
        try HostKeyStore.pin("SHA256:abc", host: "NAS.local", port: 22, store: secrets)
        #expect(HostKeyStore.pinnedFingerprint(host: "nas.local", port: 22, store: secrets) == "SHA256:abc")
        #expect(HostKeyStore.pinnedFingerprint(host: "nas.local", port: 2222, store: secrets) == nil)
        HostKeyStore.remove(host: "nas.local", port: 22, store: secrets)
        #expect(HostKeyStore.pinnedFingerprint(host: "nas.local", port: 22, store: secrets) == nil)
    }

    @Test func vaultsAccountsByKindAndKey() throws {
        let vault = CloudAccountVault(store: InMemorySecretStore())
        let drive = CloudAccount(kind: .googleDrive, subject: "s1", email: "b@example.com", displayName: nil, refreshToken: "r1", scopes: [], driveID: nil)
        let dropbox = CloudAccount(kind: .dropbox, subject: "s1", email: "a@example.com", displayName: nil, refreshToken: "r2", scopes: [], driveID: nil)
        try vault.save(drive)
        try vault.save(dropbox)
        #expect(vault.all().map(\.kind) == [.dropbox, .googleDrive])
        #expect(vault.accounts(of: .googleDrive).map(\.refreshToken) == ["r1"])
        #expect(vault.account(kind: .dropbox, key: drive.key) == nil)
        vault.remove(kind: .googleDrive, key: drive.key)
        #expect(vault.all().map(\.kind) == [.dropbox])
    }
}

// MARK: - Enumeration

/// A connector over an in-memory tree, keyed by folder URL.
private struct TreeConnector: MediaConnector {
    let kind: MediaSourceKind = .webdav
    let root: URL
    let tree: [URL: [ConnectorEntry]]
    let failing: Set<URL>

    func list(directory: URL) async throws -> [ConnectorEntry] {
        if failing.contains(directory) { throw ConnectorError.listingFailed(path: directory.path()) }
        return tree[directory] ?? []
    }
}

struct ConnectorWalkTests {

    private func folder(_ path: String) -> URL { URL(string: "davs://nas.local\(path)/")! }
    private func file(_ path: String) -> ConnectorEntry {
        let url = URL(string: "davs://nas.local\(path)")!
        return ConnectorEntry(name: url.lastPathComponent, url: url, isDirectory: false)
    }
    private func directory(_ path: String) -> ConnectorEntry {
        ConnectorEntry(name: String(path.split(separator: "/").last!), url: folder(path), isDirectory: true)
    }

    @Test func walksBreadthFirstSkippingHiddenAndNonVideo() async throws {
        let connector = TreeConnector(
            root: folder(""),
            tree: [
                folder(""): [directory("/Movies"), directory("/.Trash"), file("/Heat.1995.mkv"), file("/notes.txt")],
                folder("/Movies"): [file("/Movies/Alien.1979.mp4"), directory("/Movies/Broken"), file("/Movies/.hidden.mkv")],
                folder("/.Trash"): [file("/.Trash/Old.2001.mkv")]
            ],
            failing: [folder("/Movies/Broken")]
        )
        let videos = try await connector.enumerateVideos(under: connector.root)
        #expect(videos.map(\.name) == ["Heat.1995.mkv", "Alien.1979.mp4"])
    }

    @Test func aFailingTopFolderThrows() async {
        let connector = TreeConnector(root: folder(""), tree: [:], failing: [folder("")])
        await #expect(throws: ConnectorError.self) {
            _ = try await connector.enumerateVideos(under: connector.root)
        }
    }

    @Test func cyclesAndRunawayTreesStop() async throws {
        // A shortcut loop lists the same folder again.
        let loop = TreeConnector(
            root: folder("/a"),
            tree: [folder("/a"): [directory("/a"), file("/a/Heat.1995.mkv")]],
            failing: []
        )
        #expect(try await loop.enumerateVideos(under: loop.root).count == 1)

        let calls = Counter()
        let deep = try await ConnectorWalk.videos(under: folder("/0"), maxDirectories: 5) { directory in
            let depth = calls.increment()
            return [ConnectorEntry(name: "d\(depth)", url: folder("/\(depth)"), isDirectory: true)]
        }
        #expect(deep.isEmpty)
        #expect(calls.value == 5)
    }

    @Test func browseLocationsCompareByConnectorAndFolder() throws {
        let one = try #require(SMBConnector(host: "nas.local", credential: nil))
        let other = try #require(SMBConnector(host: "nas.local", credential: NetworkCredential(username: "me", password: "x")))
        #expect(AnyMediaConnector(one) == AnyMediaConnector(one))
        #expect(AnyMediaConnector(one) != AnyMediaConnector(other))
        #expect(AnyMediaConnector(one).hashValue == AnyMediaConnector(other).hashValue)

        let location = BrowseLocation(connector: one, url: one.root, name: "nas.local")
        let child = location.child(ConnectorEntry(name: "Media", url: URL(string: "smb://nas.local/Media")!, isDirectory: true))
        #expect(child.trail == ["nas.local", "Media"])
        #expect(child.displayPath == "nas.local › Media")
        #expect(Set([location, child, location]).count == 2)
    }

    @MainActor
    @Test func automaticRescansSkipRecentlyScannedRemoteSources() {
        let now = Date()
        let local = VideoFolder(name: "Local", folderPath: "/Movies")
        local.lastScannedAt = now
        #expect(LibraryController.needsAutomaticRescan(local, now: now))

        let remote = VideoFolder(name: "Drive", folderPath: "gdrive://a/f/Films", sourceKind: .googleDrive)
        #expect(LibraryController.needsAutomaticRescan(remote, now: now))
        remote.lastScannedAt = now.addingTimeInterval(-60)
        #expect(!LibraryController.needsAutomaticRescan(remote, now: now))
        remote.lastScannedAt = now.addingTimeInterval(-LibraryController.automaticRescanInterval)
        #expect(LibraryController.needsAutomaticRescan(remote, now: now))
    }
}

struct VLCNetworkBrowserListingTests {

    /// Lists a real directory through libvlc (instance, parse request,
    /// subitems) and checks folders and video files come back typed and
    /// sorted folders-first.
    @Test func listsLocalDirectoryEntries() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("edendale-connector-test-\(UUID().uuidString)", isDirectory: true)
        let season = root.appendingPathComponent("Season 1", isDirectory: true)
        try FileManager.default.createDirectory(at: season, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        for name in ["The.Matrix.1999.mkv", "Show.S01E01.mp4"] {
            try Data("stub".utf8).write(to: root.appendingPathComponent(name))
        }

        let entries = try await VLCNetworkBrowser.shared.list(directory: root, credential: nil)

        let directories = entries.filter(\.isDirectory).map(\.name)
        let files = entries.filter { !$0.isDirectory }.map(\.name)
        #expect(directories == ["Season 1"])
        #expect(files.sorted() == ["Show.S01E01.mp4", "The.Matrix.1999.mkv"])
        // Folders sort ahead of files.
        #expect(entries.first?.isDirectory == true)
    }
}
