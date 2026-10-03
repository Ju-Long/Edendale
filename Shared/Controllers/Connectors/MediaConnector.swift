//
//  MediaConnector.swift
//  Edendale
//
//  The connector abstraction for browsable media sources: SMB, NFS, SFTP,
//  WebDAV, and S3-compatible servers, plus Google Drive, OneDrive, and
//  Dropbox accounts. A connector knows how to reach a file tree and hands
//  back credential-free canonical URLs (see SourceURL). Auth material never
//  appears in anything a connector returns; passwords and tokens live in the
//  Keychain and are applied only to the requests made for listing and
//  playback.
//

import Foundation

// MARK: - Source kind

/// Where a library source's files live. Raw values are persisted on
/// `VideoFolder`, so cases must never be renamed.
nonisolated enum MediaSourceKind: String, CaseIterable, Identifiable, Codable, Sendable {
    /// A user-picked local folder (security-scoped bookmark access).
    case local
    /// An SMB share reached over the local network.
    case smb
    /// An NFS export (AUTH_SYS, no login).
    case nfs
    /// A folder on an SSH server, read over SFTP.
    case sftp
    /// A WebDAV server: Nextcloud, ownCloud, Synology, QNAP, pCloud, Koofr,
    /// or `rclone serve webdav`.
    case webdav
    /// An S3-compatible bucket: AWS, Backblaze B2, Cloudflare R2, Wasabi, MinIO.
    case s3
    case googleDrive = "gdrive"
    case oneDrive = "onedrive"
    case dropbox

    var id: String { rawValue }

    /// The kind an item URL belongs to, from its scheme.
    init?(scheme: String) {
        switch scheme.lowercased() {
        case "file": self = .local
        case "smb", "smb2": self = .smb
        case "nfs": self = .nfs
        case "sftp": self = .sftp
        case "dav", "davs": self = .webdav
        case "s3": self = .s3
        case "gdrive": self = .googleDrive
        case "onedrive": self = .oneDrive
        case "dropbox": self = .dropbox
        default: return nil
        }
    }

    /// The kind of a stored item or source URL; `nil` for unrelated URLs.
    init?(url: URL) {
        guard let scheme = url.scheme else { return nil }
        self.init(scheme: scheme)
    }

    /// URL scheme used for this kind's media URLs; `nil` for local files.
    /// WebDAV also uses `dav` for servers reached over plain HTTP.
    var scheme: String? {
        switch self {
        case .local: nil
        case .smb: "smb"
        case .nfs: "nfs"
        case .sftp: "sftp"
        case .webdav: "davs"
        case .s3: "s3"
        case .googleDrive: "gdrive"
        case .oneDrive: "onedrive"
        case .dropbox: "dropbox"
        }
    }

    var displayName: String {
        switch self {
        case .local: String(localized: "Local Folder")
        case .smb: "SMB"
        case .nfs: "NFS"
        case .sftp: "SFTP"
        case .webdav: "WebDAV"
        case .s3: String(localized: "S3-Compatible Storage")
        case .googleDrive: "Google Drive"
        case .oneDrive: "OneDrive"
        case .dropbox: "Dropbox"
        }
    }

    var isRemote: Bool { self != .local }

    /// Linked through an OAuth account rather than a server login.
    var isCloudAccount: Bool {
        switch self {
        case .googleDrive, .oneDrive, .dropbox: true
        default: false
        }
    }

    /// Reached with a saved server login (`NetworkCredentialStore`).
    var usesServerLogin: Bool {
        switch self {
        case .smb, .sftp, .webdav, .s3: true
        default: false
        }
    }

    /// Streams through `RemoteByteSource` (URLSession) rather than a
    /// file-sharing protocol library.
    var streamsOverHTTP: Bool {
        switch self {
        case .webdav, .s3, .googleDrive, .oneDrive, .dropbox: true
        default: false
        }
    }

    /// Listing is metered API traffic or a slow walk, so the automatic
    /// rescan on every library visit is throttled (see LibraryController).
    var throttlesAutomaticRescans: Bool { isRemote }
}

// MARK: - Credentials

/// A username/password pair for a server source. Stored in the Keychain
/// (see `NetworkCredentialStore`); an empty username means guest. S3 stores
/// the access key ID and secret access key here, with the bucket's location.
nonisolated struct NetworkCredential: Codable, Hashable, Sendable {
    var username: String
    var password: String
    /// S3 sources only.
    var s3: S3Configuration? = nil
    /// SFTP sources only: the server's SSH port. Logins are keyed by host,
    /// so this is how one handed to Apple TV reaches the same server.
    var port: Int? = nil

    var isGuest: Bool { username.isEmpty && password.isEmpty }
}

