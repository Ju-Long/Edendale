//
//  CloudSyncStorage.swift
//  Edendale
//
//  The API locations of SYNC-PROTOCOL.md §4: Google Drive under
//  `drive.file` (the app creates `Edendale/<space>/` in My Drive and sees
//  only what it created, so there is nothing else to list), OneDrive in the
//  app folder (`Apps/Edendale/<space>/`, `Files.ReadWrite.AppFolder`), and
//  Dropbox under `/Edendale/<space>/` (`files.content.write`). Requests go
//  through ProviderHTTP: a Bearer token per request, one refresh on 401,
//  backoff on rate limits. Tokens and URLs are never logged.
//

import Foundation

// MARK: - Google Drive

actor GoogleDriveSyncStorage: SyncStorage {
    nonisolated let providerName = "googleDrive"
    static let folderMimeType = "application/vnd.google-apps.folder"
    static let uploadBase = URL(string: "https://www.googleapis.com/upload/drive/v3/")!

    private let http: ProviderHTTP
    private let space: String
    /// The space folder's ID, from the location when known, else found or
    /// created at the first listing.
    private var spaceFolderID: String?
    /// File IDs by name, from the last listing.
    private var fileIDs: [String: String] = [:]

    init(
        account: CloudAccount,
        space: String,
        folderID: String? = nil,
        tokens: CloudTokenProvider = .shared,
        session: URLSession = .shared
    ) {
        http = ProviderHTTP(kind: .googleDrive, accountKey: account.key, tokens: tokens, session: session)
        self.space = space
        spaceFolderID = folderID
    }

    /// The space folder's ID once known, for the handoff's hint (§6.4).
    var resolvedFolderID: String? { spaceFolderID }

    func listSpace() async throws -> [SyncFileEntry] {
        let folderID = try await resolveSpaceFolder()
        var entries: [SyncFileEntry] = []
        var ids: [String: String] = [:]
        var pageToken: String?
        repeat {
            var query = [
                URLQueryItem(name: "q", value: "'\(folderID)' in parents and trashed = false"),
                URLQueryItem(name: "fields", value: "nextPageToken,files(id,name,modifiedTime,size)"),
                URLQueryItem(name: "pageSize", value: "1000")
            ]
            if let pageToken { query.append(URLQueryItem(name: "pageToken", value: pageToken)) }
            let request = URLRequest(url: GoogleDriveConnector.url("files", query: query))
            let page: FileList
            do {
                page = try await http.json(FileList.self, decoder: JSONDecoder()) { _ in request }
            } catch ConnectorError.notFound {
                // The folder from the location is gone: find or make it again.
                spaceFolderID = nil
                return try await listSpace()
            }
            for file in page.files where SyncFileFormat.deviceID(ofFileName: file.name) != nil {
                ids[file.name] = file.id
                entries.append(SyncFileEntry(
                    name: file.name,
                    stamp: "\(file.modifiedTime ?? ""):\(file.size ?? "")",
                    size: file.size.flatMap { Int64($0) }
                ))
            }
            pageToken = page.nextPageToken
        } while pageToken != nil
        fileIDs = ids
        return entries.sorted { $0.name < $1.name }
    }

    func read(_ name: String) async throws -> Data {
        let id = try await fileID(for: name)
        let request = URLRequest(url: GoogleDriveConnector.url("files/\(id)", query: [URLQueryItem(name: "alt", value: "media")]))
        let (data, _) = try await http.send { _ in request }
        return data
    }

    func write(_ name: String, data: Data) async throws {
        let folderID = try await resolveSpaceFolder()
        var existingID = fileIDs[name]
        if existingID == nil { existingID = try? await fileID(for: name) }
        if let id = existingID {
            // Replace the content in one request (§4: `files.update`, `uploadType=media`).
            var request = URLRequest(url: Self.uploadURL("files/\(id)", query: [URLQueryItem(name: "uploadType", value: "media")]))
            request.httpMethod = "PATCH"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = data
            _ = try await http.send { _ in request }
            return
        }
        // A new file: metadata and content in one multipart upload.
        let boundary = "edendale-" + UUID().uuidString
        let metadata = try JSONSerialization.data(withJSONObject: [
            "name": name,
            "parents": [folderID],
            "mimeType": "application/json"
        ] as [String: Any])
        var body = Data()
        body.append(Data("--\(boundary)\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n".utf8))
        body.append(metadata)
        body.append(Data("\r\n--\(boundary)\r\nContent-Type: application/json\r\n\r\n".utf8))
        body.append(data)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        var request = URLRequest(url: Self.uploadURL("files", query: [
            URLQueryItem(name: "uploadType", value: "multipart"),
            URLQueryItem(name: "fields", value: "id")
        ]))
        request.httpMethod = "POST"
        request.setValue("multipart/related; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = body
        struct Created: Decodable { let id: String }
        let created = try await http.json(Created.self, decoder: JSONDecoder()) { _ in request }
        fileIDs[name] = created.id
    }

    func delete(_ name: String) async throws {
        var existingID = fileIDs[name]
        if existingID == nil { existingID = try? await fileID(for: name) }
        guard let id = existingID else { return }
        var request = URLRequest(url: GoogleDriveConnector.url("files/\(id)", query: []))
        request.httpMethod = "DELETE"
        _ = try await http.send(allowing: [404]) { _ in request }
        fileIDs[name] = nil
    }

    // MARK: Folders

    private func resolveSpaceFolder() async throws -> String {
        if let spaceFolderID { return spaceFolderID }
        let root = try await folder(named: SyncFileFormat.rootFolderName, in: "root")
        let id = try await folder(named: space, in: root)
        spaceFolderID = id
        return id
    }

    /// The ID of the folder `name` directly under `parent`, created when
    /// missing. `drive.file` lists only folders this app made, which is
    /// exactly the set that matters.
    private func folder(named name: String, in parent: String) async throws -> String {
        let query = [
            URLQueryItem(
                name: "q",
                value: "name = '\(Self.escaped(name))' and '\(parent)' in parents and mimeType = '\(Self.folderMimeType)' and trashed = false"
            ),
            URLQueryItem(name: "fields", value: "files(id,name)"),
            URLQueryItem(name: "pageSize", value: "10")
        ]
        let request = URLRequest(url: GoogleDriveConnector.url("files", query: query))
        let found = try await http.json(FileList.self, decoder: JSONDecoder()) { _ in request }
        if let existing = found.files.first(where: { $0.name == name }) { return existing.id }

        var create = try ProviderHTTP.jsonRequest(
            GoogleDriveConnector.url("files", query: [URLQueryItem(name: "fields", value: "id")]),
            body: ["name": name, "mimeType": Self.folderMimeType, "parents": [parent]]
        )
        create.httpMethod = "POST"
        struct Created: Decodable { let id: String }
        return try await http.json(Created.self, decoder: JSONDecoder()) { _ in create }.id
    }

    private func fileID(for name: String) async throws -> String {
        if let id = fileIDs[name] { return id }
        _ = try await listSpace()
        guard let id = fileIDs[name] else { throw SyncStorageError.notFound(name) }
        return id
    }

    /// A string inside a Drive query's single quotes.
    static func escaped(_ value: String) -> String {
        value.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "'", with: "\\'")
    }

    static func uploadURL(_ path: String, query: [URLQueryItem]) -> URL {
        var components = URLComponents(url: uploadBase.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        components.queryItems = query
        return components.url!
    }

    private struct FileList: Decodable {
        let nextPageToken: String?
        let files: [File]
        struct File: Decodable {
            let id: String
            let name: String
            let modifiedTime: String?
            let size: String?
        }
    }
}

// MARK: - OneDrive

actor OneDriveSyncStorage: SyncStorage {
    nonisolated let providerName = "oneDrive"
    /// Above this, Graph wants an upload session.
    static let simpleUploadLimit = 4 * 1024 * 1024

    private let http: ProviderHTTP
    private let space: String
    private let session: URLSession

    init(account: CloudAccount, space: String, tokens: CloudTokenProvider = .shared, session: URLSession = .shared) {
        http = ProviderHTTP(kind: .oneDrive, accountKey: account.key, tokens: tokens, session: session)
        self.space = space
        self.session = session
    }

    func listSpace() async throws -> [SyncFileEntry] {
        var entries: [SyncFileEntry] = []
        var next: URL? = Self.url("me/drive/special/approot:/\(Self.segment(space)):/children", query: [
            URLQueryItem(name: "$select", value: "id,name,size,lastModifiedDateTime,eTag,file,folder"),
            URLQueryItem(name: "$top", value: "200")
        ])
        while let url = next {
            let request = URLRequest(url: url)
            let (data, response) = try await http.send(allowing: [404]) { _ in request }
            if response.statusCode == 404 {
                try await createSpaceFolder()
                return []
            }
            let page = try Self.decode(Page.self, from: data)
            for item in page.value where item.file != nil && SyncFileFormat.deviceID(ofFileName: item.name) != nil {
                entries.append(SyncFileEntry(
                    name: item.name,
                    stamp: "\(item.eTag ?? item.lastModifiedDateTime ?? ""):\(item.size ?? -1)",
                    size: item.size
                ))
            }
            next = page.nextLink.flatMap(URL.init(string:))
        }
        return entries.sorted { $0.name < $1.name }
    }

    func read(_ name: String) async throws -> Data {
        // The item's pre-authenticated link, fetched without a Bearer token:
        // the storage behind it refuses one.
        let request = URLRequest(url: Self.url(itemPath(name), query: []))
        let (data, _) = try await http.send { _ in request }
        struct Item: Decodable {
            let downloadURL: String?
            enum CodingKeys: String, CodingKey { case downloadURL = "@microsoft.graph.downloadUrl" }
        }
        guard let link = try Self.decode(Item.self, from: data).downloadURL, let url = URL(string: link) else {
            throw SyncStorageError.notFound(name)
        }
        let (content, response) = try await session.data(from: url)
        guard let status = (response as? HTTPURLResponse)?.statusCode, (200..<300).contains(status) else {
            throw SyncStorageError.failed(String(localized: "download a file from OneDrive"))
        }
        return content
    }

    func write(_ name: String, data: Data) async throws {
        if data.count <= Self.simpleUploadLimit {
            var request = URLRequest(url: Self.url(itemPath(name) + ":/content", query: []))
            request.httpMethod = "PUT"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = data
            _ = try await http.send { _ in request }
            return
        }
        var start = try ProviderHTTP.jsonRequest(
            Self.url(itemPath(name) + ":/createUploadSession", query: []),
            body: ["item": ["@microsoft.graph.conflictBehavior": "replace"]]
        )
        start.httpMethod = "POST"
        struct Session: Decodable { let uploadUrl: String }
        let upload = try await http.json(Session.self, decoder: JSONDecoder()) { _ in start }
        guard let uploadURL = URL(string: upload.uploadUrl) else {
            throw SyncStorageError.failed(String(localized: "upload to OneDrive"))
        }
        var put = URLRequest(url: uploadURL)
        put.httpMethod = "PUT"
        put.setValue("bytes 0-\(data.count - 1)/\(data.count)", forHTTPHeaderField: "Content-Range")
        put.httpBody = data
        let (_, response) = try await session.data(for: put)
        guard let status = (response as? HTTPURLResponse)?.statusCode, (200..<300).contains(status) else {
            throw SyncStorageError.failed(String(localized: "upload to OneDrive"))
        }
    }

    func delete(_ name: String) async throws {
        var request = URLRequest(url: Self.url(itemPath(name), query: []))
        request.httpMethod = "DELETE"
        _ = try await http.send(allowing: [404]) { _ in request }
    }

    private func createSpaceFolder() async throws {
        var request = try ProviderHTTP.jsonRequest(
            Self.url("me/drive/special/approot/children", query: []),
            body: ["name": space, "folder": [:] as [String: Any], "@microsoft.graph.conflictBehavior": "fail"]
        )
        request.httpMethod = "POST"
        // 409: it exists after all (made by another device between calls).
        _ = try await http.send(allowing: [409]) { _ in request }
    }

    private func itemPath(_ name: String) -> String {
        "me/drive/special/approot:/\(Self.segment(space))/\(Self.segment(name))"
    }

    static func segment(_ value: String) -> String {
        SourceURL.encodeSegment(value)
    }

    static func url(_ path: String, query: [URLQueryItem]) -> URL {
        var components = URLComponents(string: OneDriveConnector.apiBase.absoluteString + path)!
        if !query.isEmpty { components.queryItems = query }
        return components.url!
    }

    private static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw ConnectorError.serverError(provider: MediaSourceKind.oneDrive.displayName, status: 200)
        }
    }

    private struct Page: Decodable {
        let value: [Item]
        let nextLink: String?
        enum CodingKeys: String, CodingKey {
            case value
            case nextLink = "@odata.nextLink"
        }
        struct Item: Decodable {
            let name: String
            let size: Int64?
            let eTag: String?
            let lastModifiedDateTime: String?
            let file: Facet?
            struct Facet: Decodable {}
        }
    }
}

