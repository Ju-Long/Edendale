package com.babasama.edendale.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchQueryTest {
    @Test
    fun everyPeopleAliasScopesToPeople() {
        for (alias in listOf("actor", "actors", "actress", "actresses", "person", "people", "cast")) {
            val query = SearchQuery.parse("$alias: tom hanks")
            assertEquals(SearchScope.PEOPLE, query.scope, "alias `$alias:`")
            assertEquals("tom hanks", query.term, "alias `$alias:`")
        }
    }

    @Test
    fun everyMovieAliasScopesToMovies() {
        for (alias in listOf("movie", "movies", "film", "films")) {
            val query = SearchQuery.parse("$alias: alien")
            assertEquals(SearchScope.MOVIES, query.scope, "alias `$alias:`")
            assertEquals("alien", query.term, "alias `$alias:`")
        }
    }

    @Test
    fun everyShowAliasScopesToShows() {
        for (alias in listOf("show", "shows", "tv", "series")) {
            val query = SearchQuery.parse("$alias: severance")
            assertEquals(SearchScope.SHOWS, query.scope, "alias `$alias:`")
            assertEquals("severance", query.term, "alias `$alias:`")
        }
    }

    @Test
    fun prefixMatchingIgnoresCaseAndSurroundingWhitespace() {
        assertEquals(SearchQuery(SearchScope.PEOPLE, "tom hanks"), SearchQuery.parse("Actors: tom hanks"))
        assertEquals(SearchQuery(SearchScope.PEOPLE, "tom hanks"), SearchQuery.parse("ACTORS:tom hanks"))
        assertEquals(SearchQuery(SearchScope.MOVIES, "alien"), SearchQuery.parse("  Movies :   alien  "))
    }

    @Test
    fun unrecognisedPrefixStaysLiteralText() {
        // Titles legitimately contain colons — eating them would make the
        // title unsearchable.
        val query = SearchQuery.parse("Alien: Romulus")
        assertEquals(SearchScope.ALL, query.scope)
        assertEquals("Alien: Romulus", query.term)
        assertFalse(query.isScoped)
    }

    @Test
    fun onlyALeadingKeywordCounts() {
        // The colon belongs to the title, not to a filter.
        val query = SearchQuery.parse("Star Trek: movies of the week")
        assertEquals(SearchScope.ALL, query.scope)
        assertEquals("Star Trek: movies of the week", query.term)
    }

    @Test
    fun noColonIsAlwaysAnUnscopedSearch() {
        val query = SearchQuery.parse("  blade runner  ")
        assertEquals(SearchScope.ALL, query.scope)
        assertEquals("blade runner", query.term)
    }

    @Test
    fun prefixWithNoTermIsScopedButAwaitingInput() {
        val query = SearchQuery.parse("actors:")
        assertEquals(SearchScope.PEOPLE, query.scope)
        assertEquals("", query.term)
        assertTrue(query.isAwaitingTerm)
    }

    @Test
    fun emptyInputIsAnUnscopedEmptyTerm() {
        val query = SearchQuery.parse("")
        assertEquals(SearchScope.ALL, query.scope)
        assertEquals("", query.term)
        assertFalse(query.isAwaitingTerm)
    }

    @Test
    fun onlyTheFirstColonSplitsTheQuery() {
        val query = SearchQuery.parse("movies: Alien: Romulus")
        assertEquals(SearchScope.MOVIES, query.scope)
        assertEquals("Alien: Romulus", query.term)
    }
}
