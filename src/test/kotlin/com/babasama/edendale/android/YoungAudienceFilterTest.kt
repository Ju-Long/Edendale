package com.babasama.edendale.android

import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.tmdb.ContentCertificationLookup
import kotlin.test.Test
import kotlin.test.assertEquals

class YoungAudienceFilterTest {

    @Test
    fun decisionFailsClosedForEverythingButPgAndPg13() {
        assertEquals(
            AudienceDecision.ALLOWED,
            audienceDecision(ContentCertificationLookup.Found("PG"), MediaType.MOVIE),
        )
        assertEquals(
            AudienceDecision.ALLOWED,
            audienceDecision(ContentCertificationLookup.Found("PG-13"), MediaType.MOVIE),
        )
        assertEquals(
            AudienceDecision.ALLOWED,
            audienceDecision(ContentCertificationLookup.Found("TV-14"), MediaType.TV),
        )
        assertEquals(
            AudienceDecision.BLOCKED,
            audienceDecision(ContentCertificationLookup.Found("R"), MediaType.MOVIE),
        )
        // A TV label never qualifies a movie.
        assertEquals(
            AudienceDecision.BLOCKED,
            audienceDecision(ContentCertificationLookup.Found("TV-14"), MediaType.MOVIE),
        )
        // Unrated fails closed; an error can be retried.
        assertEquals(
            AudienceDecision.BLOCKED,
            audienceDecision(ContentCertificationLookup.Unrated, MediaType.TV),
        )
        assertEquals(
            AudienceDecision.UNAVAILABLE,
            audienceDecision(ContentCertificationLookup.Unavailable, MediaType.MOVIE),
        )
    }

    @Test
    fun visibleKeepsOnlyAllowedTitlesInOrderWhenEnabled() {
        val pg = item(10, MediaType.MOVIE)
        val restricted = item(20, MediaType.MOVIE)
        val tv14 = item(30, MediaType.TV)
        val unknown = item(40, MediaType.TV)
        val items = listOf(tv14, restricted, pg, unknown)
        val decisions = mapOf(
            pg.ref to AudienceDecision.ALLOWED,
            restricted.ref to AudienceDecision.BLOCKED,
            tv14.ref to AudienceDecision.ALLOWED,
            // `unknown` is absent — unverified titles fail closed.
        )

        // Preserves source order among the allowed titles.
        assertEquals(listOf(tv14, pg), visibleItems(items, decisions, enabled = true))
        // Off is a verbatim bypass, even with decisions present.
        assertEquals(items, visibleItems(items, decisions, enabled = false))
    }

    private fun item(id: Int, type: MediaType) = MediaItem(
        id = id,
        mediaType = type,
        title = "Title $id",
    )
}
