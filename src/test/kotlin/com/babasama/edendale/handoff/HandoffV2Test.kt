package com.babasama.edendale.handoff

import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.handoff.AccountHandoff.HandoffException
import com.babasama.edendale.sync.SyncProvider
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SYNC-PROTOCOL.md Appendix A.2–A.4 (from `main` at c620b21): the external
 * vectors first (X25519 RFC 7748 §6.1, HKDF RFC 5869 A.1, HMAC RFC 4231
 * 1–4), then the QR key schedule, the QR link, the wrong secret, the channel
 * nonces, and whole exchanges by QR code and by typed code over a loopback
 * socket, with a wrong code and a wrong secret failing before any payload.
 */
class HandoffV2Test {
    private val workers = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    @AfterTest
    fun tearDown() = workers.shutdownNow().let { }

    private fun hex(text: String) = HandoffV2.unhex(text.replace(" ", ""))

    // MARK: - A.4 external vectors

    private val aliceSecret = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
    private val alicePublic = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
    private val bobSecret = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
    private val bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
    private val shared = hex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")

    @Test
    fun `X25519 matches RFC 7748 section 6 point 1`() {
        val alice = HandoffV2.keyPairFromSecret(aliceSecret)
        val bob = HandoffV2.keyPairFromSecret(bobSecret)
        assertContentEquals(alicePublic, alice.publicKey)
        assertContentEquals(bobPublic, bob.publicKey)
        assertContentEquals(shared, alice.agree(bobPublic))
        assertContentEquals(shared, bob.agree(alicePublic))
        // A low-order point is refused.
        assertFailsWith<HandoffException.MalformedMessage> { alice.agree(ByteArray(32)) }
        assertFailsWith<HandoffException.MalformedMessage> { alice.agree(ByteArray(31)) }
        // Fresh pairs differ and agree.
        val a = HandoffV2.newKeyPair()
        val b = HandoffV2.newKeyPair()
        assertFalse(a.publicKey.contentEquals(b.publicKey))
        assertContentEquals(a.agree(b.publicKey), b.agree(a.publicKey))
    }

