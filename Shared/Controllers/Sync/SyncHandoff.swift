//
//  SyncHandoff.swift
//  Edendale
//
//  What a handoff carries for sync (SYNC-PROTOCOL.md §9.6–§9.8): the
//  sender turns its sync location and the accounts and logins the user
//  ticked into an `offer`, under the Google Drive rule; the receiver
//  validates each item (a token refresh, or a connection to the server
//  with the host key checked), stores what validates in the Keychain,
//  sets the location, and reports what it kept. A folder location cannot
//  travel: the receiver picks its own folder for the space it was given,
//  and a Google Drive location from another ecosystem comes as an account
//  hint the receiver signs in with.
//

import Foundation

@MainActor
enum SyncHandoff {

    /// What the sender chose to send.
    struct Selection: Equatable {
        var includeLocation = true
        var accountKeys: Set<String> = []
        var loginIDs: Set<String> = []
    }

    /// The sender's offer for a peer on `peerPlatform`.
    static func makeOffer(
        location: SyncLocation?,
        selection: Selection,
        accounts: [CloudAccount],
        logins: [SavedServerLogin],
        peerPlatform: String?,
        secrets: any SecretStore = KeychainStore.shared
    ) -> HandoffV2.Offer {
        let sameGoogleClient = HandoffV2.sharesGoogleClient(withPlatform: peerPlatform)
        var offer = HandoffV2.Offer(status: .approved)

        if selection.includeLocation, let location {
            var wire: HandoffV2.OfferLocation? = HandoffV2.OfferLocation(provider: location.provider, space: location.space)
            switch location.provider {
            case .folder:
                break
            case .iCloudDrive:
                // Only Apple devices reach the app's iCloud Drive folder; Windows
                // reaches it as a folder through iCloud for Windows (FEATURES-27.1.md
                // §5.2), and Android and Quest not at all, where `icloudDrive` would
                // also be an unknown provider that fails the whole offer.
                if HandoffV2.isApplePlatform(peerPlatform) {
                    break
                } else if peerPlatform?.lowercased() == "windows" {
                    wire = HandoffV2.OfferLocation(provider: .folder, space: location.space)
                } else {
                    wire = nil
                }
            case .googleDrive, .oneDrive, .dropbox:
                if let kind = location.provider.mediaSourceKind, let key = location.accountKey,
                   let account = accounts.first(where: { $0.kind == kind && $0.key == key }) {
                    if location.provider == .googleDrive, !sameGoogleClient {
                        wire?.googleAccountHint = account.email
                        wire?.googleFolderId = location.googleFolderID
                    } else {
                        wire?.account = HandoffV2.Account(account)
                    }
                }
            case .webdav, .smb, .sftp:
                wire?.url = location.serverURL?.absoluteString
                if let kind = location.provider.mediaSourceKind, let host = location.serverHost,
                   let login = logins.first(where: { $0.kind == kind && $0.host.lowercased() == host.lowercased() }) {
                    wire?.login = AccountHandoff.Login.handing(login, store: secrets)
                }
            }
            offer.location = wire
        }

        offer.accounts = accounts
            .filter { selection.accountKeys.contains($0.key) }
            .filter { $0.kind != .googleDrive || sameGoogleClient }
            .map(HandoffV2.Account.init)
        offer.logins = logins
            .filter { selection.loginIDs.contains($0.id) }
            .compactMap { AccountHandoff.Login.handing($0, store: secrets) }
        return offer
    }

    /// What the receiver could not finish by itself and the UI completes.
    enum FollowUp: Equatable {
        /// A folder location: the user picks a folder for this space.
        case pickFolder(space: String)
        /// A Google Drive location from another ecosystem: sign in with
        /// the hint, then use the folder.
        case googleSignIn(hint: String?, folderID: String?, space: String)
    }

    struct Received: Equatable {
        var result: HandoffV2.ResultMessage
        var followUp: FollowUp?
    }

