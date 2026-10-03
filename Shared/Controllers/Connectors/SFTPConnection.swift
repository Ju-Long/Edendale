//
//  SFTPConnection.swift
//  Edendale
//
//  One SSH session with an SFTP channel, over SwiftNIO SSH: curve25519 and
//  ECDH key exchange, Ed25519 and ECDSA host keys, and AES-GCM, which current
//  OpenSSH servers offer. (The libssh2 inside libvlc only has finite-field
//  Diffie-Hellman and `ssh-rsa`, which OpenSSH 8.8 and later refuse.)
//
//  Calls block the calling thread, which must not be one of NIO's event
//  loops: SFTPConnector runs them on a global queue, and playback reads come
//  from FFmpeg's worker queue. Connecting stops after the key exchange, so
//  the host key is checked against its pin before any password is sent.
//  abort() may be called from any thread; it fails the call in progress and
//  every later one.
//

import CryptoKit
import Foundation
import NIOCore
import NIOPosix
import NIOSSH

/// A directory entry from an SFTP listing. Symbolic links are resolved.
nonisolated struct SFTPEntry: Sendable {
    let name: String
    let isDirectory: Bool
    /// Bytes, or -1 when the server didn't say.
    let size: Int64
    let modified: Date?
}

/// A server's host key, as the link flow shows it for approval.
nonisolated struct SFTPHostKey: Equatable, Sendable {
    /// `ssh-ed25519`, `ecdsa-sha2-nistp256`, …
    let algorithm: String
    /// `SHA256:<base64>`, as `ssh-keygen -l` prints it.
    let fingerprint: String

    /// The key type as `ssh-keygen -l` names it.
    var typeName: String {
        if algorithm == "ssh-ed25519" { return "ED25519" }
        if algorithm.hasPrefix("ecdsa-") { return "ECDSA" }
        return algorithm
    }
}

