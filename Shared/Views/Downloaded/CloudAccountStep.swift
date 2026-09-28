//
//  CloudAccountStep.swift
//  Edendale
//
//  The account step of Link Source for Google Drive, OneDrive, and Dropbox:
//  continue with an account already linked (one sign-in covers every device
//  sharing the iCloud Keychain), or sign in. On iPhone, iPad, Mac, and
//  Vision Pro that is the provider's page in ASWebAuthenticationSession.
//  Apple TV has no web sign-in: it gets the account from a nearby iPhone or
//  iPad (AccountHandoff), or for OneDrive also from a code approved on any
//  other device.
//

import SwiftUI
#if os(tvOS)
import DeviceDiscoveryUI
import Network
#endif

struct CloudAccountStep: View {
    let kind: MediaSourceKind
    /// The account's top folder, to browse from.
    let onReady: (BrowseLocation) -> Void

    @Environment(CloudAccountStore.self) private var store
    @State private var isSigningIn = false
    @State private var errorMessage: String?
    @State private var deviceAuthorization: DeviceAuthorization?
    @State private var deviceSignIn: Task<Void, Never>?

    var body: some View {
        List {
            let accounts = store.accounts(of: kind)
            if !accounts.isEmpty {
                Section {
                    ForEach(accounts) { account in
                        Button {
                            open(account)
                        } label: {
                            Label(account.label, image: .circleUserFill)
                                .foregroundStyle(Theme.textPrimary)
                                #if os(tvOS)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(SettingsMetrics.highlightBleed)
                                #endif
                        }
                        #if os(tvOS)
                        // Keeps the light label legible when focused (see
                        // LinkSourceProviderList).
                        .archiveRowStyle()
                        .padding(-SettingsMetrics.highlightBleed)
                        #endif
                    }
                } header: {
                    Text("Linked Accounts").labelCaps()
                } footer: {
                    Text(accountsFooter)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                }
            }

            Section {
                signInActions
            } header: {
                Text(accounts.isEmpty ? String(localized: "Sign In") : String(localized: "Another Account")).labelCaps()
            } footer: {
                Text(privacyNote)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }

            if let deviceAuthorization {
                Section {
                    deviceCodeView(deviceAuthorization)
                } header: {
                    Text("Approve on Another Device").labelCaps()
                }
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
        .navigationTitle(kind.displayName)
        #endif
        .background(Theme.background)
        .onAppear { store.reload() }
        .onDisappear { deviceSignIn?.cancel() }
    }

    // MARK: - Actions

    @ViewBuilder
    private var signInActions: some View {
        #if os(tvOS)
        HandoffButton(kind: kind, title: String(localized: "Continue on iPhone or iPad")) { response in
            guard let account = response.account else { throw AccountHandoff.HandoffError.malformedMessage }
            open(try await store.adoptHandedOffAccount(account.cloudAccount))
        } onError: { message in
            errorMessage = message
        }
        if CloudProviders.supportsDeviceCode(kind) {
            Button {
                startDeviceSignIn()
            } label: {
                Label("Sign In with a Code", image: .link)
            }
            .disabled(deviceSignIn != nil)
        }
        #else
        Button {
            signIn()
        } label: {
            if isSigningIn {
                HStack(spacing: 12) {
                    ProgressView().tint(Theme.gold)
                    Text("Waiting for \(kind.displayName)…")
                }
            } else {
                Label("Sign In to \(kind.displayName)", image: .link)
            }
        }
        .disabled(isSigningIn)
        #endif
    }

    private var accountsFooter: String {
        #if os(tvOS)
        String(localized: "Accounts on Apple TV stay on this Apple TV.")
        #else
        String(localized: "Accounts are stored in your iCloud Keychain and shared with your other devices, except Apple TV.")
        #endif
    }

    private var privacyNote: String {
        switch kind {
        case .googleDrive:
            String(localized: "Edendale asks Google for read-only access to your Drive. It connects from this device only, reads folder listings and the videos you play, and never uploads or changes anything.")
        default:
            String(localized: "Edendale asks \(kind.displayName) for read-only access. It connects from this device only, reads folder listings and the videos you play, and never uploads or changes anything.")
        }
    }

    private func open(_ account: CloudAccount) {
        guard let connector = ConnectorFactory.connector(for: account) else {
            errorMessage = ConnectorError.signInRequired(provider: kind.displayName).localizedDescription
            return
        }
        errorMessage = nil
        onReady(BrowseLocation(connector: connector, url: connector.root, name: kind.displayName))
    }

    #if !os(tvOS)
    private func signIn() {
        isSigningIn = true
        errorMessage = nil
        Task {
            do {
                open(try await store.signIn(kind: kind))
            } catch OAuthError.cancelled {
            } catch {
                errorMessage = error.localizedDescription
            }
            isSigningIn = false
        }
    }
    #endif

    // MARK: - Device code

    private func startDeviceSignIn() {
        errorMessage = nil
        deviceSignIn = Task {
            defer {
                deviceAuthorization = nil
                deviceSignIn = nil
            }
            do {
                let authorization = try await store.startDeviceSignIn(kind: kind)
                deviceAuthorization = authorization
                let account = try await store.finishDeviceSignIn(kind: kind, authorization: authorization)
                open(account)
            } catch is CancellationError {
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    @ViewBuilder
    private func deviceCodeView(_ authorization: DeviceAuthorization) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("On your phone or computer, open \(authorization.verificationURI.host() ?? authorization.verificationURI.absoluteString)\(authorization.verificationURI.path()) and enter this code:")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Text(authorization.userCode)
                .font(Typography.display(56))
                .foregroundStyle(Theme.gold)
                .accessibilityLabel("Code \(authorization.userCode.map(String.init).joined(separator: " "))")
            QRCodeView(
                url: authorization.verificationURIComplete ?? authorization.verificationURI,
                accessibilityLabel: String(localized: "\(kind.displayName) sign-in QR code")
            )
            HStack(spacing: 12) {
                ProgressView().tint(Theme.gold)
                Text("Waiting for approval…")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
            Button("Cancel") {
                deviceSignIn?.cancel()
            }
            .archiveButtonStyle(.ghost)
        }
        .padding(.vertical, 8)
    }
}

#if os(tvOS)
/// "Continue on iPhone or iPad": presents DeviceDiscoveryUI's device picker,
/// sends an AccountHandoff request to the device the user chooses, and
/// passes on the approved response.
struct HandoffButton: View {
    let kind: MediaSourceKind
    let title: String
    let onResponse: (AccountHandoff.Response) async throws -> Void
    let onError: (String) -> Void

    @Environment(\.devicePickerSupports) private var devicePickerSupports
    @State private var isPickerPresented = false
    @State private var isWaiting = false

    private var descriptor: NWBrowser.Descriptor {
        .applicationService(name: AccountHandoff.serviceName)
    }

    var body: some View {
        if devicePickerSupports(descriptor, parameters: { .applicationService }) {
            Button {
                isPickerPresented = true
            } label: {
                if isWaiting {
                    HStack(spacing: 12) {
                        ProgressView().tint(Theme.gold)
                        Text("Approve on your iPhone or iPad…")
                    }
                } else {
                    Label(title, image: .link)
                }
            }
            .disabled(isWaiting)
            .fullScreenCover(isPresented: $isPickerPresented) {
                DevicePicker(descriptor) { endpoint in
                    isPickerPresented = false
                    request(from: endpoint)
                } label: {
                    Text("Link \(kind.displayName) from your iPhone or iPad. Open Edendale there to approve.")
                } fallback: {
                    Text("Linking from an iPhone or iPad isn't available on this Apple TV.")
                } parameters: {
                    .applicationService
                }
            }
        }
    }

    private func request(from endpoint: NWEndpoint) {
        isWaiting = true
        Task {
            do {
                let response = try await AccountHandoffClient.request(
                    AccountHandoff.Request(kind: kind, deviceName: UIDevice.current.name),
                    endpoint: endpoint
                )
                try await onResponse(response)
            } catch {
                onError(error.localizedDescription)
            }
            isWaiting = false
        }
    }
}
#endif
