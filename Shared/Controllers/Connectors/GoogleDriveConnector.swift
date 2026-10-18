//
//  GoogleDriveConnector.swift
//  Edendale
//
//  Google Drive through the Drive v3 REST API with the `drive.file` scope:
//  Edendale sees only the files the user picked in Google's picker, which
//  opens during sign-in and from Choose More Videos (ENHANCEMENT.md J.14).
//  So there is nothing to browse. Each account has one virtual folder,
//  "Videos you chose", listing every file picked so far on any device or
//  platform, because Google grants picks per account, not per token. Item
//  URLs are `gdrive://<account>/<fileId>/<Name.ext>`.
//
//  Folders are skipped (a picked folder never grants what's inside it),
//  shortcuts are followed to their targets, other Google formats (Docs,
//  Sheets) are skipped, and videos are recognized by file extension rather
//  than MIME type. Files stream from `files/<id>?alt=media` with a Bearer
//  token and `Range`. `acknowledgeAbuse` is never sent: a file Google flags
//  as abusive fails with a clear message instead.
//

import Foundation

nonisolated struct GoogleDriveConnector: MediaConnector {
    static let apiBase = URL(string: "https://www.googleapis.com/drive/v3/")!
    static let shortcutMimeType = "application/vnd.google-apps.shortcut"

    /// The ID of the "Videos you chose" folder; real Drive IDs never start
    /// with `~`. The name in its URL is fixed, so a source's stored URL is
    /// the same in every language.
    static let chosenFolderID = "~chosen"
    static let chosenFolderURLName = "Google Drive"

    let kind: MediaSourceKind = .googleDrive
    let account: CloudAccount
    var http: ProviderHTTP

    init(account: CloudAccount, tokens: CloudTokenProvider = .shared, session: URLSession = .shared) {
        self.account = account
        self.http = ProviderHTTP(kind: .googleDrive, accountKey: account.key, tokens: tokens, session: session)
    }

    var root: URL {
        Self.chosenFolderURL(accountKey: account.key)
    }

    /// The account's Google Drive source URL.
    static func chosenFolderURL(accountKey: String) -> URL {
        SourceURL.accountItem(kind: .googleDrive, account: accountKey, ids: [chosenFolderID], name: chosenFolderURLName)
    }

    /// Whether `url` is a Google Drive source from this build: older builds
    /// linked Drive folders, which `drive.file` can't list.
    static func isChosenFolder(_ url: URL) -> Bool {
        guard let item = SourceURL.parseAccountItem(url), item.kind == .googleDrive else { return false }
        return item.ids == [chosenFolderID]
    }

    /// What the source is called in the library.
    static var chosenFolderName: String { String(localized: "Videos you chose") }

    /// Apple TV never opens Google's picker (R10).
    static var chooseOnAnotherDeviceNote: String {
        String(localized: "Choose videos for this source in Edendale on an iPhone, iPad, or Mac, then rescan here.")
    }

    var accountLabel: String? { account.label }

    func validate() async throws {
        struct About: Decodable { let user: User?; struct User: Decodable { let emailAddress: String? } }
        _ = try await http.json(About.self) { _ in
            URLRequest(url: Self.url("about", query: [URLQueryItem(name: "fields", value: "user(emailAddress)")]))
        }
    }

    /// Only the "Videos you chose" folder lists: it holds every file the
    /// user picked, and no folder's contents are ever granted.
    func list(directory: URL) async throws -> [ConnectorEntry] {
        guard let item = SourceURL.parseAccountItem(directory), item.kind == .googleDrive,
              item.ids == [Self.chosenFolderID]
        else { throw ConnectorError.invalidAddress }
        return try await listChosenFiles()
    }

    // MARK: - Listing

    struct FileList: Decodable {
        let nextPageToken: String?
        let files: [File]
    }

    struct File: Decodable {
        let id: String
        let name: String
        let mimeType: String?
        /// Drive encodes 64-bit numbers as strings.
        let size: String?
        let modifiedTime: Date?
        let videoMediaMetadata: VideoMetadata?
        let shortcutDetails: ShortcutDetails?

        struct VideoMetadata: Decodable {
            /// Missing until Drive finishes processing a video.
            let durationMillis: String?
        }

        struct ShortcutDetails: Decodable {
            let targetId: String?
            let targetMimeType: String?
        }
    }

    /// Every file the user picked, across pages. `drive.file` limits
    /// `files.list` to those, in My Drive, shared with them, or in shared
    /// drives alike.
    private func listChosenFiles() async throws -> [ConnectorEntry] {
        var entries: [ConnectorEntry] = []
        var pageToken: String?
        repeat {
            var items = [
                URLQueryItem(name: "q", value: "trashed = false"),
                URLQueryItem(name: "fields", value: "nextPageToken,files(id,name,mimeType,size,modifiedTime,videoMediaMetadata(durationMillis),shortcutDetails(targetId,targetMimeType))"),
                URLQueryItem(name: "pageSize", value: "1000"),
                URLQueryItem(name: "supportsAllDrives", value: "true"),
                URLQueryItem(name: "includeItemsFromAllDrives", value: "true")
            ]
            if let pageToken {
                items.append(URLQueryItem(name: "pageToken", value: pageToken))
            }
            let request = URLRequest(url: Self.url("files", query: items))
            let page = try await http.json(FileList.self) { _ in request }
            entries += page.files.compactMap(entry(for:))
            pageToken = page.nextPageToken
        } while pageToken != nil
        return ConnectorWalk.sorted(entries)
    }

    /// Maps a picked Drive file to an entry. Folders, folder shortcuts, and
    /// other Google formats are skipped.
    func entry(for file: File) -> ConnectorEntry? {
        var id = file.id
        var mimeType = file.mimeType ?? ""
        if mimeType == Self.shortcutMimeType {
            guard let target = file.shortcutDetails?.targetId else { return nil }
            id = target
            mimeType = file.shortcutDetails?.targetMimeType ?? ""
        }
        if mimeType.hasPrefix("application/vnd.google-apps.") { return nil }
        return ConnectorEntry(
            name: file.name,
            url: SourceURL.accountItem(kind: .googleDrive, account: account.key, ids: [id], name: file.name),
            isDirectory: false,
            size: file.size.flatMap { Int64($0) },
            duration: file.videoMediaMetadata?.durationMillis.flatMap { Double($0) }.map { $0 / 1000 },
            modified: file.modifiedTime
        )
    }

    // MARK: - URLs

    static func url(_ path: String, query: [URLQueryItem]) -> URL {
        var components = URLComponents(url: apiBase.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        components.queryItems = query
        return components.url!
    }
}

// MARK: - Streaming

/// Streams a Drive file with a Bearer token; a refresh gets a new token.
actor GoogleDriveContentResolver: RemoteContentResolver {
    nonisolated let kind: MediaSourceKind = .googleDrive
    private let fileID: String
    private let accountKey: String
    private let tokens: CloudTokenProvider
    private var lastToken: String?

    init(fileID: String, accountKey: String, tokens: CloudTokenProvider = .shared) {
        self.fileID = fileID
        self.accountKey = accountKey
        self.tokens = tokens
    }

    func contentRequest(refresh: Bool) async throws -> URLRequest {
        let token = try await tokens.accessToken(
            kind: .googleDrive,
            accountKey: accountKey,
            rejecting: refresh ? lastToken : nil
        )
        lastToken = token
        var request = URLRequest(url: GoogleDriveConnector.url(
            "files/\(fileID)",
            query: [
                URLQueryItem(name: "alt", value: "media"),
                URLQueryItem(name: "supportsAllDrives", value: "true")
            ]
        ))
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return request
    }
}
