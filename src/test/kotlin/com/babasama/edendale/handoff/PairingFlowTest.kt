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
 * receiver uses to name who it waits for.
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
