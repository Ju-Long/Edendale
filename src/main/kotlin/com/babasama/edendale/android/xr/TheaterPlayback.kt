package com.babasama.edendale.android.xr

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.babasama.edendale.AndroidEdendaleCore
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.android.MainActivity
import com.babasama.edendale.android.PlaybackSources
import com.babasama.edendale.android.R
import com.babasama.edendale.android.connectorFailureMessage
import com.babasama.edendale.android.data.LocalDataStore
import com.babasama.edendale.android.player.ContentPlayerPreferencesRules
import com.babasama.edendale.android.player.EdendaleDataSource
import com.babasama.edendale.android.player.EdendaleRenderersFactory
import com.babasama.edendale.android.player.EpisodeCandidate
import com.babasama.edendale.android.player.EpisodeProgression
import com.babasama.edendale.android.player.EqAudioProcessor
import com.babasama.edendale.android.player.PlaybackFailure
import com.babasama.edendale.android.player.PlayerActivity
import com.babasama.edendale.android.player.PlayerChromeState
import com.babasama.edendale.android.player.PlayerLogic
import com.babasama.edendale.android.player.PlayerPlaylist
import com.babasama.edendale.android.player.PlayerTrackOption
import com.babasama.edendale.android.player.PlaylistEntry
import com.babasama.edendale.android.player.SharedPreferencesContentStore
import com.babasama.edendale.android.player.TrackLabels
import com.babasama.edendale.android.player.applyContentPreferences
import com.babasama.edendale.android.player.audioTrackOptions
import com.babasama.edendale.android.player.loadPlayerPlaylist
import com.babasama.edendale.android.player.seekBy
import com.babasama.edendale.android.player.selectAudioTrack
import com.babasama.edendale.android.player.selectTextTrack
import com.babasama.edendale.android.player.snapshotPreferences
import com.babasama.edendale.android.player.textTrackOptions
import com.babasama.edendale.android.remoteFailureMessage
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.introdb.IntroDbMedia
import com.babasama.edendale.introdb.PlaybackSegment
import com.babasama.edendale.introdb.PlayerSegmentController
import com.babasama.edendale.introdb.SkipAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** One audio or subtitle choice, labelled for the dock (the player's own labels). */
data class TheaterTrack(val id: String, val label: String, val isSelected: Boolean)

/** The next episode's card: its code ("S01E02"), title, and 16:9 artwork path (TMDB path, may be null). */
data class TheaterUpNext(val code: String?, val title: String, val artworkPath: String?)

/**
 * The immersive theater's playback, everything the Quest theater's activity
 * did apart from the 3D scene (ENHANCEMENT.md N.6, N-D7): it owns the
 * ExoPlayer, built as `PlayerActivity` builds its own; applies the per-title
 * memory and then the handover's choices, so what the viewer was hearing and
 * reading wins; writes progress every 5 s while playing and on pause, stop,
 * end, and exit; drives the TheIntroDB prompt and the Up Next card; and
 * remembers the viewer's audio and subtitle choices for the title.
 *
 * The theater activity lives in the `:xr` feature module, where the player's
 * `internal` pieces are invisible, so this one public facade stands in for
 * them; every base symbol keeps its visibility. Read and written on the main
 * thread; the observable fields are Compose snapshot state for the dock.
 */
class TheaterPlayback(context: Context, val handover: XrTheaterHandover) {

    private val appContext: Context = context.applicationContext

    /** Audio Enhancement's equalizer and booster, in the audio sink as in the 2D player (E.1). */
    private val equalizer = EqAudioProcessor()

    /** The player's chrome state: the viewer's skip lengths, speed, subtitle appearance, and segment prompts. */
    internal val chrome = PlayerChromeState(appContext.getSharedPreferences(PLAYER_PREFS, Context.MODE_PRIVATE))

    private val contentStore = SharedPreferencesContentStore.from(appContext)
    private val application = appContext as? EdendaleApplication
    private val dataStore: LocalDataStore? = application?.let { LocalDataStore(it.database) }

    /** Survives the activity long enough to land the final write. */
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Built exactly as `PlayerActivity` builds its own; no effects pipeline, no loop. */
    val player: ExoPlayer = buildPlayer()

    val title: String = handover.title

    /** The handover's episode code or year; backfilled from the library row when it carried none, as the 2D player does. */
    var subtitle: String? by mutableStateOf(handover.subtitle)
        private set

    var isPlaying: Boolean by mutableStateOf(false)
        private set
    var isBuffering: Boolean by mutableStateOf(false)
        private set
    var positionMillis: Long by mutableLongStateOf(0L)
        private set
    var durationMillis: Long by mutableLongStateOf(0L)
        private set

    /** width × pixelWidthHeightRatio / height; null until known. */
    var videoAspect: Float? by mutableStateOf(null)
        private set

    var audioTracks: List<TheaterTrack> by mutableStateOf(emptyList())
        private set
    var textTracks: List<TheaterTrack> by mutableStateOf(emptyList())
        private set
    var subtitlesOff: Boolean by mutableStateOf(handover.subtitlesOff)
        private set

    /** The TheIntroDB prompt to show: null unless prompts are on, the item is seekable, and the position is inside a segment. */
    var activeSegment: PlaybackSegment? by mutableStateOf(null)
        private set

    /** The next episode, in the last 30 s of an episode with a successor in the show. */
    var upcomingEpisode: TheaterUpNext? by mutableStateOf(null)
        private set

    /** The failure card's message, in the 2D player's words. */
    var failure: String? by mutableStateOf(null)
        private set

    /** STATE_ENDED (or a credits skip that reaches the end) was reached; the completed progress is already written. */
    var ended: Boolean by mutableStateOf(false)
        private set

    val skipBackwardSeconds: Int get() = chrome.skipBackwardInterval.seconds
    val skipForwardSeconds: Int get() = chrome.skipForwardInterval.seconds

    private val segmentController = PlayerSegmentController(
        preferences = chrome.preferences,
        onActiveSegmentChanged = { activeSegment = it },
        lookup = { request -> AndroidEdendaleCore.introDbService().segments(request) },
    )

    private var prefsSubscription: AutoCloseable? = chrome.preferences.addChangeListener {
        // Settings' booster applies live, from the next buffer.
        equalizer.update(chrome.preferences.audioEnhancement)
    }

    private val hold = TheaterPlaybackRules.SystemHold()
    private var started = false
    private var released = false
    private var restoredPreferences = false
    private var handoverApplied = false
    private var restoring = false
    private var completedWritten = false
    private var ticksSinceWrite = 0
    private var playlist: PlayerPlaylist? = null
    private var upcomingEntry: PlaylistEntry? = null
    private var audioOptions: List<PlayerTrackOption> = emptyList()
    private var textOptions: List<PlayerTrackOption> = emptyList()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            isBuffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_ENDED) handleEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            this@TheaterPlayback.isPlaying = isPlaying
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback failed: ${error.errorCodeName}")
            failure = failureText(error)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            videoAspect = TheaterPlaybackRules.aspect(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
        }

        // A new item first reports empty tracks, before it's prepared.
        // Restoring against those would match nothing and use up the item's
        // only restore, so wait for the report that lists them. The lists
        // are refreshed before the handover's choices so those can tick
        // their rows at once; the player's next report confirms them.
        override fun onTracksChanged(tracks: Tracks) {
            if (tracks.isEmpty) return
            if (!restoredPreferences) {
                restoredPreferences = true
                restoreContentPreferences(tracks)
            }
            refreshTracks(tracks)
            if (!handoverApplied) {
                handoverApplied = true
                applyHandoverChoices(tracks)
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            onTick()
            mainHandler.postDelayed(this, TheaterPlaybackRules.TICK_MILLIS)
        }
    }

    init {
        player.addListener(listener)
    }

    // ------------------------------------------------------------------
    // The player
    // ------------------------------------------------------------------

    private fun buildPlayer(): ExoPlayer {
        equalizer.update(chrome.preferences.audioEnhancement)
        // One factory, resolved per request: DefaultDataSource routes any
        // scheme it doesn't recognise (smb and the storage providers', H.2)
        // to the base source.
        val mediaSourceFactory = DefaultMediaSourceFactory(
            DataSource.Factory { DefaultDataSource(appContext, EdendaleDataSource(appContext)) },
        )
        return ExoPlayer.Builder(appContext, EdendaleRenderersFactory(appContext, equalizer))
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // Focus handling accepts only USAGE_MEDIA/GAME; MOVIE is
                    // a content type, not a usage.
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            // Pause-free playback through unplugged headphones is never what
            // the viewer wants; media3 registers the receiver itself.
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                // No loop in the theater: the natural end returns to the library (N-D7).
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    /**
     * Loads the handover's item at its position and starts the 500 ms ticker.
     * The handover's speed applies before the first frame; the per-title
     * memory and the track choices follow once the tracks are known.
     */
    fun start() {
        if (started || released) return
        started = true
        segmentController.begin(itemId = handover.uri, media = TheaterPlaybackRules.introDbMedia(handover))
        chrome.setRate(player, handover.speed)
        player.setMediaItem(MediaItem.fromUri(handover.uri), handover.positionMillis)
        player.prepare()
        player.playWhenReady = handover.playWhenReady
        loadPlaylist()
        mainHandler.post(tick)
    }

    private fun onTick() {
        val duration = knownDuration()
        val position = player.currentPosition.coerceAtLeast(0L)
        positionMillis = position
        durationMillis = duration ?: 0L
        isPlaying = player.isPlaying
        isBuffering = player.playbackState == Player.STATE_BUFFERING
        segmentController.update(
            positionMillis = position,
            durationMillis = duration,
            isSeekable = player.isCurrentMediaItemSeekable,
        )
        activeSegment = segmentController.activeSegment
        updateUpcomingEpisode(position, duration ?: 0L)
        if (player.isPlaying && ++ticksSinceWrite >= TheaterPlaybackRules.WRITE_EVERY_TICKS) {
            ticksSinceWrite = 0
            writeProgress()
        }
    }

    private fun knownDuration(): Long? = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    /** Skips by the viewer's App Controls interval, as the chip does. */
    fun skip(forward: Boolean) {
        val offset = if (forward) chrome.skipForwardInterval.millis else -chrome.skipBackwardInterval.millis
        seekBy(player, chrome, offset)
        positionMillis = player.currentPosition.coerceAtLeast(0L)
    }

    /** Seeks to a fraction of the duration; nothing happens until the duration is known. */
    fun seekToFraction(fraction: Float) {
        val duration = knownDuration() ?: return
        player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        positionMillis = player.currentPosition.coerceAtLeast(0L)
    }

    /** Headset off or the activity paused: pauses, and remembers to resume only a player that was playing. */
    fun pauseForSystem() {
        if (hold.hold(wantsToPlay = player.playWhenReady)) {
            player.pause()
            writeProgress()
        }
    }

    /** The headset is back on or the activity resumed: plays again only if [pauseForSystem] paused it. */
    fun resumeAfterSystem() {
        if (hold.release()) player.play()
    }

    // ------------------------------------------------------------------
    // Tracks
    // ------------------------------------------------------------------

    /** Plays the audio track with this id (a [TheaterTrack.id]) and remembers it for the title. */
    fun selectAudio(id: String) {
        val option = audioOptions.firstOrNull { trackId(it) == id } ?: return
        chooseAudio(option)
        saveContentPreferences()
    }

    /** Shows the subtitle track with this id, or none for null, and remembers it for the title. */
    fun selectSubtitles(id: String?) {
        val option = if (id == null) null else textOptions.firstOrNull { trackId(it) == id } ?: return
        chooseSubtitles(option)
        saveContentPreferences()
    }

    /** Applies an audio choice and ticks its row until the player's next tracks report confirms it. */
    private fun chooseAudio(option: PlayerTrackOption) {
        selectAudioTrack(player, option)
        audioTracks = TheaterPlaybackRules.withSelection(audioTracks, trackId(option))
    }

    /** Applies a subtitle choice (none for null) and ticks its row until the player confirms it. */
    private fun chooseSubtitles(option: PlayerTrackOption?) {
        selectTextTrack(player, option)
        textTracks = TheaterPlaybackRules.withSelection(textTracks, option?.let(::trackId))
        subtitlesOff = option == null
    }

    private fun restoreContentPreferences(tracks: Tracks) {
        val key = contentKey() ?: return
        val preferences = contentStore.get(key) ?: return
        restoring = true
        try {
            applyContentPreferences(preferences = preferences, player = player, chrome = chrome, tracks = tracks)
        } finally {
            restoring = false
        }
    }

    /**
     * The handover's choices, applied after the stored ones so what the
     * viewer was hearing and reading wins. Nothing is saved here: the 2D
     * player saved these same choices as it handed over.
     */
    private fun applyHandoverChoices(tracks: Tracks) {
        TheaterPlaybackRules.matching(
            options = audioTrackOptions(tracks),
            id = handover.audioTrackId,
            language = handover.audioLanguage,
            idOf = { it.id },
            languageOf = { it.language },
        )?.let(::chooseAudio)
        if (handover.subtitlesOff) {
            chooseSubtitles(null)
        } else {
            TheaterPlaybackRules.matching(
                options = textTrackOptions(tracks),
                id = handover.textTrackId,
                language = handover.textLanguage,
                idOf = { it.id },
                languageOf = { it.language },
            )?.let(::chooseSubtitles)
        }
        chrome.setRate(player, handover.speed)
    }

    private fun refreshTracks(tracks: Tracks) {
        val locale = appContext.resources.configuration.locales[0] ?: Locale.getDefault()
        audioOptions = audioTrackOptions(tracks)
        textOptions = textTrackOptions(tracks)
        audioTracks = audioOptions.mapIndexed { index, option ->
            TheaterTrack(id = trackId(option), label = audioLabel(option, index, locale), isSelected = option.isSelected)
        }
        textTracks = textOptions.mapIndexed { index, option ->
            TheaterTrack(id = trackId(option), label = textLabel(option, index, locale), isSelected = option.isSelected)
        }
        subtitlesOff = textOptions.none { it.isSelected }
    }

    private fun trackId(option: PlayerTrackOption): String = option.id ?: "${option.group.id}/${option.trackIndex}"

    /** The Audio Track panel's row label (B.3). */
    private fun audioLabel(option: PlayerTrackOption, index: Int, locale: Locale): String = TrackLabels.audio(
        label = option.label,
        languageCode = option.language,
        languageName = TrackLabels.languageName(option.language, locale),
        channels = TrackLabels.channels(
            count = option.channelCount,
            mono = appContext.getString(R.string.player_audio_mono),
            stereo = appContext.getString(R.string.player_audio_stereo),
        ) { appContext.getString(R.string.player_audio_channels, it) },
        fallback = appContext.getString(R.string.player_track_number, index + 1),
    )

    /** The Subtitles panel's row label: the track's name, else its language, else "Track N". */
    private fun textLabel(option: PlayerTrackOption, index: Int, locale: Locale): String =
        option.label?.takeIf { it.isNotBlank() }
            ?: TrackLabels.languageName(option.language, locale)
            ?: appContext.getString(R.string.player_track_number, index + 1)

    // ------------------------------------------------------------------
    // Per-title memory
    // ------------------------------------------------------------------

    private fun contentKey(): String? = ContentPlayerPreferencesRules.contentKey(
        tmdbId = handover.tmdbId,
        isEpisode = handover.isEpisode,
        showTmdbId = handover.showTmdbId,
    )

    /** Remembers the speed and the audio and subtitle choices for the title (or the whole show). */
    fun saveContentPreferences() {
        if (restoring || released) return
        val key = contentKey() ?: return
        val preferences = snapshotPreferences(
            baseRate = chrome.baseRate,
            tracks = player.currentTracks,
            trackSelectionParameters = player.trackSelectionParameters,
            existing = contentStore.get(key),
        )
        contentStore.save(key, preferences)
    }

    // ------------------------------------------------------------------
    // Progress
    // ------------------------------------------------------------------

    /**
     * `PlayerActivity.writeProgress`'s rule under the handover's identity:
     * nothing without a TMDB id or a known duration, completion at 95 % or
     * when [completed], and once completed never un-completed.
     */
    fun writeProgress(completed: Boolean = false) {
        if (released) return
        val store = dataStore ?: return
        val record = TheaterPlaybackRules.progress(
            handover = handover,
            positionMillis = player.currentPosition,
            durationMillis = knownDuration() ?: return,
            completed = completed,
            completedWritten = completedWritten,
            nowMillis = System.currentTimeMillis(),
        ) ?: return
        if (record.isCompleted) completedWritten = true
        writeScope.launch { store.updateWatchProgress(record) }
    }

    private fun handleEnded() {
        if (ended) return
        writeProgress(completed = true)
        ended = true
    }

    // ------------------------------------------------------------------
    // Segment prompts
    // ------------------------------------------------------------------

    /** Acts on the shown prompt: seeks past the segment, or ends the title when the segment reaches its end. */
    fun skipSegment() {
        val action = segmentController.consumeSkip(
            positionMillis = player.currentPosition,
            durationMillis = knownDuration(),
            isSeekable = player.isCurrentMediaItemSeekable,
        ) ?: return
        when (action) {
            is SkipAction.Seek -> {
                player.seekTo(action.positionMillis)
                positionMillis = player.currentPosition.coerceAtLeast(0L)
            }
            SkipAction.Finish -> handleEnded()
        }
        activeSegment = segmentController.activeSegment
    }

    // ------------------------------------------------------------------
    // Up Next
    // ------------------------------------------------------------------

    private fun loadPlaylist() {
        val app = application ?: return
        writeScope.launch {
            val loaded = runCatching {
                loadPlayerPlaylist(
                    dao = app.database.libraryDao(),
                    uriString = handover.uri,
                    showTmdbIdExtra = handover.showTmdbId,
                    // As the 2D player loads it: the show's remembered source anchors a file outside the library (27.1).
                    remembered = app.libraryRepository.sourceMemory.sourceFor(PlaybackSources.showMemoryKey(handover.showTmdbId, null)),
                )
            }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (released) return@withContext
                playlist = loaded
                // The handover carries the player's subtitle line; a player
                // that hadn't loaded its playlist yet sent none.
                if (subtitle == null) {
                    loaded.entries.firstOrNull { it.uri == handover.uri }?.let { subtitle = it.detail }
                }
            }
        }
    }

    private fun updateUpcomingEpisode(positionMillis: Long, durationMillis: Long) {
        val entry = upcomingEntry(positionMillis, durationMillis)
        if (entry == upcomingEntry) return
        upcomingEntry = entry
        upcomingEpisode = entry?.let {
            TheaterUpNext(
                code = TheaterPlaybackRules.upNextCode(it.detail, it.season, it.episode),
                title = it.title,
                artworkPath = it.artworkPath,
            )
        }
    }

    /** `PlayerActivity.updateUpcomingEpisode`'s rule with Loop off. */
    private fun upcomingEntry(positionMillis: Long, durationMillis: Long): PlaylistEntry? {
        if (!handover.isEpisode || durationMillis <= 0L) return null
        val entries = playlist?.takeIf { it.isEpisodeList }?.entries ?: return null
        val current = entries.firstOrNull { it.uri == handover.uri } ?: return null
        val currentSeason = current.season ?: return null
        val currentEpisode = current.episode ?: return null
        val candidates = entries.mapNotNull { entry ->
            val season = entry.season ?: return@mapNotNull null
            val episode = entry.episode ?: return@mapNotNull null
            EpisodeCandidate(id = entry.uri, season = season, episode = episode, title = entry.title)
        }
        val next = EpisodeProgression.upcomingEpisode(
            timeMillis = positionMillis,
            durationMillis = durationMillis,
            loopEnabled = false,
            current = EpisodeCandidate(id = current.uri, season = currentSeason, episode = currentEpisode, title = current.title),
            episodes = candidates,
        ) ?: return null
        return entries.firstOrNull { it.uri == next.id }
    }

    // ------------------------------------------------------------------
    // Lifecycle and the ways out
    // ------------------------------------------------------------------

    /** The activity's onStop: remember the choices, write progress, pause, and flush 27.1 sync (SYNC-PROTOCOL.md §8.1). */
    fun onStop() {
        saveContentPreferences()
        writeProgress()
        pauseForSystem()
        application?.sync?.flushSoon()
    }

    /** Stops the ticker and the prompts, detaches the surface, and releases the player. Writes nothing: call [onStop] first. */
    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacks(tick)
        prefsSubscription?.close()
        prefsSubscription = null
        segmentController.end()
        player.removeListener(listener)
        player.setVideoSurface(null)
        player.release()
    }

    /**
     * Exit: back to the 2D player at the current position (N-D7). The 2D
     * player already had its chance to remember this item's source (27.1), so
     * coming back doesn't record it again.
     */
    fun returnToPlayerIntent(): Intent = PlayerActivity.intent(
        context = appContext,
        uri = handover.uri,
        title = handover.title,
        tmdbId = handover.tmdbId,
        isEpisode = handover.isEpisode,
        showTmdbId = handover.showTmdbId,
        season = handover.season,
        episode = handover.episode,
        positionMillis = player.currentPosition.coerceAtLeast(0L),
        rememberSource = false,
    )

    /**
     * The Up Next card: the 2D player on the next episode, or null while there
     * is none. It follows the list, as the 2D player's Up Next does, so it
     * leaves the show's remembered source alone (27.1).
     */
    fun upNextIntent(): Intent? = upcomingEntry?.let { entry ->
        PlayerActivity.intent(
            context = appContext,
            uri = entry.uri,
            title = entry.title,
            tmdbId = entry.tmdbId,
            isEpisode = entry.isEpisode,
            showTmdbId = entry.showTmdbId,
            season = entry.season,
            episode = entry.episode,
            rememberSource = false,
        )
    }

    /** A natural end: the library, where Continue Watching picks the title up (N-D7). */
    fun libraryIntent(): Intent = Intent(appContext, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** The 2D player's words for the failure card (`playbackFailureMessage`, outside composition). */
    private fun failureText(error: PlaybackException): String = when (val failure = PlaybackFailure.of(error)) {
        is PlaybackFailure.ConnectionLost -> failure.detail
            ?.let { appContext.getString(R.string.player_error_connection_lost_detail, failure.host, it) }
            ?: appContext.getString(R.string.player_error_connection_lost, failure.host)
        is PlaybackFailure.CouldNotConnect -> failure.detail
            ?: appContext.getString(R.string.player_error_could_not_connect, failure.host)
        is PlaybackFailure.Connector -> connectorFailureMessage(appContext, failure.failure)
        is PlaybackFailure.Provider -> remoteFailureMessage(appContext, failure.kind, failure.failure)
        PlaybackFailure.Other -> appContext.getString(R.string.player_error_could_not_open)
    }

    private companion object {
        const val TAG = "TheaterPlayback"
        const val PLAYER_PREFS = "player"
    }
}

