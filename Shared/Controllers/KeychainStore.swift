//
//  KeychainStore.swift
//  Edendale
//
//  Minimal generic-password wrapper around Security.framework. Items go into
//  the data-protection keychain marked synchronizable, so they are encrypted
//  at rest and follow the user's iCloud Keychain across their devices.
//  Dependency-free on purpose — the few attributes we need don't justify a package.
//
//  tvOS never syncs keychain items in either direction ("Items that you store
//  on tvOS never leave the device where you create them, and items that you
//  store on other devices don't synchronize to tvOS devices."), so Apple TV
//  keeps its own copies; see AccountHandoff.
//

import Foundation
import Security

/// Secret storage keyed by account name. `KeychainStore` is the real one;
/// tests use `InMemorySecretStore`, because an unsigned test host can't
/// reach the data-protection keychain and must never touch the user's items.
nonisolated protocol SecretStore: Sendable {
    func data(forAccount account: String) -> Data?
    func set(_ value: Data, forAccount account: String) throws
    func remove(account: String)
    /// Account names that start with `prefix`.
    func accounts(withPrefix prefix: String) -> [String]
}

nonisolated struct KeychainStore: SecretStore {

    /// The app's single store, namespaced by `AppIdentifiers.keychainService`.
    static let shared = KeychainStore(service: AppIdentifiers.keychainService)

    /// Every stored item is declared here so account names stay unique and greppable.
    enum Key: String {
        case tmdbUserSession = "tmdb-user-session"
        case tmdbV3Session = "tmdb-v3-session"
        case wyzieAPIKey = "wyzie-api-key"
    }

    let service: String

    // MARK: - Read

    func data(for key: Key) -> Data? {
        data(forAccount: key.rawValue)
    }

    /// Reads an item under a dynamic account name — for families of items
    /// (e.g. one credential per network host) that can't be enumerated in
    /// `Key`. Prefix the account to keep it greppable, e.g. `network-credential-`.
    func data(forAccount account: String) -> Data? {
        var query = baseQuery(account: account)
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        query[kSecReturnData as String] = true
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    /// Names of every item in this service whose account starts with
    /// `prefix`, synced or local. Reads attributes only, never the secrets.
    func accounts(withPrefix prefix: String) -> [String] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            kSecUseDataProtectionKeychain as String: true,
            kSecMatchLimit as String: kSecMatchLimitAll,
            kSecReturnAttributes as String: true
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let items = result as? [[String: Any]]
        else { return [] }
        let names = items.compactMap { $0[kSecAttrAccount as String] as? String }
        return Array(Set(names.filter { $0.hasPrefix(prefix) })).sorted()
    }

    // MARK: - Write

    func set(_ value: Data, for key: Key) throws {
        try set(value, forAccount: key.rawValue)
    }

    func set(_ value: Data, forAccount account: String) throws {
        // Delete-then-add keeps the attribute set consistent across rewrites.
        SecItemDelete(baseQuery(account: account) as CFDictionary)
        var attributes = baseQuery(account: account)
        attributes[kSecValueData as String] = value
        // Adds must say exactly `true` (the Any wildcard is query-only), and
        // synchronizable items cannot use a ThisDeviceOnly accessibility class.
        attributes[kSecAttrSynchronizable as String] = true
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else { throw KeychainError(status: status) }
    }

    func remove(_ key: Key) {
        remove(account: key.rawValue)
    }

    func remove(account: String) {
        SecItemDelete(baseQuery(account: account) as CFDictionary)
    }

    // MARK: - Private

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            // Match synced and local copies alike when reading or deleting.
            kSecAttrSynchronizable as String: kSecAttrSynchronizableAny,
            // Without this, macOS targets the legacy file-based keychain,
            // which cannot hold synchronizable items.
            kSecUseDataProtectionKeychain as String: true
        ]
    }
}

nonisolated struct KeychainError: Error, LocalizedError {
    let status: OSStatus

    var errorDescription: String? {
        let message = SecCopyErrorMessageString(status, nil) as String? ?? "OSStatus \(status)"
        return String(localized: "Keychain error: \(message)")
    }
}

/// A process-local `SecretStore` for tests and previews.
nonisolated final class InMemorySecretStore: SecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String: Data] = [:]

    init() {}

    func data(forAccount account: String) -> Data? {
        lock.withLock { items[account] }
    }

    func set(_ value: Data, forAccount account: String) throws {
        lock.withLock { items[account] = value }
    }

    func remove(account: String) {
        _ = lock.withLock { items.removeValue(forKey: account) }
    }

    func accounts(withPrefix prefix: String) -> [String] {
        lock.withLock { items.keys.filter { $0.hasPrefix(prefix) }.sorted() }
    }
}
