//
//  HandoffV2Session.swift
//  Edendale
//
//  One handoff on the network (SYNC-PROTOCOL.md §9): the host advertises
//  `_edendale-handoff._tcp` with DNS-SD while its pairing screen is open
//  and shows the QR code; the guest scans it and connects, by the address
//  in the code or by finding the host's service. After the pairing of
//  HandoffV2 the two sides exchange `role`, then the sender shows what it
//  is about to send and, on approval, sends the `offer`; the receiver
//  stores what validates and answers `result`. Either side may be the
//  sender: the user chose the direction before pairing.
//
//  Frames are the 4-byte-length frames AccountHandoff already reads and
//  writes. Nothing here logs addresses with ports, keys, or payloads.
//

import Foundation
import Network

@MainActor
@Observable
final class HandoffV2Session {

    enum Phase: Equatable {
        case idle
        /// Showing the code; `expires` is when a new one is made.
        case hosting(qr: URL, expires: Date)
        case connecting
        case pairing
        /// The channel is up; roles are being exchanged.
        case paired(peer: String)
        /// Sender: the user decides whether to send what the offer holds.
        case awaitingApproval(peer: String, platform: String?)
        /// Receiver: waiting for the sender's offer, or storing it.
        case receiving(peer: String)
        case sending(peer: String)
        case finished(stored: [String], failed: [HandoffV2.ResultMessage.Failure], peer: String)
        case declined(peer: String)
        case failed(String)
    }

    let role: HandoffV2.Role
    let deviceName: String
    private(set) var phase: Phase = .idle

    /// Receiver side: stores the offer and reports what was kept. Set
    /// before starting.
    @ObservationIgnored var receiveHandler: ((HandoffV2.Offer) async -> HandoffV2.ResultMessage)?
    /// Sender side: the offer to send to a peer on `platform`, built when
    /// the user approves.
    @ObservationIgnored var offerBuilder: ((_ peerPlatform: String?) -> HandoffV2.Offer)?

    @ObservationIgnored private let queue = DispatchQueue(label: "Edendale.HandoffV2")
    @ObservationIgnored private var listener: NWListener?
    @ObservationIgnored private var browser: NWBrowser?
    @ObservationIgnored private var connection: NWConnection?
    @ObservationIgnored private var hostPairing: HandoffV2.HostPairing?
    @ObservationIgnored private var busy = false
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var codeTimer: Task<Void, Never>?
    @ObservationIgnored private var approval: CheckedContinuation<Bool, Never>?

    init(role: HandoffV2.Role, deviceName: String = SyncStamper.deviceName) {
        self.role = role
        self.deviceName = deviceName
    }

    // MARK: - Host

    /// Advertises the service and shows a code. One handoff at a time.
    func startHosting() {
        guard listener == nil else { return }
        do {
            let listener = try NWListener(using: .tcp)
            var txt = NWTXTRecord()
            txt["v"] = String(HandoffV2.version)
            txt["name"] = deviceName
            txt["platform"] = SyncStamper.platformName
            listener.service = NWListener.Service(name: deviceName, type: HandoffV2.serviceType, txtRecord: txt)
            listener.stateUpdateHandler = { [weak self] state in
                Task { @MainActor in self?.listenerStateChanged(state) }
            }
            listener.newConnectionHandler = { [weak self] connection in
                Task { @MainActor in self?.accept(connection) }
            }
            listener.start(queue: queue)
            self.listener = listener
            SyncLog.info("handoff host: advertising")
        } catch {
            phase = .failed(HandoffV2.PairingError.cancelled.errorDescription ?? "")
            SyncLog.warning("handoff host: listener failed \(SyncLog.describe(error))")
        }
    }

    private func listenerStateChanged(_ state: NWListener.State) {
        switch state {
        case .ready:
            makeCode()
        case .failed:
            phase = .failed(String(localized: "Couldn't start listening on the local network."))
            stop()
        default:
            break
        }
    }