nonisolated final class SFTPConnection: @unchecked Sendable {

    /// How the session signs in. Linking uses passwords; keys are for tests
    /// today and key-based login later.
    enum Login {
        case password(String)
        case ed25519(Curve25519.Signing.PrivateKey)
    }

    /// Reads are split into requests of this size, which every SFTP server
    /// must accept, and sent together.
    static let readChunkSize = 32 * 1024

    let hostKey: SFTPHostKey
    var hostKeyFingerprint: String { hostKey.fingerprint }

    private let host: String
    private let channel: Channel
    private let authentication: UserAuthentication
    private let timeout: TimeAmount

    private let lock = NSLock()
    private var sftp: SFTPChannelHandler?
    private var file: Data?
    private var aborted = false

    /// Connects and completes the key exchange, stopping before sign-in.
    init(host: String, port: Int, timeoutMilliseconds: Int) throws {
        let timeout = TimeAmount.milliseconds(Int64(timeoutMilliseconds))
        let loop = MultiThreadedEventLoopGroup.singleton.next()
        let hostKeyReply = OncePromise<NIOSSHPublicKey>(on: loop)
        let authentication = UserAuthentication(on: loop)
        let validator = HostKeyCapture(hostKeyReply)

        let bootstrap = ClientBootstrap(group: loop)
            .connectTimeout(timeout)
            .channelInitializer { channel in
                channel.eventLoop.makeCompletedFuture {
                    let ssh = NIOSSHHandler(
                        role: .client(.init(userAuthDelegate: authentication, serverAuthDelegate: validator)),
                        allocator: channel.allocator,
                        inboundChildChannelInitializer: nil
                    )
                    try channel.pipeline.syncOperations.addHandler(ssh)
                    try channel.pipeline.syncOperations.addHandler(
                        SessionEvents(hostKey: hostKeyReply, authentication: authentication)
                    )
                }
            }
        let channel: Channel
        do {
            channel = try bootstrap.connect(host: host, port: port).wait()
        } catch {
            // Every promise must be completed, or NIO traps in debug builds.
            hostKeyReply.fail(SessionClosed())
            authentication.close(SessionClosed())
            throw ConnectorError.unreachable(host: host)
        }
        let hostKey: SFTPHostKey
        do {
            let openSSH = String(openSSHPublicKey: try hostKeyReply.wait(timeout: timeout))
            guard let fingerprint = SFTPProtocol.fingerprint(openSSHPublicKey: openSSH) else { throw SessionClosed() }
            hostKey = SFTPHostKey(algorithm: String(openSSH.prefix { $0 != " " }), fingerprint: fingerprint)
        } catch {
            channel.close(promise: nil)
            authentication.close(SessionClosed())
            // No algorithms in common, rather than a server that isn't there
            // or doesn't speak SSH.
            if let error = error as? NIOSSHError, error.type == .keyExchangeNegotiationFailure {
                throw ConnectorError.secureConnectionFailed(host: host)
            }
            throw ConnectorError.unreachable(host: host)
        }
        self.host = host
        self.timeout = timeout
        self.channel = channel
        self.authentication = authentication
        self.hostKey = hostKey
    }

    deinit {
        // A connection used only for its host key never signs in; NIO traps
        // in debug builds on promises left pending.
        authentication.close(SessionClosed())
        channel.close(promise: nil)
    }

    // MARK: - Signing in

    func authenticate(withUsername username: String, password: String) throws {
        try authenticate(withUsername: username, login: .password(password))
    }

    /// Signs in, then starts SFTP.
    func authenticate(withUsername username: String, login: Login) throws {
        try checkActive()
        let offer: NIOSSHUserAuthenticationOffer.Offer
        switch login {
        case .password(let password):
            offer = .password(.init(password: password))
        case .ed25519(let key):
            offer = .privateKey(.init(privateKey: NIOSSHPrivateKey(ed25519Key: key)))
        }
        authentication.offer(NIOSSHUserAuthenticationOffer(username: username, serviceName: "", offer: offer))
        do {
            try authentication.outcome.wait(timeout: timeout)
        } catch let refusal as LoginRefused {
            if case .password = login, !refusal.acceptedMethods.contains(.password) {
                throw ConnectorError.passwordLoginUnavailable(host: host)
            }
            throw ConnectorError.authenticationFailed(host: host)
        } catch {
            throw mapped(error)
        }
        let sftp = try startSFTP()
        lock.withLock { self.sftp = sftp }
    }

    /// Opens a session channel, asks for the `sftp` subsystem, and agrees
    /// on protocol version 3.
    private func startSFTP() throws -> SFTPChannelHandler {
        let channel = self.channel
        let handler = SFTPChannelHandler(eventLoop: channel.eventLoop, requestTimeout: timeout)
        let child = channel.eventLoop.makePromise(of: Channel.self)
        channel.eventLoop.execute {
            do {
                let ssh = try channel.pipeline.syncOperations.handler(type: NIOSSHHandler.self)
                ssh.createChannel(child, channelType: .session) { childChannel, _ in
                    childChannel.eventLoop.makeCompletedFuture {
                        try childChannel.pipeline.syncOperations.addHandler(handler)
                    }
                }
            } catch {
                child.fail(error)
            }
        }
        let sftpChannel: Channel
        do {
            sftpChannel = try wait(child.futureResult)
        } catch {
            handler.subsystemReady.fail(SessionClosed())
            throw error
        }
        do {
            sftpChannel.triggerUserOutboundEvent(
                SSHChannelRequestEvent.SubsystemRequest(subsystem: "sftp", wantReply: true),
                promise: nil
            )
            try handler.subsystemReady.wait(timeout: timeout)
            guard case .version(let version) = try wait(handler.initialize()), version >= SFTPProtocol.version else {
                throw ConnectorError.sftpUnavailable(host: host)
            }
            return handler
        } catch {
            sftpChannel.close(promise: nil)
            if isAborted { throw CancellationError() }
            throw error as? ConnectorError ?? ConnectorError.sftpUnavailable(host: host)
        }
    }

    // MARK: - Browsing

    /// The absolute path of the login directory.
    func homeDirectory() throws -> String {
        let sftp = try activeSFTP()
        let response = try wait(sftp.request { SFTPProtocol.realpath(id: $0, path: ".") })
        guard case .name(_, let entries) = response, let home = entries.first?.filename else {
            throw failure(response, path: ".", isFolder: true)
        }
        return home
    }

    func contentsOfDirectory(atPath path: String) throws -> [SFTPEntry] {
        let sftp = try activeSFTP()
        let opened = try wait(sftp.request { SFTPProtocol.opendir(id: $0, path: path) })
        guard case .handle(_, let handle) = opened else { throw failure(opened, path: path, isFolder: true) }
        defer { _ = sftp.request { SFTPProtocol.close(id: $0, handle: handle) } }

        let prefix = path.hasSuffix("/") ? path : path + "/"
        var entries: [SFTPEntry] = []
        while true {
            let response = try wait(sftp.request { SFTPProtocol.readdir(id: $0, handle: handle) })
            switch response {
            case .name(_, let names):
                for name in names where name.filename != "." && name.filename != ".." {
                    var attributes = name.attributes
                    if attributes.isSymbolicLink {
                        // Follow the link to what it points at; skip it if it's broken.
                        let target = try? wait(sftp.request { SFTPProtocol.stat(id: $0, path: prefix + name.filename) })
                        guard case .attributes(_, let resolved)? = target else { continue }
                        attributes = resolved
                    }
                    entries.append(SFTPEntry(
                        name: name.filename,
                        isDirectory: attributes.isDirectory,
                        size: attributes.size.map { Int64(clamping: $0) } ?? -1,
                        modified: attributes.modified.map { Date(timeIntervalSince1970: TimeInterval($0)) }
                    ))
                }
            case .status(_, let code, _) where code == SFTPProtocol.Status.eof.rawValue:
                return entries
            default:
                throw failure(response, path: path, isFolder: true)
            }
        }
    }

    // MARK: - Reading

    /// Opens one file for reading (a connection reads one file at a time)
    /// and returns its size, or -1 when the server doesn't say.
    func openFile(atPath path: String) throws -> Int64 {
        let sftp = try activeSFTP()
        if let previous = lock.withLock({ () -> Data? in
            defer { file = nil }
            return file
        }) {
            _ = sftp.request { SFTPProtocol.close(id: $0, handle: previous) }
        }
        let opened = try wait(sftp.request { SFTPProtocol.open(id: $0, path: path) })
        guard case .handle(_, let handle) = opened else { throw failure(opened, path: path, isFolder: false) }
        lock.withLock { file = handle }

        let stat = try? wait(sftp.request { SFTPProtocol.fstat(id: $0, handle: handle) })
        guard case .attributes(_, let attributes)? = stat, let size = attributes.size else { return -1 }
        return Int64(clamping: size)
    }

    /// Reads up to `length` bytes at `offset` of the open file, as several
    /// requests in flight at once. Returns the byte count, which is short
    /// when the server sent less, and 0 at the end of the file.
    func read(atOffset offset: Int64, into buffer: UnsafeMutablePointer<UInt8>, length: Int) throws -> Int {
        guard length > 0, offset >= 0 else { return 0 }
        let sftp = try activeSFTP()
        guard let handle = lock.withLock({ file }) else { return 0 }

        var requests: [(start: Int, count: Int, response: EventLoopFuture<SFTPProtocol.Response>)] = []
        var position = 0
        while position < length {
            let count = min(Self.readChunkSize, length - position)
            let chunkOffset = UInt64(offset) + UInt64(position)
            requests.append((position, count, sftp.request {
                SFTPProtocol.read(id: $0, handle: handle, offset: chunkOffset, length: UInt32(count))
            }))
            position += count
        }

        var total = 0
        for request in requests {
            let response = try wait(request.response)
            switch response {
            case .data(_, let bytes):
                let count = min(bytes.count, request.count)
                if count > 0 {
                    bytes.withUnsafeBytes { raw in
                        (buffer + request.start).update(
                            from: raw.baseAddress!.assumingMemoryBound(to: UInt8.self),
                            count: count
                        )
                    }
                }
                total += count
                // A short piece ends this read; the caller asks again from here.
                if count < request.count { return total }
            case .status(_, let code, _) where code == SFTPProtocol.Status.eof.rawValue:
                return total
            default:
                if total > 0 { return total }
                throw failure(response, path: "", isFolder: false)
            }
        }
        return total
    }

    // MARK: - Stopping

    func abort() {
        lock.withLock { aborted = true }
        channel.close(promise: nil)
    }

    // MARK: - Helpers

    private var isAborted: Bool {
        lock.withLock { aborted }
    }

    private func checkActive() throws {
        if isAborted { throw CancellationError() }
    }

    private func activeSFTP() throws -> SFTPChannelHandler {
        try lock.withLock {
            if aborted { throw CancellationError() }
            guard let sftp else { throw ConnectorError.authenticationFailed(host: host) }
            return sftp
        }
    }

    /// Blocks until `future` completes or the timeout passes.
    private func wait<Value: Sendable>(_ future: EventLoopFuture<Value>) throws -> Value {
        let once = OncePromise<Value>(on: future.eventLoop)
        future.whenComplete { result in
            switch result {
            case .success(let value): once.succeed(value)
            case .failure(let error): once.fail(error)
            }
        }
        do {
            return try once.wait(timeout: timeout)
        } catch {
            throw mapped(error)
        }
    }

    private func mapped(_ error: Error) -> Error {
        if isAborted { return CancellationError() }
        if error is ConnectorError || error is CancellationError { return error }
        return ConnectorError.unreachable(host: host)
    }

    /// What a status (or unexpected) reply means for the user.
    private func failure(_ response: SFTPProtocol.Response, path: String, isFolder: Bool) -> Error {
        guard case .status(_, let code, _) = response else { return ConnectorError.unreachable(host: host) }
        switch SFTPProtocol.Status(rawValue: code) {
        case .noSuchFile:
            return ConnectorError.notFound(provider: host)
        case .permissionDenied:
            return isFolder ? ConnectorError.listingFailed(path: path) : ConnectorError.accessDenied(provider: host)
        default:
            return isFolder ? ConnectorError.listingFailed(path: path) : ConnectorError.unreachable(host: host)
        }
    }
}

