package com.babasama.edendale.android.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `SmbClient.normalizeUrl` takes raw text from the add-source dialog, so it has
 * to cope with every shape a person might type. It caused a real bug once: a
 * pasted UNC path produced a null host and the import silently did nothing
 * (fixed 2026-07-20), which is why the accepted forms are pinned here.
 *
 * `SmbClient.hostOf` is deliberately not covered — it calls `android.net.Uri`,
 * which is an unimplemented stub in JVM unit tests. It needs an instrumented
 * test or Robolectric, neither of which this hermetic suite pulls in.
 */
class SmbUrlTest {

    @Test
    fun `a bare host becomes a share root`() {
        assertEquals("smb://192.168.1.10/", SmbClient.normalizeUrl("192.168.1.10"))
        assertEquals("smb://nas.local/", SmbClient.normalizeUrl("nas.local"))
    }

    @Test
    fun `host and share are kept together`() {
        assertEquals("smb://192.168.1.10/media/", SmbClient.normalizeUrl("192.168.1.10/media"))
        assertEquals("smb://nas/media/films/", SmbClient.normalizeUrl("nas/media/films"))
    }

    @Test
    fun `an already-prefixed url is not double-prefixed`() {
        assertEquals("smb://192.168.1.10/media/", SmbClient.normalizeUrl("smb://192.168.1.10/media"))
        assertEquals("smb://192.168.1.10/media/", SmbClient.normalizeUrl("smb://192.168.1.10/media/"))
    }

    @Test
    fun `a windows UNC path is converted`() {
        assertEquals("smb://192.168.1.10/media/", SmbClient.normalizeUrl("""\\192.168.1.10\media"""))
        assertEquals("smb://nas/media/films/", SmbClient.normalizeUrl("""\\nas\media\films"""))
    }

    @Test
    fun `surrounding whitespace and stray slashes are forgiven`() {
        assertEquals("smb://nas/media/", SmbClient.normalizeUrl("  //nas/media//  "))
        assertEquals("smb://nas/media/", SmbClient.normalizeUrl("nas/media/"))
    }

    @Test
    fun `an address with no host is rejected rather than guessed`() {
        assertNull(SmbClient.normalizeUrl(""))
        assertNull(SmbClient.normalizeUrl("   "))
        assertNull(SmbClient.normalizeUrl("///"))
        assertNull(SmbClient.normalizeUrl("smb://"))
        assertNull(SmbClient.normalizeUrl("""\\"""))
    }
}
