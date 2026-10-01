package com.babasama.edendale.android.player

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Platform-independent candidate for audio, subtitle, or video track matching.
 */
data class TrackCandidate(
    val id: String?,
    val language: String?,
    val label: String?,
    val width: Int = -1,
    val height: Int = -1,
)

/**
 * Saved playback preferences for a specific title (movie) or whole series (show).
 * Mirroring Apple's ContentPlayerPreferences.
 */
data class ContentPlayerPreferences(
    val speed: Float? = null,
    val audioTrackLanguage: String? = null,
    val audioTrackName: String? = null,
    val subtitleEnabled: Boolean? = null,
    val subtitleTrackLanguage: String? = null,
    val subtitleTrackName: String? = null,
    val videoTrackWidth: Int? = null,
    val videoTrackHeight: Int? = null,
)

/**
 * Pure rules, codec, and candidate matching for per-title memory.
 */
object ContentPlayerPreferencesRules {
    const val PREFS_NAME = "player_content"

    /**
     * Storage key for a movie or TV show.
     * Episodes share the show's key so preferences carry across the entire series.
     * Files without a TMDB ID store nothing (return null).
     */
    fun contentKey(tmdbId: Int?, isEpisode: Boolean, showTmdbId: Int?): String? {
        return if (isEpisode) {
            showTmdbId?.takeIf { it > 0 }?.let { "player.content.show.$it" }
        } else {
            tmdbId?.takeIf { it > 0 }?.let { "player.content.movie.$it" }
        }
    }

    /**
     * Serializes [ContentPlayerPreferences] to a JSON string.
     */
    fun encode(preferences: ContentPlayerPreferences): String {
        val jsonObject = buildJsonObject {
            preferences.speed?.let { put("speed", JsonPrimitive(it)) }
            preferences.audioTrackLanguage?.let { put("audioTrackLanguage", JsonPrimitive(it)) }
            preferences.audioTrackName?.let { put("audioTrackName", JsonPrimitive(it)) }
            preferences.subtitleEnabled?.let { put("subtitleEnabled", JsonPrimitive(it)) }
            preferences.subtitleTrackLanguage?.let { put("subtitleTrackLanguage", JsonPrimitive(it)) }
            preferences.subtitleTrackName?.let { put("subtitleTrackName", JsonPrimitive(it)) }
            preferences.videoTrackWidth?.let { put("videoTrackWidth", JsonPrimitive(it)) }
            preferences.videoTrackHeight?.let { put("videoTrackHeight", JsonPrimitive(it)) }
        }
        return jsonObject.toString()
    }

