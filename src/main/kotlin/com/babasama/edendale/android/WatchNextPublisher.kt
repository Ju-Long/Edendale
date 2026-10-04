package com.babasama.edendale.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.tv.TvContract
import com.babasama.edendale.android.data.LibraryEpisodeEntity
import com.babasama.edendale.android.data.LibraryFolderEntity
import com.babasama.edendale.android.data.LibraryMovieEntity
import com.babasama.edendale.android.data.LibraryRepository
import com.babasama.edendale.android.data.LibraryShowEntity
import com.babasama.edendale.android.player.PlayerActivity
import com.babasama.edendale.domain.WatchProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

/** Settings → Android TV: Continue Watching on the home screen (I.3). Device-local and off by default. */
internal class WatchNextSettings(context: Context) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = preferences.getBoolean(KEY_ENABLED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    private val state = MutableStateFlow(isEnabled)

    // SharedPreferences holds its listeners weakly, so this field keeps the
    // listener alive for as long as the settings object lives (the process).
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_ENABLED || key == null) state.value = isEnabled
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    /** The setting now, and again after every change. */
    val changes: StateFlow<Boolean> = state.asStateFlow()

    private companion object {
        const val FILE_NAME = "edendale_tv"
        const val KEY_ENABLED = "tv.watchNextEnabled"
    }
}

/**
 * Keeps the TV home screen's Watch Next row in step with Continue Watching
 * (I.3) for the life of the process, on TV devices only. While the setting is
 * off it holds no rows: turning it off removes every row Edendale wrote.
 *
 * The rows go to the system's TV provider on the device, where the launcher
 * reads them; nothing else leaves the device. Titles the Young Audience filter
 * hides in the app never reach the home screen.
 */
internal class WatchNextPublisher(
    private val context: Context,
    private val library: LibraryRepository,
    private val settings: WatchNextSettings = WatchNextSettings(context),
    private val store: WatchNextStore = TvProviderWatchNextStore(context),
) {
    // The publisher's own filter: it reads the preference the app's filter
    // writes, and verifies titles the same way, with no shared state to race on.
    private val audienceFilter = youngAudienceFilter(context)

    // A failure here must never take the app down; the next change retries.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start() {
        scope.launch {
            settings.changes
                .flatMapLatest { enabled ->
                    if (!enabled) {
                        flowOf(emptyList())
                    } else {
                        combine(librarySnapshots(), audiencePreferenceChanges(context)) { snapshot, _ -> snapshot }
                            // Playback saves progress every 5 s, so the row is written once
                            // playback pauses or stops rather than on every save.
                            .debounce(SETTLE_MILLIS)
                            .mapLatest(::desired)
                    }
                }
                .collect(::publish)
        }
    }

    private fun librarySnapshots(): Flow<LibrarySnapshot> = combine(
        library.watchProgress,
        library.movies,
        library.episodes,
        library.shows,
        library.folders,
    ) { progress, movies, episodes, shows, folders -> LibrarySnapshot(progress, movies, episodes, shows, folders) }

    /** Continue Watching after the Young Audience filter, as programs. */
    private suspend fun desired(snapshot: LibrarySnapshot): List<WatchNextProgram>? = try {
        audienceFilter.refreshPreference()
        audienceFilter.verify(libraryAudienceRefs(snapshot.movies, snapshot.shows))
        val visible = visibleLibrary(snapshot.movies, snapshot.shows, snapshot.episodes, audienceFilter)
        val entries = continueWatching(
            snapshot.progress,
            visible.movies,
            visible.episodes,
            visible.shows,
            limit = null,
            folders = snapshot.folders,
        )
        WatchNext.programs(entries)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun publish(programs: List<WatchNextProgram>?) {
        if (programs == null) return
        val published = store.published() ?: return
        store.apply(WatchNext.changes(programs, published))
    }

    private data class LibrarySnapshot(
        val progress: List<WatchProgress>,
        val movies: List<LibraryMovieEntity>,
        val episodes: List<LibraryEpisodeEntity>,
        val shows: List<LibraryShowEntity>,
        val folders: List<LibraryFolderEntity>,
    )

    private companion object {
        const val SETTLE_MILLIS = 8_000L
    }
}