/**
 * The theater player's pure decisions, free of Android and Media3 so the
 * hermetic JVM suite covers them (`TheaterPlaybackRulesTest`).
 */
internal object TheaterPlaybackRules {
    /** The position ticker's period. */
    const val TICK_MILLIS = 500L

    /** Progress is written every this many ticks while playing: every 5 s, as the 2D player does. */
    const val WRITE_EVERY_TICKS = 10

    /** The handover's track: the one with its id, else the first in its language (case-insensitive), else none. */
    fun <T> matching(
        options: List<T>,
        id: String?,
        language: String?,
        idOf: (T) -> String?,
        languageOf: (T) -> String?,
    ): T? = options.firstOrNull { id != null && idOf(it) == id }
        ?: options.firstOrNull { language != null && languageOf(it)?.equals(language, ignoreCase = true) == true }

    /**
     * `PlayerActivity.writeProgress`'s record under the handover's identity,
     * or null when nothing is written: no TMDB id, no duration, or an
     * un-completed position after the completed write.
     */
    fun progress(
        handover: XrTheaterHandover,
        positionMillis: Long,
        durationMillis: Long,
        completed: Boolean,
        completedWritten: Boolean,
        nowMillis: Long,
    ): WatchProgress? {
        val tmdbId = handover.tmdbId ?: return null
        if (durationMillis <= 0L) return null
        val position = positionMillis.coerceIn(0L, durationMillis)
        val fraction = position.toDouble() / durationMillis
        val isCompleted = completed || fraction >= PlayerLogic.COMPLETE_FRACTION
        if (completedWritten && !isCompleted) return null
        return WatchProgress(
            tmdbId = tmdbId,
            mediaType = if (handover.isEpisode) WatchMediaType.EPISODE else WatchMediaType.MOVIE,
            position = if (isCompleted) 1.0 else fraction,
            watchedSeconds = position / 1000.0,
            lastWatchedEpochMillis = nowMillis,
            isCompleted = isCompleted,
            showTmdbId = handover.showTmdbId,
            seasonNumber = handover.season,
            episodeNumber = handover.episode,
            durationSeconds = durationMillis / 1000.0,
        )
    }

