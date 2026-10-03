package com.babasama.edendale.android.player.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

/** F.6.3: the offscreen Balanced benchmark runs on its own GL context and reports a time. */
@RunWith(AndroidJUnit4::class)
class EnhancementCapabilityInstrumentedTest {

    @Test
    fun theBalancedBenchmarkMeasuresAFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Its own thread, as in the app: the benchmark makes its EGL context current there.
        val millis = Executors.newSingleThreadExecutor().submit<Double?> {
            EnhancementCapability.benchmarkBalancedMillis(context)
        }.get()
        assertNotNull(millis)
        assertTrue("$millis ms", millis!!.isFinite() && millis > 0.0)
        android.util.Log.i("EnhancementCapability", "Balanced 720p→1080p frame: %.2f ms".format(millis))
    }
}
