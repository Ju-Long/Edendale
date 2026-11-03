//
//  SyncStorage.swift
//  Edendale
//
//  Reaching a sync location (SYNC-PROTOCOL.md §4): list the space folder,
//  read another device's file, replace this device's file, delete a file.
//  Every location implements the same five operations, so SyncCoordinator
//  never knows which it has. This file holds the protocol, the folder
//  implementation that also serves the app's iCloud Drive folder, and the
//  factory that turns a SyncLocation into a storage with its login.
//
//  Change detection is a per-file stamp string: a modification time and
//  size for files, an ETag or `rev` for the APIs. A file whose stamp has
//  not changed since it was last read is not read again.
//

import Foundation

/// One `<device-id>.json` in the space folder.
nonisolated struct SyncFileEntry: Equatable, Sendable {
    let name: String
    /// Modification time, ETag, Drive `modifiedTime`, Graph `eTag`, or
    /// Dropbox `rev`, with the size where the listing has it.
    let stamp: String
    let size: Int64?

    var deviceID: String? { SyncFileFormat.deviceID(ofFileName: name) }
}

nonisolated protocol SyncStorage: Sendable {
    /// For logs: `folder`, `googleDrive`, … Never an address.
    var providerName: String { get }
    /// Lists the device files in `Edendale/<space>/`, creating the folders
    /// when missing. Other files there are not listed.
    func listSpace() async throws -> [SyncFileEntry]
    func read(_ name: String) async throws -> Data
    /// Replaces `name` with `data` in one step, creating it when missing.
    func write(_ name: String, data: Data) async throws
    /// Removes `name`; a file already gone is not an error.
    func delete(_ name: String) async throws
}

nonisolated enum SyncStorageError: Error, LocalizedError, Equatable {
    /// The folder's bookmark no longer resolves, or iCloud Drive is off.
    case folderUnavailable
    /// The account or login behind the location is gone.
    case signInRequired(String)
    case notFound(String)
    case failed(String)

    var errorDescription: String? {
        switch self {
        case .folderUnavailable:
            String(localized: "The sync folder can't be opened. Choose it again in Settings → Sync.")
        case .signInRequired(let provider):
            String(localized: "Sign in to \(provider) again to keep syncing.")
        case .notFound(let name):
            String(localized: "\(name) is no longer in the sync folder.")
        case .failed(let what):
            String(localized: "Sync couldn't \(what).")
        }
    }
}

// MARK: - Folders

