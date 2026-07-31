package com.babasama.edendale.android.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors Apple's `PlayerLogicTests` so both platforms hold the same
 * playback rules: speed stepping, time formatting, auto-skip windows,
 * natural-end detection, seek clamping, and hold-drag scrub math.
 */
class PlayerLogicTest {

    // ------------------------------------------------------------------
    // Normalized levels
    // ------------------------------------------------------------------

    @Test
    fun normalizedLevelsStepAndClamp() {
        assertEquals(0.55f, PlayerLogic.adjustedLevel(0.5f, 0.05f))
        assertEquals(0.45f, PlayerLogic.adjustedLevel(0.5f, -0.05f))
        assertEquals(1f, PlayerLogic.adjustedLevel(0.98f, 0.05f))
        assertEquals(0f, PlayerLogic.adjustedLevel(0.02f, -0.05f))
    }

    // ------------------------------------------------------------------
    // Speed
    // ------------------------------------------------------------------

    @Test
    fun rateStepsInFiveHundredthsIncrements() {
        assertEquals(1.05f, PlayerLogic.incrementedRate(1.0f))
        assertEquals(0.95f, PlayerLogic.decrementedRate(1.0f))
        // Repeated stepping stays on the 0.05 grid (no float drift).
        var rate = 1.0f
        repeat(7) { rate = PlayerLogic.incrementedRate(rate) }
        assertEquals(1.35f, rate, 0.0001f)
    }

    @Test
    fun rateClampsToSupportedRange() {
        assertEquals(PlayerLogic.MIN_RATE, PlayerLogic.decrementedRate(PlayerLogic.MIN_RATE))
        assertEquals(PlayerLogic.MAX_RATE, PlayerLogic.incrementedRate(PlayerLogic.MAX_RATE))
        assertEquals(PlayerLogic.MAX_RATE, PlayerLogic.normalizedRate(17f))
        assertEquals(PlayerLogic.MIN_RATE, PlayerLogic.normalizedRate(-2f))
    }

    @Test
    fun rateSnapsOntoGrid() {
        assertEquals(1.0f, PlayerLogic.normalizedRate(1.02f))
        assertEquals(1.15f, PlayerLogic.normalizedRate(1.13f))
        assertEquals("1.50×", PlayerLogic.rateLabel(1.5f))
    }

    // ------------------------------------------------------------------
    // Press-and-hold speed
    // ------------------------------------------------------------------

    @Test
    fun holdRateComesFromTheHalfThePressStartedIn() {
        assertEquals(PlayerLogic.HOLD_SLOW_RATE, PlayerLogic.holdRate(touchX = 10f, width = 100f))
        assertEquals(PlayerLogic.HOLD_FAST_RATE, PlayerLogic.holdRate(touchX = 90f, width = 100f))
        // Dead centre counts as the fast half rather than falling through.
        assertEquals(PlayerLogic.HOLD_FAST_RATE, PlayerLogic.holdRate(touchX = 50f, width = 100f))
    }

    // ------------------------------------------------------------------
    // Timestamps
    // ------------------------------------------------------------------

    @Test
    fun timestampsFormatAcrossHourBoundary() {
        assertEquals("0:00", PlayerLogic.timestamp(0))
        assertEquals("0:59", PlayerLogic.timestamp(59_900))
        assertEquals("1:05", PlayerLogic.timestamp(65_000))
        assertEquals("59:59", PlayerLogic.timestamp(3_599_000))
        assertEquals("1:00:00", PlayerLogic.timestamp(3_600_000))
        assertEquals("1:23:45", PlayerLogic.timestamp(5_025_000))
        assertEquals("0:00", PlayerLogic.timestamp(-4_000))
    }

    @Test
    fun signedTimestampsCarryTheDirection() {
        assertEquals("+1:30", PlayerLogic.signedTimestamp(90_000))
        assertEquals("−0:10", PlayerLogic.signedTimestamp(-10_000))
        assertEquals("+0:00", PlayerLogic.signedTimestamp(0))
    }

    // ------------------------------------------------------------------
    // Auto-skip windows
    // ------------------------------------------------------------------

    @Test
    fun recapSkipOnlyAppliesToLongEnoughMedia() {
        assertEquals(90_000, PlayerLogic.recapSkipTargetMillis(2_700_000))
        assertNull(PlayerLogic.recapSkipTargetMillis(300_000))
        // An unknown duration reads as zero and must not skip.
        assertNull(PlayerLogic.recapSkipTargetMillis(0))
    }

    @Test
    fun creditsWindowStartsBeforeTheEnd() {
        assertEquals(3_420_000, PlayerLogic.creditsStartMillis(3_600_000))
        assertNull(PlayerLogic.creditsStartMillis(300_000))
        assertNull(PlayerLogic.creditsStartMillis(0))
    }

    // ------------------------------------------------------------------
    // Natural end
    // ------------------------------------------------------------------

    @Test
    fun naturalEndFiresNearNinetyFivePercent() {
        val duration = 1_200_000L
        // A mid-file stop is a user stop, not a completion.
        assertFalse(PlayerLogic.isNaturalEnd(600_000, duration))    // 50%
        assertTrue(PlayerLogic.isNaturalEnd(1_152_000, duration))   // 96%
        assertFalse(PlayerLogic.isNaturalEnd(0, duration))
        assertFalse(PlayerLogic.isNaturalEnd(1_152_000, 0))
    }

