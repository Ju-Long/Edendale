//
//  SyncHandoffView.swift
//  Edendale
//
//  Setting up another device, or receiving from one, over the local
//  network (SYNC-PROTOCOL.md §9). The sender first ticks what to send:
//  its sync location, and any linked accounts and saved server logins.
//  Then the two devices pair: an iPhone or iPad can scan the other
//  device's code or show one; a Mac, Apple TV, or Vision Pro shows one.
//  The sender confirms what goes to the named device before anything is
//  sent; the receiver stores what validates and both show the result.
//

import SwiftUI
import UniformTypeIdentifiers

struct SyncHandoffView: View {
    let role: HandoffV2.Role
    /// A code already scanned or opened as a link (receiver or sender).
    var scanned: HandoffV2.QRPayload? = nil

    @Environment(SyncCoordinator.self) private var sync
    @Environment(CloudAccountStore.self) private var accounts
    @Environment(\.dismiss) private var dismiss

    @State private var session: HandoffV2Session
    @State private var selection = SyncHandoff.Selection()
    @State private var choosing: Bool
    @State private var pairing: PairMode?
    @State private var followUp: SyncHandoff.FollowUp?
    @State private var showFolderImporter = false
    @State private var isSigningIn = false
    @State private var followUpError: String?

    enum PairMode {
        case host
        case scan
    }

    init(role: HandoffV2.Role, scanned: HandoffV2.QRPayload? = nil) {
        self.role = role
        self.scanned = scanned
        _session = State(initialValue: HandoffV2Session(role: role))
        _choosing = State(initialValue: role == .sender)
    }