/// A picked folder (any folder, iCloud Drive and other cloud apps' folders
/// included) or, with `coordinated`, the app's own iCloud Drive folder.
/// Every read, write, and delete is coordinated (NSFileCoordinator), as
/// file providers and iCloud expect; a write replaces the file atomically
/// (written beside it, then renamed over it), so no device ever reads half
/// a file. A file that is only in iCloud is downloaded before it is read.
nonisolated struct FolderSyncStorage: SyncStorage {
    let root: URL
    let space: String
    /// The root came from a security-scoped bookmark.
    let securityScoped: Bool
    /// The root is the app's ubiquity container.
    let coordinated: Bool
    /// A fresh bookmark when the stored one resolved as stale (the folder
    /// moved or was renamed); the coordinator keeps it.
    var refreshedBookmark: Data? = nil

    /// How long a read waits for iCloud to download a file.
    static let downloadWait: TimeInterval = 20

    var providerName: String { coordinated ? "icloudDrive" : "folder" }

    var spaceFolder: URL {
        root.appendingPathComponent(SyncFileFormat.rootFolderName, isDirectory: true)
            .appendingPathComponent(space, isDirectory: true)
    }

    func listSpace() async throws -> [SyncFileEntry] {
        try await scoped {
            let fileManager = FileManager.default
            let folder = spaceFolder
            if !fileManager.fileExists(atPath: folder.path) {
                try Self.coordinate(writing: folder, options: []) { url in
                    try fileManager.createDirectory(at: url, withIntermediateDirectories: true)
                }
            }
            let keys: [URLResourceKey] = [.contentModificationDateKey, .fileSizeKey, .isRegularFileKey]
            let urls = try fileManager.contentsOfDirectory(at: folder, includingPropertiesForKeys: keys, options: [])
            var entries: [String: SyncFileEntry] = [:]
            for url in urls {
                // An iCloud file not yet downloaded may show as `.<name>.icloud`.
                guard let name = Self.syncFileName(forListedName: url.lastPathComponent),
                      let values = try? url.resourceValues(forKeys: Set(keys)),
                      values.isRegularFile != false
                else { continue }
                let size = url.lastPathComponent == name ? values.fileSize.map(Int64.init) : nil
                let modified = values.contentModificationDate?.timeIntervalSince1970 ?? 0
                let entry = SyncFileEntry(name: name, stamp: "\(modified):\(size ?? -1)", size: size)
                // The downloaded file wins over its placeholder.
                if entries[name] == nil || url.lastPathComponent == name { entries[name] = entry }
            }
            return entries.values.sorted { $0.name < $1.name }
        }
    }

    func read(_ name: String) async throws -> Data {
        try await scoped {
            let url = spaceFolder.appendingPathComponent(name)
            try Self.ensureDownloaded(url)
            do {
                return try Self.coordinate(reading: url) { try Data(contentsOf: $0) }
            } catch let error as CocoaError where error.code == .fileReadNoSuchFile {
                throw SyncStorageError.notFound(name)
            }
        }
    }

    func write(_ name: String, data: Data) async throws {
        try await scoped {
            let fileManager = FileManager.default
            let folder = spaceFolder
            if !fileManager.fileExists(atPath: folder.path) {
                try Self.coordinate(writing: folder, options: []) { url in
                    try fileManager.createDirectory(at: url, withIntermediateDirectories: true)
                }
            }
            try Self.coordinate(writing: folder.appendingPathComponent(name), options: .forReplacing) { url in
                try data.write(to: url, options: .atomic)
            }
        }
    }

    func delete(_ name: String) async throws {
        try await scoped {
            let url = spaceFolder.appendingPathComponent(name)
            guard FileManager.default.fileExists(atPath: url.path) else { return }
            try Self.coordinate(writing: url, options: .forDeleting) { try FileManager.default.removeItem(at: $0) }
        }
    }

    /// `<uuid>.json`, also from an iCloud placeholder's `.<uuid>.json.icloud`;
    /// nil for anything else.
    static func syncFileName(forListedName listed: String) -> String? {
        var name = listed
        if name.hasPrefix("."), name.hasSuffix(".icloud") {
            name = String(name.dropFirst().dropLast(".icloud".count))
        }
        guard !name.hasPrefix("."), SyncFileFormat.deviceID(ofFileName: name) != nil else { return nil }
        return name
    }

    /// Runs file work off the main actor, inside the folder's security
    /// scope when it has one.
    private func scoped<T: Sendable>(_ work: @escaping @Sendable () throws -> T) async throws -> T {
        let root = self.root
        let securityScoped = self.securityScoped
        return try await Blocking.run {
            let accessed = securityScoped && root.startAccessingSecurityScopedResource()
            defer { if accessed { root.stopAccessingSecurityScopedResource() } }
            return try work()
        }
    }

    private static func coordinate<T>(reading url: URL, _ body: (URL) throws -> T) throws -> T {
        var coordinationError: NSError?
        var result: Result<T, Error>?
        NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { readURL in
            result = Result { try body(readURL) }
        }
        if let coordinationError { throw coordinationError }
        guard let result else { throw SyncStorageError.failed(String(localized: "read the sync folder")) }
        return try result.get()
    }

    private static func coordinate(
        writing url: URL,
        options: NSFileCoordinator.WritingOptions,
        _ body: (URL) throws -> Void
    ) throws {
        var coordinationError: NSError?
        var result: Result<Void, Error>?
        NSFileCoordinator().coordinate(writingItemAt: url, options: options, error: &coordinationError) { writeURL in
            result = Result { try body(writeURL) }
        }
        if let coordinationError { throw coordinationError }
        try result?.get()
    }

    /// Asks iCloud for a file that is only in the cloud (a dataless file,
    /// or only its `.icloud` placeholder) and waits, briefly, for it to
    /// arrive. One still missing is read at a later sync.
    private static func ensureDownloaded(_ url: URL) throws {
        let fileManager = FileManager.default
        let placeholder = url.deletingLastPathComponent().appendingPathComponent(".\(url.lastPathComponent).icloud")
        let hasPlaceholder = fileManager.fileExists(atPath: placeholder.path)
        let values = try? url.resourceValues(forKeys: [.isUbiquitousItemKey, .ubiquitousItemDownloadingStatusKey])
        guard hasPlaceholder || values?.isUbiquitousItem == true else { return }
        if values?.ubiquitousItemDownloadingStatus == .current { return }
        try? fileManager.startDownloadingUbiquitousItem(at: url)
        let deadline = Date().addingTimeInterval(downloadWait)
        while Date() < deadline {
            Thread.sleep(forTimeInterval: 0.25)
            guard fileManager.fileExists(atPath: url.path) else { continue }
            let status = try? url.resourceValues(forKeys: [.ubiquitousItemDownloadingStatusKey]).ubiquitousItemDownloadingStatus
            if status == nil || status == .current || status == .downloaded { return }
        }
        throw SyncStorageError.failed(String(localized: "download a file from iCloud Drive in time"))
    }
}

