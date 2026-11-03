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
/// Writes go to `<name>.tmp` and are renamed over the target; the iCloud
/// folder uses a coordinated atomic replacement instead, so the container
/// never uploads a half-written file.
nonisolated struct FolderSyncStorage: SyncStorage {
    let root: URL
    let space: String
    /// The root came from a security-scoped bookmark.
    let securityScoped: Bool
    /// The root is the ubiquity container: coordinate reads and writes
    /// and download files that are only in the cloud.
    let coordinated: Bool

    /// How long a read waits for iCloud to download a file.
    static let downloadWait: Duration = .seconds(20)

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
                try fileManager.createDirectory(at: folder, withIntermediateDirectories: true)
            }
            let keys: Set<URLResourceKey> = [.contentModificationDateKey, .fileSizeKey, .isRegularFileKey]
            let urls = try fileManager.contentsOfDirectory(
                at: folder, includingPropertiesForKeys: Array(keys), options: [.skipsHiddenFiles]
            )
            return urls.compactMap { url -> SyncFileEntry? in
                let name = url.lastPathComponent
                guard SyncFileFormat.deviceID(ofFileName: name) != nil,
                      let values = try? url.resourceValues(forKeys: keys),
                      values.isRegularFile != false
                else { return nil }
                let size = values.fileSize.map(Int64.init)
                let modified = values.contentModificationDate?.timeIntervalSince1970 ?? 0
                return SyncFileEntry(name: name, stamp: "\(modified):\(size ?? -1)", size: size)
            }
            .sorted { $0.name < $1.name }
        }
    }

    func read(_ name: String) async throws -> Data {
        try await scoped {
            let url = spaceFolder.appendingPathComponent(name)
            if coordinated {
                try Self.ensureDownloaded(url)
                var coordinationError: NSError?
                var result: Result<Data, Error> = .failure(SyncStorageError.notFound(name))
                NSFileCoordinator().coordinate(readingItemAt: url, options: [], error: &coordinationError) { readURL in
                    result = Result { try Data(contentsOf: readURL) }
                }
                if let coordinationError { throw coordinationError }
                return try result.get()
            }
            do {
                return try Data(contentsOf: url)
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
                try fileManager.createDirectory(at: folder, withIntermediateDirectories: true)
            }
            let target = folder.appendingPathComponent(name)
            if coordinated {
                var coordinationError: NSError?
                var result: Result<Void, Error> = .success(())
                NSFileCoordinator().coordinate(
                    writingItemAt: target, options: .forReplacing, error: &coordinationError
                ) { writeURL in
                    result = Result { try data.write(to: writeURL, options: .atomic) }
                }
                if let coordinationError { throw coordinationError }
                try result.get()
                return
            }
            let temporary = folder.appendingPathComponent(name + ".tmp")
            try data.write(to: temporary)
            if fileManager.fileExists(atPath: target.path) {
                _ = try fileManager.replaceItemAt(target, withItemAt: temporary)
            } else {
                try fileManager.moveItem(at: temporary, to: target)
            }
        }
    }

    func delete(_ name: String) async throws {
        try await scoped {
            let url = spaceFolder.appendingPathComponent(name)
            guard FileManager.default.fileExists(atPath: url.path) else { return }
            if coordinated {
                var coordinationError: NSError?
                var result: Result<Void, Error> = .success(())
                NSFileCoordinator().coordinate(writingItemAt: url, options: .forDeleting, error: &coordinationError) { deleteURL in
                    result = Result { try FileManager.default.removeItem(at: deleteURL) }
                }
                if let coordinationError { throw coordinationError }
                try result.get()
            } else {
                try FileManager.default.removeItem(at: url)
            }
        }
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

    /// Asks iCloud for a file that is only in the cloud and waits, briefly,
    /// for it to arrive. A file still missing is read on a later sync.
    private static func ensureDownloaded(_ url: URL) throws {
        let keys: Set<URLResourceKey> = [.ubiquitousItemDownloadingStatusKey, .isUbiquitousItemKey]
        guard let values = try? url.resourceValues(forKeys: keys), values.isUbiquitousItem == true else { return }
        if values.ubiquitousItemDownloadingStatus == .current { return }
        try FileManager.default.startDownloadingUbiquitousItem(at: url)
        let deadline = Date().addingTimeInterval(TimeInterval(downloadWait.components.seconds))
        while Date() < deadline {
            Thread.sleep(forTimeInterval: 0.25)
            let status = try? url.resourceValues(forKeys: [.ubiquitousItemDownloadingStatusKey]).ubiquitousItemDownloadingStatus
            if status == .current || status == .downloaded { return }
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
            let root = try await Blocking.run {
                var stale = false
                guard let url = try? URL(
                    resolvingBookmarkData: bookmark, options: .securityScoped, relativeTo: nil, bookmarkDataIsStale: &stale
                ) else { throw SyncStorageError.folderUnavailable }
                return url
            }
            return FolderSyncStorage(root: root, space: location.space, securityScoped: true, coordinated: false)

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