    @Test
    fun `HKDF-SHA256 matches RFC 5869 appendix A test case 1`() {
        val okm = HandoffV2.hkdf(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertContentEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test
    fun `HMAC-SHA256 matches RFC 4231 test cases 1 to 4`() {
        assertContentEquals(
            hex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            HandoffV2.hmac(hex("0b".repeat(20)), "Hi There".toByteArray()),
        )
        assertContentEquals(
            hex("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
            HandoffV2.hmac("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray()),
        )
        assertContentEquals(
            hex("773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"),
            HandoffV2.hmac(hex("aa".repeat(20)), hex("dd".repeat(50))),
        )
        assertContentEquals(
            hex("82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b"),
            HandoffV2.hmac(hex("0102030405060708090a0b0c0d0e0f10111213141516171819"), hex("cd".repeat(50))),
        )
    }

    // MARK: - A.2 QR pairing key schedule

    private val secret = hex("000102030405060708090a0b0c0d0e0f")

    @Test
    fun `the QR key schedule matches appendix A2`() {
        val transcript = HandoffV2.transcript(alicePublic, bobPublic)
        assertEquals(83, transcript.size)
        assertContentEquals(HandoffV2.LABEL_BYTES + alicePublic + bobPublic, transcript)
        assertContentEquals(hex("d0a69fb97e78208a179410b52ab43f2ad6805e7c5eb2354d112f40ac678db12a"), HandoffV2.proof(secret, transcript))
        val key = HandoffV2.key(shared, secret, transcript)
        assertContentEquals(hex("2e53bf75ce5fd3de9d5ce33c5b245c12a7bb7da14d0857d12bca26fbed33fa01"), key)
        assertContentEquals(hex("0c68fa3fa1adfdef7c07066855502ea9ace5610b06ece6a4494a7824d1cca7e1"), HandoffV2.confirm(key, transcript))
        // The wrong secret's proof, which the host must refuse.
        val wrong = hex("ff0102030405060708090a0b0c0d0e0f")
        assertContentEquals(hex("c53f739e04e85fcbe125d5a78c6692b04957d131b6e5f0f01fc32cb8b2ca297e"), HandoffV2.proof(wrong, transcript))
    }

    @Test
    fun `the QR link matches appendix A2 and parses back`() {
        val payload = HandoffV2.QrPayload("Living Room TV", null, null, alicePublic, secret)
        assertEquals(
            "edendale://handoff?v=2&n=Living%20Room%20TV&k=hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo&s=AAECAwQFBgcICQoLDA0ODw",
            payload.uri(),
        )
        val parsed = assertNotNull(HandoffV2.QrPayload.parse(payload.uri()))
        assertEquals("Living Room TV", parsed.name)
        assertNull(parsed.host)
        assertNull(parsed.port)
        assertContentEquals(alicePublic, parsed.hostPublicKey)
        assertContentEquals(secret, parsed.secret)
        assertFalse(payload.toString().contains("AAECAwQF"))

        val addressed = HandoffV2.QrPayload("TV", "192.168.1.20", 40123, alicePublic, secret)
        val back = assertNotNull(HandoffV2.QrPayload.parse(addressed.uri()))
        assertEquals("192.168.1.20", back.host)
        assertEquals(40123, back.port)
        // The guest's hello for this exchange carries Bob's key.
        val hello = HandoffV2.decodeHello(HandoffV2.helloFrame(bobPublic, HandoffV2.proof(secret, HandoffV2.transcript(alicePublic, bobPublic))))
        assertContentEquals(bobPublic, hello.guestPublicKey)
        assertTrue(HandoffV2.helloFrame(bobPublic, hello.proof).decodeToString().contains("\"pk\":\"3p7bfXt9wbTTW2HC7OQ1Nz-DQ8hbeGdNrfx-FG-IK08\""))

        // Not a handoff link, another version, a short key, a bad port.
        assertNull(HandoffV2.QrPayload.parse("https://example.com/?v=2&k=x&s=y"))
        assertNull(HandoffV2.QrPayload.parse(payload.uri().replace("v=2", "v=3")))
        assertNull(HandoffV2.QrPayload.parse(payload.uri().replace("&s=AAECAwQFBgcICQoLDA0ODw", "&s=AAEC")))
        assertNull(HandoffV2.QrPayload.parse(addressed.uri().replace("p=40123", "p=70000")))
    }

    // MARK: - A.3 channel nonces

    @Test
    fun `channel nonces and AAD match appendix A3, and the channel refuses tampering and replay`() {
        assertEquals("000000010000000000000000", HandoffV2.hex(SecureChannelV2.nonce(SecureChannelV2.GUEST_TO_HOST, 0)))
        assertEquals("000000010000000000000001", HandoffV2.hex(SecureChannelV2.nonce(SecureChannelV2.GUEST_TO_HOST, 1)))
        assertEquals("000000020000000000000002", HandoffV2.hex(SecureChannelV2.nonce(SecureChannelV2.HOST_TO_GUEST, 2)))
        assertEquals("656465" + "6e64616c652d68616e646f66662d7632", HandoffV2.hex(HandoffV2.LABEL_BYTES))

        val key = ByteArray(32) { it.toByte() }
        val host = SecureChannelV2(key, isHost = true)
        val guest = SecureChannelV2(key, isHost = false)
        val first = guest.seal("hello".toByteArray())
        assertEquals("hello", host.open(first).decodeToString())
        assertFailsWith<HandoffException.MalformedMessage> { host.open(first) }
        val tampered = guest.seal("again".toByteArray()).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<HandoffException.MalformedMessage> { host.open(tampered) }
        assertEquals("back", guest.open(host.seal("back".toByteArray())).decodeToString())
        // Version 1's channel uses another nonce layout and no AAD: its frames don't open here.
        val v1 = SecureChannel.forPhone(key)
        assertFailsWith<HandoffException.MalformedMessage> { host.open(v1.seal("v1".toByteArray())) }
    }

    // MARK: - Messages

    private val account = AccountHandoff.Account(MediaSourceKind.GOOGLE_DRIVE, "subject", "me@example.com", "Me", "refresh-token", listOf("https://www.googleapis.com/auth/drive.file"), null)
    private val login = AccountHandoff.Login(MediaSourceKind.SFTP, "nas.local", 22, "me", "pw", hostKeyFingerprint = "SHA256:abc")

    @Test
    fun `roles, offers, and results round-trip, and declines carry nothing`() {
        val role = HandoffV2.RoleMessage(HandoffV2.Role.SENDER, "Phone", "android")
        assertEquals(role, HandoffV2.decodeRole(HandoffV2.encodeRole(role)))
        val location = HandoffV2.LocationPayload(SyncProvider.SFTP, "Family", login = login, path = "sftp://nas.local/home/me/")
        val offer = HandoffV2.Offer(true, location, listOf(account), listOf(login))
        val encoded = HandoffV2.encodeOffer(offer)
        val back = HandoffV2.decodeOffer(encoded)
        assertEquals(offer, back)
        // The folder travels as `url` (the Apple branch's spelling); an older `path` is still read.
        assertTrue("\"url\":\"sftp://nas.local/home/me/\"" in encoded.decodeToString())
        assertEquals("sftp://nas.local/home/me/", HandoffV2.decodeOffer(encoded.decodeToString().replace("\"url\":", "\"path\":").toByteArray()).location?.path)
        assertEquals(HandoffV2.Offer.DECLINED, HandoffV2.decodeOffer(HandoffV2.encodeOffer(HandoffV2.Offer.DECLINED)))
        assertFalse(HandoffV2.encodeOffer(HandoffV2.Offer.DECLINED).decodeToString().contains("pw"))
        val result = HandoffV2.ResultMessage(listOf("location", "login:sftp:nas.local"), listOf(HandoffV2.ResultMessage.Failure("account:gdrive:subject", "refused")))
        assertEquals(result, HandoffV2.decodeResult(HandoffV2.encodeResult(result)))
        // A folder location carries only provider and space.
        val folder = HandoffV2.decodeOffer(HandoffV2.encodeOffer(HandoffV2.Offer(true, HandoffV2.LocationPayload(SyncProvider.FOLDER, "Family"))))
        assertEquals(HandoffV2.LocationPayload(SyncProvider.FOLDER, "Family"), folder.location)
        // Nothing prints a token or password.
        assertFalse(location.toString().contains("pw"))
        assertFalse(offer.toString().contains("refresh-token"))
        // A provider this platform doesn't have (Apple's iCloud Drive) keeps the rest of the offer and is reported as failed.
        val icloud = HandoffV2.decodeOffer("""{"type":"offer","status":"approved","location":{"provider":"icloudDrive","space":"Family"},"accounts":[],"logins":[${AccountHandoff.loginJson(login)}]}""".toByteArray())
        assertNull(icloud.location)
        assertEquals("icloudDrive", icloud.unsupportedLocationProvider)
        assertEquals(listOf(login), icloud.logins)
        // Malformed: an invalid space, a bad status.
        assertFailsWith<HandoffException.MalformedMessage> { HandoffV2.decodeOffer("""{"type":"offer","status":"approved","location":{"provider":"folder","space":"a/b"}}""".toByteArray()) }
        assertFailsWith<HandoffException.MalformedMessage> { HandoffV2.decodeOffer("""{"type":"offer","status":"maybe"}""".toByteArray()) }
        // Errors inside the channel surface as their exception.
        assertFailsWith<HandoffException.SameRole> { HandoffV2.decodeOffer(HandoffV2.channelErrorFrame(HandoffV2.ErrorReason.ROLE)) }
        assertFailsWith<HandoffException.WrongSecret> { HandoffV2.decodeConfirm(HandoffV2.errorFrame(HandoffV2.ErrorReason.SECRET)) }
        assertFailsWith<HandoffException.WrongCode> { HandoffV2.decodeConfirm(HandoffV2.errorFrame(HandoffV2.ErrorReason.CODE)) }
        assertFailsWith<HandoffException.Busy> { HandoffV2.decodeConfirm(HandoffV2.errorFrame(HandoffV2.ErrorReason.BUSY)) }
        assertEquals(3, assertFailsWith<HandoffException.UnsupportedVersion> { HandoffV2.decodeHello("""{"v":3,"type":"hello"}""".toByteArray()) }.version)
    }

    @Test
    fun `Google accounts travel only within an ecosystem`() {
        assertTrue(HandoffV2.googleLoginTransfers("android", "androidtv"))
        assertTrue(HandoffV2.googleLoginTransfers("horizon", "android"))
        assertTrue(HandoffV2.googleLoginTransfers("ios", "tvos"))
        assertTrue(HandoffV2.googleLoginTransfers("macos", "visionos"))
        assertFalse(HandoffV2.googleLoginTransfers("ios", "android"))
        assertFalse(HandoffV2.googleLoginTransfers("android", "windows"))
        assertFalse(HandoffV2.googleLoginTransfers("android", "fridge"))
        assertFalse(HandoffV2.canSignInToGoogle("androidtv"))
        assertTrue(HandoffV2.canSignInToGoogle("android"))

        val location = HandoffV2.LocationPayload(SyncProvider.GOOGLE_DRIVE, "Family", account = account, googleFolderId = "1AbC")
        val oneDrive = account.copy(kind = MediaSourceKind.ONE_DRIVE, subject = "ms")
        val same = HandoffV2.offerFor(location, listOf(account, oneDrive), listOf(login), "android", "androidtv")
        assertEquals(account, same.location?.account)
        assertEquals(listOf(account, oneDrive), same.accounts)
        val across = HandoffV2.offerFor(location, listOf(account, oneDrive), listOf(login), "android", "ios")
        assertNull(across.location?.account)
        assertEquals("me@example.com", across.location?.googleAccountHint)
        assertEquals("1AbC", across.location?.googleFolderId)
        assertEquals(listOf(oneDrive), across.accounts)
        assertEquals(listOf(login), across.logins)
    }

    // MARK: - Whole exchanges over a socket

    private class Loopback : AutoCloseable {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        fun accept(): Socket = listener.accept()
        fun connect(): Socket = Socket("127.0.0.1", listener.localPort)
        override fun close() = listener.close()
    }

    private fun <T> Future<T>.result(): T = get(30, TimeUnit.SECONDS)

    private fun hostRun(loopback: Loopback, pairing: HandoffProtocolV2.HostPairing, participant: HandoffProtocolV2.Participant) = workers.submit<HandoffProtocolV2.Outcome> {
        loopback.accept().use { socket ->
            val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
            val first = frames.read()
            HandoffProtocolV2.host(socket, frames, first, pairing, participant)
        }
    }

    @Test
    fun `a QR pairing moves the location and logins from the guest sender to the host receiver`() {
        Loopback().use { loopback ->
            val keys = HandoffV2.newKeyPair()
            val qr = HandoffV2.QrPayload("Living Room TV", "127.0.0.1", loopback.listener.localPort, keys.publicKey, HandoffV2.newSecret())
            val pairing = HandoffProtocolV2.HostPairing(qr, keys, code = null)
            val received = mutableListOf<HandoffV2.Offer>()
            val host = hostRun(
                loopback, pairing,
                HandoffProtocolV2.Participant(HandoffV2.Role.RECEIVER, "Living Room TV", "androidtv") { offer, _ ->
                    received += offer
                    HandoffV2.ResultMessage(listOf("location", "login:sftp:nas.local"), emptyList())
                },
            )
            val scanned = HandoffV2.QrPayload.parse(qr.uri())!!
            val guest = workers.submit<HandoffProtocolV2.Outcome> {
                loopback.connect().use { socket ->
                    HandoffProtocolV2.guest(
                        socket, HandoffProtocolV2.GuestPairing.Qr(scanned),
                        HandoffProtocolV2.Participant(HandoffV2.Role.SENDER, "Phone", "android", offer = { peer ->
                            assertEquals("Living Room TV", peer.name)
                            HandoffV2.offerFor(HandoffV2.LocationPayload(SyncProvider.SFTP, "Family", login = login, path = "/home"), emptyList(), listOf(login), "android", peer.platform)
                        }),
                    )
                }
            }
            val sent = guest.result()
            val got = host.result()
            assertEquals(listOf("location", "login:sftp:nas.local"), sent.result?.stored)
            assertEquals(sent.result, got.result)
            assertEquals("Phone", got.peer.name)
            assertEquals(login, received.single().location?.login)
            assertEquals(listOf(login), received.single().logins)
            // The code was for one hello: a second guest is refused.
            assertTrue(pairing.let { p -> runCatching { p }.isSuccess })
        }
    }

    @Test
    fun `a typed code pairs with J-PAKE, with the host as sender and the guest as receiver`() {
        Loopback().use { loopback ->
            val pairing = HandoffProtocolV2.HostPairing(qr = null, keyPair = null, code = "424242")
            val host = hostRun(
                loopback, pairing,
                HandoffProtocolV2.Participant(HandoffV2.Role.SENDER, "PC", "windows", offer = { peer ->
                    HandoffV2.offerFor(HandoffV2.LocationPayload(SyncProvider.GOOGLE_DRIVE, "Family", account = account, googleFolderId = "1AbC"), listOf(account), emptyList(), "windows", peer.platform)
                }),
            )
            val stored = mutableListOf<HandoffV2.Offer>()
            val guest = workers.submit<HandoffProtocolV2.Outcome> {
                loopback.connect().use { socket ->
                    HandoffProtocolV2.guest(
                        socket, HandoffProtocolV2.GuestPairing.Code("424242"),
                        HandoffProtocolV2.Participant(HandoffV2.Role.RECEIVER, "Phone", "android") { offer, _ ->
                            stored += offer
                            HandoffV2.ResultMessage(listOf("location"), emptyList())
                        },
                    )
                }
            }
            guest.result()
            val sent = host.result()
            assertEquals(listOf("location"), sent.result?.stored)
            // Windows to Android: no Google account travels, only the hint and the folder.
            val offer = stored.single()
            assertNull(offer.location?.account)
            assertEquals("me@example.com", offer.location?.googleAccountHint)
            assertEquals("1AbC", offer.location?.googleFolderId)
            assertTrue(offer.accounts.isEmpty())
        }
    }

    @Test
    fun `a wrong secret and a wrong code both fail before any payload`() {
        // Wrong secret.
        Loopback().use { loopback ->
            val keys = HandoffV2.newKeyPair()
            val qr = HandoffV2.QrPayload("TV", null, null, keys.publicKey, HandoffV2.newSecret())
            var asked = false
            val host = workers.submit {
                loopback.accept().use { socket ->
                    val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
                    val first = frames.read()
                    assertFailsWith<HandoffException.WrongSecret> {
                        HandoffProtocolV2.host(socket, frames, first, HandoffProtocolV2.HostPairing(qr, keys, null), HandoffProtocolV2.Participant(HandoffV2.Role.RECEIVER, "TV", "androidtv") { _, _ -> asked = true; HandoffV2.ResultMessage(emptyList(), emptyList()) })
                    }
                }
            }
            val wrongSecret = HandoffV2.QrPayload("TV", null, null, keys.publicKey, HandoffV2.newSecret())
            val guest = workers.submit {
                loopback.connect().use { socket ->
                    assertFailsWith<HandoffException.WrongSecret> {
                        HandoffProtocolV2.guest(socket, HandoffProtocolV2.GuestPairing.Qr(wrongSecret), HandoffProtocolV2.Participant(HandoffV2.Role.SENDER, "Phone", "android", offer = { asked = true; HandoffV2.Offer.DECLINED }))
                    }
                }
            }
            guest.result()
            host.result()
            assertFalse(asked)
        }
        // Wrong code.
        Loopback().use { loopback ->
            var asked = false
            val host = workers.submit {
                loopback.accept().use { socket ->
                    val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
                    val first = frames.read()
                    assertFailsWith<HandoffException.WrongCode> {
                        HandoffProtocolV2.host(socket, frames, first, HandoffProtocolV2.HostPairing(null, null, "111111"), HandoffProtocolV2.Participant(HandoffV2.Role.RECEIVER, "TV", "androidtv") { _, _ -> asked = true; HandoffV2.ResultMessage(emptyList(), emptyList()) })
                    }
                }
            }
            val guest = workers.submit {
                loopback.connect().use { socket ->
                    assertFailsWith<HandoffException.WrongCode> {
                        HandoffProtocolV2.guest(socket, HandoffProtocolV2.GuestPairing.Code("222222"), HandoffProtocolV2.Participant(HandoffV2.Role.SENDER, "Phone", "android", offer = { asked = true; HandoffV2.Offer.DECLINED }))
                    }
                }
            }
            guest.result()
            host.result()
            assertFalse(asked)
        }
    }

    @Test
    fun `an expired code, a reused code, both sides sending, and a declined offer each say so`() {
        // Expired: the host shows a code at t=0 and the guest arrives three minutes later.
        Loopback().use { loopback ->
            var now = 0L
            val pairing = HandoffProtocolV2.HostPairing(null, null, "123456", clock = { now })
            now = HandoffV2.CODE_VALIDITY_MILLIS + 1
            val host = workers.submit {
                loopback.accept().use { socket ->
                    val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
                    assertFailsWith<HandoffException.WrongSecret> { HandoffProtocolV2.host(socket, frames, frames.read(), pairing, HandoffProtocolV2.Participant(HandoffV2.Role.RECEIVER, "TV", "androidtv")) }
                }
            }
            val guest = workers.submit {
                loopback.connect().use { socket ->
                    assertFailsWith<HandoffException.WrongSecret> { HandoffProtocolV2.guest(socket, HandoffProtocolV2.GuestPairing.Code("123456"), HandoffProtocolV2.Participant(HandoffV2.Role.SENDER, "Phone", "android")) }
                }
            }
            guest.result()
            host.result()
        }
        // Both chose to send.
        Loopback().use { loopback ->
            val host = workers.submit {
                loopback.accept().use { socket ->
                    val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
                    assertFailsWith<HandoffException.SameRole> { HandoffProtocolV2.host(socket, frames, frames.read(), HandoffProtocolV2.HostPairing(null, null, "123456"), HandoffProtocolV2.Participant(HandoffV2.Role.SENDER, "TV", "androidtv")) }
                }
            }
            val guest = workers.submit {
                loopback.connect().use { socket ->
                    assertFailsWith<HandoffException.SameRole> { HandoffProtocolV2.guest(socket, HandoffProtocolV2.GuestPairing.Code("123456"), HandoffProtocolV2.Participant(HandoffV2.Role.SENDER, "Phone", "android")) }
                }
            }
            guest.result()
            host.result()
        }
        // Declined on the sender.
        Loopback().use { loopback ->
            val host = workers.submit {
                loopback.accept().use { socket ->
                    val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
                    assertFailsWith<HandoffException.Declined> { HandoffProtocolV2.host(socket, frames, frames.read(), HandoffProtocolV2.HostPairing(null, null, "123456"), HandoffProtocolV2.Participant(HandoffV2.Role.RECEIVER, "TV", "androidtv")) }
                }
            }
            val outcome = workers.submit<HandoffProtocolV2.Outcome> {
                loopback.connect().use { socket ->
                    HandoffProtocolV2.guest(socket, HandoffProtocolV2.GuestPairing.Code("123456"), HandoffProtocolV2.Participant(HandoffV2.Role.SENDER, "Phone", "android", offer = { null }))
                }
            }.result()
            host.result()
            assertNull(outcome.result)
            assertEquals("TV", outcome.peer.name)
        }
    }

    @Test
    fun `a version 1 phone reaching a host that reads the first frame still completes version 1`() {
        // The host peeks at the first frame: "v":1 round1 goes down the version-1 path unchanged.
        Loopback().use { loopback ->
            val account = com.babasama.edendale.oauth.CloudAccount(MediaSourceKind.DROPBOX, "s", "e@x", "E", "r", emptyList())
            val tv = workers.submit<AccountHandoff.Response> {
                loopback.accept().use { socket ->
                    val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
                    val first = frames.read()
                    assertEquals(1 to "round1", HandoffV2.peek(first))
                    HandoffProtocol.receive(socket, "123456", AccountHandoff.Request(MediaSourceKind.DROPBOX, "TV"), frames, first) { AccountHandoff.Result(true) }
                }
            }
            val phone = workers.submit<HandoffProtocol.Exchange> {
                loopback.connect().use { socket -> HandoffProtocol.send(socket, "123456") { AccountHandoff.Response(AccountHandoff.Status.APPROVED, account = AccountHandoff.Account(account)) } }
            }
            assertEquals(AccountHandoff.Result(true), phone.result().result)
            assertEquals(account, tv.result().account?.cloudAccount)
        }
        // And a version-2 guest's first frame is told apart.
        assertEquals(2 to "hello", HandoffV2.peek(HandoffV2.helloFrame(bobPublic, ByteArray(32))))
        assertEquals(2 to "round1", HandoffV2.peek(HandoffCrypto.Pake(HandoffV2.GUEST_ID, "123456", version = 2).round1()))
    }
}
