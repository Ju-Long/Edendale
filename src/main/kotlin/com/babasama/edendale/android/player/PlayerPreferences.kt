package com.babasama.edendale.android.player

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

/**
 * How far one skip jumps. Each length has matching arrow-rotate glyphs.
 */
enum class SkipInterval(val seconds: Int) {
    TEN(10),
    FIFTEEN(15),
    THIRTY(30);

    val millis: Long get() = seconds * 1000L

    companion object {
        val DEFAULT = TEN

        fun fromSeconds(seconds: Int?): SkipInterval? = when (seconds) {
            10 -> TEN
            15 -> FIFTEEN
            30 -> THIRTY
            else -> null
        }
    }
}

/**
 * Which way a skip jumps.
 */
enum class SkipDirection {
    BACKWARD,
    FORWARD;

    fun sign(): Int = when (this) {
        BACKWARD -> -1
        FORWARD -> 1
    }
}

/**
 * The side of the picture a press-and-hold rests on.
 */
enum class HoldSide {
    LEFT,
    RIGHT,
}

/**
 * Pure parsing, normalization, and constants for device-local player preferences.
 * Contains no Android imports so it can be verified with hermetic JVM tests.
 */
object PlayerPreferencesRules {
    const val PREFS_NAME = "player"

    // App Controls (B.1)
    const val KEY_SKIP_BACKWARD_SECONDS = "player.skipBackwardSeconds"
    const val KEY_SKIP_FORWARD_SECONDS = "player.skipForwardSeconds"
    const val KEY_HOLD_LEFT_RATE = "player.holdLeftRate"
    const val KEY_HOLD_RIGHT_RATE = "player.holdRightRate"

    // Persisted Player State (B.2)
    const val KEY_LOOP_ENABLED = "player.loopEnabled"
    const val KEY_ASPECT_FILL = "player.aspectFill"
    const val KEY_AUTO_PIP = "player.autoPiP"

    // Skip Prompts (C.5)
    const val KEY_SEGMENT_PROMPTS_ENABLED = "player.segmentPromptsEnabled"

    // Deprecated keys removed in C.4 (D6)
    const val KEY_SKIP_RECAP = "player.skipRecap"
    const val KEY_SKIP_CREDITS = "player.skipCredits"

    // Subtitles (B.4)
    const val KEY_SUBTITLES_FONT = "subtitles.font"
    const val KEY_SUBTITLES_TEXT_COLOR = "subtitles.textColor"
    const val KEY_SUBTITLES_BACKGROUND_COLOR = "subtitles.backgroundColor"
    const val KEY_SUBTITLES_BACKGROUND_OPACITY = "subtitles.backgroundOpacity"

    // Audio (E.1)
    const val KEY_AUDIO_ENHANCEMENT_PROFILE = "audio.enhancementProfile"
    const val KEY_AUDIO_ENHANCEMENT_PREAMP = "audio.enhancementPreamp"
    const val KEY_AUDIO_ENHANCEMENT_BANDS = "audio.enhancementBands"
    const val KEY_AUDIO_BOOSTER_ENABLED = "audio.boosterEnabled"

    // Video adjustments (F.2)
    const val KEY_VIDEO_ADJUSTMENTS = "video.adjustments"

    // Defaults and bounds
    val DEFAULT_SKIP_INTERVAL = SkipInterval.TEN
    const val DEFAULT_HOLD_LEFT_RATE = 0.5f
    const val DEFAULT_HOLD_RIGHT_RATE = 2.0f
    const val HOLD_RATE_STEP = 0.25f
    const val HOLD_RATE_MIN = 0.25f
    const val HOLD_RATE_MAX = 3.00f

    const val DEFAULT_SUBTITLES_FONT = "system"
    const val DEFAULT_SUBTITLES_TEXT_COLOR = "parchment"
    const val DEFAULT_SUBTITLES_BACKGROUND_COLOR = "ink"
    const val DEFAULT_SUBTITLES_BACKGROUND_OPACITY = 1.0f

