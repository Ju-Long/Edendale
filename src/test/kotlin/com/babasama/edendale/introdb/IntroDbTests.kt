package com.babasama.edendale.introdb

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntroDbTests {
    private val movie = IntroDbMedia.create(278)!!

    @Test
    fun requestsContainOnlyCanonicalIdentityAndRuntime() {
        val media = IntroDbMedia.create(tmdbId = 1396, season = 2, episode = 8)
        assertNotNull(media)
        val request = IntroDbRequest.create(media = media, durationSeconds = 2700.125)
        assertNotNull(request)
        assertEquals(
            "https://api.theintrodb.org/v3/media?tmdb_id=1396&season=2&episode=8&duration_ms=2700125",
            request.urlString,
        )

        val movieRequest = IntroDbRequest.create(media = movie, durationSeconds = 7200.0)
        assertNotNull(movieRequest)
        assertEquals(
            "https://api.theintrodb.org/v3/media?tmdb_id=278&duration_ms=7200000",
            movieRequest.urlString,
        )
    }

    @Test
    fun unsupportedIdentityAndDurationNeverMakeARequest() {
        assertNull(IntroDbMedia.create(0))
        assertNull(IntroDbMedia.create(10_000_001))
        assertNull(IntroDbMedia.create(1396, season = 0, episode = 1))
        assertNull(IntroDbMedia.create(1396, season = 1, episode = 0))
        assertNull(IntroDbMedia.create(1396, season = 1, episode = null))
        assertNull(IntroDbMedia.create(1396, season = null, episode = 1))

        for (duration in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 21_601.0)) {
            assertNull(IntroDbRequest.create(media = movie, durationSeconds = duration))
        }

        assertNotNull(IntroDbRequest.create(media = movie, durationSeconds = 21_600.0))
    }

    @Test
    fun decodesMultipleRangesAndPreservesCreditSceneGaps() {
        val request = IntroDbRequest.create(media = movie, durationSeconds = 120.0)!!
        val json = """
        {"tmdb_id":278,"type":"movie",
         "intro":[{"start_ms":null,"end_ms":10000}],
         "recap":[{"start_ms":20000,"end_ms":30000}],
         "credits":[{"start_ms":90000,"end_ms":100000},{"start_ms":110000,"end_ms":null}],
         "preview":[{"start_ms":30000,"end_ms":40000}]}
        """.trimIndent()

        val segments = IntroDbService.decode(json, request)
        assertEquals(listOf(SegmentKind.INTRO, SegmentKind.RECAP, SegmentKind.CREDITS, SegmentKind.CREDITS), segments.map { it.kind })
        assertEquals(listOf(0.0, 20.0, 90.0, 110.0), segments.map { it.start })
        assertEquals(listOf(10.0, 30.0, 100.0, 120.0), segments.map { it.end })
        assertEquals(listOf(false, false, false, true), segments.map { it.reachesEnd })

        assertFalse(segments.any { it.contains(105.0) })
        assertTrue(segments[0].contains(0.0))
        assertFalse(segments[0].contains(10.0))
    }

    @Test
    fun ignoresNoSegmentInvalidAndOverlappingRanges() {
        val request = IntroDbRequest.create(media = movie, durationSeconds = 120.0)!!
        val json = """
        {"tmdb_id":278,"type":"movie",
         "intro":[{"start_ms":null,"end_ms":null},{"start_ms":null,"end_ms":0},
                  {"start_ms":-1000,"end_ms":3000},{"start_ms":5000,"end_ms":4000},
                  {"start_ms":10000,"end_ms":20000},{"start_ms":30000,"end_ms":40000},
                  {"start_ms":30000,"end_ms":40000},{"start_ms":100000,"end_ms":121000}],
         "recap":[{"start_ms":15000,"end_ms":25000}],
         "credits":[{"start_ms":null,"end_ms":110000},{"start_ms":0,"end_ms":null}]}
        """.trimIndent()

        val segments = IntroDbService.decode(json, request)
        assertEquals(
            listOf(
                PlaybackSegment(
                    kind = SegmentKind.INTRO,
                    startMs = 30000,
                    endMs = 40000,
                    reachesEnd = false,
                )
            ),
            segments,
        )
    }

    @Test
    fun missingArraysAndMismatchedResponses() {
        val request = IntroDbRequest.create(media = movie, durationSeconds = 120.0)!!
        assertTrue(IntroDbService.decode("""{"tmdb_id":278,"type":"movie"}""", request).isEmpty())

        for (json in listOf(
            """{"tmdb_id":279,"type":"movie"}""",
            """{"tmdb_id":278,"type":"tv","season":1,"episode":1}""",
            """{"tmdb_id":278,"type":"movie","season":1}""",
            """{"tmdb_id":278,"type":"movie","intro":"invalid"}""",
        )) {
            assertFailsWith<IntroDbException.InvalidResponse> {
                IntroDbService.decode(json, request)
            }
        }

        val episode = IntroDbRequest.create(
            media = IntroDbMedia.create(1396, season = 1, episode = 2)!!,
            durationSeconds = 120.0,
        )!!
        assertFailsWith<IntroDbException.InvalidResponse> {
            IntroDbService.decode("""{"tmdb_id":1396,"type":"tv","season":1,"episode":3}""", episode)
        }
    }

    @Test
    fun notFoundProducesNoSegmentsAndRateLimitPreventsImmediateRetry() = runBlocking {
        val request = IntroDbRequest.create(media = movie, durationSeconds = 120.0)!!
        val missingService = IntroDbService(
            transport = object : IntroDbTransport {
                override suspend fun execute(urlString: String): IntroDbResponse =
                    IntroDbResponse(statusCode = 404, headers = emptyMap(), body = null)
            }
        )
        assertTrue(missingService.segments(request).isEmpty())

        var callCount = 0
        var currentClock = 1000L
        val rateLimitedService = IntroDbService(
            transport = object : IntroDbTransport {
                override suspend fun execute(urlString: String): IntroDbResponse {
                    callCount++
                    return IntroDbResponse(
                        statusCode = 429,
                        headers = mapOf("X-UsageLimit-Reset" to "3600"),
                        body = null,
                    )
                }
            },
            clock = { currentClock },
        )

        for (i in 0 until 2) {
            assertFailsWith<IntroDbException.RateLimited> {
                rateLimitedService.segments(request)
            }
        }
        assertEquals(1, callCount)

        // Verify minimum 60s cooldown floor
        var floorCallCount = 0
        val floorService = IntroDbService(
            transport = object : IntroDbTransport {
                override suspend fun execute(urlString: String): IntroDbResponse {
                    floorCallCount++
                    return IntroDbResponse(
                        statusCode = 429,
                        headers = mapOf("Retry-After" to "10"),
                        body = null,
                    )
                }
            },
            clock = { currentClock },
        )
        assertFailsWith<IntroDbException.RateLimited> {
            floorService.segments(request)
        }
        assertEquals(1, floorCallCount)

        // Still blocked after 50 seconds
        currentClock += 50_000L
        assertFailsWith<IntroDbException.RateLimited> {
            floorService.segments(request)
        }
        assertEquals(1, floorCallCount)

        // Allowed after 61 seconds (floor of 60s passed)
        currentClock += 11_000L
        assertFailsWith<IntroDbException.RateLimited> {
            floorService.segments(request)
        }
        assertEquals(2, floorCallCount)
    }
}
