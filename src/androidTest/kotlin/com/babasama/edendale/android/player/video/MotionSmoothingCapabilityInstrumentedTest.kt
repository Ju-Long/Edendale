package com.babasama.edendale.android.player.video

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * G.5: Motion Smoothing's device check runs the real passes offscreen at
 * both search resolutions and comes back with a time for each. It reports
 * the times and the verdict they give (logcat `MotionSmoothingCheck`); the
 * stored verdict is left alone.
 */
@RunWith(AndroidJUnit4::class)
class MotionSmoothingCapabilityInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun theCheckTimesBothSearchResolutions() {
        val full = MotionSmoothingCapability.benchmarkMillis(context, halfResolution = false)
        val half = MotionSmoothingCapability.benchmarkMillis(context, halfResolution = true)
        assertNotNull("full-resolution run failed", full)
        assertNotNull("half-resolution run failed", half)
        assertTrue("times are positive: $full, $half", full!! > 0 && half!! > 0)
        val verdict = MotionSmoothingCapability.verdict(full, half)
        val report = "1080p synthetic frame: full %.2f ms, half %.2f ms → %s".format(full, half, verdict.raw)
        Log.i("MotionSmoothingCheck", report)
        instrumentation.sendStatus(0, Bundle().apply { putString("motionSmoothingCheck", report) })
    }
}
