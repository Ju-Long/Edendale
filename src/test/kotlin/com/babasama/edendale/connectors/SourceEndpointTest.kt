package com.babasama.edendale.connectors

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Play From names a copy by where it's reached from (27.1). */
class SourceEndpointTest {

    private fun label(kind: MediaSourceKind?, treeUri: String?, endpoint: String? = null) =
        SourceEndpoint.label(SourceEndpoint.of(kind, treeUri, endpoint))

    @Test
    fun aLocalFolderOrAFileAddedOnItsOwnIsThisDevice() {
        assertEquals("This device", label(MediaSourceKind.LOCAL, "content://com.android.externalstorage.documents/tree/primary%3AMovies"))
        // A file added on its own has no source row at all.
        assertEquals("This device", label(null, null))
        assertEquals("This device", label(MediaSourceKind.SMB, null))
        assertEquals(SourceEndpoint.ThisDevice, SourceEndpoint.of(null, "smb://nas/x/", null))
    }

    @Test
    fun oneShareLinkedTwiceReadsAsTwoHosts() {
        assertEquals("longs-mac-studio.local", label(MediaSourceKind.SMB, "smb://Longs-Mac-Studio.local/Extra/"))
        // The full address: the owner's "100.*.*.*" was an example, and two Tailscale peers must stay apart.
        assertEquals("100.101.102.103", label(MediaSourceKind.SMB, "smb://100.101.102.103/Extra/"))
    }

    @Test
    fun defaultPortsAreHiddenAndOthersShown() {
        assertEquals("nas", label(MediaSourceKind.SMB, "smb://nas:445/x"))
        assertEquals("nas:4455", label(MediaSourceKind.SMB, "smb://nas:4455/x"))
        assertEquals("nas.local", label(MediaSourceKind.SFTP, "sftp://nas.local/home/"))
        assertEquals("nas.local", label(MediaSourceKind.SFTP, "sftp://nas.local:22/home/"))
        assertEquals("nas.local:2222", label(MediaSourceKind.SFTP, "sftp://nas.local:2222/"))
        assertEquals("cloud.example.com", label(MediaSourceKind.WEBDAV, "davs://cloud.example.com/remote.php/dav/"))
        assertEquals("cloud.example.com", label(MediaSourceKind.WEBDAV, "davs://cloud.example.com:443/remote.php/dav/"))
        assertEquals("nas.local:5006", label(MediaSourceKind.WEBDAV, "davs://nas.local:5006/"))
        assertEquals("192.168.1.5", label(MediaSourceKind.WEBDAV, "dav://192.168.1.5:80/"))
        assertEquals("192.168.1.5:443", label(MediaSourceKind.WEBDAV, "dav://192.168.1.5:443/"))
        assertEquals("nas", label(MediaSourceKind.NFS, "nfs://nas/export/"))
        assertEquals("nas", label(MediaSourceKind.NFS, "nfs://nas:2049/export/"))
    }

    @Test
    fun ipv6KeepsItsBracketsAndALoginIsNeverShown() {
        assertEquals("[fd7a:115c:a1e0::1]", label(MediaSourceKind.SMB, "smb://[fd7a:115c:a1e0::1]/x"))
        assertEquals("[fd7a:115c:a1e0::1]:4455", label(MediaSourceKind.SMB, "smb://[FD7A:115C:A1E0::1]:4455/x"))
        assertEquals("nas.local", label(MediaSourceKind.SMB, "smb://me:secret@nas.local/Extra/"))
        // A fully qualified name's trailing dot is the same host.
        assertEquals("nas.example.com", label(MediaSourceKind.SFTP, "sftp://nas.example.com./media/"))
        assertEquals(SourceEndpoint.Server("nas.local", 2222), SourceEndpoint.server("sftp://me@nas.local:2222/"))
        assertNull(SourceEndpoint.server("nas.local/share"))
        assertNull(SourceEndpoint.server("smb:///share"))
    }

