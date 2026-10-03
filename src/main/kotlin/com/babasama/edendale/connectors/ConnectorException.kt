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

    /** Plain HTTP isn't accepted (D10 decides the home-server rules). */
    data object InsecureConnection : ConnectorFailure

    /** The source needs the viewer to sign in or approve something again, rather than being unreachable. */
    val needsUserAction: Boolean get() = this is AuthenticationFailed || this is SignInRequired
}

class ConnectorException(val failure: ConnectorFailure, cause: Throwable? = null) : IOException(failure.toString(), cause)
