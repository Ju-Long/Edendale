package com.babasama.edendale.android.player

import com.babasama.edendale.android.CopySource
import com.babasama.edendale.android.data.LibraryEpisodeEntity
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.SourceEndpoint
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

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

    // MARK: - Panel lines and artwork (B.5)

    private fun entry(uri: String, season: Int? = null, tmdbId: Int? = null, isEpisode: Boolean = season != null) =
        PlaylistEntry(
            uri = uri,
            title = uri,
            detail = null,
            tmdbId = tmdbId,
            isEpisode = isEpisode,
            showTmdbId = null,
            season = season,
            episode = null,
        )

    @Test
    fun episodeListsGroupUnderSeasonHeadings() {
        val playlist = PlayerPlaylist(
            isEpisodeList = true,
            entries = listOf(entry("s0e1", 0), entry("s1e1", 1), entry("s1e2", 1), entry("s2e1", 2)),
        )
        val items = playlistItems(playlist)
        assertEquals(
            listOf<PlaylistItem>(
                PlaylistItem.Season(0), PlaylistItem.Entry(playlist.entries[0]),
                PlaylistItem.Season(1), PlaylistItem.Entry(playlist.entries[1]), PlaylistItem.Entry(playlist.entries[2]),
                PlaylistItem.Season(2), PlaylistItem.Entry(playlist.entries[3]),
            ),
            items,
        )
        // The panel opens on the playing file's line, past its heading.
        assertEquals(4, items.indexOfEntry("s1e2"))
        assertEquals(-1, items.indexOfEntry("elsewhere"))
    }

    @Test
    fun folderListsHaveNoHeadings() {
        val playlist = PlayerPlaylist(isEpisodeList = false, entries = listOf(entry("a.mkv"), entry("b.mkv")))
        assertEquals(playlist.entries.map { PlaylistItem.Entry(it) }, playlistItems(playlist))
        assertEquals(emptyList(), playlistItems(null))
    }

    @Test
    fun artworkForIdentifiedEpisodesAndTheCurrentIdentifiedFile() {
        val identified = entry("e1", season = 1, tmdbId = 7)
        val unknown = entry("e2", season = 1)
        assertTrue(playlistShowsArtwork(identified, isEpisodeList = true, isCurrent = false))
        assertFalse(playlistShowsArtwork(unknown, isEpisodeList = true, isCurrent = true))

        val movie = entry("movie.mkv", tmdbId = 603)
        assertTrue(playlistShowsArtwork(movie, isEpisodeList = false, isCurrent = true))
        // A sibling in the folder keeps its file-name row, known or not.
        assertFalse(playlistShowsArtwork(movie, isEpisodeList = false, isCurrent = false))
    }

    @Test
    fun artworkPrefersTheEpisodeStill() {
        assertEquals("/still.jpg", entry("e").copy(stillPath = "/still.jpg", backdropPath = "/show.jpg").artworkPath)
        assertEquals("/show.jpg", entry("e").copy(backdropPath = "/show.jpg").artworkPath)
        assertNull(entry("e").artworkPath)
    }

    // MARK: - The parental policy over the rows (O.5)

    private val allowedShow = MediaRef(100, MediaType.TV)
    private val allowedMovie = MediaRef(603, MediaType.MOVIE)
    private val allows: (MediaRef) -> Boolean = { it == allowedShow || it == allowedMovie }

    @Test
    fun rowsAreJudgedByTheirShowOrThemselves() {
        assertEquals(allowedShow, entry("e1", season = 1, tmdbId = 7).copy(showTmdbId = 100).audienceRef())
        assertEquals(allowedMovie, entry("m", tmdbId = 603).audienceRef())
        // An episode's own id is not a certified title, and a file without an id has none.
        assertNull(entry("e2", season = 1, tmdbId = 7).audienceRef())
        assertNull(entry("loose.mkv").audienceRef())
        val playlist = PlayerPlaylist(
            isEpisodeList = true,
            entries = listOf(entry("e1", 1, 7).copy(showTmdbId = 100), entry("e2", 1, 8).copy(showTmdbId = 100), entry("e3", 1)),
        )
        assertEquals(listOf(allowedShow), playlistAudienceRefs(playlist))
        assertEquals(emptyList(), playlistAudienceRefs(null))
    }

    @Test
    fun anInactivePolicyLeavesThePlaylistAlone() {
        val playlist = PlayerPlaylist(isEpisodeList = false, entries = listOf(entry("a.mkv"), entry("b.mkv")))
        assertSame(playlist, permittedPlaylist(playlist, policyActive = false) { false })
        assertNull(permittedPlaylist(null, policyActive = true, allows))
    }

    @Test
    fun anActivePolicyDropsRestrictedAndIdLessRowsAndEpisodesFollowTheirShow() {
        val ok1 = entry("s1e1", 1, 7).copy(showTmdbId = 100)
        val ok2 = entry("s1e2", 1, 8).copy(showTmdbId = 100)
        val otherShow = entry("s1e3", 1, 9).copy(showTmdbId = 200)
        val unknownShow = entry("s1e4", 1, 10)
        val episodes = PlayerPlaylist(isEpisodeList = true, showName = "Show", entries = listOf(ok1, otherShow, ok2, unknownShow))
        val permitted = permittedPlaylist(episodes, policyActive = true, allows)
        assertEquals(listOf(ok1, ok2), permitted?.entries)
        assertEquals("Show", permitted?.showName)
        assertTrue(permitted!!.isEpisodeList)

        // One surviving episode is still a list; a folder needs two siblings, as the loader does.
        assertEquals(listOf(ok1), permittedPlaylist(PlayerPlaylist(true, listOf(ok1, otherShow)), true, allows)?.entries)
        val movie = entry("movie.mkv", tmdbId = 603)
        val restricted = entry("other.mkv", tmdbId = 1)
        val looseEpisode = entry("loose.mkv", season = 2, tmdbId = 11)
        val folder = PlayerPlaylist(isEpisodeList = false, entries = listOf(movie, restricted, looseEpisode))
        assertNull(permittedPlaylist(folder, policyActive = true, allows))
        val folderWithShow = folder.copy(entries = folder.entries + looseEpisode.copy(uri = "known.mkv", showTmdbId = 100))
        assertEquals(listOf("movie.mkv", "known.mkv"), permittedPlaylist(folderWithShow, true, allows)?.entries?.map { it.uri })
        // Nothing allowed, no list.
        assertNull(permittedPlaylist(episodes, policyActive = true) { false })
    }

    // MARK: - Each episode's copies, for the row's Play From (27.1)

    private val lanRoot = "smb://longs-mac-studio.local/Public/"
    private val tailscaleRoot = "smb://100.64.0.2/Public/"

    private fun libraryEpisode(root: String, number: Int, title: String?, tmdbId: Int?) = LibraryEpisodeEntity(
        uri = "${root}Show.S01E0$number.mkv",
        folderUri = root,
        showKey = "show",
        fileName = "Show.S01E0$number.mkv",
        season = 1,
        episode = number,
        tmdbId = tmdbId,
        title = title,
        stillPath = null,
        runtimeMinutes = 50,
        addedAtEpochMillis = 0L,
    )

    private fun links(lanOffline: Boolean = false, tailscaleOffline: Boolean = false) = mapOf(
        lanRoot to CopySource("Public", MediaSourceKind.SMB, lanOffline, SourceEndpoint.Server("longs-mac-studio.local")),
        tailscaleRoot to CopySource("Public", MediaSourceKind.SMB, tailscaleOffline, SourceEndpoint.Server("100.64.0.2")),
    )

    private fun episodeList(
        playing: LibraryEpisodeEntity?,
        episodes: List<LibraryEpisodeEntity>,
        sources: Map<String, CopySource> = links(),
    ): List<PlaylistEntry> =
        playerEpisodeEntries(episodes, playing, showTmdbId = 7, backdropPath = "/show.jpg") { sources.getValue(it.folderUri!!) }

    @Test
    fun eachEpisodeCarriesItsCopiesAsEntriesOfTheirOwn() {
        val lan1 = libraryEpisode(lanRoot, 1, "Pilot", 101)
        // Enrichment hasn't reached the Tailscale copy yet.
        val tailscale1 = libraryEpisode(tailscaleRoot, 1, null, null)
        val tailscale2 = libraryEpisode(tailscaleRoot, 2, "Second", 102)
        val entries = episodeList(playing = tailscale1, episodes = listOf(lan1, tailscale1, tailscale2))

        val first = entries[0]
        assertEquals(tailscale1.uri, first.uri)
        // The copy enrichment hasn't reached plays as the matched one (27.1),
        // so its progress and resume are the episode's.
        assertEquals("Pilot", first.title)
        assertEquals(101, first.tmdbId)
        assertEquals(tailscaleRoot, first.sourceKey)
        assertEquals(listOf(lan1.uri, tailscale1.uri), first.copies.map { it.uri })
        assertEquals(listOf("Pilot", "Pilot"), first.copies.map { it.title })
        assertEquals(listOf(lanRoot, tailscaleRoot), first.copies.map { it.sourceKey })
        assertEquals(listOf(101, 101), first.copies.map { it.tmdbId })
        assertEquals(SourceEndpoint.Server("longs-mac-studio.local"), first.copies[0].source?.endpoint)
        // Each copy is a whole row: the show, the code, the artwork; alternates carry no list of their own.
        assertTrue(first.copies.all { it.showTmdbId == 7 && it.detail == "S01E01" && it.backdropPath == "/show.jpg" && it.copies.isEmpty() })
        // The row's own copy is among them, as the row itself.
        assertEquals(first.copy(copies = emptyList()), first.copies[1])
        // An episode only one source has offers no choice.
        assertEquals(tailscale2.uri, entries[1].uri)
        assertTrue(entries[1].copies.isEmpty())
    }

    @Test
    fun alternatesSurviveTheParentalFilterAndAPick() {
        val lan1 = libraryEpisode(lanRoot, 1, "Pilot", 101)
        val tailscale1 = libraryEpisode(tailscaleRoot, 1, "Pilot", 101)
        val lan2 = libraryEpisode(lanRoot, 2, "Second", 102)
        val playlist = PlayerPlaylist(isEpisodeList = true, entries = episodeList(lan1, listOf(lan1, tailscale1, lan2)))
        val permitted = permittedPlaylist(playlist, policyActive = true) { it == MediaRef(7, MediaType.TV) }
        assertEquals(playlist.entries.map { it.copies }, permitted?.entries?.map { it.copies })

        // Picking the Tailscale copy shows it as the row straight away, with the same choices.
        val pick = playlist.entries[0].copies.single { it.uri == tailscale1.uri }
        val picked = playlist.choosing(pick)
        assertEquals(listOf(tailscale1.uri, lan2.uri), picked.entries.map { it.uri })
        assertEquals(playlist.entries[0].copies, picked.entries[0].copies)
        assertEquals(1, playlistItems(picked).indexOfEntry(tailscale1.uri))
        // Picking the copy a row already plays changes nothing.
        assertEquals(playlist, playlist.choosing(playlist.entries[0]))
    }

    @Test
    fun theSourcePlayingNowNeverReadsUnavailable() {
        // An old scan flagged both links; the LAN copy of E01 plays anyway.
        val (lan1, lan2) = (1..2).map { libraryEpisode(lanRoot, it, "Episode $it", 100 + it) }
        val (tailscale1, tailscale2) = (1..2).map { libraryEpisode(tailscaleRoot, it, "Episode $it", 100 + it) }
        val flagged = links(lanOffline = true, tailscaleOffline = true)
        val entries = episodeList(lan1, listOf(lan1, lan2, tailscale1, tailscale2), flagged)

        // Every row stays on the LAN link, and its copies there answer, in the rows' own
        // source and in each row's Play From, the playing row's included.
        assertEquals(listOf(lan1.uri, lan2.uri), entries.map { it.uri })
        assertTrue(entries.all { it.source?.isUnavailable == false })
        val lanCopies = entries.flatMap { row -> row.copies.filter { it.sourceKey == lanRoot } }
        assertEquals(2, lanCopies.size)
        assertTrue(lanCopies.all { it.source?.isUnavailable == false })
        // The Tailscale link has proved nothing: its copies still read unavailable.
        assertTrue(entries.flatMap { row -> row.copies.filter { it.sourceKey == tailscaleRoot } }.all { it.source?.isUnavailable == true })

        // Nothing from the library playing: both flags stand.
        assertTrue(episodeList(null, listOf(lan1, tailscale1), flagged).single().copies.all { it.source?.isUnavailable == true })
    }
}