/** Edendale's rows in the home screen's Watch Next row (I.3). */
internal interface WatchNextStore {
    /** The rows this app wrote, or null when the device has no TV provider for it. */
    fun published(): List<PublishedWatchNextProgram>?

    fun apply(changes: WatchNextChanges)
}

/**
 * The system TV provider's Watch Next table. An app sees and changes only its
 * own rows there; the launcher decides which of them to show and in what
 * order (most recent engagement first).
 */
internal class TvProviderWatchNextStore(private val context: Context) : WatchNextStore {
    private val resolver get() = context.contentResolver

    override fun published(): List<PublishedWatchNextProgram>? = quietly {
        resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, PROJECTION, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        PublishedWatchNextProgram(
                            rowId = cursor.getLong(0),
                            key = cursor.getString(1),
                            isBrowsable = cursor.getInt(2) != 0,
                            lastEngagementMillis = cursor.getLong(3),
                            signature = if (cursor.isNull(4)) null else cursor.getLong(4),
                        ),
                    )
                }
            }
        }
    }

    override fun apply(changes: WatchNextChanges) {
        for (rowId in changes.deletes) {
            quietly { resolver.delete(TvContract.buildWatchNextProgramUri(rowId), null, null) }
        }
        for ((rowId, program) in changes.updates) {
            quietly { resolver.update(TvContract.buildWatchNextProgramUri(rowId), values(program), null, null) }
        }
        for (program in changes.inserts) {
            quietly { resolver.insert(TvContract.WatchNextPrograms.CONTENT_URI, values(program)) }
        }
    }

    private fun values(program: WatchNextProgram) = ContentValues().apply {
        put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, program.key)
        put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_FLAG1, program.signature)
        put(
            TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
            if (program.isNext) {
                TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
            } else {
                TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
            },
        )
        put(
            TvContract.WatchNextPrograms.COLUMN_TYPE,
            if (program.isEpisode) TvContract.WatchNextPrograms.TYPE_TV_EPISODE else TvContract.WatchNextPrograms.TYPE_MOVIE,
        )
        put(TvContract.WatchNextPrograms.COLUMN_TITLE, program.title)
        put(TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE, program.episodeTitle)
        put(TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, program.season?.toString())
        put(TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, program.episode?.toString())
        put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, program.artworkUrl)
        put(
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
            if (program.isWideArtwork) {
                TvContract.WatchNextPrograms.ASPECT_RATIO_16_9
            } else {
                TvContract.WatchNextPrograms.ASPECT_RATIO_2_3
            },
        )
        put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, program.durationMillis?.toIntMillis())
        put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, program.positionMillis?.toIntMillis())
        put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, program.lastEngagementMillis)
        put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, intentUri(program.play))
    }

    /** The shelf card's own player request, so a row plays exactly what the card would. */
    private fun intentUri(play: WatchNextPlay): String = PlayerActivity.intent(
        context = context,
        uri = play.uri,
        title = play.title,
        tmdbId = play.tmdbId,
        isEpisode = play.isEpisode,
        showTmdbId = play.showTmdbId,
        season = play.season,
        episode = play.episode,
    ).toUri(Intent.URI_INTENT_SCHEME)

    // The provider's millisecond columns are 32-bit.
    private fun Long.toIntMillis(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    // The provider belongs to the launcher's platform, not to Edendale: a
    // device without it, or one that refuses a write, costs the row only.
    private inline fun <T> quietly(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    }

    private companion object {
        val PROJECTION = arrayOf(
            TvContract.WatchNextPrograms._ID,
            TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
            TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_FLAG1,
        )
    }
}
