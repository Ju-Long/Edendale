//
//  AccountHandoffRequestView.swift
//  Edendale
//
//  On iPhone and iPad: "Link Google Drive on Living Room?" An Apple TV asked
//  for an account or saved login (AccountHandoff). The user picks one they
//  already have, signs in to another, or declines. Nothing is sent without
//  that choice.
//

#if os(iOS)
import SwiftUI

struct AccountHandoffRequestView: View {
    let pending: AccountHandoffCenter.PendingRequest

    @Environment(AccountHandoffCenter.self) private var center
    @Environment(CloudAccountStore.self) private var store
    @State private var isSigningIn = false
    @State private var errorMessage: String?

    private var kind: MediaSourceKind { pending.request.kind }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Edendale on \(pending.request.deviceName) is asking for your \(kind.displayName) access. It stays on that Apple TV until you sign out there.")
                        .font(Typography.bodyLG)
                        .foregroundStyle(Theme.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                if kind.isCloudAccount {
                    accountChoices
                } else {
                    loginChoices
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .font(Typography.bodySM)
                            .foregroundStyle(Theme.textSecondary)
                    }
                }

                Section {
                    Button("Don't Allow", role: .destructive) {
                        Task { await center.decline() }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Theme.background)
            .navigationTitle("Link \(kind.displayName) on \(pending.request.deviceName)?")
            .navigationBarTitleDisplayMode(.inline)
        }
        .interactiveDismissDisabled()
        .onAppear { store.reload() }
    }

    @ViewBuilder
    private var accountChoices: some View {
        let accounts = store.accounts(of: kind)
        Section {
            ForEach(accounts) { account in
                Button {
                    Task { await center.approve(account: account) }
                } label: {
                    Label("Use \(account.label)", image: .circleUserFill)
                }
            }
            if CloudProviders.isConfigured(kind) {
                Button {
                    signIn()
                } label: {
                    if isSigningIn {
                        HStack(spacing: 12) {
                            ProgressView().tint(Theme.gold)
                            Text("Waiting for \(kind.displayName)…")
                        }
                    } else {
                        Label(accounts.isEmpty ? "Sign In to \(kind.displayName)" : "Sign In to Another Account", image: .link)
                    }
                }
                .disabled(isSigningIn)
            }
        } header: {
            Text("Account").labelCaps()
        }
    }

    @ViewBuilder
    private var loginChoices: some View {
        let logins = store.savedLogins.filter { $0.kind == kind }
        Section {
            if logins.isEmpty {
                Text("There are no saved \(kind.displayName) logins on this device. Link the server here first, or type the login on Apple TV.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
            ForEach(logins) { login in
                Button {
                    approve(login)
                } label: {
                    Label(login.detail ?? "\(login.username) @ \(login.host)", image: .link)
                }
            }
        } header: {
            Text("Saved Logins").labelCaps()
        }
    }

    private func signIn() {
        isSigningIn = true
        errorMessage = nil
        Task {
            do {
                let account = try await store.signIn(kind: kind)
                await center.approve(account: account)
            } catch OAuthError.cancelled {
            } catch {
                errorMessage = error.localizedDescription
            }
            isSigningIn = false
        }
    }

    private func approve(_ login: SavedServerLogin) {
        guard let handoff = AccountHandoff.Login.handing(login) else {
            errorMessage = ConnectorError.signInRequired(provider: login.host).localizedDescription
            return
        }
        Task { await center.approve(login: handoff) }
    }
}
#endif
