//
//  AccountsSection.swift
//  Edendale
//
//  Settings → Accounts: every linked Google Drive, OneDrive, and Dropbox
//  account and every saved server login, with the sources that use each.
//  This is the only place accounts and logins are removed; removing a source
//  never signs anything out. Items sync through the iCloud Keychain, so
//  signing out applies to the user's other devices too (Apple TV keeps its
//  own copies). Google and Dropbox grants can also be revoked at the
//  provider, which ends access everywhere, Apple TV included.
//

import SwiftUI
import SwiftData

struct AccountsSection: View {
    @Environment(CloudAccountStore.self) private var store
    @Query private var folders: [VideoFolder]

    @State private var accountToSignOut: CloudAccount?
    @State private var loginToForget: SavedServerLogin?

    var body: some View {
        SettingsSection(String(localized: "Accounts")) {
            if store.accounts.isEmpty && store.savedLogins.isEmpty {
                SettingsNote(String(localized: "No linked accounts or saved logins. Link Source adds them."))
            }

            ForEach(store.accounts) { account in
                SettingsRow(
                    account.label,
                    value: account.kind.displayName,
                    detail: sourcesDetail(kind: account.kind, host: account.key)
                ) {
                    Button("Sign Out", role: .destructive) {
                        accountToSignOut = account
                    }
                    .archiveButtonStyle(.ghost)
                }
            }

            ForEach(store.savedLogins) { login in
                SettingsRow(
                    loginTitle(login),
                    value: login.kind.displayName,
                    detail: sourcesDetail(kind: login.kind, host: login.host)
                ) {
                    Button("Forget", role: .destructive) {
                        loginToForget = login
                    }
                    .archiveButtonStyle(.ghost)
                }
            }

            SettingsNote(syncNote)
        }
        .onAppear { store.reload() }
        .confirmationDialog(
            "Sign Out",
            isPresented: Binding(get: { accountToSignOut != nil }, set: { if !$0 { accountToSignOut = nil } }),
            presenting: accountToSignOut
        ) { account in
            Button("Sign Out", role: .destructive) {
                Task { await store.signOut(account, revoke: false) }
            }
            if CloudProviders.supportsRevocation(account.kind) {
                Button("Sign Out and Revoke Access", role: .destructive) {
                    Task { await store.signOut(account, revoke: true) }
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: { account in
            Text(signOutMessage(account))
        }
        .confirmationDialog(
            "Forget Login",
            isPresented: Binding(get: { loginToForget != nil }, set: { if !$0 { loginToForget = nil } }),
            presenting: loginToForget
        ) { login in
            Button("Forget", role: .destructive) { store.forget(login) }
            Button("Cancel", role: .cancel) {}
        } message: { login in
            Text(forgetMessage(login))
        }
    }

    // MARK: - Copy

    private func loginTitle(_ login: SavedServerLogin) -> String {
        if let detail = login.detail {
            return login.username.isEmpty ? detail : "\(login.username) · \(detail)"
        }
        return login.username.isEmpty ? login.host : "\(login.username) @ \(login.host)"
    }

    private func sourcesDetail(kind: MediaSourceKind, host: String) -> String {
        let count = folders.filter { folder in
            folder.sourceKind == kind
                && folder.remoteURL?.host(percentEncoded: false)?.lowercased() == host.lowercased()
        }.count
        switch count {
        case 0: return String(localized: "No sources on this device")
        case 1: return String(localized: "1 source on this device")
        default: return String(localized: "\(count) sources on this device")
        }
    }

    private var syncNote: String {
        #if os(tvOS)
        String(localized: "Accounts and logins on Apple TV stay on this Apple TV. Removing a source keeps them; sign out here.")
        #else
        String(localized: "Accounts and logins sync through your iCloud Keychain to your other devices, except Apple TV. Removing a source keeps them; sign out here.")
        #endif
    }

    private func signOutMessage(_ account: CloudAccount) -> String {
        #if os(tvOS)
        let base = String(localized: "Sources from \(account.label) stop working on this Apple TV until you link the account again.")
        #else
        let base = String(localized: "Sources from \(account.label) stop working on this device and your other devices that share your iCloud Keychain, until you sign in again.")
        #endif
        guard CloudProviders.supportsRevocation(account.kind) else { return base }
        return base + " " + String(localized: "Revoking access also signs out any Apple TV you linked it to.")
    }

    private func forgetMessage(_ login: SavedServerLogin) -> String {
        // An S3 login is keyed by an account hash; name the bucket instead.
        let server = login.detail ?? login.host
        #if os(tvOS)
        return String(localized: "Sources on \(server) can't connect until you enter the login again.")
        #else
        return String(localized: "Sources on \(server) can't connect, on this device or your other devices that share your iCloud Keychain, until you enter the login again.")
        #endif
    }
}
