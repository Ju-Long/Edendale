package com.babasama.edendale.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Parity with Apple's `AppRouteTests.swift`: the same round-trip, unicode, and
 * rejection cases, extended to cover the web-link spelling of every route.
 */
class AppRouteTest {
    private val localId = "8ba77310-e230-46d0-ba65-12e31d5ea25b"

    private val everyRoute = listOf(
        AppRoute.Search("Blade Runner & more"),
        AppRoute.Media(MediaRef(78, MediaType.MOVIE)),
        AppRoute.Media(MediaRef(1399, MediaType.TV)),
        AppRoute.LocalMovie(localId),
        AppRoute.LocalShow(localId),
        AppRoute.PlayMovie(78),
        AppRoute.PlayEpisode(63056),
        AppRoute.PlayLocalMovie(localId),
        AppRoute.PlayLocalEpisode(localId),
    )

    @Test
    fun everyRouteRoundTripsThroughItsDeepLink() {
        for (route in everyRoute) {
            assertEquals(route, AppRoute.parse(route.deepLink()), route.deepLink())
        }
    }

    @Test
    fun everyRouteRoundTripsThroughItsWebLink() {
        for (route in everyRoute) {
            assertEquals(route, AppRoute.parse(route.webLink()), route.webLink())
        }
    }

    /** The two spellings are the same contract — neither may drift. */
    @Test
    fun bothSpellingsOfARouteResolveIdentically() {
        for (route in everyRoute) {
            assertEquals(AppRoute.parse(route.deepLink()), AppRoute.parse(route.webLink()))
        }
    }

    @Test
    fun searchLinksPreserveUnicodeAndPunctuation() {
        val route = AppRoute.Search("Amélie: 2001 / restored 🎬")
        assertEquals(route, AppRoute.parse(route.deepLink()))
        assertEquals(route, AppRoute.parse(route.webLink()))
    }

    @Test
    fun searchAcceptsPlusEncodedSpaces() {
        assertEquals(
            AppRoute.Search("blade runner"),
            AppRoute.parse("edendale://search?q=blade+runner"),
        )
    }

    @Test
    fun webLinksOnlyResolveForTheVerifiedHost() {
        assertNull(AppRoute.parse("https://example.com/media/movie/78"))
        assertEquals(
            AppRoute.Media(MediaRef(78, MediaType.MOVIE)),
            AppRoute.parse("https://${AppRoute.HOST}/media/movie/78"),
        )
    }

    /** Hosts are case-insensitive, and a port or userinfo does not change identity. */
    @Test
    fun hostMatchingIgnoresCasePortAndUserInfo() {
        val expected = AppRoute.Media(MediaRef(78, MediaType.MOVIE))
        assertEquals(expected, AppRoute.parse("https://${AppRoute.HOST.uppercase()}/media/movie/78"))
        assertEquals(expected, AppRoute.parse("https://${AppRoute.HOST}:443/media/movie/78"))
    }

    @Test
    fun fragmentsAndTrailingSlashesAreIgnored() {
        assertEquals(
            AppRoute.Media(MediaRef(78, MediaType.MOVIE)),
            AppRoute.parse("https://${AppRoute.HOST}/media/movie/78/#cast"),
        )
    }

    @Test
    fun localIdsAreNormalisedToLowercase() {
        assertEquals(
            AppRoute.LocalMovie(localId),
            AppRoute.parse("edendale://library/movie/${localId.uppercase()}"),
        )
    }

    @Test
    fun rejectsUnknownOrIncompleteLinks() {
        val rejected = listOf(
            "https://example.com/movie/78",
            "edendale://search",
            "edendale://search?q=",
            "edendale://search?q=%20%20",
            "edendale://play/movie/not-an-id",
            "edendale://library/movie/not-a-uuid",
            "edendale://media/person/78",
            "edendale://media/movie",
            "edendale://media/movie/78/extra",
            "edendale://elsewhere/movie/78",
            "edendale://",
            "http://${AppRoute.HOST}/media/movie/78",
            "not a url",
            "",
        )
        for (link in rejected) {
            assertNull(AppRoute.parse(link), link)
        }
    }
}
