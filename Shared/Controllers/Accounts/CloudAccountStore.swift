//
//  CloudAccountStore.swift
//  Edendale
//
//  The observable face of linked accounts and saved server logins, for the
//  Link Source flow and Settings → Accounts. Signing in runs the provider's
//  OAuth page (or, on Apple TV, a device code or AccountHandoff), resolves
//  who the account is, and keeps it in CloudAccountVault. Signing out is
//  explicit and reaches every device sharing the user's iCloud Keychain;
//  it can also revoke Edendale's access at the provider.
//

import Foundation

@Observable
@MainActor
final class CloudAccountStore {

    private(set) var accounts: [CloudAccount] = []
    private(set) var savedLogins: [SavedServerLogin] = []

    @ObservationIgnored let vault: CloudAccountVault
    @ObservationIgnored let secrets: any SecretStore
    @ObservationIgnored let tokens: CloudTokenProvider
    @ObservationIgnored private let session: URLSession
    #if !os(tvOS)
    @ObservationIgnored private let authenticator = WebAuthenticator()
    #endif

    init(
        vault: CloudAccountVault = .shared,
        secrets: any SecretStore = KeychainStore.shared,
        tokens: CloudTokenProvider = .shared,
        session: URLSession = .shared
    ) {
        self.vault = vault
        self.secrets = secrets
        self.tokens = tokens
        self.session = session
        reload()
    }

    /// Re-reads the Keychain: accounts and logins can arrive through iCloud
    /// Keychain sync or a handoff at any time.
    func reload() {
        accounts = vault.all()
        savedLogins = NetworkCredentialStore.savedLogins(store: secrets)
    }

    func accounts(of kind: MediaSourceKind) -> [CloudAccount] {
        accounts.filter { $0.kind == kind }
    }

    /// Set by the launch that removed Google Drive accounts or sources from
    /// before `drive.file` (D1); ContentView's notice clears it.
    var needsGoogleDriveRelinkNotice = false

    /// A finished sign-in, with the Drive files picked in Google's picker
    /// along the way (empty for other providers).
    nonisolated struct SignIn: Equatable, Sendable {
        let account: CloudAccount
        let pickedFileIDs: [String]
    }

    // MARK: - Signing in

    #if !os(tvOS)
    /// The provider's sign-in page, then the account it produced. For
    /// Google Drive the page also opens Google's picker.
    func signIn(kind: MediaSourceKind) async throws -> SignIn {
        let (response, picked) = try await authorize(kind: kind)
        return SignIn(account: try await completeSignIn(kind: kind, tokens: response), pickedFileIDs: picked)
    }

    /// Reruns Google's picker for a linked account, so its Drive source can
    /// list more videos (R7). Every run returns a new refresh token; the
    /// newest is kept and the old one is left alone, because Google's
    /// revoke would end the whole grant, the new token and any Apple TV's
    /// included. Throws if the user switched to another Google account.
    func chooseMoreVideos(for account: CloudAccount) async throws -> SignIn {
        let kind = account.kind
        guard kind == .googleDrive else { throw OAuthError.notConfigured(provider: kind.displayName) }
        let (response, picked) = try await authorize(
            kind: kind,
            parameters: CloudProviders.googleChooseMoreParameters(loginHint: account.email)
        )
        return SignIn(account: try await storeChosenVideosTokens(response, for: account), pickedFileIDs: picked)
    }

    /// The provider's page in ASWebAuthenticationSession, then the code
    /// exchange.
    private func authorize(
        kind: MediaSourceKind,
        parameters: [String: String] = [:]
    ) async throws -> (OAuthTokenResponse, [String]) {
        guard let configuration = CloudProviders.configuration(for: kind) else {
            throw OAuthError.notConfigured(provider: kind.displayName)
        }
        let client = OAuthClient(configuration: configuration, session: session)
        let verifier = PKCE.makeVerifier()
        let state = PKCE.makeState()
        let url = client.authorizationURL(
            state: state,
            codeChallenge: PKCE.challenge(for: verifier),
            parameters: parameters
        )
        let callback = try await authenticator.authenticate(url: url, callbackScheme: configuration.callbackScheme)
        let authorization = try OAuthClient.authorizationResponse(
            from: callback,
            expectedState: state,
            provider: kind.displayName
        )
        let response = try await client.exchange(code: authorization.code, verifier: verifier)
        return (response, authorization.pickedFileIDs)
    }
    #endif

