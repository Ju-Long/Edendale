package com.babasama.edendale.android.data

import com.babasama.edendale.connectors.ConnectorException
import com.babasama.edendale.connectors.ConnectorFailure
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.SourceStatus
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteSourceException
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

    @Test
    fun connectorsThatNeedTheViewerAskForSignIn() {
        val refused = ConnectorException(ConnectorFailure.AuthenticationFailed("cloud.example.com"))
        assertEquals(SourceStatus.NEEDS_SIGN_IN, SourceScanRules.classifyFailure(IOException("wrapped", refused)))
        assertEquals(
            SourceStatus.NEEDS_SIGN_IN,
            SourceScanRules.classifyFailure(RemoteSourceException(MediaSourceKind.DROPBOX, RemoteFailure.SignInRequired)),
        )
        assertEquals(
            SourceStatus.OFFLINE,
            SourceScanRules.classifyFailure(ConnectorException(ConnectorFailure.Unreachable("cloud.example.com"))),
        )
        assertEquals(SourceStatus.OFFLINE, SourceScanRules.classifyFailure(ConnectorException(ConnectorFailure.ListingFailed("/dav"))))
    }

    @Test
    fun serverLoginsCountTheirSourcesByKindHostAndPort() {
        val nextcloud = SavedServerLogin(MediaSourceKind.WEBDAV, "cloud.example.com", null, "me")
        val synology = SavedServerLogin(MediaSourceKind.WEBDAV, "nas.local", 5006, "me")
        val usage = serverLoginUsage(
            listOf(nextcloud, synology),
            listOf(
                folder("davs://Cloud.Example.com/remote.php/dav/files/me/Movies/", "webdav"),
                folder("davs://cloud.example.com/remote.php/dav/files/me/Shows/", "webdav"),
                folder("davs://nas.local:5006/video/", "webdav"),
                // Another port, another kind, a local folder: none count.
                folder("davs://nas.local:5005/video/", "webdav"),
                folder("smb://nas.local/video/", "smb"),
                folder("content://tree/movies"),
            ),
        )
        assertEquals(2, usage[nextcloud])
        assertEquals(1, usage[synology])
        assertEquals("nas.local:5006", synology.address)
        assertEquals("webdav|nas.local|5006", ServerLoginStore.key(MediaSourceKind.WEBDAV, "NAS.local", 5006))
        assertEquals("webdav|nas.local|", ServerLoginStore.key(MediaSourceKind.WEBDAV, "nas.local", null))
    }
}
