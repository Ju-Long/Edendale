//
//  HandoffV2.swift
//  Edendale
//
//  Version 2 of the device-to-device handoff (SYNC-PROTOCOL.md §9, main
//  915b40d): how a device gives another, on any platform, its sync
//  location with the login that reaches it, and any other linked accounts
//  and server logins the user ticks. Apple devices pair with non-Apple
//  devices by QR code only: the host shows an ephemeral X25519 public key
//  and a 16-byte secret, the guest scans them, and both derive one
//  AES-256-GCM key (§9.3). Apple-to-Apple keeps AccountHandoff's
//  DeviceDiscoveryUI path for the account requests it already serves.
//
//  Everything here is transport-free: the pairing halves take and return
//  frames, so HandoffV2Tests run the whole exchange in memory against
//  Appendix A's vectors. HandoffV2Session puts it on the network. Nothing
//  here logs a key, a secret, a proof, a token, or a password.
//

import CryptoKit
import Foundation

nonisolated enum HandoffV2 {
    static let version = 2
    static let serviceType = "_edendale-handoff._tcp"
    static let urlScheme = "edendale"
    static let urlHost = "handoff"
    static let label = "edendale-handoff-v2"
    static let labelBytes = Data(label.utf8)
    /// §9.2: a frame body is at most 64 KiB.
    static let maxFrameSize = 65_536
    /// §9.3: a QR code is valid for two minutes and one `hello`.
    static let codeValidity: TimeInterval = 120
    static let handshakeTimeout: Duration = .seconds(30)
    static let offerTimeout: Duration = .seconds(600)
    static let resultTimeout: Duration = .seconds(120)
    static let secretLength = 16

    // MARK: - Roles and platforms

    /// Who holds the login (§9.6), independent of who showed the code.
    enum Role: String, Codable, Sendable, CaseIterable {
        case sender
        case receiver
    }

    /// The platforms sharing one Google OAuth client with this app (§9.8):
    /// a Google refresh token moves only between them. They are also the
    /// only ones that reach the app's own iCloud Drive folder directly.
    static let applePlatforms: Set<String> = ["ios", "ipados", "macos", "tvos", "visionos"]

    static func isApplePlatform(_ platform: String?) -> Bool {
        guard let platform else { return false }
        return applePlatforms.contains(platform.lowercased())
    }

    static func sharesGoogleClient(withPlatform platform: String?) -> Bool {
        isApplePlatform(platform)
    }

    // MARK: - Key schedule (§9.3)

    /// `label ‖ pkH ‖ pkG`, 83 bytes.
    static func transcript(hostKey: Data, guestKey: Data) -> Data {
        labelBytes + hostKey + guestKey
    }

    /// `HMAC-SHA256(key = secret, msg = "guest" ‖ transcript)`.
    static func proof(secret: Data, transcript: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data("guest".utf8) + transcript, using: SymmetricKey(data: secret)))
    }

    /// `HKDF-SHA256(ikm = shared, salt = secret, info = transcript, L = 32)`.
    static func channelKey(shared: Data, secret: Data, transcript: Data) -> SymmetricKey {
        HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: shared), salt: secret, info: transcript, outputByteCount: 32
        )
    }

    /// `HMAC-SHA256(key = key, msg = "host" ‖ transcript)`.
    static func confirmation(key: SymmetricKey, transcript: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data("host".utf8) + transcript, using: key))
    }

    /// Byte-for-byte comparison in constant time.
    static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var difference: UInt8 = 0
        for (x, y) in zip(a, b) { difference |= x ^ y }
        return difference == 0
    }

    static func randomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        precondition(status == errSecSuccess, "SecRandomCopyBytes failed")
        return Data(bytes)
    }

    // MARK: - QR code (§9.3)

    struct QRPayload: Equatable, Sendable, Identifiable {
        var id: String { PKCE.base64URL(hostPublicKey) }
        var name: String
        var host: String?
        var port: Int?
        var hostPublicKey: Data
        var secret: Data

        /// `edendale://handoff?v=2&n=…&h=…&p=…&k=…&s=…`, with `h` and `p`
        /// only when the host has an address to give.
        var url: URL {
            var components = URLComponents()
            components.scheme = HandoffV2.urlScheme
            components.host = HandoffV2.urlHost
            var items = [URLQueryItem(name: "v", value: String(HandoffV2.version)), URLQueryItem(name: "n", value: name)]
            if let host { items.append(URLQueryItem(name: "h", value: host)) }
            if let port { items.append(URLQueryItem(name: "p", value: String(port))) }
            items.append(URLQueryItem(name: "k", value: PKCE.base64URL(hostPublicKey)))
            items.append(URLQueryItem(name: "s", value: PKCE.base64URL(secret)))
            components.queryItems = items
            // Spaces in a name are `%20`, never `+`.
            components.percentEncodedQuery = components.percentEncodedQuery?.replacingOccurrences(of: "+", with: "%2B")
            return components.url!
        }

        /// Parses a scanned code; `nil` for anything but a version-2 code
        /// with a 32-byte key and a 16-byte secret.
        init?(url: URL) {
            guard url.scheme?.lowercased() == HandoffV2.urlScheme, url.host()?.lowercased() == HandoffV2.urlHost,
                  let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
            else { return nil }
            func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
            guard value("v") == String(HandoffV2.version),
                  let name = value("n"), !name.isEmpty,
                  let key = value("k").flatMap(PKCE.decodeBase64URL), key.count == 32,
                  let secret = value("s").flatMap(PKCE.decodeBase64URL), secret.count == HandoffV2.secretLength
            else { return nil }
            self.name = name
            host = value("h").flatMap { $0.isEmpty ? nil : $0 }
            port = value("p").flatMap(Int.init)
            hostPublicKey = key
            self.secret = secret
        }

        init(name: String, host: String? = nil, port: Int? = nil, hostPublicKey: Data, secret: Data) {
            self.name = name
            self.host = host
            self.port = port
            self.hostPublicKey = hostPublicKey
            self.secret = secret
        }
    }

    // MARK: - Pairing

    enum PairingError: Error, LocalizedError, Equatable {
        /// The guest's proof did not verify (host side), or the host's
        /// confirmation did not (guest side): the code was wrong or expired.
        case secret
        /// A second `hello` on a code already used, or one after it expired.
        case codeSpent
        case unsupportedVersion
        case malformed
        case frameTooLarge
        /// The other side chose the same role.
        case role
        case busy
        /// The other side reported an error.
        case remote(String)
        case cancelled
        case timedOut

        var errorDescription: String? {
            switch self {
            case .secret, .codeSpent:
                String(localized: "The code has expired or was wrong. Show a new code and try again.")
            case .unsupportedVersion:
                String(localized: "Update Edendale on both devices, then try again.")
            case .malformed, .frameTooLarge:
                String(localized: "The other device sent something Edendale couldn't use. Try again.")
            case .role:
                String(localized: "Both devices chose the same role. Choose who sends on one device.")
            case .busy:
                String(localized: "The other device is already handing off to someone. Try again in a moment.")
            case .remote(let reason):
                String(localized: "The other device stopped the handoff (\(reason)).")
            case .cancelled:
                String(localized: "The handoff was cancelled.")
            case .timedOut:
                String(localized: "The other device didn't answer in time.")
            }
        }

        /// The `reason` an error frame carries for this failure (§9.9).
        var reason: String {
            switch self {
            case .secret, .codeSpent: "secret"
            case .unsupportedVersion: "version"
            case .malformed, .frameTooLarge: "malformed"
            case .role: "role"
            case .busy: "busy"
            case .remote(let reason): reason
            case .cancelled, .timedOut: "malformed"
            }
        }
    }

    /// The host's half: makes the code, checks the guest's `hello`, and
    /// answers `confirm`. One `hello` per code; the key material is
    /// dropped once the channel exists.
    struct HostPairing {
        let payload: QRPayload
        private let privateKey: Curve25519.KeyAgreement.PrivateKey
        private let madeAt: Date
        private var spent = false

        init(name: String, host: String? = nil, port: Int? = nil, now: Date = Date()) {
            privateKey = Curve25519.KeyAgreement.PrivateKey()
            madeAt = now
            payload = QRPayload(
                name: name, host: host, port: port,
                hostPublicKey: privateKey.publicKey.rawRepresentation,
                secret: HandoffV2.randomBytes(HandoffV2.secretLength)
            )
        }

        /// For tests: a host with a known key and secret.
        init(name: String, privateKey: Curve25519.KeyAgreement.PrivateKey, secret: Data, now: Date = Date()) {
            self.privateKey = privateKey
            madeAt = now
            payload = QRPayload(name: name, hostPublicKey: privateKey.publicKey.rawRepresentation, secret: secret)
        }

        var qrURL: URL { payload.url }

        /// Verifies the guest's `hello` frame body and returns the `confirm`
        /// frame body with the established channel. Throws `.secret` on a
        /// wrong proof (the caller sends `error`, reason `secret`, and
        /// closes) and `.codeSpent` for a second attempt or an expired code.
        mutating func accept(helloBody: Data, now: Date = Date()) throws(PairingError) -> (confirmBody: Data, channel: Channel) {
            guard !spent, now.timeIntervalSince(madeAt) <= HandoffV2.codeValidity else { throw .codeSpent }
            spent = true
            let hello = try Message.decode(Hello.self, from: helloBody)
            guard let guestKey = PKCE.decodeBase64URL(hello.pk), guestKey.count == 32,
                  let proof = PKCE.decodeBase64URL(hello.proof)
            else { throw .malformed }
            let transcript = HandoffV2.transcript(hostKey: payload.hostPublicKey, guestKey: guestKey)
            guard HandoffV2.constantTimeEquals(proof, HandoffV2.proof(secret: payload.secret, transcript: transcript)) else {
                throw .secret
            }
            guard let guestPublic = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: guestKey),
                  let shared = try? privateKey.sharedSecretFromKeyAgreement(with: guestPublic)
            else { throw .malformed }
            let key = HandoffV2.channelKey(
                shared: shared.withUnsafeBytes { Data($0) }, secret: payload.secret, transcript: transcript
            )
            let confirm = Confirm(mac: PKCE.base64URL(HandoffV2.confirmation(key: key, transcript: transcript)))
            return (try Message.encode(confirm), Channel(key: key, isHost: true))
        }
    }

    /// The guest's half: from a scanned code, the `hello` to send and the
    /// check of the host's `confirm`.
    struct GuestPairing {
        let payload: QRPayload
        private let privateKey: Curve25519.KeyAgreement.PrivateKey
        private let transcript: Data

        init(payload: QRPayload) {
            self.init(payload: payload, privateKey: Curve25519.KeyAgreement.PrivateKey())
        }

        /// For tests: a guest with a known key.
        init(payload: QRPayload, privateKey: Curve25519.KeyAgreement.PrivateKey) {
            self.payload = payload
            self.privateKey = privateKey
            transcript = HandoffV2.transcript(hostKey: payload.hostPublicKey, guestKey: privateKey.publicKey.rawRepresentation)
        }

        var guestPublicKey: Data { privateKey.publicKey.rawRepresentation }

        /// The `hello` frame body.
        func helloBody() throws(PairingError) -> Data {
            let hello = Hello(
                pk: PKCE.base64URL(guestPublicKey),
                proof: PKCE.base64URL(HandoffV2.proof(secret: payload.secret, transcript: transcript))
            )
            return try Message.encode(hello)
        }

        /// Verifies the host's `confirm` (or reads its `error`) and returns
        /// the established channel.
        func accept(confirmBody: Data) throws(PairingError) -> Channel {
            let envelope = try Message.envelope(from: confirmBody)
            if envelope.type == Message.errorType {
                throw .remote((try? Message.decode(ErrorMessage.self, from: confirmBody))?.reason ?? "malformed")
            }
            let confirm = try Message.decode(Confirm.self, from: confirmBody)
            guard let mac = PKCE.decodeBase64URL(confirm.mac) else { throw .malformed }
            guard let hostPublic = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: payload.hostPublicKey),
                  let shared = try? privateKey.sharedSecretFromKeyAgreement(with: hostPublic)
            else { throw .malformed }
            let key = HandoffV2.channelKey(
                shared: shared.withUnsafeBytes { Data($0) }, secret: payload.secret, transcript: transcript
            )
            guard HandoffV2.constantTimeEquals(mac, HandoffV2.confirmation(key: key, transcript: transcript)) else {
                throw .secret
            }
            return Channel(key: key, isHost: false)
        }
    }

    // MARK: - Channel (§9.5)

    /// AES-256-GCM under the pairing key. Nonces are a 4-byte direction
    /// tag (1 guest → host, 2 host → guest) and an 8-byte counter per
    /// direction, from 0; the AAD is the label.
    struct Channel: Sendable {
        static let guestToHost: UInt32 = 1
        static let hostToGuest: UInt32 = 2

        private let key: SymmetricKey
        private let sendDirection: UInt32
        private let receiveDirection: UInt32
        private var sendCounter: UInt64 = 0
        private var receiveCounter: UInt64 = 0

        init(key: SymmetricKey, isHost: Bool) {
            self.key = key
            sendDirection = isHost ? Self.hostToGuest : Self.guestToHost
            receiveDirection = isHost ? Self.guestToHost : Self.hostToGuest
        }

        /// The next nonce in `direction` after `counter` frames.
        static func nonce(direction: UInt32, counter: UInt64) -> Data {
            var data = Data()
            withUnsafeBytes(of: direction.bigEndian) { data.append(contentsOf: $0) }
            withUnsafeBytes(of: counter.bigEndian) { data.append(contentsOf: $0) }
            return data
        }

        /// Encrypts one frame body: ciphertext ‖ tag.
        mutating func seal(_ plaintext: Data) throws(PairingError) -> Data {
            let nonce = Self.nonce(direction: sendDirection, counter: sendCounter)
            sendCounter += 1
            guard let box = try? AES.GCM.seal(plaintext, using: key, nonce: AES.GCM.Nonce(data: nonce), authenticating: labelBytes) else {
                throw .malformed
            }
            return box.ciphertext + box.tag
        }

        /// Decrypts one frame body; a frame that fails to open ends the
        /// connection (the caller closes).
        mutating func open(_ body: Data) throws(PairingError) -> Data {
            guard body.count >= 16 else { throw .malformed }
            let nonce = Self.nonce(direction: receiveDirection, counter: receiveCounter)
            receiveCounter += 1
            guard let sealed = try? AES.GCM.SealedBox(
                    nonce: AES.GCM.Nonce(data: nonce), ciphertext: body.dropLast(16), tag: body.suffix(16)
                  ),
                  let plaintext = try? AES.GCM.open(sealed, using: key, authenticating: labelBytes)
            else { throw .malformed }
            return plaintext
        }
    }

    // MARK: - Framing (§9.2)

    /// A 4-byte big-endian length, then the body.
    static func frame(_ body: Data) throws(PairingError) -> Data {
        guard body.count <= maxFrameSize else { throw .frameTooLarge }
        var length = UInt32(body.count).bigEndian
        return Data(bytes: &length, count: 4) + body
    }

    static func bodyLength(ofHeader header: Data) throws(PairingError) -> Int {
        guard header.count == 4 else { throw .malformed }
        let length = header.reduce(0) { ($0 << 8) | Int($1) }
        guard length > 0, length <= maxFrameSize else { throw .frameTooLarge }
        return length
    }

    // MARK: - Messages

    struct Hello: Codable, Equatable, Sendable {
        var v = HandoffV2.version
        var type = "hello"
        let pk: String
        let proof: String

        init(pk: String, proof: String) {
            self.pk = pk
            self.proof = proof
        }
    }

    struct Confirm: Codable, Equatable, Sendable {
        var v = HandoffV2.version
        var type = "confirm"
        let mac: String

        init(mac: String) {
            self.mac = mac
        }
    }

    /// Plaintext before the channel (with `v`), inside it afterwards.
    struct ErrorMessage: Codable, Equatable, Sendable {
        var v: Int? = HandoffV2.version
        var type = "error"
        let reason: String

        init(reason: String, inChannel: Bool = false) {
            self.reason = reason
            v = inChannel ? nil : HandoffV2.version
        }
    }

    struct RoleMessage: Codable, Equatable, Sendable {
        var type = "role"
        let role: Role
        let name: String
        /// This device's platform (§3 names), for the Google rule (§9.8).
        /// Not in the specification's message; a peer that omits it is
        /// treated as another ecosystem.
        var platform: String?

        init(role: Role, name: String, platform: String?) {
            self.role = role
            self.name = name
            self.platform = platform
        }
    }

    /// A cloud account on the wire, the version-1 shape with the
    /// specification's `driveId` spelling (§9.7).
    struct Account: Codable, Equatable, Sendable {
        let kind: MediaSourceKind
        let subject: String
        let email: String?
        let displayName: String?
        let refreshToken: String
        let scopes: [String]
        let driveID: String?

        enum CodingKeys: String, CodingKey {
            case kind, subject, email, displayName, refreshToken, scopes
            case driveID = "driveId"
        }

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
                kind: kind, subject: subject, email: email, displayName: displayName,
                refreshToken: refreshToken, scopes: scopes, driveID: driveID
            )
        }

        /// Absent fields are written as `null`, as the specification's
        /// example shows them.
        func encode(to encoder: Encoder) throws {
            var container = encoder.container(keyedBy: CodingKeys.self)
            try container.encode(kind, forKey: .kind)
            try container.encode(subject, forKey: .subject)
            try container.encode(email, forKey: .email)
            try container.encode(displayName, forKey: .displayName)
            try container.encode(refreshToken, forKey: .refreshToken)
            try container.encode(scopes, forKey: .scopes)
            try container.encode(driveID, forKey: .driveID)
        }
    }

    /// `offer.location` (§9.6). For a folder only the provider and space
    /// travel; a server location also carries the credential-free URL of
    /// the folder holding `Edendale/` (`url`), which the specification's
    /// example leaves implicit.
    struct OfferLocation: Codable, Equatable, Sendable {
        let provider: SyncLocation.Provider
        let space: String
        var account: Account?
        var login: AccountHandoff.Login?
        var url: String?
        var googleAccountHint: String?
        var googleFolderId: String?
    }

    struct Offer: Codable, Equatable, Sendable {
        enum Status: String, Codable, Sendable {
            case approved, declined
        }

        var type = "offer"
        let status: Status
        var location: OfferLocation?
        var accounts: [Account]?
        var logins: [AccountHandoff.Login]?

        static let declined = Offer(status: .declined)

        init(status: Status, location: OfferLocation? = nil, accounts: [Account]? = nil, logins: [AccountHandoff.Login]? = nil) {
            self.status = status
            self.location = location
            self.accounts = accounts
            self.logins = logins
        }
    }

    struct ResultMessage: Codable, Equatable, Sendable {
        struct Failure: Codable, Equatable, Sendable {
            let item: String
            /// Android may send `null`.
            let reason: String?
        }

        var type = "result"
        var stored: [String]
        var failed: [Failure]

        init(stored: [String], failed: [Failure]) {
            self.stored = stored
            self.failed = failed
        }
    }

    enum Message {
        static let errorType = "error"

        struct Envelope: Decodable {
            let v: Int?
            let type: String
        }

        static func envelope(from body: Data) throws(PairingError) -> Envelope {
            guard let envelope = try? JSONDecoder().decode(Envelope.self, from: body) else { throw .malformed }
            if let v = envelope.v, v != HandoffV2.version { throw .unsupportedVersion }
            return envelope
        }

        static func decode<T: Decodable>(_ type: T.Type, from body: Data) throws(PairingError) -> T {
            _ = try envelope(from: body)
            guard let message = try? JSONDecoder().decode(T.self, from: body) else { throw .malformed }
            return message
        }

        static func encode<T: Encodable>(_ message: T) throws(PairingError) -> Data {
            guard let data = try? JSONEncoder().encode(message) else { throw .malformed }
            guard data.count <= HandoffV2.maxFrameSize else { throw .frameTooLarge }
            return data
        }
    }

    /// The `stored` names a receiver reports (§9.6).
    static func storedName(location: SyncLocation.Provider) -> String { "location" }
    static func storedName(account: Account) -> String { "account:\(account.kind.rawValue):\(account.subject)" }
    static func storedName(login: AccountHandoff.Login) -> String { "login:\(login.kind.rawValue):\(login.host)" }
}
