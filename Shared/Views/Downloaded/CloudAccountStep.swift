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
//  other device. With no account linked there yet, the list of nearby
//  devices opens as soon as the step does.
//
//  Google Drive's sign-in also opens Google's picker, where the user ticks
//  the videos Edendale may play. There is no folder to browse afterwards:
//  the account's "Videos you chose" source is linked straight away. Apple
//  TV never opens the picker; it lists what was chosen on other devices.
//
//  Settings → Sync uses the same step with `purpose: .sync` (ENHANCEMENT.md
//  K.8): nothing is chosen there. Google's sign-in skips the picker, an
//  account whose grant predates the 27.1 write scopes signs in again from
//  its own row, and Apple TV asks the iPhone or iPad for a sync sign-in.
//

import SwiftUI
#if os(tvOS)
import DeviceDiscoveryUI
import Network
#endif

struct CloudAccountStep: View {
    let kind: MediaSourceKind
    /// `.sync` (Settings → Sync): signing in skips Google's picker.
    var purpose: CloudProviders.SignInPurpose = .library
    /// The account's top folder, to browse from.
    let onReady: (BrowseLocation) -> Void
    /// Google Drive: the account to link "Videos you chose" for, with any
    /// files just picked.
    let onGoogleDriveReady: (CloudAccountStore.SignIn) -> Void

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
                            choose(account)
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Label(account.label, image: .circleUserFill)
                                    .foregroundStyle(Theme.textPrimary)
                                if needsSyncSignIn(account) {
                                    Text("Sign in again so Edendale can write its sync files here.")
                                        .font(Typography.bodySM)
                                        .foregroundStyle(Theme.textSecondary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            #if os(tvOS)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(SettingsMetrics.highlightBleed)
                            #endif
                        }
                        .disabled(isSigningIn)
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
        // With no account here yet, choosing the provider was the request
        // to hand one off: open the device list straight away.
        HandoffButton(
            kind: kind,
            title: String(localized: "Continue on iPhone or iPad"),
            opensOnAppear: store.accounts(of: kind).isEmpty,
            forSync: purpose == .sync
        ) { response in
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

    /// What Edendale may do with the account. Since 27.1 the grant also
    /// covers the sync files (FEATURES-27.1.md §10), which are written only
    /// once the account is chosen as the sync location.
    private var privacyNote: String {
        switch kind {
        case .googleDrive where purpose == .sync:
            #if os(tvOS)
            String(localized: "Apple TV gets the account from your iPhone or iPad. In Google Drive, Edendale sees only the sync files it creates and changes nothing else.")
            #else
            String(localized: "There's nothing to choose: Edendale keeps its sync files in an Edendale folder it creates in your Google Drive. It sees only the files it creates, connects from this device only, and changes nothing else.")
            #endif
        case .googleDrive:
            #if os(tvOS)
            String(localized: "Choose your videos in Edendale on an iPhone, iPad, or Mac. Apple TV gets the account from your iPhone or iPad and lists every video you chose. Edendale sees only those videos and the sync files it creates, and changes nothing else.")
            #else
            String(localized: "After you sign in, Google's picker opens: choose the videos Edendale may play. Edendale sees only those and the sync files it creates, connects from this device only, and changes nothing else.")
            #endif
        case .oneDrive:
            String(localized: "Edendale reads folder listings and the videos you play, connects from this device only, and writes only to its own Apps/Edendale folder, when you sync there.")
        case .dropbox:
            String(localized: "Edendale reads folder listings and the videos you play, connects from this device only, and writes only its sync files, in the Edendale folder, when you sync there.")
        default:
            String(localized: "Edendale reads folder listings and the videos you play, connects from this device only, and changes nothing.")
        }
    }

    private func open(_ account: CloudAccount, pickedFileIDs: [String] = []) {
        if kind == .googleDrive {
            errorMessage = nil
            onGoogleDriveReady(CloudAccountStore.SignIn(account: account, pickedFileIDs: pickedFileIDs))
            return
        }
        guard let connector = ConnectorFactory.connector(for: account) else {
            errorMessage = ConnectorError.signInRequired(provider: kind.displayName).localizedDescription
            return
        }
        errorMessage = nil
        onReady(BrowseLocation(connector: connector, url: connector.root, name: kind.displayName))
    }

    /// A linked account whose grant can't write sync files: a OneDrive or
    /// Dropbox sign-in from before 27.1 (FEATURES-27.1.md §10).
    private func needsSyncSignIn(_ account: CloudAccount) -> Bool {
        purpose == .sync && !CloudProviders.grantsSyncWrites(account)
    }

    /// A linked account's row. For sync, one without the write scopes signs
    /// in again here instead of sending the user to Link Source, where
    /// OneDrive and Dropbox would ask for a folder. Apple TV can't sign in
    /// itself; Settings → Sync explains what to do there.
    private func choose(_ account: CloudAccount) {
        #if os(tvOS)
        open(account)
        #else
        if needsSyncSignIn(account) {
            // Dropbox's authorize page takes no login hint.
            signIn(loginHint: kind == .dropbox ? nil : account.email)
        } else {
            open(account)
        }
        #endif
    }

    #if !os(tvOS)
    private func signIn(loginHint: String? = nil) {
        isSigningIn = true
        errorMessage = nil
        Task {
            do {
                let signIn = try await store.signIn(kind: kind, loginHint: loginHint, purpose: purpose)
                open(signIn.account, pickedFileIDs: signIn.pickedFileIDs)
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
/// "Continue on iPhone or iPad": opens the system list of nearby iPhones and
/// iPads, sends an AccountHandoff request to the device the user chooses,
/// and passes on the approved response.
struct HandoffButton: View {
    let kind: MediaSourceKind
    let title: String
    /// Opens the device list as soon as the button first appears, for a
    /// step where handing off is the expected next move (a cloud provider
    /// with no account on this Apple TV yet). Backing out of the list
    /// leaves the button and the step's other options.
    var opensOnAppear = false
    /// Settings → Sync: the iPhone or iPad signs in without Google's
    /// picker if it has to sign in at all.
    var forSync = false
    let onResponse: (AccountHandoff.Response) async throws -> Void
    let onError: (String) -> Void

    @State private var isWaiting = false
    @State private var didOpenOnAppear = false

    private var descriptor: NWBrowser.Descriptor {
        .applicationService(name: AccountHandoff.serviceName)
    }

    var body: some View {
        if DDDevicePickerViewController.isSupported(descriptor, using: .applicationService) {
            Button {
                chooseDevice()
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
            .onAppear {
                guard opensOnAppear, !didOpenOnAppear else { return }
                didOpenOnAppear = true
                chooseDevice()
            }
        }
    }

    /// Unstructured on purpose: the full-screen picker takes this view off
    /// screen, which would cancel a `.task` mid-handoff.
    private func chooseDevice() {
        Task {
            guard let endpoint = await NearbyDevicePicker.choose(descriptor) else { return }
            isWaiting = true
            do {
                let response = try await AccountHandoffClient.request(
                    AccountHandoff.Request(kind: kind, deviceName: UIDevice.current.name, forSync: forSync ? true : nil),
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

/// DeviceDiscoveryUI's device list, presented directly. SwiftUI's
/// DevicePicker is itself a button that opens this list, which put a
/// second press between "Continue on iPhone or iPad" and the devices.
@MainActor
enum NearbyDevicePicker {
    /// Presents the list over whatever is frontmost and returns the device
    /// chosen, or nil when the user backs out or the list can't be shown.
    static func choose(_ descriptor: NWBrowser.Descriptor) async -> NWEndpoint? {
        // Selecting a provider pushes its step and opens the list in the
        // same moment; presenting mid-push unbalances UIKit's appearance
        // calls, so let the push land first.
        if let frontmost = frontmostController() {
            await transitionToFinish(in: frontmost)
        }
        guard let picker = DDDevicePickerViewController(browseDescriptor: descriptor, parameters: .applicationService),
              let presenter = frontmostController()
        else { return nil }
        presenter.present(picker, animated: true)
        defer {
            // The list may already be on its way out (Menu, or the system
            // closing it after a choice). Dismissing the picker itself,
            // never its presenter, can't take the sheet beneath with it.
            if picker.presentingViewController != nil, !picker.isBeingDismissed {
                picker.dismiss(animated: true)
            }
        }
        return try? await picker.endpoint
    }

    private static func frontmostController() -> UIViewController? {
        let window = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
        var controller = window?.rootViewController
        while let presented = controller?.presentedViewController, !presented.isBeingDismissed {
            controller = presented
        }
        return controller
    }

    /// Waits out a presentation or navigation transition in progress under
    /// `controller`, if any.
    private static func transitionToFinish(in controller: UIViewController) async {
        guard let coordinator = runningTransition(in: controller) else { return }
        await withCheckedContinuation { continuation in
            var resumed = false
            let resume = {
                guard !resumed else { return }
                resumed = true
                continuation.resume()
            }
            if !coordinator.animate(alongsideTransition: nil, completion: { _ in resume() }) {
                resume()
            }
        }
    }

    private static func runningTransition(in controller: UIViewController) -> UIViewControllerTransitionCoordinator? {
        if let coordinator = controller.transitionCoordinator { return coordinator }
        for child in controller.children {
            if let coordinator = runningTransition(in: child) { return coordinator }
        }
        return nil
    }
}
#endif
