//
//  AccountHandoff.swift
//  Edendale
//
//  Brings a linked account or saved server login from an iPhone or iPad to
//  Apple TV, which never receives iCloud Keychain items. Google's device
//  sign-in can't grant `drive.readonly` and Dropbox has none, so Apple TV
//  asks a nearby iPhone or iPad instead, the way YouTube is approved on a
//  phone:
//
//    1. Apple TV: Link Source → provider → Continue on iPhone or iPad opens
//       DeviceDiscoveryUI's DevicePicker, listing iPhones and iPads on the
//       same network, signed in to the TV user's iCloud account (or a family
//       member's), with Edendale installed.
//    2. Choosing one opens an NWConnection with `.applicationService`
//       parameters, which the system encrypts, and sends a Request.
//    3. The iPhone app, listening since launch (AccountHandoffCenter), asks
//       "Link Google Drive on Living Room?", offering an account already
//       linked there or the normal sign-in.
//    4. It answers with a versioned Response carrying the account (or
//       login) and closes the connection.
//    5. Apple TV refreshes a token to validate it, stores the account in
//       its own Keychain, and continues to the folder picker.
//
//  Messages are JSON behind a 4-byte big-endian length. Google allows 100
//  refresh tokens per account per client, so the TV reuses the phone's
//  token rather than minting another.
//

import Foundation
import Network

nonisolated enum AccountHandoff {
    /// Matches `NSApplicationServices` in Info.plist (Browses on tvOS,
    /// Advertises on iOS).
    static let serviceName = "Edendale-AccountHandoff"
    static let version = 1
    static let maxMessageSize = 64 * 1024

    struct Request: Codable, Equatable, Sendable {
        var version = AccountHandoff.version
        let kind: MediaSourceKind
        /// Shown in the confirmation on the phone.
        let deviceName: String
    }

    struct Response: Codable, Equatable, Sendable {
        enum Status: String, Codable, Sendable {
            case approved, declined
        }

        var version = AccountHandoff.version
        let status: Status
        var account: Account? = nil
        var login: Login? = nil

        static let declined = Response(status: .declined)
    }

    /// A cloud account, as the TV stores it.
    struct Account: Codable, Equatable, Sendable {
        let kind: MediaSourceKind
        let subject: String
        let email: String?
        let displayName: String?
        let refreshToken: String
        let scopes: [String]
        let driveID: String?

        init(_ account: CloudAccount) {
            kind = account.kind
            subject = account.subject
            email = account.email
            displayName = account.displayName
            refreshToken = account.refreshToken
            scopes = account.scopes
            driveID = account.driveID
        }

        var cloudAccount: CloudAccount {
            CloudAccount(
                kind: kind,
                subject: subject,
                email: email,
                displayName: displayName,
                refreshToken: refreshToken,
                scopes: scopes,
                driveID: driveID
            )
        }
    }

    /// A saved server login, with an SFTP server's approved host key.
    struct Login: Codable, Equatable, Sendable {
        let kind: MediaSourceKind
        /// The server host, or the account key for S3.
        let host: String
        let credential: NetworkCredential
        var port: Int? = nil
        var hostKeyFingerprint: String? = nil

        /// What to send Apple TV for a login saved on this device: the
        /// secret from the Keychain and, for SFTP, the port the server was
        /// linked on with the host key approved for that port. `nil` when
        /// the login is gone.
        static func handing(
            _ login: SavedServerLogin,
            store: any SecretStore = KeychainStore.shared
        ) -> Login? {
            guard let credential = NetworkCredentialStore.credential(kind: login.kind, host: login.host, store: store) else {
                return nil
            }
            guard login.kind == .sftp else {
                return Login(kind: login.kind, host: login.host, credential: credential)
            }
            let port = credential.port ?? SFTPConnector.defaultPort
            return Login(
                kind: .sftp,
                host: login.host,
                credential: credential,
                port: port,
                hostKeyFingerprint: HostKeyStore.pinnedFingerprint(host: login.host, port: port, store: store)
            )
        }
    }

    enum HandoffError: Error, LocalizedError, Equatable {
        case unsupportedVersion(Int)
        case malformedMessage
        case messageTooLarge
        case declined
        case connectionFailed
        case timedOut
        case wrongKind

        var errorDescription: String? {
            switch self {
            case .unsupportedVersion:
                String(localized: "Update Edendale on both devices, then try again.")
            case .malformedMessage, .messageTooLarge, .wrongKind:
                String(localized: "The other device sent something Edendale couldn't use. Try again.")
            case .declined:
                String(localized: "The request was declined on the other device.")
            case .connectionFailed:
                String(localized: "Couldn't connect to the other device. Make sure it's nearby, unlocked, and on the same network, with Edendale open.")
            case .timedOut:
                String(localized: "The other device didn't answer in time.")
            }
        }
    }

    // MARK: - Encoding

    /// A length-prefixed JSON frame.
    static func frame<T: Encodable>(_ value: T) throws -> Data {
        let body = try JSONEncoder().encode(value)
        guard body.count <= maxMessageSize else { throw HandoffError.messageTooLarge }
        var length = UInt32(body.count).bigEndian
        return Data(bytes: &length, count: 4) + body
    }

    /// The body length a 4-byte frame header announces.
    static func bodyLength(ofHeader header: Data) throws -> Int {
        guard header.count == 4 else { throw HandoffError.malformedMessage }
        let length = header.reduce(0) { ($0 << 8) | Int($1) }
        guard length > 0, length <= maxMessageSize else { throw HandoffError.messageTooLarge }
        return length
    }

    static func decodeRequest(_ body: Data) throws -> Request {
        try checkVersion(body)
        guard let request = try? JSONDecoder().decode(Request.self, from: body) else {
            throw HandoffError.malformedMessage
        }
        return request
    }

    static func decodeResponse(_ body: Data) throws -> Response {
        try checkVersion(body)
        guard let response = try? JSONDecoder().decode(Response.self, from: body) else {
            throw HandoffError.malformedMessage
        }
        return response
    }

    /// Rejects a message from a newer (or unknown) protocol version before
    /// reading anything else in it.
    private static func checkVersion(_ body: Data) throws {
        struct Envelope: Decodable { let version: Int }
        guard let envelope = try? JSONDecoder().decode(Envelope.self, from: body) else {
            throw HandoffError.malformedMessage
        }
        guard envelope.version == version else { throw HandoffError.unsupportedVersion(envelope.version) }
    }
}

