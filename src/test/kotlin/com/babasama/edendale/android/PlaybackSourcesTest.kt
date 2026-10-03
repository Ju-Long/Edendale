package com.babasama.edendale.android

import com.babasama.edendale.connectors.MediaSourceKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** D.5.T1, ported from Apple's PlaybackSourcesTests. */
class PlaybackSourcesTest {

    private data class Copy(val path: String, val source: CopySource?)

    private fun copy(path: String, name: String, kind: MediaSourceKind, unavailable: Boolean = false) =
        Copy(path, CopySource(name, kind, unavailable))

    @Test
    fun ordersThePagesCopyFirstThenLocalThenByName() {
        val page = copy("smb://nas/films/Heat.2160p.mkv", "NAS", MediaSourceKind.SMB)
        val zeta = copy("sftp://zeta/Heat.mkv", "Zeta", MediaSourceKind.SFTP)
        val alpha = copy("nfs://alpha/Heat.mkv", "alpha", MediaSourceKind.NFS)
        val local = copy("content://tree/Movies/Heat.mkv", "Movies", MediaSourceKind.LOCAL)

        val ordered = PlaybackSources.order(page, listOf(zeta, alpha, local), { it.source }, { it.path })
        assertEquals(listOf(page, local, alpha, zeta), ordered)
        // Without a page copy, the same rule picks the order.
        assertEquals(
            listOf(local, alpha, page, zeta),
            PlaybackSources.order(null, listOf(zeta, page, alpha, local), { it.source }, { it.path }),
        )
    }

    @Test
    fun namesCompareNaturallyAndPathsBreakTies() {
        val disk10 = copy("smb://b/Heat.mkv", "Disk 10", MediaSourceKind.SMB)
        val disk9 = copy("smb://a/Heat.mkv", "Disk 9", MediaSourceKind.SMB)
        val sameNameB = copy("smb://z/Heat.mkv", "Disk 9", MediaSourceKind.SMB)
        val ordered = PlaybackSources.order(null, listOf(disk10, sameNameB, disk9), { it.source }, { it.path })
        assertEquals(listOf(disk9, sameNameB, disk10), ordered)
    }

    @Test
    fun playSkipsUnavailableSources() {
        assertEquals("laptop", PlaybackSources.preferred(listOf("nas", "laptop")) { it == "nas" })
        assertEquals("nas", PlaybackSources.preferred(listOf("nas", "laptop")) { false })
        // Every source unreachable: still try the page's own.
        assertEquals("nas", PlaybackSources.preferred(listOf("nas", "laptop")) { true })
        assertNull(PlaybackSources.preferred(emptyList<String>()) { false })
    }

    private data class Ep(val path: String, val season: Int, val number: Int, val onPage: Boolean, val source: CopySource)

    @Test
    fun mergesAShowsEpisodesAcrossItsCopies() {
        val nas = CopySource("NAS", MediaSourceKind.SMB, false)
        val laptop = CopySource("Laptop", MediaSourceKind.LOCAL, false)
        val episodes = listOf(
            Ep("smb://nas/S1E1.mkv", 1, 1, true, nas),
            Ep("smb://nas/S1E2.mkv", 1, 2, true, nas),
            Ep("content://laptop/S1E2.mkv", 1, 2, false, laptop),
            Ep("content://laptop/S2E1.mkv", 2, 1, false, laptop),
            Ep("content://laptop/S1E3.mkv", 1, 3, false, laptop),
        )
        // The page's own show first, then the usual copy order.
        val rank = compareBy<Ep> { !it.onPage }.then(PlaybackSources.comparator({ it.source }, { it.path }))
        val slots = PlaybackSources.episodeSlots(episodes, { it.season }, { it.number }, rank)
        assertEquals(listOf("1-1", "1-2", "1-3", "2-1"), slots.map { it.id })
        // Episodes only the other copy has are on the page too.
        assertEquals(listOf("content://laptop/S1E3.mkv"), slots[2].copies.map { it.path })
        // A shared episode lists the page's copy first.
        assertEquals(listOf("smb://nas/S1E2.mkv", "content://laptop/S1E2.mkv"), slots[1].copies.map { it.path })
        assertEquals("smb://nas/S1E2.mkv", slots[1].primary.path)
    }

    @Test
    fun describesACopyByKindAndFileName() {
        assertEquals("SMB · Heat (1995) 2160p.mkv", PlaybackSources.detail("SMB", "smb://nas/films/Heat%20(1995)%202160p.mkv"))
        assertEquals("Heat (1995).mkv", PlaybackSources.fileName("/Users/me/Movies/Heat (1995).mkv"))
        // A SAF document URI encodes the document id's path.
        assertEquals(
            "Heat+Cut.mkv",
            PlaybackSources.fileName("content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2FHeat%2BCut.mkv"),
        )
        assertEquals("Local Folder · Heat.mkv", PlaybackSources.detail("Local Folder", "content://x/document/primary%3AHeat.mkv"))
    }
}
