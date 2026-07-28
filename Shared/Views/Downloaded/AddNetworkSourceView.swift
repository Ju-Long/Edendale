//
//  AddNetworkSourceView.swift
//  Edendale
//
//  "Link Source" flow: enter a server address and credentials, connect,
//  then browse the share in NetworkFolderPickerView to pick the folder to
//  index. The only entry point for network sources on every platform, and
//  the only library entry point at all on tvOS (no local file access there).
//
//  Presentation differs by platform: iOS/macOS/visionOS show this as a
//  sheet wrapping its own NavigationStack; tvOS pushes it onto
//  DownloadedView's stack (sheets are full-screen takeovers there), so the
//  tvOS body attaches its destinations to the enclosing stack instead of
//  nesting one.
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

    #if os(tvOS)
    /// Root of the validated share; setting it pushes the folder picker
    /// onto the enclosing NavigationStack.
    @State private var rootLocation: BrowseLocation?
    @State private var isBrowsing = false
    #else
    @State private var path = NavigationPath()
    #endif

    var body: some View {
        #if os(tvOS)
        content
            .navigationDestination(for: BrowseLocation.self) { location in
                NetworkFolderPickerView(location: location, onIndex: index)
            }
            .navigationDestination(isPresented: $isBrowsing) {
                if let rootLocation {
                    NetworkFolderPickerView(location: rootLocation, onIndex: index)
                }
            }
        #else
        NavigationStack(path: $path) {
            content
                .navigationDestination(for: BrowseLocation.self) { location in
                    NetworkFolderPickerView(location: location, onIndex: index)
                }
        }
        #if os(macOS)
        .frame(minWidth: 520, minHeight: 480)
        #endif
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
                let location = BrowseLocation(connector: connector, url: connector.root, name: connector.host)
                #if os(tvOS)
                rootLocation = location
                isBrowsing = true
                #else
                path.append(location)
                #endif
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
        #if os(tvOS)
        // Pop the picker levels first, then the form itself.
        isBrowsing = false
        #endif
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
