//
//  AppIdentifiers.swift
//  Edendale
//
//  Single source of truth for the app's Apple-platform identifiers.
//  Keep these in sync with Edendale.entitlements and the Apple developer portal.
//  Never hardcode these strings elsewhere — reference this enum instead.
//

import Foundation

enum AppIdentifiers {

    /// App Group — shared container for UserDefaults (and files) across the app
    /// and any current or future extensions.
    static let appGroup = "group.com.BaBaSaMa.Edendale"

    /// Private CloudKit container backing the iCloud-synced watch-progress store.
    /// Must match `com.apple.developer.icloud-container-identifiers` in the entitlements.
    static let iCloudContainer = "iCloud.com.BaBaSaMa.Edendale"

    /// UserDefaults scoped to the app group so the app and its extensions share one store.
    /// Use this instead of `UserDefaults.standard` for anything that must be group-visible.
    static let defaults = UserDefaults(suiteName: appGroup)!

    /// Keychain service namespace for the app's secure items (e.g. the TMDB user
    /// access token). A stable literal, deliberately equal to the bundle identifier,
    /// so every platform target reads and writes the same synchronized items.
    static let keychainService = "com.BaBaSaMa.Edendale"

    /// The universal domain for web links (HTTPS routing).
    static let linkHost = "edendale.babasama.com"
}
