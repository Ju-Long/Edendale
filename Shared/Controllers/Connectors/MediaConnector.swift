//
//  MediaConnector.swift
//  Edendale
//
//  The connector abstraction for browsable media sources. A connector knows
//  how to reach a file tree (an SMB share today; NFS/SFTP/FTP later — the
//  bundled libvlc already carries those access modules) and hands back
//  credential-free canonical URLs. Auth material never appears in anything
//  a connector returns; passwords live in the Keychain and are injected
//  only into the ephemeral URLs used for listing and playback.
//

import Foundation

// MARK: - Source kind

/// Where a library source's files live. Raw values are persisted on
/// `VideoFolder`, so cases must never be renamed.
enum MediaSourceKind: String, CaseIterable, Identifiable, Sendable {
    /// A user-picked local folder (security-scoped bookmark access).
    case local
    /// An SMB share reached over the local network.
    case smb

    var id: String { rawValue }

    /// URL scheme used for this kind's media URLs; `nil` for local files.
    var scheme: String? {
        switch self {
        case .local: nil
        case .smb: "smb"
        }
    }

    var displayName: String {
        switch self {
        case .local: String(localized: "Local Folder")
        case .smb: "SMB"
        }
    }
}

// MARK: - Credentials

/// A username/password pair for a network source. Stored in the Keychain
/// keyed by host (see `NetworkCredentialStore`); empty username means guest.
struct NetworkCredential: Codable, Hashable, Sendable {
    var username: String
    var password: String

    var isGuest: Bool { username.isEmpty && password.isEmpty }
}

// MARK: - Directory entries

/// One item in a listed directory: a subfolder to drill into or a file.
/// `url` is canonical and credential-free.
struct ConnectorEntry: Identifiable, Hashable, Sendable {
    let name: String
    let url: URL
    let isDirectory: Bool

    var id: URL { url }
}

// MARK: - Connector

/// A connection to a remote file tree that can verify itself and list
/// directories. Implementations are value types capturing the address and
/// credential, so views can hold and pass them freely.
///
/// Adding a protocol means adding a conformance (address parsing + a root
/// URL); the listing machinery in `VLCNetworkBrowser` is scheme-agnostic.
protocol MediaConnector: Sendable {
    var kind: MediaSourceKind { get }
    /// Top of the browsable tree (e.g. `smb://host/` — shares list here).
    var root: URL { get }
    /// The connector's credential, needed at playback time as well.
    var credential: NetworkCredential? { get }

    /// Confirms the source is reachable and the credential works.
    func validate() async throws
    /// Lists one directory (non-recursive), folders first.
    func list(directory: URL) async throws -> [ConnectorEntry]
}

// MARK: - Errors

enum ConnectorError: Error, LocalizedError {
    case invalidAddress
    case unreachable(host: String)
    case listingFailed(path: String)

    var errorDescription: String? {
        switch self {
        case .invalidAddress:
            String(localized: "That server address doesn't look right. Enter a hostname like nas.local or an IP address.")
        case .unreachable(let host):
            String(localized: "Can't reach \(host). Check that the server is on, on the same network, and that the name or credentials are correct.")
        case .listingFailed(let path):
            String(localized: "Couldn't read the folder \(path). It may need different credentials or permissions.")
        }
    }
}
