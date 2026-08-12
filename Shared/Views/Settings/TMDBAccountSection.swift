//
//  TMDBAccountSection.swift
//  Edendale
//
//  The "TMDB Account" section of SettingsView. Drives TMDBAccountStore's
//  sign-in flow: presents the themoviedb.org approval page inside an
//  ASWebAuthenticationSession (required by App Review Guideline 5.1.1).
//  Shows the approval page as a QR code fallback on cancel.
//

import AuthenticationServices
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif
#if canImport(AppKit)
import AppKit
#endif

struct TMDBAccountSection: View {
    @Environment(TMDBAccountStore.self) private var account

    var body: some View {
        Section {
            switch account.phase {
            case .signedIn:
                LabeledContent("Account", value: String(localized: "Connected"))
                Text("Your TMDB sign-in is stored in your keychain and shared with your other devices through iCloud.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                Button("Sign Out", role: .destructive) {
                    Task { await account.signOut() }
                }
                .archiveButtonStyle(.ghost)

            case .awaitingApproval:
                Text("Approve Edendale on the TMDB page in your browser, or scan the QR code with another device.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                if let approvalURL = account.pendingApprovalURL {
                    QRCodeView(url: approvalURL)
                        .frame(maxWidth: .infinity)
                }
                Button("Click here to continue") {
                    Task { await account.completeSignIn() }
                }
                .archiveButtonStyle(.secondary)
                Button("Cancel") { account.cancelSignIn() }
                    .archiveButtonStyle(.ghost)

            case .exchanging:
                HStack(spacing: 12) {
                    ProgressView()
                    Text("Finishing sign-in…")
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                }
                .accessibilityElement(children: .combine)

            case .signedOut:
                signedOutContent
            }

            if let error = account.lastError {
                Text(error)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.gold)
            }
        } header: {
            Text("TMDB Account").labelCaps()
        }
    }

    @ViewBuilder
    private var signedOutContent: some View {
        if account.canSignIn {
            Text("Connect your TMDB account — sign in, or create one for free, on themoviedb.org.")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)

            Button("Connect TMDB Account") {
                Task {
                    guard let url = await account.beginSignIn() else { return }
                    let session = ASWebAuthenticationSession(
                        url: url,
                        callbackURLScheme: "edendale"
                    ) { _, error in
                        if error != nil {
                            // ASWebAuthenticationSessionError.canceledLogin
                            // leaves the request token valid; the user can
                            // retry or use the QR fallback below.
                            return
                        }
                        Task { @MainActor in
                            await account.completeSignIn()
                        }
                    }
#if !os(tvOS)
                    session.prefersEphemeralWebBrowserSession = true
                    session.presentationContextProvider = WebAuthContext.shared
#endif
                    session.start()
                }
            }
            .archiveButtonStyle(.secondary)
        } else {
            Text("Signing in needs the app's TMDB read access token (TMDB_READ_ACCESS_TOKEN in Secrets.xcconfig).")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
        }
    }
}

// MARK: - ASWebAuthenticationSession presentation context

#if !os(tvOS)
/// Shared presentation context provider so ASWebAuthenticationSession can
/// present its authentication sheet from a SwiftUI view hierarchy.
/// tvOS manages its own presentation and does not expose this protocol.
private final class WebAuthContext: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = WebAuthContext()

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
#if os(macOS)
        NSApplication.shared.keyWindow ?? NSApp.windows.first!
#else
        UIApplication.shared
            .connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }
        ?? ASPresentationAnchor()
#endif
    }
}
#endif
