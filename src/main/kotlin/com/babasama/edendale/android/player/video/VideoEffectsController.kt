package com.babasama.edendale.android.player.video

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Enhancement settings for the app process (D11): Apple starts each playback
 * at Balanced, Sharpness 0.5, Denoise 0.5, and never saves changes. Android
 * starts at Off where the capability check failed, and on TV until F.1.6
 * passes on a real set.
 */
object EnhancementSession {
    @Volatile var settings: EnhancementSettings? = null

    fun current(defaultPreset: EnhancementPreset): EnhancementSettings =
        settings ?: EnhancementSettings(preset = defaultPreset).also { settings = it }
}

/**
 * The player's side of Picture and Enhancement (F.1, F.6): installs Media3's
 * effects pipeline only when something needs it, re-prepares at the current
 * position when that happens mid-play, redraws the paused frame after a
 * change, bypasses HDR and Dolby Vision, sizes the output to the display, and
 * runs the GPU budget governor. Main thread only.
 */
@OptIn(UnstableApi::class)
class VideoEffectsController(
    private val context: Context,
    private val isTelevision: Boolean,
    /** Stops and prepares the current item again at its position (the effects pipeline is set up at prepare). */
    private val reprepare: () -> Unit,
    /** Saves picture adjustments (device-local, `video.adjustments`). */
    private val saveAdjustments: (VideoAdjustmentValues) -> Unit,
) {
    val holder = VideoEffectsHolder()
    private val governor = EnhancementGovernor()
    private var player: ExoPlayer? = null
    private var installed by mutableStateOf(false)
    private var installedOutputPolicy: Triple<Boolean, PixelSize, Boolean>? = null
    private var lastDroppedFrames = 0

    // Compose state for the panel (F.2.3, F.7).
    var adjustments by mutableStateOf(VideoAdjustmentValues.NEUTRAL)
        private set
    var pictureShowOriginal by mutableStateOf(false)
        private set
    var enhancement by mutableStateOf(EnhancementSettings(preset = EnhancementPreset.OFF))
        private set
    var isHdr by mutableStateOf(false)
        private set
    var sourceSize by mutableStateOf<PixelSize?>(null)
        private set
    var targetSize by mutableStateOf<PixelSize?>(null)
        private set

    /**
     * The surface buffer size on TV while the effects path upscales (F.1.3),
     * so a 4K target isn't scaled back down to a 1080p interface; else null.
     */
    val fixedSurfaceSize: PixelSize?
        get() {
            val target = targetSize ?: return null
            return target.takeIf { isTelevision && installed && target != sourceSize }
        }

    fun attach(player: ExoPlayer, storedAdjustments: VideoAdjustmentValues) {
        this.player = player
        adjustments = storedAdjustments
        holder.adjustments = storedAdjustments
        val capable = EnhancementCapability.cachedResult(context) == true
        val defaultPreset = if (isTelevision || !capable) EnhancementPreset.OFF else EnhancementPreset.BALANCED
        enhancement = EnhancementSession.current(defaultPreset)
        holder.enhancement = enhancement
    }

    /** Before the first prepare of an item: install the pipeline if anything needs it (F.1.1). */
    fun beforePrepare() {
        val exo = player ?: return
        holder.historyGeneration++
        if (!installed && holder.needsEffects) {
            installed = true
            exo.setVideoEffects(listOf(EnhancementEffect(holder)))
            installedOutputPolicy = outputPolicy()
        }
    }

    fun setAdjustment(adjustment: VideoAdjustment, value: Float) = updateAdjustments(adjustments.with(adjustment, value))

    fun resetAdjustments() = updateAdjustments(VideoAdjustmentValues.NEUTRAL)

    private fun updateAdjustments(values: VideoAdjustmentValues) {
        adjustments = values
        saveAdjustments(values)
        holder.adjustments = if (pictureShowOriginal) VideoAdjustmentValues.NEUTRAL else values
        apply()
    }

    /** Show Original for Picture: neutral values on screen, the stored ones untouched. */
    fun showPictureOriginal(on: Boolean) {
        pictureShowOriginal = on
        holder.adjustments = if (on) VideoAdjustmentValues.NEUTRAL else adjustments
        apply()
    }

    fun setPreset(preset: EnhancementPreset) {
        if (preset != enhancement.preset) holder.historyGeneration++
        updateEnhancement(enhancement.copy(preset = preset))
    }

    fun setSharpness(value: Float) = updateEnhancement(enhancement.withSharpness(value))

    fun setDenoise(value: Float) = updateEnhancement(enhancement.withDenoise(value))

    fun showEnhancementOriginal(on: Boolean) = updateEnhancement(enhancement.copy(showOriginal = on))

    private fun updateEnhancement(settings: EnhancementSettings) {
        enhancement = settings
        // Show Original is momentary; the session keeps the real choice.
        EnhancementSession.settings = settings.copy(showOriginal = false)
        holder.enhancement = settings
        apply()
    }

    /** The selected video track changed: HDR or Dolby Vision bypasses every effect (F.1.4). */
    fun onTracksChanged(tracks: Tracks) {
        val format = tracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
            ?.let { group -> (0 until group.length).firstOrNull(group::isTrackSelected)?.let(group::getTrackFormat) }
            ?: return
        val hdr = isHdr(format)
        sourceSize = PixelSize(format.width, format.height).takeUnless { it.isEmpty }
        if (hdr != holder.isHdr) {
            holder.isHdr = hdr
            isHdr = hdr
        }
        refreshTarget()
        apply()
    }

    /** The video's on-screen viewport in physical pixels changed (rotation, window resize). */
    fun onDisplaySizeChanged(display: PixelSize) {
        if (display.isEmpty || display == holder.display) return
        holder.display = display
        refreshTarget()
        apply()
    }

    /**
     * About once a second while playing: feed the governor GPU times (or the
     * dropped-frame rate where timer queries aren't available), thermal
     * status, and battery saver (F.6.2).
     */
    fun onTick() {
        val exo = player ?: return
        if (!installed) return
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            (power?.currentThermalStatus ?: 0) >= PowerManager.THERMAL_STATUS_MODERATE
        governor.setPressure(thermalModerateOrWorse = thermal, batterySaver = power?.isPowerSaveMode == true)
        val samples = holder.drainGpuMillis()
        if (samples.isNotEmpty()) {
            samples.forEach { governor.record(it) }
        } else {
            val dropped = exo.videoDecoderCounters?.droppedBufferCount ?: 0
            val delta = (dropped - lastDroppedFrames).coerceAtLeast(0)
            lastDroppedFrames = dropped
            if (exo.isPlaying) {
                // No timer queries: a dropped frame reads as over budget, a clean second as well under it.
                governor.record(if (delta > 0) EnhancementGovernor.BUDGET_MILLIS * 1.5 else EnhancementGovernor.BUDGET_MILLIS * 0.3)
            }
        }
        val upscale = governor.allowsUpscale
        holder.allowsDenoise = governor.allowsDenoise
        if (upscale != holder.allowsUpscale) {
            holder.allowsUpscale = upscale
            refreshTarget()
            apply()
        }
    }

    /** After an item switch: the next item starts with fresh history and no HDR verdict yet. */
    fun onItemChanged() {
        holder.historyGeneration++
        holder.isHdr = false
        isHdr = false
        sourceSize = null
        targetSize = null
    }

    private fun refreshTarget() {
        val source = sourceSize ?: return
        targetSize = holder.outputSizeFor(source)
    }

    /** What decides the effect's configured output size. */
    private fun outputPolicy() = Triple(holder.enhancement.preset.upscales && holder.allowsUpscale, holder.display, holder.isHdr)

    /**
     * Makes the pipeline match the settings: install it (re-preparing when
     * playback already started), install a new effect when the output size
     * policy changed, and redraw the paused frame.
     */
    private fun apply() {
        val exo = player ?: return
        if (!installed) {
            if (!holder.needsEffects) return
            installed = true
            exo.setVideoEffects(listOf(EnhancementEffect(holder)))
            installedOutputPolicy = outputPolicy()
            if (exo.playbackState != androidx.media3.common.Player.STATE_IDLE) reprepare()
            return
        }
        val policy = outputPolicy()
        if (policy != installedOutputPolicy) {
            installedOutputPolicy = policy
            exo.setVideoEffects(listOf(EnhancementEffect(holder)))
        }
        if (!exo.isPlaying) exo.setVideoEffects(VideoFrameProcessor.REDRAW)
    }

    companion object {
        /** HDR10, HLG, and Dolby Vision by their transfer function or MIME type. */
        fun isHdr(format: Format): Boolean {
            val transfer = format.colorInfo?.colorTransfer
            return transfer == C.COLOR_TRANSFER_ST2084 ||
                transfer == C.COLOR_TRANSFER_HLG ||
                format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
        }
    }
}