// MARK: - Connection I/O

nonisolated extension NWConnection {
    /// Starts the connection and waits until it is ready.
    func startAndWaitUntilReady(queue: DispatchQueue) async throws {
        let gate = ResumeOnce()
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            stateUpdateHandler = { state in
                switch state {
                case .ready:
                    gate.run { continuation.resume() }
                case .failed, .cancelled:
                    gate.run { continuation.resume(throwing: AccountHandoff.HandoffError.connectionFailed) }
                default:
                    break
                }
            }
            start(queue: queue)
        }
    }

    func sendFrame(_ frame: Data) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            send(content: frame, completion: .contentProcessed { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume()
                }
            })
        }
    }

    /// Reads one length-prefixed frame's body.
    func receiveFrame() async throws -> Data {
        let header = try await receive(exactly: 4)
        return try await receive(exactly: AccountHandoff.bodyLength(ofHeader: header))
    }

    private func receive(exactly count: Int) async throws -> Data {
        var data = Data()
        while data.count < count {
            let (chunk, isComplete) = try await receiveChunk(maximum: count - data.count)
            data.append(chunk)
            if isComplete, data.count < count { throw AccountHandoff.HandoffError.malformedMessage }
        }
        return data
    }

    private func receiveChunk(maximum: Int) async throws -> (Data, Bool) {
        try await withCheckedThrowingContinuation { continuation in
            receive(minimumIncompleteLength: 1, maximumLength: maximum) { content, _, isComplete, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: (content ?? Data(), isComplete))
                }
            }
        }
    }
}

/// Runs a continuation's resume exactly once across racing callbacks.
nonisolated final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false

    func run(_ body: () -> Void) {
        let first = lock.withLock { () -> Bool in
            defer { done = true }
            return !done
        }
        if first { body() }
    }

    var hasRun: Bool {
        lock.withLock { done }
    }
}

// MARK: - Apple TV side

nonisolated enum AccountHandoffClient {
    private static let queue = DispatchQueue(label: "Edendale.AccountHandoff.client")

    /// Asks the device at `endpoint` for an account or login. The other
    /// side may run a full sign-in first, so this waits up to ten minutes.
    static func request(
        _ request: AccountHandoff.Request,
        endpoint: NWEndpoint,
        timeout: Duration = .seconds(600)
    ) async throws -> AccountHandoff.Response {
        let connection = NWConnection(to: endpoint, using: .applicationService)
        defer { connection.cancel() }

        let expired = ResumeOnce()
        let watchdog = Task {
            try? await Task.sleep(for: timeout)
            guard !Task.isCancelled else { return }
            expired.run {}
            connection.cancel()
        }
        defer { watchdog.cancel() }

        do {
            try await connection.startAndWaitUntilReady(queue: queue)
            try await connection.sendFrame(AccountHandoff.frame(request))
            let response = try AccountHandoff.decodeResponse(try await connection.receiveFrame())
            guard response.status == .approved else { throw AccountHandoff.HandoffError.declined }
            if let account = response.account, account.kind != request.kind {
                throw AccountHandoff.HandoffError.wrongKind
            }
            if let login = response.login, login.kind != request.kind {
                throw AccountHandoff.HandoffError.wrongKind
            }
            return response
        } catch let error as AccountHandoff.HandoffError {
            throw expired.hasRun ? AccountHandoff.HandoffError.timedOut : error
        } catch {
            throw expired.hasRun ? AccountHandoff.HandoffError.timedOut : AccountHandoff.HandoffError.connectionFailed
        }
    }
}
