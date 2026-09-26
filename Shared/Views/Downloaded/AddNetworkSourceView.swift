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
//  macOS lays the form out as a padded column of bordered fields instead
//  of a list, so Tab steps from field to field, and Return connects once
//  every field is filled.
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

    #if os(macOS)
    private enum Field: Hashable {
        case server, username, password
    }

    @FocusState private var focusedField: Field?
    #endif

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
        form
        #if !os(tvOS)
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

    #if os(macOS)
    /// A padded column rather than a list: inside a list's table rows, Tab
    /// leaves the field instead of moving to the next one.
    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Server")
                        .labelCaps()
                        .accessibilityAddTraits(.isHeader)
                    HStack {
                        Text("Protocol")
                            .foregroundStyle(Theme.textPrimary)
                        Spacer()
                        Text(MediaSourceKind.smb.displayName)
                            .foregroundStyle(Theme.textSecondary)
                    }
                    .font(Typography.bodyLG)
                    .accessibilityElement(children: .combine)
                    SourceField(isFocused: focusedField == .server) {
                        focusedField = .server
                    } input: {
                        TextField("Server", text: $host, prompt: Text("nas.local or 192.168.1.1"))
                            .noAutoCorrections()
                            .focused($focusedField, equals: .server)
                    }
                }

                VStack(alignment: .leading, spacing: 14) {
                    Text("Credentials")
                        .labelCaps()
                        .accessibilityAddTraits(.isHeader)
                    SourceField(isFocused: focusedField == .username) {
                        focusedField = .username
                    } input: {
                        TextField("Username", text: $username, prompt: Text("Username"))
                            .noAutoCorrections()
                            .focused($focusedField, equals: .username)
                    }
                    SourceField(isFocused: focusedField == .password) {
                        focusedField = .password
                    } input: {
                        SecureField("Password", text: $password, prompt: Text("Password"))
                            .focused($focusedField, equals: .password)
                    }
                    Text("Leave both empty to connect as guest. The password is stored only in your Keychain.")
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                if let errorMessage {
                    Text(errorMessage)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(32)
        }
        .onSubmit(submit)
        // Typing goes straight into the address. Set once on appearance:
        // a default focus would also re-apply after each Return.
        .onAppear { focusedField = .server }
    }

    /// Return connects once every field is filled — a guest connection
    /// stays one click on Connect — and otherwise moves to the first empty
    /// field.
    private func submit() {
        guard !isConnecting else { return }
        let emptyField: Field? = if host.trimmingCharacters(in: .whitespaces).isEmpty {
            .server
        } else if username.trimmingCharacters(in: .whitespaces).isEmpty {
            .username
        } else if password.isEmpty {
            .password
        } else {
            nil
        }
        guard let emptyField else {
            connect()
            return
        }
        // The field is still finishing its Return; a focus change made now
        // is undone when it ends editing, so move on the next turn instead.
        Task { @MainActor in focusedField = emptyField }
    }
    #else
    private var form: some View {
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
        }
        #if !os(tvOS)
        .scrollContentBackground(.hidden)
        #endif
    }
    #endif

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

#if os(macOS)
/// A text input drawn as an archive field: padded on a dim surface inside a
/// hairline border that brightens on hover and turns gold while the field
/// has focus. A click on the padding focuses the field too.
private struct SourceField<Input: View>: View {
    let isFocused: Bool
    let focus: () -> Void
    @ViewBuilder let input: Input

    @State private var isHovering = false

    var body: some View {
        input
            .textFieldStyle(.plain)
            .font(Typography.bodyLG)
            .foregroundStyle(Theme.textPrimary)
            // The gold border is the focus indicator.
            .focusEffectDisabled()
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background {
                RoundedRectangle(cornerRadius: Theme.Radius.soft)
                    .fill(Theme.surfaceLow)
                    .onTapGesture(perform: focus)
            }
            .overlay {
                RoundedRectangle(cornerRadius: Theme.Radius.soft)
                    .strokeBorder(borderColor, lineWidth: 1)
                    .allowsHitTesting(false)
            }
            .onHover { isHovering = $0 }
    }

    private var borderColor: Color {
        if isFocused { return Theme.gold }
        return isHovering ? Theme.outlineBright : Theme.outline
    }
}
#endif

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
