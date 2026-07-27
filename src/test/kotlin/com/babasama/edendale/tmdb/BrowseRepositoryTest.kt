package com.babasama.edendale.tmdb

import com.babasama.edendale.domain.MediaType
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseRepositoryTest {

    @Test
    fun testReleaseCountsAggregatesAcrossPages() = runCommonTest {
        val transport = FakePagingTransport()
        val api = TmdbApi(transport)
        val repository = BrowseRepository(api)

        val counts = repository.releaseCounts(2026)

        // 2026-03-12 is duplicated on page 1 and page 2, but has different IDs so it's 2 separate movies.
        // Wait, fake transport needs to return different IDs for same date if we want it counted twice,
        // or same ID if we want it deduplicated.
        // Let's check our FakePagingTransport.
        assertEquals(2, counts["2026-03-12"])
        assertEquals(1, counts["2026-05-10"])
        assertEquals(1, counts["2026-01-01"])
    }
}

private class FakePagingTransport : TmdbTransport {
    override val isConfigured: Boolean = true

    override suspend fun get(path: String, parameters: Map<String, String>): String {
        val page = parameters["page"]?.toIntOrNull() ?: 1
        return when (page) {
            1 -> """
                {
                  "results": [
                    {"id": 1, "media_type": "movie", "title": "A", "release_date": "2026-03-12"},
                    {"id": 2, "media_type": "movie", "title": "B", "release_date": "2026-05-10"}
                  ]
                }
            """.trimIndent()
            2 -> """
                {
                  "results": [
                    {"id": 1, "media_type": "movie", "title": "A", "release_date": "2026-03-12"},
                    {"id": 3, "media_type": "movie", "title": "C", "release_date": "2026-03-12"},
                    {"id": 4, "media_type": "movie", "title": "D", "release_date": "2026-01-01"}
                  ]
                }
            """.trimIndent()
            else -> """{"results": []}"""
        }
    }
}

private fun runCommonTest(block: suspend () -> Unit) {
    var outcome: Result<Unit>? = null
    block.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                outcome = result
            }
        },
    )
    checkNotNull(outcome) { "Test coroutine unexpectedly suspended." }.getOrThrow()
}
