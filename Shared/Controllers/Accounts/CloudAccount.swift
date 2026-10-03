//
//  CloudAccount.swift
//  Edendale
//
//  A linked Google Drive, OneDrive, or Dropbox account: who it is and the
//  refresh token Edendale uses to reach it. One Keychain item per account
//  (`cloud-account-<kind>-<accountKey>`). Access tokens are never stored;
//  CloudTokenProvider keeps them in memory.
//
//  KeychainStore writes synchronizable items, so one sign-in covers iPhone,
//  iPad, Mac, and Vision Pro. Apple TV never receives synced items and gets
//  its accounts through AccountHandoff instead. Because deleting a
//  synchronizable item deletes every synced copy, removing a source never
//  deletes an account; signing out is explicit (Settings → Accounts).
//

import Foundation

nonisolated struct CloudAccount: Codable, Hashable, Sendable, Identifiable {
    /// Bumped if the stored shape ever changes incompatibly.
    static let currentVersion = 1

    var version: Int = CloudAccount.currentVersion
    let kind: MediaSourceKind
    /// The provider's stable user ID: Google's `sub`, the Microsoft user
    /// `id`, Dropbox's `account_id`.
    let subject: String
    var email: String?
    var displayName: String?
    var refreshToken: String
    var scopes: [String]
    /// Microsoft only: the default drive's ID, the root of the source picker.
    var driveID: String?
    var linkedAt: Date = Date()

    var id: String { key }

    /// The URL host of every item from this account (see SourceURL).
    var key: String { SourceURL.accountKey(kind: kind, subject: subject) }

    /// What Settings and the source picker show: the email when known.
    var label: String { email ?? displayName ?? kind.displayName }
}

/// Keychain persistence for `CloudAccount`s. Safe to use from any thread.
nonisolated struct CloudAccountVault: Sendable {
    static let shared = CloudAccountVault(store: KeychainStore.shared)

    private static let prefix = "cloud-account-"

    let store: any SecretStore

    func account(kind: MediaSourceKind, key: String) -> CloudAccount? {
        guard let data = store.data(forAccount: Self.itemName(kind: kind, key: key)),
              let account = try? JSONDecoder().decode(CloudAccount.self, from: data),
              account.version <= CloudAccount.currentVersion,
              account.kind == kind
        else { return nil }
        return account
    }

    func save(_ account: CloudAccount) throws {
        let data = try JSONEncoder().encode(account)
        try store.set(data, forAccount: Self.itemName(kind: account.kind, key: account.key))
    }

    func remove(kind: MediaSourceKind, key: String) {
        store.remove(account: Self.itemName(kind: kind, key: key))
    }

    /// Every linked account, grouped by provider, then by label.
    func all() -> [CloudAccount] {
        store.accounts(withPrefix: Self.prefix).compactMap { name -> CloudAccount? in
            let remainder = name.dropFirst(Self.prefix.count)
            guard let dash = remainder.lastIndex(of: "-"),
                  let kind = MediaSourceKind(rawValue: String(remainder[..<dash]))
            else { return nil }
            return account(kind: kind, key: String(remainder[remainder.index(after: dash)...]))
        }
        .sorted { ($0.kind.displayName, $0.label) < ($1.kind.displayName, $1.label) }
    }

    func accounts(of kind: MediaSourceKind) -> [CloudAccount] {
        all().filter { $0.kind == kind }
    }

    private static func itemName(kind: MediaSourceKind, key: String) -> String {
        "\(prefix)\(kind.rawValue)-\(key)"
    }
}
