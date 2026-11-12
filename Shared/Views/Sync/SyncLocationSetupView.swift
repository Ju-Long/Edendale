//
//  SyncLocationSetupView.swift
//  Edendale
//
//  Choosing where sync files live (FEATURES-27.1.md §5.2): a folder from
//  Files or Finder, the app's own iCloud Drive folder, a linked Google
//  Drive, OneDrive, or Dropbox account (signing in if needed, and again
//  when an account predates the write scopes; nothing to choose there, not
//  even in Google's picker, K.8), or a folder on a WebDAV,
//  SMB, or SFTP server through the Link Source forms. Then the space name.
//  Nothing is saved until the last step; the chosen login goes to the
//  Keychain with it. Apple TV offers only the account and server kinds.
//

import SwiftUI
import UniformTypeIdentifiers

struct SyncLocationSetupView: View {
    @Environment(SyncCoordinator.self) private var sync
    @Environment(CloudAccountStore.self) private var accounts
    @Environment(\.dismiss) private var dismiss

    /// What has been chosen so far, before the space name.
    struct Draft: Hashable {
        var provider: SyncLocation.Provider
        var folderBookmark: Data?
        var folderPath: String?
        var accountKey: String?
        var serverURL: URL?
        var pendingLogin: PendingLogin?
    }

    enum Step: Hashable {
        case account(MediaSourceKind)
        case server(MediaSourceKind)
        case spaceName(Draft)
    }

    @State private var path = NavigationPath()
    @State private var showImporter = false
    @State private var errorMessage: String?
    @State private var pendingLogin: PendingLogin?

