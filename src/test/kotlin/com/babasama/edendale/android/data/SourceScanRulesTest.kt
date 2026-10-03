package com.babasama.edendale.android.data

import com.babasama.edendale.connectors.SourceStatus
import jcifs.smb.NtStatus
import jcifs.smb.SmbException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** D.3.T1: the automatic-rescan throttle and failure classification. */
class SourceScanRulesTest {

    private val now = 10_000_000_000L
    private val minute = 60_000L

    private fun folder(treeUri: String, kind: String? = null, lastScannedAt: Long? = null) = LibraryFolderEntity(
        treeUri = treeUri,
        displayName = "Media",
        addedAtEpochMillis = 0,
        kind = kind,
        lastScannedAt = lastScannedAt,
    )

    @Test
    fun remoteSourcesWaitFifteenMinutes() {
        val share = { scannedAgo: Long -> folder("smb://nas/media/", "smb", now - scannedAgo) }
        assertFalse(SourceScanRules.shouldAutoRescan(share(14 * minute + 59_000), now))
        assertTrue(SourceScanRules.shouldAutoRescan(share(15 * minute), now))
        assertTrue(SourceScanRules.shouldAutoRescan(share(15 * minute + 1_000), now))
        // Never scanned (or a row from before v3): scan.
        assertTrue(SourceScanRules.shouldAutoRescan(folder("smb://nas/media/", "smb", null), now))
        // A row without a kind is judged by its URI.
        assertFalse(SourceScanRules.shouldAutoRescan(folder("smb://nas/media/", null, now - minute), now))
    }

    @Test
    fun localSourcesAreNeverThrottled() {
        val local = folder("content://com.android.externalstorage.documents/tree/primary%3AMovies", "local", now - minute)
        assertTrue(SourceScanRules.shouldAutoRescan(local, now))
        assertTrue(SourceScanRules.shouldAutoRescan(local.copy(kind = null), now))
    }

    @Test
    fun authenticationErrorsNeedSignIn() {
        assertEquals(
            SourceStatus.NEEDS_SIGN_IN,
            SourceScanRules.classifyFailure(SmbException(NtStatus.NT_STATUS_LOGON_FAILURE, false)),
        )
        assertEquals(
            SourceStatus.NEEDS_SIGN_IN,
            SourceScanRules.classifyFailure(SmbException(NtStatus.NT_STATUS_ACCESS_DENIED, false)),
        )
        // Wrapped, as listFiles() sometimes reports it.
        assertEquals(
            SourceStatus.NEEDS_SIGN_IN,
            SourceScanRules.classifyFailure(IOException("list", SmbException(NtStatus.NT_STATUS_WRONG_PASSWORD, false))),
        )
    }

    @Test
    fun ioErrorsAreOffline() {
        assertEquals(SourceStatus.OFFLINE, SourceScanRules.classifyFailure(SocketTimeoutException("timed out")))
        assertEquals(SourceStatus.OFFLINE, SourceScanRules.classifyFailure(UnknownHostException("nas")))
        assertEquals(SourceStatus.OFFLINE, SourceScanRules.classifyFailure(SmbException("Failed to connect: nas")))
        assertEquals(
            SourceStatus.OFFLINE,
            SourceScanRules.classifyFailure(SmbException(NtStatus.NT_STATUS_BAD_NETWORK_NAME, false)),
        )
        // The local folder errors are plain exceptions.
        assertEquals(SourceStatus.OFFLINE, SourceScanRules.classifyFailure(IllegalStateException("Folder can't be opened")))
    }
}
