package com.babasama.edendale.android.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The right-hand swipe moves the device's media volume (owner request 8). */
class DeviceVolumeTest {

    // MARK: - Steps

    @Test
    fun indexesMapToLevelsAndBack() {
        assertEquals(0f, VolumeSteps.level(0, 0, 15, muted = false))
        assertEquals(1f, VolumeSteps.level(15, 0, 15, muted = false))
        assertEquals(0.4f, VolumeSteps.level(6, 0, 15, muted = false), 1e-6f)
        // A stream whose minimum isn't 0 (API 28+ reports it).
        assertEquals(0f, VolumeSteps.level(1, 1, 25, muted = false))
        assertEquals(0.5f, VolumeSteps.level(13, 1, 25, muted = false), 1e-6f)

        assertEquals(0, VolumeSteps.index(0f, 0, 15))
        assertEquals(15, VolumeSteps.index(1f, 0, 15))
        assertEquals(8, VolumeSteps.index(0.5f, 0, 15))
        assertEquals(7, VolumeSteps.index(0.49f, 0, 15))
        assertEquals(13, VolumeSteps.index(0.5f, 1, 25))
        for (max in listOf(7, 15, 25, 100)) {
            for (index in 0..max) {
                assertEquals(index, VolumeSteps.index(VolumeSteps.level(index, 0, max, muted = false), 0, max), "$index of $max")
            }
        }
    }

    @Test
    fun indexesStayInsideTheStream() {
        assertEquals(0, VolumeSteps.index(-0.3f, 0, 15))
        assertEquals(15, VolumeSteps.index(4f, 0, 15))
        assertEquals(0, VolumeSteps.index(Float.NaN, 0, 15))
        assertEquals(15, VolumeSteps.index(Float.POSITIVE_INFINITY, 0, 15))
        assertEquals(2, VolumeSteps.index(0f, 2, 15))
        assertEquals(1f, VolumeSteps.level(40, 0, 15, muted = false))
        assertEquals(0f, VolumeSteps.level(-3, 0, 15, muted = false))
    }

    @Test
    fun aMutedStreamReadsSilent() {
        assertEquals(0f, VolumeSteps.level(9, 0, 15, muted = true))
    }

    @Test
    fun aStreamWithNoStepsCannotMove() {
        assertEquals(1f, VolumeSteps.level(0, 0, 0, muted = false))
        assertEquals(0f, VolumeSteps.level(0, 0, 0, muted = true))
        assertEquals(5, VolumeSteps.index(0.7f, 5, 5))
        assertEquals(5, VolumeSteps.index(0.7f, 5, 3))
    }

    @Test
    fun onlyARaiseThatCameBackLowBeforeAndroid12WarrantsTheWarningRetry() {
        assertTrue(VolumeSteps.needsWarningRetry(before = 9, requested = 11, after = 10, sdkInt = 30))
        assertTrue(VolumeSteps.needsWarningRetry(before = 10, requested = 11, after = 10, sdkInt = 26))
        assertFalse(VolumeSteps.needsWarningRetry(before = 9, requested = 11, after = 11, sdkInt = 30))
        assertFalse(VolumeSteps.needsWarningRetry(before = 9, requested = 7, after = 9, sdkInt = 30))
        assertFalse(VolumeSteps.needsWarningRetry(before = 9, requested = 9, after = 8, sdkInt = 30))
        // From Android 12 the quiet write already brought the warning up; asking
        // again would only add Android's own volume panel.
        assertFalse(VolumeSteps.needsWarningRetry(before = 9, requested = 11, after = 10, sdkInt = 31))
        assertFalse(VolumeSteps.needsWarningRetry(before = 10, requested = 11, after = 10, sdkInt = 36))
    }

    // MARK: - The controller

    private class FakeStream(
        var index: Int,
        val min: Int = 0,
        val max: Int = 15,
        var muted: Boolean = false,
        override var isFixed: Boolean = false,
    ) : MusicStream {
        /** Raises above this are held back, as Android's hearing check holds them with headphones in. */
        var safeIndex = Int.MAX_VALUE
        /** Writes that don't take, as on a route that keeps the stream at its maximum. */
        var ignoresWrites = false
        val writes = mutableListOf<Pair<Int, Boolean>>()

        override fun read() = MusicStreamState(index, min, max, muted)

        override fun write(index: Int, showUi: Boolean) {
            writes += index to showUi
            if (ignoresWrites) return
            if (index > safeIndex && index > this.index) {
                this.index = maxOf(this.index, safeIndex)
                return
            }
            this.index = index
            // Stock Android: a non-zero index unmutes, zero mutes.
            muted = index == 0
        }

        override fun unmute() {
            muted = false
        }
    }

    private class Gain(var volume: Float = 1f)

    private fun volume(stream: FakeStream, gain: Gain = Gain(), sdkInt: Int = 35) =
        DeviceVolume(stream, readGain = { gain.volume }, writeGain = { gain.volume = it }, sdkInt = sdkInt)

    @Test
    fun aSwipeStartsAtTheDeviceVolumeAndMovesIt() {
        val stream = FakeStream(index = 6)
        val gain = Gain()
        val control = volume(stream, gain)

        assertEquals(0.4f, control.current(), 1e-6f)
        assertEquals(10f / 15f, control.set(0.66f), 1e-6f)
        assertEquals(10, stream.index)
        assertEquals(0f, control.set(0f))
        assertEquals(0, stream.index)
        // Android's own panel stays away; the player's gain never moves.
        assertTrue(stream.writes.none { it.second })
        assertEquals(1f, gain.volume)
    }

