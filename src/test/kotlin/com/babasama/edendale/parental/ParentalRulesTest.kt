package com.babasama.edendale.parental

import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.tmdb.ContentCertificationLookup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParentalRulesTest {

    @Test
    fun usMovieCeilingAllowsAtOrUnderAndBlocksAbove() {
        val ceiling = RatingCeiling("US", movie = "PG-13", tv = null)
        for (label in listOf("G", "PG", "PG-13", "PG 13", "pg13")) {
            assertTrue(ParentalRules.allows(label, MediaType.MOVIE, ceiling, lists), label)
        }
        for (label in listOf("R", "NC-17", "NR", "", "Not Rated", "Unrated", "XYZ")) {
            assertFalse(ParentalRules.allows(label, MediaType.MOVIE, ceiling, lists), label)
        }
        // No TV limit: every rated TV title passes the ceiling.
        assertTrue(ParentalRules.allows("TV-MA", MediaType.TV, ceiling, lists))
    }

    @Test
    fun gbTwelveAIsUnderTwelve() {
        val gb = ParentalRules.parseCertificationLists(moviePayload, tvPayload, "GB")!!
        val twelveA = RatingCeiling("GB", movie = "12A", tv = null)
        assertTrue(ParentalRules.allows("U", MediaType.MOVIE, twelveA, gb))
        assertTrue(ParentalRules.allows("12A", MediaType.MOVIE, twelveA, gb))
        assertFalse(ParentalRules.allows("12", MediaType.MOVIE, twelveA, gb))
        assertFalse(ParentalRules.allows("15", MediaType.MOVIE, twelveA, gb))

        val twelve = RatingCeiling("GB", movie = "12", tv = null)
        assertTrue(ParentalRules.allows("12A", MediaType.MOVIE, twelve, gb))
        assertTrue(ParentalRules.allows("12", MediaType.MOVIE, twelve, gb))
        assertFalse(ParentalRules.allows("15", MediaType.MOVIE, twelve, gb))
        // The same labels on the TV scale, where U is order 0.
        val tvTwelve = RatingCeiling("GB", movie = null, tv = "12")
        assertTrue(ParentalRules.allows("U", MediaType.TV, tvTwelve, gb))
        assertFalse(ParentalRules.allows("15", MediaType.TV, tvTwelve, gb))
    }

    @Test
    fun aTvLabelNeverQualifiesAMovie() {
        val ceiling = RatingCeiling("US", movie = "R", tv = "TV-MA")
        assertFalse(ParentalRules.allows("TV-14", MediaType.MOVIE, ceiling, lists))
        assertTrue(ParentalRules.allows("TV-14", MediaType.TV, ceiling, lists))
        // The lowest TV labels sit under a TV-PG ceiling; the movie scale is not consulted.
        val tvPg = RatingCeiling("US", movie = null, tv = "TV-PG")
        assertTrue(ParentalRules.allows("TV-Y", MediaType.TV, tvPg, lists))
        assertTrue(ParentalRules.allows("TV-G", MediaType.TV, tvPg, lists))
        assertFalse(ParentalRules.allows("TV-14", MediaType.TV, tvPg, lists))
        assertFalse(ParentalRules.allows("PG", MediaType.TV, tvPg, lists))
    }

    @Test
    fun missingListsOrAnotherRegionFailClosed() {
        val ceiling = RatingCeiling("US", movie = "PG-13", tv = null)
        assertFalse(ParentalRules.allows("PG", MediaType.MOVIE, ceiling, null))
        val gb = ParentalRules.parseCertificationLists(moviePayload, tvPayload, "GB")!!
        assertFalse(ParentalRules.allows("PG", MediaType.MOVIE, ceiling, gb))
        // A ceiling label the list no longer carries restricts everything.
        val stale = RatingCeiling("US", movie = "PG-15", tv = null)
        assertFalse(ParentalRules.allows("G", MediaType.MOVIE, stale, lists))
    }

    @Test
    fun decisionCombinesThePresetAndTheCeiling() {
        val found = { label: String -> ContentCertificationLookup.Found(label) }
        // Nothing on: everything passes, nothing is looked at.
        assertEquals(AudienceDecision.ALLOWED, ParentalRules.decision(found("R"), MediaType.MOVIE, ParentalPolicy.OFF, lists))
        assertEquals(AudienceDecision.ALLOWED, ParentalRules.decision(ContentCertificationLookup.Unrated, MediaType.MOVIE, ParentalPolicy.OFF, null))

        val preset = ParentalPolicy(youngAudienceFriendly = true)
        assertEquals(AudienceDecision.ALLOWED, ParentalRules.decision(found("PG"), MediaType.MOVIE, preset, null))
        assertEquals(AudienceDecision.BLOCKED, ParentalRules.decision(found("G"), MediaType.MOVIE, preset, null))
        assertEquals(AudienceDecision.BLOCKED, ParentalRules.decision(ContentCertificationLookup.Unrated, MediaType.TV, preset, null))
        assertEquals(AudienceDecision.UNAVAILABLE, ParentalRules.decision(ContentCertificationLookup.Unavailable, MediaType.MOVIE, preset, null))

        val ceiling = ParentalPolicy(ceiling = RatingCeiling("US", movie = "PG-13", tv = "TV-14"))
        assertEquals(AudienceDecision.ALLOWED, ParentalRules.decision(found("G"), MediaType.MOVIE, ceiling, lists))
        assertEquals(AudienceDecision.BLOCKED, ParentalRules.decision(found("R"), MediaType.MOVIE, ceiling, lists))
        assertEquals(AudienceDecision.ALLOWED, ParentalRules.decision(found("TV-Y7"), MediaType.TV, ceiling, lists))
        assertEquals(AudienceDecision.BLOCKED, ParentalRules.decision(found("TV-MA"), MediaType.TV, ceiling, lists))
        assertEquals(AudienceDecision.BLOCKED, ParentalRules.decision(ContentCertificationLookup.Unrated, MediaType.MOVIE, ceiling, lists))
        assertEquals(AudienceDecision.UNAVAILABLE, ParentalRules.decision(ContentCertificationLookup.Unavailable, MediaType.TV, ceiling, lists))
        // A ceiling without lists restricts every rated title until they arrive.
        assertEquals(AudienceDecision.BLOCKED, ParentalRules.decision(found("G"), MediaType.MOVIE, ceiling, null))

        val both = ParentalPolicy(youngAudienceFriendly = true, ceiling = RatingCeiling("US", movie = "R", tv = "TV-MA"))
        assertEquals(AudienceDecision.BLOCKED, ParentalRules.decision(found("G"), MediaType.MOVIE, both, lists))
        assertEquals(AudienceDecision.ALLOWED, ParentalRules.decision(found("PG-13"), MediaType.MOVIE, both, lists))
        assertEquals(AudienceDecision.BLOCKED, ParentalRules.decision(found("R"), MediaType.MOVIE, both, lists))
        assertEquals(AudienceDecision.ALLOWED, ParentalRules.decision(found("TV-14"), MediaType.TV, both, lists))
        assertEquals(AudienceDecision.BLOCKED, ParentalRules.decision(found("TV-MA"), MediaType.TV, both, lists))
    }

    @Test
    fun parsesTheRecordedListsInOrder() {
        val us = ParentalRules.parseCertificationList(moviePayload, "us")!!
        assertEquals(listOf("NR", "G", "PG", "PG-13", "R", "NC-17"), us.map { it.certification })
        assertEquals(listOf(0, 1, 2, 3, 4, 5), us.map { it.order })
        val gbTv = ParentalRules.parseCertificationList(tvPayload, "GB")!!
        assertEquals("U", gbTv.first().certification)
        assertEquals(0, gbTv.first().order)
        // An entry without an order or a label is skipped, not guessed.
        assertEquals(listOf("0", "6", "12", "16", "18"), ParentalRules.parseCertificationList(moviePayload, "DE")!!.map { it.certification })
        assertNull(ParentalRules.parseCertificationList(moviePayload, "FR"))
        assertNull(ParentalRules.parseCertificationList(Json.parseToJsonElement("{}").jsonObject, "US"))
        assertEquals(setOf("US", "GB", "SG", "DE", "JP"), ParentalRules.availableRegions(moviePayload))
    }

    @Test
    fun fallsBackToTheUnitedStatesWhenTheRegionHasNoList() {
        assertEquals("GB", ParentalRules.effectiveRegion("gb", setOf("US", "GB")))
        assertEquals("US", ParentalRules.effectiveRegion("FR", setOf("US", "GB")))
        assertEquals("US", ParentalRules.effectiveRegion("", setOf("US", "GB")))

        val de = ParentalRules.parseCertificationLists(moviePayload, tvPayload, "de")!!
        assertEquals("DE", de.region)
        // JP has a movie list but no TV list: one region for both types, so US.
        val jp = ParentalRules.parseCertificationLists(moviePayload, tvPayload, "JP")!!
        assertEquals("US", jp.region)
        assertEquals(7, jp.tv.size)
        assertEquals("US", ParentalRules.parseCertificationLists(moviePayload, tvPayload, "FR")!!.region)
        assertNull(ParentalRules.parseCertificationLists(Json.parseToJsonElement("{}").jsonObject, tvPayload, "US"))

        // The lookups follow the lists once there are any, and the device otherwise.
        assertEquals("US", ParentalRules.effectiveRegion("FR", jp))
        assertEquals("DE", ParentalRules.effectiveRegion("DE", de))
        assertEquals("FR", ParentalRules.effectiveRegion("fr", null))
    }

    @Test
    fun retryDelayFollowsTheSchedule() {
        for (failures in 0..3) assertEquals(0L, ParentalRules.retryDelayMillis(failures))
        assertEquals(30_000L, ParentalRules.retryDelayMillis(4))
        assertEquals(60_000L, ParentalRules.retryDelayMillis(5))
        assertEquals(5 * 60_000L, ParentalRules.retryDelayMillis(6))
        assertEquals(15 * 60_000L, ParentalRules.retryDelayMillis(7))
        assertEquals(60 * 60_000L, ParentalRules.retryDelayMillis(8))
        assertEquals(60 * 60_000L, ParentalRules.retryDelayMillis(40))
    }

    @Test
    fun policyIsActiveAndKeyedByItsTitleRulesOnly() {
        assertFalse(ParentalPolicy.OFF.isActive)
        assertFalse(ParentalPolicy(ceiling = RatingCeiling("US", null, null)).isActive)
        assertTrue(ParentalPolicy(ceiling = RatingCeiling("US", null, "TV-14")).isActive)
        assertTrue(ParentalPolicy(youngAudienceFriendly = true).isActive)
        assertFalse(ParentalPolicy(lockSettings = true, lockLibraryEdits = true, blockTrailersAndLinks = true).isActive)

        val base = ParentalPolicy(ceiling = RatingCeiling("US", "PG-13", null))
        assertEquals(base.key, base.copy(lockSettings = true, hideRestrictedTitles = false, blockTrailersAndLinks = true).key)
        assertNotEquals(base.key, base.copy(youngAudienceFriendly = true).key)
        assertNotEquals(base.key, base.copy(ceiling = RatingCeiling("US", "R", null)).key)
        assertNotEquals(base.key, base.copy(ceiling = RatingCeiling("GB", "PG-13", null)).key)
        assertNotEquals(base.key, base.copy(ceiling = null).key)
    }

    private val lists: CertificationLists by lazy { ParentalRules.parseCertificationLists(moviePayload, tvPayload, "US")!! }

    // Excerpts of TMDB's `/certification/movie/list` and `/certification/tv/list` bodies (2026-11-08).
    private val moviePayload by lazy {
        json(
            """
            {"certifications": {
              "US": [
                {"certification": "NR", "meaning": "No rating information.", "order": 0},
                {"certification": "R", "meaning": "Under 17 requires accompanying parent or adult guardian 21 or older.", "order": 4},
                {"certification": "G", "meaning": "All ages admitted.", "order": 1},
                {"certification": "PG-13", "meaning": "Some material may be inappropriate for children under 13.", "order": 3},
                {"certification": "PG", "meaning": "Some material may not be suitable for children.", "order": 2},
                {"certification": "NC-17", "meaning": "No one 17 and under admitted.", "order": 5}
              ],
              "GB": [
                {"certification": "U", "meaning": "All ages admitted.", "order": 1},
                {"certification": "PG", "meaning": "Parental guidance.", "order": 2},
                {"certification": "12A", "meaning": "Under 12s with an adult.", "order": 3},
                {"certification": "12", "meaning": "12 and over.", "order": 4},
                {"certification": "15", "meaning": "15 and over.", "order": 5},
                {"certification": "18", "meaning": "Adults only.", "order": 6},
                {"certification": "R18", "meaning": "Licensed premises only.", "order": 7}
              ],
              "SG": [
                {"certification": "G", "meaning": "General.", "order": 1},
                {"certification": "PG", "meaning": "Parental guidance.", "order": 2},
                {"certification": "PG13", "meaning": "Parental guidance for children below 13.", "order": 3},
                {"certification": "NC16", "meaning": "No children below 16.", "order": 4},
                {"certification": "M18", "meaning": "Mature 18.", "order": 5},
                {"certification": "R21", "meaning": "Restricted 21.", "order": 6}
              ],
              "DE": [
                {"certification": "0", "meaning": "No age restriction.", "order": 1},
                {"certification": "6", "meaning": "6 and over.", "order": 2},
                {"certification": "12", "meaning": "12 and over.", "order": 3},
                {"certification": "16", "meaning": "16 and over.", "order": 4},
                {"certification": "18", "meaning": "Adults only.", "order": 5},
                {"certification": "", "meaning": "A stray blank label.", "order": 6},
                {"certification": "FSK?", "meaning": "No order."}
              ],
              "JP": [
                {"certification": "G", "meaning": "General.", "order": 1},
                {"certification": "PG12", "meaning": "Parental guidance under 12.", "order": 2},
                {"certification": "R15+", "meaning": "15 and over.", "order": 3},
                {"certification": "R18+", "meaning": "18 and over.", "order": 4}
              ]
            }}
            """.trimIndent(),
        )
    }

    private val tvPayload by lazy {
        json(
            """
            {"certifications": {
              "US": [
                {"certification": "NR", "meaning": "No rating information.", "order": 0},
                {"certification": "TV-Y", "meaning": "All children.", "order": 1},
                {"certification": "TV-Y7", "meaning": "Directed to older children.", "order": 2},
                {"certification": "TV-G", "meaning": "General audience.", "order": 3},
                {"certification": "TV-PG", "meaning": "Parental guidance suggested.", "order": 4},
                {"certification": "TV-14", "meaning": "Parents strongly cautioned.", "order": 5},
                {"certification": "TV-MA", "meaning": "Mature audiences only.", "order": 6}
              ],
              "GB": [
                {"certification": "U", "meaning": "Universal.", "order": 0},
                {"certification": "PG", "meaning": "Parental guidance.", "order": 1},
                {"certification": "12A", "meaning": "Under 12s with an adult.", "order": 2},
                {"certification": "12", "meaning": "12 and over.", "order": 3},
                {"certification": "15", "meaning": "15 and over.", "order": 4},
                {"certification": "18", "meaning": "Adults only.", "order": 5},
                {"certification": "R18", "meaning": "Licensed premises only.", "order": 6}
              ],
              "SG": [
                {"certification": "G", "meaning": "General.", "order": 1},
                {"certification": "PG", "meaning": "Parental guidance.", "order": 2},
                {"certification": "PG13", "meaning": "Parental guidance for children below 13.", "order": 3},
                {"certification": "NC16", "meaning": "No children below 16.", "order": 4},
                {"certification": "M18", "meaning": "Mature 18.", "order": 5},
                {"certification": "R21", "meaning": "Restricted 21.", "order": 6}
              ],
              "DE": [
                {"certification": "0", "meaning": "No age restriction.", "order": 1},
                {"certification": "6", "meaning": "6 and over.", "order": 2},
                {"certification": "12", "meaning": "12 and over.", "order": 3},
                {"certification": "16", "meaning": "16 and over.", "order": 4},
                {"certification": "18", "meaning": "Adults only.", "order": 5}
              ]
            }}
            """.trimIndent(),
        )
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
}
