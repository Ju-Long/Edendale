package com.babasama.edendale.handoff

import com.babasama.edendale.connectors.MediaSourceKind
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pairing screen built as Apple's (ENHANCEMENT.md Section S): which
 * devices scan and which show, which scanned codes count, the sender's
 * Continue, how result items read, the host found by name, the camera
 * frame decoder against a real handoff code, and the peer callback a
 * receiver uses to name who it waits for. Section T: where entering the
 * other device's code is offered, which hosts take a typed code, the host
 * it goes to, the code field, and the wait after five wrong codes.
 */
class PairingFlowTest {
    private val workers = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }

    @AfterTest
    fun tearDown() = workers.shutdownNow().let { }

    private fun code(name: String = "Living Room TV", host: String? = "192.168.1.20", port: Int? = 40123) =
        HandoffV2.QrPayload(name, host, port, HandoffV2.newKeyPair().publicKey, HandoffV2.newSecret())

    @Test
    fun `a phone or tablet with a camera scans or shows, everything else shows`() {
        val both = listOf(PairingFlow.Mode.SCAN, PairingFlow.Mode.SHOW)
        val show = listOf(PairingFlow.Mode.SHOW)
        assertEquals(both, PairingFlow.modes(isTelevision = false, isHeadset = false, hasCamera = true))
        assertEquals(show, PairingFlow.modes(isTelevision = false, isHeadset = false, hasCamera = false))
        assertEquals(show, PairingFlow.modes(isTelevision = true, isHeadset = false, hasCamera = true))
        assertEquals(show, PairingFlow.modes(isTelevision = false, isHeadset = true, hasCamera = true))
    }

    @Test
    fun `only a version-2 handoff code counts as scanned`() {
        val shown = code()
        val scanned = assertNotNull(PairingFlow.scanned(shown.uri()))
        assertEquals("Living Room TV", scanned.name)
        assertEquals(40123, scanned.port)
        assertNull(PairingFlow.scanned(null))
        assertNull(PairingFlow.scanned("https://www.themoviedb.org/auth/access?request_token=abc"))
        assertNull(PairingFlow.scanned(shown.uri().replace("v=2", "v=1")))
        assertNull(PairingFlow.scanned(shown.uri() + "&x=" + "a".repeat(PairingFlow.MAX_CODE_TEXT)))
    }

    @Test
    fun `the sender continues once something is ticked`() {
        assertTrue(PairingFlow.canContinue(hasLocation = true, includeLocation = true, tickedAccounts = 0, tickedLogins = 0))
        assertFalse(PairingFlow.canContinue(hasLocation = true, includeLocation = false, tickedAccounts = 0, tickedLogins = 0))
        assertFalse(PairingFlow.canContinue(hasLocation = false, includeLocation = true, tickedAccounts = 0, tickedLogins = 0))
        assertTrue(PairingFlow.canContinue(hasLocation = false, includeLocation = true, tickedAccounts = 1, tickedLogins = 0))
        assertTrue(PairingFlow.canContinue(hasLocation = false, includeLocation = false, tickedAccounts = 0, tickedLogins = 2))
    }

    @Test
    fun `result items read as Apple names them`() {
        assertEquals(PairingFlow.Item.Location, PairingFlow.item("location"))
        assertEquals(PairingFlow.Item.Account(MediaSourceKind.GOOGLE_DRIVE, "gdrive"), PairingFlow.item("account:gdrive:1234567890"))
        assertEquals(PairingFlow.Item.Login(MediaSourceKind.SFTP, "sftp", "nas.local"), PairingFlow.item("login:sftp:nas.local"))
        // An IPv6 host keeps its colons.
        assertEquals(PairingFlow.Item.Login(MediaSourceKind.SMB, "smb", "fe80::1"), PairingFlow.item("login:smb:fe80::1"))
        // A kind this platform doesn't have is still named by its raw value.
        assertEquals(PairingFlow.Item.Account(null, "icloudDrive"), PairingFlow.item("account:icloudDrive:x"))
        assertEquals(PairingFlow.Item.Other("something"), PairingFlow.item("something"))
    }

    @Test
    fun `a host is found by the code's name, or as the only one on the network`() {
        assertEquals("Living Room TV", PairingFlow.target(listOf("Bedroom TV", "Living Room TV"), "Living Room TV"))
        assertEquals("Bedroom TV", PairingFlow.target(listOf("Bedroom TV"), "Living Room TV"))
        assertNull(PairingFlow.target(listOf("Bedroom TV", "Office PC"), "Living Room TV"))
        assertNull(PairingFlow.target(emptyList(), "Living Room TV"))
    }

    // MARK: - Joining by the other device's code (Section T)

    @Test
    fun `entering the other device's code is offered from the scanner and wherever a device can only show`() {
        val phone = PairingFlow.modes(isTelevision = false, isHeadset = false, hasCamera = true)
        val television = PairingFlow.modes(isTelevision = true, isHeadset = false, hasCamera = false)
        val headset = PairingFlow.modes(isTelevision = false, isHeadset = true, hasCamera = true)
        val noCamera = PairingFlow.modes(isTelevision = false, isHeadset = false, hasCamera = false)
        // The scanner offers it on every device that has one: the camera may be refused.
        assertTrue(PairingFlow.offersEntry(PairingFlow.Mode.SCAN, phone))
        // A phone showing its code goes Back to the choice, where the scanner is.
        assertFalse(PairingFlow.offersEntry(PairingFlow.Mode.SHOW, phone))
        // A device that can only show offers it under its own code, so two such devices can pair.
        assertTrue(PairingFlow.offersEntry(PairingFlow.Mode.SHOW, television))
        assertTrue(PairingFlow.offersEntry(PairingFlow.Mode.SHOW, headset))
        assertTrue(PairingFlow.offersEntry(PairingFlow.Mode.SHOW, noCamera))
        assertFalse(PairingFlow.offersEntry(PairingFlow.Mode.ENTER, television))
        assertFalse(PairingFlow.offersEntry(null, phone))
        // Entering is never one of the first choices: the scanner stays first where there is a camera.
        assertEquals(PairingFlow.Mode.SCAN, phone.first())
        assertTrue(listOf(phone, television, headset, noCamera).none { PairingFlow.Mode.ENTER in it })
    }

    @Test
    fun `a typed code goes only to a version-2 host that shows one`() {
        val found = listOf(
            PairingFlow.Host("Living Room TV", "androidtv", 2),
            PairingFlow.Host("Bedroom TV", null, 1),
            PairingFlow.Host("Kitchen iPad", "ipados", 2),
            PairingFlow.Host("Apple TV", "tvos", 2),
            PairingFlow.Host("Quest 3", "horizon", 2),
            PairingFlow.Host("Office PC", "windows", 2),
            PairingFlow.Host("Pixel", "android", 2),
        )
        assertEquals(listOf("Living Room TV", "Quest 3", "Office PC", "Pixel"), PairingFlow.codeHosts(found).map { it.name })
        assertFalse(PairingFlow.onlyQrHosts(found))
        // Only Apple devices (and a version-1 TV) answered: the screen says why none is listed.
        assertTrue(PairingFlow.onlyQrHosts(listOf(PairingFlow.Host("Apple TV", "tvos", 2), PairingFlow.Host("Bedroom TV", null, 1))))
        assertFalse(PairingFlow.onlyQrHosts(listOf(PairingFlow.Host("Bedroom TV", null, 1))))
        assertFalse(PairingFlow.onlyQrHosts(emptyList()))
        assertFalse(PairingFlow.showsCode("macOS"))
        assertFalse(PairingFlow.showsCode("visionos"))
        // A platform this build doesn't know yet, or none, may still show a code.
        assertTrue(PairingFlow.showsCode("xbox"))
        assertTrue(PairingFlow.showsCode(null))
    }

    @Test
    fun `this device's own code, still advertised a moment after it stopped showing it, isn't listed`() {
        val found = listOf(
            PairingFlow.Host("Living Room TV", "androidtv", 2, port = 40123),
            PairingFlow.Host("Office PC", "windows", 2, port = 40123),
            PairingFlow.Host("Living Room TV", "androidtv", 2, port = 40999),
        )
        assertEquals(listOf("Office PC" to 40123, "Living Room TV" to 40999), PairingFlow.codeHosts(found, ownName = "Living Room TV", ownPort = 40123).map { it.name to it.port })
        assertEquals(3, PairingFlow.codeHosts(found).size)
        assertEquals(3, PairingFlow.codeHosts(found, ownName = "Living Room TV", ownPort = null).size)
    }

    @Test
    fun `the code goes to the host picked while it is there, or to the only one`() {
        assertEquals("Living Room TV", PairingFlow.codeHost(listOf("Living Room TV"), picked = null))
        assertNull(PairingFlow.codeHost(listOf("Living Room TV", "Office PC"), picked = null))
        assertEquals("Office PC", PairingFlow.codeHost(listOf("Living Room TV", "Office PC"), picked = "Office PC"))
        // The picked host left: the list again, not another host the code wasn't read from.
        assertNull(PairingFlow.codeHost(listOf("Living Room TV"), picked = "Office PC"))
        assertNull(PairingFlow.codeHost(emptyList(), picked = null))
    }

    @Test
    fun `the code field keeps six digits`() {
        assertEquals("123456", PairingFlow.typedCode("123 456"))
        assertEquals("123456", PairingFlow.typedCode("12-34-5678"))
        // Full-width digits, as a Japanese or Chinese keyboard may type them, and Arabic-Indic ones.
        assertEquals("123456", PairingFlow.typedCode("１２３４５６"))
        assertEquals("123", PairingFlow.typedCode("١٢٣"))
        assertEquals("", PairingFlow.typedCode("code"))
        assertTrue(PairingFlow.isWholeCode("012345"))
        assertFalse(PairingFlow.isWholeCode("12345"))
        assertFalse(PairingFlow.isWholeCode("１２３４５６"))
        assertTrue(PairingFlow.isWholeCode(PairingFlow.typedCode("１２３４５６")))
    }

    @Test
    fun `five wrong codes in a row make the field wait a minute, as the host does`() {
        val start = 1_000_000L
        var attempts = PairingFlow.CodeAttempts()
        repeat(HandoffV2.MAX_CODE_FAILURES - 1) { attempts = attempts.failed(start) }
        assertEquals(HandoffV2.MAX_CODE_FAILURES - 1, attempts.wrong)
        assertFalse(attempts.isLocked(start))
        attempts = attempts.failed(start)
        assertTrue(attempts.isLocked(start))
        assertTrue(attempts.isLocked(start + HandoffV2.CODE_LOCKOUT_MILLIS - 1))
        assertFalse(attempts.isLocked(start + HandoffV2.CODE_LOCKOUT_MILLIS))
        // After the wait the count starts again, as the host's does.
        attempts = attempts.failed(start + HandoffV2.CODE_LOCKOUT_MILLIS)
        assertEquals(1, attempts.wrong)
        assertFalse(attempts.isLocked(start + HandoffV2.CODE_LOCKOUT_MILLIS))
    }

    // MARK: - Camera frames

    /** A camera-like luminance plane: grey background, rows padded past the width, the code drawn [scale] pixels per module. */
    private fun frame(text: String, width: Int = 640, height: Int = 480, rowStride: Int = 704, inverted: Boolean = false, transposed: Boolean = false): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
        val scale = 5
        val plane = ByteArray(rowStride * height) { 0x60 }
        val left = (width - matrix.width * scale) / 2
        val top = (height - matrix.height * scale) / 2
        for (y in 0 until matrix.height * scale) {
            for (x in 0 until matrix.width * scale) {
                val dark = if (transposed) matrix[y / scale, x / scale] else matrix[x / scale, y / scale]
                val ink = if (dark != inverted) 0x10 else 0xF0
                plane[(top + y) * rowStride + left + x] = ink.toByte()
            }
        }
        return plane
    }

    @Test
    fun `the frame decoder reads a real handoff code from a padded camera plane`() {
        val link = code().uri()
        val decoder = QrFrameDecoder()
        assertEquals(link, decoder.decode(frame(link), rowStride = 704, width = 640, height = 480))
        // Seen sideways (the sensor's own orientation), and as light ink on a dark screen.
        assertEquals(link, decoder.decode(frame(link, transposed = true), rowStride = 704, width = 640, height = 480))
        assertEquals(link, decoder.decode(frame(link, inverted = true), rowStride = 704, width = 640, height = 480))
        // The same decoder keeps working frame after frame, and finds nothing in noise.
        val noise = Random(7).nextBytes(704 * 480)
        assertNull(decoder.decode(noise, rowStride = 704, width = 640, height = 480))
        assertEquals(link, PairingFlow.scanned(decoder.decode(frame(link), rowStride = 704, width = 640, height = 480))?.uri())
    }

    @Test
    fun `a last row without padding is still a whole frame`() {
        val link = code(host = null, port = null).uri()
        val padded = frame(link)
        val trimmed = padded.copyOf(704 * 479 + 640)
        assertEquals(link, QrFrameDecoder().decode(trimmed, rowStride = 704, width = 640, height = 480))
    }

    // MARK: - The receiver names who it waits for

    @Test
    fun `both sides hear the other device once the roles are exchanged, before the offer`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val keys = HandoffV2.newKeyPair()
            val qr = HandoffV2.QrPayload("Living Room TV", "127.0.0.1", listener.localPort, keys.publicKey, HandoffV2.newSecret())
            val events = java.util.Collections.synchronizedList(mutableListOf<String>())
            val host = workers.submit<HandoffProtocolV2.Outcome> {
                listener.accept().use { socket ->
                    val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
                    HandoffProtocolV2.host(
                        socket, frames, frames.read(), HandoffProtocolV2.HostPairing(qr, keys, code = null),
                        HandoffProtocolV2.Participant(HandoffV2.Role.RECEIVER, "Living Room TV", "androidtv", onPeer = { events += "receiver heard ${it.name}" }) { _, _ ->
                            events += "receiver stores"
                            HandoffV2.ResultMessage(listOf("location"), emptyList())
                        },
                    )
                }
            }
            val guest = workers.submit<HandoffProtocolV2.Outcome> {
                Socket("127.0.0.1", listener.localPort).use { socket ->
                    HandoffProtocolV2.guest(
                        socket, HandoffProtocolV2.GuestPairing.Qr(HandoffV2.QrPayload.parse(qr.uri())!!),
                        HandoffProtocolV2.Participant(
                            HandoffV2.Role.SENDER, "Phone", "android",
                            offer = { HandoffV2.Offer(approved = true) },
                            onPeer = { events += "sender heard ${it.name} on ${it.platform}" },
                        ),
                    )
                }
            }
            assertEquals(listOf("location"), guest.get(30, TimeUnit.SECONDS).result?.stored)
            host.get(30, TimeUnit.SECONDS)
            assertTrue("receiver heard Phone" in events)
            assertTrue("sender heard Living Room TV on androidtv" in events)
            assertTrue(events.indexOf("receiver heard Phone") < events.indexOf("receiver stores"))
        }
    }
}
