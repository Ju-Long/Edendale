//
//  GoogleDriveConnector.swift
//  Edendale
//
//  Google Drive through the Drive v3 REST API with the `drive.readonly`
//  scope. Item URLs are `gdrive://<account>/<fileId>/<Name.ext>`; folders use
//  the folder's ID the same way, plus `?drive=<driveId>` inside a shared
//  drive, whose listings must name it. The picker's root holds My Drive,
//  Shared with me, and Shared drives.
//
//  Shortcuts are followed to their targets, other Google formats (Docs,
//  Sheets) are skipped, and videos are recognized by file extension rather
//  than MIME type. Files stream from `files/<id>?alt=media` with a Bearer
//  token and `Range`. `acknowledgeAbuse` is never sent: a file Google flags
//  as abusive fails with a clear message instead.
//

import Foundation

nonisolated struct GoogleDriveConnector: MediaConnector {
    static let apiBase = URL(string: "https://www.googleapis.com/drive/v3/")!
    static let folderMimeType = "application/vnd.google-apps.folder"
    static let shortcutMimeType = "application/vnd.google-apps.shortcut"

    /// IDs of the picker's virtual folders; real Drive IDs never start with `~`.
    enum VirtualFolder {
        static let roots = "~roots"
        static let sharedWithMe = "~shared"
        static let sharedDrives = "~drives"
        static let myDrive = "root"
    }

    let kind: MediaSourceKind = .googleDrive
    let account: CloudAccount
    var http: ProviderHTTP

    init(account: CloudAccount, tokens: CloudTokenProvider = .shared, session: URLSession = .shared) {
        self.account = account
        self.http = ProviderHTTP(kind: .googleDrive, accountKey: account.key, tokens: tokens, session: session)
    }

    var root: URL {
        folderURL(id: VirtualFolder.roots, name: "Google Drive")
    }

    var accountLabel: String? { account.label }

    /// The picker's root and the list of shared drives only gather other
    /// folders; linking either would scan all of Drive.
    func canIndex(_ directory: URL) -> Bool {
        guard let id = SourceURL.parseAccountItem(directory)?.ids.first else { return false }
        return id != VirtualFolder.roots && id != VirtualFolder.sharedDrives
    }

    func validate() async throws {
        struct About: Decodable { let user: User?; struct User: Decodable { let emailAddress: String? } }
        _ = try await http.json(About.self) { _ in
            URLRequest(url: Self.url("about", query: [URLQueryItem(name: "fields", value: "user(emailAddress)")]))
        }
    }

    func list(directory: URL) async throws -> [ConnectorEntry] {
        guard let item = SourceURL.parseAccountItem(directory), item.kind == .googleDrive,
              let id = item.ids.first
        else { throw ConnectorError.invalidAddress }

        switch id {
        case VirtualFolder.roots:
            return [
                ConnectorEntry(name: String(localized: "My Drive"), url: folderURL(id: VirtualFolder.myDrive, name: String(localized: "My Drive")), isDirectory: true),
                ConnectorEntry(name: String(localized: "Shared with me"), url: folderURL(id: VirtualFolder.sharedWithMe, name: String(localized: "Shared with me")), isDirectory: true),
                ConnectorEntry(name: String(localized: "Shared drives"), url: folderURL(id: VirtualFolder.sharedDrives, name: String(localized: "Shared drives")), isDirectory: true)
            ]
        case VirtualFolder.sharedDrives:
            return try await listSharedDrives()
        case VirtualFolder.sharedWithMe:
            return try await listFiles(query: "sharedWithMe = true and trashed = false", driveID: nil)
        default:
            let driveID = item.queryValue("drive")
            return try await listFiles(query: "'\(id)' in parents and trashed = false", driveID: driveID)
        }
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

    private func listFiles(query: String, driveID: String?) async throws -> [ConnectorEntry] {
        var entries: [ConnectorEntry] = []
        var pageToken: String?
        repeat {
            var items = [
                URLQueryItem(name: "q", value: query),
                URLQueryItem(name: "fields", value: "nextPageToken,files(id,name,mimeType,size,modifiedTime,videoMediaMetadata(durationMillis),shortcutDetails(targetId,targetMimeType))"),
                URLQueryItem(name: "pageSize", value: "1000"),
                URLQueryItem(name: "supportsAllDrives", value: "true"),
                URLQueryItem(name: "includeItemsFromAllDrives", value: "true")
            ]
            if let driveID {
                items.append(URLQueryItem(name: "corpora", value: "drive"))
                items.append(URLQueryItem(name: "driveId", value: driveID))
            }
            if let pageToken {
                items.append(URLQueryItem(name: "pageToken", value: pageToken))
            }
            let request = URLRequest(url: Self.url("files", query: items))
            let page = try await http.json(FileList.self) { _ in request }
            entries += page.files.compactMap { entry(for: $0, driveID: driveID) }
            pageToken = page.nextPageToken
        } while pageToken != nil
        return ConnectorWalk.sorted(entries)
    }

    private func listSharedDrives() async throws -> [ConnectorEntry] {
        struct DriveList: Decodable {
            let nextPageToken: String?
            let drives: [Drive]
            struct Drive: Decodable { let id: String; let name: String }
        }
        var entries: [ConnectorEntry] = []
        var pageToken: String?
        repeat {
            var items = [
                URLQueryItem(name: "pageSize", value: "100"),
                URLQueryItem(name: "fields", value: "nextPageToken,drives(id,name)")
            ]
            if let pageToken {
                items.append(URLQueryItem(name: "pageToken", value: pageToken))
            }
            let request = URLRequest(url: Self.url("drives", query: items))
            let page = try await http.json(DriveList.self) { _ in request }
            entries += page.drives.map { drive in
                ConnectorEntry(
                    name: drive.name,
                    // A shared drive's root folder has the drive's ID.
                    url: folderURL(id: drive.id, name: drive.name, driveID: drive.id),
                    isDirectory: true
                )
            }
            pageToken = page.nextPageToken
        } while pageToken != nil
        return ConnectorWalk.sorted(entries)
    }

    /// Maps a Drive file to an entry: folders and folder shortcuts are
    /// directories; other Google formats are skipped.
    func entry(for file: File, driveID: String?) -> ConnectorEntry? {
        var id = file.id
        var mimeType = file.mimeType ?? ""
        if mimeType == Self.shortcutMimeType {
            guard let target = file.shortcutDetails?.targetId else { return nil }
            id = target
            mimeType = file.shortcutDetails?.targetMimeType ?? ""
        }
        if mimeType == Self.folderMimeType {
            return ConnectorEntry(
                name: file.name,
                url: folderURL(id: id, name: file.name, driveID: driveID),
                isDirectory: true,
                modified: file.modifiedTime
            )
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

    func folderURL(id: String, name: String, driveID: String? = nil) -> URL {
        SourceURL.accountItem(
            kind: .googleDrive,
            account: account.key,
            ids: [id],
            name: name,
            query: driveID.map { [URLQueryItem(name: "drive", value: $0)] } ?? []
        )
    }

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
