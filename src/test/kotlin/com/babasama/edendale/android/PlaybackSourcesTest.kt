package com.babasama.edendale.android

import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.SourceEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    private data class Copied(val path: String, val folder: String, val season: Int, val number: Int)

    @Test
    fun thePlayersEpisodeListKeepsOneReachableCopyPerEpisode() {
        // Found on TV: a show imported from WebDAV and from an SFTP source that's offline.
        val sources = mapOf(
            "sftp://old/" to CopySource("Old SFTP", MediaSourceKind.SFTP, isUnavailable = true),
            "davs://nas/" to CopySource("NAS", MediaSourceKind.WEBDAV, isUnavailable = false),
            "content://tree/" to CopySource("Movies", MediaSourceKind.LOCAL, isUnavailable = false),
        )
        val sftp1 = Copied("sftp://old/S01E01.mkv", "sftp://old/", 1, 1)
        val sftp2 = Copied("sftp://old/S01E02.mkv", "sftp://old/", 1, 2)
        val sftp3 = Copied("sftp://old/S01E03.mkv", "sftp://old/", 1, 3)
        val dav1 = Copied("davs://nas/S01E01.mkv", "davs://nas/", 1, 1)
        val dav2 = Copied("davs://nas/S01E02.mkv", "davs://nas/", 1, 2)
        val local3 = Copied("content://tree/S01E03.mkv", "content://tree/", 1, 3)
        val all = listOf(sftp1, sftp2, sftp3, dav1, dav2, local3)
        fun list(playing: Copied?) = PlaybackSources.playerEpisodes(
            all, playing, { it.season }, { it.number }, { it.folder }, { sources.getValue(it.folder) }, { it.path },
        )

        // Playing the WebDAV copy: E02 stays on WebDAV; E03 isn't there, so the reachable local copy, never the offline SFTP one.
        assertEquals(listOf(dav1, dav2, local3), list(dav1))
        // Playing a copy on a source flagged offline: the file playing proves the source answers, so the
        // next episodes stay on it (27.1; this used to advance to WebDAV after the first episode).
        assertEquals(listOf(sftp1, sftp2, sftp3), list(sftp1))
        // Same source first even over a local copy, when that source is reachable.
        val dav3 = Copied("davs://nas/S01E03.mkv", "davs://nas/", 1, 3)
        assertEquals(
            listOf(dav1, dav2, dav3),
            PlaybackSources.playerEpisodes(all + dav3, dav1, { it.season }, { it.number }, { it.folder }, { sources.getValue(it.folder) }, { it.path }),
        )
        // Nothing playing from the library: Play From order with offline sources skipped.
        assertEquals(listOf(dav1, dav2, local3), list(null))
    }

    // MARK: - Endpoints (27.1)

    @Test
    fun endpointRanksPutThisDeviceThenTheLanThenOverlaysThenTheRest() {
        assertEquals(PlaybackSources.RANK_DEVICE, PlaybackSources.endpointRank("content://com.android.externalstorage.documents/tree/primary%3AMovies"))
        assertEquals(PlaybackSources.RANK_DEVICE, PlaybackSources.endpointRank("file:///storage/emulated/0/Movies/Heat.mkv"))
        listOf(
            "smb://Longs-Mac-Studio.local/Extra/",
            "smb://nas/films/",
            "smb://nas./films/",
            "smb://192.168.1.10/media/",
            "smb://10.0.0.5/media/",
            "smb://172.16.0.1/x/",
            "smb://172.31.255.254/x/",
            "smb://169.254.1.1/x/",
            "smb://127.0.0.1/x/",
            "smb://[fe80::1]/x/",
            "smb://[fd00::5]/x/",
            "smb://[fc12:3456::1]/x/",
            // Unique-local, but outside Tailscale's /48.
            "smb://[fd7a:115c:a1e1::1]/x/",
            "smb://[::1]/x/",
            "smb://[::ffff:192.168.1.5]/x/",
            "davs://nas.local:5006/",
            "sftp://me@10.1.2.3:2222/home/",
        ).forEach { assertEquals(PlaybackSources.RANK_LAN, PlaybackSources.endpointRank(it), it) }
        listOf(
            "smb://100.64.0.1/Extra/",
            "smb://100.101.102.103/Extra/",
            "smb://100.127.255.254/Extra/",
            "smb://longs-mac-studio.tail1234.ts.net/Extra/",
            "smb://[fd7a:115c:a1e0::1]/Extra/",
            "smb://[fd7a:115c:a1e0:ab12:4843:cd96:6258:b240]/Extra/",
        ).forEach { assertEquals(PlaybackSources.RANK_OVERLAY, PlaybackSources.endpointRank(it), it) }
        listOf(
            "davs://cloud.example.com/remote.php/dav/",
            "smb://100.63.255.255/x/",
            "smb://100.128.0.1/x/",
            "smb://172.32.0.1/x/",
            "smb://8.8.8.8/x/",
            "smb://[2001:db8::1]/x/",
            "smb://999.1.1.1/x/",
            // Account URLs carry a key, not a place on the network.
            "gdrive://9f86d081884c7d659a2feaa0c55ad015/chosen/",
            "s3://9f86d081884c7d659a2feaa0c55ad015/films/",
        ).forEach { assertEquals(PlaybackSources.RANK_OTHER, PlaybackSources.endpointRank(it), it) }
    }

    @Test
    fun theLanLinkOfAShareComesBeforeItsTailscaleLink() {
        val lan = Copy("smb://longs-mac-studio.local/Extra/Heat.mkv", smb("Extra", "smb://longs-mac-studio.local/Extra/"))
        val tailscale = Copy("smb://100.101.102.103/Extra/Heat.mkv", smb("Extra", "smb://100.101.102.103/Extra/"))
        val drive = Copy("gdrive://9f86d081884c7d659a2feaa0c55ad015/abc/Heat.mkv", CopySource("Videos you chose", MediaSourceKind.GOOGLE_DRIVE, false, SourceEndpoint.Account(MediaSourceKind.GOOGLE_DRIVE, "me@example.com")))
        val local = copy("content://tree/Movies/Heat.mkv", "Movies", MediaSourceKind.LOCAL)
        // By URL alone the Tailscale address would sort first ("1" before "l").
        assertEquals(listOf(local, lan, tailscale, drive), PlaybackSources.order(null, listOf(drive, tailscale, lan, local), { it.source }, { it.path }))
        // An S3 endpoint on the LAN ranks by its server, not by its account-key URL.
        val minio = Copy("s3://9f86d081884c7d659a2feaa0c55ad015/films/Heat.mkv", CopySource("films", MediaSourceKind.S3, false, SourceEndpoint.Server("minio.local", 9000)))
        assertEquals(listOf(minio, tailscale), PlaybackSources.order(null, listOf(tailscale, minio), { it.source }, { it.path }))
        // The same rank sorts by endpoint before the folder's name.
        val other = Copy("smb://alpha.local/Zeta/Heat.mkv", smb("Zeta", "smb://alpha.local/Zeta/"))
        assertEquals(listOf(other, lan), PlaybackSources.order(null, listOf(lan, other), { it.source }, { it.path }))
    }

    private fun smb(name: String, treeUri: String, unavailable: Boolean = false) =
        CopySource(name, MediaSourceKind.SMB, unavailable, SourceEndpoint.of(MediaSourceKind.SMB, treeUri, null))

    // MARK: - The same source for the next episode (27.1)

    private val lanRoot = "smb://longs-mac-studio.local/Public/"
    private val tailscaleRoot = "smb://100.64.0.2/Public/"

    private fun twoLinks(lanOffline: Boolean = false, tailscaleOffline: Boolean = false) = mapOf(
        lanRoot to smb("Public", lanRoot, lanOffline),
        tailscaleRoot to smb("Public", tailscaleRoot, tailscaleOffline),
    )

    private fun ep(root: String?, season: Int, number: Int) =
        Copied("${root ?: "content://picked/"}S${season}E$number.mkv", root.orEmpty(), season, number)

    private fun rows(
        episodes: List<Copied>,
        playing: Copied?,
        sources: Map<String, CopySource>,
        remembered: String? = null,
    ) = PlaybackSources.playerEpisodeRows(
        episodes, playing, { it.season }, { it.number },
        { PlaybackSources.sourceKey(it.folder.ifEmpty { null }) },
        { sources[it.folder] ?: CopySource("", MediaSourceKind.LOCAL, false) },
        { it.path },
        remembered,
    )

    private fun chosen(episodes: List<Copied>, playing: Copied?, sources: Map<String, CopySource>, remembered: String? = null) =
        rows(episodes, playing, sources, remembered).map { it.chosen }

    private val lan = (1..3).map { ep(lanRoot, 1, it) }
    private val tailscale = (1..3).map { ep(tailscaleRoot, 1, it) }

    @Test
    fun withBothLinksReachableTheLanLinkPlays() {
        assertEquals(lan, chosen(lan + tailscale, null, twoLinks()))
    }

    @Test
    fun aFlaggedLanLinkFallsBackToTailscaleUntilItPlays() {
        val sources = twoLinks(lanOffline = true)
        // Nothing playing: the flag stands.
        assertEquals(tailscale, chosen(lan + tailscale, null, sources))
        // Playing the LAN copy proves the LAN link answers, whatever its last scan said.
        assertEquals(lan, chosen(lan + tailscale, lan[0], sources))
    }

    @Test
    fun aLinkThatSortsSecondKeepsItsEpisodes() {
        assertEquals(tailscale, chosen(lan + tailscale, tailscale[0], twoLinks()))
        assertEquals(tailscale, chosen(lan + tailscale, tailscale[1], twoLinks()))
    }

    @Test
    fun aMissingEpisodeFallsBackThenReturns() {
        val lanWithoutE2 = listOf(lan[0], lan[2])
        assertEquals(listOf(lan[0], tailscale[1], lan[2]), chosen(lanWithoutE2 + tailscale, lan[0], twoLinks()))
    }

    @Test
    fun filesAddedOnTheirOwnAreOneSource() {
        val loose = (1..2).map { ep(null, 1, it) }
        assertEquals(PlaybackSources.LOOSE_FILES, PlaybackSources.sourceKey(null))
        assertEquals(loose, chosen(loose + tailscale.take(2), loose[0], twoLinks()))
    }

    @Test
    fun aRememberedSourceAnchorsTheListWhenNothingPlays() {
        assertEquals(tailscale, chosen(lan + tailscale, null, twoLinks(), remembered = tailscaleRoot))
        // A remembered source that's flagged isn't proven: the reachable link plays.
        assertEquals(lan, chosen(lan + tailscale, null, twoLinks(tailscaleOffline = true), remembered = tailscaleRoot))
        // The playing copy outranks the memory.
        assertEquals(lan, chosen(lan + tailscale, lan[0], twoLinks(), remembered = tailscaleRoot))
        // A source the show no longer has changes nothing.
        assertEquals(lan, chosen(lan + tailscale, null, twoLinks(), remembered = "smb://gone/Public/"))
    }

    @Test
    fun playerRowsCarryEveryCopyInPlayFromOrderWhicheverIsChosen() {
        val fromLan = rows(lan + tailscale, lan[0], twoLinks())
        val fromTailscale = rows(lan + tailscale, tailscale[0], twoLinks())
        assertEquals(listOf(lan[1], tailscale[1]), fromLan[1].copies)
        assertEquals(fromLan.map { it.copies }, fromTailscale.map { it.copies })
        assertEquals(tailscale[1], fromTailscale[1].chosen)
        assertEquals(listOf(lan[2]), rows(listOf(lan[2]), null, twoLinks()).single().copies)
    }

    @Test
    fun rememberedFirstAndTheStartingCopy() {
        val copies = listOf("a1", "b1", "a2", "c1")
        assertEquals(listOf("b1", "a1", "a2", "c1"), PlaybackSources.rememberedFirst(copies, { it.take(1) }, "b"))
        assertEquals(copies, PlaybackSources.rememberedFirst(copies, { it.take(1) }, null))
        assertEquals(copies, PlaybackSources.rememberedFirst(copies, { it.take(1) }, "z"))
        assertEquals("c1", PlaybackSources.startingCopy(copies, { it.take(1) }, "c") { false })
        // The remembered source's last scan failed: the first reachable copy instead.
        assertEquals("a1", PlaybackSources.startingCopy(copies, { it.take(1) }, "c") { it == "c1" })
    }

    @Test
    fun aStartThatFellBackFromTheRememberedSourceLeavesTheMemoryAlone() {
        val heat = listOf("lan", "tailscale")
        fun remembers(started: String, remembered: String?) = PlaybackSources.remembersSource(heat, started, { it }, remembered)
        // Nothing remembered yet, or the remembered source started: it counts.
        assertTrue(remembers("lan", null))
        assertTrue(remembers("tailscale", "tailscale"))
        // The remembered Tailscale link was flagged, so the card started the LAN one:
        // a stale flag mustn't move the title off the viewer's choice.
        assertFalse(remembers("lan", "tailscale"))
        // A remembered source with no copy of this title wasn't passed over.
        assertTrue(remembers("lan", "smb://gone/Public/"))
    }

    @Test
    fun theSourcePlayingNowAnswersInEveryRowsMenu() {
        val sources = twoLinks(lanOffline = true, tailscaleOffline = true)
        val sourceKey: (Copied) -> String = { PlaybackSources.sourceKey(it.folder.ifEmpty { null }) }
        val raw: (Copied) -> CopySource = { sources.getValue(it.folder) }
        val whileLanPlays = PlaybackSources.whilePlaying(lan[0], sourceKey, raw)
        assertFalse(whileLanPlays(lan[2]).isUnavailable)
        assertEquals(raw(lan[2]).copy(isUnavailable = false), whileLanPlays(lan[2]))
        // Another flagged source still reads unavailable.
        assertTrue(whileLanPlays(tailscale[2]).isUnavailable)
        // Nothing playing proves nothing.
        assertSame(raw, PlaybackSources.whilePlaying(null, sourceKey, raw))
    }

    @Test
    fun memoryKeysPreferTheShowsTmdbId() {
        assertEquals("show:95396", PlaybackSources.showMemoryKey(95396, "severance"))
        assertEquals("showKey:severance", PlaybackSources.showMemoryKey(null, "severance"))
        assertNull(PlaybackSources.showMemoryKey(null, null))
        assertEquals(listOf("show:95396", "showKey:severance"), PlaybackSources.showMemoryKeys(95396, "severance"))
        assertEquals(listOf("showKey:severance"), PlaybackSources.showMemoryKeys(null, "severance"))
        assertEquals("movie:949", PlaybackSources.movieMemoryKey(949))
        assertNull(PlaybackSources.movieMemoryKey(null))
        // Remembered before the show matched TMDB, found after.
        val sources = mapOf("showKey:severance" to lanRoot)
        assertEquals(lanRoot, PlaybackSources.rememberedShowSource(sources, 95396, listOf("severance")))
        assertEquals(tailscaleRoot, PlaybackSources.rememberedShowSource(sources + ("show:95396" to tailscaleRoot), 95396, listOf("severance")))
        assertNull(PlaybackSources.rememberedShowSource(sources, 95396, listOf("other")))
    }

    // MARK: - Menu text

    @Test
    fun menuRowsNameTheEndpointThenTheKindAndFileName() {
        val local = Copy("content://tree/Movies/Heat.mkv", CopySource("Movies", MediaSourceKind.LOCAL, false))
        val lanCopy = Copy("smb://longs-mac-studio.local/Extra/Heat.mkv", smb("Extra", "smb://longs-mac-studio.local/Extra/"))
        val tailscaleCopy = Copy("smb://100.101.102.103/Extra/Heat.mkv", smb("Extra", "smb://100.101.102.103/Extra/"))
        val drive = Copy(
            "gdrive://9f86d081884c7d659a2feaa0c55ad015/abc/Heat.mkv",
            CopySource("Videos you chose", MediaSourceKind.GOOGLE_DRIVE, false, SourceEndpoint.Account(MediaSourceKind.GOOGLE_DRIVE, "me@example.com")),
        )
        val rows = PlaybackSources.menuRows(
            listOf(local, lanCopy, tailscaleCopy, drive),
            { it.source!! },
            { it.path },
            { SourceEndpoint.label(it) },
            { kind -> if (kind == MediaSourceKind.SMB) "SMB" else "Local Folder" },
        )
        assertEquals(
            listOf(
                PlaybackSources.MenuRow("This device", "Local Folder · Heat.mkv"),
                PlaybackSources.MenuRow("longs-mac-studio.local", "SMB · Heat.mkv"),
                PlaybackSources.MenuRow("100.101.102.103", "SMB · Heat.mkv"),
                // The provider is on the first line already.
                PlaybackSources.MenuRow("Google Drive - me@example.com", "Heat.mkv"),
            ),
            rows,
        )
    }

    @Test
    fun rowsThatWouldReadTheSameAddTheirFolder() {
        val movies = Copy("content://tree/Movies/Heat.mkv", CopySource("Movies", MediaSourceKind.LOCAL, false))
        val backup = Copy("content://tree/Backup/Heat.mkv", CopySource("Backup", MediaSourceKind.LOCAL, false))
        val other = Copy("content://tree/Backup/Alien.mkv", CopySource("Backup", MediaSourceKind.LOCAL, false))
        val rows = PlaybackSources.menuRows(listOf(movies, backup, other), { it.source!! }, { it.path }, { SourceEndpoint.label(it) }, { "Local Folder" })
        assertEquals(
            listOf("Local Folder · Movies · Heat.mkv", "Local Folder · Backup · Heat.mkv", "Local Folder · Alien.mkv"),
            rows.map { it.detail },
        )
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
        assertEquals("Heat.mkv", PlaybackSources.detail(null, "gdrive://k/abc/Heat.mkv"))
        assertEquals("SMB · Extra · Heat.mkv", PlaybackSources.detail("SMB", "smb://nas/Extra/Heat.mkv", "Extra"))
    }
}
