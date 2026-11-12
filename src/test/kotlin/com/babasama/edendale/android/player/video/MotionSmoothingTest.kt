package com.babasama.edendale.android.player.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** G.6.T1: Motion Smoothing's schedule (Apple's scheduler cases) and the rules that offer it (G.5, D12). */
class MotionSmoothingTest {

    // MARK: - The schedule (G.4.1, Apple's FrameInterpolationTests)

    /** Frame [index] of a 23.976 fps source, as Apple's tests time them (1001/24000 s), in microseconds. */
    private fun frameTime(index: Int): Long = index * 1001L * 1_000_000L / 24_000L

    private val duration = 1_000_000L * 1001 / 24_000
    private val shape = "1920×1080 full"

    @Test
    fun aMidpointComesBeforeEachNeighbourButNotTheFirstFrame() {
        val schedule = MotionSmoothingSchedule()
        assertNull(schedule.midpointBefore(frameTime(0), shape, duration))
        schedule.commit(frameTime(0), shape)
        // A redraw of the same frame while paused makes nothing (Apple's keepCurrent).
        assertNull(schedule.midpointBefore(frameTime(0), shape, duration))

        val midpoint = assertNotNull(schedule.midpointBefore(frameTime(1), shape, duration))
        assertEquals((frameTime(0) + frameTime(1)) / 2, midpoint)
        assertTrue(midpoint > frameTime(0) && midpoint < frameTime(1), "the synthetic frame lands before the real one")
        schedule.commit(frameTime(1), shape)
        assertNotNull(schedule.midpointBefore(frameTime(2), shape, duration))
    }

    @Test
    fun noMidpointAcrossADroppedFrameASeekOrAStall() {
        val schedule = MotionSmoothingSchedule()
        schedule.commit(frameTime(10), shape)
        // Frame 11 was dropped.
        assertNull(schedule.midpointBefore(frameTime(12), shape, duration))
        schedule.commit(frameTime(12), shape)
        // A seek backwards.
        assertNull(schedule.midpointBefore(frameTime(3), shape, duration))
        schedule.commit(frameTime(3), shape)
        // Neighbours again.
        assertNotNull(schedule.midpointBefore(frameTime(4), shape, duration))
        // Just under 1.5 frames still blends; 1.5 frames doesn't.
        schedule.commit(0, shape)
        assertNotNull(schedule.midpointBefore(duration * 3 / 2 - 1, shape, duration))
        assertNull(schedule.midpointBefore(duration * 3 / 2, shape, duration))
    }

    @Test
    fun resetAndASizeChangeDropTheHistory() {
        val schedule = MotionSmoothingSchedule()
        schedule.commit(frameTime(0), shape)
        schedule.reset()
        assertNull(schedule.midpointBefore(frameTime(1), shape, duration))

        schedule.commit(frameTime(1), shape)
        // A new size, or the governor's half-resolution step, doesn't compare with the old frame.
        assertNull(schedule.midpointBefore(frameTime(2), "1920×1080 half", duration))
        // Without a known frame spacing nothing is made.
        assertNull(schedule.midpointBefore(frameTime(2), shape, 0))
    }

    @Test
    fun theHistoryMovesOnlyThroughCommitAfterTheMidpoint() {
        val schedule = MotionSmoothingSchedule()
        schedule.commit(frameTime(0), shape)
        // Asking twice doesn't move the history.
        assertEquals(schedule.midpointBefore(frameTime(1), shape, duration), schedule.midpointBefore(frameTime(1), shape, duration))
        // Committing frame N before interpolating would blend N with itself: nothing comes out.
        schedule.commit(frameTime(1), shape)
        assertNull(schedule.midpointBefore(frameTime(1), shape, duration))
    }

    // MARK: - When it's offered (D12, G.5.1)

