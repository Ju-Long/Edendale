package com.babasama.edendale.android.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.tmdbImageUrl
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

// ------------------------------------------------------------------
// Pure rules (JVM-tested)
// ------------------------------------------------------------------

/** Whether the playlist has an entry before or after the playing one. */
internal data class PlaylistNeighbors(val hasPrevious: Boolean, val hasNext: Boolean) {
    companion object {
        val NONE = PlaylistNeighbors(hasPrevious = false, hasNext = false)

        /**
         * The neighbors `PlayerActivity.switchToNeighbor` would play: the
         * entries beside [currentUri] in panel order. An item that isn't in
         * the list has none.
         */
        fun of(entryUris: List<String>, currentUri: String): PlaylistNeighbors {
            val index = entryUris.indexOf(currentUri)
            if (index < 0) return NONE
            return PlaylistNeighbors(hasPrevious = index > 0, hasNext = index < entryUris.lastIndex)
        }
    }
}

/**
 * What the system's media surfaces show for the playing item: the title, the
 * episode code (or a movie's year), the show, and TMDB artwork.
 */
internal data class NowPlayingInfo(
    val title: String,
    /** The player's own subtitle line: "S01E02" or a year. */
    val subtitle: String?,
    /** The show, for an episode whose show the library knows. */
    val showName: String?,
    /** A TMDB image path ("/abc.jpg"), not a URL. */
    val artworkPath: String?,
    val kind: Kind,
) {
    enum class Kind { EPISODE, MOVIE, VIDEO }

    companion object {
        /**
         * The playlist row for the playing item knows the most (its still or
         * the show's backdrop); without one, the launch title and subtitle
         * still name what's playing. A file TMDB never matched is a plain video.
         */
        fun of(
            entry: PlaylistEntry?,
            title: String,
            subtitle: String?,
            showName: String?,
            isEpisode: Boolean,
            tmdbId: Int?,
        ): NowPlayingInfo {
            val episode = entry?.isEpisode ?: isEpisode
            return NowPlayingInfo(
                title = entry?.title ?: title,
                subtitle = entry?.detail ?: subtitle,
                showName = showName?.takeIf { episode && it != (entry?.title ?: title) },
                artworkPath = entry?.stillPath ?: entry?.backdropPath,
                kind = when {
                    episode -> Kind.EPISODE
                    (entry?.tmdbId ?: tmdbId) != null -> Kind.MOVIE
                    else -> Kind.VIDEO
                },
            )
        }
    }
}

/** The session-facing commands, decided without Media3's Android-only types. */
internal object MediaSessionRules {