    var body: some View {
        NavigationStack(path: $path) {
            providerList
                .toolbar {
                    #if !os(tvOS)
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel", image: .xmark) { dismiss() }
                            .archiveButtonStyle(.ghost)
                    }
                    #endif
                }
                .navigationDestination(for: Step.self) { step in
                    switch step {
                    case .account(let kind):
                        CloudAccountStep(kind: kind, purpose: .sync) { location in
                            guard let key = SourceURL.credentialHost(of: location.url) else { return }
                            chooseAccount(kind: kind, key: key)
                        } onGoogleDriveReady: { signIn in
                            chooseAccount(kind: .googleDrive, key: signIn.account.key)
                        }
                    case .server(let kind):
                        ServerSourceForm(kind: kind) { location, login in
                            pendingLogin = login
                            path.append(location)
                        }
                    case .spaceName(let draft):
                        SpaceNameStep(draft: draft) { location, login in
                            finish(location, login: login)
                        }
                    }
                }
                .navigationDestination(for: BrowseLocation.self) { location in
                    NetworkFolderPickerView(location: location, purpose: .sync) { picked in
                        guard let provider = SyncLocation.Provider(mediaSourceKind: picked.connector.kind) else { return }
                        path.append(Step.spaceName(Draft(
                            provider: provider, serverURL: picked.url, pendingLogin: pendingLogin
                        )))
                    }
                }
        }
        .presentationSizing(.form)
        #if os(macOS)
        .frame(minWidth: 640, minHeight: 560)
        #endif
        #if !os(tvOS)
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.folder]) { result in
            guard case .success(let url) = result else { return }
            chooseFolder(url)
        }
        #endif
    }

    // MARK: - Providers

    private var providerList: some View {
        List {
            #if !os(tvOS)
            Section {
                row(.folder, detail: String(localized: "Any folder, including iCloud Drive and folders other cloud apps show in Files."))
                row(.iCloudDrive, detail: String(localized: "The Edendale folder in your iCloud Drive, visible in Files and Finder."))
            } header: {
                Text("On This Device").labelCaps()
            }
            #endif
            Section {
                row(.googleDrive, detail: String(localized: "An Edendale folder in your Google Drive, reachable from every platform."))
                row(.oneDrive, detail: String(localized: "The Apps/Edendale folder in your OneDrive."))
                row(.dropbox, detail: String(localized: "An Edendale folder in your Dropbox."))
            } header: {
                Text("Cloud Storage").labelCaps()
            }
            Section {
                row(.webdav, detail: String(localized: "A folder on a WebDAV server you choose."))
                row(.smb, detail: String(localized: "A folder on an SMB share you choose."))
                row(.sftp, detail: String(localized: "A folder on an SSH server you choose."))
            } header: {
                Text("On Your Network").labelCaps()
            } footer: {
                Text("Edendale writes one small JSON file per device into an Edendale folder there, and reads the files your other devices wrote. Anyone who can open that folder can read your watch history.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                }
            }
        }
        #if !os(tvOS)
        .scrollContentBackground(.hidden)
        .navigationTitle("Sync Location")
        #endif
        .background(Theme.background)
    }

    private func row(_ provider: SyncLocation.Provider, detail: String) -> some View {
        let kind = provider.mediaSourceKind
        let isAvailable = kind.map { !$0.isCloudAccount || CloudProviders.isConfigured($0) } ?? true
        return Button {
            choose(provider)
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text(provider.displayName)
                    .font(Typography.text(15, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                Text(isAvailable ? detail : String(localized: "Not set up in this build of Edendale."))
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            #if os(tvOS)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(SettingsMetrics.highlightBleed)
            #endif
        }
        .disabled(!isAvailable)
        #if os(tvOS)
        .archiveRowStyle()
        .padding(-SettingsMetrics.highlightBleed)
        #endif
    }

    private func choose(_ provider: SyncLocation.Provider) {
        errorMessage = nil
        switch provider {
        case .folder:
            showImporter = true
        case .iCloudDrive:
            guard FileManager.default.ubiquityIdentityToken != nil else {
                errorMessage = String(localized: "Sign in to iCloud and turn on iCloud Drive to use this location.")
                return
            }
            path.append(Step.spaceName(Draft(provider: .iCloudDrive)))
        case .googleDrive, .oneDrive, .dropbox:
            path.append(Step.account(provider.mediaSourceKind!))
        case .webdav, .smb, .sftp:
            path.append(Step.server(provider.mediaSourceKind!))
        }
    }

    private func chooseFolder(_ url: URL) {
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        guard let bookmark = try? url.bookmarkData(options: .securityScoped, includingResourceValuesForKeys: nil, relativeTo: nil) else {
            errorMessage = String(localized: "Edendale couldn't keep access to that folder. Choose it again.")
            return
        }
        path.append(Step.spaceName(Draft(provider: .folder, folderBookmark: bookmark, folderPath: url.path)))
    }

    private func chooseAccount(kind: MediaSourceKind, key: String) {
        guard let provider = SyncLocation.Provider(mediaSourceKind: kind) else { return }
        if let account = accounts.vault.account(kind: kind, key: key), !CloudProviders.grantsSyncWrites(account) {
            #if os(tvOS)
            errorMessage = String(localized: "\(kind.displayName) needs to be signed in again before Edendale can write sync files there. Sign in once more from Link Source.")
            #else
            // The account step already signed in again: the grant came back
            // without the write scope.
            errorMessage = String(localized: "\(kind.displayName) didn't allow Edendale to write its sync files. Sign in again and allow that access.")
            #endif
            path = NavigationPath()
            return
        }
        path.append(Step.spaceName(Draft(provider: provider, accountKey: key)))
    }

    private func finish(_ location: SyncLocation, login: PendingLogin?) {
        if let login {
            do {
                try NetworkCredentialStore.save(login.credential, kind: login.kind, host: login.host)
            } catch {
                errorMessage = error.localizedDescription
                return
            }
        }
        sync.setLocation(location)
        dismiss()
    }
}

// MARK: - Space name

private struct SpaceNameStep: View {
    let draft: SyncLocationSetupView.Draft
    let onDone: (SyncLocation, PendingLogin?) -> Void

    @Environment(SyncCoordinator.self) private var sync
    @State private var name = ""
    @State private var errorMessage: String?

    var body: some View {
        List {
            Section {
                TextField("Family", text: $name)
                    .noAutoCorrections()
                    #if os(iOS)
                    .submitLabel(.done)
                    #endif
                    .onSubmit(start)
            } header: {
                Text("Space Name").labelCaps()
            } footer: {
                Text("Devices that use the same location and the same space name share one history. Use one space per person; the name becomes a folder, so it can't contain / \\ : * ? \" < > |.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                }
            }
            Section {
                Button("Start Syncing", action: start)
                    .archiveButtonStyle(.primary)
                    .disabled(SyncFileFormat.normalizedSpaceName(name) == nil)
            } footer: {
                Text("Anyone who can open \(draft.provider.displayName) can read your watch history. Logins never go into the folder.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        #if !os(tvOS)
        .scrollContentBackground(.hidden)
        .navigationTitle(draft.provider.displayName)
        #endif
        .background(Theme.background)
        .onAppear {
            if name.isEmpty { name = sync.location?.space ?? "" }
        }
    }

    private func start() {
        guard let space = SyncFileFormat.normalizedSpaceName(name) else {
            errorMessage = String(localized: "Enter a space name without path characters.")
            return
        }
        onDone(SyncLocation(
            provider: draft.provider,
            space: space,
            folderBookmark: draft.folderBookmark,
            folderPath: draft.folderPath,
            accountKey: draft.accountKey,
            serverURL: draft.serverURL
        ), draft.pendingLogin)
    }
}