    private fun offer(
        fps: Float?,
        rates: List<Float> = listOf(60f, 120f),
        television: Boolean = false,
        hdr: Boolean = false,
        capable: Boolean? = true,
    ) = MotionSmoothingRules.offer(fps, rates, television, hdr, capable)

    @Test
    fun onlyWhenTheDisplayShowsTheDoubledRateEvenly() {
        // 30 fps doubles to 60, which 60 and 120 Hz show evenly; the lowest mode wins.
        assertEquals(MotionSmoothingOffer(30f, 60f), offer(30f))
        assertEquals(60f, offer(29.97f)?.displayRate)
        // 48 fps on a 60/120 Hz panel would alternate one and two refreshes: not offered.
        assertNull(offer(24f))
        assertNull(offer(23.976f))
        assertNull(offer(24f, rates = listOf(60f, 90f, 120f)))
        // A 48, 96, or 144 Hz mode shows it evenly.
        assertEquals(48f, offer(23.976f, rates = listOf(60f, 48f, 120f))?.displayRate)
        assertEquals(96f, offer(24f, rates = listOf(60f, 96f, 120f))?.displayRate)
        assertEquals(144f, offer(24f, rates = listOf(60f, 120f, 144f))?.displayRate)
        // 25 fps needs 50 or 100 Hz.
        assertNull(offer(25f))
        assertEquals(50f, offer(25f, rates = listOf(50f, 60f))?.displayRate)
        // 30 fps on 90 Hz would be one and two refreshes again.
        assertNull(offer(30f, rates = listOf(90f)))
        // A slow source works the same way: 12 fps doubles to 24, five 120 Hz refreshes each.
        assertEquals(120f, offer(12f)?.displayRate)
        // No display modes known: nothing to check against.
        assertNull(offer(30f, rates = emptyList()))
    }

    @Test
    fun neverOnATvForHdrAbove30FpsOrWithoutThePassedCapabilityCheck() {
        assertNull(offer(30f, television = true))
        assertNull(offer(30f, hdr = true))
        assertNull(offer(30f, capable = false))
        assertNull(offer(30f, capable = null))
        assertNull(offer(48f, rates = listOf(96f, 120f)))
        assertNull(offer(60f))
        // Unknown or nonsense frame rates.
        assertNull(offer(null))
        assertNull(offer(-1f))
        assertNull(offer(Float.NaN))
        assertNull(offer(0f))
    }

    @Test
    fun wholeMultiplesWithinOnePercent() {
        assertTrue(MotionSmoothingRules.isWholeMultiple(59.94f, 60f))
        assertTrue(MotionSmoothingRules.isWholeMultiple(48f, 47.952f))
        assertTrue(MotionSmoothingRules.isWholeMultiple(120f, 59.94f))
        assertFalse(MotionSmoothingRules.isWholeMultiple(120f, 48f))
        assertFalse(MotionSmoothingRules.isWholeMultiple(50f, 48f))
        assertFalse(MotionSmoothingRules.isWholeMultiple(30f, 60f))
        assertFalse(MotionSmoothingRules.isWholeMultiple(0f, 60f))
    }

    @Test
    fun theLabelAndTheFrameSpacing() {
        val film = MotionSmoothingOffer(23.976f, 48f)
        assertEquals(24, film.sourceLabelFps)
        assertEquals(48, film.outputLabelFps)
        assertEquals(41_708L, film.frameDurationUs)
        val video = MotionSmoothingOffer(29.97f, 60f)
        assertEquals(listOf(30, 60), listOf(video.sourceLabelFps, video.outputLabelFps))
    }

    @Test
    fun itRunsOnlyAtNormalSpeedOrSlower() {
        assertTrue(MotionSmoothingRules.runsAtSpeed(1f))
        assertTrue(MotionSmoothingRules.runsAtSpeed(0.5f))
        assertFalse(MotionSmoothingRules.runsAtSpeed(1.25f))
        assertFalse(MotionSmoothingRules.runsAtSpeed(2f))
        assertFalse(MotionSmoothingRules.runsAtSpeed(0f))
        assertFalse(MotionSmoothingRules.runsAtSpeed(Float.NaN))
    }

