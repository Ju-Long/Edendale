package com.babasama.edendale.introdb

import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Service for querying TheIntroDB timestamp metadata.
 * Enforces rate-limiting cooldown and delegates transport to [IntroDbTransport].
 */
class IntroDbService(
    private val transport: IntroDbTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var retryAfterMillis: Long = 0L

    val isRateLimited: Boolean get() = clock() < retryAfterMillis

    suspend fun segments(request: IntroDbRequest): List<PlaybackSegment> {
        val now = clock()
        if (now < retryAfterMillis) {
            throw IntroDbException.RateLimited()
        }
        val response = transport.execute(request.urlString)
        if (response.statusCode == 429) {
            val cooldownSeconds = calculateCooldown(response)
            retryAfterMillis = clock() + (cooldownSeconds * 1000L)
            throw IntroDbException.RateLimited()
        }
        if (response.statusCode == 404) {
            return emptyList()
        }
        if (response.statusCode !in 200..299) {
            throw IntroDbException.BadStatus(response.statusCode)
        }
        val body = response.body ?: throw IntroDbException.InvalidResponse()
        return IntroDbDecoder.decode(body, request)
    }

    private fun calculateCooldown(response: IntroDbResponse): Long {
        val headers = listOf("Retry-After", "X-RateLimit-Reset", "X-UsageLimit-Reset")
        val delays = headers.mapNotNull { name ->
            response.getHeader(name)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        }
        val maxDelay = delays.maxOrNull() ?: 60.0
        return max(60.0, maxDelay).roundToLong()
    }

    companion object {
        fun decode(rawJson: String, request: IntroDbRequest): List<PlaybackSegment> =
            IntroDbDecoder.decode(rawJson, request)
    }
}
