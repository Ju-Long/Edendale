package com.babasama.edendale.android.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
}