    const val DEFAULT_AUDIO_PROFILE = "movies"
    const val DEFAULT_AUDIO_PREAMP = 0.0f
    const val AUDIO_PREAMP_MIN = -20.0f
    const val AUDIO_PREAMP_MAX = 20.0f
    const val AUDIO_BOOSTER_BOOST = 10.0f

    val ALLOWED_SUBTITLE_FONTS = setOf("system", "rounded", "serif", "monospaced")
    val ALLOWED_SUBTITLE_TEXT_COLORS = setOf("parchment", "white", "yellow", "cyan", "green", "black")
    val ALLOWED_SUBTITLE_BG_COLORS = setOf("ink", "black", "charcoal", "navy", "white")
    val ALLOWED_AUDIO_PROFILES = setOf("flat", "movies", "music", "dialogue", "nightMode")

    fun parseSkipInterval(value: Int?): SkipInterval {
        return SkipInterval.fromSeconds(value) ?: DEFAULT_SKIP_INTERVAL
    }

    fun skipOffset(direction: SkipDirection, interval: SkipInterval): Int {
        return direction.sign() * interval.seconds
    }

    fun normalizeHoldRate(rate: Float?): Float {
        if (rate == null || !rate.isFinite()) return HOLD_RATE_MIN
        val snapped = (rate / HOLD_RATE_STEP).roundToInt() * HOLD_RATE_STEP
        return snapped.coerceIn(HOLD_RATE_MIN, HOLD_RATE_MAX)
    }

    fun parseHoldRate(value: Float?, fallback: Float): Float {
        if (value == null) return fallback
        return normalizeHoldRate(value)
    }

    fun normalizeSubtitleFont(raw: String?): String {
        return if (raw != null && raw in ALLOWED_SUBTITLE_FONTS) raw else DEFAULT_SUBTITLES_FONT
    }

    fun normalizeSubtitleTextColor(raw: String?): String {
        return if (raw != null && raw in ALLOWED_SUBTITLE_TEXT_COLORS) raw else DEFAULT_SUBTITLES_TEXT_COLOR
    }

    fun normalizeSubtitleBackgroundColor(raw: String?): String {
        return if (raw != null && raw in ALLOWED_SUBTITLE_BG_COLORS) raw else DEFAULT_SUBTITLES_BACKGROUND_COLOR
    }

    fun normalizeSubtitleBackgroundOpacity(raw: Float?): Float {
        if (raw == null || !raw.isFinite()) return DEFAULT_SUBTITLES_BACKGROUND_OPACITY
        val rounded = (raw * 100f).roundToInt() / 100f
        return rounded.coerceIn(0f, 1f)
    }

    fun normalizeAudioProfile(raw: String?): String {
        return if (raw != null && raw in ALLOWED_AUDIO_PROFILES) raw else DEFAULT_AUDIO_PROFILE
    }

    fun normalizeAudioPreamp(raw: Float?): Float {
        if (raw == null || !raw.isFinite()) return DEFAULT_AUDIO_PREAMP
        return raw.coerceIn(AUDIO_PREAMP_MIN, AUDIO_PREAMP_MAX)
    }

    fun normalizeAudioBands(raw: List<Float>?): List<Float> {
        if (raw == null || raw.size != 10) return List(10) { 0f }
        return raw.map { band ->
            if (band.isFinite()) band.coerceIn(AUDIO_PREAMP_MIN, AUDIO_PREAMP_MAX) else 0f
        }
    }
}

/**
 * Abstraction over key-value storage for player preferences.
 * Enables in-memory testing without Robolectric or Android framework mocks.
 */
interface PlayerPreferencesStore {
    fun getInt(key: String, defValue: Int): Int
    fun putInt(key: String, value: Int)
    fun getFloat(key: String, defValue: Float): Float
    fun putFloat(key: String, value: Float)
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
    fun getString(key: String, defValue: String?): String?
    fun putString(key: String, value: String?)
    fun contains(key: String): Boolean
    fun remove(key: String)
    fun addListener(listener: (String) -> Unit): AutoCloseable
}

/**
 * SharedPreferences implementation of [PlayerPreferencesStore].
 */
