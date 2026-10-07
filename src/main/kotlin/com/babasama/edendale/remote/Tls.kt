package com.babasama.edendale.remote

import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * A server certificate as Edendale shows and pins it (H.3.4, D10): the
 * subject, who issued it, its SHA-256 fingerprint, and when it expires.
 * Never the certificate itself, which can be large.
 */
data class TlsCertificate(
    /** The subject's distinguished name, RFC 2253 (`CN=nas.local,O=Home`). */
    val subject: String,
    val issuer: String,
    /** `SHA-256` of the DER certificate as hex pairs with colons, as browsers print it. */
    val fingerprint: String,
    val notAfterEpochMillis: Long,
) {
    /** Whether the certificate signed itself, which is what a home server usually presents. */
    val isSelfSigned: Boolean get() = subject == issuer
}

/** A certificate fingerprint pinned for a host and port, or none yet (the TLS twin of [com.babasama.edendale.connectors.Sftp.HostKeyPins]). */
fun interface TlsPins {
    fun pinned(host: String, port: Int): String?

    companion object {
        /** No pins: only certificates the device trusts connect. */
        val NONE = TlsPins { _, _ -> null }
    }
}

/**
 * The device didn't trust the server's certificate and no pin covered it
 * (D10). [replacesPinned] says a different certificate was approved before,
 * which the viewer should treat with more suspicion than a first contact.
 */
class UntrustedCertificateException(
    val host: String,
    val port: Int,
    val certificate: TlsCertificate,
    val replacesPinned: Boolean,
    cause: Throwable? = null,
) : CertificateException("Untrusted certificate for $host:$port", cause)

/** A plain `http://` request to an address outside the private networks D10 allows. */
class CleartextRefusedException(val host: String) : UnknownHostException("$host needs HTTPS: plain HTTP connects only to private-network addresses")

/** TLS rules for home servers (D10). Pure JVM: no Android or OkHttp here. */
object Tls {

    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(it) }

    fun describe(certificate: X509Certificate) = TlsCertificate(
        subject = certificate.subjectX500Principal.getName(X500Principal.RFC2253),
        issuer = certificate.issuerX500Principal.getName(X500Principal.RFC2253),
        fingerprint = fingerprint(certificate),
        notAfterEpochMillis = certificate.notAfter.time,
    )

    /**
     * The private-network ranges plain HTTP may reach (D10): 10/8, 172.16/12,
     * 192.168/16, 100.64/10 (Tailscale), 127/8, 169.254/16, `::1`, `fc00::/7`,
     * and `fe80::/10`. Checked on the resolved address, never on the name.
     */
    fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        val bytes = address.address
        return when (bytes.size) {
            // 100.64.0.0/10: carrier-grade NAT, which Tailscale hands out.
            4 -> bytes[0].toInt() and 0xFF == 100 && bytes[1].toInt() and 0xC0 == 64
            // fc00::/7: unique local addresses.
            16 -> bytes[0].toInt() and 0xFE == 0xFC
            else -> false
        }
    }

    /** The [UntrustedCertificateException] in an error's cause chain, if the failure was one. */
    fun untrusted(error: Throwable?): UntrustedCertificateException? = find(error)

    /** The [CleartextRefusedException] in an error's cause chain, if the failure was one. */
    fun cleartextRefused(error: Throwable?): CleartextRefusedException? = find(error)

    private inline fun <reified T : Throwable> find(error: Throwable?): T? {
        var cause = error
        val seen = HashSet<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (cause is T) return cause
            cause = cause.cause
        }
        return null
    }

    /** The platform's own trust store: the system CAs on Android, the JDK's `cacerts` in tests. */
    fun systemTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** An SSL context whose server checks go through [PinnedTrustManager]. */
    fun sslContext(trust: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
}

/**
 * System validation first; when it fails, the leaf certificate's fingerprint
 * pinned for the peer's host and port (D10). Anything else fails with
 * [UntrustedCertificateException], carrying what the server presented so the
 * viewer can review and trust it. Pins are looked up at handshake time, so a
 * certificate trusted a moment ago connects without rebuilding the client.
 */
class PinnedTrustManager(
    private val pins: TlsPins,
    private val system: X509TrustManager = Tls.systemTrustManager(),
) : X509ExtendedTrustManager() {

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
        val session = (socket as? SSLSocket)?.handshakeSession
        check(chain, authType, session?.peerHost, session?.peerPort ?: socket?.port ?: -1)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
        check(chain, authType, engine?.peerHost, engine?.peerPort ?: -1)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = check(chain, authType, null, -1)

    private fun check(chain: Array<X509Certificate>, authType: String, host: String?, port: Int) {
        val failure = try {
            system.checkServerTrusted(chain, authType)
            return
        } catch (error: CertificateException) {
            error
        }
        val leaf = chain.firstOrNull() ?: throw failure
        val presented = Tls.fingerprint(leaf)
        val pinned = host?.let { pins.pinned(it, port) }
        if (pinned != null && pinned.equals(presented, ignoreCase = true)) return
        throw UntrustedCertificateException(host.orEmpty(), port, Tls.describe(leaf), replacesPinned = pinned != null, cause = failure)
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) = system.checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) = system.checkClientTrusted(chain, authType)
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    /**
     * Hostname checking to pair with this trust manager: the usual name
     * match, or a pinned certificate, whose subject rarely names the host.
     * A resumed TLS session skips the trust manager, so this is also where a
     * pin removed or changed since the last handshake is caught; it fails
     * with the same [UntrustedCertificateException] (under the
     * [SSLPeerUnverifiedException] OkHttp expects) so the viewer gets the
     * same review.
     */
    fun hostnameVerifier(standard: HostnameVerifier): HostnameVerifier = HostnameVerifier { hostname, session ->
        if (standard.verify(hostname, session)) return@HostnameVerifier true
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull() ?: return@HostnameVerifier false
        val pinned = pins.pinned(hostname, session.peerPort)
        if (pinned != null && pinned.equals(Tls.fingerprint(leaf), ignoreCase = true)) return@HostnameVerifier true
        throw SSLPeerUnverifiedException("Hostname $hostname not verified").apply {
            initCause(UntrustedCertificateException(hostname, session.peerPort, Tls.describe(leaf), replacesPinned = pinned != null))
        }
    }
}
