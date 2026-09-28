//
//  AddNetworkSourceView.swift
//  Edendale
//
//  The "Link Source" flow: choose where the videos live, then either fill in
//  a server form (SMB, NFS, SFTP, WebDAV, S3-compatible storage) or sign in
//  to an account (Google Drive, OneDrive, Dropbox), then browse to the folder
//  to index in NetworkFolderPickerView. The only entry point for network
//  sources on every platform, and the only library entry point at all on
//  tvOS (no local file access there).
//
//  Presented as a sheet on every platform, wrapping its own NavigationStack
//  so the steps push inside the sheet. tvOS shows sheets full screen and
//  maps the remote's Menu button to "pop a level, then dismiss", which is
//  exactly the flow this needs — only the usual tvOS trims (no navigation
//  title, no toolbar).
//
//  Nothing is saved until a folder is picked: then the login (if any) goes
//  to the Keychain and indexing starts in the background.
//

import SwiftUI

/// One step of the Link Source flow, pushed as a navigation value.
enum LinkSourceStep: Hashable {
    /// A server form for SMB, NFS, SFTP, WebDAV, or S3.
    case server(MediaSourceKind)
    /// Sign-in or account choice for a cloud provider.
    case account(MediaSourceKind)
}

/// A server login to save once a folder is picked.
struct PendingLogin: Equatable {
    let kind: MediaSourceKind
    /// The server host, or the account key for S3.
    let host: String
    let credential: NetworkCredential
}

struct AddNetworkSourceView: View {
    @Environment(LibraryController.self) private var library
    @Environment(\.dismiss) private var dismiss

    /// Drives the steps: a provider pushes its form or sign-in step, which
    /// pushes the source's top folder, and each subfolder another level.
    @State private var path = NavigationPath()
    @State private var pendingLogin: PendingLogin?

    var body: some View {
        NavigationStack(path: $path) {
            LinkSourceProviderList { kind in
                path.append(kind.isCloudAccount ? LinkSourceStep.account(kind) : LinkSourceStep.server(kind))
            }
            .toolbar {
                #if !os(tvOS)
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", image: .xmark) { dismiss() }
                        .archiveButtonStyle(.ghost)
                }
                #endif
            }
            .navigationDestination(for: LinkSourceStep.self) { step in
                switch step {
                case .server(let kind):
                    ServerSourceForm(kind: kind) { location, login in
                        pendingLogin = login
                        path.append(location)
                    }
                case .account(let kind):
                    CloudAccountStep(kind: kind) { location in
                        pendingLogin = nil
                        path.append(location)
                    }
                }
            }
            .navigationDestination(for: BrowseLocation.self) { location in
                NetworkFolderPickerView(location: location, onIndex: index)
            }
        }
        // A sheet defaults to a small form; this one browses a whole share,
        // so ask for the page size wherever the platform resizes sheets
        // (macOS, iPadOS, visionOS — ignored on iPhone and tvOS).
        .presentationSizing(.form)
        #if os(macOS)
        .frame(minWidth: 640, minHeight: 560)
        #endif
    }

    /// Saves the login, kicks off indexing in the background, and closes the
    /// flow — DownloadedView shows the import progress row.
    private func index(_ location: BrowseLocation) {
        if let pendingLogin, pendingLogin.kind == location.connector.kind {
            do {
                try NetworkCredentialStore.save(
                    pendingLogin.credential,
                    kind: pendingLogin.kind,
                    host: pendingLogin.host
                )
            } catch {
                // Import still works (the connector carries the login in
                // memory); playback and rescans would fail later, so
                // surface it rather than hiding it.
                library.errorMessage = error.localizedDescription
            }
        }
        let connector = location.connector.base
        Task {
            await library.importRemoteFolder(
                connector: connector,
                folderURL: location.url,
                displayName: location.name,
                displayPath: location.displayPath
            )
        }
        // Dismissing the sheet takes its whole navigation stack with it.
        dismiss()
    }
}

// MARK: - Provider list

/// Where the videos live: servers on the local network, then cloud storage.
private struct LinkSourceProviderList: View {
    let onSelect: (MediaSourceKind) -> Void

    private static let networkKinds: [MediaSourceKind] = [.smb, .nfs, .sftp, .webdav]
    private static let cloudKinds: [MediaSourceKind] = [.googleDrive, .oneDrive, .dropbox, .s3]

    var body: some View {
        List {
            Section {
                ForEach(Self.networkKinds) { kind in row(kind) }
            } header: {
                Text("On Your Network").labelCaps()
            }

            Section {
                ForEach(Self.cloudKinds) { kind in row(kind) }
            } header: {
                Text("Cloud Storage").labelCaps()
            } footer: {
                Text("Edendale connects to these services directly from this device. It reads folder listings and the files you play, and never changes anything.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        #if !os(tvOS)
        .scrollContentBackground(.hidden)
        .navigationTitle("Link Source")
        #endif
        .background(Theme.background)
    }

    private func row(_ kind: MediaSourceKind) -> some View {
        let isAvailable = !kind.isCloudAccount || CloudProviders.isConfigured(kind)
        return Button {
            onSelect(kind)
        } label: {
            VStack(alignment: .leading, spacing: 3) {
                Text(kind.displayName)
                    .font(Typography.text(15, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                Text(isAvailable ? kind.linkDescription : String(localized: "Not set up in this build of Edendale."))
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.leading)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            #if os(tvOS)
            .padding(SettingsMetrics.highlightBleed)
            #endif
            .contentShape(Rectangle())
        }
        .disabled(!isAvailable)
        #if os(tvOS)
        // The system's white focus platter would wash out this row's light
        // text; the archive row highlight keeps it legible (as SourceRow).
        .archiveRowStyle()
        .padding(-SettingsMetrics.highlightBleed)
        #endif
        .accessibilityElement(children: .combine)
    }
}

extension MediaSourceKind {
    /// What the provider list says about each kind.
    var linkDescription: String {
        switch self {
        case .local: String(localized: "A folder on this device.")
        case .smb: String(localized: "Shared folders on a Mac, PC, or NAS.")
        case .nfs: String(localized: "Exports from a Linux server or NAS.")
        case .sftp: String(localized: "Any server you can reach over SSH.")
        case .webdav: String(localized: "Nextcloud, ownCloud, Synology, QNAP, pCloud, Koofr, or rclone.")
        case .s3: String(localized: "AWS, Backblaze B2, Cloudflare R2, Wasabi, or MinIO.")
        case .googleDrive: String(localized: "My Drive, files shared with you, and shared drives.")
        case .oneDrive: String(localized: "Personal, work, or school OneDrive.")
        case .dropbox: String(localized: "Your Dropbox folders.")
        }
    }
}
