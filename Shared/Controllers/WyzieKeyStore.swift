//
//  WyzieKeyStore.swift
//  Edendale
//
//  Resolves a user-provided Wyzie API key ahead of the optional build-time
//  key. The user key is stored in the synchronized keychain and is never
//  logged.
//

import Foundation

/// Resolves the optional Wyzie credential. A user-provided key is stored in
/// the synchronized keychain and is never logged.
@MainActor
@Observable
final class WyzieKeyStore {
    private(set) var userKey: String

    init() {
        userKey = KeychainStore.shared.data(for: .wyzieAPIKey)
            .flatMap { String(data: $0, encoding: .utf8) }?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    var buildKey: String {
        ((Bundle.main.object(forInfoDictionaryKey: "WyzieAPIKey") as? String) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var resolvedKey: String {
        userKey.isEmpty ? buildKey : userKey
    }

    var isConfigured: Bool { !resolvedKey.isEmpty }
    var hasUserKey: Bool { !userKey.isEmpty }
    var usesBuildKey: Bool { userKey.isEmpty && !buildKey.isEmpty }

    func save(_ key: String) throws {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            clear()
            return
        }
        try KeychainStore.shared.set(Data(trimmed.utf8), for: .wyzieAPIKey)
        userKey = trimmed
    }

    func clear() {
        KeychainStore.shared.remove(.wyzieAPIKey)
        userKey = ""
    }
}
