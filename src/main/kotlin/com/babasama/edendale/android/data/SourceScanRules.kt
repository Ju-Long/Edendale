package com.babasama.edendale.android.data

import com.babasama.edendale.connectors.ConnectorException
import com.babasama.edendale.connectors.Enumeration
import com.babasama.edendale.connectors.MediaConnector
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.SourceStatus
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteSourceException
import jcifs.smb.NtStatus
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbException

/**
 * When the library rescans a source on its own, and how a failed scan is
 * recorded on that source (D.3). Pure apart from jcifs-ng's exception types,
 * so the JVM suite covers it.
 */
internal object SourceScanRules {

    /** Remote sources scanned more recently than this are skipped by the automatic sweep. */
    const val AUTOMATIC_RESCAN_INTERVAL_MILLIS = 15 * 60 * 1000L

    fun kindOf(folder: LibraryFolderEntity): MediaSourceKind? =
        MediaSourceKind.fromRaw(folder.kind) ?: MediaSourceKind.forSourceUri(folder.treeUri)

    /**
     * Whether the sweep on each Downloaded visit rescans [folder]. Local
     * folders always do; a remote one only once 15 minutes have passed since
     * its last successful scan. A manual Rescan ignores this.
     */
    fun shouldAutoRescan(folder: LibraryFolderEntity, nowMillis: Long): Boolean {
        if (kindOf(folder)?.isRemote != true) return true
        val last = folder.lastScannedAt ?: return true
        return nowMillis - last >= AUTOMATIC_RESCAN_INTERVAL_MILLIS
    }

    /**
     * Every video in the linked [folder], after checking the source still
     * answers. The walk starts at the folder itself, not the connector's
     * root: a cloud account's or S3 bucket's root is the whole account, so
     * starting there imported everything the account holds.
     */
    suspend fun enumerate(connector: MediaConnector, folder: LibraryFolderEntity): Enumeration {
        connector.validate()
        return connector.enumerateVideos(folder.treeUri)
    }

    /** NT status codes that mean the server refused the login rather than the network failing. */
    private val authenticationStatuses = setOf(
        NtStatus.NT_STATUS_LOGON_FAILURE,
        NtStatus.NT_STATUS_WRONG_PASSWORD,
        NtStatus.NT_STATUS_ACCESS_DENIED,
        NtStatus.NT_STATUS_ACCOUNT_DISABLED,
        NtStatus.NT_STATUS_ACCOUNT_LOCKED_OUT,
        NtStatus.NT_STATUS_ACCOUNT_RESTRICTION,
        NtStatus.NT_STATUS_PASSWORD_EXPIRED,
        NtStatus.NT_STATUS_PASSWORD_MUST_CHANGE,
        NtStatus.NT_STATUS_LOGON_TYPE_NOT_GRANTED,
        NtStatus.NT_STATUS_NO_SUCH_USER,
        NtStatus.NT_STATUS_TRUSTED_DOMAIN_FAILURE,
    )

    /**
     * `needsSignIn` when the server refused the login (jcifs's
     * [SmbAuthException], an authentication NT status, or a connector's
     * refused login or missing account anywhere in the cause chain);
     * `offline` for everything else — unreachable hosts,
     * timeouts, missing shares, an unmounted folder.
     */
    fun classifyFailure(error: Throwable): SourceStatus {
        var cause: Throwable? = error
        val seen = HashSet<Throwable>()
        while (cause != null && seen.add(cause)) {
            if (cause is SmbAuthException) return SourceStatus.NEEDS_SIGN_IN
            if (cause is ConnectorException && cause.failure.needsUserAction) return SourceStatus.NEEDS_SIGN_IN
            if (cause is RemoteSourceException && cause.failure == RemoteFailure.SignInRequired) return SourceStatus.NEEDS_SIGN_IN
            if (cause is SmbException && cause.ntStatus in authenticationStatuses) return SourceStatus.NEEDS_SIGN_IN
            cause = cause.cause
        }
        return SourceStatus.OFFLINE
    }
}
