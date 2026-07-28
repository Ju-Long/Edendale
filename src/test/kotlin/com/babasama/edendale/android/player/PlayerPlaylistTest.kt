package com.babasama.edendale.android.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The playlist panel's pure URI grouping. Runs on a bare JVM, so only the
 * scheme branches that never touch the Android framework are covered here —
 * SAF document-id parsing needs `DocumentsContract` and is exercised on
 * device instead.
 */
class PlayerPlaylistTest {

    @Test
    fun smbParentIsTheContainingDirectory() {
        assertEquals(
            "smb://nas/media/Show/Season 1",
            playlistParentKey("smb://nas/media/Show/Season 1/e01.mkv"),
        )
        // Trailing slashes don't change the answer.
        assertEquals(
            "smb://nas/media/Show/Season 1",
            playlistParentKey("smb://nas/media/Show/Season 1/e01.mkv/"),
        )
    }

    @Test
    fun smbShareRootHasNoParent() {
        // A path directly under the scheme yields the bare authority, and a
        // degenerate URL yields nothing rather than a made-up parent.
        assertEquals("smb://", playlistParentKey("smb:///movie.mkv"))
        assertNull(playlistParentKey("smb://"))
    }

    @Test
    fun unknownSchemesFallBackToSourceGrouping() {
        // file:// and other schemes return null, which the loader treats as
        // "group by the imported source root".
        assertNull(playlistParentKey("file:///storage/emulated/0/Movies/movie.mkv"))
        assertNull(playlistParentKey("/plain/path/movie.mkv"))
    }
}
