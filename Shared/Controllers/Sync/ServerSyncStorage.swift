//
//  ServerSyncStorage.swift
//  Edendale
//
//  The server locations of SYNC-PROTOCOL.md §4, on the connectors that
//  already read media from them: WebDAV (`PROPFIND` for ETags and
//  modification times, `MKCOL` and `PUT` to write), SFTP (SwiftNIO SSH,
//  with the write, mkdir, remove, and rename requests added for sync), and
//  SMB (libsmb2 through EDSMBSyncClient). Logins come from the Keychain
//  and are applied to requests only; nothing here logs an address with a
//  credential or a file's contents.
//

import Foundation

// MARK: - WebDAV

nonisolated struct WebDAVSyncStorage: SyncStorage {
    let providerName = "webdav"
    /// The canonical `davs://` or `dav://` folder holding `Edendale/`.
    let root: URL
    let space: String
    let credential: NetworkCredential?
    var session: URLSession

    init(root: URL, space: String, credential: NetworkCredential?, session: URLSession? = nil) {
        self.root = WebDAVConnector.directoryURL(root)
        self.space = space
        self.credential = credential
        self.session = session ?? WebDAVConnector.loginSession(for: credential)
    }

    static let propfindBody = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>
        """

    var rootFolderURL: URL {
        root.appendingPathComponent(SyncFileFormat.rootFolderName, isDirectory: true)
    }

    var spaceFolderURL: URL {
        rootFolderURL.appendingPathComponent(space, isDirectory: true)
    }

    private var host: String { root.host() ?? "WebDAV" }

    func listSpace() async throws -> [SyncFileEntry] {
        let (data, status) = try await propfind(spaceFolderURL)
        switch status {
        case 207:
            guard let entries = Self.parseListing(data, requestURL: try httpURL(spaceFolderURL)) else {
                throw ConnectorError.listingFailed(path: spaceFolderURL.path())
            }
            return entries.sorted { $0.name < $1.name }
        case 404:
            try await makeCollection(rootFolderURL)
            try await makeCollection(spaceFolderURL)
            return []
        case 401, 403:
            throw ConnectorError.authenticationFailed(host: host)
        default:
            throw ConnectorError.listingFailed(path: spaceFolderURL.path())
        }
    }

    func read(_ name: String) async throws -> Data {
        var request = URLRequest(url: try httpURL(spaceFolderURL.appendingPathComponent(name)))
        request.httpMethod = "GET"
        let (data, status) = try await send(request)
        switch status {
        case 200: return data
        case 404: throw SyncStorageError.notFound(name)
        case 401, 403: throw ConnectorError.authenticationFailed(host: host)
        default: throw ConnectorError.serverError(provider: host, status: status)
        }
    }

    func write(_ name: String, data: Data) async throws {
        var request = URLRequest(url: try httpURL(spaceFolderURL.appendingPathComponent(name)))
        request.httpMethod = "PUT"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = data
        let (_, status) = try await send(request)
        switch status {
        case 200, 201, 204: return
        case 401, 403: throw ConnectorError.authenticationFailed(host: host)
        case 404, 409:
            // The folders went away between the listing and the write.
            try await makeCollection(rootFolderURL)
            try await makeCollection(spaceFolderURL)
            let (_, retry) = try await send(request)
            guard (200..<300).contains(retry) else { throw ConnectorError.serverError(provider: host, status: retry) }
        default: throw ConnectorError.serverError(provider: host, status: status)
        }
    }

    func delete(_ name: String) async throws {
        var request = URLRequest(url: try httpURL(spaceFolderURL.appendingPathComponent(name)))
        request.httpMethod = "DELETE"
        let (_, status) = try await send(request)
        switch status {
        case 200, 202, 204, 404: return
        case 401, 403: throw ConnectorError.authenticationFailed(host: host)
        default: throw ConnectorError.serverError(provider: host, status: status)
        }
    }

    // MARK: Requests

    private func propfind(_ folder: URL) async throws -> (Data, Int) {
        var request = URLRequest(url: try httpURL(folder))
        request.httpMethod = "PROPFIND"
        request.setValue("1", forHTTPHeaderField: "Depth")
        request.setValue("application/xml; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(Self.propfindBody.utf8)
        return try await send(request)
    }

    /// `MKCOL`; a collection already there (405) is fine.
    private func makeCollection(_ folder: URL) async throws {
        var request = URLRequest(url: try httpURL(folder))
        request.httpMethod = "MKCOL"
        let (_, status) = try await send(request)
        switch status {
        case 200, 201, 204, 405, 301, 302: return
        case 401, 403: throw ConnectorError.authenticationFailed(host: host)
        default: throw ConnectorError.serverError(provider: host, status: status)
        }
    }

    private func send(_ request: URLRequest) async throws -> (Data, Int) {
        var request = request
        request.cachePolicy = .reloadIgnoringLocalCacheData
        do {
            let (data, response) = try await session.data(for: request, delegate: WebDAVChallengeHandler(credential: credential))
            return (data, (response as? HTTPURLResponse)?.statusCode ?? 0)
        } catch let error as URLError {
            throw WebDAVConnector.connectorError(for: error, host: host)
        }
    }

    private func httpURL(_ url: URL) throws -> URL {
        guard let http = WebDAVConnector.httpURL(for: url) else { throw ConnectorError.invalidAddress }
        return http
    }

    /// The device files in a `multistatus` listing of the space folder.
    static func parseListing(_ data: Data, requestURL: URL) -> [SyncFileEntry]? {
        guard let tree = XMLTree.parse(data), tree.name == "multistatus" else { return nil }
        let listedPath = normalized(requestURL.path(percentEncoded: false))
        return tree.children("response").compactMap { response -> SyncFileEntry? in
            guard let href = response.value("href"),
                  let resolved = WebDAVConnector.resolve(href: href, against: requestURL)
            else { return nil }
            let path = resolved.path(percentEncoded: false)
            guard normalized(path) != listedPath else { return nil }
            let props = response.children("propstat")
                .filter { ($0.value("status") ?? "200").contains(" 200") || $0.value("status") == nil }
                .compactMap { $0.child("prop") }
            if props.contains(where: { $0.child("resourcetype")?.child("collection") != nil }) { return nil }
            guard let name = path.split(separator: "/").last.map(String.init),
                  SyncFileFormat.deviceID(ofFileName: name) != nil
            else { return nil }
            let size = props.lazy.compactMap { $0.value("getcontentlength").flatMap { Int64($0) } }.first
            let etag = props.lazy.compactMap { $0.value("getetag") }.first { !$0.isEmpty }
            let modified = props.lazy.compactMap { $0.value("getlastmodified") }.first { !$0.isEmpty }
            return SyncFileEntry(name: name, stamp: "\(etag ?? modified ?? ""):\(size ?? -1)", size: size)
        }
    }

    private static func normalized(_ path: String) -> String {
        var path = path
        while path.hasSuffix("/") { path.removeLast() }
        return path.isEmpty ? "/" : path
    }
}

// MARK: - SFTP

actor SFTPSyncStorage: SyncStorage {
    nonisolated let providerName = "sftp"

    private let host: String
    private let port: Int
    private let credential: NetworkCredential
    /// The absolute folder holding `Edendale/`.
    private let basePath: String
    private let space: String
    private let pinnedFingerprint: String?
    private var connection: SFTPConnection?

    init(host: String, port: Int, credential: NetworkCredential, basePath: String, space: String, pinnedFingerprint: String?) {
        self.host = host
        self.port = port
        self.credential = credential
        self.basePath = basePath
        self.space = space
        self.pinnedFingerprint = pinnedFingerprint
    }

    private var rootFolder: String { Self.join(basePath, SyncFileFormat.rootFolderName) }
    private var spaceFolder: String { Self.join(rootFolder, space) }

    func listSpace() async throws -> [SyncFileEntry] {
        let folder = spaceFolder
        let rootFolder = self.rootFolder
        return try await run { connection in
            let listing: [SFTPEntry]
            do {
                listing = try connection.contentsOfDirectory(atPath: folder)
            } catch ConnectorError.notFound, ConnectorError.listingFailed {
                if try connection.attributes(atPath: rootFolder) == nil {
                    try connection.createDirectory(atPath: rootFolder)
                }
                try connection.createDirectory(atPath: folder)
                return []
            }
            return listing.compactMap { entry -> SyncFileEntry? in
                guard !entry.isDirectory, SyncFileFormat.deviceID(ofFileName: entry.name) != nil else { return nil }
                let modified = entry.modified?.timeIntervalSince1970 ?? 0
                return SyncFileEntry(name: entry.name, stamp: "\(modified):\(entry.size)", size: entry.size < 0 ? nil : entry.size)
            }
            .sorted { $0.name < $1.name }
        }
    }

    func read(_ name: String) async throws -> Data {
        let path = Self.join(spaceFolder, name)
        return try await run { connection in
            do {
                return try connection.readFile(atPath: path, limit: SyncFileFormat.maxFileBytes + 1)
            } catch ConnectorError.notFound {
                throw SyncStorageError.notFound(name)
            } catch is SFTPFileTooLarge {
                throw SyncFileFormat.Rejection.tooLarge
            }
        }
    }

    func write(_ name: String, data: Data) async throws {
        let folder = spaceFolder
        let rootFolder = self.rootFolder
        let target = Self.join(folder, name)
        let temporary = target + ".tmp"
        try await run { connection in
            if try connection.attributes(atPath: folder) == nil {
                if try connection.attributes(atPath: rootFolder) == nil {
                    try connection.createDirectory(atPath: rootFolder)
                }
                try connection.createDirectory(atPath: folder)
            }
            try connection.writeFile(atPath: temporary, data: data)
            // Version 3's rename refuses an existing target on most servers.
            try? connection.removeFile(atPath: target)
            try connection.renameFile(atPath: temporary, toPath: target)
        }
    }

    func delete(_ name: String) async throws {
        let path = Self.join(spaceFolder, name)
        try await run { connection in
            do {
                try connection.removeFile(atPath: path)
            } catch ConnectorError.notFound {}
        }
    }

    /// Runs blocking work on the open connection, opening one first; a
    /// failure drops the connection so the next call reconnects.
    private func run<T: Sendable>(_ work: @escaping @Sendable (SFTPConnection) throws -> T) async throws -> T {
        let connection = try await open()
        do {
            return try await Blocking.run { try work(connection) }
        } catch {
            connection.abort()
            self.connection = nil
            throw SFTPConnector.connectorError(error, host: host)
        }
    }

    private func open() async throws -> SFTPConnection {
        if let connection { return connection }
        let host = self.host
        let port = self.port
        let credential = self.credential
        let pinned = self.pinnedFingerprint
        let opened = try await Blocking.run {
            try SFTPConnector.connect(host: host, port: port, credential: credential, pinned: pinned)
        }
        connection = opened
        return opened
    }

    nonisolated static func join(_ folder: String, _ name: String) -> String {
        folder.hasSuffix("/") ? folder + name : folder + "/" + name
    }
}

// MARK: - SMB

actor SMBSyncStorage: SyncStorage {
    nonisolated let providerName = "smb"

    private let server: String
    private let host: String
    private let share: String
    /// Inside the share, `/`-separated, no leading slash; empty at the
    /// share's root.
    private let basePath: String
    private let space: String
    private let credential: NetworkCredential?
    private var client: SMBSyncClient?

    /// `smb://host[:port]/share/path/` and the share's saved login.
    init(sourceURL: URL, space: String, store: any SecretStore = KeychainStore.shared) throws {
        guard let host = sourceURL.host() else { throw ConnectorError.invalidAddress }
        let segments = SourceURL.pathSegments(of: sourceURL)
        guard let share = segments.first else { throw ConnectorError.invalidAddress }
        self.host = host
        server = sourceURL.port.map { "\(host):\($0)" } ?? host
        self.share = share
        basePath = segments.dropFirst().joined(separator: "/")
        self.space = space
        credential = NetworkCredentialStore.credential(kind: .smb, host: host, store: store)
    }

    private var rootFolder: String { Self.join(basePath, SyncFileFormat.rootFolderName) }
    private var spaceFolder: String { Self.join(rootFolder, space) }

    func listSpace() async throws -> [SyncFileEntry] {
        let folder = spaceFolder
        let rootFolder = self.rootFolder
        return try await run { client in
            do {
                let entries = try client.listDirectory(atPath: folder)
                return entries.compactMap { entry -> SyncFileEntry? in
                    guard !entry.isDirectory, SyncFileFormat.deviceID(ofFileName: entry.name) != nil else { return nil }
                    let modified = entry.modified?.timeIntervalSince1970 ?? 0
                    return SyncFileEntry(name: entry.name, stamp: "\(modified):\(entry.size)", size: entry.size)
                }
                .sorted { $0.name < $1.name }
            } catch let error as NSError where RemoteFileFailure(error) == .notFound {
                try client.createDirectory(atPath: rootFolder)
                try client.createDirectory(atPath: folder)
                return []
            }
        }
    }

    func read(_ name: String) async throws -> Data {
        let path = Self.join(spaceFolder, name)
        return try await run { client in
            do {
                return try client.readFile(atPath: path, limit: UInt(SyncFileFormat.maxFileBytes))
            } catch let error as NSError where RemoteFileFailure(error) == .notFound {
                throw SyncStorageError.notFound(name)
            }
        }
    }

    func write(_ name: String, data: Data) async throws {
        let folder = spaceFolder
        let rootFolder = self.rootFolder
        let target = Self.join(folder, name)
        let temporary = target + ".tmp"
        try await run { client in
            do {
                try client.write(data, toPath: temporary)
            } catch let error as NSError where RemoteFileFailure(error) == .notFound {
                try client.createDirectory(atPath: rootFolder)
                try client.createDirectory(atPath: folder)
                try client.write(data, toPath: temporary)
            }
            try client.removeItem(atPath: target)
            try client.renameItem(atPath: temporary, toPath: target)
        }
    }

    func delete(_ name: String) async throws {
        let path = Self.join(spaceFolder, name)
        try await run { client in
            try client.removeItem(atPath: path)
        }
    }

    private func run<T: Sendable>(_ work: @escaping @Sendable (SMBSyncClient) throws -> T) async throws -> T {
        let client = try await open()
        do {
            let result = try await Blocking.run { try work(client) }
            if client.isBroken { self.client = nil }
            return result
        } catch {
            self.client = nil
            if let failure = RemoteFileFailure(error) {
                throw failure.connectorError(kind: .smb, host: host)
            }
            throw error
        }
    }

    private func open() async throws -> SMBSyncClient {
        if let client, !client.isBroken { return client }
        let server = self.server
        let share = self.share
        let credential = self.credential
        let opened = try await Blocking.run { () throws -> SMBSyncClient in
            // "DOMAIN;user" or "DOMAIN\user", as playback splits it.
            var user = credential?.username ?? ""
            var domain: String?
            for separator in [";", "\\"] {
                if let range = user.range(of: separator) {
                    domain = String(user[..<range.lowerBound])
                    user = String(user[range.upperBound...])
                    break
                }
            }
            do {
                return try SMBSyncClient(server: server, share: share, user: user, domain: domain, password: credential?.password)
            } catch {
                if let failure = RemoteFileFailure(error) { throw failure.connectorError(kind: .smb, host: server) }
                throw error
            }
        }
        client = opened
        return opened
    }

    nonisolated static func join(_ folder: String, _ name: String) -> String {
        folder.isEmpty ? name : (folder.hasSuffix("/") ? folder + name : folder + "/" + name)
    }
}

// MARK: - Shared helpers

extension SMBSyncClient: @unchecked Sendable {}