// MARK: - Promises

/// A promise that the first of several racing callbacks completes, with a
/// blocking wait that gives up after a timeout.
private nonisolated final class OncePromise<Value: Sendable>: @unchecked Sendable {
    private let promise: EventLoopPromise<Value>
    private let lock = NSLock()
    private var completed = false

    init(on loop: EventLoop) {
        promise = loop.makePromise(of: Value.self)
    }

    func succeed(_ value: Value) {
        if claim() { promise.succeed(value) }
    }

    func fail(_ error: Error) {
        if claim() { promise.fail(error) }
    }

    /// Never call from an event loop thread.
    func wait(timeout: TimeAmount) throws -> Value {
        let timer = promise.futureResult.eventLoop.scheduleTask(in: timeout) {
            self.fail(TimedOut())
        }
        defer { timer.cancel() }
        return try promise.futureResult.wait()
    }

    private func claim() -> Bool {
        lock.withLock {
            defer { completed = true }
            return !completed
        }
    }
}

private nonisolated struct TimedOut: Error {}
private nonisolated struct SessionClosed: Error {}

/// The server refused every login offered; `acceptedMethods` are the ones it
/// said it would take.
private nonisolated struct LoginRefused: Error {
    let acceptedMethods: NIOSSHAvailableUserAuthenticationMethods
}

