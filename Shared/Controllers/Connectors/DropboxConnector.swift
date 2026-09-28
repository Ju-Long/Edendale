//
//  DropboxConnector.swift
//  Edendale
//
//  Dropbox through API v2 with the scoped `files.metadata.read`,
//  `files.content.read`, and `account_info.read` permissions. Item URLs are
//  `dropbox://<account>/<fileId>/<Name.ext>` with the percent-encoded `id:…`;
//  the root folder is the virtual ID `root` (the API's empty path).
//
//  Imports list a whole folder in one recursive `list_folder` (plus
//  `continue` pages) instead of walking it. Files stream from
//  `get_temporary_link`, which lasts four hours and then answers 410 Gone;
//  RemoteByteSource then resolves a new link.
//

import Foundation

nonisolated struct DropboxConnector: MediaConnector {
    static let apiBase = URL(string: "https://api.dropboxapi.com/2/")!
    static let rootFolder = "root"

    let kind: MediaSourceKind = .dropbox
    let account: CloudAccount
    var http: ProviderHTTP

    init(account: CloudAccount, tokens: CloudTokenProvider = .shared, session: URLSession = .shared) {
        self.account = account
        self.http = ProviderHTTP(kind: .dropbox, accountKey: account.key, tokens: tokens, session: session)
    }

    var root: URL {
        SourceURL.accountItem(kind: .dropbox, account: account.key, ids: [Self.rootFolder], name: "Dropbox")
    }

    var accountLabel: String? { account.label }

    func list(directory: URL) async throws -> [ConnectorEntry] {
        ConnectorWalk.sorted(try await listFolder(directory, recursive: false).filter { !$0.isHidden })
    }

    func enumerateVideos(under folder: URL) async throws -> [ConnectorEntry] {
        // A recursive listing names every descendant; skip anything inside a
        // hidden folder, as the default walk would.
        try await listFolder(folder, recursive: true).filter { entry in
            entry.isVideo && !entry.isHidden
        }
    }

    // MARK: - API

    struct ListFolderResult: Decodable {
        let entries: [Metadata]
        let cursor: String
        let hasMore: Bool

        enum CodingKeys: String, CodingKey {
            case entries, cursor
            case hasMore = "has_more"
        }
    }

    struct Metadata: Decodable {
        let tag: String
        let name: String
        let id: String?
        let pathLower: String?
        let size: Int64?
        let serverModified: Date?
        let isDownloadable: Bool?

        enum CodingKeys: String, CodingKey {
            case tag = ".tag"
            case name, id, size
            case pathLower = "path_lower"
            case serverModified = "server_modified"
            case isDownloadable = "is_downloadable"
        }
    }

    private func listFolder(_ folder: URL, recursive: Bool) async throws -> [ConnectorEntry] {
        guard let item = SourceURL.parseAccountItem(folder), item.kind == .dropbox,
              let id = item.ids.first
        else { throw ConnectorError.invalidAddress }

        let body: [String: Any] = [
            "path": id == Self.rootFolder ? "" : id,
            "recursive": recursive,
            "include_deleted": false,
            "include_non_downloadable_files": false,
            "limit": 2000
        ]
        var page = try await http.json(ListFolderResult.self) { _ in
            try ProviderHTTP.jsonRequest(Self.apiBase.appendingPathComponent("files/list_folder"), body: body)
        }
        var entries = page.entries.compactMap { entry(for: $0, hiddenAncestors: recursive) }
        while page.hasMore {
            let cursor = page.cursor
            page = try await http.json(ListFolderResult.self) { _ in
                try ProviderHTTP.jsonRequest(
                    Self.apiBase.appendingPathComponent("files/list_folder/continue"),
                    body: ["cursor": cursor]
                )
            }
            entries += page.entries.compactMap { entry(for: $0, hiddenAncestors: recursive) }
        }
        return entries
    }

    /// Files and folders become entries. With `hiddenAncestors`, an item
    /// below a dot-folder is reported hidden by prefixing its name's path.
    func entry(for metadata: Metadata, hiddenAncestors: Bool = false) -> ConnectorEntry? {
        guard let id = metadata.id, metadata.tag == "file" || metadata.tag == "folder" else { return nil }
        if metadata.tag == "file", metadata.isDownloadable == false { return nil }
        if hiddenAncestors, let path = metadata.pathLower,
           path.split(separator: "/").dropLast().contains(where: { $0.hasPrefix(".") }) {
            return nil
        }
        let isFolder = metadata.tag == "folder"
        return ConnectorEntry(
            name: metadata.name,
            url: SourceURL.accountItem(kind: .dropbox, account: account.key, ids: [id], name: metadata.name),
            isDirectory: isFolder,
            size: isFolder ? nil : metadata.size,
            modified: metadata.serverModified
        )
    }
}

// MARK: - Streaming

/// Streams from a temporary link, resolving a new one after it expires.
actor DropboxContentResolver: RemoteContentResolver {
    nonisolated let kind: MediaSourceKind = .dropbox
    nonisolated var usesPreauthorizedLinks: Bool { true }

    private let fileID: String
    private let http: ProviderHTTP
    private var link: URL?

    init(fileID: String, accountKey: String, tokens: CloudTokenProvider = .shared, session: URLSession = .shared) {
        self.fileID = fileID
        self.http = ProviderHTTP(kind: .dropbox, accountKey: accountKey, tokens: tokens, session: session)
    }

    func contentRequest(refresh: Bool) async throws -> URLRequest {
        if !refresh, let link {
            return URLRequest(url: link)
        }
        struct TemporaryLink: Decodable { let link: String }
        let fileID = self.fileID
        let result = try await http.json(TemporaryLink.self) { _ in
            try ProviderHTTP.jsonRequest(
                DropboxConnector.apiBase.appendingPathComponent("files/get_temporary_link"),
                body: ["path": fileID]
            )
        }
        guard let link = URL(string: result.link) else {
            throw ConnectorError.accessDenied(provider: kind.displayName)
        }
        self.link = link
        return URLRequest(url: link)
    }
}