class SharedPreferencesPlayerStore(
    private val prefs: SharedPreferences
) : PlayerPreferencesStore {
    override fun getInt(key: String, defValue: Int): Int = prefs.getInt(key, defValue)
    override fun putInt(key: String, value: Int) = prefs.edit().putInt(key, value).apply()

    override fun getFloat(key: String, defValue: Float): Float = prefs.getFloat(key, defValue)
    override fun putFloat(key: String, value: Float) = prefs.edit().putFloat(key, value).apply()

    override fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)
    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    override fun getString(key: String, defValue: String?): String? = prefs.getString(key, defValue)
    override fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()

    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun remove(key: String) = prefs.edit().remove(key).apply()

    override fun addListener(listener: (String) -> Unit): AutoCloseable {
        val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) listener(key)
        }
        prefs.registerOnSharedPreferenceChangeListener(changeListener)
        return AutoCloseable {
            prefs.unregisterOnSharedPreferenceChangeListener(changeListener)
        }
    }
}

/**
 * In-memory map implementation of [PlayerPreferencesStore] for hermetic testing.
 */
class InMemoryPlayerPreferencesStore : PlayerPreferencesStore {
    private val values = mutableMapOf<String, Any>()
    private val listeners = mutableListOf<(String) -> Unit>()

    override fun getInt(key: String, defValue: Int): Int = (values[key] as? Number)?.toInt() ?: defValue
    override fun putInt(key: String, value: Int) {
        values[key] = value
        notifyListeners(key)
    }

    override fun getFloat(key: String, defValue: Float): Float = (values[key] as? Number)?.toFloat() ?: defValue
    override fun putFloat(key: String, value: Float) {
        values[key] = value
        notifyListeners(key)
    }

    override fun getBoolean(key: String, defValue: Boolean): Boolean = (values[key] as? Boolean) ?: defValue
    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
        notifyListeners(key)
    }

    override fun getString(key: String, defValue: String?): String? = (values[key] as? String) ?: defValue
    override fun putString(key: String, value: String?) {
        if (value != null) values[key] = value else values.remove(key)
        notifyListeners(key)
    }

    override fun contains(key: String): Boolean = values.containsKey(key)
    override fun remove(key: String) {
        if (values.remove(key) != null) notifyListeners(key)
    }

    override fun addListener(listener: (String) -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    private fun notifyListeners(key: String) {
        listeners.toList().forEach { it(key) }
    }
}

/**
 * Device-local player preference store adapter.
 * Wraps [PlayerPreferencesStore] and provides typed access with live change notifications.
 */
