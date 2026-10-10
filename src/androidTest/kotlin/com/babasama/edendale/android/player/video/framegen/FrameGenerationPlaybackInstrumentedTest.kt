package com.babasama.edendale.android.player.video.framegen

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babasama.edendale.android.MainActivity
import com.babasama.edendale.android.player.EdendaleRenderersFactory
import com.babasama.edendale.android.player.EqAudioProcessor
import com.babasama.edendale.android.player.video.EnhancementEffect
import com.babasama.edendale.android.player.video.EnhancementPreset
import com.babasama.edendale.android.player.video.EnhancementSettings
import com.babasama.edendale.android.player.video.PixelSize
import com.babasama.edendale.android.player.video.VideoEffectsHolder
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * G.1.1 probe: plays a clip through [MidpointFrameEffect] into a real
 * SurfaceView and records when ExoPlayer releases each frame, the audio
 * clock, and what SurfaceFlinger presented (its timestats). Then seeks,
 * pauses, redraws while paused, switches the audio track, and plays the same
 * clip with the midpoints off for a baseline.
 *
 * Opt-in, because it needs a clip with audio on the device:
 * `adb push clip.mp4 /data/local/tmp/` and run with
 * `-e g1Clip /data/local/tmp/clip.mp4 -e g1Fps 24`, optionally
 * `-e g1WorkMs 8` to hold the GL thread that long per midpoint (the cost of
 * real interpolation), `-e g1Pool 10` for more output textures, and
 * `-e g1Chain balanced` to run Edendale's Balanced enhancement first. It
 * turns SurfaceFlinger's timestats on for the run and off afterwards. The
 * report is the instrumentation status `g1` and `cache/g1-playback.txt`
 * (`run-as`).
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class FrameGenerationPlaybackInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val main = Handler(Looper.getMainLooper())

    private class FrameRecord(val ptsUs: Long, val releaseNs: Long, val callbackNs: Long)
    private class PositionSample(val nanos: Long, val positionMs: Long)
    private class Mark(val name: String, val nanos: Long)

    private class Recording(val label: String, val config: MidpointConfig) {
        val frames = ConcurrentLinkedQueue<FrameRecord>()
        val positions = ConcurrentLinkedQueue<PositionSample>()
        val marks = ConcurrentLinkedQueue<Mark>()
        val notes = mutableListOf<String>()
        @Volatile var error: PlaybackException? = null
        var dropped = 0
        var rendered = 0
        var skipped = 0

        fun mark(name: String) = marks.add(Mark(name, System.nanoTime()))
    }

    @Test
    fun midpointFramesThroughExoPlayer() {
        val clipPath = arguments.getString("g1Clip")
        assumeTrue("G.1 probe: run with -e g1Clip <a clip with audio on the device>", clipPath != null)
        val fps = arguments.getString("g1Fps")?.toFloat() ?: 24f
        val frameUs = (1_000_000 / fps).roundToLong()
        val workMs = arguments.getString("g1WorkMs")?.toLong() ?: 0
        val pool = arguments.getString("g1Pool")?.toInt() ?: 6
        val chain = arguments.getString("g1Chain") == "balanced"
        val clip = copyFromShell(clipPath!!)

        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val surfaceView = attachSurfaceView(activity)
        val report = StringBuilder()
        try {
            val on = Recording("midpoints on" + if (workMs > 0) ", $workMs ms simulated work each" else "", MidpointConfig(frameUs, enabled = true, simulatedWorkMs = workMs, poolSize = pool))
            play(on, clip, surfaceView, chain) { player, rec ->
                waitForPosition(player, 2_000)
                shell("dumpsys SurfaceFlinger --timestats -clear -enable")
                waitForPosition(player, 7_000)
                rec.notes += timeStats()
                rec.notes += "frame-rate votes while playing:\n" + frameRateVotes()
                rec.notes += "display: " + shell("dumpsys display").lineSequence()
                    .firstOrNull { "mActiveSfDisplayMode" in it || "renderFrameRate" in it }?.trim().orEmpty().take(300)
                seek(player, rec, 15_000)
                waitForPosition(player, 18_000)
                rec.mark("pause")
                instrumentation.runOnMainSync { player.pause() }
                Thread.sleep(400)
                // As Show Original and the sliders do while paused (F.1.2).
                instrumentation.runOnMainSync { player.setVideoEffects(VideoFrameProcessor.REDRAW) }
                Thread.sleep(600)
                rec.mark("play")
                instrumentation.runOnMainSync { player.play() }
                Thread.sleep(1_500)
                rec.mark("audio track switch")
                instrumentation.runOnMainSync {
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setPreferredAudioLanguage("fr").build()
                }
                Thread.sleep(2_000)
                seek(player, rec, 3_000)
                Thread.sleep(150)
                seek(player, rec, 24_000)
                waitForPosition(player, 27_000)
            }
            val off = Recording("midpoints off (same pipeline)", MidpointConfig(frameUs, enabled = false, poolSize = pool))
            play(off, clip, surfaceView, chain) { player, rec ->
                waitForPosition(player, 2_000)
                shell("dumpsys SurfaceFlinger --timestats -clear -enable")
                waitForPosition(player, 7_000)
                rec.notes += timeStats()
            }

            report.appendLine("G.1.1 probe: ${clip.name} at $fps fps, pool $pool${if (chain) ", after Balanced enhancement" else ""}, ${android.os.Build.MODEL}, API ${android.os.Build.VERSION.SDK_INT}")
            report.appendLine("GL: ${shell("dumpsys SurfaceFlinger").lineSequence().firstOrNull { it.startsWith("GLES:") }?.take(200)}")
            for (rec in listOf(on, off)) report.append(analyze(rec, frameUs))
            publish(report.toString())

            assertNull("playback error: ${on.error?.errorCodeName}", on.error)
            assertNull("playback error: ${off.error?.errorCodeName}", off.error)
            assertTrue("no midpoint frames were made", on.config.synthesizedTimesUs.isNotEmpty())
        } finally {
            shell("dumpsys SurfaceFlinger --timestats -disable -clear")
            activity.finish()
            clip.delete()
        }
    }

    // MARK: - Playback

    private fun play(rec: Recording, clip: File, surfaceView: SurfaceView, chain: Boolean, script: (ExoPlayer, Recording) -> Unit) {
        lateinit var player: ExoPlayer
        val sampler = object : Runnable {
            override fun run() {
                if (player.isPlaying) rec.positions.add(PositionSample(System.nanoTime(), player.currentPosition))
                main.postDelayed(this, 4)
            }
        }
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(context, EdendaleRenderersFactory(context, EqAudioProcessor())).build()
            player.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    rec.error = error
                }
            })
            player.setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
                rec.frames.add(FrameRecord(presentationTimeUs, releaseTimeNs, System.nanoTime()))
            }
            player.setVideoSurfaceView(surfaceView)
            val effects = mutableListOf<Effect>()
            if (chain) {
                // Edendale's effect first, as Apple interpolates enhanced frames; the view is 1280×720.
                effects += EnhancementEffect(VideoEffectsHolder().apply {
                    display = PixelSize(1280, 720)
                    enhancement = EnhancementSettings(preset = EnhancementPreset.BALANCED)
                })
            }
            effects += MidpointFrameEffect(rec.config)
            player.setVideoEffects(effects)
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(clip)))
            player.prepare()
            player.play()
            rec.mark("start")
            main.post(sampler)
        }
        try {
            script(player, rec)
        } finally {
            instrumentation.runOnMainSync {
                main.removeCallbacks(sampler)
                rec.mark("end")
                player.videoDecoderCounters?.let {
                    it.ensureUpdated()
                    rec.dropped = it.droppedBufferCount
                    rec.rendered = it.renderedOutputBufferCount
                    rec.skipped = it.skippedOutputBufferCount
                }
                player.release()
            }
        }
    }

    private fun seek(player: ExoPlayer, rec: Recording, positionMs: Long) {
        rec.mark("seek to ${positionMs / 1000} s")
        instrumentation.runOnMainSync { player.seekTo(positionMs) }
    }

    private fun waitForPosition(player: ExoPlayer, positionMs: Long, timeoutMs: Long = 40_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            var position = 0L
            var state = Player.STATE_IDLE
            instrumentation.runOnMainSync {
                position = player.currentPosition
                state = player.playbackState
            }
            if (position >= positionMs || state == Player.STATE_ENDED) return
            Thread.sleep(50)
        }
        throw AssertionError("playback never reached $positionMs ms")
    }

    private fun attachSurfaceView(activity: Activity): SurfaceView {
        val created = CountDownLatch(1)
        lateinit var view: SurfaceView
        instrumentation.runOnMainSync {
            view = SurfaceView(activity)
            view.setZOrderOnTop(true)
            view.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = created.countDown()
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) {}
            })
            // A 16:9 view at the clip's size, so neither scaling nor composition dominates on a software GPU.
            activity.addContentView(view, FrameLayout.LayoutParams(1280, 720, Gravity.CENTER))
        }
        assertTrue("no surface", created.await(10, TimeUnit.SECONDS))
        return view
    }

    // MARK: - Device state

    private fun shell(command: String): String {
        val pfd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    private fun copyFromShell(path: String): File {
        val target = File(context.cacheDir, "g1-" + File(path).name)
        val pfd = instrumentation.uiAutomation.executeShellCommand("cat $path")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> target.outputStream().use { input.copyTo(it) } }
        assertTrue("couldn't read $path", target.length() > 0)
        return target
    }

    /** SurfaceFlinger's timestats for the SurfaceView: frame counts and the present-to-present histogram. */
    private fun timeStats(): String {
        val dump = shell("dumpsys SurfaceFlinger --timestats -dump -maxlayers 40")
        val sections = dump.split(Regex("(?=layerName = )"))
        val ours = sections.filter { it.startsWith("layerName = ") && "SurfaceView" in it && "MainActivity" in it }
        if (ours.isEmpty()) return "timestats: no SurfaceView layer (" + dump.lineSequence().take(6).joinToString(" | ") + ")"
        return "timestats:\n" + ours.joinToString("\n") { section ->
            val lines = section.lines()
            lines.mapIndexedNotNull { index, line ->
                when {
                    listOf("layerName", "totalFrames", "droppedFrames", "lateAcquireFrames", "badDesiredPresentFrames", "averageFPS").any { it in line } -> "  " + line.trim()
                    // The histogram follows its heading: "<ms>ms=<count>" pairs; keep the non-zero buckets.
                    line.trim().startsWith("present2present histogram") ->
                        "  present-to-present ms: " + lines.getOrNull(index + 1).orEmpty().trim().split(Regex("\\s+")).filterNot { it.endsWith("=0") }.joinToString(" ")
                    else -> null
                }
            }.joinToString("\n")
        }
    }

    private fun frameRateVotes(): String =
        shell("dumpsys SurfaceFlinger").lines()
            .filter { line -> ("rameRate" in line || "frameRate" in line) && ("SurfaceView" in line || "votes" in line || "FixedSource" in line || "ExactOrMultiple" in line) }
            .take(12).joinToString("\n") { "  " + it.trim().take(240) }
            .ifEmpty { "  (none found)" }

    private fun publish(report: String) {
        File(context.cacheDir, "g1-playback.txt").writeText(report)
        instrumentation.sendStatus(0, Bundle().apply { putString("g1", "\n" + report) })
    }

    // MARK: - Analysis

    private fun analyze(rec: Recording, frameUs: Long): String = buildString {
        val frames = rec.frames.toList()
        val marks = rec.marks.toList()
        val synthesized = rec.config.synthesizedTimesUs.toHashSet()
        appendLine()
        appendLine("== ${rec.label} ==")
        appendLine("input frames ${rec.config.inputFrames.get()}, midpoints made ${synthesized.size}, flushes ${rec.config.flushes.get()}")
        appendLine("redrawn while paused: ${frames.count { it.releaseNs == C.TIME_UNSET }}")
        appendLine("released frames ${frames.size} (midpoints ${frames.count { it.ptsUs in synthesized }}); decoder counters: rendered ${rec.rendered}, dropped ${rec.dropped}, skipped ${rec.skipped}")
        rec.config.issueNanos.toList().takeIf { it.isNotEmpty() }?.let { issue ->
            appendLine("GL-thread time per input: median ${"%.2f".format(median(issue.map { it / 1e6 }))} ms, max ${"%.2f".format(issue.max() / 1e6)} ms")
        }
        rec.error?.let { appendLine("ERROR: ${it.errorCodeName}: ${it.cause}") }
        appendLine("first pts ${frames.firstOrNull()?.ptsUs} us; marks: " + marks.joinToString { "${it.name} @${fmt((it.nanos - marks.first().nanos) / 1e9)}s" })

        // Steady segments: from 1 s after each mark to the next mark, while playing.
        val segments = marks.zipWithNext().filter { (a, _) -> a.name != "pause" && a.name != "end" }
        for ((from, to) in segments) {
            val start = from.nanos + 1_000_000_000L
            if (to.nanos - start < 1_000_000_000L) continue
            val inSegment = frames.filter { it.releaseNs in start until to.nanos }.sortedBy { it.releaseNs }
            val positions = rec.positions.filter { it.nanos in start until to.nanos }
            if (inSegment.size < 10 || positions.size < 50) continue
            appendLine("-- after '${from.name}' (${fmt((to.nanos - start) / 1e9)} s)")
            // ExoPlayer's position follows the audio clock; fit it against the monotonic clock.
            val fit = fit(positions.map { it.nanos.toDouble() }, positions.map { it.positionMs * 1000.0 })
            val (real, mid) = inSegment.partition { it.ptsUs !in synthesized }
            fun offsets(list: List<FrameRecord>) = list.map { (it.releaseNs - fit.timeNsFor(it.ptsUs.toDouble())) / 1e6 }
            val baseline = median(offsets(real))
            appendLine("  real frames' release minus audio-clock time: median ${fmt(baseline)} ms")
            appendLine("  release vs audio clock (ms, relative to the median for real frames): real ${spread(offsets(real).map { it - baseline })}" +
                if (mid.isNotEmpty()) "; midpoints ${spread(offsets(mid).map { it - baseline })}" else "")
            val gaps = inSegment.zipWithNext { a, b -> (b.ptsUs - a.ptsUs) }
            val expectedGap = if (mid.isNotEmpty()) frameUs / 2 else frameUs
            val missing = gaps.sumOf { gap -> ((gap.toDouble() / expectedGap).roundToInt() - 1).coerceAtLeast(0) }
            appendLine("  released ${inSegment.size} frames (${mid.size} midpoints), ${fmt(inSegment.size / ((inSegment.last().releaseNs - inSegment.first().releaseNs) / 1e9))} fps; gaps in the timestamp sequence: $missing")
            val early = inSegment.map { (it.releaseNs - it.callbackNs) / 1e6 }
            appendLine("  scheduled ahead of release (ms): ${spread(early)}")
        }

        // After each seek (300 ms on, until the next mark), every released frame
        // belongs at or after the target: nothing left over from before it.
        marks.zipWithNext().filter { (seek, _) -> seek.name.startsWith("seek to") }.forEach { (seek, next) ->
            val targetUs = seek.name.removePrefix("seek to ").removeSuffix(" s").toLong() * 1_000_000
            val window = frames.filter { it.callbackNs in (seek.nanos + 300_000_000L) until minOf(next.nanos, seek.nanos + 2_000_000_000L) }
            val stale = window.count { it.ptsUs < targetUs - frameUs || it.ptsUs > targetUs + 3_000_000 }
            val firstAfter = frames.firstOrNull { it.callbackNs > seek.nanos }
            appendLine("${seek.name}: first frame after ${firstAfter?.let { fmt((it.callbackNs - seek.nanos) / 1e6) } ?: "-"} ms (pts ${firstAfter?.ptsUs}); " +
                if (window.isEmpty()) "the next seek came first" else "stale frames among the ${window.size} released after it: $stale")
        }

        rec.notes.forEach { appendLine(it) }
    }

    private class Fit(val meanX: Double, val meanY: Double, val slope: Double) {
        /** The monotonic time at which the clock reads [positionUs]. */
        fun timeNsFor(positionUs: Double) = meanX + (positionUs - meanY) / slope
    }

    private fun fit(x: List<Double>, y: List<Double>): Fit {
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in x.indices) {
            sxy += (x[i] - mx) * (y[i] - my)
            sxx += (x[i] - mx) * (x[i] - mx)
        }
        return Fit(mx, my, sxy / sxx)
    }

    private fun median(values: List<Double>): Double = values.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }

    private fun spread(values: List<Double>): String {
        if (values.isEmpty()) return "-"
        val sorted = values.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).roundToInt()]
        return "median ${fmt(pct(0.5))}, p5 ${fmt(pct(0.05))}, p95 ${fmt(pct(0.95))}, min ${fmt(sorted.first())}, max ${fmt(sorted.last())} (n=${sorted.size})"
    }

    private fun fmt(value: Double) = "%.1f".format(value)
}
