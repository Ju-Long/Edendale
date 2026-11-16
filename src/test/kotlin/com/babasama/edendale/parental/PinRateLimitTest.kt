package com.babasama.edendale.parental

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinRateLimitTest {
    private val wall = 1_762_000_000_000L
    private val elapsed = 50_000L

    @Test
    fun theFirstThreeFailuresAreFree() {
        var limit = PinRateLimit()
        repeat(3) {
            limit = limit.recordFailure(wall, elapsed)
            assertEquals(0L, limit.retryAfterMillis(wall, elapsed))
            assertFalse(limit.isLocked(wall, elapsed))
        }
        assertEquals(3, limit.failures)
        assertEquals(0L, limit.lockedUntilWall)
        limit = limit.recordFailure(wall, elapsed)
        assertEquals(4, limit.failures)
        assertEquals(30_000L, limit.retryAfterMillis(wall, elapsed))
        assertTrue(limit.isLocked(wall, elapsed))
    }

    @Test
    fun theDelayGrowsWithEveryFurtherFailure() {
        var limit = PinRateLimit(failures = 3)
        val expected = listOf(30_000L, 60_000L, 5 * 60_000L, 15 * 60_000L, 60 * 60_000L, 60 * 60_000L)
        for (delay in expected) {
            limit = limit.recordFailure(wall, elapsed)
            assertEquals(delay, limit.retryAfterMillis(wall, elapsed), "after ${limit.failures} failures")
            assertEquals(wall + delay, limit.lockedUntilWall)
            assertEquals(elapsed + delay, limit.lockedUntilElapsed)
        }
    }

    @Test
    fun theLaterDeadlineWins() {
        val limit = PinRateLimit(failures = 3).recordFailure(wall, elapsed)
        // Ten seconds on the wall, five on the uptime clock: the uptime clock has more left.
        assertEquals(25_000L, limit.retryAfterMillis(wall + 10_000, elapsed + 5_000))
        // Wall set forward by an hour: the uptime clock still counts.
        assertEquals(25_000L, limit.retryAfterMillis(wall + 3_600_000, elapsed + 5_000))
        // Uptime moved more than the wall: the wall has more left.
        assertEquals(20_000L, limit.retryAfterMillis(wall + 10_000, elapsed + 25_000))
    }

    @Test
    fun aClockTrickDoesNotShortenTheWaitAndAStaleDeadlineDoesNotLengthenIt() {
        val limit = PinRateLimit(failures = 3).recordFailure(wall, elapsed)
        // Wall set back by a day: that deadline is no longer trusted; the uptime clock decides.
        assertEquals(25_000L, limit.retryAfterMillis(wall - 86_400_000, elapsed + 5_000))
        // A reboot restarted the uptime clock: that deadline is dropped; the wall decides.
        assertEquals(20_000L, limit.retryAfterMillis(wall + 10_000, 1_000))
        // Days later, after a reboot, nothing is left to wait: the lock does not re-arm.
        assertEquals(0L, limit.retryAfterMillis(wall + 86_400_000, 3_000))
        assertFalse(limit.isLocked(wall + 86_400_000, 3_000))
    }

    @Test
    fun anExpiredDeadlineUnlocksButKeepsCounting() {
        val limit = PinRateLimit(failures = 3).recordFailure(wall, elapsed)
        assertEquals(0L, limit.retryAfterMillis(wall + 30_000, elapsed + 30_000))
        assertEquals(4, limit.failures)
        val next = limit.recordFailure(wall + 30_000, elapsed + 30_000)
        assertEquals(5, next.failures)
        assertEquals(60_000L, next.retryAfterMillis(wall + 30_000, elapsed + 30_000))
    }

    @Test
    fun aCorrectPinResetsEverything() {
        val locked = PinRateLimit(failures = 3).recordFailure(wall, elapsed).recordFailure(wall, elapsed)
        assertEquals(PinRateLimit(), locked.recordSuccess())
        assertEquals(0L, locked.recordSuccess().retryAfterMillis(wall, elapsed))
    }
}
