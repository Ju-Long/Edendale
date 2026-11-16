package com.babasama.edendale.android.player

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.media3.common.Player
import kotlin.math.roundToInt

/**
 * Step math for the device's media volume, which Android keeps as a whole
 * index between the stream's minimum and maximum (0–15 on most phones, finer
 * on some). Pure, so the JVM suite covers it.
 */
object VolumeSteps {

    /**
     * The 0…1 level an index shows as. A muted stream reads 0; a stream with
     * no steps (maximum at or below its minimum) can't be moved and reads full.
     */
    fun level(index: Int, min: Int, max: Int, muted: Boolean): Float {
        if (muted) return 0f
        if (max <= min) return 1f
        return ((index - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }

    /** The index nearest a 0…1 level, inside the stream's range. */
    fun index(level: Float, min: Int, max: Int): Int {
        if (max <= min) return min
        val clamped = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        return (min + (clamped * (max - min)).roundToInt()).coerceIn(min, max)
    }

    /**
     * A raise that came back lower than asked for, where only asking again
     * brings up Android's hearing warning. With headphones in, Android holds
     * the media volume at its safe level until the listener accepts that
     * warning. Before Android 12 it showed the warning only to a request made
     * with `FLAG_SHOW_UI`, so such a raise is worth asking once more that way.
     * From Android 12 the system adds the flag to the warning itself
     * (`AudioService.VolumeController.postDisplaySafeVolumeWarning`): the
     * quiet write has already brought it up, and asking again would only open
     * Android's own volume panel over the player.
     */
    fun needsWarningRetry(before: Int, requested: Int, after: Int, sdkInt: Int): Boolean =
        sdkInt < ANDROID_S && requested > before && after < requested

    private const val ANDROID_S = 31
}

/** The media stream as [DeviceVolume] needs it: AudioManager on a device, a fake in the JVM tests. */
internal interface MusicStream {
    /** True where the device holds its volume fixed (some HDMI and USB outputs, some TVs). */
    val isFixed: Boolean

    /** The stream now, or null when the device won't say. */
    fun read(): MusicStreamState?

    /**
     * Asks for [index]; [showUi] lets Android show its own volume panel and,
     * before Android 12, its hearing warning.
     */
    fun write(index: Int, showUi: Boolean)

    fun unmute()
}

internal data class MusicStreamState(val index: Int, val min: Int, val max: Int, val muted: Boolean) {
    /** The index as heard: a muted stream sits at its minimum. */
    val heardIndex: Int get() = if (muted) min else index

    val level: Float get() = VolumeSteps.level(index, min, max, muted)

    val isAdjustable: Boolean get() = max > min
}

/**
 * The right-hand swipe's level: the device's media volume, the one Android's
 * own volume panel and the hardware keys move. The swipe used to change the
 * player's gain instead, which left the device volume where it was, started
 * every swipe at 100%, and compounded with the device volume.
 *
 * Writes go without Android's panel (`flags = 0`) because the player shows
 * its own pill, and what the pill shows is the stream read back, so a step
 * the system refused never looks taken. Only before Android 12 does a raise
 * the hearing check held back ask once more with Android's UI, since that
 * was the only way its warning showed ([VolumeSteps.needsWarningRetry]). A
 * swipe writes only when it crosses into another step. The player's gain
 * stands in only where the stream can't move: a fixed-volume device, or a
 * route that keeps the stream at its maximum (a lowering there reads back
 * unchanged). Everywhere else the gain stays at 1, and goes back to 1 once
 * the stream moves again. Main thread only, like the player.
 */
internal class DeviceVolume(
    private val stream: MusicStream,
    private val readGain: () -> Float,
    private val writeGain: (Float) -> Unit,
    private val sdkInt: Int,
) : PlayerLevelControl {

    constructor(context: Context, player: Player) : this(
        stream = AudioManagerMusicStream(context),
        readGain = { player.volume },
        writeGain = { player.volume = it },
        sdkInt = Build.VERSION.SDK_INT,
    )

    /** The stream as last read; null while the player's gain stands in. */
    private var state: MusicStreamState? = null

    /** A lowering at the maximum didn't take: this route holds the stream there. */
    private var pinnedAtMax = false

    /** The step this swipe last asked for, so moving within one step writes nothing. */
    private var requested: Int? = null

    /** Whether this swipe has already asked once with Android's warning allowed. */
    private var retried = false

    /** Each swipe reads this once as it begins, which also gives the swipe its one warning retry. */
    override fun current(): Float {
        requested = null
        retried = false
        val now = stream.read()
        // Off its maximum, the stream evidently moves (another route, or the keys).
        if (now != null && now.index < now.max) pinnedAtMax = false
        if (now == null || !now.isAdjustable || stream.isFixed || pinnedAtMax) {
            state = null
            return unit(readGain())
        }
        // The device volume governs: undo any gain a fallback left lowered.
        if (readGain() != 1f) writeGain(1f)
        state = now
        return now.level
    }

    override fun set(level: Float): Float {
        val before = state ?: return useGain(level)
        val target = VolumeSteps.index(level, before.min, before.max)
        if (target == requested) return before.level
        requested = target
        if (target == before.heardIndex) return before.level

        stream.write(target, showUi = false)
        var after = stream.read() ?: before
        // A non-zero index unmutes on stock Android; this covers devices where it doesn't.
        if (target > 0 && after.muted) {
            stream.unmute()
            after = stream.read() ?: after
        }
        if (!retried && VolumeSteps.needsWarningRetry(before.heardIndex, target, after.heardIndex, sdkInt)) {
            retried = true
            stream.write(target, showUi = true)
            after = stream.read() ?: after
        }
        if (target < before.heardIndex && before.heardIndex == before.max && after.heardIndex == before.max) {
            pinnedAtMax = true
            state = null
            return useGain(level)
        }
        state = after
        return after.level
    }

    private fun useGain(level: Float): Float {
        val gain = unit(level)
        writeGain(gain)
        return gain
    }

    private fun unit(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)
}

/**
 * STREAM_MUSIC through AudioManager. Every call is guarded: some devices throw
 * from the getters, and Do Not Disturb can refuse a write with a
 * SecurityException.
 */
private class AudioManagerMusicStream(context: Context) : MusicStream {
    private val audio: AudioManager? = context.getSystemService(AudioManager::class.java)

    override val isFixed: Boolean
        get() = runCatching { audio?.isVolumeFixed == true }.getOrDefault(false)

    override fun read(): MusicStreamState? {
        val manager = audio ?: return null
        return runCatching {
            MusicStreamState(
                index = manager.getStreamVolume(STREAM),
                min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.getStreamMinVolume(STREAM) else 0,
                max = manager.getStreamMaxVolume(STREAM),
                muted = manager.isStreamMute(STREAM),
            )
        }.getOrNull()
    }

    override fun write(index: Int, showUi: Boolean) {
        runCatching { audio?.setStreamVolume(STREAM, index, if (showUi) AudioManager.FLAG_SHOW_UI else 0) }
    }

    override fun unmute() {
        runCatching { audio?.adjustStreamVolume(STREAM, AudioManager.ADJUST_UNMUTE, 0) }
    }

    private companion object {
        const val STREAM = AudioManager.STREAM_MUSIC
    }
}