    @Test
    fun naturalEndTreatsCreditsSkipAsFinished() {
        // Skip-credits ends playback CREDITS_LENGTH before the tail — far
        // more than two seconds. For a feature-length runtime that cutoff
        // still sits past 95%, so the stop completes instead of lingering in
        // Continue Watching.
        val feature = 7_200_000L
        val creditsStart = PlayerLogic.creditsStartMillis(feature)!!
        assertTrue(PlayerLogic.isNaturalEnd(creditsStart, feature))
    }

    @Test
    fun naturalEndKeepsTwoSecondArmForShortClips() {
        // Below MINIMUM_SKIPPABLE the credits window never applies, so
        // completion rides the two-second arm. At 30 s the arms diverge: the
        // 2 s cutoff is 28 s while 95% is 28.5 s.
        val clip = 30_000L
        assertTrue(PlayerLogic.isNaturalEnd(28_200, clip))   // within 2 s, below 95%
        assertFalse(PlayerLogic.isNaturalEnd(27_000, clip))  // short of both arms
    }

    // ------------------------------------------------------------------
    // Seeking
    // ------------------------------------------------------------------

    @Test
    fun relativeSeeksClampToTheTimeline() {
        assertEquals(40_000, PlayerLogic.seekTargetMillis(30_000, 10_000, 100_000))
        assertEquals(0, PlayerLogic.seekTargetMillis(5_000, -10_000, 100_000))
        assertEquals(100_000, PlayerLogic.seekTargetMillis(95_000, 10_000, 100_000))
        // An unknown duration still allows seeking forward.
        assertEquals(40_000, PlayerLogic.seekTargetMillis(30_000, 10_000, 0))
    }

    @Test
    fun relativeSeekPositionsAreNormalized() {
        assertEquals(0.4, PlayerLogic.seekPosition(30_000, 10_000, 100_000), 1e-9)
        assertEquals(0.0, PlayerLogic.seekPosition(5_000, -10_000, 100_000), 1e-9)
        assertEquals(1.0, PlayerLogic.seekPosition(95_000, 10_000, 100_000), 1e-9)
        assertEquals(0.0, PlayerLogic.seekPosition(30_000, 10_000, 0), 1e-9)
    }

    @Test
    fun progressFractionClampsAndSurvivesUnknownDurations() {
        assertEquals(0.5, PlayerLogic.progressFraction(50_000, 100_000), 1e-9)
        assertEquals(1.0, PlayerLogic.progressFraction(150_000, 100_000), 1e-9)
        assertEquals(0.0, PlayerLogic.progressFraction(50_000, 0), 1e-9)
    }

    // ------------------------------------------------------------------
    // Hold-drag scrubbing
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Natural ordering
    // ------------------------------------------------------------------

    @Test
    fun naturalOrderingReadsDigitRunsAsNumbers() {
        // Mirrors Apple's localizedStandardCompare sibling sort: video
        // files in a folder list numerically, not byte-wise.
        val sorted = listOf("Episode 10.mkv", "b.mkv", "Episode 2.mkv", "a.mp4")
            .sortedWith(PlayerLogic::naturalCompare)
        assertEquals(listOf("a.mp4", "b.mkv", "Episode 2.mkv", "Episode 10.mkv"), sorted)
    }

    @Test
    fun naturalOrderingIgnoresCaseAndLeadingZeros() {
        assertTrue(PlayerLogic.naturalCompare("episode 2", "Episode 10") < 0)
        assertEquals(0, PlayerLogic.naturalCompare("E01", "E1"))
        assertTrue(PlayerLogic.naturalCompare("E2", "E10") < 0)
        assertTrue(PlayerLogic.naturalCompare("abc", "abcd") < 0)
    }

    @Test
    fun subtitleLookupUsesMovieIdAndEpisodeSeriesId() {
        assertEquals(WyzieLookup("278", null, null), subtitleLookup(278, false, null, null, null))
        val episode = subtitleLookup(
            tmdbId = 62085,
            isEpisode = true,
            showTmdbId = 1396,
            season = 2,
            episode = 8,
        )
        assertEquals(WyzieLookup("1396", 2, 8), episode)
        assertFalse(episode?.id == "62085")
        assertNull(subtitleLookup(null, false, null, null, null))
        assertNull(subtitleLookup(62085, true, null, 2, 8))
    }

    @Test
    fun scrubTargetSweepsTheConfiguredWindow() {
        // A full-width drag on a 600 s file moves 300 s → +0.5 of position.
        assertEquals(
            0.7,
            PlayerLogic.scrubTarget(basePosition = 0.2, translation = 400f, width = 400f, durationMillis = 600_000),
            0.0001,
        )
        assertEquals(
            0.0,
            PlayerLogic.scrubTarget(basePosition = 0.2, translation = -400f, width = 400f, durationMillis = 600_000),
            0.0001,
        )
        // Degenerate inputs leave the position untouched.
        assertEquals(
            0.4,
            PlayerLogic.scrubTarget(basePosition = 0.4, translation = 100f, width = 0f, durationMillis = 600_000),
            0.0001,
        )
        assertEquals(
            0.4,
            PlayerLogic.scrubTarget(basePosition = 0.4, translation = 100f, width = 400f, durationMillis = 0),
            0.0001,
        )
    }
}
