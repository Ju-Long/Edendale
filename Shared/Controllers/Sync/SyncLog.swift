//
//  SyncLog.swift
//  Edendale
//
//  The one place sync and the handoff log through (SYNC-PROTOCOL.md §8.5,
//  FEATURES-27.1.md H10). Lines carry counts, file names, stamps, device
//  names, and error classes; never record contents, review text, titles,
//  tokens, passwords, keys, pairing secrets, or URLs with credentials.
//  Callers format nothing secret into a message, and the sink redacts the
//  patterns a secret would take if one slipped through anyway. Tests
//  install a capturing sink and assert that no key material reaches it.
//

import Foundation
import os

nonisolated enum SyncLog {
    typealias Sink = @Sendable (String) -> Void

    private static let state = State()

    private final class State: @unchecked Sendable {
        private let lock = NSLock()
        private var sink: Sink?
        private let logger = Logger(subsystem: "com.BaBaSaMa.Edendale", category: "Sync")

        func emit(_ line: String) {
            let sink = lock.withLock { self.sink }
            if let sink {
                sink(line)
            } else {
                logger.info("\(line, privacy: .public)")
            }
        }

        func install(_ sink: Sink?) {
            lock.withLock { self.sink = sink }
        }
    }

    /// Replaces the default `os.Logger` sink; `nil` restores it.
    static func install(sink: Sink?) {
        state.install(sink)
    }

    static func info(_ message: String) {
        state.emit(redacted(message))
    }

    static func warning(_ message: String) {
        state.emit("warning: " + redacted(message))
    }

    /// What an error is, without what it carries: the type and, for the
    /// connector and OAuth errors that name a host or provider, the case.
    static func describe(_ error: Error) -> String {
        if let connectorError = error as? ConnectorError {
            return "ConnectorError." + caseName(connectorError)
        }
        if let oauthError = error as? OAuthError {
            return "OAuthError." + caseName(oauthError)
        }
        if let rejection = error as? SyncFileFormat.Rejection {
            return "Rejection." + caseName(rejection)
        }
        if let urlError = error as? URLError {
            return "URLError(\(urlError.code.rawValue))"
        }
        let nsError = error as NSError
        return "\(nsError.domain)(\(nsError.code))"
    }

    private static func caseName(_ value: Any) -> String {
        let text = String(describing: value)
        return String(text.prefix { $0 != "(" })
    }

    /// Strips anything that looks like a bearer token, a query string with
    /// credentials, or a long base64/hex run (keys, tokens, secrets) from a
    /// line before it is logged.
    static func redacted(_ message: String) -> String {
        // Device file names (`<uuid>.json`) and stamps stay; everything
        // else that looks like key material goes.
        message.split(separator: " ", omittingEmptySubsequences: false).map { token -> String in
            let text = String(token)
            if deviceFileName.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil {
                return text
            }
            var redacted = text
            for pattern in redactionPatterns {
                redacted = pattern.stringByReplacingMatches(
                    in: redacted, range: NSRange(redacted.startIndex..., in: redacted), withTemplate: "[redacted]"
                )
            }
            return redacted
        }
        .joined(separator: " ")
    }

    private static let deviceFileName = try! NSRegularExpression(
        pattern: #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(\.json)?[:,;.]?$"#
    )

    private static let redactionPatterns: [NSRegularExpression] = [
        // Authorization headers and OAuth parameters.
        try! NSRegularExpression(pattern: #"(?i)bearer\s+\S+"#),
        try! NSRegularExpression(pattern: #"(?i)(access_token|refresh_token|password|client_secret|secret|token|proof|mac|key)=[^&\s]+"#),
        // Any parameter whose value is a run of base64url or hex: `s=…`, `k=…`.
        try! NSRegularExpression(pattern: #"\b\w{1,32}=[A-Za-z0-9_\-+/=]{16,}"#),
        // URL userinfo.
        try! NSRegularExpression(pattern: #"://[^/\s@]+@"#),
        // Long base64url or hex runs: keys, proofs, tokens.
        try! NSRegularExpression(pattern: #"[A-Za-z0-9_\-+/=]{32,}"#)
    ]
}
