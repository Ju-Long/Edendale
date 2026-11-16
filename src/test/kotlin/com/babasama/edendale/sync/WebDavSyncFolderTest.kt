package com.babasama.edendale.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/** The WebDAV folder the sync location chooser starts with for a saved login. */
class WebDavSyncFolderTest {
    @Test
    fun plainHttpServerKeepsHttp() {
        // A server on the viewer's network linked over http is stored as dav://.
        assertEquals(
            "http://10.0.2.2:18080/",
            defaultWebDavSyncFolder("10.0.2.2", 18080, listOf("content://com.android.externalstorage.documents/tree/x", "dav://10.0.2.2:18080/Films/")),
        )
    }

    @Test
    fun httpsServerAndUnknownServerUseHttps() {
        assertEquals("https://nas.local/", defaultWebDavSyncFolder("nas.local", null, listOf("davs://nas.local/Movies/")))
        assertEquals("https://nas.local/", defaultWebDavSyncFolder("nas.local", null, emptyList()))
    }

    @Test
    fun onlyTheSameHostAndPortCount() {
        val linked = listOf("dav://nas.local:8080/", "dav://other.local/")
        assertEquals("https://nas.local/", defaultWebDavSyncFolder("nas.local", null, linked))
        assertEquals("https://nas.local:9090/", defaultWebDavSyncFolder("nas.local", 9090, linked))
        assertEquals("http://NAS.local:8080/", defaultWebDavSyncFolder("NAS.local", 8080, linked))
    }

    @Test
    fun otherKindsOnTheSameHostAreIgnored() {
        assertEquals("https://nas.local/", defaultWebDavSyncFolder("nas.local", null, listOf("smb://nas.local/Share/", "sftp://nas.local/")))
    }
}
