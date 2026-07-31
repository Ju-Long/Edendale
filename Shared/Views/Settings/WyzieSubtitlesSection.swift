//
//  WyzieSubtitlesSection.swift
//  Edendale
//
//  Settings for the optional Wyzie Subs credential used only when the user
//  explicitly starts an online subtitle search.
//

import SwiftUI

struct WyzieSubtitlesSection: View {
    @Environment(WyzieKeyStore.self) private var keys
    @State private var enteredKey = ""
    @State private var lastError: String?

    private let redeemURL = URL(string: "https://store.wyzie.io/redeem")!

    var body: some View {
        Section {
            if keys.hasUserKey {
                LabeledContent("API Key", value: String(localized: "Key saved"))
                Text("Your Wyzie key is stored in your keychain and shared with your other devices through iCloud.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                Button("Remove", role: .destructive) {
                    keys.clear()
                    lastError = nil
                }
                .archiveButtonStyle(.ghost)
            } else {
                if keys.usesBuildKey {
                    Text("Using the key from Secrets.xcconfig.")
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                } else {
                    Text("Online subtitle search needs a free Wyzie API key from store.wyzie.io/redeem.")
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)

                    Link("Get a Free Key", destination: redeemURL)
                        .archiveButtonStyle(.secondary)
                }

                SecureField("Wyzie API Key", text: $enteredKey)

                Button("Save") {
                    do {
                        try keys.save(enteredKey)
                        enteredKey = ""
                        lastError = nil
                    } catch {
                        lastError = error.localizedDescription
                    }
                }
                .archiveButtonStyle(.secondary)
                .disabled(enteredKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }

            if let lastError {
                Text(lastError)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.gold)
            }
        } header: {
            Text("Subtitles").labelCaps()
        }
    }
}
