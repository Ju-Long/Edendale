//
//  AddNetworkSourceView.swift
//  Edendale
//
//  "Link Source" flow: enter a server address and credentials, connect,
//  then browse the share in NetworkFolderPickerView to pick the folder to
//  index. The only entry point for network sources on every platform, and
//  the only library entry point at all on tvOS (no local file access there).
//
//  Presented as a sheet on every platform, wrapping its own NavigationStack
//  so the browse levels push inside the sheet. tvOS shows sheets full
//  screen and maps the remote's Menu button to "pop a level, then dismiss",
//  which is exactly the flow this needs — no separate tvOS presentation
//  path, only the usual tvOS trims (no navigation title, no toolbar).
//

import SwiftUI

struct AddNetworkSourceView: View {
    @Environment(LibraryController.self) private var library
    @Environment(\.dismiss) private var dismiss

    @State private var host = ""
    @State private var username = ""
    @State private var password = ""
    @State private var isConnecting = false
    @State private var errorMessage: String?

    /// Drives the browse levels: connecting appends the share root, and each
    /// subfolder in NetworkFolderPickerView appends another BrowseLocation.
    @State private var path = NavigationPath()

    var body: some View {
        NavigationStack(path: $path) {
            content
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

    // MARK: - Form

    private var content: some View {
        List {
            Section {
                LabeledContent("Protocol", value: MediaSourceKind.smb.displayName)
                TextField("Server", text: $host, prompt: Text("nas.local or 192.168.1.1"))
                    .noAutoCorrections()
            } header: {
                Text("Server").labelCaps()
            }

            Section {
                TextField("Username", text: $username, prompt: Text("Username"))
                    .noAutoCorrections()
                SecureField("Password", text: $password, prompt: Text("Password"))
            } header: {
                Text("Credentials").labelCaps()
            } footer: {
                Text("Leave both empty to connect as guest. The password is stored only in your Keychain.")
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

            #if !os(macOS)
            Section {
                Button(action: connect) {
                    if isConnecting {
                        HStack(spacing: 12) {
                            ProgressView().tint(Theme.gold)
                            Text("Connecting…")
                        }
                    } else {
                        Label("Connect", image: .link)
                    }
                }
                .disabled(isConnecting || host.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            #endif
        }
        #if !os(tvOS)
        .scrollContentBackground(.hidden)
        .navigationTitle("Link Source")
        #endif
        .background(Theme.background)
        .toolbar {
            #if !os(tvOS)
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel", image: .xmark) { dismiss() }
                    .archiveButtonStyle(.ghost)
            }
            #endif

            #if os(macOS)
            ToolbarItem(placement: .confirmationAction) {
                Button(action: connect) {
                    if isConnecting {
                        HStack(spacing: 12) {
                            ProgressView().tint(Theme.gold)
                            Text("Connecting…")
                        }
                    } else {
                        Label("Connect", image: .link)
                    }
                }
                .disabled(isConnecting || host.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            #endif
        }
    }

    // MARK: - Actions

    private var enteredCredential: NetworkCredential? {
        let user = username.trimmingCharacters(in: .whitespaces)
        guard !user.isEmpty || !password.isEmpty else { return nil }
        return NetworkCredential(username: user, password: password)
    }

    private func connect() {
        guard let connector = SMBConnector(host: host, credential: enteredCredential) else {
            errorMessage = ConnectorError.invalidAddress.localizedDescription
            return
        }
        isConnecting = true
        errorMessage = nil
        Task {
            do {
                try await connector.validate()
                path.append(BrowseLocation(
                    connector: connector,
                    url: connector.root,
                    name: connector.host
                ))
            } catch {
                errorMessage = error.localizedDescription
            }
            isConnecting = false
        }
    }

    /// Saves the credential, kicks off indexing in the background, and
    /// closes the flow — DownloadedView shows the import progress row.
    private func index(folderURL: URL, connector: SMBConnector, displayName: String) {
        if let credential = connector.credential {
            do {
                try NetworkCredentialStore.save(credential, host: connector.host)
            } catch {
                // Import still works (the connector carries the credential in
                // memory); playback and rescans would prompt-fail later, so
                // surface it rather than hiding it.
                library.errorMessage = error.localizedDescription
            }
        }
        Task {
            await library.importRemoteFolder(
                connector: connector,
                folderURL: folderURL,
                displayName: displayName
            )
        }
        // Dismissing the sheet takes its whole navigation stack with it.
        dismiss()
    }
}

// MARK: - Field helpers

private extension View {
    /// Server addresses and usernames must never be autocorrected.
    @ViewBuilder
    func noAutoCorrections() -> some View {
        #if os(macOS)
        self.autocorrectionDisabled()
        #else
        self.autocorrectionDisabled()
            .textInputAutocapitalization(.never)
        #endif
    }
}