// MARK: - Factory

nonisolated enum SyncStorageFactory {

    /// The storage for a location, with its login from the Keychain. Runs
    /// off the main actor: resolving a bookmark or the ubiquity container
    /// can block.
    static func make(
        for location: SyncLocation,
        environment: ConnectorFactory.Environment = .live
    ) async throws -> any SyncStorage {
        switch location.provider {
        case .folder:
            guard let bookmark = location.folderBookmark else { throw SyncStorageError.folderUnavailable }
            let (root, refreshed) = try await Blocking.run { () -> (URL, Data?) in
                var stale = false
                guard let url = try? URL(
                    resolvingBookmarkData: bookmark, options: .securityScoped, relativeTo: nil, bookmarkDataIsStale: &stale
                ) else { throw SyncStorageError.folderUnavailable }
                guard stale else { return (url, nil) }
                let accessed = url.startAccessingSecurityScopedResource()
                defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                return (url, try? url.bookmarkData(options: .securityScoped, includingResourceValuesForKeys: nil, relativeTo: nil))
            }
            return FolderSyncStorage(
                root: root, space: location.space, securityScoped: true, coordinated: false, refreshedBookmark: refreshed
            )

        case .iCloudDrive:
            let root = try await Blocking.run { () -> URL in
                guard let container = FileManager.default.url(forUbiquityContainerIdentifier: nil) else {
                    throw SyncStorageError.folderUnavailable
                }
                return container.appendingPathComponent("Documents", isDirectory: true)
            }
            return FolderSyncStorage(root: root, space: location.space, securityScoped: false, coordinated: true)

        case .googleDrive, .oneDrive, .dropbox:
            guard let kind = location.provider.mediaSourceKind, let key = location.accountKey,
                  let account = environment.accounts.account(kind: kind, key: key)
            else { throw SyncStorageError.signInRequired(location.provider.displayName) }
            switch location.provider {
            case .googleDrive:
                return GoogleDriveSyncStorage(
                    account: account, space: location.space, folderID: location.googleFolderID,
                    tokens: environment.tokens, session: environment.session
                )
            case .oneDrive:
                return OneDriveSyncStorage(
                    account: account, space: location.space, tokens: environment.tokens, session: environment.session
                )
            default:
                return DropboxSyncStorage(
                    account: account, space: location.space, tokens: environment.tokens, session: environment.session
                )
            }

        case .webdav:
            guard let url = location.serverURL, let host = url.host() else { throw SyncStorageError.folderUnavailable }
            let credential = NetworkCredentialStore.credential(kind: .webdav, host: host, store: environment.secrets)
            return WebDAVSyncStorage(root: url, space: location.space, credential: credential)

        case .smb:
            guard let url = location.serverURL, url.host() != nil else { throw SyncStorageError.folderUnavailable }
            return try SMBSyncStorage(sourceURL: url, space: location.space, store: environment.secrets)

        case .sftp:
            guard let url = location.serverURL, let host = url.host() else { throw SyncStorageError.folderUnavailable }
            guard let credential = NetworkCredentialStore.credential(kind: .sftp, host: host, store: environment.secrets) else {
                throw SyncStorageError.signInRequired(host)
            }
            let port = url.port ?? SFTPConnector.defaultPort
            return SFTPSyncStorage(
                host: host, port: port, credential: credential,
                basePath: SFTPConnector.path(of: url), space: location.space,
                pinnedFingerprint: HostKeyStore.pinnedFingerprint(host: host, port: port, store: environment.secrets)
            )
        }
    }
}
