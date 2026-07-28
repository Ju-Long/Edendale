//
//  URLBookmarkOptions.swift
//  Edendale
//
//  Security-scoped bookmark options resolved per platform: macOS and
//  Catalyst require .withSecurityScope; the other platforms scope
//  implicitly and take no extra flags.
//

import Foundation

extension URL.BookmarkCreationOptions {
    /// Security-scoped creation where the platform supports it.
    nonisolated static var securityScoped: URL.BookmarkCreationOptions {
        #if os(macOS) || targetEnvironment(macCatalyst)
        .withSecurityScope
        #else
        []
        #endif
    }
}

extension URL.BookmarkResolutionOptions {
    /// Security-scoped resolution where the platform supports it.
    nonisolated static var securityScoped: URL.BookmarkResolutionOptions {
        #if os(macOS) || targetEnvironment(macCatalyst)
        .withSecurityScope
        #else
        []
        #endif
    }
}
