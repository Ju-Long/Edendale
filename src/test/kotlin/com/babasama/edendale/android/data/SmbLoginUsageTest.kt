package com.babasama.edendale.android.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** D.4.T1: how many linked sources use each saved login. */
class SmbLoginUsageTest {

    private val nas = SavedSmbLogin(host = "NAS.local", user = "me")
    private val office = SavedSmbLogin(host = "192.168.1.20", user = "admin")

    @Test
    fun countsSourcesPerHostIgnoringCase() {
        val usage = smbLoginUsage(
            logins = listOf(nas, office),
            sourceUris = listOf(
                "smb://nas.local/media/",
                "smb://NAS.LOCAL/music/",
                "smb://192.168.1.20/share/",
                "content://com.android.externalstorage.documents/tree/primary%3AMovies",
                "smb://other/media/",
            ),
        )
        assertEquals(2, usage[nas])
        assertEquals(1, usage[office])
    }

    @Test
    fun aLoginWithoutSourcesCountsZero() {
        assertEquals(mapOf(nas to 0), smbLoginUsage(listOf(nas), emptyList()))
    }

    @Test
    fun hostsAreReadWithoutAndroid() {
        assertEquals("nas", smbHostOf("smb://nas/media/Show/S01E01.mkv"))
        assertEquals("nas", smbHostOf("smb://nas:445/media/"))
        assertEquals("[fe80::1]", smbHostOf("smb://[fe80::1]/media/"))
        assertEquals("NAS", smbHostOf("SMB://NAS/"))
        assertNull(smbHostOf("content://x/y"))
        assertNull(smbHostOf("smb:///nohost"))
    }
}
