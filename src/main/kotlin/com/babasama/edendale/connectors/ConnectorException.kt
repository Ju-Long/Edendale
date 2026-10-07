package com.babasama.edendale.connectors

import com.babasama.edendale.remote.Tls
import com.babasama.edendale.remote.TlsCertificate
import java.io.IOException

/**
 * Why linking, listing, or scanning a source failed (H.3; Apple's
 * `ConnectorError`), for the message the viewer sees. Hosts and paths only,
 * never a credential.
 */
sealed interface ConnectorFailure {
    data object InvalidAddress : ConnectorFailure
    data class Unreachable(val host: String) : ConnectorFailure
    data class ListingFailed(val path: String) : ConnectorFailure
    data class AuthenticationFailed(val host: String) : ConnectorFailure
    data class SignInRequired(val kind: MediaSourceKind) : ConnectorFailure

    /** This build has no client ID for the provider (H.6). */
    data class NotConfigured(val kind: MediaSourceKind) : ConnectorFailure

    /** Plain HTTP to an address outside the private networks (D10): the server needs HTTPS. */
    data object InsecureConnection : ConnectorFailure

    /** An HTTPS server whose certificate the device doesn't trust and nobody has approved yet (D10); [certificate] is what it presented. */
    data class CertificateUntrusted(val host: String, val port: Int, val certificate: TlsCertificate) : ConnectorFailure

    /** An HTTPS server presenting a different certificate from the one approved: refused until approved again. */
    data class CertificateMismatch(val host: String, val port: Int, val certificate: TlsCertificate) : ConnectorFailure

    /** An S3 bucket answered from another region than the one entered (H.5). */
    data class BucketInAnotherRegion(val region: String?) : ConnectorFailure

    data class ServerError(val kind: MediaSourceKind, val status: Int) : ConnectorFailure

    /** An SSH server whose host key hasn't been approved yet (H.4); [key] is what it presented. */
    data class HostKeyUnverified(val host: String, val key: SshHostKey?) : ConnectorFailure

    /** An SSH server presenting a different key from the one approved: refused until approved again. */
    data class HostKeyMismatch(val host: String, val key: SshHostKey?) : ConnectorFailure

    /** The SSH server offers no key exchange, host key, or cipher Edendale supports. */
    data class SecureConnectionFailed(val host: String) : ConnectorFailure

    /** The SSH server takes only key-based or keyboard-interactive logins. */
    data class PasswordLoginUnavailable(val host: String) : ConnectorFailure

    /** The SSH server refused the SFTP subsystem. */
    data class SftpUnavailable(val host: String) : ConnectorFailure

    /** The source needs the viewer to sign in or approve something again, rather than being unreachable. */
    val needsUserAction: Boolean
        get() = this is AuthenticationFailed || this is SignInRequired || this is NotConfigured ||
            this is HostKeyUnverified || this is HostKeyMismatch || this is PasswordLoginUnavailable ||
            this is CertificateUntrusted || this is CertificateMismatch

    companion object {
        /**
         * What a transport failure reaching [host] means (D10): a certificate
         * to review, plain HTTP refused off the private network, or otherwise
         * the server being unreachable.
         */
        fun transport(error: Throwable, host: String): ConnectorFailure {
            Tls.untrusted(error)?.let { untrusted ->
                val name = untrusted.host.ifEmpty { host }
                return if (untrusted.replacesPinned) {
                    CertificateMismatch(name, untrusted.port, untrusted.certificate)
                } else {
                    CertificateUntrusted(name, untrusted.port, untrusted.certificate)
                }
            }
            if (Tls.cleartextRefused(error) != null) return InsecureConnection
            return Unreachable(host)
        }
    }
}

class ConnectorException(val failure: ConnectorFailure, cause: Throwable? = null) : IOException(failure.toString(), cause)
