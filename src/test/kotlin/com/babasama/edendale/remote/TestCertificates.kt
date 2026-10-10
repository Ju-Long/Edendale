package com.babasama.edendale.remote

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** Self-signed server certificates for the JVM suite's HTTPS servers (D10), made fresh for each test. */
object TestCertificates {

    class Identity(val certificate: X509Certificate, val sslContext: SSLContext) {
        val fingerprint: String get() = Tls.fingerprint(certificate)
    }

    /** A P-256 certificate for [commonName], valid from a minute ago for a day, signed by itself. */
    fun selfSigned(commonName: String = "nas.local"): Identity {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        // Reversed here so the RFC 2253 form Edendale shows reads CN first.
        val name = X500Name("O=Edendale Tests,CN=$commonName")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(now),
            Date(now - 60_000),
            Date(now + 86_400_000),
            name,
            keys.public,
        ).build(JcaContentSignerBuilder("SHA256withECDSA").build(keys.private))
        val certificate = JcaX509CertificateConverter().getCertificate(holder)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("server", keys.private, CharArray(0), arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, CharArray(0)) }
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        return Identity(certificate, context)
    }
}
