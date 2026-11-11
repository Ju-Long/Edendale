package com.babasama.edendale.parental

/**
 * The failed-PIN back-off (O-D3) as a value: the failure count and the
 * deadline on both clocks, so a force-stop or a clock change does not reset
 * it. A deadline is only trusted while it is at most one delay away: further
 * than that means the wall clock was set back or the device rebooted, and that
 * clock is ignored while the other still counts. Zero means no deadline.
 */
data class PinRateLimit(
    val failures: Int = 0,
    val lockedUntilWall: Long = 0L,
    val lockedUntilElapsed: Long = 0L,
) {
    /** The state after another wrong PIN at ([nowWall], [nowElapsed]). */
    fun recordFailure(nowWall: Long, nowElapsed: Long): PinRateLimit {
        val count = failures + 1
        val delay = ParentalRules.retryDelayMillis(count)
        return if (delay == 0L) PinRateLimit(count) else PinRateLimit(count, nowWall + delay, nowElapsed + delay)
    }

    /** A correct PIN clears the count and both deadlines. */
    fun recordSuccess(): PinRateLimit = PinRateLimit()

    /** How long the next attempt has to wait, on whichever clock says longer; 0 when an attempt may be made. */
    fun retryAfterMillis(nowWall: Long, nowElapsed: Long): Long {
        val delay = ParentalRules.retryDelayMillis(failures)
        if (delay == 0L) return 0L
        return maxOf(remaining(lockedUntilWall, nowWall, delay), remaining(lockedUntilElapsed, nowElapsed, delay))
    }

    fun isLocked(nowWall: Long, nowElapsed: Long): Boolean = retryAfterMillis(nowWall, nowElapsed) > 0L

    private fun remaining(deadline: Long, now: Long, delay: Long): Long {
        if (deadline == 0L) return 0L
        val left = deadline - now
        return if (left in 1..delay) left else 0L
    }
}
