//
//  WebAuthenticator.swift
//  Edendale
//
//  Runs an OAuth authorization page in ASWebAuthenticationSession, which
//  captures the redirect to the provider's custom scheme itself (no URL
//  type registration needed). Apple TV has no web sign-in here; it links
//  accounts through AccountHandoff or, for OneDrive, a device code.
//

import AuthenticationServices
import Foundation
#if canImport(UIKit)
import UIKit
#endif
#if canImport(AppKit)
import AppKit
#endif

#if !os(tvOS)
@MainActor
final class WebAuthenticator: NSObject, ASWebAuthenticationPresentationContextProviding {

    private var session: ASWebAuthenticationSession?

    /// Presents `url` and returns the redirect URL the provider sent back.
    /// Throws `OAuthError.cancelled` when the user closes the sheet.
    func authenticate(url: URL, callbackScheme: String) async throws -> URL {
        try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(
                url: url,
                callback: .customScheme(callbackScheme)
            ) { [weak self] callbackURL, error in
                self?.session = nil
                if let callbackURL {
                    continuation.resume(returning: callbackURL)
                } else if let error = error as? ASWebAuthenticationSessionError, error.code == .canceledLogin {
                    continuation.resume(throwing: OAuthError.cancelled)
                } else {
                    continuation.resume(throwing: error ?? OAuthError.cancelled)
                }
            }
            session.presentationContextProvider = self
            // Share the browser's sign-in state, so an account already
            // signed in there is one tap away.
            session.prefersEphemeralWebBrowserSession = false
            self.session = session
            if !session.start() {
                self.session = nil
                continuation.resume(throwing: OAuthError.cancelled)
            }
        }
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            #if os(macOS)
            NSApplication.shared.keyWindow ?? NSApp.windows.first ?? ASPresentationAnchor()
            #else
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first { $0.isKeyWindow }
                ?? ASPresentationAnchor()
            #endif
        }
    }
}
#endif