    /// Validates and stores an offer. Items that fail are reported, never
    /// stored.
    static func receive(
        _ offer: HandoffV2.Offer,
        accounts: CloudAccountStore,
        sync: SyncCoordinator,
        secrets: any SecretStore = KeychainStore.shared
    ) async -> Received {
        var stored: [String] = []
        var failed: [HandoffV2.ResultMessage.Failure] = []
        var followUp: FollowUp?

        for account in offer.accounts ?? [] {
            await adopt(account, accounts: accounts, stored: &stored, failed: &failed)
        }
        for login in offer.logins ?? [] {
            await store(login, secrets: secrets, stored: &stored, failed: &failed)
        }

        if let location = offer.location {
            let name = HandoffV2.storedName(location: location.provider)
            if let space = SyncFileFormat.normalizedSpaceName(location.space) {
                switch location.provider {
                case .folder:
                    #if os(tvOS)
                    failed.append(.init(item: name, reason: "unavailable"))
                    #else
                    followUp = .pickFolder(space: space)
                    stored.append(name)
                    #endif
                case .iCloudDrive:
                    #if os(tvOS)
                    failed.append(.init(item: name, reason: "unavailable"))
                    #else
                    sync.setLocation(SyncLocation(provider: .iCloudDrive, space: space))
                    stored.append(name)
                    #endif
                case .googleDrive, .oneDrive, .dropbox:
                    if let account = location.account {
                        if let adopted = await adopt(account, accounts: accounts, stored: &stored, failed: &failed) {
                            // An account from before 27.1's write scopes can't hold
                            // sync files until it signs in again (FEATURES-27.1.md §10).
                            if CloudProviders.grantsSyncWrites(adopted) {
                                sync.setLocation(SyncLocation(
                                    provider: location.provider, space: space, accountKey: adopted.key,
                                    googleFolderID: location.googleFolderId
                                ))
                                stored.append(name)
                            } else {
                                failed.append(.init(item: name, reason: "scope"))
                            }
                        } else {
                            failed.append(.init(item: name, reason: "account"))
                        }
                    } else if location.provider == .googleDrive {
                        #if os(tvOS)
                        failed.append(.init(item: name, reason: "google-sign-in-unavailable"))
                        #else
                        followUp = .googleSignIn(hint: location.googleAccountHint, folderID: location.googleFolderId, space: space)
                        stored.append(name)
                        #endif
                    } else {
                        failed.append(.init(item: name, reason: "account"))
                    }
                case .webdav, .smb, .sftp:
                    guard let urlString = location.url, let url = URL(string: urlString),
                          MediaSourceKind(url: url) == location.provider.mediaSourceKind
                    else {
                        failed.append(.init(item: name, reason: "malformed"))
                        break
                    }
                    if let login = location.login {
                        guard await store(login, secrets: secrets, stored: &stored, failed: &failed) else {
                            failed.append(.init(item: name, reason: "login"))
                            break
                        }
                    }
                    sync.setLocation(SyncLocation(provider: location.provider, space: space, serverURL: url))
                    stored.append(name)
                }
            } else {
                failed.append(.init(item: name, reason: "malformed"))
            }
        }
        return Received(result: HandoffV2.ResultMessage(stored: stored, failed: failed), followUp: followUp)
    }

    @discardableResult
    private static func adopt(
        _ account: HandoffV2.Account,
        accounts: CloudAccountStore,
        stored: inout [String],
        failed: inout [HandoffV2.ResultMessage.Failure]
    ) async -> CloudAccount? {
        let name = HandoffV2.storedName(account: account)
        do {
            let adopted = try await accounts.adoptHandedOffAccount(account.cloudAccount)
            stored.append(name)
            return adopted
        } catch {
            failed.append(.init(item: name, reason: "rejected"))
            SyncLog.warning("handoff: account rejected \(SyncLog.describe(error))")
            return nil
        }
    }

    /// Connects with the login (SFTP: after pinning the handed-over host
    /// key and checking the server presents it), then saves it.
    @discardableResult
    private static func store(
        _ login: AccountHandoff.Login,
        secrets: any SecretStore,
        stored: inout [String],
        failed: inout [HandoffV2.ResultMessage.Failure]
    ) async -> Bool {
        let name = HandoffV2.storedName(login: login)
        do {
            switch login.kind {
            case .sftp:
                let port = login.port ?? login.credential.port ?? SFTPConnector.defaultPort
                let key = try await SFTPConnector.fetchHostKey(host: login.host, port: port)
                guard let pinned = login.hostKeyFingerprint, pinned == key.fingerprint else {
                    throw ConnectorError.hostKeyMismatch(host: login.host)
                }
                try HostKeyStore.pin(pinned, host: login.host, port: port, store: secrets)
                var credential = login.credential
                credential.port = port
                guard let connector = SFTPConnector(host: login.host, port: port, credential: credential) else {
                    throw ConnectorError.invalidAddress
                }
                _ = try await connector.homeDirectory()
                try NetworkCredentialStore.save(credential, kind: .sftp, host: login.host, store: secrets)
            case .smb:
                guard let connector = SMBConnector(host: login.host, credential: login.credential) else {
                    throw ConnectorError.invalidAddress
                }
                try await connector.validate()
                try NetworkCredentialStore.save(login.credential, kind: .smb, host: login.host, store: secrets)
            case .webdav, .s3:
                // The folder a WebDAV login reaches is not in the login; it is
                // checked when the location first syncs.
                try NetworkCredentialStore.save(login.credential, kind: login.kind, host: login.host, store: secrets)
            default:
                throw AccountHandoff.HandoffError.wrongKind
            }
            stored.append(name)
            return true
        } catch {
            failed.append(.init(item: name, reason: error is ConnectorError ? "unreachable" : "rejected"))
            SyncLog.warning("handoff: login rejected \(SyncLog.describe(error))")
            return false
        }
    }
}