    // MARK: - Measuring the frame rate (Matroska states none to Media3)

    /** Frame times of a source at [fps], rounded to whole milliseconds as Matroska stores them. */
    private fun millisecondFrames(fps: Double, count: Int, startMs: Long = 0): List<Long> =
        (0 until count).map { (startMs + Math.round(it * 1000.0 / fps)) * 1000 }

    @Test
    fun theRateComesFromMillisecondTimestampsWithoutTheirRounding() {
        for (fps in listOf(23.976, 24.0, 25.0, 29.97, 30.0)) {
            val estimator = FrameRateEstimator()
            millisecondFrames(fps, 25).forEach(estimator::add)
            val measured = assertNotNull(estimator.fps, "$fps fps")
            // The median gap alone would read 30 fps as 33 ms, 30.3 fps: past Motion Smoothing's limit.
            assertTrue(kotlin.math.abs(measured - fps) < 0.05, "$fps fps measured as $measured")
        }
        // 30 fps measured this way is still offered on a 60 Hz screen.
        val estimator = FrameRateEstimator()
        millisecondFrames(30.0, 25).forEach(estimator::add)
        assertNotNull(MotionSmoothingRules.offer(estimator.fps, listOf(60f), isTelevision = false, isHdr = false, capable = true))
    }

    @Test
    fun theEstimateWaitsForEnoughFramesAndShrugsOffDropsSeeksAndRedraws() {
        val estimator = FrameRateEstimator()
        val frames = millisecondFrames(24.0, 40)
        frames.take(24).forEach(estimator::add)
        assertNull(estimator.fps, "23 gaps aren't enough")
        // A redraw of the same frame changes nothing.
        estimator.add(frames[23])
        assertNull(estimator.fps)
        estimator.add(frames[24])
        assertEquals(24f, assertNotNull(estimator.fps), 0.05f)

        // A dropped frame (one gap twice as long) doesn't pull the rate down.
        val dropped = FrameRateEstimator()
        frames.filterIndexed { i, _ -> i != 10 }.forEach(dropped::add)
        assertEquals(24f, assertNotNull(dropped.fps), 0.05f)

        // A seek back or a long jump starts the count again.
        val seeking = FrameRateEstimator()
        frames.take(20).forEach(seeking::add)
        millisecondFrames(24.0, 10, startMs = 100).forEach(seeking::add)
        assertNull(seeking.fps)
        millisecondFrames(24.0, 30, startMs = 60_000).forEach(seeking::add)
        assertEquals(24f, assertNotNull(seeking.fps), 0.05f)
        seeking.reset()
        assertNull(seeking.fps)
    }

    // MARK: - The scene-cut count and the grids (G.2.2)

    @Test
    fun aCutNeedsThirtyPercentOfTheCoarseBlocks() {
        // 1080p: 120 × 68 blocks of 16 px.
        val grid = MotionSmoothingPasses.grid(PixelSize(1920, 1080), MotionSmoothingPasses.COARSE_BLOCK)
        assertEquals(PixelSize(120, 68), grid)
        assertEquals(2448, MotionSmoothingPasses.cutBlockCount(grid))
        // Apple's test frame, 160 × 120: 10 × 8 blocks, 24 of them.
        assertEquals(24, MotionSmoothingPasses.cutBlockCount(MotionSmoothingPasses.grid(PixelSize(160, 120), 16)))
        // At least one block.
        assertEquals(1, MotionSmoothingPasses.cutBlockCount(PixelSize(1, 1)))
        assertEquals(PixelSize(480, 270), MotionSmoothingPasses.grid(PixelSize(1920, 1080), MotionSmoothingPasses.REFINE_BLOCK))
    }
}