    /**
     * Deserializes a JSON string into [ContentPlayerPreferences].
     * Unknown fields are ignored. Corrupt JSON returns null.
     */
    fun decode(rawJson: String?): ContentPlayerPreferences? {
        if (rawJson.isNullOrBlank()) return null
        return try {
            val element = Json.parseToJsonElement(rawJson)
            val obj = element as? JsonObject ?: return null
            ContentPlayerPreferences(
                speed = obj["speed"]?.jsonPrimitive?.floatOrNull,
                audioTrackLanguage = obj["audioTrackLanguage"]?.jsonPrimitive?.contentOrNull,
                audioTrackName = obj["audioTrackName"]?.jsonPrimitive?.contentOrNull,
                subtitleEnabled = obj["subtitleEnabled"]?.jsonPrimitive?.booleanOrNull,
                subtitleTrackLanguage = obj["subtitleTrackLanguage"]?.jsonPrimitive?.contentOrNull,
                subtitleTrackName = obj["subtitleTrackName"]?.jsonPrimitive?.contentOrNull,
                videoTrackWidth = obj["videoTrackWidth"]?.jsonPrimitive?.intOrNull,
                videoTrackHeight = obj["videoTrackHeight"]?.jsonPrimitive?.intOrNull,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Matches audio track by language first, then track name.
     */
    fun bestAudioMatch(
        preferences: ContentPlayerPreferences,
        candidates: List<TrackCandidate>,
    ): TrackCandidate? {
        val lang = preferences.audioTrackLanguage
        if (!lang.isNullOrBlank()) {
            val match = candidates.firstOrNull { it.language == lang }
            if (match != null) return match
        }
        val name = preferences.audioTrackName
        if (!name.isNullOrBlank()) {
            val match = candidates.firstOrNull { it.label == name }
            if (match != null) return match
        }
        return null
    }

    /**
     * Matches embedded subtitle track by language first, then track name.
     * Sideloaded tracks (prefix "ext-") are excluded from matching.
     */
    fun bestSubtitleMatch(
        preferences: ContentPlayerPreferences,
        candidates: List<TrackCandidate>,
    ): TrackCandidate? {
        val embedded = candidates.filter { it.id?.startsWith("ext-") != true }
        val lang = preferences.subtitleTrackLanguage
        if (!lang.isNullOrBlank()) {
            val match = embedded.firstOrNull { it.language == lang }
            if (match != null) return match
        }
        val name = preferences.subtitleTrackName
        if (!name.isNullOrBlank()) {
            val match = embedded.firstOrNull { it.label == name }
            if (match != null) return match
        }
        return null
    }

    /**
     * Matches video track by width × height only when more than one video track exists.
     */
    fun bestVideoMatch(
        preferences: ContentPlayerPreferences,
        candidates: List<TrackCandidate>,
    ): TrackCandidate? {
        if (candidates.size <= 1) return null
        val width = preferences.videoTrackWidth ?: return null
        val height = preferences.videoTrackHeight ?: return null
        return candidates.firstOrNull { it.width == width && it.height == height }
    }
}

/**
 * Storage interface for per-title player preferences.
 */
interface ContentPlayerPreferencesStore {
    fun get(key: String): ContentPlayerPreferences?
    fun save(key: String, preferences: ContentPlayerPreferences)
    fun remove(key: String)
}

/**
 * SharedPreferences-backed [ContentPlayerPreferencesStore] using the "player_content" file.
 */
class SharedPreferencesContentStore(
    private val prefs: SharedPreferences,
) : ContentPlayerPreferencesStore {
    companion object {
        fun from(context: Context): SharedPreferencesContentStore {
            val sharedPrefs = context.getSharedPreferences(
                ContentPlayerPreferencesRules.PREFS_NAME,
                Context.MODE_PRIVATE,
            )
            return SharedPreferencesContentStore(sharedPrefs)
        }
    }

    override fun get(key: String): ContentPlayerPreferences? {
        val raw = prefs.getString(key, null) ?: return null
        return ContentPlayerPreferencesRules.decode(raw)
    }

    override fun save(key: String, preferences: ContentPlayerPreferences) {
        val raw = ContentPlayerPreferencesRules.encode(preferences)
        prefs.edit().putString(key, raw).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/**
 * In-memory implementation of [ContentPlayerPreferencesStore] for hermetic testing.
 */
class InMemoryContentPlayerPreferencesStore : ContentPlayerPreferencesStore {
    private val map = mutableMapOf<String, String>()

    override fun get(key: String): ContentPlayerPreferences? {
        val raw = map[key] ?: return null
        return ContentPlayerPreferencesRules.decode(raw)
    }

    override fun save(key: String, preferences: ContentPlayerPreferences) {
        map[key] = ContentPlayerPreferencesRules.encode(preferences)
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}

// ------------------------------------------------------------------
// Media3 Track Adapters
// ------------------------------------------------------------------

internal data class PlayerTrackOption(
    val group: TrackGroup,
    val trackIndex: Int,
    val id: String?,
    val label: String?,
    val language: String?,
    val width: Int = -1,
    val height: Int = -1,
    val channelCount: Int = -1,
    val isSelected: Boolean = false,
) {
    fun toCandidate(): TrackCandidate = TrackCandidate(
        id = id,
        language = language,
        label = label,
        width = width,
        height = height,
    )
}

/**
 * Selectable text (subtitle) tracks in the current media.
 */
internal fun textTrackOptions(tracks: Tracks): List<PlayerTrackOption> =
    tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
        (0 until group.length).mapNotNull { index ->
            if (!group.isTrackSupported(index)) return@mapNotNull null
            val format = group.getTrackFormat(index)
            PlayerTrackOption(
                group = group.mediaTrackGroup,
                trackIndex = index,
                id = format.id,
                label = format.label,
                language = format.language,
                isSelected = group.isTrackSelected(index),
            )
        }
    }

/**
 * Selectable audio tracks in the current media.
 */
internal fun audioTrackOptions(tracks: Tracks): List<PlayerTrackOption> =
    tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
        (0 until group.length).mapNotNull { index ->
            if (!group.isTrackSupported(index)) return@mapNotNull null
            val format = group.getTrackFormat(index)
            PlayerTrackOption(
                group = group.mediaTrackGroup,
                trackIndex = index,
                id = format.id,
                label = format.label,
                language = format.language,
                channelCount = format.channelCount,
                isSelected = group.isTrackSelected(index),
            )
        }
    }

/**
 * Selectable video tracks in the current media.
 */
internal fun videoTrackOptions(tracks: Tracks): List<PlayerTrackOption> =
    tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
        (0 until group.length).mapNotNull { index ->
            if (!group.isTrackSupported(index)) return@mapNotNull null
            val format = group.getTrackFormat(index)
            PlayerTrackOption(
                group = group.mediaTrackGroup,
                trackIndex = index,
                id = format.id,
                label = format.label,
                language = format.language,
                width = format.width,
                height = format.height,
                isSelected = group.isTrackSelected(index),
            )
        }
    }

/**
 * Applies a subtitle choice to the player.
 * Passing null disables the text track type and clears any overrides.
 */
internal fun selectTextTrack(player: Player, option: PlayerTrackOption?) {
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .apply {
            if (option == null) {
                clearOverridesOfType(C.TRACK_TYPE_TEXT)
                setPreferredTextLanguage(null)
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setOverrideForType(TrackSelectionOverride(option.group, option.trackIndex))
                option.language?.let { setPreferredTextLanguage(it) }
            }
        }
        .build()
}

/**
 * Applies an audio track choice to the player.
 */
internal fun selectAudioTrack(player: Player, option: PlayerTrackOption) {
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
        .setOverrideForType(TrackSelectionOverride(option.group, option.trackIndex))
        .apply {
            option.language?.let { setPreferredAudioLanguage(it) }
        }
        .build()
}

/**
 * Applies a video track choice to the player.
 */
internal fun selectVideoTrack(player: Player, option: PlayerTrackOption) {
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
        .setOverrideForType(TrackSelectionOverride(option.group, option.trackIndex))
        .build()
}

/**
 * Takes a snapshot of current player state for persistence.
 */
internal fun snapshotPreferences(
    baseRate: Float,
    tracks: Tracks,
    trackSelectionParameters: TrackSelectionParameters? = null,
    existing: ContentPlayerPreferences? = null,
): ContentPlayerPreferences {
    val audioOptions = audioTrackOptions(tracks)
    val textOptions = textTrackOptions(tracks)
    val videoOptions = videoTrackOptions(tracks)

    val isTextDisabled = trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
    val textOverride = trackSelectionParameters?.overrides?.entries?.firstOrNull { it.key.type == C.TRACK_TYPE_TEXT }
    val audioOverride = trackSelectionParameters?.overrides?.entries?.firstOrNull { it.key.type == C.TRACK_TYPE_AUDIO }
    val videoOverride = trackSelectionParameters?.overrides?.entries?.firstOrNull { it.key.type == C.TRACK_TYPE_VIDEO }

    val selectedAudio = if (audioOverride != null) {
        val index = audioOverride.value.trackIndices.firstOrNull() ?: 0
        audioOptions.firstOrNull { it.group == audioOverride.key && it.trackIndex == index }
            ?: audioOptions.firstOrNull { it.isSelected }
    } else {
        audioOptions.firstOrNull { it.isSelected }
    }

    val selectedVideo = if (videoOverride != null) {
        val index = videoOverride.value.trackIndices.firstOrNull() ?: 0
        videoOptions.firstOrNull { it.group == videoOverride.key && it.trackIndex == index }
            ?: videoOptions.firstOrNull { it.isSelected }
    } else {
        videoOptions.firstOrNull { it.isSelected }
    }

    val subtitleEnabled: Boolean?
    val subtitleTrackLanguage: String?
    val subtitleTrackName: String?

    if (isTextDisabled) {
        subtitleEnabled = false
        subtitleTrackLanguage = null
        subtitleTrackName = null
    } else {
        val selectedText = if (textOverride != null) {
            val index = textOverride.value.trackIndices.firstOrNull() ?: 0
            textOptions.firstOrNull { it.group == textOverride.key && it.trackIndex == index }
                ?: textOptions.firstOrNull { it.isSelected }
        } else {
            textOptions.firstOrNull { it.isSelected }
        }

        if (selectedText != null) {
            if (selectedText.id?.startsWith("ext-") == true) {
                // Sideloaded Wyzie track: never remember or save as embedded preference
                subtitleEnabled = existing?.subtitleEnabled
                subtitleTrackLanguage = existing?.subtitleTrackLanguage
                subtitleTrackName = existing?.subtitleTrackName
            } else {
                subtitleEnabled = true
                subtitleTrackLanguage = selectedText.language
                subtitleTrackName = selectedText.label
            }
        } else if (textOptions.isNotEmpty()) {
            subtitleEnabled = false
            subtitleTrackLanguage = null
            subtitleTrackName = null
        } else {
            subtitleEnabled = existing?.subtitleEnabled
            subtitleTrackLanguage = existing?.subtitleTrackLanguage
            subtitleTrackName = existing?.subtitleTrackName
        }
    }

    return ContentPlayerPreferences(
        speed = baseRate,
        audioTrackLanguage = selectedAudio?.language ?: existing?.audioTrackLanguage,
        audioTrackName = selectedAudio?.label ?: existing?.audioTrackName,
        subtitleEnabled = subtitleEnabled,
        subtitleTrackLanguage = subtitleTrackLanguage,
        subtitleTrackName = subtitleTrackName,
        videoTrackWidth = selectedVideo?.width?.takeIf { it > 0 } ?: existing?.videoTrackWidth,
        videoTrackHeight = selectedVideo?.height?.takeIf { it > 0 } ?: existing?.videoTrackHeight,
    )
}

/**
 * Applies saved content preferences to the player and chrome.
 */
internal fun applyContentPreferences(
    preferences: ContentPlayerPreferences,
    player: Player,
    chrome: PlayerChromeState,
    tracks: Tracks,
) {
    preferences.speed?.let { speed ->
        chrome.setRate(player, speed)
    }

    val audioOptions = audioTrackOptions(tracks)
    val audioCandidates = audioOptions.map { it.toCandidate() }
    val audioMatch = ContentPlayerPreferencesRules.bestAudioMatch(preferences, audioCandidates)
    if (audioMatch != null) {
        val index = audioCandidates.indexOf(audioMatch)
        if (index >= 0) {
            selectAudioTrack(player, audioOptions[index])
        }
    }

    val textOptions = textTrackOptions(tracks)
    if (preferences.subtitleEnabled == false) {
        selectTextTrack(player, null)
    } else if (preferences.subtitleEnabled == true) {
        val textCandidates = textOptions.map { it.toCandidate() }
        val textMatch = ContentPlayerPreferencesRules.bestSubtitleMatch(preferences, textCandidates)
        if (textMatch != null) {
            val index = textCandidates.indexOf(textMatch)
            if (index >= 0) {
                selectTextTrack(player, textOptions[index])
            }
        }
    }

    val videoOptions = videoTrackOptions(tracks)
    if (videoOptions.size > 1) {
        val videoCandidates = videoOptions.map { it.toCandidate() }
        val videoMatch = ContentPlayerPreferencesRules.bestVideoMatch(preferences, videoCandidates)
        if (videoMatch != null) {
            val index = videoCandidates.indexOf(videoMatch)
            if (index >= 0) {
                selectVideoTrack(player, videoOptions[index])
            }
        }
    }
}