// MARK: - SSH delegates

/// Records the host key and lets the key exchange finish. Nothing is sent
/// until SFTPConnector compares the key with its pin and signs in.
private nonisolated final class HostKeyCapture: NIOSSHClientServerAuthenticationDelegate, @unchecked Sendable {
    private let hostKey: OncePromise<NIOSSHPublicKey>

    init(_ hostKey: OncePromise<NIOSSHPublicKey>) {
        self.hostKey = hostKey
    }

    func validateHostKey(hostKey: NIOSSHPublicKey, validationCompletePromise: EventLoopPromise<Void>) {
        self.hostKey.succeed(hostKey)
        validationCompletePromise.succeed(())
    }
}

/// Holds NIO SSH's request for a login until `offer(_:)` supplies one, and
/// reports whether the server took it.
private nonisolated final class UserAuthentication: NIOSSHClientUserAuthenticationDelegate, @unchecked Sendable {
    let outcome: OncePromise<Void>

    private let lock = NSLock()
    private var request: EventLoopPromise<NIOSSHUserAuthenticationOffer?>?
    private var pendingOffer: NIOSSHUserAuthenticationOffer?
    private var offered = false

    init(on loop: EventLoop) {
        outcome = OncePromise(on: loop)
    }

    /// Ends sign-in: the connection closed, or never opened.
    func close(_ error: Error) {
        let request = lock.withLock { () -> EventLoopPromise<NIOSSHUserAuthenticationOffer?>? in
            defer { self.request = nil }
            return self.request
        }
        outcome.fail(error)
        request?.fail(error)
    }

    func offer(_ offer: NIOSSHUserAuthenticationOffer) {
        let request = lock.withLock { () -> EventLoopPromise<NIOSSHUserAuthenticationOffer?>? in
            guard let request = self.request else {
                pendingOffer = offer
                return nil
            }
            self.request = nil
            offered = true
            return request
        }
        request?.succeed(offer)
    }

    func nextAuthenticationType(
        availableMethods: NIOSSHAvailableUserAuthenticationMethods,
        nextChallengePromise: EventLoopPromise<NIOSSHUserAuthenticationOffer?>
    ) {
        enum Next { case offer(NIOSSHUserAuthenticationOffer), refused, wait }
        let next = lock.withLock { () -> Next in
            if offered { return .refused }
            if let pendingOffer {
                self.pendingOffer = nil
                offered = true
                return .offer(pendingOffer)
            }
            request = nextChallengePromise
            return .wait
        }
        switch next {
        case .offer(let offer):
            nextChallengePromise.succeed(offer)
        case .refused:
            // The one login offered was refused; NIO SSH ends the attempt.
            outcome.fail(LoginRefused(acceptedMethods: availableMethods))
            nextChallengePromise.succeed(nil)
        case .wait:
            break
        }
    }
}

