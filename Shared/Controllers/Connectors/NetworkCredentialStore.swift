//
//  NetworkCredentialStore.swift
//  Edendale
//
//  Keychain persistence for server logins, one per host and protocol.
//  Items ride iCloud Keychain (KeychainStore marks them synchronizable),
//  so a server added on one device offers its saved login on the others —
//  the library index itself stays local by design. Apple TV never receives
//  synced items; logins reach it by typing them or through AccountHandoff.
//
//  Removing a source never deletes its login: deleting a synchronizable
//  item deletes every synced copy, which would sign the user's other devices
//  out of that server. Logins are forgotten explicitly in Settings → Accounts.
//

import Foundation

/// Where an S3-compatible bucket lives. Not secret, but stored with the key
/// pair so an item URL (whose host is the account key) finds everything it
/// needs to sign a request.
nonisolated struct S3Configuration: Codable, Hashable, Sendable {
    /// e.g. `https://s3.us-east-1.amazonaws.com`, `https://<id>.r2.cloudflarestorage.com`,
    /// or `http://minio.local:9000`.
    var endpoint: URL
    /// `us-east-1` for AWS's default, `auto` for Cloudflare R2.
    var region: String
    var bucket: String
    /// Path-style addressing (`endpoint/bucket/key`), which MinIO and most
    /// self-hosted servers need; otherwise virtual-hosted (`bucket.endpoint/key`).
    var usesPathStyle: Bool
}

/// A saved login, as listed in Settings → Accounts. Never carries the secret.
nonisolated struct SavedServerLogin: Identifiable, Hashable, Sendable {
    let kind: MediaSourceKind
    /// The server host, or the account key for S3.
    let host: String
    let username: String
    /// S3 only: `bucket @ endpoint host`.
    let detail: String?

    var id: String { "\(kind.rawValue)|\(host)" }
}

nonisolated enum NetworkCredentialStore {

    /// SMB logins keep the item name they have always had, so older builds
    /// on the user's other devices still find them.
    private static let legacySMBPrefix = "network-credential-"
    private static let loginPrefix = "server-login-"

    // MARK: - Logins

    static func save(
        _ credential: NetworkCredential,
        kind: MediaSourceKind = .smb,
        host: String,
        store: any SecretStore = KeychainStore.shared
    ) throws {
        let data = try JSONEncoder().encode(credential)
        try store.set(data, forAccount: account(kind: kind, host: host))
    }

    static func credential(
        kind: MediaSourceKind = .smb,
        host: String,
        store: any SecretStore = KeychainStore.shared
    ) -> NetworkCredential? {
        guard let data = store.data(forAccount: account(kind: kind, host: host)) else {
            return nil
        }
        return try? JSONDecoder().decode(NetworkCredential.self, from: data)
    }

    static func remove(
        kind: MediaSourceKind = .smb,
        host: String,
        store: any SecretStore = KeychainStore.shared
    ) {
        store.remove(account: account(kind: kind, host: host))
    }

    /// Every saved login on this device (synced ones included), for
    /// Settings → Accounts.
    static func savedLogins(store: any SecretStore = KeychainStore.shared) -> [SavedServerLogin] {
        let smb = store.accounts(withPrefix: legacySMBPrefix).map { name in
            (MediaSourceKind.smb, String(name.dropFirst(legacySMBPrefix.count)))
        }
        let others = store.accounts(withPrefix: loginPrefix).compactMap { name -> (MediaSourceKind, String)? in
            let remainder = name.dropFirst(loginPrefix.count)
            guard let dash = remainder.firstIndex(of: "-"),
                  let kind = MediaSourceKind(rawValue: String(remainder[..<dash])),
                  kind.usesServerLogin
            else { return nil }
            return (kind, String(remainder[remainder.index(after: dash)...]))
        }
        return (smb + others).compactMap { kind, host in
            guard let credential = credential(kind: kind, host: host, store: store) else { return nil }
            let detail = credential.s3.map { "\($0.bucket) @ \($0.endpoint.host() ?? $0.endpoint.absoluteString)" }
            return SavedServerLogin(kind: kind, host: host, username: credential.username, detail: detail)
        }
        .sorted { ($0.kind.displayName, $0.host) < ($1.kind.displayName, $1.host) }
    }

    private static func account(kind: MediaSourceKind, host: String) -> String {
        let host = host.lowercased()
        return kind == .smb ? legacySMBPrefix + host : "\(loginPrefix)\(kind.rawValue)-\(host)"
    }
}

// MARK: - SSH host keys

/// Trust-on-first-use pins for SFTP servers: the SHA-256 host-key
/// fingerprint the user approved when linking. A changed key is refused
/// until the user approves it again.
nonisolated enum HostKeyStore {
    private static let prefix = "sftp-host-key-"

    static func pinnedFingerprint(
        host: String,
        port: Int,
        store: any SecretStore = KeychainStore.shared
    ) -> String? {
        store.data(forAccount: account(host: host, port: port)).flatMap { String(data: $0, encoding: .utf8) }
    }

    static func pin(
        _ fingerprint: String,
        host: String,
        port: Int,
        store: any SecretStore = KeychainStore.shared
    ) throws {
        try store.set(Data(fingerprint.utf8), forAccount: account(host: host, port: port))
    }

    static func remove(host: String, port: Int, store: any SecretStore = KeychainStore.shared) {
        store.remove(account: account(host: host, port: port))
    }

    private static func account(host: String, port: Int) -> String {
        "\(prefix)\(host.lowercased()):\(port)"
    }
}
