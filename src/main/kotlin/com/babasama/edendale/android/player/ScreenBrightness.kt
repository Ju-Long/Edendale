package com.babasama.edendale.android.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Resources
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Android's own brightness slider scale. Since Android 9, SystemUI places a
 * brightness on its slider through a Hybrid Log-Gamma curve
 * (`BrightnessUtils.convertLinearToGammaFloat`), so the middle of the slider
 * is 1/12 of the panel's range, not half of it. A player swipe that maps
 * linearly starts far below where Quick Settings shows the brightness, and
 * its top half barely changes anything; on this scale the HUD starts where
 * Quick Settings is and moves the way its slider does. Pure.
 */
object BrightnessCurve {
    // SystemUI's HLG constants.
    private const val R = 0.5
    private const val A = 0.17883277
    private const val B = 0.28466892
    private const val C = 0.55991073

    private const val ANDROID_P = 28
    private const val ANDROID_R = 30

    /** Before Android 11 the window's value becomes an int setting, where 0 would mean no override at all. */
    private const val MIN_PRE_R_SETTING = 1

    /** The slider position (0…1) for a linear brightness (0…1 of the panel's range). */
    fun levelForLinear(linear: Float): Float {
        // HLG works on 0…12, so the curve's knee falls at 1/12.
        val scaled = unit(linear).toDouble() * 12
        val level = if (scaled <= 1.0) R * sqrt(scaled) else A * ln(scaled - B) + C
        return level.toFloat().coerceIn(0f, 1f)
    }

