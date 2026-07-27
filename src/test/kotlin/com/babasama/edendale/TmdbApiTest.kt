package com.babasama.edendale

import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.tmdb.MissingTmdbCredentialsException
import com.babasama.edendale.tmdb.TmdbApi
import com.babasama.edendale.tmdb.TmdbTransport
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TmdbApiTest {
    @Test
    fun mixedBrowseFiltersPeopleAndMapsMoviesAndShows() = runCommonTest {
        val api = TmdbApi(FakeTransport(resultsJson))
        val items = api.trending()

        assertEquals(2, items.size)
        assertEquals(MediaType.MOVIE, items[0].mediaType)
        assertEquals("A Film", items[0].title)
        assertEquals(MediaType.TV, items[1].mediaType)
        assertEquals("A Series", items[1].title)
    }

    @Test
    fun movieDetailMapsSharedHeroFields() = runCommonTest {
        val api = TmdbApi(FakeTransport(movieDetailJson))
        val detail = api.mediaDetail(MediaRef(10, MediaType.MOVIE))

        assertEquals("Archive Film", detail.title)
        assertEquals(2024, detail.year)
        assertEquals("Directed by Jane Director", detail.attribution)
        assertEquals(listOf("Drama"), detail.genres)
        assertEquals("Lead Actor", detail.cast.single().name)
    }

    @Test
    fun episodeDetailMapsPlaybackMetadataAndExactPath() = runCommonTest {
        val transport = FakeTransport(episodeDetailJson)
        val detail = TmdbApi(transport).episodeDetail(showId = 93405, season = 2, episode = 3)

        assertEquals("/tv/93405/season/2/episode/3", transport.lastPath)
        assertEquals(emptyMap(), transport.lastParameters)
        assertEquals(420001, detail.id)
        assertEquals("Who Is Alive?", detail.name)
        assertEquals("/episode-still.jpg", detail.stillPath)
        assertEquals("2025-01-31", detail.airDate)
        assertEquals(53, detail.runtimeMinutes)
        assertEquals(2, detail.seasonNumber)
        assertEquals(3, detail.episodeNumber)
    }

    @Test
    fun seasonDetailMapsEpisodeListAndExactPath() = runCommonTest {
        val transport = FakeTransport(seasonDetailJson)
        val detail = TmdbApi(transport).season(showId = 93405, seasonNumber = 2)

        assertEquals("/tv/93405/season/2", transport.lastPath)
        assertEquals(emptyMap(), transport.lastParameters)
        assertEquals(2, detail.seasonNumber)
        assertEquals("Book Two: Earth", detail.name)
        assertEquals("2025-01-31", detail.airDate)
        assertEquals("/season-poster.jpg", detail.posterPath)
        assertEquals(listOf("Who Is Alive?", "The Awakening"), detail.episodes.map { it.name })
        assertEquals(listOf(3, 4), detail.episodes.mapNotNull { it.episodeNumber })
    }

    @Test
    fun tvDetailMapsSeasonSummaries() = runCommonTest {
        val detail = TmdbApi(FakeTransport(tvDetailJson)).mediaDetail(MediaRef(93405, MediaType.TV))

        // Numbered seasons ascending, Specials last, announced-but-empty
        // seasons dropped — the order a season picker renders.
        assertEquals(listOf(1, 2, 0), detail.seasons.map { it.seasonNumber })
        assertEquals("Book Two: Earth", detail.seasons[1].name)
        assertEquals(10, detail.seasons[1].episodeCount)
        assertEquals("Specials", detail.seasons.last().name)
    }

    @Test
    fun filmographyMapsMovieAndTvCreditsDeduplicatedNewestFirst() = runCommonTest {
        val transport = FakeTransport(personCreditsJson)
        val items = TmdbApi(transport).filmography(personId = 1253360)

        assertEquals("/person/1253360/combined_credits", transport.lastPath)
        assertEquals(listOf(20, 20, 10), items.map { it.id })
        assertEquals(listOf(MediaType.TV, MediaType.MOVIE, MediaType.MOVIE), items.map { it.mediaType })
        assertEquals(listOf("Recent Series", "Colliding Film ID", "Older Film"), items.map { it.title })
    }

    @Test
    fun bestTrailerPrefersOfficialYoutubeTrailer() = runCommonTest {
        val transport = FakeTransport(videosJson)
        val trailer = TmdbApi(transport).bestTrailer(MediaRef(603, MediaType.MOVIE))

        assertEquals("/movie/603/videos", transport.lastPath)
        assertEquals("officialKey", trailer?.key)
        assertEquals("Official Trailer", trailer?.name)
        assertEquals(true, trailer?.official)
    }

    @Test
    fun bestTrailerFallsBackToYoutubeTeaser() = runCommonTest {
        val trailer = TmdbApi(FakeTransport(teaserVideosJson))
            .bestTrailer(MediaRef(66732, MediaType.TV))

        assertEquals("teaserKey", trailer?.key)
    }

    @Test
    fun missingCredentialStopsBeforeNetwork() = runCommonTest {
        val api = TmdbApi(FakeTransport("{}", isConfigured = false))
        assertFailsWith<MissingTmdbCredentialsException> { api.trending() }
    }
}