// MARK: - Dropbox

actor DropboxSyncStorage: SyncStorage {
    nonisolated let providerName = "dropbox"
    static let contentBase = URL(string: "https://content.dropboxapi.com/2/")!

    private let http: ProviderHTTP
    private let space: String

    init(account: CloudAccount, space: String, tokens: CloudTokenProvider = .shared, session: URLSession = .shared) {
        http = ProviderHTTP(kind: .dropbox, accountKey: account.key, tokens: tokens, session: session)
        self.space = space
    }

    private var folderPath: String { "/\(SyncFileFormat.rootFolderName)/\(space)" }

    func listSpace() async throws -> [SyncFileEntry] {
        var request = try ProviderHTTP.jsonRequest(
            DropboxConnector.apiBase.appendingPathComponent("files/list_folder"),
            body: ["path": folderPath, "recursive": false, "include_deleted": false, "limit": 2000]
        )
        var (data, response) = try await http.send(allowing: [409]) { _ in request }
        if response.statusCode == 409 {
            guard Self.summary(of: data).hasPrefix("path/not_found") else {
                throw ConnectorError.serverError(provider: MediaSourceKind.dropbox.displayName, status: 409)
            }
            try await createSpaceFolder()
            return []
        }
        var page = try Self.decode(Listing.self, from: data)
        var entries = page.entries.compactMap(entry(for:))
        while page.hasMore {
            request = try ProviderHTTP.jsonRequest(
                DropboxConnector.apiBase.appendingPathComponent("files/list_folder/continue"),
                body: ["cursor": page.cursor]
            )
            (data, response) = try await http.send { _ in request }
            page = try Self.decode(Listing.self, from: data)
            entries += page.entries.compactMap(entry(for:))
        }
        return entries.sorted { $0.name < $1.name }
    }

    func read(_ name: String) async throws -> Data {
        var request = URLRequest(url: Self.contentBase.appendingPathComponent("files/download"))
        request.httpMethod = "POST"
        request.setValue(try Self.apiArgument(["path": "\(folderPath)/\(name)"]), forHTTPHeaderField: "Dropbox-API-Arg")
        let (data, response) = try await http.send(allowing: [409]) { _ in request }
        if response.statusCode == 409 { throw SyncStorageError.notFound(name) }
        return data
    }

    func write(_ name: String, data: Data) async throws {
        var request = URLRequest(url: Self.contentBase.appendingPathComponent("files/upload"))
        request.httpMethod = "POST"
        request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        request.setValue(try Self.apiArgument([
            "path": "\(folderPath)/\(name)", "mode": "overwrite", "autorename": false, "mute": true
        ]), forHTTPHeaderField: "Dropbox-API-Arg")
        request.httpBody = data
        _ = try await http.send { _ in request }
    }

    func delete(_ name: String) async throws {
        let request = try ProviderHTTP.jsonRequest(
            DropboxConnector.apiBase.appendingPathComponent("files/delete_v2"),
            body: ["path": "\(folderPath)/\(name)"]
        )
        _ = try await http.send(allowing: [409]) { _ in request }
    }

    private func createSpaceFolder() async throws {
        let request = try ProviderHTTP.jsonRequest(
            DropboxConnector.apiBase.appendingPathComponent("files/create_folder_v2"),
            body: ["path": folderPath, "autorename": false]
        )
        // 409 `path/conflict/folder`: another device made it first.
        _ = try await http.send(allowing: [409]) { _ in request }
    }

    private func entry(for metadata: Listing.Entry) -> SyncFileEntry? {
        guard metadata.tag == "file", let rev = metadata.rev,
              SyncFileFormat.deviceID(ofFileName: metadata.name) != nil
        else { return nil }
        return SyncFileEntry(name: metadata.name, stamp: rev, size: metadata.size)
    }

    /// `Dropbox-API-Arg` must be ASCII: non-ASCII characters are escaped.
    static func apiArgument(_ object: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        var text = ""
        for scalar in String(decoding: data, as: UTF8.self).unicodeScalars {
            if scalar.isASCII {
                text.unicodeScalars.append(scalar)
            } else {
                for unit in String(scalar).utf16 { text += String(format: "\\u%04x", unit) }
            }
        }
        return text
    }

    private static func summary(of data: Data) -> String {
        struct Body: Decodable { let errorSummary: String? }
        return (try? OAuthClient.decoder.decode(Body.self, from: data))?.errorSummary ?? ""
    }

    private static func decode<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw ConnectorError.serverError(provider: MediaSourceKind.dropbox.displayName, status: 200)
        }
    }

    private struct Listing: Decodable {
        let entries: [Entry]
        let cursor: String
        let hasMore: Bool
        enum CodingKeys: String, CodingKey {
            case entries, cursor
            case hasMore = "has_more"
        }
        struct Entry: Decodable {
            let tag: String
            let name: String
            let rev: String?
            let size: Int64?
            enum CodingKeys: String, CodingKey {
                case tag = ".tag"
                case name, rev, size
            }
        }
    }
}
