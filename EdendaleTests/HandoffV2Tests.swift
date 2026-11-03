//
//  HandoffV2Tests.swift
//  EdendaleTests
//
//  SYNC-PROTOCOL.md Appendix A.2–A.4 (main, 915b40d): the primitives
//  against their RFC vectors, the QR pairing key schedule for RFC 7748's
//  X25519 pair, the QR URI and `hello` vectors, the wrong-secret case
//  that must fail before `confirm`, the channel nonces, and then the whole
//  exchange run in memory between a host and a guest, with the Google
//  Drive ecosystem rule (§9.8).
//

import CryptoKit
import Foundation
import Testing
@testable import Edendale

@Suite struct HandoffV2Tests {

    // MARK: - Vectors

    static func hex(_ string: String) -> Data {
        var data = Data()
        var index = string.startIndex
        while index < string.endIndex {
            let next = string.index(index, offsetBy: 2)
            data.append(UInt8(string[index..<next], radix: 16)!)
            index = next
        }
        return data
    }

    static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }

    /// RFC 7748 §6.1: Alice is the host, Bob the guest.
    static let alicePrivate = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    static let alicePublic = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
    static let bobPrivate = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    static let bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
    static let shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")
    static let secret = hex("000102030405060708090a0b0c0d0e0f")

    @Test func x25519MatchesRFC7748() throws {
        let alice = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Self.alicePrivate)
        let bob = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Self.bobPrivate)
        #expect(alice.publicKey.rawRepresentation == Self.alicePublic)
        #expect(bob.publicKey.rawRepresentation == Self.bobPublic)
        let fromAlice = try alice.sharedSecretFromKeyAgreement(with: bob.publicKey)
        let fromBob = try bob.sharedSecretFromKeyAgreement(with: alice.publicKey)
        #expect(fromAlice.withUnsafeBytes { Data($0) } == Self.shared)
        #expect(fromBob.withUnsafeBytes { Data($0) } == Self.shared)
    }

    @Test func hkdfMatchesRFC5869TestCase1() {
        let ikm = Self.hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b")
        let salt = Self.hex("000102030405060708090a0b0c")
        let info = Self.hex("f0f1f2f3f4f5f6f7f8f9")
        let okm = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: ikm), salt: salt, info: info, outputByteCount: 42)
        #expect(Self.hex(okm.withUnsafeBytes { Data($0) })
            == "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865")
    }

    @Test func hmacMatchesRFC4231TestCases1To4() {
        let cases: [(key: Data, data: Data, mac: String)] = [
            (Self.hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), Data("Hi There".utf8),
             "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            (Data("Jefe".utf8), Data("what do ya want for nothing?".utf8),
             "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
            (Self.hex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"), Data(repeating: 0xdd, count: 50),
             "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"),
            (Self.hex("0102030405060708090a0b0c0d0e0f10111213141516171819"), Data(repeating: 0xcd, count: 50),
             "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b")
        ]
        for testCase in cases {
            let mac = HMAC<SHA256>.authenticationCode(for: testCase.data, using: SymmetricKey(data: testCase.key))
            #expect(Self.hex(Data(mac)) == testCase.mac)
        }
    }

    @Test func keyScheduleMatchesAppendixA2() {
        let transcript = HandoffV2.transcript(hostKey: Self.alicePublic, guestKey: Self.bobPublic)
        #expect(transcript.count == 83)
        #expect(transcript.prefix(19) == Data("edendale-handoff-v2".utf8))
        #expect(Self.hex(HandoffV2.proof(secret: Self.secret, transcript: transcript))
            == "d0a69fb97e78208a179410b52ab43f2ad6805e7c5eb2354d112f40ac678db12a")
        let key = HandoffV2.channelKey(shared: Self.shared, secret: Self.secret, transcript: transcript)
        #expect(Self.hex(key.withUnsafeBytes { Data($0) })
            == "2e53bf75ce5fd3de9d5ce33c5b245c12a7bb7da14d0857d12bca26fbed33fa01")
        #expect(Self.hex(HandoffV2.confirmation(key: key, transcript: transcript))
            == "0c68fa3fa1adfdef7c07066855502ea9ace5610b06ece6a4494a7824d1cca7e1")
    }

    @Test func qrCodeMatchesAppendixA2() throws {
        let payload = HandoffV2.QRPayload(name: "Living Room TV", hostPublicKey: Self.alicePublic, secret: Self.secret)
        #expect(payload.url.absoluteString
            == "edendale://handoff?v=2&n=Living%20Room%20TV&k=hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo&s=AAECAwQFBgcICQoLDA0ODw")
        let parsed = try #require(HandoffV2.QRPayload(url: payload.url))
        #expect(parsed == payload)
        #expect(parsed.host == nil)
        #expect(parsed.port == nil)

        let addressed = HandoffV2.QRPayload(name: "Study PC", host: "192.168.1.20", port: 45_123, hostPublicKey: Self.alicePublic, secret: Self.secret)
        let reparsed = try #require(HandoffV2.QRPayload(url: addressed.url))
        #expect(reparsed.host == "192.168.1.20")
        #expect(reparsed.port == 45_123)

        // Other codes are refused: a wrong version, a short key, a short secret.
        #expect(HandoffV2.QRPayload(url: URL(string: "edendale://handoff?v=3&n=x&k=hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo&s=AAECAwQFBgcICQoLDA0ODw")!) == nil)
        #expect(HandoffV2.QRPayload(url: URL(string: "edendale://handoff?v=2&n=x&k=hSDw&s=AAECAwQFBgcICQoLDA0ODw")!) == nil)
        #expect(HandoffV2.QRPayload(url: URL(string: "edendale://handoff?v=2&n=x&k=hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo&s=AAEC")!) == nil)
        #expect(HandoffV2.QRPayload(url: URL(string: "edendale://media?v=2")!) == nil)
    }

    @Test func helloMatchesAppendixA2AndConfirmFollows() throws {
        let hostKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Self.alicePrivate)
        let guestKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Self.bobPrivate)
        var host = HandoffV2.HostPairing(name: "Living Room TV", privateKey: hostKey, secret: Self.secret)
        let guest = HandoffV2.GuestPairing(payload: try #require(HandoffV2.QRPayload(url: host.qrURL)), privateKey: guestKey)

        let helloBody = try guest.helloBody()
        let hello = try JSONDecoder().decode(HandoffV2.Hello.self, from: helloBody)
        #expect(hello.v == 2)
        #expect(hello.type == "hello")
        #expect(hello.pk == "3p7bfXt9wbTTW2HC7OQ1Nz-DQ8hbeGdNrfx-FG-IK08")
        #expect(PKCE.decodeBase64URL(hello.proof) == Self.hex("d0a69fb97e78208a179410b52ab43f2ad6805e7c5eb2354d112f40ac678db12a"))

        let (confirmBody, hostChannel) = try host.accept(helloBody: helloBody)
        let confirm = try JSONDecoder().decode(HandoffV2.Confirm.self, from: confirmBody)
        #expect(PKCE.decodeBase64URL(confirm.mac) == Self.hex("0c68fa3fa1adfdef7c07066855502ea9ace5610b06ece6a4494a7824d1cca7e1"))
        var guestChannel = try guest.accept(confirmBody: confirmBody)

        // The channel carries frames both ways.
        var hostChannel2 = hostChannel
        let sealed = try guestChannel.seal(Data("{\"type\":\"role\"}".utf8))
        #expect(try hostChannel2.open(sealed) == Data("{\"type\":\"role\"}".utf8))
        let back = try hostChannel2.seal(Data("ok".utf8))
        #expect(try guestChannel.open(back) == Data("ok".utf8))

        // A second hello on the same code is refused.
        #expect(throws: HandoffV2.PairingError.codeSpent) {
            try host.accept(helloBody: helloBody)
        }
    }

    @Test func wrongSecretFailsBeforeConfirm() throws {
        let hostKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Self.alicePrivate)
        let guestKey = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Self.bobPrivate)
        var host = HandoffV2.HostPairing(name: "Living Room TV", privateKey: hostKey, secret: Self.secret)
        var wrong = host.payload
        wrong.secret = Self.hex("ff0102030405060708090a0b0c0d0e0f")
        let guest = HandoffV2.GuestPairing(payload: wrong, privateKey: guestKey)
        let helloBody = try guest.helloBody()
        let hello = try JSONDecoder().decode(HandoffV2.Hello.self, from: helloBody)
        #expect(PKCE.decodeBase64URL(hello.proof) == Self.hex("c53f739e04e85fcbe125d5a78c6692b04957d131b6e5f0f01fc32cb8b2ca297e"))
        #expect(throws: HandoffV2.PairingError.secret) {
            try host.accept(helloBody: helloBody)
        }
        // The host answers with the error frame and the guest reads it as such.
        let errorBody = try HandoffV2.Message.encode(HandoffV2.ErrorMessage(reason: HandoffV2.PairingError.secret.reason))
        #expect(String(decoding: errorBody, as: UTF8.self).contains("\"reason\":\"secret\""))
        #expect(throws: HandoffV2.PairingError.remote("secret")) {
            try guest.accept(confirmBody: errorBody)
        }
    }

    @Test func expiredCodesAreRefused() throws {
        let made = Date()
        var host = HandoffV2.HostPairing(name: "TV", now: made)
        let guest = HandoffV2.GuestPairing(payload: host.payload)
        #expect(throws: HandoffV2.PairingError.codeSpent) {
            try host.accept(helloBody: try guest.helloBody(), now: made.addingTimeInterval(121))
        }
    }

    @Test func nonceAndAADMatchAppendixA3() {
        #expect(Self.hex(HandoffV2.Channel.nonce(direction: 1, counter: 0)) == "000000010000000000000000")
        #expect(Self.hex(HandoffV2.Channel.nonce(direction: 1, counter: 1)) == "000000010000000000000001")
        #expect(Self.hex(HandoffV2.Channel.nonce(direction: 2, counter: 2)) == "000000020000000000000002")
        #expect(Self.hex(HandoffV2.labelBytes) == "6564656e64616c652d68616e646f66662d7632")
    }

    @Test func channelNoncesCountPerDirection() throws {
        let key = SymmetricKey(size: .bits256)
        var guest = HandoffV2.Channel(key: key, isHost: false)
        var host = HandoffV2.Channel(key: key, isHost: true)
        let first = try guest.seal(Data("a".utf8))
        let second = try guest.seal(Data("b".utf8))
        let hostFirst = try host.seal(Data("c".utf8))
        // Direct checks of the nonces the channel used.
        let keyData = key.withUnsafeBytes { Data($0) }
        func open(_ body: Data, direction: UInt32, counter: UInt64) throws -> Data {
            let box = try AES.GCM.SealedBox(
                nonce: AES.GCM.Nonce(data: HandoffV2.Channel.nonce(direction: direction, counter: counter)),
                ciphertext: body.dropLast(16), tag: body.suffix(16)
            )
            return try AES.GCM.open(box, using: SymmetricKey(data: keyData), authenticating: HandoffV2.labelBytes)
        }
        #expect(try open(first, direction: 1, counter: 0) == Data("a".utf8))
        #expect(try open(second, direction: 1, counter: 1) == Data("b".utf8))
        #expect(try open(hostFirst, direction: 2, counter: 0) == Data("c".utf8))
        // Out of order, or tampered, frames fail to open.
        #expect(throws: HandoffV2.PairingError.malformed) { try host.open(second) }
        var tampered = first
        tampered[tampered.startIndex] ^= 0x01
        var fresh = HandoffV2.Channel(key: key, isHost: true)
        #expect(throws: HandoffV2.PairingError.malformed) { try fresh.open(tampered) }
    }

    @Test func framesAreLengthPrefixedAndBounded() throws {
        let frame = try HandoffV2.frame(Data("hello".utf8))
        #expect(frame.prefix(4) == Data([0, 0, 0, 5]))
        #expect(try HandoffV2.bodyLength(ofHeader: frame.prefix(4)) == 5)
        #expect(throws: HandoffV2.PairingError.frameTooLarge) {
            try HandoffV2.frame(Data(count: 65_537))
        }
        #expect(throws: HandoffV2.PairingError.frameTooLarge) {
            try HandoffV2.bodyLength(ofHeader: Data([0, 1, 0, 1]))
        }
        #expect(throws: HandoffV2.PairingError.malformed) {
            try HandoffV2.bodyLength(ofHeader: Data([0, 1]))
        }
        #expect(throws: HandoffV2.PairingError.unsupportedVersion) {
            try HandoffV2.Message.decode(HandoffV2.Hello.self, from: Data(#"{"v":3,"type":"hello","pk":"","proof":""}"#.utf8))
        }
    }

    // MARK: - The whole exchange

    @Test func senderAndReceiverExchangeAnOffer() throws {
        var host = HandoffV2.HostPairing(name: "Living Room TV")
        let guest = HandoffV2.GuestPairing(payload: host.payload)
        let (confirmBody, hostChannel) = try host.accept(helloBody: try guest.helloBody())
        var guestChannel = try guest.accept(confirmBody: confirmBody)
        var hostSide = hostChannel

        // Roles: the phone (guest) sends, the TV (host) receives.
        let guestRole = try HandoffV2.Message.encode(HandoffV2.RoleMessage(role: .sender, name: "Ju's iPhone", platform: "ios"))
        let hostRole = try HandoffV2.Message.encode(HandoffV2.RoleMessage(role: .receiver, name: "Living Room TV", platform: "tvos"))
        let hostSees = try HandoffV2.Message.decode(HandoffV2.RoleMessage.self, from: try hostSide.open(try guestChannel.seal(guestRole)))
        let guestSees = try HandoffV2.Message.decode(HandoffV2.RoleMessage.self, from: try guestChannel.open(try hostSide.seal(hostRole)))
        #expect(hostSees.role == .sender)
        #expect(guestSees.role == .receiver)
        #expect(guestSees.platform == "tvos")

        let account = CloudAccount(
            kind: .googleDrive, subject: "01234567890123456789", email: "me@example.com", displayName: "Me",
            refreshToken: "refresh-token", scopes: CloudProviders.googleScopes, driveID: nil
        )
        let login = AccountHandoff.Login(kind: .sftp, host: "nas.local", credential: NetworkCredential(username: "me", password: "pw"), port: 22, hostKeyFingerprint: "SHA256:abc")
        let offer = HandoffV2.Offer(
            status: .approved,
            location: HandoffV2.OfferLocation(provider: .googleDrive, space: "Family", account: HandoffV2.Account(account), googleFolderId: "1AbC"),
            accounts: [],
            logins: [login]
        )
        let received = try HandoffV2.Message.decode(HandoffV2.Offer.self, from: try hostSide.open(try guestChannel.seal(try HandoffV2.Message.encode(offer))))
        #expect(received == offer)
        #expect(received.location?.account?.cloudAccount.key == account.key)
        #expect(received.location?.account?.cloudAccount.refreshToken == account.refreshToken)
        // The wire spelling follows the specification.
        let wire = String(decoding: try HandoffV2.Message.encode(offer), as: UTF8.self)
        #expect(wire.contains("\"driveId\":null"))
        #expect(wire.contains("\"provider\":\"googleDrive\""))
        #expect(wire.contains("\"googleFolderId\":\"1AbC\""))

        let result = HandoffV2.ResultMessage(stored: ["location", HandoffV2.storedName(login: login)], failed: [])
        let seen = try HandoffV2.Message.decode(HandoffV2.ResultMessage.self, from: try guestChannel.open(try hostSide.seal(try HandoffV2.Message.encode(result))))
        #expect(seen.stored == ["location", "login:sftp:nas.local"])
    }

    @Test func googleAccountsStayInsideTheAppleEcosystem() {
        #expect(HandoffV2.sharesGoogleClient(withPlatform: "tvos"))
        #expect(HandoffV2.sharesGoogleClient(withPlatform: "iPadOS"))
        #expect(!HandoffV2.sharesGoogleClient(withPlatform: "android"))
        #expect(!HandoffV2.sharesGoogleClient(withPlatform: "windows"))
        #expect(!HandoffV2.sharesGoogleClient(withPlatform: nil))
    }

    @Test func sameRoleIsAnError() {
        #expect(HandoffV2.PairingError.role.reason == "role")
        #expect(HandoffV2.PairingError.busy.reason == "busy")
        #expect(HandoffV2.PairingError.unsupportedVersion.reason == "version")
        #expect(HandoffV2.PairingError.secret.reason == "secret")
    }
}
