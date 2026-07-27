package com.babasama.edendale.tmdb

import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.SearchScope
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScopedSearchTest {
    @Test
    fun peopleScopeCallsSearchPersonAndLeadsWithPeople() = runScopedTest {
        val transport = RecordingTransport()
        val result = BrowseRepository(TmdbApi(transport)).searchScoped("actors: tom hanks")

        assertEquals(SearchScope.PEOPLE, result.scope)
        assertEquals("tom hanks", result.term)
        assertTrue(result.leadsWithPeople)
        assertTrue("/search/person" in transport.paths)
        // Titles are the additive section under a people scope.
        assertTrue("/search/multi" in transport.paths)
        assertEquals("Tom Hanks", result.people.single().name)
        assertEquals(listOf("tom hanks"), transport.queriesFor("/search/person"))
    }

    @Test
    fun movieScopeUsesTheMovieEndpointOnly() = runScopedTest {
        val transport = RecordingTransport()
        val result = BrowseRepository(TmdbApi(transport)).searchScoped("movies: alien")

        assertEquals(listOf("/search/movie"), transport.paths)
        assertEquals(listOf("alien"), transport.queriesFor("/search/movie"))
        assertEquals(MediaType.MOVIE, result.titles.single().mediaType)
        assertTrue(result.people.isEmpty())
        assertEquals(false, result.leadsWithPeople)
    }

    @Test
    fun showScopeUsesTheTvEndpointAndTagsResultsAsTv() = runScopedTest {
        val transport = RecordingTransport()
        val result = BrowseRepository(TmdbApi(transport)).searchScoped("shows: severance")

        assertEquals(listOf("/search/tv"), transport.paths)
        assertEquals(MediaType.TV, result.titles.single().mediaType)
    }

    @Test
    fun unscopedSearchFetchesBothAndLeadsWithTitles() = runScopedTest {
        val transport = RecordingTransport()
        val result = BrowseRepository(TmdbApi(transport)).searchScoped("alien")

        assertEquals(SearchScope.ALL, result.scope)
        assertTrue("/search/multi" in transport.paths)
        assertTrue("/search/person" in transport.paths)
        assertEquals(false, result.leadsWithPeople)
        assertTrue(result.titles.isNotEmpty())
        assertTrue(result.people.isNotEmpty())
    }

    @Test
    fun aFailedAdditiveLookupNeverBlanksThePrimarySection() = runScopedTest {
        // People fail; an unscoped search must still return its titles.
        val transport = RecordingTransport(failPaths = setOf("/search/person"))
        val result = BrowseRepository(TmdbApi(transport)).searchScoped("alien")

        assertTrue(result.titles.isNotEmpty())
        assertTrue(result.people.isEmpty())
    }

    @Test
    fun aPrefixWithNoTermSkipsTheNetworkEntirely() = runScopedTest {
        val transport = RecordingTransport()
        val result = BrowseRepository(TmdbApi(transport)).searchScoped("actors:")

        assertEquals(emptyList(), transport.paths)
        assertEquals(SearchScope.PEOPLE, result.scope)
        assertTrue(result.isEmpty)
    }

    @Test
    fun personDetailMapsBiographyAndVitals() = runScopedTest {
        val transport = RecordingTransport()
        val detail = BrowseRepository(TmdbApi(transport)).personDetail(31)

        assertEquals(listOf("/person/31"), transport.paths)
        assertEquals("Tom Hanks", detail.name)
        assertEquals("An American actor.", detail.biography)
        assertEquals("/profile.jpg", detail.profilePath)
        assertEquals("Acting", detail.knownForDepartment)
        assertEquals("1956 · Concord, California", detail.vitals)
        assertEquals("https://image.tmdb.org/t/p/h632/profile.jpg", detail.profileUrl())
    }

    @Test
    fun blankPersonFieldsAreDroppedRatherThanRenderedEmpty() = runScopedTest {
        val transport = RecordingTransport(personJson = sparsePersonJson)
        val detail = BrowseRepository(TmdbApi(transport)).personDetail(31)

        assertEquals(null, detail.biography)
        assertEquals(null, detail.placeOfBirth)
        assertEquals(null, detail.vitals)
    }
}

private class RecordingTransport(
    private val failPaths: Set<String> = emptySet(),
    private val personJson: String = fullPersonJson,
) : TmdbTransport {
    override val isConfigured: Boolean = true

    val paths = mutableListOf<String>()
    private val queries = mutableListOf<Pair<String, String?>>()

    fun queriesFor(path: String): List<String> =
        queries.filter { it.first == path }.mapNotNull { it.second }

    override suspend fun get(path: String, parameters: Map<String, String>): String {
        paths += path
        queries += path to parameters["query"]
        if (path in failPaths) throw IllegalStateException("scripted failure for $path")
        return when {
            path == "/search/person" -> peopleJson
            path.startsWith("/person/") -> personJson
            else -> titlesJson
        }
    }
}

private const val titlesJson = """
    {"results": [{"id": 348, "title": "Alien", "release_date": "1979-05-25"}]}
"""

private const val peopleJson = """
    {
      "results": [
        {"id": 31, "name": "Tom Hanks", "profile_path": "/profile.jpg",
         "known_for": [{"title": "Big"}, {"name": "Band of Brothers"}]}
      ]
    }
"""

private const val fullPersonJson = """
    {
      "id": 31,
      "name": "Tom Hanks",
      "biography": "An American actor.",
      "profile_path": "/profile.jpg",
      "birthday": "1956-07-09",
      "place_of_birth": "Concord, California",
      "known_for_department": "Acting"
    }
"""

private const val sparsePersonJson = """
    {"id": 31, "name": "Tom Hanks", "biography": "", "place_of_birth": ""}
"""

/**
 * `searchScoped` fans out with `async`, so — unlike the sequential helpers in
 * the sibling test files — this one runs on `Dispatchers.Unconfined`. The fake
 * transport never really suspends, so every child completes eagerly on this
 * thread and the whole round finishes synchronously without pulling in
 * kotlinx-coroutines-test.
 */
private fun runScopedTest(block: suspend () -> Unit) {
    var outcome: Result<Unit>? = null
    block.startCoroutine(
        Continuation(Dispatchers.Unconfined) { result -> outcome = result },
    )
    outcome?.getOrThrow() ?: error("Scoped search test did not complete synchronously.")
}
