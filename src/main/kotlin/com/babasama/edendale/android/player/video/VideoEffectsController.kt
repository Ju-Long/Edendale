package com.babasama.edendale.android.player.video

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The player's side of Picture and Enhancement (F.1, F.6): installs Media3's
 * effects pipeline only when something needs it, re-prepares at the current
 * position when that happens mid-play, redraws the paused frame after a
 * change, bypasses HDR and Dolby Vision, sizes the output to the display, and
 * runs the GPU budget governor. It also decides Motion Smoothing (G.4, G.5):
 * whether this video is offered it, whether it runs right now, and the
 * effect that makes the frames. Main thread only.
 */
@OptIn(UnstableApi::class)
class VideoEffectsController(
    private val context: Context,
    private val isTelevision: Boolean,
    /** Stops and prepares the current item again at its position (the effects pipeline is set up at prepare). */
    private val reprepare: () -> Unit,
    /** Saves picture adjustments (device-local, `video.adjustments`). */
    private val saveAdjustments: (VideoAdjustmentValues) -> Unit,
    /** Saves the viewer's enhancement choice (device-local, `video.enhancement`); never Show Original. */
    private val saveEnhancement: (EnhancementSettings) -> Unit = {},
) {
    val holder = VideoEffectsHolder()
    private val governor = EnhancementGovernor()
    private var player: ExoPlayer? = null
    private var installed by mutableStateOf(false)
    private var installedOutputPolicy: Triple<Boolean, PixelSize, Boolean>? = null
    private var installedMotionSmoothing = false
    private var lastDroppedFrames = 0

    /** The selected video track's frame rate, when its container says (MP4 does, Matroska doesn't). */
    private var containerFrameRate: Float? = null

    /** Otherwise measured from the first frames shown, before any midpoint exists. */
    private val frameRateEstimator = FrameRateEstimator()
    private var measuredFrameRate: Float? = null

    /** Shown frames' timestamps from the playback thread, while [measuring]. */
    private val shownFrameTimes = ConcurrentLinkedQueue<Long>()
    @Volatile private var measuring = false
    private var playbackSpeed = 1f

    private val sourceFrameRate: Float? get() = containerFrameRate ?: measuredFrameRate

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

    /** Motion Smoothing for this video and display, or null when it isn't offered (G.5.1). */
    var motionSmoothingOffer by mutableStateOf<MotionSmoothingOffer?>(null)
        private set

    /** Why Motion Smoothing, switched on, isn't running right now; null when it runs or is off. */
    var motionSmoothingPause by mutableStateOf<MotionSmoothingPause?>(null)
        private set

    /** The selected video track's size as shown: rotation and pixel shape applied. */
    private var trackVideoSize by mutableStateOf<VideoSize?>(null)

    /**
     * The size to lay the picture out with while the effects pipeline is
     * installed; null on the direct path, where ExoPlayer reports it. Media3
     * 1.9.0 drops size reports from its video sink (b/292111083), so the
     * player says 0 × 0 there: without this, Fit and Fill look the same, cues
     * use the whole screen, and PiP is always 16:9.
     */
    val effectsVideoSize: VideoSize?
        get() = trackVideoSize.takeIf { installed }

    /**
     * The surface buffer size on TV while the effects path upscales (F.1.3),
     * so a 4K target isn't scaled back down to a 1080p interface; else null.
     */
    val fixedSurfaceSize: PixelSize?
        get() {
            val target = targetSize ?: return null
            return target.takeIf { isTelevision && installed && target != sourceSize }
        }

    /**
     * [storedEnhancement] is the viewer's saved choice, or null to start at the
     * device's default. The default is worked out for each player, so the
     * first one after an install or update (before the capability check has
     * run) no longer holds Off for the rest of the process.
     */
    fun attach(player: ExoPlayer, storedAdjustments: VideoAdjustmentValues, storedEnhancement: EnhancementSettings? = null) {
        this.player = player
        adjustments = storedAdjustments
        holder.adjustments = storedAdjustments
        enhancement = EnhancementSettings.initial(
            stored = storedEnhancement,
            isTelevision = isTelevision,
            capable = EnhancementCapability.cachedResult(context),
        )
        holder.enhancement = enhancement
        player.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                playbackSpeed = playbackParameters.speed
                refreshMotionSmoothing()
            }
        })
        player.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            if (measuring) shownFrameTimes.add(presentationTimeUs)
        }
    }

    /** Before the first prepare of an item: install the pipeline if anything needs it (F.1.1). */
    fun beforePrepare() {
        val exo = player ?: return
        holder.historyGeneration++
        if (!installed && holder.needsEffects) {
            installed = true
            install(exo)
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
        updateEnhancement(enhancement.copy(preset = preset), save = true)
    }

    fun setSharpness(value: Float) = updateEnhancement(enhancement.withSharpness(value), save = true)

    fun setDenoise(value: Float) = updateEnhancement(enhancement.withDenoise(value), save = true)

    /** Momentary: compares against the untouched picture without saving anything. */
    fun showEnhancementOriginal(on: Boolean) = updateEnhancement(enhancement.copy(showOriginal = on), save = false)

    /** The Motion Smoothing switch (G.5.1), saved with the rest of the enhancement choice. */
    fun setMotionSmoothing(on: Boolean) = updateEnhancement(enhancement.copy(motionSmoothing = on), save = true)

    private fun updateEnhancement(settings: EnhancementSettings, save: Boolean) {
        enhancement = settings
        if (save) saveEnhancement(settings.copy(showOriginal = false))
        holder.enhancement = settings
        refreshMotionSmoothing()
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
        trackVideoSize = displayVideoSize(format.width, format.height, format.rotationDegrees, format.pixelWidthHeightRatio)
        containerFrameRate = format.frameRate.takeIf { it != Format.NO_VALUE.toFloat() && it > 0f }
        if (hdr != holder.isHdr) {
            holder.isHdr = hdr
            isHdr = hdr
        }
        refreshTarget()
        refreshMotionSmoothing()
        apply()
    }

    /** The video's on-screen viewport in physical pixels changed (rotation, window resize). */
    fun onDisplaySizeChanged(display: PixelSize) {
        if (display.isEmpty || display == holder.display) return
        holder.display = display
        refreshTarget()
        // A foldable's other screen may have other refresh rates.
        refreshMotionSmoothing()
        apply()
    }

    /**
     * About once a second while playing: feed the governor GPU times (or the
     * dropped-frame rate where timer queries aren't available), thermal
     * status, and battery saver (F.6.2).
     */
    fun onTick() {
        val exo = player ?: return
        measureFrameRate()
        if (!installed) {
            // Nothing to govern, but the Motion Smoothing switch may become available (the capability verdict).
            refreshMotionSmoothing()
            return
        }
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
        // After a step the rest of the batch was timed before the GL thread saw it.
        for (millis in holder.drainMotionSmoothingMillis()) if (governor.recordMotionSmoothing(millis)) break
        val upscale = governor.allowsUpscale
        holder.allowsDenoise = governor.allowsDenoise
        // Also picks up the capability verdict once its first run finishes, a measured frame rate, and a GPU
        // that can't build the passes.
        refreshMotionSmoothing()
        if (upscale != holder.allowsUpscale) {
            holder.allowsUpscale = upscale
            refreshTarget()
            apply()
        } else if (wantsMotionSmoothingEffect() && !installedMotionSmoothing) {
            // Wanted and not yet in the chain (offered just now, by whichever call noticed first): add it.
            // One no longer wanted just stops running until the next change, so the gap between two
            // videos doesn't rebuild the chain twice.
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
        trackVideoSize = null
        containerFrameRate = null
        measuredFrameRate = null
        frameRateEstimator.reset()
        shownFrameTimes.clear()
        governor.resetMotionSmoothing()
        refreshMotionSmoothing()
    }

    /**
     * Feeds the frames shown since the last tick to the estimator, while the
     * container gave no rate and no midpoint is being made; the first
     * estimate holds for the rest of the video.
     */
    private fun measureFrameRate() {
        while (true) {
            val time = shownFrameTimes.poll() ?: break
            if (measuredFrameRate == null) frameRateEstimator.add(time)
        }
        if (measuredFrameRate == null && containerFrameRate == null) {
            frameRateEstimator.fps?.let { fps ->
                measuredFrameRate = fps
                refreshMotionSmoothing()
            }
        }
    }

    /**
     * Works out Motion Smoothing for the current video, display, speed, and
     * governor (G.5): the offer the panel shows, why it's paused, and what
     * the GL thread runs. The effect itself is installed by [apply].
     */
    private fun refreshMotionSmoothing() {
        // Both checks: Enhancement's (F.6.3), and Motion Smoothing's own, which also says where to start.
        val searchCheck = MotionSmoothingCapability.cachedResult(context)
        val capable = EnhancementCapability.cachedResult(context) == true &&
            (searchCheck == MotionSmoothingCapability.Verdict.FULL || searchCheck == MotionSmoothingCapability.Verdict.HALF)
        if (searchCheck == MotionSmoothingCapability.Verdict.HALF) governor.requireHalfResolution()
        val offer = if (holder.motionSmoothingUnavailable) {
            null
        } else {
            MotionSmoothingRules.offer(
                sourceFps = sourceFrameRate,
                displayRates = displayRefreshRates(),
                isTelevision = isTelevision,
                isHdr = holder.isHdr,
                capable = capable,
            )
        }
        if (offer != motionSmoothingOffer) motionSmoothingOffer = offer
        val wanted = enhancement.motionSmoothing && offer != null
        val speedAllows = MotionSmoothingRules.runsAtSpeed(playbackSpeed)
        val governorAllows = governor.allowsMotionSmoothing
        val pause = when {
            !wanted -> null
            !speedAllows -> MotionSmoothingPause.SPEED
            !governorAllows -> MotionSmoothingPause.BUSY
            else -> null
        }
        if (pause != motionSmoothingPause) motionSmoothingPause = pause
        offer?.let { governor.motionSmoothingBudgetMillis = EnhancementGovernor.motionSmoothingBudgetFor(it.sourceFps) }
        val running = wanted && speedAllows && governorAllows
        // Only a running Motion Smoothing costs anything, so only then does it go before enhancement stages.
        governor.motionSmoothingActive = running
        holder.motionSmoothing = if (running) {
            MotionSmoothingRun(running = true, frameDurationUs = offer!!.frameDurationUs, halfResolution = governor.motionSmoothingAtHalfResolution)
        } else {
            MotionSmoothingRun.OFF
        }
        // Shown frames are real ones only while nothing is making midpoints.
        measuring = !running && sourceFrameRate == null
    }

    /** The refresh rates the display offers at its current resolution. */
    private fun displayRefreshRates(): List<Float> = runCatching {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display
        } else {
            context.getSystemService(WindowManager::class.java)?.defaultDisplay
        } ?: return emptyList()
        val current = display.mode
        display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .map { it.refreshRate }
    }.getOrDefault(emptyList())

    private fun refreshTarget() {
        val source = sourceSize ?: return
        targetSize = holder.outputSizeFor(source)
    }

    /** What decides the effect's configured output size. */
    private fun outputPolicy() = Triple(holder.enhancement.preset.upscales && holder.allowsUpscale, holder.display, holder.isHdr)

    /** Motion Smoothing's effect belongs in the chain: switched on and offered for this video. */
    private fun wantsMotionSmoothingEffect() = enhancement.motionSmoothing && motionSmoothingOffer != null

    /** Installs the effects for the current settings: enhancement, then Motion Smoothing when it's wanted. */
    private fun install(exo: ExoPlayer) {
        val motionSmoothing = wantsMotionSmoothingEffect()
        val effects = buildList<Effect> {
            add(EnhancementEffect(holder))
            if (motionSmoothing) add(MotionSmoothingEffect(holder))
        }
        exo.setVideoEffects(effects)
        installedOutputPolicy = outputPolicy()
        installedMotionSmoothing = motionSmoothing
    }

    /**
     * Makes the pipeline match the settings: install it (re-preparing when
     * playback already started), install new effects when the output size
     * policy changed or Motion Smoothing came or went, and redraw the paused
     * frame.
     */
    private fun apply() {
        val exo = player ?: return
        if (!installed) {
            if (!holder.needsEffects) return
            installed = true
            install(exo)
            if (exo.playbackState != Player.STATE_IDLE) reprepare()
            return
        }
        if (outputPolicy() != installedOutputPolicy || wantsMotionSmoothingEffect() != installedMotionSmoothing) {
            // A new chain: the output size changed, or Motion Smoothing came or went.
            install(exo)
        }
        if (!exo.isPlaying) exo.setVideoEffects(VideoFrameProcessor.REDRAW)
    }

    companion object {
        /**
         * A track's size as the screen shows it, the way ExoPlayer reports it
         * on the direct path: a quarter turn swaps the sides and inverts the
         * pixel shape. Null when the track gives no size.
         */
        fun displayVideoSize(width: Int, height: Int, rotationDegrees: Int, pixelRatio: Float): VideoSize? {
            if (width <= 0 || height <= 0) return null
            val ratio = pixelRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
            return if (rotationDegrees % 180 != 0) VideoSize(height, width, 1f / ratio) else VideoSize(width, height, ratio)
        }

        /** HDR10, HLG, and Dolby Vision by their transfer function or MIME type. */
        fun isHdr(format: Format): Boolean {
            val transfer = format.colorInfo?.colorTransfer
            return transfer == C.COLOR_TRANSFER_ST2084 ||
                transfer == C.COLOR_TRANSFER_HLG ||
                format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION
        }
    }
}
