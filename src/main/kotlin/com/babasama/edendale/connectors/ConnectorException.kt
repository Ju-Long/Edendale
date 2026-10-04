package com.babasama.edendale.connectors

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

    /** Plain HTTP isn't accepted (D10 decides the home-server rules). */
    data object InsecureConnection : ConnectorFailure

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
            this is HostKeyUnverified || this is HostKeyMismatch || this is PasswordLoginUnavailable
}

class ConnectorException(val failure: ConnectorFailure, cause: Throwable? = null) : IOException(failure.toString(), cause)
