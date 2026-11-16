package com.babasama.edendale.android.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which sources show as scanning: by `treeUri`, never by name. */
class LibraryActivityTest {

    private val lan = "smb://nas.local/Extra/"
    private val tailscale = "smb://100.101.102.103/Extra/"

    @Test
    fun `two sources with one name are told apart`() {
        val activity = LibraryActivity(scanningSources = mapOf(lan to "Extra"))
        assertTrue(lan in activity.scanningSources)
        assertFalse(tailscale in activity.scanningSources)
        assertTrue(activity.isBusy)
    }

    @Test
    fun `the Downloaded tab names the latest scan to begin`() {
        val both = LibraryActivity(scanningSources = mapOf(lan to "Extra", "content://tree/movies" to "Movies"))
        assertEquals("Movies", both.scanningFolder)
        assertEquals("Extra", both.copy(scanningSources = both.scanningSources - "content://tree/movies").scanningFolder)
        val idle = LibraryActivity()
        assertNull(idle.scanningFolder)
        assertFalse(idle.isBusy)
        assertTrue(idle.copy(isEnriching = true).isBusy)
    }
}
