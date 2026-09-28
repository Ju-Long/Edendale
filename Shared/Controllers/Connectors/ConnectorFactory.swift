//
//  ConnectorFactory.swift
//  Edendale
//
//  Rebuilds connectors and playback byte sources from what the library
//  stores: a source's credential-free folder URL or an item's file URL. The
//  login or account comes back out of the Keychain by the URL's host (the
//  server host, or the account key for S3 and the cloud providers). A
//  missing login or account yields `nil` or `signInRequired`, so the source
//  can show "Sign in again".
//

import Foundation

nonisolated enum ConnectorFactory {

    /// Where logins, accounts, and tokens come from. Tests substitute
    /// in-memory stores.
    struct Environment: Sendable {
        var secrets: any SecretStore
        var accounts: CloudAccountVault
        var tokens: CloudTokenProvider

        static let live = Environment(
            secrets: KeychainStore.shared,
            accounts: .shared,
            tokens: .shared
        )
    }

    // MARK: - Connectors

    /// The connector for a stored source URL; `nil` for local folders, or
    /// when the source's login or account is gone.
    static func connector(
        forSource url: URL,
        kind: MediaSourceKind,
        environment: Environment = .live
    ) -> (any MediaConnector)? {
        switch kind {
        case .local:
            return nil
        case .smb:
            return SMBConnector(sourceURL: url, store: environment.secrets)
        case .nfs:
            return NFSConnector(sourceURL: url)
        case .sftp:
            return SFTPConnector(sourceURL: url, store: environment.secrets)
        case .webdav:
            return WebDAVConnector(sourceURL: url, store: environment.secrets)
        case .s3:
            return S3Connector(sourceURL: url, store: environment.secrets)
        case .googleDrive, .oneDrive, .dropbox:
            guard let account = account(for: url, kind: kind, environment: environment) else { return nil }
            return connector(for: account, environment: environment)
        }
    }

    /// The connector at the root of a linked cloud account.
    static func connector(for account: CloudAccount, environment: Environment = .live) -> (any MediaConnector)? {
        switch account.kind {
        case .googleDrive: GoogleDriveConnector(account: account, tokens: environment.tokens)
        case .oneDrive: OneDriveConnector(account: account, tokens: environment.tokens)
        case .dropbox: DropboxConnector(account: account, tokens: environment.tokens)
        default: nil
        }
    }

    static func account(for url: URL, kind: MediaSourceKind, environment: Environment = .live) -> CloudAccount? {
        guard let key = url.host(percentEncoded: false) else { return nil }
        return environment.accounts.account(kind: kind, key: key)
    }

    // MARK: - Playback

    /// Remote items FFmpeg reads through a byte source: everything except
    /// local files and SMB (which carries its login in the URL to libsmb2).
    static func streamsThroughByteSource(_ url: URL) -> Bool {
        guard let kind = MediaSourceKind(url: url) else { return false }
        return kind != .local && kind != .smb
    }

    /// The byte source FFmpeg reads `itemURL` through. Nothing touches the
    /// network until FFmpeg's first read.
    static func byteSource(for itemURL: URL, environment: Environment = .live) throws -> any ByteSource {
        guard let kind = MediaSourceKind(url: itemURL) else { throw ConnectorError.invalidAddress }
        switch kind {
        case .nfs:
            guard let source = NFSConnector.byteSource(for: itemURL) else { throw ConnectorError.invalidAddress }
            return source
        case .sftp:
            guard let source = SFTPConnector.byteSource(for: itemURL, store: environment.secrets) else {
                throw ConnectorError.invalidAddress
            }
            return source
        case .webdav, .s3, .googleDrive, .oneDrive, .dropbox:
            return RemoteByteSource(resolver: try contentResolver(for: itemURL, environment: environment))
        case .local, .smb:
            throw ConnectorError.invalidAddress
        }
    }

    /// How an HTTP provider's item is fetched.
    static func contentResolver(
        for itemURL: URL,
        environment: Environment = .live
    ) throws -> any RemoteContentResolver {
        guard let kind = MediaSourceKind(url: itemURL) else { throw ConnectorError.invalidAddress }
        switch kind {
        case .googleDrive:
            guard let item = SourceURL.parseAccountItem(itemURL), let fileID = item.ids.first else {
                throw ConnectorError.invalidAddress
            }
            try requireAccount(kind: kind, key: item.account, environment: environment)
            return GoogleDriveContentResolver(fileID: fileID, accountKey: item.account, tokens: environment.tokens)
        case .oneDrive:
            guard let item = SourceURL.parseAccountItem(itemURL), item.ids.count == 2 else {
                throw ConnectorError.invalidAddress
            }
            try requireAccount(kind: kind, key: item.account, environment: environment)
            return OneDriveContentResolver(
                driveID: item.ids[0], itemID: item.ids[1], accountKey: item.account, tokens: environment.tokens
            )
        case .dropbox:
            guard let item = SourceURL.parseAccountItem(itemURL), let fileID = item.ids.first else {
                throw ConnectorError.invalidAddress
            }
            try requireAccount(kind: kind, key: item.account, environment: environment)
            return DropboxContentResolver(fileID: fileID, accountKey: item.account, tokens: environment.tokens)
        case .s3:
            guard let item = SourceURL.parseS3(itemURL), !item.isPrefix else { throw ConnectorError.invalidAddress }
            guard let credential = NetworkCredentialStore.credential(kind: .s3, host: item.account, store: environment.secrets),
                  let configuration = credential.s3
            else { throw ConnectorError.signInRequired(provider: kind.displayName) }
            return S3ContentResolver(configuration: configuration, credential: credential, key: item.key)
        case .webdav:
            guard let host = itemURL.host() else { throw ConnectorError.invalidAddress }
            let credential = NetworkCredentialStore.credential(kind: .webdav, host: host, store: environment.secrets)
            return WebDAVContentResolver(fileURL: itemURL, credential: credential)
        case .local, .smb, .nfs, .sftp:
            throw ConnectorError.invalidAddress
        }
    }

    private static func requireAccount(kind: MediaSourceKind, key: String, environment: Environment) throws {
        guard environment.accounts.account(kind: kind, key: key) != nil else {
            throw ConnectorError.signInRequired(provider: kind.displayName)
        }
    }
}
