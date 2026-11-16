package com.babasama.edendale.android.data

import android.content.Context
import com.babasama.edendale.android.PlaybackSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The source each show and movie last played from on this device (27.1), so
 * the next episode — from the show page, Continue Watching, the TV's Watch
 * Next row, or the Movies & Shows hero — starts on the same one. Keys are
 * `PlaybackSources.showMemoryKey`/`movieMemoryKey`; values are
 * `PlaybackSources.sourceKey`, a linked source's `treeUri`.
 *
 * Which link of a share a device reaches is a fact about that device and its
 * network, so this never syncs, never goes to a TV, and is excluded from
 * backup and device transfer (backup_rules.xml, data_extraction_rules.xml).
 * The map loads on [scope]'s IO thread; [sourceFor] and the writers block on
 * disk the first time, so call them from a background thread.
 */
class PlaybackSourceMemory(context: Context, scope: CoroutineScope) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow<Map<String, String>>(emptyMap())
    private var loaded = false

    /** Every remembered source by key, now and after each change; empty until the file has loaded. */
    val changes: StateFlow<Map<String, String>> = state.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { sources() }
    }

    /** Every remembered source, loading the file the first time. */
    @Synchronized
    fun sources(): Map<String, String> {
        if (!loaded) {
            state.value = preferences.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap()
            loaded = true
        }
        return state.value
    }

    /** The source remembered under [key]. */
    fun sourceFor(key: String?): String? = key?.let { sources()[it] }

    /** Remembers [sourceKey] under every one of [keys], in one write. */
    @Synchronized
    fun remember(keys: Collection<String>, sourceKey: String) {
        val current = sources()
        if (keys.isEmpty() || keys.all { current[it] == sourceKey }) return
        preferences.edit().apply { keys.forEach { putString(it, sourceKey) } }.apply()
        state.value = current + keys.associateWith { sourceKey }
    }

    fun remember(key: String, sourceKey: String) = remember(listOf(key), sourceKey)

    /** An unlinked source is forgotten everywhere it was remembered. */
    @Synchronized
    fun forgetSource(sourceKey: String) {
        val current = sources()
        val keys = current.filterValues { it == sourceKey }.keys
        if (keys.isEmpty()) return
        preferences.edit().apply { keys.forEach(::remove) }.apply()
        state.value = current - keys
    }

    /**
     * Remembers the source of the library file at [uri], once it has played:
     * an episode under its show (by TMDB id and by show key, so the choice
     * survives enrichment), a movie under its TMDB id — its own, else
     * [tmdbId], the title's the player was given, for a copy whose match is
     * still pending. A file the library doesn't hold, or a movie with no id
     * either way, isn't remembered.
     */
    suspend fun recordPlayed(dao: LibraryDao, uri: String, tmdbId: Int? = null) {
        val episode = dao.episodeByUri(uri)
        val (keys, folderUri) = if (episode != null) {
            PlaybackSources.showMemoryKeys(dao.showByKey(episode.showKey)?.tmdbId, episode.showKey) to episode.folderUri
        } else {
            val movie = dao.movieByUri(uri) ?: return
            listOfNotNull(PlaybackSources.movieMemoryKey(movie.tmdbId ?: tmdbId)) to movie.folderUri
        }
        withContext(Dispatchers.IO) { remember(keys, PlaybackSources.sourceKey(folderUri)) }
    }

    companion object {
        /** Listed in backup_rules.xml and data_extraction_rules.xml. */
        const val FILE_NAME = "edendale_playback_sources"
    }
}
