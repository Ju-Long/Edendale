//
//  SyncLocation.swift
//  Edendale
//
//  Where this device's sync files live (FEATURES-27.1.md §5.2): a folder
//  picked in Files or Finder, the app's own iCloud Drive folder, a linked
//  Google Drive, OneDrive, or Dropbox account, or a WebDAV, SMB, or SFTP
//  server, plus the space name. One per device, kept in the app group
//  defaults, which never sync. Logins are not here: a cloud account is
//  named by its CloudAccountVault key and a server by its credential-free
//  URL, and both come back out of the Keychain when a sync runs.
//

import Foundation

nonisolated struct SyncLocation: Codable, Equatable, Sendable {

    /// The provider names of the handoff's `offer.location.provider`
    /// (SYNC-PROTOCOL.md §9.6). Every branch uses these raw values.
    enum Provider: String, Codable, CaseIterable, Sendable {
        case folder
        case iCloudDrive = "icloudDrive"
        case googleDrive
        case oneDrive
        case dropbox
        case webdav
        case smb
        case sftp

        var displayName: String {
            switch self {
            case .folder: String(localized: "Folder")
            case .iCloudDrive: String(localized: "iCloud Drive")
            case .googleDrive: "Google Drive"
            case .oneDrive: "OneDrive"
            case .dropbox: "Dropbox"
            case .webdav: "WebDAV"
            case .smb: "SMB"
            case .sftp: "SFTP"
            }
        }

        /// The account or login kind behind an API or server provider.
        var mediaSourceKind: MediaSourceKind? {
            switch self {
            case .folder, .iCloudDrive: nil
            case .googleDrive: .googleDrive
            case .oneDrive: .oneDrive
            case .dropbox: .dropbox
            case .webdav: .webdav
            case .smb: .smb
            case .sftp: .sftp
            }
        }

        init?(mediaSourceKind: MediaSourceKind) {
            switch mediaSourceKind {
            case .googleDrive: self = .googleDrive
            case .oneDrive: self = .oneDrive
            case .dropbox: self = .dropbox
            case .webdav: self = .webdav
            case .smb: self = .smb
            case .sftp: self = .sftp
            default: return nil
            }
        }

        var isCloudAccount: Bool { mediaSourceKind?.isCloudAccount == true }
        var isServer: Bool { mediaSourceKind?.usesServerLogin == true }

        /// Apple TV has no file picker and no iCloud Drive (Apple QA1935).
        var isAvailableOnThisDevice: Bool {
            #if os(tvOS)
            self != .folder && self != .iCloudDrive
            #else
            true
            #endif
        }

        /// The providers offered when choosing a location, in menu order.
        static var offered: [Provider] {
            allCases.filter(\.isAvailableOnThisDevice)
        }
    }

    var provider: Provider
    var space: String
    /// `folder`: the picked folder's security-scoped bookmark, and its
    /// path for display only.
    var folderBookmark: Data?
    var folderPath: String?
    /// Cloud providers: the account's `CloudAccountVault` key.
    var accountKey: String?
    /// Servers: the credential-free canonical URL of the folder holding
    /// `Edendale/` (`davs://host/path/`, `smb://host/share/path/`,
    /// `sftp://host:22/path/`).
    var serverURL: URL?
    /// Google Drive: the space folder's ID once found or created; sent in
    /// the handoff to a device on another ecosystem (§6.4).
    var googleFolderID: String?
    /// When this location was chosen or received.
    var since: Date

    init(
        provider: Provider,
        space: String,
        folderBookmark: Data? = nil,
        folderPath: String? = nil,
        accountKey: String? = nil,
        serverURL: URL? = nil,
        googleFolderID: String? = nil,
        since: Date = Date()
    ) {
        self.provider = provider
        self.space = space
        self.folderBookmark = folderBookmark
        self.folderPath = folderPath
        self.accountKey = accountKey
        self.serverURL = serverURL
        self.googleFolderID = googleFolderID
        self.since = since
    }

    /// The server host a server location points at.
    var serverHost: String? {
        serverURL?.host()
    }

    /// What Settings shows under the provider's name: the folder, the
    /// account's email, or the server. Never a secret.
    func detail(accounts: CloudAccountVault = .shared) -> String? {
        switch provider {
        case .folder:
            return folderPath.map { ($0 as NSString).abbreviatingWithTildeInPath }
        case .iCloudDrive:
            return "iCloud Drive/Edendale"
        case .googleDrive, .oneDrive, .dropbox:
            guard let kind = provider.mediaSourceKind, let accountKey else { return nil }
            return accounts.account(kind: kind, key: accountKey)?.label
        case .webdav, .smb, .sftp:
            return serverURL?.absoluteString
        }
    }

    /// Whether two locations point at the same place, space name aside.
    func sameStorage(as other: SyncLocation) -> Bool {
        guard provider == other.provider else { return false }
        switch provider {
        case .folder: return folderBookmark == other.folderBookmark && folderPath == other.folderPath
        case .iCloudDrive: return true
        case .googleDrive, .oneDrive, .dropbox: return accountKey == other.accountKey
        case .webdav, .smb, .sftp: return serverURL == other.serverURL
        }
    }
}