    var body: some View {
        NavigationStack {
            content
                #if !os(tvOS)
                .navigationTitle(role == .sender ? "Set Up Another Device" : "Receive from Another Device")
                #endif
                .background(Theme.background)
                .toolbar {
                    #if !os(tvOS)
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close", image: .xmark) { close() }
                            .archiveButtonStyle(.ghost)
                    }
                    #endif
                }
        }
        .presentationSizing(.form)
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 600)
        #endif
        .onAppear(perform: prepare)
        .onDisappear { session.cancel() }
        #if !os(tvOS)
        .fileImporter(isPresented: $showFolderImporter, allowedContentTypes: [.folder]) { result in
            guard case .success(let url) = result, case .pickFolder(let space) = followUp else { return }
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            if let bookmark = try? url.bookmarkData(options: .securityScoped, includingResourceValuesForKeys: nil, relativeTo: nil) {
                sync.setLocation(SyncLocation(provider: .folder, space: space, folderBookmark: bookmark, folderPath: url.path))
                followUp = nil
            }
        }
        #endif
    }

    private func prepare() {
        session.receiveHandler = { offer in
            let received = await SyncHandoff.receive(offer, accounts: accounts, sync: sync)
            followUp = received.followUp
            return received.result
        }
        session.offerBuilder = { peerPlatform in
            SyncHandoff.makeOffer(
                location: sync.location, selection: selection,
                accounts: accounts.accounts, logins: accounts.savedLogins, peerPlatform: peerPlatform
            )
        }
        accounts.reload()
        if let scanned {
            choosing = role == .sender
            pairing = .scan
            if !choosing { session.startGuest(code: scanned) }
        }
    }

    private func close() {
        session.cancel()
        dismiss()
    }

    // MARK: - Content

    @ViewBuilder
    private var content: some View {
        if choosing {
            choiceList
        } else if pairing == nil {
            pairingChoice
        } else {
            phaseView
        }
    }

    /// Sender: what to send.
    private var choiceList: some View {
        List {
            Section {
                if let location = sync.location {
                    Toggle(isOn: $selection.includeLocation) {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Sync location: \(location.provider.displayName)")
                            Text(location.provider == .folder
                                 ? String(localized: "The other device picks its own folder for the space \"\(location.space)\".")
                                 : String(localized: "Space \"\(location.space)\", with the login that reaches it."))
                                .font(Typography.bodySM)
                                .foregroundStyle(Theme.textSecondary)
                        }
                    }
                } else {
                    Text("This device has no sync location. You can still send linked accounts and saved logins.")
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                }
            } header: {
                Text("Sync").labelCaps()
            }
            if !accounts.accounts.isEmpty {
                Section {
                    ForEach(accounts.accounts) { account in
                        Toggle(isOn: binding(forAccount: account.key)) {
                            Text("\(account.kind.displayName): \(account.label)")
                        }
                    }
                } header: {
                    Text("Accounts").labelCaps()
                } footer: {
                    Text("A Google Drive login moves only to another Apple device; other devices sign in to Google again with the account pre-filled.")
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            if !accounts.savedLogins.isEmpty {
                Section {
                    ForEach(accounts.savedLogins) { login in
                        Toggle(isOn: binding(forLogin: login.id)) {
                            Text("\(login.kind.displayName): \(login.detail ?? "\(login.username) @ \(login.host)")")
                        }
                    }
                } header: {
                    Text("Saved Logins").labelCaps()
                }
            }
            Section {
                Button("Continue") {
                    choosing = false
                    // A code scanned (or opened as a link) before choosing.
                    if let scanned { session.startGuest(code: scanned) }
                }
                    .archiveButtonStyle(.primary)
                    .disabled(!(selection.includeLocation && sync.location != nil) && selection.accountKeys.isEmpty && selection.loginIDs.isEmpty)
            } footer: {
                Text("Everything is sent encrypted, directly to the other device on your local network, after you confirm its name.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        #if !os(tvOS)
        .scrollContentBackground(.hidden)
        #endif
        .background(Theme.background)
    }

    private func binding(forAccount key: String) -> Binding<Bool> {
        Binding(
            get: { selection.accountKeys.contains(key) },
            set: { if $0 { selection.accountKeys.insert(key) } else { selection.accountKeys.remove(key) } }
        )
    }

    private func binding(forLogin id: String) -> Binding<Bool> {
        Binding(
            get: { selection.loginIDs.contains(id) },
            set: { if $0 { selection.loginIDs.insert(id) } else { selection.loginIDs.remove(id) } }
        )
    }

    /// iPhone and iPad can scan or show; everything else shows.
    @ViewBuilder
    private var pairingChoice: some View {
        #if os(iOS)
        List {
            Section {
                Button {
                    pairing = .scan
                } label: {
                    Label("Scan the Other Device's Code", image: .magnifyingGlassPlay)
                }
                Button {
                    pairing = .host
                    session.startHosting()
                } label: {
                    Label("Show a Code for the Other Device to Scan", image: .link)
                }
            } footer: {
                Text("Open Edendale on the other device, choose \(otherDeviceAction) in Settings → Sync, and pair the two by code. Both devices must be on the same network.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Theme.background)
        #else
        Color.clear.onAppear {
            pairing = .host
            session.startHosting()
        }
        #endif
    }

    @ViewBuilder
    private var phaseView: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                switch session.phase {
                case .idle:
                    if pairing == .scan {
                        scanner
                    } else {
                        progress(String(localized: "Starting…"))
                    }
                case .hosting(let qr, _):
                    QRCodeView(url: qr, accessibilityLabel: String(localized: "Handoff code"))
                        .frame(maxWidth: .infinity)
                    Text("Scan this code with Edendale on the other device, from Settings → Sync → \(otherDeviceAction). A new code appears every two minutes.")
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                case .connecting:
                    progress(String(localized: "Connecting to the other device…"))
                case .pairing:
                    progress(String(localized: "Pairing…"))
                case .paired(let peer):
                    progress(String(localized: "Connected to \(peer)."))
                case .awaitingApproval(let peer, let platform):
                    approval(peer: peer, platform: platform)
                case .receiving(let peer):
                    progress(String(localized: "Waiting for \(peer) to send…"))
                case .sending(let peer):
                    progress(String(localized: "Sending to \(peer)…"))
                case .finished(let stored, let failed, let peer):
                    result(stored: stored, failed: failed, peer: peer)
                case .declined(let peer):
                    Text(role == .sender
                         ? String(localized: "Nothing was sent to \(peer).")
                         : String(localized: "\(peer) didn't send anything."))
                        .font(Typography.bodyLG)
                        .foregroundStyle(Theme.textPrimary)
                    Button("Done") { close() }.archiveButtonStyle(.primary)
                case .failed(let message):
                    Text(message)
                        .font(Typography.bodyLG)
                        .foregroundStyle(Theme.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                    SettingsActions {
                        Button("Try Again") { retry() }.archiveButtonStyle(.secondary)
                        Button("Close") { close() }.archiveButtonStyle(.ghost)
                    }
                }
            }
            .frame(maxWidth: 560, alignment: .leading)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .background(Theme.background)
    }

    @ViewBuilder
    private var scanner: some View {
        #if os(iOS)
        QRScannerView { url in
            guard let payload = HandoffV2.QRPayload(url: url) else { return }
            session.startGuest(code: payload)
        }
        .frame(height: 360)
        .clipShape(.rect(cornerRadius: Theme.Radius.card))
        Text("Point the camera at the code on the other device.")
            .font(Typography.bodySM)
            .foregroundStyle(Theme.textSecondary)
        #else
        EmptyView()
        #endif
    }

    /// What the other device chooses: the opposite of this one's role.
    private var otherDeviceAction: String {
        role == .sender ? String(localized: "Receive from Another Device") : String(localized: "Set Up Another Device")
    }

    private func progress(_ text: String) -> some View {
        HStack(spacing: 12) {
            ProgressView().tint(Theme.gold)
            Text(text)
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.textPrimary)
        }
        .accessibilityElement(children: .combine)
    }

    /// Sender: the summary before anything is sent (§6.1).
    private func approval(peer: String, platform: String?) -> some View {
        let offer = SyncHandoff.makeOffer(
            location: sync.location, selection: selection,
            accounts: accounts.accounts, logins: accounts.savedLogins, peerPlatform: platform
        )
        return VStack(alignment: .leading, spacing: 16) {
            Text("Send to \(peer)?")
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.textPrimary)
            if let location = offer.location {
                bullet(location.provider == .folder
                       ? String(localized: "The space \"\(location.space)\" (\(peer) picks its own folder)")
                       : location.account == nil && location.provider == .googleDrive
                       ? String(localized: "Google Drive, space \"\(location.space)\": \(peer) signs in to Google again with your account pre-filled")
                       : String(localized: "\(location.provider.displayName), space \"\(location.space)\", with its login"))
            }
            if selection.includeLocation, sync.location?.provider == .iCloudDrive, offer.location == nil {
                bullet(String(localized: "Not the sync location: \(peer) can't open iCloud Drive."))
            }
            ForEach(offer.accounts ?? [], id: \.subject) { account in
                bullet(String(localized: "\(account.kind.displayName) account \(account.email ?? account.displayName ?? "")"))
            }
            ForEach(offer.logins ?? [], id: \.host) { login in
                bullet(String(localized: "\(login.kind.displayName) login for \(login.host)"))
            }
            Text("\(peer) keeps these until you sign out there or revoke Edendale's access at the provider.")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            SettingsActions {
                Button("Send") { session.approve() }.archiveButtonStyle(.primary)
                Button("Don't Send") { session.decline() }.archiveButtonStyle(.ghost)
            }
        }
    }

    private func bullet(_ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text("•")
            Text(text).fixedSize(horizontal: false, vertical: true)
        }
        .font(Typography.bodyLG)
        .foregroundStyle(Theme.textPrimary)
    }

    @ViewBuilder
    private func result(stored: [String], failed: [HandoffV2.ResultMessage.Failure], peer: String) -> some View {
        Text(role == .sender ? String(localized: "\(peer) received:") : String(localized: "Received from \(peer):"))
            .font(Typography.bodyLG)
            .foregroundStyle(Theme.textPrimary)
        if stored.isEmpty && failed.isEmpty {
            bullet(String(localized: "Nothing"))
        }
        ForEach(stored, id: \.self) { item in
            bullet(describe(item))
        }
        ForEach(failed, id: \.item) { failure in
            if let reason = failure.reason {
                bullet(String(localized: "\(describe(failure.item)) couldn't be kept (\(reason))"))
            } else {
                bullet(String(localized: "\(describe(failure.item)) couldn't be kept."))
            }
        }
        if role == .receiver, let followUp {
            followUpView(followUp)
        } else {
            Button("Done") { close() }.archiveButtonStyle(.primary)
        }
    }

    private func describe(_ item: String) -> String {
        let parts = item.split(separator: ":", maxSplits: 2).map(String.init)
        switch parts.first {
        case "location": return String(localized: "Sync location")
        case "account": return parts.count > 1 ? String(localized: "\(MediaSourceKind(rawValue: parts[1])?.displayName ?? parts[1]) account") : item
        case "login": return parts.count > 2 ? String(localized: "\(MediaSourceKind(rawValue: parts[1])?.displayName ?? parts[1]) login for \(parts[2])") : item
        default: return item
        }
    }

    /// Receiver: what this device finishes itself.
    @ViewBuilder
    private func followUpView(_ followUp: SyncHandoff.FollowUp) -> some View {
        switch followUp {
        case .pickFolder(let space):
            Text("Choose the folder for the space \"\(space)\" on this device.")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
            #if !os(tvOS)
            Button("Choose Folder…") { showFolderImporter = true }.archiveButtonStyle(.primary)
            #endif
        case .googleSignIn(let hint, let folderID, let space):
            Group {
                if let hint {
                    Text("Sign in to Google Drive as \(hint) to join the space \"\(space)\".")
                } else {
                    Text("Sign in to Google Drive to join the space \"\(space)\".")
                }
            }
            .font(Typography.bodySM)
            .foregroundStyle(Theme.textSecondary)
            if let followUpError {
                Text(followUpError)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.gold)
            }
            #if !os(tvOS)
            Button(isSigningIn ? String(localized: "Waiting for Google Drive…") : String(localized: "Sign In to Google Drive")) {
                signInToGoogle(hint: hint, folderID: folderID, space: space)
            }
            .archiveButtonStyle(.primary)
            .disabled(isSigningIn)
            #endif
        }
        Button("Later") { close() }.archiveButtonStyle(.ghost)
    }

    private func signInToGoogle(hint: String?, folderID: String?, space: String) {
        #if !os(tvOS)
        isSigningIn = true
        followUpError = nil
        Task {
            do {
                let signIn = try await accounts.signIn(kind: .googleDrive, loginHint: hint, purpose: .sync)
                sync.setLocation(SyncLocation(provider: .googleDrive, space: space, accountKey: signIn.account.key, googleFolderID: folderID))
                followUp = nil
            } catch OAuthError.cancelled {
            } catch {
                followUpError = error.localizedDescription
            }
            isSigningIn = false
        }
        #endif
    }

    private func retry() {
        session.cancel()
        session = HandoffV2Session(role: role)
        prepare()
        if pairing == .host {
            session.startHosting()
        } else if let scanned {
            session.startGuest(code: scanned)
        }
    }
}
