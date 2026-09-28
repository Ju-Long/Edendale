//
//  SFTPConnector.swift
//  Edendale
//
//  Folders on an SSH server, read over SFTP through SFTPConnection
//  (SwiftNIO SSH), so Edendale owns host-key checking: trust on first use.
//  Linking shows the server's SHA-256 host-key fingerprint, the user
//  approves it, and HostKeyStore pins it in the Keychain. Every later
//  connection — listing, rescans, playback — refuses a different key until
//  the user approves it again. Password login for now; keys later.
//
//  URLs are `sftp://host[:port]/path/Name.ext` with absolute server paths.
//

import Foundation

nonisolated struct SFTPConnector: MediaConnector, Hashable {
    static let defaultPort = 22
    static let timeoutMilliseconds = 15_000

    let kind: MediaSourceKind = .sftp
    let host: String
    let port: Int
    let credential: NetworkCredential
    /// Where browsing starts: the login directory when linking.
    let startPath: String

    init?(host: String, port: Int?, credential: NetworkCredential, startPath: String = "/") {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              SourceURL.server(scheme: "sftp", host: trimmed, port: port, pathSegments: []) != nil
        else { return nil }
        self.host = trimmed
        self.port = port ?? Self.defaultPort
        self.credential = credential
        self.startPath = startPath
    }

    /// Rebuilds the connector for a stored source with its saved login.
    init?(sourceURL: URL, store: any SecretStore = KeychainStore.shared) {
        guard MediaSourceKind(url: sourceURL) == .sftp, let host = sourceURL.host(),
              let credential = NetworkCredentialStore.credential(kind: .sftp, host: host, store: store)
        else { return nil }
        self.host = host
        self.port = sourceURL.port ?? Self.defaultPort
        self.credential = credential
        self.startPath = Self.path(of: sourceURL)
    }

    var root: URL {
        url(forPath: startPath, isDirectory: true)
    }

    var accountLabel: String? { credential.username }

    // MARK: - Listing

    func list(directory: URL) async throws -> [ConnectorEntry] {
        let path = Self.path(of: directory)
        return try await withConnection { connection in
            try entries(in: path, connection: connection)
        }
    }

    /// One connection for the whole walk: an SSH handshake per folder would
    /// dominate the scan.
    func enumerateVideos(under folder: URL) async throws -> [ConnectorEntry] {
        let start = Self.path(of: folder)
        return try await withConnection { connection in
            var videos: [ConnectorEntry] = []
            var queue = [start]
            var visited: Set<String> = [start]
            var listed = 0
            while !queue.isEmpty, listed < ConnectorWalk.maxDirectories {
                let path = queue.removeFirst()
                let entries: [ConnectorEntry]
                if listed == 0 {
                    entries = try self.entries(in: path, connection: connection)
                } else {
                    entries = (try? self.entries(in: path, connection: connection)) ?? []
                }
                listed += 1
                for entry in entries where !entry.isHidden {
                    if entry.isDirectory {
                        let child = Self.path(of: entry.url)
                        if visited.insert(child).inserted { queue.append(child) }
                    } else if entry.isVideo {
                        videos.append(entry)
                    }
                }
            }
            return videos
        }
    }

    private func entries(in path: String, connection: SFTPConnection) throws -> [ConnectorEntry] {
        let listing: [SFTPEntry]
        do {
            listing = try connection.contentsOfDirectory(atPath: path)
        } catch {
            throw Self.connectorError(error, host: host)
        }
        let base = path.hasSuffix("/") ? path : path + "/"
        return ConnectorWalk.sorted(listing.map { entry in
            ConnectorEntry(
                name: entry.name,
                url: url(forPath: base + entry.name, isDirectory: entry.isDirectory),
                isDirectory: entry.isDirectory,
                size: entry.isDirectory || entry.size < 0 ? nil : entry.size,
                modified: entry.modified
            )
        })
    }

    // MARK: - Connections

    /// Connects without logging in, for the key the user approves.
    static func fetchHostKey(host: String, port: Int) async throws -> SFTPHostKey {
        try await Blocking.run {
            do {
                return try SFTPConnection(host: host, port: port, timeoutMilliseconds: timeoutMilliseconds).hostKey
            } catch {
                throw connectorError(error, host: host)
            }
        }
    }

    /// The login directory, where browsing starts after linking.
    func homeDirectory() async throws -> String {
        try await withConnection { connection in
            do {
                return try connection.homeDirectory()
            } catch {
                throw Self.connectorError(error, host: host)
            }
        }
    }

    /// Runs `work` on a connected, authenticated session whose host key
    /// matches the pinned one, off the main actor. Cancelling the task
    /// aborts the connection.
    func withConnection<T: Sendable>(_ work: @escaping @Sendable (SFTPConnection) throws -> T) async throws -> T {
        let box = ConnectionBox()
        return try await withTaskCancellationHandler {
            try await Blocking.run { [self] in
                let connection = try Self.connect(
                    host: host, port: port, credential: credential, pinned: HostKeyStore.pinnedFingerprint(host: host, port: port)
                )
                box.set(connection)
                return try work(connection)
            }
        } onCancel: {
            box.abort()
        }
    }

    /// Connects, checks the host key against `pinned`, and logs in.
    static func connect(
        host: String,
        port: Int,
        credential: NetworkCredential,
        pinned: String?
    ) throws -> SFTPConnection {
        let connection: SFTPConnection
        do {
            connection = try SFTPConnection(host: host, port: port, timeoutMilliseconds: timeoutMilliseconds)
        } catch {
            throw connectorError(error, host: host)
        }
        guard let pinned else { throw ConnectorError.hostKeyUnverified(host: host) }
        guard pinned == connection.hostKeyFingerprint else { throw ConnectorError.hostKeyMismatch(host: host) }
        do {
            try connection.authenticate(withUsername: credential.username, password: credential.password)
        } catch {
            throw connectorError(error, host: host)
        }
        return connection
    }

    /// Reads a file over its own connection, opened at the first read.
    static func byteSource(for itemURL: URL, store: any SecretStore = KeychainStore.shared) -> RemoteFileByteSource? {
        guard MediaSourceKind(url: itemURL) == .sftp, let host = itemURL.host() else { return nil }
        let port = itemURL.port ?? defaultPort
        let path = path(of: itemURL)
        let credential = NetworkCredentialStore.credential(kind: .sftp, host: host, store: store)
        let pinned = HostKeyStore.pinnedFingerprint(host: host, port: port, store: store)
        return RemoteFileByteSource(kind: .sftp, host: host) {
            guard let credential else { throw ConnectorError.signInRequired(provider: host) }
            let connection = try connect(host: host, port: port, credential: credential, pinned: pinned)
            let size: Int64
            do {
                size = try connection.openFile(atPath: path)
            } catch {
                throw connectorError(error, host: host)
            }
            return SFTPFileConnection(connection: connection, fileSize: size)
        }
    }

    // MARK: - Helpers

    func url(forPath path: String, isDirectory: Bool) -> URL {
        let segments = path.split(separator: "/").map(String.init)
        return SourceURL.server(
            scheme: "sftp",
            host: host,
            port: port == Self.defaultPort ? nil : port,
            pathSegments: segments,
            isDirectory: isDirectory
        )!
    }

    /// The absolute server path of an `sftp://` URL.
    static func path(of url: URL) -> String {
        "/" + SourceURL.pathSegments(of: url).joined(separator: "/")
    }

    static func connectorError(_ error: Error, host: String) -> Error {
        if error is ConnectorError || error is CancellationError { return error }
        guard let failure = RemoteFileFailure(error) else { return ConnectorError.unreachable(host: host) }
        return failure.connectorError(kind: .sftp, host: host)
    }
}

/// Holds the connection a blocking task opens, so cancellation can abort it.
private nonisolated final class ConnectionBox: @unchecked Sendable {
    private let lock = NSLock()
    private var connection: SFTPConnection?
    private var aborted = false

    func set(_ connection: SFTPConnection) {
        let abort = lock.withLock { () -> Bool in
            self.connection = connection
            return aborted
        }
        if abort { connection.abort() }
    }

    func abort() {
        let connection = lock.withLock { () -> SFTPConnection? in
            aborted = true
            return self.connection
        }
        connection?.abort()
    }
}

/// Runs blocking C-library work on a global queue, never the main thread.
nonisolated enum Blocking {
    static func run<T: Sendable>(_ work: @escaping @Sendable () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(with: Result { try work() })
            }
        }
    }
}
