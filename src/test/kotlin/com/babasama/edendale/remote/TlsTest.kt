package com.babasama.edendale.remote

import okhttp3.Dns
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * H.3.4 (D10): certificate fingerprints, the private-network rule for plain
 * HTTP, and trust on first use for HTTPS through OkHttp against a local
 * server with a self-signed certificate.
 */
class TlsTest {

    private val servers = mutableListOf<LocalHttpServer>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private val ok = LocalHttpServer.Response(200, mapOf("Content-Type" to "text/plain"), "ok".toByteArray())

    /** Resolves every name to a public address (TEST-NET-3). */
    private val publicDns = object : Dns {
        override fun lookup(hostname: String) = listOf(InetAddress.getByName("203.0.113.5"))
    }

    @Test
    fun `fingerprints are SHA-256 of the certificate as hex pairs`() {
        val identity = TestCertificates.selfSigned("nas.local")
        val fingerprint = Tls.fingerprint(identity.certificate)
        assertTrue(Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$").matches(fingerprint), fingerprint)
        val expected = MessageDigest.getInstance("SHA-256").digest(identity.certificate.encoded).joinToString(":") { "%02X".format(it) }
        assertEquals(expected, fingerprint)
        val described = Tls.describe(identity.certificate)
        assertEquals("CN=nas.local,O=Edendale Tests", described.subject)
        assertTrue(described.isSelfSigned)
        assertEquals(identity.certificate.notAfter.time, described.notAfterEpochMillis)
    }

    @Test
    fun `private networks are the ranges D10 lists`() {
        val private = listOf(
            "10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.255", "192.168.1.1",
            "100.64.0.1", "100.127.255.255", "127.0.0.1", "127.1.2.3", "169.254.1.1",
            "::1", "fc00::1", "fdab:1234::9", "fe80::1",
        )
        val public = listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "172.15.255.255", "100.63.255.255", "100.128.0.1", "203.0.113.5", "2001:4860:4860::8888", "2606:4700::1111")
        private.forEach { assertTrue(Tls.isPrivateAddress(InetAddress.getByName(it)), it) }
        public.forEach { assertFalse(Tls.isPrivateAddress(InetAddress.getByName(it)), it) }
    }

    @Test
    fun `plain HTTP resolves only to private addresses`() {
        val answers = mutableMapOf<String, List<InetAddress>>()
        val dns = PrivateNetworkDns(object : Dns {
            override fun lookup(hostname: String) = answers.getValue(hostname)
        })
        answers["nas.local"] = listOf(InetAddress.getByName("192.168.1.20"))
        assertEquals(answers["nas.local"], dns.lookup("nas.local"))
        // One public address among the answers is enough to refuse: OkHttp would try them all.
        answers["mixed.example"] = listOf(InetAddress.getByName("10.0.0.2"), InetAddress.getByName("203.0.113.5"))
        val refused = assertFailsWith<CleartextRefusedException> { dns.lookup("mixed.example") }
        assertEquals("mixed.example", refused.host)
        answers["public.example"] = listOf(InetAddress.getByName("203.0.113.5"))
        assertFailsWith<CleartextRefusedException> { dns.lookup("public.example") }
        answers["nowhere.example"] = emptyList()
        assertFailsWith<CleartextRefusedException> { dns.lookup("nowhere.example") }
    }

    @Test
    fun `plain HTTP to the loopback goes through the shared transport`() {
        val server = LocalHttpServer({ ok }).also { servers += it }
        val response = OkHttpRemoteHttp().newCall(RemoteRequest("http://127.0.0.1:${server.port}/"), bodyLimit = 1024).execute()
        assertEquals(200, response.status)
        assertEquals("ok", response.body.decodeToString())
    }

    @Test
    fun `plain HTTP off the private network is refused before connecting`() {
        val http = OkHttpRemoteHttp(RemoteTls.newClient(TlsPins.NONE, publicDns))
        val error = assertFailsWith<IOException> { http.newCall(RemoteRequest("http://nas.example/"), bodyLimit = 1024).execute() }
        assertEquals("nas.example", assertNotNull(Tls.cleartextRefused(error)).host)
    }

    @Test
    fun `an untrusted certificate is reported for review, then pinned`() {
        val identity = TestCertificates.selfSigned("nas.local")
        val server = LocalHttpServer({ ok }, identity.sslContext).also { servers += it }
        val pins = mutableMapOf<String, String>()
        val http = OkHttpRemoteHttp(RemoteTls.newClient(TlsPins { host, port -> pins["$host:$port"] }))
        val url = "https://127.0.0.1:${server.port}/"

        // First contact: the device doesn't trust it and nothing is pinned.
        val first = assertFailsWith<IOException> { http.newCall(RemoteRequest(url), bodyLimit = 1024).execute() }
        val untrusted = assertNotNull(Tls.untrusted(first))
        assertEquals("127.0.0.1", untrusted.host)
        assertEquals(server.port, untrusted.port)
        assertEquals(identity.fingerprint, untrusted.certificate.fingerprint)
        assertEquals("CN=nas.local,O=Edendale Tests", untrusted.certificate.subject)
        assertFalse(untrusted.replacesPinned)

        // A different certificate was approved before: refused, and said so.
        pins["127.0.0.1:${server.port}"] = "AA:BB:CC"
        val changed = assertNotNull(Tls.untrusted(assertFailsWith<IOException> { http.newCall(RemoteRequest(url), bodyLimit = 1024).execute() }))
        assertTrue(changed.replacesPinned)
        assertEquals(identity.fingerprint, changed.certificate.fingerprint)

        // Pinned: connects, although the certificate names nas.local and the URL says 127.0.0.1.
        pins["127.0.0.1:${server.port}"] = identity.fingerprint.lowercase()
        val response = http.newCall(RemoteRequest(url), bodyLimit = 1024).execute()
        assertEquals(200, response.status)

        // A pin for another port doesn't carry over.
        pins.clear()
        pins["127.0.0.1:1"] = identity.fingerprint
        assertNotNull(Tls.untrusted(assertFailsWith<IOException> { http.newCall(RemoteRequest(url), bodyLimit = 1024).execute() }))
    }

    @Test
    fun `transport failures become connector failures`() {
        val identity = TestCertificates.selfSigned()
        val certificate = Tls.describe(identity.certificate)
        val untrusted = IOException("handshake", UntrustedCertificateException("nas.local", 8443, certificate, replacesPinned = false))
        assertEquals(
            com.babasama.edendale.connectors.ConnectorFailure.CertificateUntrusted("nas.local", 8443, certificate),
            com.babasama.edendale.connectors.ConnectorFailure.transport(untrusted, "nas.local"),
        )
        val mismatch = IOException("handshake", UntrustedCertificateException("", 443, certificate, replacesPinned = true))
        assertEquals(
            com.babasama.edendale.connectors.ConnectorFailure.CertificateMismatch("typed.host", 443, certificate),
            com.babasama.edendale.connectors.ConnectorFailure.transport(mismatch, "typed.host"),
        )
        assertEquals(
            com.babasama.edendale.connectors.ConnectorFailure.InsecureConnection,
            com.babasama.edendale.connectors.ConnectorFailure.transport(IOException("dns", CleartextRefusedException("x")), "x"),
        )
        assertEquals(
            com.babasama.edendale.connectors.ConnectorFailure.Unreachable("x"),
            com.babasama.edendale.connectors.ConnectorFailure.transport(IOException("timeout"), "x"),
        )
        assertTrue(com.babasama.edendale.connectors.ConnectorFailure.CertificateMismatch("h", 443, certificate).needsUserAction)
    }
}