    /** `PlayerActivity.introDbMedia`: episodes need the show id and positive season and episode; movies their own id. */
    fun introDbMedia(handover: XrTheaterHandover): IntroDbMedia? = if (handover.isEpisode) {
        val showId = handover.showTmdbId?.takeIf { it in 1..10_000_000 } ?: return null
        val season = handover.season?.takeIf { it > 0 } ?: return null
        val episode = handover.episode?.takeIf { it > 0 } ?: return null
        IntroDbMedia.create(tmdbId = showId, season = season, episode = episode)
    } else {
        val movieId = handover.tmdbId?.takeIf { it in 1..10_000_000 } ?: return null
        IntroDbMedia.create(tmdbId = movieId)
    }

    /** The Up Next card's code: the row's detail ("S01E02"), else one formatted from the numbers, else none. */
    fun upNextCode(detail: String?, season: Int?, episode: Int?): String? =
        detail ?: season?.let { s -> episode?.let { e -> "S%02dE%02d".format(s, e) } }

    /** The picture's shape from the reported size; null until it is known or when a side is 0. */
    fun aspect(width: Int, height: Int, pixelWidthHeightRatio: Float): Float? {
        val pictureWidth = width * pixelWidthHeightRatio
        return if (pictureWidth > 0f && height > 0) pictureWidth / height else null
    }

    /** The dock's lists after a choice, before the player confirms it: only [id] is ticked (none for null). */
    fun withSelection(tracks: List<TheaterTrack>, id: String?): List<TheaterTrack> =
        tracks.map { it.copy(isSelected = it.id == id) }

    /**
     * Headset off or the activity paused: the player pauses, and only a
     * player that wanted to play resumes when the system gives the theater
     * back. A second hold while already held keeps the pending resume.
     */
    class SystemHold {
        var resumePending: Boolean = false
            private set

        /** True when the player should pause now. */
        fun hold(wantsToPlay: Boolean): Boolean {
            if (wantsToPlay) resumePending = true
            return wantsToPlay
        }

        /** True when the player should play now; the pending resume is spent. */
        fun release(): Boolean {
            val resume = resumePending
            resumePending = false
            return resume
        }
    }
}
