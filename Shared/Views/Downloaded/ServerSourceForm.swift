//
//  ServerSourceForm.swift
//  Edendale
//
//  The server step of Link Source: an address and, where the protocol has
//  one, a login. Connect checks the server, then pushes its top folder into
//  the folder picker; the login is saved only once a folder is picked.
//
//  SFTP first shows the server's host-key fingerprint for approval (trust
//  on first use) and pins it, so a changed key is refused later. On Apple
//  TV a saved login can come from an iPhone or iPad instead of being typed
//  with the Siri Remote.
//
//  macOS lays the form out as a padded column of bordered fields instead of
//  a list, so Tab steps from field to field, and Return connects once every
//  field is filled.
//

import SwiftUI

/// The inputs a server form can show.
enum ServerField: Hashable {
    case address, port, exportPath, username, password, region, bucket

    /// Fields the user may leave empty.
    var isOptional: Bool {
        self == .port || self == .exportPath || self == .region
    }
}

extension MediaSourceKind {
    /// The server form's fields, in Tab order.
    var serverFields: [ServerField] {
        switch self {
        case .smb, .webdav: [.address, .username, .password]
        case .nfs: [.address, .exportPath]
        case .sftp: [.address, .port, .username, .password]
        case .s3: [.address, .region, .bucket, .username, .password]
        default: []
        }
    }
}

struct ServerSourceForm: View {
    let kind: MediaSourceKind
    /// The source's top folder, and the login to save once a folder is picked.
    let onConnected: (BrowseLocation, PendingLogin?) -> Void

    @State private var values: [ServerField: String] = [:]
    @State private var isConnecting = false
    @State private var errorMessage: String?
    @State private var hostKeyReview: HostKeyReview?

    #if os(macOS)
    @FocusState private var focusedField: ServerField?
    #endif

    /// An SFTP host key waiting for the user's approval.
    struct HostKeyReview: Identifiable {
        let id = UUID()
        let host: String
        let port: Int
        let key: SFTPHostKey
        /// A different key was approved before: the server changed, or
        /// something is impersonating it.
        let replacesPinnedKey: Bool

        /// As `ssh-keygen -l` prints it, to compare with the server's key.
        var displayedKey: String { "\(key.fingerprint) (\(key.typeName))" }
    }

    var body: some View {
        form
            #if !os(tvOS)
            .navigationTitle(kind.displayName)
            #endif
            .background(Theme.background)
            .toolbar {
                #if os(macOS)
                ToolbarItem(placement: .confirmationAction) {
                    connectButton
                }
                #endif
            }
            .alert(
                hostKeyReview?.replacesPinnedKey == true ? "Host Key Changed" : "Verify Host Key",
                isPresented: Binding(get: { hostKeyReview != nil }, set: { if !$0 { hostKeyReview = nil } }),
                presenting: hostKeyReview
            ) { review in
                Button(review.replacesPinnedKey ? "Trust New Key" : "Trust") { trust(review) }
                Button("Cancel", role: .cancel) {}
            } message: { review in
                if review.replacesPinnedKey {
                    Text("The key \(review.host) presents isn't the one you approved before. Only continue if you know the server was reinstalled or its key was changed.\n\n\(review.displayedKey)")
                } else {
                    Text("Check that this matches the server's SSH host key before trusting it.\n\n\(review.displayedKey)")
                }
            }
    }