    /// Starts an RFC 8628 sign-in: show the code and link, then call
    /// `finishDeviceSignIn`. OneDrive only (see CloudProviders).
    func startDeviceSignIn(kind: MediaSourceKind) async throws -> DeviceAuthorization {
        guard CloudProviders.supportsDeviceCode(kind),
              let configuration = CloudProviders.configuration(for: kind)
        else { throw OAuthError.notConfigured(provider: kind.displayName) }
        return try await OAuthClient(configuration: configuration, session: session).startDeviceAuthorization()
    }

    /// Waits for the user to approve the code on another device.
    func finishDeviceSignIn(kind: MediaSourceKind, authorization: DeviceAuthorization) async throws -> CloudAccount {
        guard let configuration = CloudProviders.configuration(for: kind) else {
            throw OAuthError.notConfigured(provider: kind.displayName)
        }
        let response = try await OAuthClient(configuration: configuration, session: session)
            .waitForDeviceAuthorization(authorization)
        return try await completeSignIn(kind: kind, tokens: response)
    }

    /// Turns fresh tokens into a stored account: who it is, the refresh
    /// token, and the granted scopes.
    func completeSignIn(kind: MediaSourceKind, tokens response: OAuthTokenResponse) async throws -> CloudAccount {
        guard let refreshToken = response.refreshToken, !refreshToken.isEmpty else {
            throw SignInError.noOfflineAccess(provider: kind.displayName)
        }
        let scopes = try grantedScopes(kind: kind, response: response)
        let identity = try await CloudProviders.identity(for: kind, tokens: response, session: session)
        let account = CloudAccount(
            kind: kind,
            subject: identity.subject,
            email: identity.email,
            displayName: identity.displayName,
            refreshToken: refreshToken,
            scopes: scopes,
            driveID: identity.driveID
        )
        try vault.save(account)
        await tokens.store(response, for: account)
        reload()
        return account
    }

    /// Keeps the tokens a Choose More Videos run returned for `account`:
    /// the newest refresh token replaces the stored one, which is never
    /// revoked (Google would end the whole grant). Throws if they belong to
    /// another Google account.
    func storeChosenVideosTokens(_ response: OAuthTokenResponse, for account: CloudAccount) async throws -> CloudAccount {
        let kind = account.kind
        let scopes = try grantedScopes(kind: kind, response: response)
        let identity = try await CloudProviders.identity(for: kind, tokens: response, session: session)
        guard identity.subject == account.subject else {
            throw SignInError.differentAccount(
                chosen: identity.email ?? identity.displayName ?? kind.displayName,
                expected: account.label
            )
        }
        var updated = vault.account(kind: kind, key: account.key) ?? account
        if let refreshToken = response.refreshToken, !refreshToken.isEmpty {
            updated.refreshToken = refreshToken
        }
        updated.scopes = scopes
        updated.email = identity.email ?? updated.email
        updated.displayName = identity.displayName ?? updated.displayName
        try vault.save(updated)
        await tokens.store(response, for: updated)
        reload()
        return updated
    }

    /// The scopes a token response granted. Google's consent page can leave
    /// Drive access unticked.
    private func grantedScopes(kind: MediaSourceKind, response: OAuthTokenResponse) throws -> [String] {
        let scopes = response.grantedScopes ?? CloudProviders.configuration(for: kind)?.scopes ?? []
        if kind == .googleDrive, !scopes.contains(CloudProviders.googleDriveFileScope) {
            throw SignInError.driveAccessNotGranted
        }
        return scopes
    }