    @Test
    fun movingWithinOneStepWritesNothing() {
        val stream = FakeStream(index = 6)
        val control = volume(stream)
        control.current()
        control.set(0.40f)
        control.set(0.42f)
        assertTrue(stream.writes.isEmpty())
        control.set(0.47f)
        control.set(0.48f)
        control.set(0.49f)
        assertEquals(listOf(7 to false), stream.writes)
    }

    @Test
    fun aRaiseFromMutedUnmutes() {
        val stream = FakeStream(index = 9, muted = true)
        val control = volume(stream)
        assertEquals(0f, control.current())
        assertEquals(1f / 15f, control.set(0.07f), 1e-6f)
        assertFalse(stream.muted)
        assertEquals(1, stream.index)
    }

    @Test
    fun beforeAndroid12AHeldBackRaiseAsksOnceWithAndroidsWarningAndShowsWhatTook() {
        val stream = FakeStream(index = 9).apply { safeIndex = 10 }
        val control = volume(stream, sdkInt = 30)
        control.current()
        assertEquals(10f / 15f, control.set(10f / 15f), 1e-6f)
        // Past the safe level the read-back is what the HUD shows.
        assertEquals(10f / 15f, control.set(11f / 15f), 1e-6f)
        assertEquals(listOf(10 to false, 11 to false, 11 to true), stream.writes)
        // One warning a swipe: the rest of the swipe asks quietly.
        control.set(12f / 15f)
        assertEquals(12 to false, stream.writes.last())
        assertEquals(1, stream.writes.count { it.second })
        // The next swipe may ask again.
        control.current()
        control.set(13f / 15f)
        assertEquals(listOf(13 to false, 13 to true), stream.writes.takeLast(2))
    }

    @Test
    fun fromAndroid12AHeldBackRaiseIsNeverAskedAgainWithAndroidsPanel() {
        val stream = FakeStream(index = 9).apply { safeIndex = 10 }
        val control = volume(stream, sdkInt = 31)
        control.current()
        assertEquals(10f / 15f, control.set(10f / 15f), 1e-6f)
        // The system showed its warning for the quiet write; the pill shows the safe level.
        assertEquals(10f / 15f, control.set(11f / 15f), 1e-6f)
        assertEquals(10f / 15f, control.set(12f / 15f), 1e-6f)
        assertEquals(listOf(10 to false, 11 to false, 12 to false), stream.writes)
    }

    @Test
    fun aFixedVolumeDeviceFallsBackToThePlayersGain() {
        val stream = FakeStream(index = 15, isFixed = true)
        val gain = Gain()
        val control = volume(stream, gain)
        assertEquals(1f, control.current())
        assertEquals(0.5f, control.set(0.5f))
        assertEquals(0.5f, gain.volume)
        assertTrue(stream.writes.isEmpty())
        // The next swipe starts where the gain is.
        assertEquals(0.5f, control.current())
    }

    @Test
    fun aRouteThatKeepsTheStreamAtItsMaximumFallsBackToTheGainUntilItMoves() {
        val stream = FakeStream(index = 15).apply { ignoresWrites = true }
        val gain = Gain()
        val control = volume(stream, gain)
        assertEquals(1f, control.current())
        // The lowering doesn't take: the gain stands in from here.
        assertEquals(0.8f, control.set(0.8f))
        assertEquals(0.8f, gain.volume)
        assertEquals(0.6f, control.set(0.6f))
        assertEquals(0.6f, gain.volume)
        assertEquals(1, stream.writes.size)
        // Still pinned on the next swipe.
        assertEquals(0.6f, control.current())
        // Another route, below its maximum: the device volume governs again and the gain goes back to 1.
        stream.ignoresWrites = false
        stream.index = 7
        assertEquals(7f / 15f, control.current(), 1e-6f)
        assertEquals(1f, gain.volume)
    }

    @Test
    fun aLoweringThatDoesNotTakeBelowTheMaximumKeepsTheDeviceVolume() {
        // Do Not Disturb refusing a write is no reason to start lowering the gain.
        val stream = FakeStream(index = 9).apply { ignoresWrites = true }
        val gain = Gain()
        val control = volume(stream, gain)
        control.current()
        assertEquals(9f / 15f, control.set(0.3f), 1e-6f)
        assertEquals(1f, gain.volume)
    }

    @Test
    fun aGainLeftLoweredGoesBackToOneWhenTheStreamCanMove() {
        val stream = FakeStream(index = 4)
        val gain = Gain(volume = 0.3f)
        val control = volume(stream, gain)
        assertEquals(4f / 15f, control.current(), 1e-6f)
        assertEquals(1f, gain.volume)
    }

    @Test
    fun anUnreadableStreamUsesTheGain() {
        val gain = Gain(volume = 0.7f)
        val unreadable = object : MusicStream {
            override val isFixed = false
            override fun read(): MusicStreamState? = null
            override fun write(index: Int, showUi: Boolean) = error("never written")
            override fun unmute() = error("never unmuted")
        }
        val control = DeviceVolume(unreadable, readGain = { gain.volume }, writeGain = { gain.volume = it }, sdkInt = 35)
        assertEquals(0.7f, control.current())
        assertEquals(0.2f, control.set(0.2f))
        assertEquals(0.2f, gain.volume)
    }
}
