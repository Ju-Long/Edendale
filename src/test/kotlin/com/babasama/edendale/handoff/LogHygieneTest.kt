package com.babasama.edendale.handoff

import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.sync.MemorySyncStorage
import com.babasama.edendale.sync.MemorySyncStore
import com.babasama.edendale.sync.SyncDeviceInfo
import com.babasama.edendale.sync.SyncEngine
import com.babasama.edendale.sync.SyncLog
import com.babasama.edendale.sync.SyncMediaType
import com.babasama.edendale.sync.SyncProvider
import com.babasama.edendale.sync.UserMediaSyncRecord
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FEATURES-27.1.md H10: no pairing code, QR secret, session key, refresh
 * token, or password reaches the log sink. A whole QR handoff, a whole
 * typed-code handoff, and a sync with a review in it run through a recording
 * sink; the lines are then searched for every secret in play, in hex,
 * base64url, and plain form, and for the review text and a title.
 */
class LogHygieneTest {
    private val workers = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    private val recording = SyncLog.Recording()

    @BeforeTest
    fun install() {
        SyncLog.sink = recording
    }

    @AfterTest
    fun tearDown() {
        SyncLog.sink = SyncLog.Sink { _, _ -> }
        workers.shutdownNow()
    }

    private val refreshToken = "1//0gRefreshTokenSecretValue-xyz"
    private val password = "P4ssw0rd-very-secret"
    private val code = "735912"
    private val review = "A review nobody should see in a log"

    private val account = AccountHandoff.Account(MediaSourceKind.GOOGLE_DRIVE, "subject", "me@example.com", "Me", refreshToken, listOf("https://www.googleapis.com/auth/drive.file"), null)
    private val login = AccountHandoff.Login(MediaSourceKind.WEBDAV, "nas.local", null, "me", password)

    @Test
    fun `no secret reaches the log`() {
        val secrets = mutableListOf<ByteArray>()
        // A QR handoff.
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val keys = HandoffV2.newKeyPair()
            val secret = HandoffV2.newSecret()
            secrets += secret
            val qr = HandoffV2.QrPayload("TV", null, null, keys.publicKey, secret)
            val host = workers.submit {
                listener.accept().use { socket ->
                    val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
                    HandoffProtocolV2.host(socket, frames, frames.read(), HandoffProtocolV2.HostPairing(qr, keys, code), HandoffProtocolV2.Participant(HandoffV2.Role.RECEIVER, "TV", "androidtv") { _, _ -> HandoffV2.ResultMessage(listOf("location"), emptyList()) })
                }
            }
            Socket("127.0.0.1", listener.localPort).use { socket ->
                HandoffProtocolV2.guest(socket, HandoffProtocolV2.GuestPairing.Qr(qr), HandoffProtocolV2.Participant(HandoffV2.Role.SENDER, "Phone", "android", offer = {
                    HandoffV2.Offer(true, HandoffV2.LocationPayload(SyncProvider.GOOGLE_DRIVE, "Family", account = account), listOf(account), listOf(login))
                }))
            }
            host.get(30, TimeUnit.SECONDS)
        }
        // A typed-code handoff, wrong code first, then right.
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            repeat(2) { attempt ->
                val host = workers.submit {
                    listener.accept().use { socket ->
                        val frames = FrameStream(socket.getInputStream(), socket.getOutputStream())
                        runCatching { HandoffProtocolV2.host(socket, frames, frames.read(), HandoffProtocolV2.HostPairing(null, null, code), HandoffProtocolV2.Participant(HandoffV2.Role.SENDER, "TV", "androidtv", offer = { HandoffV2.Offer(true, null, listOf(account), listOf(login)) })) }
                    }
                }
                Socket("127.0.0.1", listener.localPort).use { socket ->
                    runCatching { HandoffProtocolV2.guest(socket, HandoffProtocolV2.GuestPairing.Code(if (attempt == 0) "000000" else code), HandoffProtocolV2.Participant(HandoffV2.Role.RECEIVER, "Phone", "android") { _, _ -> HandoffV2.ResultMessage(emptyList(), emptyList()) }) }
                }
                host.get(30, TimeUnit.SECONDS)
            }
        }
        // A sync whose records carry a review.
        runBlocking {
            val storage = MemorySyncStorage()
            val store = MemorySyncStore()
            val device = SyncDeviceInfo("0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a", "Phone", "android", "27.1")
            store.write(1_762_000_000_000) { stamp -> UserMediaSyncRecord(SyncMediaType.MOVIE, 603, rating = 9.0, review = review, device = device.id, updateTime = stamp) }
            SyncEngine(storage, store, device, "Family") { 1_762_000_000_000 }.sync()
        }

        val lines = recording.lines
        assertTrue(lines.isNotEmpty(), "the sink saw log lines")
        val forbidden = listOf(refreshToken, password, code, review, "000000", "me@example.com") +
            secrets.flatMap { listOf(HandoffV2.hex(it), HandoffV2.b64u(it), java.util.Base64.getEncoder().encodeToString(it)) }
        forbidden.forEach { secret ->
            assertFalse(lines.any { secret in it }, "a log line carries '$secret'")
        }
        // Nothing that prints a payload prints a secret either.
        listOf(account.toString(), login.toString(), HandoffV2.QrPayload("TV", null, null, ByteArray(32), secrets.first()).toString()).forEach { text ->
            assertFalse(refreshToken in text || password in text || HandoffV2.b64u(secrets.first()) in text, text)
        }
    }
}
