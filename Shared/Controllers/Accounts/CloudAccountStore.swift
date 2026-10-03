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

    // MARK: - Signing in

    #if !os(tvOS)
    /// The provider's sign-in page, then the account it produced.
    func signIn(kind: MediaSourceKind) async throws -> CloudAccount {
        guard let configuration = CloudProviders.configuration(for: kind) else {
            throw OAuthError.notConfigured(provider: kind.displayName)
        }
        let client = OAuthClient(configuration: configuration, session: session)
        let verifier = PKCE.makeVerifier()
        let state = PKCE.makeState()
        let url = client.authorizationURL(state: state, codeChallenge: PKCE.challenge(for: verifier))
        let callback = try await authenticator.authenticate(url: url, callbackScheme: configuration.callbackScheme)
        let code = try OAuthClient.authorizationCode(from: callback, expectedState: state, provider: kind.displayName)
        let response = try await client.exchange(code: code, verifier: verifier)
        return try await completeSignIn(kind: kind, tokens: response)
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
        let scopes = response.grantedScopes ?? CloudProviders.configuration(for: kind)?.scopes ?? []
        // Google's consent page lets the user untick Drive access.
        if kind == .googleDrive, !scopes.contains("https://www.googleapis.com/auth/drive.readonly") {
            throw SignInError.driveAccessNotGranted
        }
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

        var errorDescription: String? {
            switch self {
            case .noOfflineAccess(let provider):
                String(localized: "\(provider) didn't grant lasting access. Sign in again and allow Edendale to stay connected.")
            case .driveAccessNotGranted:
                String(localized: "Edendale needs permission to see your Google Drive files. Sign in again and leave Drive access selected.")
            case .handoffRejected(let provider):
                String(localized: "The \(provider) account from your other device couldn't be verified. Sign in on that device again, then try once more.")
            }
        }
    }
}
