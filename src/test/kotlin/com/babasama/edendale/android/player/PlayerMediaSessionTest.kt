package com.babasama.edendale.android.player

import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules behind the MediaSession wrapper (C.6). Media3's types stay out. */
class PlayerMediaSessionTest {

    private fun entry(
        uri: String,
        title: String = uri,
        detail: String? = null,
        isEpisode: Boolean = true,
        tmdbId: Int? = 1,
        still: String? = null,
        backdrop: String? = null,
    ) = PlaylistEntry(
        uri = uri,
        title = title,
        detail = detail,
        tmdbId = tmdbId,
        isEpisode = isEpisode,
        showTmdbId = null,
        season = null,
        episode = null,
        stillPath = still,
        backdropPath = backdrop,
    )

    @Test
    fun neighborsFollowThePanelOrder() {
        val uris = listOf("a", "b", "c")
        assertEquals(PlaylistNeighbors(hasPrevious = false, hasNext = true), PlaylistNeighbors.of(uris, "a"))
        assertEquals(PlaylistNeighbors(hasPrevious = true, hasNext = true), PlaylistNeighbors.of(uris, "b"))
        assertEquals(PlaylistNeighbors(hasPrevious = true, hasNext = false), PlaylistNeighbors.of(uris, "c"))
        assertEquals(PlaylistNeighbors.NONE, PlaylistNeighbors.of(uris, "elsewhere"))
        assertEquals(PlaylistNeighbors.NONE, PlaylistNeighbors.of(listOf("a"), "a"))
        assertEquals(PlaylistNeighbors.NONE, PlaylistNeighbors.of(emptyList(), "a"))
    }

    @Test
    fun seekCommandsNeedASeekableItem() {
        val seekable = MediaSessionRules.addedCommands(isSeekable = true, neighbors = PlaylistNeighbors.NONE)
        assertEquals(setOf(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD), seekable)
        assertTrue(MediaSessionRules.addedCommands(isSeekable = false, neighbors = PlaylistNeighbors.NONE).isEmpty())
    }

    @Test
    fun nextAndPreviousNeedANeighbor() {
        val both = MediaSessionRules.addedCommands(isSeekable = false, neighbors = PlaylistNeighbors(true, true))
        assertEquals(
            setOf(
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            ),
            both,
        )
        val nextOnly = MediaSessionRules.addedCommands(isSeekable = false, neighbors = PlaylistNeighbors(false, true))
        assertEquals(setOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM), nextOnly)
        // The wrapped player's one-item playlist never decides next/previous item.
        assertTrue(Player.COMMAND_SEEK_TO_NEXT in MediaSessionRules.removedCommands)
        assertTrue(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM in MediaSessionRules.removedCommands)
        // Plain previous may still restart the item.
        assertTrue(Player.COMMAND_SEEK_TO_PREVIOUS !in MediaSessionRules.removedCommands)
    }

    @Test
    fun seeksUseTheAppControlsLengths() {
        val neighbors = PlaylistNeighbors.NONE
        assertEquals(
            MediaSessionRules.SeekAction.By(-15_000),
            MediaSessionRules.seekAction(Player.COMMAND_SEEK_BACK, 15_000, 30_000, neighbors),
        )
        assertEquals(
            MediaSessionRules.SeekAction.By(30_000),
            MediaSessionRules.seekAction(Player.COMMAND_SEEK_FORWARD, 15_000, 30_000, neighbors),
        )
    }

    @Test
    fun nextAndPreviousPlayTheNeighbor() {
        val both = PlaylistNeighbors(hasPrevious = true, hasNext = true)
        assertEquals(
            MediaSessionRules.SeekAction.Neighbor(+1),
            MediaSessionRules.seekAction(Player.COMMAND_SEEK_TO_NEXT, 10_000, 10_000, both),
        )
        assertEquals(
            MediaSessionRules.SeekAction.Neighbor(+1),
            MediaSessionRules.seekAction(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, 10_000, 10_000, both),
        )
        assertEquals(
            MediaSessionRules.SeekAction.Neighbor(-1),
            MediaSessionRules.seekAction(Player.COMMAND_SEEK_TO_PREVIOUS, 10_000, 10_000, both),
        )
        // Without an entry before, previous falls through to the player's restart.
        assertEquals(
            MediaSessionRules.SeekAction.Forward,
            MediaSessionRules.seekAction(Player.COMMAND_SEEK_TO_PREVIOUS, 10_000, 10_000, PlaylistNeighbors.NONE),
        )
        assertEquals(
            MediaSessionRules.SeekAction.Forward,
            MediaSessionRules.seekAction(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, 10_000, 10_000, both),
        )
    }

    @Test
    fun nowPlayingPrefersThePlaylistRow() {
        val info = NowPlayingInfo.of(
            entry = entry("ep2", title = "Half Loop", detail = "S01E02", still = "/still.jpg", backdrop = "/show.jpg"),
            title = "Severance",
            subtitle = null,
            showName = "Severance",
            isEpisode = true,
            tmdbId = 3000,
        )
        assertEquals(NowPlayingInfo("Half Loop", "S01E02", "Severance", "/still.jpg", NowPlayingInfo.Kind.EPISODE), info)

        val noStill = NowPlayingInfo.of(entry("ep3", backdrop = "/show.jpg"), "x", null, "Show", true, 1)
        assertEquals("/show.jpg", noStill.artworkPath)
    }

    @Test
    fun nowPlayingWithoutAPlaylistUsesTheLaunchTitle() {
        val movie = NowPlayingInfo.of(entry = null, title = "Alien", subtitle = "1979", showName = null, isEpisode = false, tmdbId = 348)
        assertEquals(NowPlayingInfo("Alien", "1979", null, null, NowPlayingInfo.Kind.MOVIE), movie)

        val openWith = NowPlayingInfo.of(entry = null, title = "clip.mp4", subtitle = null, showName = null, isEpisode = false, tmdbId = null)
        assertEquals(NowPlayingInfo.Kind.VIDEO, openWith.kind)
    }

    @Test
    fun aShowNameThatIsAlsoTheTitleIsNotRepeated() {
        // Continue Watching launches an episode under its show's name.
        val info = NowPlayingInfo.of(entry = null, title = "Severance", subtitle = "S01E02", showName = "Severance", isEpisode = true, tmdbId = 3000)
        assertNull(info.showName)
        // A movie in a folder playlist never borrows a show name.
        val movie = NowPlayingInfo.of(entry("m", isEpisode = false), "m", null, "Severance", false, 1)
        assertNull(movie.showName)
    }
}