    /**
     * Player commands the session player offers beyond the wrapped player's.
     * Seeking back and forward needs a seekable item; next and previous need a
     * playlist neighbor, because ExoPlayer itself holds one item at a time.
     */
    fun addedCommands(isSeekable: Boolean, neighbors: PlaylistNeighbors): Set<Int> = buildSet {
        if (isSeekable) {
            add(Player.COMMAND_SEEK_BACK)
            add(Player.COMMAND_SEEK_FORWARD)
        }
        if (neighbors.hasNext) {
            add(Player.COMMAND_SEEK_TO_NEXT)
            add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (neighbors.hasPrevious) {
            add(Player.COMMAND_SEEK_TO_PREVIOUS)
            add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
    }

    /**
     * The wrapped player's own next/previous-item commands describe a
     * one-item playlist; the session's come from [addedCommands] instead.
     * Plain "previous" stays when the wrapped player offers it, since it can
     * still restart the item.
     */
    val removedCommands: Set<Int> = setOf(
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
    )

    /** What a session seek command does in Edendale's terms. */
    sealed interface SeekAction {
        data class By(val offsetMillis: Long) : SeekAction
        data class Neighbor(val offset: Int) : SeekAction
        /** Not Edendale's to change: the wrapped player handles it. */
        data object Forward : SeekAction
    }

    fun seekAction(
        command: Int,
        backMillis: Long,
        forwardMillis: Long,
        neighbors: PlaylistNeighbors,
    ): SeekAction = when (command) {
        Player.COMMAND_SEEK_BACK -> SeekAction.By(-backMillis)
        Player.COMMAND_SEEK_FORWARD -> SeekAction.By(forwardMillis)
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        -> if (neighbors.hasNext) SeekAction.Neighbor(+1) else SeekAction.Forward
        // Same as the media key on a focused window: previous plays the
        // entry before, and restarts the item only when there's none.
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        -> if (neighbors.hasPrevious) SeekAction.Neighbor(-1) else SeekAction.Forward
        else -> SeekAction.Forward
    }
}

// ------------------------------------------------------------------
// Media3 adapters
// ------------------------------------------------------------------

/**
 * The player the session controls (C.6.2). ExoPlayer holds one item at a time
 * — Edendale's playlist lives in [PlayerActivity] — so this wrapper:
 *  - reports the App Controls lengths as the seek increments, following live
 *    changes, and seeks by them (with the player's own HUD);
 *  - offers next and previous while the playlist has a neighbor, and plays it
 *    through the activity, so a newer request cancels a pending auto-advance;
 *  - reports Edendale's title, episode code or show, and TMDB artwork.
 */
@OptIn(UnstableApi::class)
internal class SessionPlayer(
    player: Player,
    private val onSeekBy: (Long) -> Unit,
    private val onNeighbor: (Int) -> Unit,
) : ForwardingSimpleBasePlayer(player) {

    private var backMillis = SkipInterval.DEFAULT.millis
    private var forwardMillis = SkipInterval.DEFAULT.millis
    private var neighbors = PlaylistNeighbors.NONE
    private var nowPlaying: NowPlayingInfo? = null

    fun update(backMillis: Long, forwardMillis: Long, neighbors: PlaylistNeighbors, nowPlaying: NowPlayingInfo) {
        if (backMillis == this.backMillis && forwardMillis == this.forwardMillis &&
            neighbors == this.neighbors && nowPlaying == this.nowPlaying
        ) {
            return
        }
        this.backMillis = backMillis
        this.forwardMillis = forwardMillis
        this.neighbors = neighbors
        this.nowPlaying = nowPlaying
        invalidateState()
    }

    override fun getState(): State {
        val base = super.getState()
        val isSeekable = base.availableCommands.contains(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        val commands = base.availableCommands.buildUpon()
            .removeAll(*MediaSessionRules.removedCommands.toIntArray())
            .addAll(*MediaSessionRules.addedCommands(isSeekable, neighbors).toIntArray())
            .build()
        val builder = base.buildUpon()
            .setAvailableCommands(commands)
            .setSeekBackIncrementMs(backMillis)
            .setSeekForwardIncrementMs(forwardMillis)
        val info = nowPlaying
        if (info != null && !base.timeline.isEmpty) {
            builder.setPlaylist(base.timeline, base.currentTracks, info.toMediaMetadata(base.currentMetadata))
        }
        return builder.build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        return when (val action = MediaSessionRules.seekAction(seekCommand, backMillis, forwardMillis, neighbors)) {
            is MediaSessionRules.SeekAction.By -> {
                onSeekBy(action.offsetMillis)
                Futures.immediateVoidFuture()
            }
            is MediaSessionRules.SeekAction.Neighbor -> {
                onNeighbor(action.offset)
                Futures.immediateVoidFuture()
            }
            MediaSessionRules.SeekAction.Forward -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
    }
}

/**
 * The [MediaSession] [PlayerActivity] owns for its lifetime (C.6.1, D13). No
 * `MediaSessionService`: playback still pauses in `onStop`. Headset buttons,
 * Bluetooth controls, Assistant, and Android TV's system media UI reach the
 * player through it, including while the video floats in PiP. Media keys that
 * reach the focused player window are consumed by
 * `PlayerActivity.dispatchKeyEvent`, so the platform never also routes them
 * to the session.
 */
@OptIn(UnstableApi::class)
internal class PlayerMediaSession(
    context: Context,
    player: Player,
    onSeekBy: (Long) -> Unit,
    onNeighbor: (Int) -> Unit,
) {
    private val sessionPlayer = SessionPlayer(player, onSeekBy, onNeighbor)

    private val session: MediaSession = MediaSession.Builder(context, sessionPlayer)
        // Ids must be unique while two sessions exist: "Open with" can start a
        // second player task while another floats in PiP.
        .setId("edendale.player.${nextSessionNumber++}")
        .build()

    fun update(backMillis: Long, forwardMillis: Long, neighbors: PlaylistNeighbors, nowPlaying: NowPlayingInfo) {
        sessionPlayer.update(backMillis, forwardMillis, neighbors, nowPlaying)
    }

    /** Releases the session, then the wrapper — which releases the wrapped player too. */
    fun release() {
        session.release()
        sessionPlayer.release()
    }

    private companion object {
        var nextSessionNumber = 0
    }
}

private fun NowPlayingInfo.toMediaMetadata(base: MediaMetadata?): MediaMetadata =
    (base ?: MediaMetadata.EMPTY).buildUpon()
        .setTitle(title)
        .setDisplayTitle(title)
        .setSubtitle(subtitle)
        .setArtist(showName ?: subtitle)
        .setAlbumTitle(showName)
        .setArtworkUri(tmdbImageUrl(artworkPath, TmdbImageSize.BACKDROP)?.let(Uri::parse))
        .setMediaType(
            when (kind) {
                // Media3's "TV show" is one programme, an episode; a series is TV_SERIES.
                NowPlayingInfo.Kind.EPISODE -> MediaMetadata.MEDIA_TYPE_TV_SHOW
                NowPlayingInfo.Kind.MOVIE -> MediaMetadata.MEDIA_TYPE_MOVIE
                NowPlayingInfo.Kind.VIDEO -> MediaMetadata.MEDIA_TYPE_VIDEO
            },
        )
        .build()