// MARK: - Directory entries

/// One item in a listed directory: a subfolder to drill into or a file.
/// `url` is canonical and credential-free.
nonisolated struct ConnectorEntry: Identifiable, Hashable, Sendable {
    let name: String
    let url: URL
    let isDirectory: Bool
    /// Bytes, when the listing reports it.
    var size: Int64? = nil
    /// Seconds, when the provider reports it (Drive `videoMediaMetadata`,
    /// the Graph `video` facet); `nil` elsewhere.
    var duration: TimeInterval? = nil
    var modified: Date? = nil

    var id: URL { url }

    /// A file with one of the video extensions the library imports. Extension
    /// filtering, not MIME type: providers disagree on video MIME types.
    var isVideo: Bool {
        !isDirectory && LibraryController.supportedExtensions.contains(
            (name as NSString).pathExtension.lowercased()
        )
    }

    /// Hidden files and folders (`.DS_Store`, `.Trash`, `@eaDir` stays).
    var isHidden: Bool { name.hasPrefix(".") }
}

// MARK: - Connector

/// A connection to a remote file tree that can verify itself and list
/// directories. Implementations are value types capturing the address and
/// how to authenticate, so views can hold and pass them freely.
nonisolated protocol MediaConnector: Sendable {
    var kind: MediaSourceKind { get }
    /// Top of the browsable tree (e.g. `smb://host/`, where shares list,
    /// or a cloud account's roots).
    var root: URL { get }
    /// Username or account email shown with the source; never a secret.
    var accountLabel: String? { get }

    /// Confirms the source is reachable and the login or account works.
    func validate() async throws
    /// Lists one directory (non-recursive), folders first.
    func list(directory: URL) async throws -> [ConnectorEntry]
    /// Every video under `folder`. The default walks `list(directory:)`
    /// breadth-first; providers with recursive listings override it.
    func enumerateVideos(under folder: URL) async throws -> [ConnectorEntry]
    /// Whether `directory` can become a library source. Virtual folders
    /// that only gather others (every shared drive at once) can't.
    func canIndex(_ directory: URL) -> Bool
}

nonisolated extension MediaConnector {
    var accountLabel: String? { nil }

    func validate() async throws {
        _ = try await list(directory: root)
    }

    func enumerateVideos(under folder: URL) async throws -> [ConnectorEntry] {
        try await ConnectorWalk.videos(under: folder, list: list(directory:))
    }

    func canIndex(_ directory: URL) -> Bool { true }
}

