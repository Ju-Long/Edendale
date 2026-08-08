package com.babasama.edendale.tmdb

import com.babasama.edendale.domain.MediaType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentCertificationTest {

    @Test
    fun policyAcceptsOnlyPgPg13AndTvEquivalents() {
        for (certification in listOf("PG", "pg", "PG-13", "PG 13", "PG13")) {
            assertTrue(YoungAudienceCertificationPolicy.allows(certification, MediaType.MOVIE))
        }
        for (certification in listOf("G", "R", "NC-17", "M18", "", "Not Rated")) {
            assertFalse(YoungAudienceCertificationPolicy.allows(certification, MediaType.MOVIE))
        }

        assertTrue(YoungAudienceCertificationPolicy.allows("TV-PG", MediaType.TV))
        assertTrue(YoungAudienceCertificationPolicy.allows("TV-14", MediaType.TV))
        assertFalse(YoungAudienceCertificationPolicy.allows("TV-G", MediaType.TV))
        assertFalse(YoungAudienceCertificationPolicy.allows("TV-MA", MediaType.TV))
        // A TV label never qualifies a movie.
        assertFalse(YoungAudienceCertificationPolicy.allows("TV-14", MediaType.MOVIE))
    }

    @Test
    fun movieCertificationUsesRequestedRegionAndTheatricalPrecedence() {
        val payload = json(
            """
            {"results": [
              {"iso_3166_1": "SG", "release_dates": [
                {"certification": "PG", "release_date": "2026-02-01", "type": 6},
                {"certification": "PG13", "release_date": "2026-01-01", "type": 3}
              ]},
              {"iso_3166_1": "US", "release_dates": [
                {"certification": "R", "release_date": "2026-01-02", "type": 3}
              ]},
              {"iso_3166_1": "GB", "release_dates": [
                {"certification": "PG", "release_date": "2026-01-01", "type": 3},
                {"certification": "15", "release_date": "2026-02-01", "type": 3}
              ]}
            ]}
            """.trimIndent(),
        )

        // Theatrical (type 3) outranks the TV (type 6) release.
        assertEquals("PG13", parseMovieCertification(payload, "sg"))
        assertEquals("R", parseMovieCertification(payload, "US"))
        // Two distinct certifications at the best tier is ambiguous — no guess.
        assertNull(parseMovieCertification(payload, "GB"))
        // A region with no release data returns nothing.
        assertNull(parseMovieCertification(payload, "CA"))
    }

    @Test
    fun televisionCertificationUsesOnlyTheRequestedRegion() {
        val payload = json(
            """
            {"results": [
              {"iso_3166_1": "SG", "rating": "PG13"},
              {"iso_3166_1": "US", "rating": "TV-14"}
            ]}
            """.trimIndent(),
        )

        assertEquals("PG13", parseTvCertification(payload, "SG"))
        assertEquals("TV-14", parseTvCertification(payload, "us"))
        assertNull(parseTvCertification(payload, "CA"))
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
