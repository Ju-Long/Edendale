//
//  NetworkCredentialStore.swift
//  Edendale
//
//  Keychain persistence for network-source credentials, one per host.
//  Items ride iCloud Keychain (KeychainStore marks them synchronizable),
//  so a NAS added on one device offers its saved login on the others —
//  the library index itself stays local by design.
//

import Foundation

enum NetworkCredentialStore {

    /// One credential per host: sources on the same server share a login.
    static func save(_ credential: NetworkCredential, host: String) throws {
        let data = try JSONEncoder().encode(credential)
        try KeychainStore.shared.set(data, forAccount: account(host: host))
    }

    static func credential(host: String) -> NetworkCredential? {
        guard let data = KeychainStore.shared.data(forAccount: account(host: host)) else {
            return nil
        }
        return try? JSONDecoder().decode(NetworkCredential.self, from: data)
    }

    static func remove(host: String) {
        KeychainStore.shared.remove(account: account(host: host))
    }

    private static func account(host: String) -> String {
        "network-credential-\(host.lowercased())"
    }
}
