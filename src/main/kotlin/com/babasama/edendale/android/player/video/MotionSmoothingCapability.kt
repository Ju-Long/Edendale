package com.babasama.edendale.android.player.video

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.content.pm.PackageInfoCompat
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi

/**
 * Whether this device can make Motion Smoothing's frames in time (G.5, the
 * owner's choice on 2026-11-12): a short offscreen run of the real passes on
 * 1080p frames, at full and at half search resolution, once per app
 * version. Motion Smoothing is offered only where one of them makes a frame
 * within the governor's budget for the strictest case it's offered for, a
 * 30 fps source (13.3 ms), and starts at the resolution that did, so a phone
 * that can't keep up never plays a stuttering video while the governor
 * steps down. The result stays on the
 * device; nothing about performance leaves it.
 */
object MotionSmoothingCapability {
    private const val PREFS = "player"
    private const val KEY = "video.motionSmoothingCapability"

    /** The budget at 30 fps, the fastest source Motion Smoothing is offered for, with a 1080p frame. */
    val CHECK_BUDGET_MILLIS: Double = EnhancementGovernor.motionSmoothingBudgetFor(MotionSmoothingRules.MAX_SOURCE_FPS)

    /** What the check found. */
    enum class Verdict(val raw: String) {
        /** In budget with the full-resolution search. */
        FULL("full"),

        /** In budget only with the search at half resolution. */
        HALF("half"),

        /** Neither; Motion Smoothing isn't offered. */
        NONE("none"),
    }

    /** The verdict for timings of one synthetic 1080p frame; a null time means that run failed. Pure. */
    fun verdict(fullMillis: Double?, halfMillis: Double?, budgetMillis: Double = CHECK_BUDGET_MILLIS): Verdict =
        when {
            fullMillis != null && fullMillis.isFinite() && fullMillis <= budgetMillis -> Verdict.FULL
            halfMillis != null && halfMillis.isFinite() && halfMillis <= budgetMillis -> Verdict.HALF
            else -> Verdict.NONE
        }

    /** The stored verdict for this app version, or null when the check hasn't run. */
    fun cachedResult(context: Context): Verdict? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val (raw, version) = stored.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
        if (version != appVersion(context)) return null
        return Verdict.entries.firstOrNull { it.raw == raw }
    }

    /**
     * Runs the check when there's no verdict yet, only where Motion Smoothing
     * could be offered at all: not on TV, and after Enhancement's own check
     * passed. Blocking GPU work: call off the main thread, after
     * [EnhancementCapability.evaluateIfNeeded].
     */
    fun evaluateIfNeeded(context: Context, isTelevision: Boolean) {
        if (isTelevision || cachedResult(context) != null) return
        if (EnhancementCapability.cachedResult(context) != true) return
        val verdict = runCatching { evaluate(context) }.getOrDefault(Verdict.NONE)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, "${verdict.raw}:${appVersion(context)}")
            .apply()
    }

    private fun evaluate(context: Context): Verdict {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return Verdict.NONE
        if (manager.isLowRamDevice || manager.deviceConfigurationInfo.reqGlEsVersion < 0x30000) return Verdict.NONE
        val full = benchmarkMillis(context, halfResolution = false)
        // A device far over budget at full resolution still gets its half-resolution run.
        val half = if (verdict(full, null) == Verdict.FULL) null else benchmarkMillis(context, halfResolution = true)
        return verdict(full, half)
    }

    /**
     * The median time of one synthetic 1080p frame (the real frame copied in,
     * its luma, the search, the cut count, the warp, and the real frame drawn
     * out), measured offscreen, or null when GL fails.
     */
    @OptIn(UnstableApi::class)
    fun benchmarkMillis(context: Context, halfResolution: Boolean, frames: Int = 14): Double? {
        val display = GlUtil.getDefaultEglDisplay()
        val eglContext = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        val surface = GlUtil.createFocusedPlaceholderEglSurface(eglContext, display)
        try {
            if (!isGles3()) return null
            val size = PixelSize(1920, 1080)
            // The passes do the same work whatever the pictures hold.
            val inputs = List(2) { GlUtil.createTexture(size.width, size.height, false) }
            val output = RenderTarget(size.width, size.height)
            val passes = MotionSmoothingPasses(context)
            try {
                val times = (0 until frames).mapNotNull { i ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    passes.ingest(inputs[i % 2], size, halfResolution)
                    val synthesized = passes.hasPrevious
                    if (synthesized) passes.synthesize(output.fboId)
                    passes.drawCurrent(output.fboId)
                    passes.commit()
                    GLES20.glFinish()
                    ((SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0).takeIf { synthesized }
                }
                // The first frames include shader compilation and warm-up.
                return times.drop(times.size / 3).sorted().let { it[it.size / 2] }
            } finally {
                passes.release()
                output.release()
                inputs.forEach { GlUtil.deleteTexture(it) }
            }
        } catch (e: GlUtil.GlException) {
            return null
        } finally {
            GlUtil.destroyEglContext(display, eglContext)
            GlUtil.destroyEglSurface(display, surface)
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).let { "${PackageInfoCompat.getLongVersionCode(it)}-${it.versionName}" }
    }.getOrDefault("0")
}
