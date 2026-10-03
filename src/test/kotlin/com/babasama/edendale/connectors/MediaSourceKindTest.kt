package com.babasama.edendale.connectors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** D.2.T1: the `kind` backfill and the persisted raw values. */
class MediaSourceKindTest {

    @Test
    fun rawValuesNeverChange() {
        assertEquals(
            listOf("local", "smb", "nfs", "sftp", "webdav", "s3", "gdrive", "onedrive", "dropbox"),
            MediaSourceKind.entries.map { it.raw },
        )
        assertEquals(listOf("offline", "needsSignIn"), SourceStatus.entries.map { it.raw })
    }

    @Test
    fun existingRowsGetTheirKindFromTheScheme() {
        assertEquals(
            MediaSourceKind.LOCAL,
            MediaSourceKind.forSourceUri("content://com.android.externalstorage.documents/tree/primary%3AMovies"),
        )
        assertEquals(MediaSourceKind.SMB, MediaSourceKind.forSourceUri("smb://192.168.1.10/media/"))
        assertEquals(MediaSourceKind.SMB, MediaSourceKind.forSourceUri("SMB://NAS/Media/"))
    }

    @Test
    fun laterProvidersMapToo() {
        assertEquals(MediaSourceKind.WEBDAV, MediaSourceKind.forSourceUri("davs://cloud.example.com/remote.php/dav/files/me/"))
        assertEquals(MediaSourceKind.WEBDAV, MediaSourceKind.forSourceUri("dav://192.168.1.2:8080/"))
        assertEquals(MediaSourceKind.SFTP, MediaSourceKind.forSourceUri("sftp://host:2222/srv/media/"))
        assertEquals(MediaSourceKind.GOOGLE_DRIVE, MediaSourceKind.forSourceUri("gdrive://0123abcd/root/Movies"))
        assertEquals(MediaSourceKind.ONE_DRIVE, MediaSourceKind.forSourceUri("onedrive://0123abcd/d/i/Movies"))
        assertEquals(MediaSourceKind.DROPBOX, MediaSourceKind.forSourceUri("dropbox://0123abcd/id/Movies"))
        assertEquals(MediaSourceKind.S3, MediaSourceKind.forSourceUri("s3://0123abcd/bucket/Movies/"))
        assertEquals(MediaSourceKind.NFS, MediaSourceKind.forSourceUri("nfs://nas/export/"))
    }

    @Test
    fun unknownSchemesStayNull() {
        assertNull(MediaSourceKind.forSourceUri("file:///sdcard/Movies"))
        assertNull(MediaSourceKind.forSourceUri("/no/scheme"))
        assertNull(MediaSourceKind.forSourceUri(""))
        assertNull(MediaSourceKind.fromRaw("ftp"))
        assertEquals(MediaSourceKind.SMB, MediaSourceKind.fromRaw("smb"))
        assertEquals(SourceStatus.NEEDS_SIGN_IN, SourceStatus.fromRaw("needsSignIn"))
    }
}
