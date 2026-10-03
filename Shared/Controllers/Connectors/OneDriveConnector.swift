//
//  OneDriveConnector.swift
//  Edendale
//
//  OneDrive (personal, work, and school) through Microsoft Graph with the
//  least-privileged delegated `Files.Read` permission. Item URLs are
//  `onedrive://<account>/<driveId>/<itemId>/<Name.ext>`; the picker's root is
//  the user's own drive (`root`). Folders shared into it (`remoteItem`) are
//  followed into the drive they live in.
//
//  Files stream from their `@microsoft.graph.downloadUrl`: a pre-authenticated
//  link that needs no Authorization header, "might expire within minutes",
//  and takes `Range` itself. It may also ignore `Range` and answer 200,
//  which RemoteByteSource accepts at offset 0.
//

import Foundation

nonisolated struct OneDriveConnector: MediaConnector {
    static let apiBase = URL(string: "https://graph.microsoft.com/v1.0/")!
    static let rootItem = "root"

    let kind: MediaSourceKind = .oneDrive
    let account: CloudAccount
    let driveID: String
    var http: ProviderHTTP

    /// `nil` when the account predates knowing its drive (sign in again).
    init?(account: CloudAccount, tokens: CloudTokenProvider = .shared, session: URLSession = .shared) {
        guard let driveID = account.driveID, !driveID.isEmpty else { return nil }
        self.account = account
        self.driveID = driveID
        self.http = ProviderHTTP(kind: .oneDrive, accountKey: account.key, tokens: tokens, session: session)
    }

    var root: URL {
        SourceURL.accountItem(kind: .oneDrive, account: account.key, ids: [driveID, Self.rootItem], name: "OneDrive")
    }

    var accountLabel: String? { account.label }

    func list(directory: URL) async throws -> [ConnectorEntry] {
        guard let item = SourceURL.parseAccountItem(directory), item.kind == .oneDrive,
              item.ids.count == 2
        else { throw ConnectorError.invalidAddress }

        var entries: [ConnectorEntry] = []
        var next: URL? = Self.childrenURL(driveID: item.ids[0], itemID: item.ids[1])
        while let url = next {
            let request = URLRequest(url: url)
            let page = try await http.json(ChildrenPage.self) { _ in request }
            entries += page.value.compactMap(entry(for:))
            next = page.nextLink.flatMap(URL.init(string:))
        }
        return ConnectorWalk.sorted(entries)
    }

    // MARK: - Graph

    struct ChildrenPage: Decodable {
        let value: [Item]
        let nextLink: String?

        enum CodingKeys: String, CodingKey {
            case value
            case nextLink = "@odata.nextLink"
        }
    }

    struct Item: Decodable {
        let id: String
        let name: String
        let size: Int64?
        let folder: Facet?
        let file: Facet?
        let package: Facet?
        let video: Video?
        let lastModifiedDateTime: Date?
        let parentReference: ParentReference?
        let remoteItem: RemoteItem?

        struct Facet: Decodable {}
        struct Video: Decodable {
            /// Milliseconds.
            let duration: Double?
        }
        struct ParentReference: Decodable { let driveId: String? }
        struct RemoteItem: Decodable {
            let id: String
            let size: Int64?
            let folder: Facet?
            let file: Facet?
            let video: Video?
            let parentReference: ParentReference?
        }
    }

    /// Folders and files become entries; OneNote packages are skipped.
    func entry(for item: Item) -> ConnectorEntry? {
        if item.package != nil { return nil }
        var driveID = item.parentReference?.driveId ?? self.driveID
        var id = item.id
        var isFolder = item.folder != nil
        var isFile = item.file != nil
        var size = item.size
        var video = item.video
        if let remote = item.remoteItem {
            // A folder shared into this drive lives in another one.
            driveID = remote.parentReference?.driveId ?? driveID
            id = remote.id
            isFolder = remote.folder != nil
            isFile = remote.file != nil
            size = remote.size ?? size
            video = remote.video ?? video
        }
        guard isFolder || isFile else { return nil }
        return ConnectorEntry(
            name: item.name,
            url: SourceURL.accountItem(kind: .oneDrive, account: account.key, ids: [driveID, id], name: item.name),
            isDirectory: isFolder,
            size: isFolder ? nil : size,
            duration: video?.duration.map { $0 / 1000 },
            modified: item.lastModifiedDateTime
        )
    }

    static func childrenURL(driveID: String, itemID: String) -> URL {
        let path = itemID == rootItem
            ? "drives/\(driveID)/root/children"
            : "drives/\(driveID)/items/\(itemID)/children"
        var components = URLComponents(url: apiBase.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        components.queryItems = [
            URLQueryItem(name: "$select", value: "id,name,size,folder,file,package,video,lastModifiedDateTime,parentReference,remoteItem"),
            URLQueryItem(name: "$top", value: "200")
        ]
        return components.url!
    }

    static func itemURL(driveID: String, itemID: String) -> URL {
        apiBase.appendingPathComponent("drives/\(driveID)/items/\(itemID)")
    }
}

// MARK: - Streaming

/// Streams from the item's pre-authenticated download URL, fetching a new
/// one when it expires.
actor OneDriveContentResolver: RemoteContentResolver {
    nonisolated let kind: MediaSourceKind = .oneDrive
    nonisolated var usesPreauthorizedLinks: Bool { true }

    private let driveID: String
    private let itemID: String
    private let http: ProviderHTTP
    private var link: URL?

    init(
        driveID: String,
        itemID: String,
        accountKey: String,
        tokens: CloudTokenProvider = .shared,
        session: URLSession = .shared
    ) {
        self.driveID = driveID
        self.itemID = itemID
        self.http = ProviderHTTP(kind: .oneDrive, accountKey: accountKey, tokens: tokens, session: session)
    }

    func contentRequest(refresh: Bool) async throws -> URLRequest {
        if !refresh, let link {
            return URLRequest(url: link)
        }
        struct DownloadItem: Decodable {
            let downloadURL: String?
            enum CodingKeys: String, CodingKey {
                case downloadURL = "@microsoft.graph.downloadUrl"
            }
        }
        let url = OneDriveConnector.itemURL(driveID: driveID, itemID: itemID)
        let item = try await http.json(DownloadItem.self) { _ in URLRequest(url: url) }
        guard let string = item.downloadURL, let link = URL(string: string) else {
            throw ConnectorError.accessDenied(provider: kind.displayName)
        }
        self.link = link
        return URLRequest(url: link)
    }
}