/// Watches the connection for sign-in success and failure.
private nonisolated final class SessionEvents: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = ByteBuffer

    private let hostKey: OncePromise<NIOSSHPublicKey>
    private let authentication: UserAuthentication

    init(hostKey: OncePromise<NIOSSHPublicKey>, authentication: UserAuthentication) {
        self.hostKey = hostKey
        self.authentication = authentication
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        if event is UserAuthSuccessEvent {
            authentication.outcome.succeed(())
        }
        context.fireUserInboundEventTriggered(event)
    }

    func channelInactive(context: ChannelHandlerContext) {
        hostKey.fail(SessionClosed())
        authentication.close(SessionClosed())
        context.fireChannelInactive()
    }

    /// Always called when the connection is torn down, even when NIO SSH
    /// doesn't pass `channelInactive` along.
    func handlerRemoved(context: ChannelHandlerContext) {
        hostKey.fail(SessionClosed())
        authentication.close(SessionClosed())
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        hostKey.fail(error)
        authentication.close(error)
        context.close(promise: nil)
    }
}

// MARK: - SFTP channel

/// The SFTP session on one SSH channel: frames and matches requests and
/// replies by ID. Its state belongs to the channel's event loop; `request`
/// and `initialize` may be called from any thread.
private nonisolated final class SFTPChannelHandler: ChannelInboundHandler, @unchecked Sendable {
    typealias InboundIn = SSHChannelData
    typealias OutboundOut = SSHChannelData

    let subsystemReady: OncePromise<Void>

    private let eventLoop: EventLoop
    private let requestTimeout: TimeAmount
    private var context: ChannelHandlerContext?
    private var received = Data()
    private var nextID: UInt32 = 1
    private var pending: [UInt32: (promise: EventLoopPromise<SFTPProtocol.Response>, timer: Scheduled<Void>)] = [:]
    private var versionReply: EventLoopPromise<SFTPProtocol.Response>?
    private var closed = false

    init(eventLoop: EventLoop, requestTimeout: TimeAmount) {
        self.eventLoop = eventLoop
        self.requestTimeout = requestTimeout
        self.subsystemReady = OncePromise(on: eventLoop)
    }

    /// Sends the request `build` makes with a fresh ID.
    func request(_ build: @escaping @Sendable (UInt32) -> Data) -> EventLoopFuture<SFTPProtocol.Response> {
        eventLoop.flatSubmit {
            guard !self.closed, let context = self.context else {
                return self.eventLoop.makeFailedFuture(SessionClosed())
            }
            let id = self.nextID
            self.nextID &+= 1
            let promise = self.eventLoop.makePromise(of: SFTPProtocol.Response.self)
            let timer = self.eventLoop.scheduleTask(in: self.requestTimeout) {
                if let entry = self.pending.removeValue(forKey: id) {
                    entry.promise.fail(TimedOut())
                }
            }
            self.pending[id] = (promise, timer)
            self.write(build(id), context: context)
            return promise.futureResult
        }
    }

    /// `SSH_FXP_INIT`; the reply is the server's version.
    func initialize() -> EventLoopFuture<SFTPProtocol.Response> {
        eventLoop.flatSubmit {
            guard !self.closed, let context = self.context else {
                return self.eventLoop.makeFailedFuture(SessionClosed())
            }
            let promise = self.eventLoop.makePromise(of: SFTPProtocol.Response.self)
            self.versionReply = promise
            self.write(SFTPProtocol.initialize(), context: context)
            return promise.futureResult
        }
    }

    private func write(_ packet: Data, context: ChannelHandlerContext) {
        let buffer = context.channel.allocator.buffer(bytes: packet)
        context.writeAndFlush(wrapOutboundOut(SSHChannelData(type: .channel, data: .byteBuffer(buffer))), promise: nil)
    }

    // MARK: ChannelInboundHandler

    func handlerAdded(context: ChannelHandlerContext) {
        self.context = context
    }

    func handlerRemoved(context: ChannelHandlerContext) {
        self.context = nil
        failAll(SessionClosed())
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        let message = unwrapInboundIn(data)
        guard message.type == .channel, case .byteBuffer(let bytes) = message.data else { return }
        bytes.withUnsafeReadableBytes { received.append(contentsOf: $0) }
        do {
            for packet in try SFTPProtocol.takePackets(from: &received) {
                let response = try SFTPProtocol.decode(packet)
                if let id = response.id {
                    if let entry = pending.removeValue(forKey: id) {
                        entry.timer.cancel()
                        entry.promise.succeed(response)
                    }
                } else {
                    versionReply?.succeed(response)
                    versionReply = nil
                }
            }
        } catch {
            // Framing is lost; nothing after this can be trusted.
            failAll(error)
            context.close(promise: nil)
        }
    }

    func userInboundEventTriggered(context: ChannelHandlerContext, event: Any) {
        switch event {
        case is ChannelSuccessEvent:
            subsystemReady.succeed(())
        case is ChannelFailureEvent:
            subsystemReady.fail(SessionClosed())
        default:
            context.fireUserInboundEventTriggered(event)
        }
    }

    func channelInactive(context: ChannelHandlerContext) {
        failAll(SessionClosed())
        context.fireChannelInactive()
    }

    func errorCaught(context: ChannelHandlerContext, error: Error) {
        failAll(error)
        context.close(promise: nil)
    }

    private func failAll(_ error: Error) {
        closed = true
        subsystemReady.fail(error)
        let waiting = pending
        pending.removeAll()
        for entry in waiting.values {
            entry.timer.cancel()
            entry.promise.fail(error)
        }
        versionReply?.fail(error)
        versionReply = nil
    }
}