    /// A fresh code: an ephemeral key, a new secret, two minutes.
    private func makeCode() {
        guard let listener, let port = listener.port?.rawValue else { return }
        let pairing = HandoffV2.HostPairing(name: deviceName, host: LocalAddress.primaryIPv4(), port: Int(port))
        hostPairing = pairing
        let expires = Date().addingTimeInterval(HandoffV2.codeValidity)
        phase = .hosting(qr: pairing.qrURL, expires: expires)
        codeTimer?.cancel()
        codeTimer = Task { [weak self] in
            try? await Task.sleep(for: .seconds(HandoffV2.codeValidity))
            guard !Task.isCancelled, let self, case .hosting = self.phase else { return }
            self.makeCode()
        }
    }

    private func accept(_ incoming: NWConnection) {
        guard !busy, case .hosting = phase else {
            // One handoff at a time (§9.1): the second guest is told so.
            let queue = self.queue
            Task {
                try? await incoming.startAndWaitUntilReady(queue: queue)
                try? await incoming.sendFrame(try HandoffV2.frame(HandoffV2.Message.encode(HandoffV2.ErrorMessage(reason: "busy"))))
                incoming.cancel()
            }
            return
        }
        busy = true
        connection = incoming
        codeTimer?.cancel()
        phase = .pairing
        task = Task { [weak self] in
            guard let self else { return }
            do {
                try await incoming.startAndWaitUntilReady(queue: self.queue)
                let helloBody = try await self.timed(HandoffV2.handshakeTimeout) { try await incoming.receiveFrame() }
                let channel: HandoffV2.Channel
                do {
                    guard var pairing = self.hostPairing else { throw HandoffV2.PairingError.codeSpent }
                    let accepted = try pairing.accept(helloBody: helloBody)
                    self.hostPairing = nil
                    try await incoming.sendFrame(try HandoffV2.frame(accepted.confirmBody))
                    channel = accepted.channel
                } catch let error as HandoffV2.PairingError {
                    try? await incoming.sendFrame(try HandoffV2.frame(HandoffV2.Message.encode(HandoffV2.ErrorMessage(reason: error.reason))))
                    incoming.cancel()
                    SyncLog.warning("handoff host: pairing failed \(error.reason)")
                    self.connection = nil
                    self.busy = false
                    self.makeCode()
                    return
                }
                SyncLog.info("handoff host: channel established")
                try await self.exchange(over: incoming, channel: channel)
            } catch {
                self.fail(error)
            }
        }
    }

    // MARK: - Guest

    /// Connects to the host a scanned code names, by its address when the
    /// code has one, else by finding its service.
    func startGuest(code: HandoffV2.QRPayload) {
        guard task == nil else { return }
        phase = .connecting
        task = Task { [weak self] in
            guard let self else { return }
            do {
                let connection = try await self.connect(to: code)
                self.connection = connection
                self.phase = .pairing
                let pairing = HandoffV2.GuestPairing(payload: code)
                try await connection.sendFrame(try HandoffV2.frame(try pairing.helloBody()))
                let confirmBody = try await self.timed(HandoffV2.handshakeTimeout) { try await connection.receiveFrame() }
                let channel = try pairing.accept(confirmBody: confirmBody)
                SyncLog.info("handoff guest: channel established")
                try await self.exchange(over: connection, channel: channel)
            } catch {
                self.fail(error)
            }
        }
    }

    private func connect(to code: HandoffV2.QRPayload) async throws -> NWConnection {
        if let host = code.host, let port = code.port, let endpointPort = NWEndpoint.Port(rawValue: UInt16(clamping: port)) {
            let direct = NWConnection(host: NWEndpoint.Host(host), port: endpointPort, using: .tcp)
            do {
                try await timed(.seconds(10)) { try await direct.startAndWaitUntilReady(queue: self.queue) }
                return direct
            } catch {
                direct.cancel()
                SyncLog.info("handoff guest: direct connection failed, browsing")
            }
        }
        let endpoint = try await timed(.seconds(15)) { try await self.findService(named: code.name) }
        let connection = NWConnection(to: endpoint, using: .tcp)
        try await timed(.seconds(15)) { try await connection.startAndWaitUntilReady(queue: self.queue) }
        return connection
    }