    @Test
    fun aCloudCopyNamesItsAccount() {
        assertEquals("Google Drive - me@example.com", label(MediaSourceKind.GOOGLE_DRIVE, "gdrive://9f86d081884c7d659a2feaa0c55ad015/chosen/", "me@example.com"))
        assertEquals("OneDrive - me@outlook.com", label(MediaSourceKind.ONE_DRIVE, "onedrive://9f86d081884c7d659a2feaa0c55ad015/d/i/Movies", "me@outlook.com"))
        assertEquals("Dropbox - me@example.com", label(MediaSourceKind.DROPBOX, "dropbox://9f86d081884c7d659a2feaa0c55ad015/id%3Aabc/Movies", " me@example.com "))
        // No email: the name the account goes by; neither: the provider alone, never the URL's account key.
        assertEquals("Dropbox - Jane Doe", label(MediaSourceKind.DROPBOX, "dropbox://9f86d081884c7d659a2feaa0c55ad015/id%3Aabc/Movies", "Jane Doe"))
        assertEquals("Google Drive", label(MediaSourceKind.GOOGLE_DRIVE, "gdrive://9f86d081884c7d659a2feaa0c55ad015/chosen/", null))
        assertEquals("Google Drive", label(MediaSourceKind.GOOGLE_DRIVE, "gdrive://9f86d081884c7d659a2feaa0c55ad015/chosen/", "  "))
    }

    @Test
    fun s3NamesItsServerWhenTheEndpointIsKnownElseItsBucket() {
        val bucket = "s3://9f86d081884c7d659a2feaa0c55ad015/films/Movies/"
        assertEquals("minio.local:9000", label(MediaSourceKind.S3, bucket, "http://minio.local:9000"))
        assertEquals("s3.us-east-1.amazonaws.com", label(MediaSourceKind.S3, bucket, "https://s3.us-east-1.amazonaws.com"))
        assertEquals("minio.local:9000", label(MediaSourceKind.S3, bucket, "minio.local:9000"))
        assertEquals("S3-Compatible Storage - films", label(MediaSourceKind.S3, bucket, null))
    }

    @Test
    fun theLabelUsesTheScreensTranslations() {
        val provider: (MediaSourceKind) -> String = { if (it == MediaSourceKind.S3) "Stockage S3" else it.raw.uppercase() }
        fun french(endpoint: SourceEndpoint) =
            SourceEndpoint.label(endpoint, provider, thisDevice = "Cet appareil", withIdentity = { name, who -> "$who sur $name" })
        assertEquals("Cet appareil", french(SourceEndpoint.ThisDevice))
        assertEquals("me@example.com sur GDRIVE", french(SourceEndpoint.Account(MediaSourceKind.GOOGLE_DRIVE, "me@example.com")))
        assertEquals("Stockage S3", french(SourceEndpoint.Account(MediaSourceKind.S3, null)))
        // An address is never translated.
        assertEquals("nas:4455", french(SourceEndpoint.Server("nas", 4455)))
    }

    @Test
    fun aSourceLinkedBeforeTheEndpointWasRecordedFindsItsAccountInItsPath() {
        assertEquals("me@example.com", SourceEndpoint.accountFromDisplayPath(MediaSourceKind.GOOGLE_DRIVE, "me@example.com › Videos you chose"))
        assertEquals("Jane Doe", SourceEndpoint.accountFromDisplayPath(MediaSourceKind.DROPBOX, "Jane Doe"))
        // An account with neither email nor name was labelled by its raw kind.
        assertNull(SourceEndpoint.accountFromDisplayPath(MediaSourceKind.GOOGLE_DRIVE, "gdrive › Videos you chose"))
        assertNull(SourceEndpoint.accountFromDisplayPath(MediaSourceKind.ONE_DRIVE, " › Movies"))
        assertNull(SourceEndpoint.accountFromDisplayPath(MediaSourceKind.ONE_DRIVE, null))
        assertEquals("me@example.com", SourceEndpoint.accountIdentity(" me@example.com ", "Me"))
        assertEquals("Me", SourceEndpoint.accountIdentity("", "Me"))
        assertNull(SourceEndpoint.accountIdentity(null, " "))
    }

    @Test
    fun sortKeysCarryNoTranslatedText() {
        assertEquals("", SourceEndpoint.ThisDevice.sortKey)
        assertEquals("nas:4455", SourceEndpoint.Server("nas", 4455).sortKey)
        assertEquals("gdrive:me@example.com", SourceEndpoint.Account(MediaSourceKind.GOOGLE_DRIVE, "Me@Example.com").sortKey)
        assertEquals("s3:", SourceEndpoint.Account(MediaSourceKind.S3, null).sortKey)
        assertNotEquals(
            SourceEndpoint.of(MediaSourceKind.SMB, "smb://longs-mac-studio.local/Extra/", null).sortKey,
            SourceEndpoint.of(MediaSourceKind.SMB, "smb://100.101.102.103/Extra/", null).sortKey,
        )
    }
}