class PlayerPreferences(
    private val store: PlayerPreferencesStore
) {
    constructor(prefs: SharedPreferences) : this(SharedPreferencesPlayerStore(prefs))

    init {
        // C.4.2: On the first launch after the upgrade, remove obsolete timed auto-skip keys.
        // Skip Prompts (segmentPromptsEnabled) never reads or depends on them.
        store.remove(PlayerPreferencesRules.KEY_SKIP_RECAP)
        store.remove(PlayerPreferencesRules.KEY_SKIP_CREDITS)
    }

    companion object {
        fun from(context: Context): PlayerPreferences {
            val sharedPrefs = context.getSharedPreferences(
                PlayerPreferencesRules.PREFS_NAME,
                Context.MODE_PRIVATE
            )
            return PlayerPreferences(sharedPrefs)
        }
    }

    // Live change listeners
    private val listeners = mutableListOf<() -> Unit>()
    private val storeSubscription = store.addListener { key ->
        when (key) {
            PlayerPreferencesRules.KEY_SKIP_BACKWARD_SECONDS,
            PlayerPreferencesRules.KEY_SKIP_FORWARD_SECONDS,
            PlayerPreferencesRules.KEY_HOLD_LEFT_RATE,
            PlayerPreferencesRules.KEY_HOLD_RIGHT_RATE,
            PlayerPreferencesRules.KEY_LOOP_ENABLED,
            PlayerPreferencesRules.KEY_ASPECT_FILL,
            PlayerPreferencesRules.KEY_AUTO_PIP,
            PlayerPreferencesRules.KEY_SEGMENT_PROMPTS_ENABLED -> {
                listeners.toList().forEach { it() }
            }
        }
    }

    fun addChangeListener(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    // App Controls
    var skipBackwardInterval: SkipInterval
        get() = PlayerPreferencesRules.parseSkipInterval(
            store.getInt(PlayerPreferencesRules.KEY_SKIP_BACKWARD_SECONDS, 10)
        )
        set(value) = store.putInt(PlayerPreferencesRules.KEY_SKIP_BACKWARD_SECONDS, value.seconds)

    var skipForwardInterval: SkipInterval
        get() = PlayerPreferencesRules.parseSkipInterval(
            store.getInt(PlayerPreferencesRules.KEY_SKIP_FORWARD_SECONDS, 10)
        )
        set(value) = store.putInt(PlayerPreferencesRules.KEY_SKIP_FORWARD_SECONDS, value.seconds)

    fun skipInterval(direction: SkipDirection): SkipInterval = when (direction) {
        SkipDirection.BACKWARD -> skipBackwardInterval
        SkipDirection.FORWARD -> skipForwardInterval
    }

    fun skipOffset(direction: SkipDirection): Int =
        PlayerPreferencesRules.skipOffset(direction, skipInterval(direction))

    var holdLeftRate: Float
        get() {
            val raw = if (store.contains(PlayerPreferencesRules.KEY_HOLD_LEFT_RATE)) {
                store.getFloat(PlayerPreferencesRules.KEY_HOLD_LEFT_RATE, PlayerPreferencesRules.DEFAULT_HOLD_LEFT_RATE)
            } else null
            return PlayerPreferencesRules.parseHoldRate(raw, PlayerPreferencesRules.DEFAULT_HOLD_LEFT_RATE)
        }
        set(value) = store.putFloat(
            PlayerPreferencesRules.KEY_HOLD_LEFT_RATE,
            PlayerPreferencesRules.normalizeHoldRate(value)
        )

    var holdRightRate: Float
        get() {
            val raw = if (store.contains(PlayerPreferencesRules.KEY_HOLD_RIGHT_RATE)) {
                store.getFloat(PlayerPreferencesRules.KEY_HOLD_RIGHT_RATE, PlayerPreferencesRules.DEFAULT_HOLD_RIGHT_RATE)
            } else null
            return PlayerPreferencesRules.parseHoldRate(raw, PlayerPreferencesRules.DEFAULT_HOLD_RIGHT_RATE)
        }
        set(value) = store.putFloat(
            PlayerPreferencesRules.KEY_HOLD_RIGHT_RATE,
            PlayerPreferencesRules.normalizeHoldRate(value)
        )

    fun holdRate(side: HoldSide): Float = when (side) {
        HoldSide.LEFT -> holdLeftRate
        HoldSide.RIGHT -> holdRightRate
    }

    fun setHoldRate(rate: Float, side: HoldSide) {
        when (side) {
            HoldSide.LEFT -> holdLeftRate = rate
            HoldSide.RIGHT -> holdRightRate = rate
        }
    }

    // Playback state
    var loopEnabled: Boolean
        get() = store.getBoolean(PlayerPreferencesRules.KEY_LOOP_ENABLED, false)
        set(value) = store.putBoolean(PlayerPreferencesRules.KEY_LOOP_ENABLED, value)

    var aspectFill: Boolean
        get() = store.getBoolean(PlayerPreferencesRules.KEY_ASPECT_FILL, false)
        set(value) = store.putBoolean(PlayerPreferencesRules.KEY_ASPECT_FILL, value)

    var autoPip: Boolean
        get() = store.getBoolean(PlayerPreferencesRules.KEY_AUTO_PIP, true)
        set(value) = store.putBoolean(PlayerPreferencesRules.KEY_AUTO_PIP, value)

    var segmentPromptsEnabled: Boolean
        get() = store.getBoolean(PlayerPreferencesRules.KEY_SEGMENT_PROMPTS_ENABLED, false)
        set(value) = store.putBoolean(PlayerPreferencesRules.KEY_SEGMENT_PROMPTS_ENABLED, value)

    // Subtitles appearance
    var subtitleFont: String
        get() = PlayerPreferencesRules.normalizeSubtitleFont(
            store.getString(PlayerPreferencesRules.KEY_SUBTITLES_FONT, PlayerPreferencesRules.DEFAULT_SUBTITLES_FONT)
        )
        set(value) = store.putString(
            PlayerPreferencesRules.KEY_SUBTITLES_FONT,
            PlayerPreferencesRules.normalizeSubtitleFont(value)
        )

    var subtitleTextColor: String
        get() = PlayerPreferencesRules.normalizeSubtitleTextColor(
            store.getString(PlayerPreferencesRules.KEY_SUBTITLES_TEXT_COLOR, PlayerPreferencesRules.DEFAULT_SUBTITLES_TEXT_COLOR)
        )
        set(value) = store.putString(
            PlayerPreferencesRules.KEY_SUBTITLES_TEXT_COLOR,
            PlayerPreferencesRules.normalizeSubtitleTextColor(value)
        )

    var subtitleBackgroundColor: String
        get() = PlayerPreferencesRules.normalizeSubtitleBackgroundColor(
            store.getString(PlayerPreferencesRules.KEY_SUBTITLES_BACKGROUND_COLOR, PlayerPreferencesRules.DEFAULT_SUBTITLES_BACKGROUND_COLOR)
        )
        set(value) = store.putString(
            PlayerPreferencesRules.KEY_SUBTITLES_BACKGROUND_COLOR,
            PlayerPreferencesRules.normalizeSubtitleBackgroundColor(value)
        )

    var subtitleBackgroundOpacity: Float
        get() {
            val raw = if (store.contains(PlayerPreferencesRules.KEY_SUBTITLES_BACKGROUND_OPACITY)) {
                store.getFloat(PlayerPreferencesRules.KEY_SUBTITLES_BACKGROUND_OPACITY, PlayerPreferencesRules.DEFAULT_SUBTITLES_BACKGROUND_OPACITY)
            } else null
            return PlayerPreferencesRules.normalizeSubtitleBackgroundOpacity(raw)
        }
        set(value) = store.putFloat(
            PlayerPreferencesRules.KEY_SUBTITLES_BACKGROUND_OPACITY,
            PlayerPreferencesRules.normalizeSubtitleBackgroundOpacity(value)
        )

    fun resetSubtitleAppearance() {
        subtitleFont = PlayerPreferencesRules.DEFAULT_SUBTITLES_FONT
        subtitleTextColor = PlayerPreferencesRules.DEFAULT_SUBTITLES_TEXT_COLOR
        subtitleBackgroundColor = PlayerPreferencesRules.DEFAULT_SUBTITLES_BACKGROUND_COLOR
        subtitleBackgroundOpacity = PlayerPreferencesRules.DEFAULT_SUBTITLES_BACKGROUND_OPACITY
    }

    // Audio enhancement
    var audioEnhancementProfile: String
        get() = PlayerPreferencesRules.normalizeAudioProfile(
            store.getString(PlayerPreferencesRules.KEY_AUDIO_ENHANCEMENT_PROFILE, PlayerPreferencesRules.DEFAULT_AUDIO_PROFILE)
        )
        set(value) = store.putString(
            PlayerPreferencesRules.KEY_AUDIO_ENHANCEMENT_PROFILE,
            PlayerPreferencesRules.normalizeAudioProfile(value)
        )

    var audioEnhancementPreamp: Float
        get() {
            val raw = if (store.contains(PlayerPreferencesRules.KEY_AUDIO_ENHANCEMENT_PREAMP)) {
                store.getFloat(PlayerPreferencesRules.KEY_AUDIO_ENHANCEMENT_PREAMP, PlayerPreferencesRules.DEFAULT_AUDIO_PREAMP)
            } else null
            return PlayerPreferencesRules.normalizeAudioPreamp(raw)
        }
        set(value) = store.putFloat(
            PlayerPreferencesRules.KEY_AUDIO_ENHANCEMENT_PREAMP,
            PlayerPreferencesRules.normalizeAudioPreamp(value)
        )

    var audioBoosterEnabled: Boolean
        get() = store.getBoolean(PlayerPreferencesRules.KEY_AUDIO_BOOSTER_ENABLED, false)
        set(value) = store.putBoolean(PlayerPreferencesRules.KEY_AUDIO_BOOSTER_ENABLED, value)
}