    /// The host's service, by the instance name the code carries, or the
    /// only version-2 host on the network.
    private func findService(named name: String) async throws -> NWEndpoint {
        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: HandoffV2.serviceType, domain: nil), using: .tcp)
        self.browser = browser
        defer {
            browser.cancel()
            self.browser = nil
        }
        let gate = ResumeOnce()
        return try await withCheckedThrowingContinuation { continuation in
            browser.browseResultsChangedHandler = { results, _ in
                let candidates = results.filter { result in
                    guard case .bonjour(let txt) = result.metadata else { return false }
                    return txt["v"] == String(HandoffV2.version)
                }
                let match = candidates.first { result in
                    if case .service(let serviceName, _, _, _) = result.endpoint { return serviceName == name }
                    if case .bonjour(let txt) = result.metadata { return txt["name"] == name }
                    return false
                } ?? (candidates.count == 1 ? candidates.first : nil)
                if let match {
                    gate.run { continuation.resume(returning: match.endpoint) }
                }
            }
            browser.stateUpdateHandler = { state in
                if case .failed = state {
                    gate.run { continuation.resume(throwing: HandoffV2.PairingError.cancelled) }
                }
            }
            browser.start(queue: queue)
        }
    }

    // MARK: - After the channel (§9.6)

    private func exchange(over connection: NWConnection, channel: HandoffV2.Channel) async throws {
        var channel = channel
        try await send(HandoffV2.RoleMessage(role: role, name: deviceName, platform: SyncStamper.platformName), over: connection, channel: &channel)
        let peerRole: HandoffV2.RoleMessage = try await receive(over: connection, channel: &channel, timeout: HandoffV2.handshakeTimeout)
        guard peerRole.role != role else {
            try await send(HandoffV2.ErrorMessage(reason: "role", inChannel: true), over: connection, channel: &channel)
            throw HandoffV2.PairingError.role
        }
        let peer = String(peerRole.name.prefix(SyncFileFormat.maxDeviceNameCharacters))
        phase = .paired(peer: peer)

        switch role {
        case .sender:
            phase = .awaitingApproval(peer: peer, platform: peerRole.platform)
            let approved = await withCheckedContinuation { continuation in approval = continuation }
            approval = nil
            guard approved, let offer = offerBuilder?(peerRole.platform) else {
                try await send(HandoffV2.Offer.declined, over: connection, channel: &channel)
                phase = .declined(peer: peer)
                stop()
                return
            }
            phase = .sending(peer: peer)
            try await send(offer, over: connection, channel: &channel)
            SyncLog.info("handoff sender: offer sent location=\(offer.location != nil) accounts=\(offer.accounts?.count ?? 0) logins=\(offer.logins?.count ?? 0)")
            let result: HandoffV2.ResultMessage = try await receive(over: connection, channel: &channel, timeout: HandoffV2.resultTimeout)
            phase = .finished(stored: result.stored, failed: result.failed, peer: peer)
            SyncLog.info("handoff sender: result stored=\(result.stored.count) failed=\(result.failed.count)")

        case .receiver:
            phase = .receiving(peer: peer)
            let offer: HandoffV2.Offer = try await receive(over: connection, channel: &channel, timeout: HandoffV2.offerTimeout)
            guard offer.status == .approved else {
                phase = .declined(peer: peer)
                stop()
                return
            }
            SyncLog.info("handoff receiver: offer received location=\(offer.location != nil) accounts=\(offer.accounts?.count ?? 0) logins=\(offer.logins?.count ?? 0)")
            let result = await receiveHandler?(offer) ?? HandoffV2.ResultMessage(stored: [], failed: [])
            try await send(result, over: connection, channel: &channel)
            phase = .finished(stored: result.stored, failed: result.failed, peer: peer)
            SyncLog.info("handoff receiver: result stored=\(result.stored.count) failed=\(result.failed.count)")
        }
        stop()
    }

    /// Sender: the user approved what the preview showed.
    func approve() {
        approval?.resume(returning: true)
    }

    func decline() {
        approval?.resume(returning: false)
    }

    private func send<T: Encodable>(_ message: T, over connection: NWConnection, channel: inout HandoffV2.Channel) async throws {
        let body = try channel.seal(try HandoffV2.Message.encode(message))
        try await connection.sendFrame(try HandoffV2.frame(body))
    }

    private func receive<T: Decodable>(
        over connection: NWConnection, channel: inout HandoffV2.Channel, timeout: Duration
    ) async throws -> T {
        let body = try channel.open(try await timed(timeout) { try await connection.receiveFrame() })
        let envelope = try HandoffV2.Message.envelope(from: body)
        if envelope.type == HandoffV2.Message.errorType {
            let error = try HandoffV2.Message.decode(HandoffV2.ErrorMessage.self, from: body)
            throw error.reason == "role" ? HandoffV2.PairingError.role : HandoffV2.PairingError.remote(error.reason)
        }
        return try HandoffV2.Message.decode(T.self, from: body)
    }

    // MARK: - Ending

    func cancel() {
        approval?.resume(returning: false)
        approval = nil
        task?.cancel()
        stop()
        if case .finished = phase {} else if case .declined = phase {} else {
            phase = .idle
        }
    }

    private func stop() {
        codeTimer?.cancel()
        codeTimer = nil
        connection?.cancel()
        connection = nil
        browser?.cancel()
        browser = nil
        listener?.cancel()
        listener = nil
        hostPairing = nil
        busy = false
        task = nil
    }

    private func fail(_ error: Error) {
        if error is CancellationError { return }
        let message: String
        if let pairing = error as? HandoffV2.PairingError {
            message = pairing.errorDescription ?? ""
        } else if let handoff = error as? AccountHandoff.HandoffError {
            message = handoff.errorDescription ?? ""
        } else {
            message = HandoffV2.PairingError.cancelled.errorDescription ?? ""
        }
        SyncLog.warning("handoff \(role.rawValue): failed \(SyncLog.describe(error))")
        phase = .failed(message)
        stop()
    }

    private func timed<T: Sendable>(_ timeout: Duration, _ work: @escaping @Sendable () async throws -> T) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await work() }
            group.addTask {
                try await Task.sleep(for: timeout)
                throw HandoffV2.PairingError.timedOut
            }
            let result = try await group.next()!
            group.cancelAll()
            return result
        }
    }
}

// MARK: - Local address

/// The IPv4 address the QR code offers the guest for a direct connection.
nonisolated enum LocalAddress {
    static func primaryIPv4() -> String? {
        var addresses: [(name: String, address: String)] = []
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var pointer: UnsafeMutablePointer<ifaddrs>? = first
        while let interface = pointer {
            defer { pointer = interface.pointee.ifa_next }
            guard let addr = interface.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  (Int32(interface.pointee.ifa_flags) & IFF_LOOPBACK) == 0,
                  (Int32(interface.pointee.ifa_flags) & IFF_UP) != 0
            else { continue }
            var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(addr, socklen_t(addr.pointee.sa_len), &buffer, socklen_t(buffer.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            addresses.append((String(cString: interface.pointee.ifa_name), String(cString: buffer)))
        }
        // Wi-Fi and Ethernet first, never link-local or VPN addresses.
        let usable = addresses.filter { !$0.address.hasPrefix("169.254.") && !$0.name.hasPrefix("utun") && !$0.name.hasPrefix("ipsec") }
        return (usable.first { $0.name == "en0" } ?? usable.first { $0.name.hasPrefix("en") } ?? usable.first)?.address
    }
}
