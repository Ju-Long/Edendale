package com.babasama.edendale.handoff

import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.S3Configuration
import com.babasama.edendale.handoff.AccountHandoff.HandoffException
import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.oauth.CloudAccountVault
import com.babasama.edendale.oauth.CloudProviders
import com.babasama.edendale.oauth.CloudTokenProvider
import com.babasama.edendale.oauth.OAuthConfiguration
import com.babasama.edendale.oauth.SecretStore
import com.babasama.edendale.remote.LocalHttpServer
import com.babasama.edendale.remote.OkHttpRemoteHttp
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I.2.T1 (Apple's `AccountHandoffTests`, plus the Android key agreement):
 * frames round-trip, unknown versions and malformed bodies are rejected, the
 * J-PAKE handshake agrees a key and fails on a wrong code before any payload,
 * the AES-GCM channel refuses tampering and replay, a whole exchange runs over
 * a loopback socket, and an account whose token fails to refresh is refused
 * rather than stored.
 */
class AccountHandoffTest {

    private val account = CloudAccount(
        kind = MediaSourceKind.GOOGLE_DRIVE,
        subject = "110169484474386276334",
        email = "me@example.com",
        displayName = "Me",
        refreshToken = "refresh-token",
        scopes = CloudProviders.GOOGLE_SCOPES,
        driveId = null,
    )

    private val workers = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    private val servers = mutableListOf<LocalHttpServer>()

    @AfterTest
    fun tearDown() {
        workers.shutdownNow()
        servers.forEach { it.close() }
    }

    /** Splits a frame into its announced length and body. */
    private fun unframe(frame: ByteArray): ByteArray {
        val length = AccountHandoff.bodyLength(frame.copyOfRange(0, 4))
        val body = frame.copyOfRange(4, frame.size)
        assertEquals(length, body.size)
        return body
    }

    // MARK: - Messages

    @Test
    fun `requests round trip`() {
        val request = AccountHandoff.Request(MediaSourceKind.DROPBOX, "Living Room")
        val body = unframe(AccountHandoff.frame(AccountHandoff.encodeRequest(request)))
        assertEquals(request, AccountHandoff.decodeRequest(body))
        assertTrue(body.decodeToString().contains("\"v\":1"))
    }

    @Test
    fun `accounts round trip with their key and token`() {
        val response = AccountHandoff.Response(AccountHandoff.Status.APPROVED, account = AccountHandoff.Account(account))
        val decoded = AccountHandoff.decodeResponse(unframe(AccountHandoff.frame(AccountHandoff.encodeResponse(response))))
        val handedOver = assertNotNull(decoded.account).cloudAccount
        assertEquals(account.key, handedOver.key)
        assertEquals("refresh-token", handedOver.refreshToken)
        assertEquals(CloudProviders.GOOGLE_SCOPES, handedOver.scopes)
        assertEquals("me@example.com", handedOver.email)
        assertEquals(account, handedOver)
        // Nothing prints the token.
        assertFalse(decoded.account.toString().contains("refresh-token"))
    }

    @Test
    fun `logins round trip with their host key, certificate, and bucket`() {
        val sftp = AccountHandoff.Login(
            kind = MediaSourceKind.SFTP, host = "nas.local", port = 2222, user = "me", password = "pw",
            hostKeyFingerprint = "SHA256:abc", addresses = listOf("sftp://nas.local:2222/media/"),
        )
        val sftpBack = AccountHandoff.decodeResponse(unframe(AccountHandoff.frame(AccountHandoff.encodeResponse(AccountHandoff.Response(AccountHandoff.Status.APPROVED, login = sftp)))))
        assertEquals(sftp, sftpBack.login)
        assertNull(sftpBack.account)

        val s3 = AccountHandoff.Login(
            kind = MediaSourceKind.S3, host = "0123abcd", port = null, user = "AKIA", password = "secret",
            certificateFingerprint = "AA:BB", s3 = S3Configuration("https://minio.local:9000", "us-east-1", "films", usesPathStyle = true),
        )
        val s3Back = AccountHandoff.decodeResponse(unframe(AccountHandoff.frame(AccountHandoff.encodeResponse(AccountHandoff.Response(AccountHandoff.Status.APPROVED, login = s3)))))
        assertEquals(s3, s3Back.login)
        assertFalse(s3.toString().contains("secret"))

        val dav = AccountHandoff.Login(MediaSourceKind.WEBDAV, "cloud.example.com", null, "me", "pw")
        val davBack = AccountHandoff.decodeResponse(unframe(AccountHandoff.frame(AccountHandoff.encodeResponse(AccountHandoff.Response(AccountHandoff.Status.APPROVED, login = dav)))))
        assertEquals(dav, davBack.login)
        assertNull(davBack.login?.port)
        assertNull(davBack.login?.s3)
    }

    @Test
    fun `results and declines round trip, and declines carry no account`() {
        val declined = AccountHandoff.decodeResponse(unframe(AccountHandoff.frame(AccountHandoff.encodeResponse(AccountHandoff.Response.DECLINED))))
        assertEquals(AccountHandoff.Status.DECLINED, declined.status)
        assertNull(declined.account)
        assertNull(declined.login)
        assertEquals(AccountHandoff.Result(true), AccountHandoff.decodeResult(AccountHandoff.encodeResult(AccountHandoff.Result(true))))
        assertEquals(AccountHandoff.Result(false, "Nope"), AccountHandoff.decodeResult(AccountHandoff.encodeResult(AccountHandoff.Result(false, "Nope"))))
    }

    @Test
    fun `rejects unknown versions before reading anything else`() {
        val future = """{"v":2,"type":"request","kind":"gdrive","deviceName":"TV","somethingNew":true}""".toByteArray()
        assertEquals(2, assertFailsWith<HandoffException.UnsupportedVersion> { AccountHandoff.decodeRequest(future) }.version)
        val past = """{"v":0,"type":"response","status":"approved"}""".toByteArray()
        assertEquals(0, assertFailsWith<HandoffException.UnsupportedVersion> { AccountHandoff.decodeResponse(past) }.version)
        val missing = """{"type":"result","stored":true}""".toByteArray()
        assertFailsWith<HandoffException.MalformedMessage> { AccountHandoff.decodeResult(missing) }
    }

    @Test
    fun `rejects malformed messages and oversized frames`() {
        assertFailsWith<HandoffException.MalformedMessage> { AccountHandoff.decodeRequest("not json".toByteArray()) }
        assertFailsWith<HandoffException.MalformedMessage> { AccountHandoff.decodeRequest("""{"v":1,"type":"request","kind":"ftp","deviceName":"TV"}""".toByteArray()) }
        // A response where a request is expected.
        assertFailsWith<HandoffException.MalformedMessage> { AccountHandoff.decodeRequest(AccountHandoff.encodeResponse(AccountHandoff.Response.DECLINED)) }
        // Approved with nothing in it, an account of a server kind, a login of a cloud kind.
        assertFailsWith<HandoffException.MalformedMessage> { AccountHandoff.decodeResponse("""{"v":1,"type":"response","status":"approved"}""".toByteArray()) }
        assertFailsWith<HandoffException.MalformedMessage> {
            AccountHandoff.decodeResponse("""{"v":1,"type":"response","status":"approved","account":{"kind":"smb","subject":"s","refreshToken":"r"}}""".toByteArray())
        }
        assertFailsWith<HandoffException.MalformedMessage> {
            AccountHandoff.decodeResponse("""{"v":1,"type":"response","status":"approved","login":{"kind":"gdrive","host":"h","user":"u","password":"p"}}""".toByteArray())
        }
        assertFailsWith<HandoffException.MessageTooLarge> { AccountHandoff.bodyLength(byteArrayOf(0x7F, -1, -1, -1)) }
        assertFailsWith<HandoffException.MessageTooLarge> { AccountHandoff.bodyLength(byteArrayOf(0, 0, 0, 0)) }
        assertFailsWith<HandoffException.MessageTooLarge> { AccountHandoff.bodyLength(byteArrayOf(0, 1, 0, 1)) }
        assertEquals(AccountHandoff.MAX_MESSAGE_SIZE, AccountHandoff.bodyLength(byteArrayOf(0, 1, 0, 0)))
        assertFailsWith<HandoffException.MalformedMessage> { AccountHandoff.bodyLength(byteArrayOf(0, 1)) }
        assertFailsWith<HandoffException.MessageTooLarge> { AccountHandoff.frame(ByteArray(AccountHandoff.MAX_MESSAGE_SIZE + 1)) }
        assertFailsWith<HandoffException.MessageTooLarge> { AccountHandoff.frame(ByteArray(0)) }
        assertContentEquals(byteArrayOf(0, 0, 0, 3, 1, 2, 3), AccountHandoff.frame(byteArrayOf(1, 2, 3)))
    }

    // MARK: - Key agreement and channel

    @Test
    fun `the key agreement gives both sides the same key`() {
        val phone = HandoffCrypto.Pake(HandoffCrypto.PHONE, "123456")
        val tv = HandoffCrypto.Pake(HandoffCrypto.TV, "123456")
        tv.receiveRound1(phone.round1())
        phone.receiveRound1(tv.round1())
        tv.receiveRound2(phone.round2())
        phone.receiveRound2(tv.round2())
        tv.receiveRound3(phone.round3())
        phone.receiveRound3(tv.round3())
        val key = phone.sessionKey()
        assertContentEquals(key, tv.sessionKey())
        assertEquals(32, key.size)

        // The channel: each direction its own counter; tampering and replay fail.
        val phoneChannel = SecureChannel.forPhone(key)
        val tvChannel = SecureChannel.forTelevision(key)
        val sealed = phoneChannel.seal("hello".toByteArray())
        assertEquals("hello", tvChannel.open(sealed).decodeToString())
        assertFailsWith<HandoffException.MalformedMessage> { tvChannel.open(sealed) }
        val second = phoneChannel.seal("again".toByteArray())
        val tampered = second.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFailsWith<HandoffException.MalformedMessage> { tvChannel.open(tampered) }
        assertEquals("again", tvChannel.open(second).decodeToString())
        assertEquals("back", phoneChannel.open(tvChannel.seal("back".toByteArray())).decodeToString())
        // A message sealed for the other direction doesn't open on the same side.
        assertFailsWith<HandoffException.MalformedMessage> { phoneChannel.open(phoneChannel.seal("mine".toByteArray())) }
    }

    @Test
    fun `a wrong code fails at the key confirmation and nothing else`() {
        val phone = HandoffCrypto.Pake(HandoffCrypto.PHONE, "123456")
        val tv = HandoffCrypto.Pake(HandoffCrypto.TV, "123457")
        tv.receiveRound1(phone.round1())
        phone.receiveRound1(tv.round1())
        tv.receiveRound2(phone.round2())
        phone.receiveRound2(tv.round2())
        assertFailsWith<HandoffException.WrongCode> { tv.receiveRound3(phone.round3()) }
        // The TV's plaintext answer tells the phone the same.
        assertFailsWith<HandoffException.WrongCode> { phone.receiveRound3(HandoffCrypto.wrongCodeFrame()) }
        assertFailsWith<IllegalStateException> { phone.sessionKey() }
        // Codes are six digits, zero-padded.
        repeat(20) { assertTrue(Regex("^[0-9]{6}$").matches(HandoffCrypto.newCode())) }
    }

    @Test
    fun `round payloads from another version or of the wrong shape are refused`() {
        val tv = HandoffCrypto.Pake(HandoffCrypto.TV, "123456")
        assertFailsWith<HandoffException.MalformedMessage> { tv.receiveRound1("nope".toByteArray()) }
        assertFailsWith<HandoffException.UnsupportedVersion> { tv.receiveRound1("""{"v":3,"type":"round1"}""".toByteArray()) }
        assertFailsWith<HandoffException.MalformedMessage> { tv.receiveRound1("""{"v":1,"type":"round2"}""".toByteArray()) }
        assertFailsWith<HandoffException.MalformedMessage> { tv.receiveRound1("""{"v":1,"type":"round1","id":"x","gx1":"AA","gx2":"AA","zkp1":{},"zkp2":{}}""".toByteArray()) }
    }

    // MARK: - A whole exchange over a socket

    private class Loopback : AutoCloseable {
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        fun accept(): Socket = listener.accept()
        fun connect(): Socket = Socket("127.0.0.1", listener.localPort)
        override fun close() = listener.close()
    }

    private fun <T> Future<T>.result(): T = get(30, TimeUnit.SECONDS)

    @Test
    fun `an approved account travels, is stored, and the phone hears the result`() {
        Loopback().use { loopback ->
            val code = "424242"
            val stored = mutableListOf<AccountHandoff.Response>()
            val tv = workers.submit<AccountHandoff.Response> {
                loopback.accept().use { socket ->
                    HandoffProtocol.receive(socket, code, AccountHandoff.Request(MediaSourceKind.GOOGLE_DRIVE, "Living Room")) { response ->
                        stored += response
                        AccountHandoff.Result(stored = true)
                    }
                }
            }
            val seen = mutableListOf<AccountHandoff.Request>()
            val phone = workers.submit<HandoffProtocol.Exchange> {
                loopback.connect().use { socket ->
                    HandoffProtocol.send(socket, code) { request ->
                        seen += request
                        AccountHandoff.Response(AccountHandoff.Status.APPROVED, account = AccountHandoff.Account(account))
                    }
                }
            }
            val exchange = phone.result()
            val received = tv.result()
            assertEquals(AccountHandoff.Request(MediaSourceKind.GOOGLE_DRIVE, "Living Room"), exchange.request)
            assertEquals(AccountHandoff.Result(true), exchange.result)
            assertEquals(listOf(exchange.request), seen)
            assertEquals(account, assertNotNull(received.account).cloudAccount)
            assertEquals(listOf(received), stored)
        }
    }

    @Test
    fun `a wrong code stops both sides before any payload`() {
        Loopback().use { loopback ->
            var asked = false
            val tv = workers.submit {
                loopback.accept().use { socket ->
                    assertFailsWith<HandoffException.WrongCode> {
                        HandoffProtocol.receive(socket, "111111", AccountHandoff.Request(MediaSourceKind.DROPBOX, "TV")) { AccountHandoff.Result(true) }
                    }
                }
            }
            val phone = workers.submit {
                loopback.connect().use { socket ->
                    assertFailsWith<HandoffException.WrongCode> {
                        HandoffProtocol.send(socket, "222222") {
                            asked = true
                            AccountHandoff.Response.DECLINED
                        }
                    }
                }
            }
            phone.result()
            tv.result()
            assertFalse(asked)
        }
    }

    @Test
    fun `a decline, a rejection, and an answer for another provider each say so`() {
        // Declined on the phone.
        Loopback().use { loopback ->
            val tv = workers.submit {
                loopback.accept().use { socket ->
                    assertFailsWith<HandoffException.Declined> {
                        HandoffProtocol.receive(socket, "333333", AccountHandoff.Request(MediaSourceKind.DROPBOX, "TV")) { AccountHandoff.Result(true) }
                    }
                }
            }
            val exchange = workers.submit<HandoffProtocol.Exchange> {
                loopback.connect().use { socket -> HandoffProtocol.send(socket, "333333") { AccountHandoff.Response.DECLINED } }
            }.result()
            tv.result()
            assertNull(exchange.result)
            assertEquals(MediaSourceKind.DROPBOX, exchange.request.kind)
        }
        // The TV couldn't use the login.
        Loopback().use { loopback ->
            val login = AccountHandoff.Login(MediaSourceKind.SMB, "nas.local", null, "me", "pw")
            val tv = workers.submit<AccountHandoff.Response> {
                loopback.accept().use { socket ->
                    HandoffProtocol.receive(socket, "444444", AccountHandoff.Request(MediaSourceKind.SMB, "TV")) { AccountHandoff.Result(false, "No such share") }
                }
            }
            val phone = workers.submit {
                loopback.connect().use { socket ->
                    val rejected = assertFailsWith<HandoffException.Rejected> {
                        HandoffProtocol.send(socket, "444444") { AccountHandoff.Response(AccountHandoff.Status.APPROVED, login = login) }
                    }
                    assertEquals("No such share", rejected.reason)
                }
            }
            phone.result()
            assertEquals(login, tv.result().login)
        }
        // The phone answered for another provider than the TV asked about.
        Loopback().use { loopback ->
            val tv = workers.submit {
                loopback.accept().use { socket ->
                    assertFailsWith<HandoffException.WrongKind> {
                        HandoffProtocol.receive(socket, "555555", AccountHandoff.Request(MediaSourceKind.DROPBOX, "TV")) { AccountHandoff.Result(true) }
                    }
                }
            }
            val phone = workers.submit {
                loopback.connect().use { socket ->
                    assertFailsWith<HandoffException.Rejected> {
                        HandoffProtocol.send(socket, "555555") { AccountHandoff.Response(AccountHandoff.Status.APPROVED, account = AccountHandoff.Account(account)) }
                    }
                }
            }
            phone.result()
            tv.result()
        }
    }

    // MARK: - Adoption on the TV

    private class MemoryStore : SecretStore {
        private val values = ConcurrentHashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun keys(): Set<String> = values.keys.toSet()
    }

    private fun tokenServer(handler: (LocalHttpServer.Request) -> LocalHttpServer.Response) = LocalHttpServer(handler).also { servers += it }

    private fun configuration(server: LocalHttpServer) = OAuthConfiguration(
        MediaSourceKind.GOOGLE_DRIVE, "client", "https://auth.example/auth", "http://127.0.0.1:${server.port}/token", null, "x:/y", "x", emptyList(),
    )

    @Test
    fun `refuses an account whose token doesn't refresh`() {
        val server = tokenServer { LocalHttpServer.Response(400, mapOf("Content-Type" to "application/json"), """{"error":"invalid_grant"}""".toByteArray()) }
        val vault = CloudAccountVault(MemoryStore())
        val http = OkHttpRemoteHttp()
        val tokens = CloudTokenProvider(vault, http, { configuration(server) })
        val rejected = assertFailsWith<HandoffException.Rejected> {
            runBlocking { adoptHandedOffAccount(account, configuration(server), http, vault, tokens) { "Google refused the sign-in" } }
        }
        assertEquals("Google refused the sign-in", rejected.reason)
        assertNull(vault.account(MediaSourceKind.GOOGLE_DRIVE, account.key))
        assertTrue(vault.all().isEmpty())
        // Without a client ID for the provider, nothing is tried.
        assertFailsWith<HandoffException.Rejected> { runBlocking { adoptHandedOffAccount(account, null, http, vault, tokens) } }
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `keeps an account whose token refreshes, reusing the phone's refresh token`() {
        val server = tokenServer { LocalHttpServer.Response(200, mapOf("Content-Type" to "application/json"), """{"access_token":"a","expires_in":3600}""".toByteArray()) }
        val vault = CloudAccountVault(MemoryStore())
        val http = OkHttpRemoteHttp()
        val tokens = CloudTokenProvider(vault, http, { configuration(server) })
        val adopted = runBlocking { adoptHandedOffAccount(account, configuration(server), http, vault, tokens) }
        assertEquals(account.key, adopted.key)
        assertEquals(listOf(account.key), vault.all().map { it.key })
        assertEquals("refresh-token", vault.account(MediaSourceKind.GOOGLE_DRIVE, account.key)?.refreshToken)
        assertTrue(String(server.requests.single().body).contains("refresh_token=refresh-token"))
        // The access token the refresh produced is cached: the next listing needs no second refresh.
        assertEquals("a", tokens.cachedToken(MediaSourceKind.GOOGLE_DRIVE, account.key))
        assertIs<CloudAccount>(adopted)
    }
}