    // MARK: - drive.file migration (D1)

    /// Removes every Google Drive account from before `drive.file`: its
    /// token can't open the picker, and under `drive.readonly` the Drive
    /// root would list every file. Runs at each launch on every platform
    /// (Apple TV keeps its own Keychain), so an account an older build syncs
    /// in later goes too. Returns whether anything was removed.
    @discardableResult
    func removeGoogleAccountsWithoutDriveFile() -> Bool {
        let outdated = vault.accounts(of: .googleDrive).filter {
            !$0.scopes.contains(CloudProviders.googleDriveFileScope)
        }
        for account in outdated {
            vault.remove(kind: .googleDrive, key: account.key)
        }
        if !outdated.isEmpty { reload() }
        return !outdated.isEmpty
    }

    /// D1 at launch: old Google Drive accounts and sources go, and the user
    /// is told once to link Google Drive again.
    func migrateGoogleDriveToFileAccess(library: LibraryController) {
        let removedAccounts = removeGoogleAccountsWithoutDriveFile()
        let removedSources = library.removeGoogleDriveSourcesFromBeforeDriveFile()
        if removedAccounts || removedSources > 0 {
            needsGoogleDriveRelinkNotice = true
        }
    }

    /// Stores an account another device handed over, after proving its
    /// refresh token works. Apple TV keeps it in its own Keychain.
    func adoptHandedOffAccount(_ account: CloudAccount) async throws -> CloudAccount {
        let existing = vault.account(kind: account.kind, key: account.key)
        try vault.save(account)
        await tokens.forget(kind: account.kind, accountKey: account.key)
        do {
            _ = try await tokens.accessToken(kind: account.kind, accountKey: account.key)
        } catch {
            if let existing {
                try? vault.save(existing)
            } else {
                vault.remove(kind: account.kind, key: account.key)
            }
            reload()
            throw SignInError.handoffRejected(provider: account.kind.displayName)
        }
        reload()
        return account
    }

    // MARK: - Signing out

    /// Forgets the account on every device sharing the iCloud Keychain.
    /// With `revoke`, also ends Edendale's access at the provider, which
    /// signs out any Apple TV that received the same grant.
    func signOut(_ account: CloudAccount, revoke: Bool) async {
        if revoke {
            let accessToken = try? await tokens.accessToken(kind: account.kind, accountKey: account.key)
            await CloudProviders.revoke(
                kind: account.kind,
                refreshToken: account.refreshToken,
                accessToken: accessToken,
                session: session
            )
        }
        vault.remove(kind: account.kind, key: account.key)
        await tokens.forget(kind: account.kind, accountKey: account.key)
        reload()
    }

    /// Forgets a saved server login everywhere it synced to.
    func forget(_ login: SavedServerLogin) {
        NetworkCredentialStore.remove(kind: login.kind, host: login.host, store: secrets)
        reload()
    }

    nonisolated enum SignInError: Error, LocalizedError, Equatable {
        case noOfflineAccess(provider: String)
        case driveAccessNotGranted
        case handoffRejected(provider: String)
        /// Choose More Videos came back signed in as someone else.
        case differentAccount(chosen: String, expected: String)

        var errorDescription: String? {
            switch self {
            case .noOfflineAccess(let provider):
                String(localized: "\(provider) didn't grant lasting access. Sign in again and allow Edendale to stay connected.")
            case .driveAccessNotGranted:
                String(localized: "Edendale needs access to the Google Drive files you choose. Sign in again and leave that access selected.")
            case .differentAccount(let chosen, let expected):
                String(localized: "Those videos were chosen as \(chosen). Choose More Videos again and use \(expected).")
            case .handoffRejected(let provider):
                String(localized: "The \(provider) account from your other device couldn't be verified. Sign in on that device again, then try once more.")
            }
        }
    }
}