/// The breadth-first walk behind the default `enumerateVideos(under:)`.
nonisolated enum ConnectorWalk {
    /// Caps runaway trees: symlink cycles and shortcut loops have no other
    /// guard.
    static let maxDirectories = 2000

    /// A failure listing `folder` itself throws (the source is unreachable);
    /// a failure below it skips just that branch.
    static func videos(
        under folder: URL,
        maxDirectories: Int = maxDirectories,
        list: (URL) async throws -> [ConnectorEntry]
    ) async throws -> [ConnectorEntry] {
        var videos: [ConnectorEntry] = []
        var queue: [URL] = [folder]
        var visited: Set<URL> = [folder]
        var listed = 0

        while !queue.isEmpty, listed < maxDirectories {
            try Task.checkCancellation()
            let directory = queue.removeFirst()
            let entries: [ConnectorEntry]
            if listed == 0 {
                entries = try await list(directory)
            } else {
                do {
                    entries = try await list(directory)
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    entries = []
                }
            }
            listed += 1

            for entry in entries where !entry.isHidden {
                if entry.isDirectory {
                    if visited.insert(entry.url).inserted {
                        queue.append(entry.url)
                    }
                } else if entry.isVideo {
                    videos.append(entry)
                }
            }
        }
        return videos
    }

    /// Folders first, then names in Finder order.
    static func sorted(_ entries: [ConnectorEntry]) -> [ConnectorEntry] {
        entries.sorted {
            if $0.isDirectory != $1.isDirectory { return $0.isDirectory }
            return $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }
}

/// A hashable box around any connector, so navigation values can carry one.
/// Two boxes are equal when they reach the same tree the same way.
nonisolated struct AnyMediaConnector: Hashable, Sendable {
    let base: any MediaConnector

    init(_ base: any MediaConnector) {
        self.base = base
    }

    var kind: MediaSourceKind { base.kind }
    var root: URL { base.root }

    static func == (lhs: AnyMediaConnector, rhs: AnyMediaConnector) -> Bool {
        lhs.kind == rhs.kind
            && lhs.root == rhs.root
            && lhs.base.accountLabel == rhs.base.accountLabel
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(kind)
        hasher.combine(root)
    }
}

// MARK: - Errors

nonisolated enum ConnectorError: Error, LocalizedError, Equatable {
    case invalidAddress
    case unreachable(host: String)
    case listingFailed(path: String)
    /// The account or saved login this source uses is gone or was revoked.
    case signInRequired(provider: String)
    /// This build has no client ID for the provider (see Example.xcconfig).
    case notConfigured(provider: String)
    /// The provider isn't available on this device (e.g. no web sign-in on
    /// Apple TV).
    case unavailableOnThisDevice(provider: String)
    case accessDenied(provider: String)
    case notFound(provider: String)
    case rateLimited(provider: String)
    case serverError(provider: String, status: Int)
    /// Plain HTTP is only allowed to servers on the local network.
    case insecureConnection
    case rangeRequestsUnsupported(provider: String)
    case nfsMountFailed(host: String, reason: String)
    case hostKeyMismatch(host: String)
    case hostKeyUnverified(host: String)
    case authenticationFailed(host: String)
    /// The SSH server offers no key exchange, host key, or cipher Edendale
    /// supports (see SFTPConnection).
    case secureConnectionFailed(host: String)
    /// The SSH server takes only key-based or keyboard-interactive logins.
    case passwordLoginUnavailable(host: String)
    /// The SSH server refused the SFTP subsystem.
    case sftpUnavailable(host: String)
    case abusiveFile
    /// An S3 bucket answered from another region than the one entered.
    case bucketInAnotherRegion(region: String?)

    var errorDescription: String? {
        switch self {
        case .invalidAddress:
            String(localized: "That server address doesn't look right. Enter a hostname like nas.local or an IP address.")
        case .unreachable(let host):
            String(localized: "Can't reach \(host). Check that the server is on, on the same network, and that the name or credentials are correct.")
        case .listingFailed(let path):
            String(localized: "Couldn't read the folder \(path). It may need different credentials or permissions.")
        case .signInRequired(let provider):
            String(localized: "Sign in to \(provider) again to reach this source.")
        case .notConfigured(let provider):
            String(localized: "\(provider) isn't set up in this build of Edendale.")
        case .unavailableOnThisDevice(let provider):
            String(localized: "\(provider) can't be linked on this device.")
        case .accessDenied(let provider):
            String(localized: "\(provider) denied access to this item.")
        case .notFound(let provider):
            String(localized: "This file is no longer in \(provider).")
        case .rateLimited(let provider):
            String(localized: "\(provider) is limiting requests right now. Try again in a minute.")
        case .serverError(let provider, let status):
            String(localized: "\(provider) returned an error (HTTP \(status)).")
        case .insecureConnection:
            String(localized: "Use HTTPS for servers outside your local network. Plain HTTP works only for local addresses such as nas.local or 192.168.1.10.")
        case .rangeRequestsUnsupported(let provider):
            String(localized: "\(provider) doesn't support seeking in this file.")
        case .nfsMountFailed(let host, let reason):
            String(localized: "Couldn't mount the NFS export on \(host): \(reason). Apple devices connect from an unprivileged port, so the export needs the “insecure” option.")
        case .hostKeyMismatch(let host):
            String(localized: "The SSH host key for \(host) has changed. Edendale won't connect until you approve the new key by linking the server again.")
        case .hostKeyUnverified(let host):
            String(localized: "The SSH host key for \(host) hasn't been approved yet.")
        case .authenticationFailed(let host):
            String(localized: "\(host) didn't accept the username and password.")
        case .secureConnectionFailed(let host):
            String(localized: "Couldn't set up a secure connection with \(host). Its SSH server offers only older encryption, which Edendale doesn't support.")
        case .passwordLoginUnavailable(let host):
            String(localized: "\(host) doesn't accept password logins. Allow password authentication in its SSH settings to link it.")
        case .sftpUnavailable(let host):
            String(localized: "\(host) doesn't offer SFTP for this account.")
        case .abusiveFile:
            String(localized: "Google Drive flagged this file as potentially harmful, so it can't be streamed.")
        case .bucketInAnotherRegion(let region):
            if let region {
                String(localized: "This bucket is in the \(region) region. Change the region and connect again.")
            } else {
                String(localized: "This bucket is in a different region. Check the region and connect again.")
            }
        }
    }

    /// The source needs the user to sign in or approve something again,
    /// rather than being temporarily unreachable.
    var needsUserAction: Bool {
        switch self {
        case .signInRequired, .hostKeyMismatch, .hostKeyUnverified, .authenticationFailed, .notConfigured,
             .passwordLoginUnavailable:
            true
        default:
            false
        }
    }
}
