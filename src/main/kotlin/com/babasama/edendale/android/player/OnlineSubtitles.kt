package com.babasama.edendale.android.player

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.babasama.edendale.wyzie.WyzieSubtitle

internal sealed interface OnlineSubtitlePhase {
    data object Idle : OnlineSubtitlePhase
    data object Searching : OnlineSubtitlePhase
    data object Results : OnlineSubtitlePhase
    data class Failed(val message: String) : OnlineSubtitlePhase
}

/**
 * Coroutine-free state for online subtitle controls. The activity owns all
 * request jobs; this object only publishes UI state and persisted filters.
 */
internal class OnlineSubtitlesState(private val prefs: SharedPreferences) {
    var phase by mutableStateOf<OnlineSubtitlePhase>(OnlineSubtitlePhase.Idle)
        private set

    var results by mutableStateOf<List<WyzieSubtitle>>(emptyList())
        private set

    var language by mutableStateOf(prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE).orEmpty())
        private set

    var hearingImpaired by mutableStateOf(prefs.getBoolean(KEY_HEARING_IMPAIRED, false))
        private set

    var downloadingId by mutableStateOf<String?>(null)
        private set

    var downloadedIds by mutableStateOf<Set<String>>(emptySet())
        private set

    fun updateLanguage(value: String) {
        language = value
        prefs.edit().putString(KEY_LANGUAGE, value).apply()
    }

    fun updateHearingImpaired(value: Boolean) {
        hearingImpaired = value
        prefs.edit().putBoolean(KEY_HEARING_IMPAIRED, value).apply()
    }

    fun startSearch() {
        phase = OnlineSubtitlePhase.Searching
        results = emptyList()
        downloadingId = null
    }

    fun showResults(value: List<WyzieSubtitle>) {
        results = value
        phase = OnlineSubtitlePhase.Results
    }

    fun fail(message: String, clearResults: Boolean) {
        if (clearResults) results = emptyList()
        phase = OnlineSubtitlePhase.Failed(message)
        downloadingId = null
    }

    fun startDownload(id: String) {
        downloadingId = id
    }

    fun finishDownload(id: String) {
        downloadedIds = downloadedIds + id
        downloadingId = null
        if (phase is OnlineSubtitlePhase.Failed) phase = OnlineSubtitlePhase.Results
    }

    fun reset() {
        phase = OnlineSubtitlePhase.Idle
        results = emptyList()
        downloadingId = null
        downloadedIds = emptySet()
    }

    private companion object {
        const val DEFAULT_LANGUAGE = "en"
        const val KEY_LANGUAGE = "subtitles.wyzieLanguage"
        const val KEY_HEARING_IMPAIRED = "subtitles.wyzieHearingImpaired"
    }
}
