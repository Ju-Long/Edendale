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
                Text("Online subtitle search needs your own Wyzie API key for unrestricted access. Visit store.wyzie.io/redeem, complete the steps on the site to redeem your subscription, then paste the key below.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                
                Link("Redeem a Key", destination: redeemURL)
                    .archiveButtonStyle(.secondary)

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