    /** The linear brightness (0…1 of the panel's range) at a slider position (0…1). */
    fun linearForLevel(level: Float): Float {
        val h = unit(level).toDouble()
        val scaled = if (h <= R) (h / R) * (h / R) else exp((h - C) / A) + B
        return (scaled / 12).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Where Android's own slider shows the `SCREEN_BRIGHTNESS` setting, 0…1.
     * Android 11 and later keep the setting as 1…255 for the panel's whole
     * float range (`BrightnessSynchronizer`); before that it runs over the
     * configured range. Android 8's slider was linear.
     */
    fun levelForSetting(setting: Int, range: BrightnessSettingRange, sdkInt: Int): Float {
        val scale = range.including(setting)
        val linear = if (sdkInt >= ANDROID_R) {
            (setting - 1f) / (scale.max - 1).coerceAtLeast(1)
        } else {
            (setting - scale.min).toFloat() / (scale.max - scale.min)
        }
        val l = unit(linear)
        return if (sdkInt >= ANDROID_P) levelForLinear(l) else l
    }

    /**
     * The window's `screenBrightness` for a slider position. Android 11 and
     * later read it as a brightness on the panel's float scale, where 0 is the
     * panel's minimum, never off. Before that it is multiplied by 255 and
     * truncated into the int setting (`(int) (value * 255)`), which the system
     * then clamps to the configured range, and an override of 0 counts as
     * none. So the value aims at the middle of the nearest step: through the
     * curve's float round trip, a setting can come back a hair under its whole
     * number and would truncate to the step below.
     */
    fun windowValueForLevel(level: Float, range: BrightnessSettingRange, sdkInt: Int): Float {
        val h = unit(level)
        val linear = if (sdkInt >= ANDROID_P) linearForLevel(h) else h
        if (sdkInt >= ANDROID_R) return linear
        val setting = (range.min + linear * (range.max - range.min)).roundToInt().coerceAtLeast(MIN_PRE_R_SETTING)
        return ((setting + 0.5f) / 255f).coerceAtMost(1f)
    }

    private fun unit(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
}

/**
 * The range of the `SCREEN_BRIGHTNESS` setting: the framework's configured
 * minimum and maximum (`config_screenBrightnessSettingMinimum` and
 * `…Maximum`), 1 and 255 where a device doesn't say.
 */
data class BrightnessSettingRange(val min: Int, val max: Int) {

    /**
     * Widened to hold [setting]: some manufacturers keep the setting on a
     * 2047- or 4095-step scale, so a value past the maximum means the scale
     * runs to the next 2^n − 1.
     */
    fun including(setting: Int): BrightnessSettingRange {
        if (setting <= max) return this
        val widened = Integer.highestOneBit(setting).toLong() * 2 - 1
        return copy(max = widened.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    companion object {
        val DEFAULT = BrightnessSettingRange(min = 1, max = 255)

        /** The configured range, with the default for any end that is missing or makes no range. */
        fun of(min: Int?, max: Int?): BrightnessSettingRange {
            val low = min?.takeIf { it >= 0 } ?: DEFAULT.min
            val high = max?.takeIf { it > low } ?: DEFAULT.max.coerceAtLeast(low + 1)
            return BrightnessSettingRange(low, high)
        }
    }
}

/**
 * Whether the player's brightness comes back with the player (Apple's rule):
 * yes, unless the viewer changed the device's brightness while it was away.
 * With adaptive brightness on, the setting moves by itself, so a change says
 * nothing about the viewer and the player's level stays.
 */
object BrightnessResumeRules {
    fun keepsPlayerLevel(automatic: Boolean, settingAtSuspend: Int, settingNow: Int): Boolean =
        automatic || settingAtSuspend == settingNow
}

/**
 * The left-hand swipe's level: the screen's brightness on Android's own
 * slider scale, for the player only. It is a window override, as Settings'
 * documentation asks apps to use (writing the system setting would need
 * WRITE_SETTINGS and outlive the player), and it belongs to the player the way
 * Apple's `ScreenBrightness` does:
 *
 * - every player starts at the device's brightness, and the level lasts
 *   through episode switches;
 * - [suspend] hands the screen back whenever the player isn't the activity
 *   in front (another app, split screen, Picture in Picture, where a window
 *   override would set the whole display's brightness), and [resume] brings
 *   the player's level back unless the viewer changed the brightness
 *   meanwhile ([BrightnessResumeRules]);
 * - moving Android's own slider while the player is up hands the screen back
 *   too: the override would otherwise hide the change.
 *
 * Main thread only.
 */
internal class PlayerScreenBrightness(private val activity: Activity) : PlayerLevelControl {

    /** The player's level on the slider scale, or null while the screen follows the device. */
    var level: Float? = null
        private set

    private val range: BrightnessSettingRange by lazy { configuredRange() }
    private var suspended = false
    private var settingAtSuspend = UNREADABLE
    private var automaticAtSuspend = false

    /** When the override was last written; the setting's echo of that write isn't the viewer's. */
    private var appliedAtMillis = 0L
    private var observing = false

    private val viewerChanges = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            if (SystemClock.uptimeMillis() - appliedAtMillis < OWN_CHANGE_MILLIS) return
            // The viewer moved Android's own slider: the screen follows it from here.
            level = null
            stopObserving()
            writeOverride(null)
        }
    }

    override fun current(): Float =
        level ?: readSetting()?.let { BrightnessCurve.levelForSetting(it, range, Build.VERSION.SDK_INT) } ?: 0.5f

    override fun set(level: Float): Float {
        val clamped = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        this.level = clamped
        if (!suspended) apply(clamped)
        return clamped
    }

    /** The player isn't in front: the screen goes back to the device's brightness. Idempotent. */
    fun suspend() {
        if (suspended) return
        suspended = true
        settingAtSuspend = readSetting() ?: UNREADABLE
        automaticAtSuspend = isAutomatic()
        stopObserving()
        if (level != null) writeOverride(null)
    }

    /** The player is in front again: its level comes back unless the viewer changed the brightness. Idempotent. */
    fun resume() {
        if (!suspended) return
        suspended = false
        val held = level ?: return
        val keeps = BrightnessResumeRules.keepsPlayerLevel(
            automatic = automaticAtSuspend && isAutomatic(),
            settingAtSuspend = settingAtSuspend,
            settingNow = readSetting() ?: UNREADABLE,
        )
        if (keeps) apply(held) else level = null
    }

    /** The player is closing. */
    fun release() {
        stopObserving()
        level = null
        writeOverride(null)
    }

    private fun apply(level: Float) {
        appliedAtMillis = SystemClock.uptimeMillis()
        writeOverride(BrightnessCurve.windowValueForLevel(level, range, Build.VERSION.SDK_INT))
        startObserving()
    }

    private fun writeOverride(value: Float?) {
        val window = activity.window ?: return
        val attributes = window.attributes
        attributes.screenBrightness = value ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        // The reassignment is what applies it.
        window.attributes = attributes
    }

    private fun startObserving() {
        if (observing) return
        observing = runCatching {
            activity.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                viewerChanges,
            )
        }.isSuccess
    }

    private fun stopObserving() {
        if (!observing) return
        observing = false
        runCatching { activity.contentResolver.unregisterContentObserver(viewerChanges) }
    }

    private fun readSetting(): Int? = runCatching {
        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrNull()

    private fun isAutomatic(): Boolean = runCatching {
        Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
    }.getOrDefault(false)

    /** The framework's own range, looked up by name because it isn't public. */
    @SuppressLint("DiscouragedApi")
    private fun configuredRange(): BrightnessSettingRange {
        val system = Resources.getSystem()
        fun integer(name: String): Int? = runCatching {
            system.getIdentifier(name, "integer", "android").takeIf { it != 0 }?.let(system::getInteger)
        }.getOrNull()
        return BrightnessSettingRange.of(
            min = integer("config_screenBrightnessSettingMinimum"),
            max = integer("config_screenBrightnessSettingMaximum"),
        )
    }

    private companion object {
        /** How long after a write a change to the setting is taken as the system's, not the viewer's. */
        const val OWN_CHANGE_MILLIS = 1_000L
        const val UNREADABLE = -1
    }
}
