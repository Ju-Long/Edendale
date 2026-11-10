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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** D.3.T1: the automatic-rescan throttle and failure classification. */
class SourceScanRulesTest {

    private val now = 10_000_000_000L
    private val minute = 60_000L

    private fun folder(
        treeUri: String,
        kind: String? = null,
        lastScannedAt: Long? = null,
        scanStartedAt: Long? = null,
    ) = LibraryFolderEntity(
        treeUri = treeUri,
        displayName = "Media",
        addedAtEpochMillis = 0,
        kind = kind,
        lastScannedAt = lastScannedAt,
        scanStartedAt = scanStartedAt,
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

    /** A scan the app was closed in the middle of left the source incomplete, so the throttle doesn't apply. */
    @Test
    fun interruptedSourcesRescanInsideTheThrottle() {
        val share = folder("smb://nas/media/", "smb", lastScannedAt = now - minute)
        assertFalse(SourceScanRules.shouldAutoRescan(share, now))
        assertTrue(SourceScanRules.shouldAutoRescan(share.copy(scanStartedAt = now - 30_000), now))
        // A first scan that never finished, and a local folder, rescan either way.
        assertTrue(SourceScanRules.shouldAutoRescan(folder("smb://nas/media/", "smb", scanStartedAt = now - minute), now))
        assertTrue(SourceScanRules.shouldAutoRescan(folder("content://tree/movies", "local", now - minute, now - minute), now))
    }

    /**
     * The launch resume and the Downloaded sweep each work through sources
     * read once, so a scan checks the source's row again as it claims it: a
     * source another scan finished in the meantime isn't listed again.
     */
    @Test
    fun aSourceAnotherScanFinishedIsNoLongerDue() {
        // Killed mid-scan two minutes after its last good scan: both list it.
        val interrupted = folder("smb://nas/media/", "smb", lastScannedAt = now - 2 * minute, scanStartedAt = now - minute)
        assertTrue(SourceScanRules.isInterrupted(interrupted))
        assertTrue(SourceScanRules.shouldAutoRescan(interrupted, now))

        // Committed by the other (LibraryDao.finishScan): neither lists it again.
        val committed = interrupted.copy(lastScannedAt = now, scanStartedAt = null, status = null)
        assertFalse(SourceScanRules.isInterrupted(committed))
        assertFalse(SourceScanRules.shouldAutoRescan(committed, now + 30_000))

        // A failure the other recorded (markFolderFailed) ends it for the resume;
        // the sweep's throttle still counts from the last good scan.
        val failed = interrupted.copy(scanStartedAt = null, status = "offline")
        assertFalse(SourceScanRules.isInterrupted(failed))
        assertFalse(SourceScanRules.shouldAutoRescan(failed, now))
        assertTrue(SourceScanRules.shouldAutoRescan(failed, now + 15 * minute))

        // A local folder is due on every sweep, as on every visit.
        val local = folder("content://tree/movies", "local", lastScannedAt = now)
        assertFalse(SourceScanRules.isInterrupted(local))
        assertTrue(SourceScanRules.shouldAutoRescan(local, now))
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

    // MARK: - Where a source is reached from (27.1)

    @Test
    fun theVaultOrTheSavedS3LocationNamesTheSource() {
        assertEquals(
            "me@example.com",
            SourceScanRules.endpointToRecord(MediaSourceKind.GOOGLE_DRIVE, stored = null, fromStore = "me@example.com", displayPath = "gdrive › Videos you chose"),
        )
        // A new sign-in can bring a new email.
        assertEquals(
            "new@example.com",
            SourceScanRules.endpointToRecord(MediaSourceKind.ONE_DRIVE, stored = "old@example.com", fromStore = " new@example.com ", displayPath = null),
        )
        assertEquals(
            "http://minio.local:9000",
            SourceScanRules.endpointToRecord(MediaSourceKind.S3, stored = null, fromStore = "http://minio.local:9000", displayPath = "films › Movies"),
        )
    }

    @Test
    fun aSignedOutAccountKeepsItsLabel() {
        assertEquals("me@example.com", SourceScanRules.endpointToRecord(MediaSourceKind.DROPBOX, "me@example.com", null, "Jane › Movies"))
        assertEquals("https://s3.example.com", SourceScanRules.endpointToRecord(MediaSourceKind.S3, "https://s3.example.com", null, "films"))
    }

    @Test
    fun aSourceLinkedBeforeV5FallsBackToItsPath() {
        assertEquals("me@example.com", SourceScanRules.endpointToRecord(MediaSourceKind.GOOGLE_DRIVE, null, null, "me@example.com › Videos you chose"))
        assertNull(SourceScanRules.endpointToRecord(MediaSourceKind.GOOGLE_DRIVE, null, null, "gdrive › Videos you chose"))
        // An S3 path starts with the bucket, which the URL already names.
        assertNull(SourceScanRules.endpointToRecord(MediaSourceKind.S3, null, null, "films › Movies"))
    }

    @Test
    fun serversAndLocalFoldersRecordNothing() {
        assertNull(SourceScanRules.endpointToRecord(MediaSourceKind.SMB, null, "nas", "smb://nas/media/"))
        assertNull(SourceScanRules.endpointToRecord(MediaSourceKind.SFTP, "stray", null, "nas.local › home"))
        assertNull(SourceScanRules.endpointToRecord(MediaSourceKind.LOCAL, null, null, null))
        assertNull(SourceScanRules.endpointToRecord(null, null, null, null))
    }
}