private class FakeTransport(
    private val response: String,
    override val isConfigured: Boolean = true,
) : TmdbTransport {
    var lastPath: String? = null
        private set
    var lastParameters: Map<String, String> = emptyMap()
        private set

    override suspend fun get(path: String, parameters: Map<String, String>): String {
        lastPath = path
        lastParameters = parameters
        return response
    }
}

/** These fake-transport API tests complete synchronously, so no test scheduler is needed. */
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

private val resultsJson = """
    {
      "results": [
        {"id": 1, "media_type": "movie", "title": "A Film", "release_date": "2025-01-02"},
        {"id": 2, "media_type": "tv", "name": "A Series", "first_air_date": "2023-02-03"},
        {"id": 3, "media_type": "person", "name": "A Person"}
      ]
    }
""".trimIndent()

private val movieDetailJson = """
    {
      "id": 10,
      "title": "Archive Film",
      "release_date": "2024-04-05",
      "runtime": 122,
      "genres": [{"id": 18, "name": "Drama"}],
      "credits": {
        "crew": [{"id": 4, "name": "Jane Director", "job": "Director"}],
        "cast": [{"id": 5, "name": "Lead Actor", "character": "Lead"}]
      }
    }
""".trimIndent()

private val episodeDetailJson = """
    {
      "id": 420001,
      "name": "Who Is Alive?",
      "overview": "The team searches for answers.",
      "still_path": "/episode-still.jpg",
      "air_date": "2025-01-31",
      "runtime": 53,
      "season_number": 2,
      "episode_number": 3,
      "vote_average": 8.4
    }
""".trimIndent()

private val seasonDetailJson = """
    {
      "id": 111,
      "season_number": 2,
      "name": "Book Two: Earth",
      "overview": "The team presses on.",
      "air_date": "2025-01-31",
      "poster_path": "/season-poster.jpg",
      "episodes": [
        {"id": 420001, "name": "Who Is Alive?", "still_path": "/e3.jpg", "air_date": "2025-01-31", "runtime": 53, "season_number": 2, "episode_number": 3, "vote_average": 8.4},
        {"id": 420002, "name": "The Awakening", "still_path": "/e4.jpg", "air_date": "2025-02-07", "runtime": 49, "season_number": 2, "episode_number": 4, "vote_average": 8.1}
      ]
    }
""".trimIndent()

private val tvDetailJson = """
    {
      "id": 93405,
      "name": "Archive Series",
      "first_air_date": "2021-09-17",
      "number_of_seasons": 2,
      "number_of_episodes": 18,
      "genres": [{"id": 18, "name": "Drama"}],
      "seasons": [
        {"season_number": 0, "name": "Specials", "episode_count": 3, "air_date": "2021-08-01"},
        {"season_number": 2, "name": "Book Two: Earth", "episode_count": 10, "air_date": "2025-01-31"},
        {"season_number": 1, "name": "Book One: Water", "episode_count": 8, "air_date": "2021-09-17"},
        {"season_number": 3, "name": "Book Three: Fire", "episode_count": 0, "air_date": null}
      ]
    }
""".trimIndent()

private val personCreditsJson = """
    {
      "cast": [
        {"id": 10, "media_type": "movie", "title": "Older Film", "release_date": "2020-02-03"},
        {"id": 20, "media_type": "tv", "name": "Recent Series", "first_air_date": "2025-04-05"},
        {"id": 20, "media_type": "movie", "title": "Colliding Film ID", "release_date": "2024-03-04"},
        {"id": 20, "media_type": "tv", "name": "Duplicate Credit", "first_air_date": "2025-04-05"},
        {"id": 30, "media_type": "person", "name": "Not a title"}
      ]
    }
""".trimIndent()

private val videosJson = """
    {
      "results": [
        {"id": "a", "key": "clipKey", "site": "YouTube", "type": "Clip", "official": true},
        {"id": "b", "key": "fanKey", "site": "YouTube", "type": "Trailer", "official": false},
        {"id": "c", "key": "vimeoKey", "site": "Vimeo", "type": "Trailer", "official": true},
        {"id": "d", "key": "officialKey", "name": "Official Trailer", "site": "YouTube", "type": "Trailer", "official": true}
      ]
    }
""".trimIndent()

private val teaserVideosJson = """
    {
      "results": [
        {"id": "a", "key": "vimeoKey", "site": "Vimeo", "type": "Trailer", "official": true},
        {"id": "b", "key": "teaserKey", "site": "YouTube", "type": "Teaser"}
      ]
    }
""".trimIndent()
