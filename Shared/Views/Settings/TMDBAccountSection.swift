//
//  TMDBAccountSection.swift
//  Edendale
//
//  The "TMDB Account" section of SettingsView. Drives TMDBAccountStore's
//  sign-in flow: show the themoviedb.org approval page as a QR code, attempt
//  to open it in the device browser, then exchange the approved request token.
//

import SwiftUI

struct TMDBAccountSection: View {
    @Environment(TMDBAccountStore.self) private var account
    @Environment(\.openURL) private var openURL

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
                    if let url = await account.beginSignIn() { openURL(url) }
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