    // MARK: - Layout

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
                    ForEach(serverFields, id: \.self) { field in
                        macField(field)
                    }
                    if let serverFooter {
                        footnote(serverFooter)
                    }
                }

                if !credentialFields.isEmpty {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Credentials")
                            .labelCaps()
                            .accessibilityAddTraits(.isHeader)
                        ForEach(credentialFields, id: \.self) { field in
                            macField(field)
                        }
                        if let credentialFooter {
                            footnote(credentialFooter)
                        }
                    }
                }

                if let errorMessage {
                    footnote(errorMessage)
                }
            }
            .padding(32)
        }
        .onSubmit(submit)
        // Typing goes straight into the address. Set once on appearance:
        // a default focus would also re-apply after each Return.
        .onAppear { focusedField = .address }
    }

    private func macField(_ field: ServerField) -> some View {
        SourceField(isFocused: focusedField == field) {
            focusedField = field
        } input: {
            input(for: field)
                .focused($focusedField, equals: field)
        }
    }

    private func footnote(_ text: String) -> some View {
        Text(text)
            .font(Typography.bodySM)
            .foregroundStyle(Theme.textSecondary)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// Return connects once every required field is filled — a guest SMB
    /// connection stays one click on Connect — and otherwise moves to the
    /// first empty field.
    private func submit() {
        guard !isConnecting else { return }
        let emptyField = kind.serverFields.first { !$0.isOptional && text($0).isEmpty }
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
                LabeledContent("Protocol", value: kind.displayName)
                ForEach(serverFields, id: \.self) { field in
                    input(for: field)
                }
            } header: {
                Text("Server").labelCaps()
            } footer: {
                if let serverFooter {
                    Text(serverFooter)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                }
            }

            if !credentialFields.isEmpty {
                Section {
                    ForEach(credentialFields, id: \.self) { field in
                        input(for: field)
                    }
                } header: {
                    Text("Credentials").labelCaps()
                } footer: {
                    if let credentialFooter {
                        Text(credentialFooter)
                            .font(Typography.bodySM)
                            .foregroundStyle(Theme.textSecondary)
                    }
                }
            }

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                }
            }

            Section {
                connectButton
                #if os(tvOS)
                if kind.usesServerLogin {
                    HandoffButton(kind: kind, title: String(localized: "Use a Login from iPhone or iPad")) { response in
                        guard let login = response.login else { throw AccountHandoff.HandoffError.malformedMessage }
                        try apply(login)
                        connect()
                    } onError: { message in
                        errorMessage = message
                    }
                }
                #endif
            }
        }
        #if !os(tvOS)
        .scrollContentBackground(.hidden)
        #endif
    }
    #endif

    private var connectButton: some View {
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
        .disabled(isConnecting || !canConnect)
    }

    // MARK: - Fields

    private var serverFields: [ServerField] {
        kind.serverFields.filter { $0 != .username && $0 != .password }
    }

    private var credentialFields: [ServerField] {
        kind.serverFields.filter { $0 == .username || $0 == .password }
    }

    @ViewBuilder
    private func input(for field: ServerField) -> some View {
        let binding = Binding(get: { values[field] ?? "" }, set: { values[field] = $0 })
        if field == .password {
            SecureField(title(for: field), text: binding, prompt: Text(prompt(for: field)))
        } else {
            TextField(title(for: field), text: binding, prompt: Text(prompt(for: field)))
                .noAutoCorrections()
                .modify { view in
                    #if os(iOS) || os(visionOS)
                    switch field {
                    case .port: view.keyboardType(.numberPad)
                    case .address where kind == .webdav || kind == .s3: view.keyboardType(.URL)
                    default: view
                    }
                    #else
                    view
                    #endif
                }
        }
    }

    private func title(for field: ServerField) -> String {
        switch field {
        case .address:
            switch kind {
            case .webdav: String(localized: "Address")
            case .s3: String(localized: "Endpoint")
            default: String(localized: "Server")
            }
        case .port: String(localized: "Port")
        case .exportPath: String(localized: "Export Path")
        case .username: kind == .s3 ? String(localized: "Access Key ID") : String(localized: "Username")
        case .password: kind == .s3 ? String(localized: "Secret Access Key") : String(localized: "Password")
        case .region: String(localized: "Region")
        case .bucket: String(localized: "Bucket")
        }
    }

    private func prompt(for field: ServerField) -> String {
        switch field {
        case .address:
            switch kind {
            case .webdav: String(localized: "https://cloud.example.com/remote.php/dav/files/you/")
            case .s3: String(localized: "https://s3.us-east-1.amazonaws.com")
            default: String(localized: "nas.local or 192.168.1.1")
            }
        case .port: "22"
        case .exportPath: String(localized: "/volume1/video (optional)")
        case .username: kind == .s3 ? String(localized: "Access Key ID") : String(localized: "Username")
        case .password: kind == .s3 ? String(localized: "Secret Access Key") : String(localized: "Password")
        case .region: String(localized: "us-east-1, or auto for Cloudflare R2")
        case .bucket: String(localized: "Bucket name")
        }
    }

    private var serverFooter: String? {
        switch kind {
        case .nfs:
            String(localized: "Leave the export path empty to list the server's exports. Apple devices connect from an unprivileged port, so the export needs the “insecure” option.")
        case .webdav:
            String(localized: "Use https:// for servers outside your home network. Plain http:// works only for local addresses.")
        case .sftp:
            String(localized: "The first time Edendale connects, it shows the server's host key for you to approve.")
        default:
            nil
        }
    }

    private var credentialFooter: String? {
        switch kind {
        case .smb:
            String(localized: "Leave both empty to connect as guest. The password is stored only in your Keychain.")
        case .s3:
            String(localized: "Use a key that can only read this bucket. The secret is stored only in your Keychain.")
        default:
            String(localized: "The password is stored only in your Keychain.")
        }
    }

    private func text(_ field: ServerField) -> String {
        (values[field] ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canConnect: Bool {
        switch kind {
        case .sftp: !text(.address).isEmpty && !text(.username).isEmpty
        case .s3: !text(.address).isEmpty && !text(.bucket).isEmpty && !text(.username).isEmpty && !(values[.password] ?? "").isEmpty
        default: !text(.address).isEmpty
        }
    }

    /// Username and password as typed; `nil` for a guest connection.
    private var enteredCredential: NetworkCredential? {
        let user = text(.username)
        let password = values[.password] ?? ""
        guard !user.isEmpty || !password.isEmpty else { return nil }
        return NetworkCredential(username: user, password: password)
    }

    // MARK: - Connecting

    private func connect() {
        guard !isConnecting else { return }
        isConnecting = true
        errorMessage = nil
        Task {
            do {
                let (location, login) = try await makeLocation()
                onConnected(location, login)
            } catch let review as HostKeyReviewNeeded {
                hostKeyReview = review.review
            } catch is CancellationError {
            } catch {
                errorMessage = error.localizedDescription
            }
            isConnecting = false
        }
    }

    private struct HostKeyReviewNeeded: Error {
        let review: HostKeyReview
    }

    private func trust(_ review: HostKeyReview) {
        do {
            try HostKeyStore.pin(review.key.fingerprint, host: review.host, port: review.port)
            connect()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Checks the server and returns its top folder with the login to save.
    private func makeLocation() async throws -> (BrowseLocation, PendingLogin?) {
        switch kind {
        case .smb:
            guard let connector = SMBConnector(host: text(.address), credential: enteredCredential) else {
                throw ConnectorError.invalidAddress
            }
            try await connector.validate()
            let login = enteredCredential.map { PendingLogin(kind: .smb, host: connector.host, credential: $0) }
            return (BrowseLocation(connector: connector, url: connector.root, name: connector.host), login)

        case .nfs:
            guard let connector = NFSConnector(host: text(.address), exportPath: text(.exportPath)) else {
                throw ConnectorError.invalidAddress
            }
            try await connector.validate()
            let trail = [connector.host] + (connector.exportPath ?? "").split(separator: "/").map(String.init)
            return (BrowseLocation(connector: connector, url: connector.root, name: trail.last ?? connector.host, trail: trail), nil)

        case .sftp:
            guard let credential = enteredCredential,
                  let probe = SFTPConnector(host: text(.address), port: Int(text(.port)), credential: credential)
            else { throw ConnectorError.invalidAddress }
            let hostKey = try await SFTPConnector.fetchHostKey(host: probe.host, port: probe.port)
            let pinned = HostKeyStore.pinnedFingerprint(host: probe.host, port: probe.port)
            guard pinned == hostKey.fingerprint else {
                throw HostKeyReviewNeeded(review: HostKeyReview(
                    host: probe.host, port: probe.port, key: hostKey, replacesPinnedKey: pinned != nil
                ))
            }
            let home = try await probe.homeDirectory()
            guard let connector = SFTPConnector(host: probe.host, port: probe.port, credential: credential, startPath: home) else {
                throw ConnectorError.invalidAddress
            }
            let segments = home.split(separator: "/").map(String.init)
            let location = BrowseLocation(
                connector: connector, url: connector.root, name: segments.last ?? probe.host, trail: [probe.host] + segments
            )
            // Logins are keyed by host; the port goes with the login so a
            // handoff to Apple TV reaches the same server.
            var login = credential
            login.port = probe.port
            return (location, PendingLogin(kind: .sftp, host: probe.host, credential: login))

        case .webdav:
            guard let connector = WebDAVConnector(address: text(.address), credential: enteredCredential),
                  let host = connector.root.host()
            else { throw ConnectorError.invalidAddress }
            try await connector.validate()
            let segments = SourceURL.pathSegments(of: connector.root)
            let location = BrowseLocation(
                connector: connector, url: connector.root, name: segments.last ?? host, trail: [host] + segments
            )
            return (location, enteredCredential.map { PendingLogin(kind: .webdav, host: host, credential: $0) })

        case .s3:
            var endpointText = text(.address)
            if !endpointText.contains("://") { endpointText = "https://" + endpointText }
            guard let endpoint = URL(string: endpointText), endpoint.host() != nil,
                  ["http", "https"].contains(endpoint.scheme?.lowercased() ?? "")
            else { throw ConnectorError.invalidAddress }
            let bucket = text(.bucket)
            let region = text(.region).isEmpty ? "us-east-1" : text(.region)
            let configuration = S3Configuration(
                endpoint: endpoint,
                region: region,
                bucket: bucket,
                usesPathStyle: S3Connector.defaultUsesPathStyle(endpoint: endpoint, bucket: bucket)
            )
            let credential = NetworkCredential(username: text(.username), password: values[.password] ?? "", s3: configuration)
            let connector = S3Connector(configuration: configuration, credential: credential)
            try await connector.validate()
            let location = BrowseLocation(connector: connector, url: connector.root, name: bucket)
            return (location, PendingLogin(kind: .s3, host: connector.accountKey, credential: credential))

        default:
            throw ConnectorError.invalidAddress
        }
    }

    /// Fills the form from a login another device handed over.
    private func apply(_ login: AccountHandoff.Login) throws {
        guard login.kind == kind else { throw AccountHandoff.HandoffError.wrongKind }
        values[.username] = login.credential.username
        values[.password] = login.credential.password
        if let s3 = login.credential.s3 {
            values[.address] = s3.endpoint.absoluteString
            values[.region] = s3.region
            values[.bucket] = s3.bucket
        } else {
            values[.address] = login.host
        }
        if let port = login.port {
            values[.port] = String(port)
        }
        // The key was approved on the other device.
        if kind == .sftp, let fingerprint = login.hostKeyFingerprint {
            try HostKeyStore.pin(fingerprint, host: login.host, port: login.port ?? SFTPConnector.defaultPort)
        }
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

extension View {
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
