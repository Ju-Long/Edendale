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
 * Whether this device can run Balanced enhancement within budget (F.6.3,
 * D11): OpenGL ES 3.0 or later, not a low-RAM device, and a short offscreen
 * benchmark of the Balanced passes (720p EASU to 1080p, then sharpening at
 * 1080p) under 8 ms a frame. It runs once per app version and the result
 * stays on the device; devices that fail start enhancement at Off, but the
 * viewer can still pick any preset. Nothing about performance leaves the
 * device.
 */
object EnhancementCapability {
    private const val PREFS = "player"
    private const val KEY = "video.enhancementCapability"

    /** The stored verdict for this app version, or null when the check hasn't run. */
    fun cachedResult(context: Context): Boolean? {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val (verdict, version) = stored.split(':', limit = 2).let { it[0] to it.getOrNull(1) }
        if (version != appVersion(context)) return null
        return verdict == "pass"
    }

    /** Runs the check when there's no verdict yet. Blocking GPU work: call off the main thread. */
    fun evaluateIfNeeded(context: Context) {
        if (cachedResult(context) != null) return
        val passed = runCatching { evaluate(context) }.getOrDefault(false)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, "${if (passed) "pass" else "fail"}:${appVersion(context)}")
            .apply()
    }

    private fun evaluate(context: Context): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return false
        if (manager.isLowRamDevice) return false
        if (manager.deviceConfigurationInfo.reqGlEsVersion < 0x30000) return false
        val millis = benchmarkBalancedMillis(context) ?: return false
        return millis < EnhancementGovernor.BUDGET_MILLIS
    }

    /** The average time of one Balanced frame at 1080p, measured offscreen, or null when GL fails. */
    @OptIn(UnstableApi::class)
    fun benchmarkBalancedMillis(context: Context, frames: Int = 12): Double? {
        val display = GlUtil.getDefaultEglDisplay()
        val eglContext = GlUtil.createEglContext(EGL14.EGL_NO_CONTEXT, display, 3, GlUtil.EGL_CONFIG_ATTRIBUTES_RGBA_8888)
        val surface = GlUtil.createFocusedPlaceholderEglSurface(eglContext, display)
        try {
            if (!isGles3()) return null
            val source = PixelSize(1280, 720)
            val target = PixelSize(1920, 1080)
            val input = GlUtil.createTexture(source.width, source.height, false)
            val upscaled = RenderTarget(target.width, target.height)
            val sharpened = RenderTarget(target.width, target.height)
            val upscale = UpscalePass(context, useEasu = true)
            val sharpen = SharpenPass(context)
            try {
                val times = (0 until frames).map {
                    val start = SystemClock.elapsedRealtimeNanos()
                    upscaled.focus()
                    upscale.draw(input, source, target)
                    sharpened.focus()
                    sharpen.draw(upscaled.texId, 0.5f)
                    GLES20.glFinish()
                    (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                }
                // The first frames include shader compilation and warm-up.
                return times.drop(frames / 3).average()
            } finally {
                upscale.release()
                sharpen.release()
                upscaled.release()
                sharpened.release()
                GlUtil.deleteTexture(input)
            }
        } finally {
            GlUtil.destroyEglContext(display, eglContext)
            GlUtil.destroyEglSurface(display, surface)
        }
    }

    private fun appVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).let { "${PackageInfoCompat.getLongVersionCode(it)}-${it.versionName}" }
    }.getOrDefault("0")
}
