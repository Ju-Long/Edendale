package com.babasama.edendale.tmdb

import com.babasama.edendale.domain.CastMember
import com.babasama.edendale.domain.Genre
import com.babasama.edendale.domain.MediaDetail
import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.PersonDetail
import com.babasama.edendale.domain.PersonItem
import com.babasama.edendale.domain.SeasonSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Android HTTP boundary for TMDB request construction and response mapping. */
interface TmdbTransport {
    val isConfigured: Boolean
    suspend fun get(path: String, parameters: Map<String, String> = emptyMap()): String

    /**
     * Write request (TMDB account features): `method` is POST or DELETE and
     * `jsonBody` is an optional `application/json` payload. Read-only test
     * transports can keep this default.
     */
    suspend fun send(
        method: String,
        path: String,
        parameters: Map<String, String> = emptyMap(),
        jsonBody: String? = null,
    ): String = throw UnsupportedOperationException(
        "TMDB account requests are not supported on this platform.",
    )
}

class MissingTmdbCredentialsException : IllegalStateException(
    "No TMDB credential is configured for this build.",
)

data class TmdbEpisodeDetail(
    val id: Int,
    val name: String,
    val overview: String? = null,
    val stillPath: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val voteAverage: Double? = null,
)

/** One entry in a show's season list (TMDB TV detail `seasons` array). */
data class TmdbSeasonSummary(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int? = null,
    val airDate: String? = null,
    val posterPath: String? = null,
    val overview: String? = null,
)

data class TmdbSeasonDetail(
    val seasonNumber: Int,
    val name: String,
    val overview: String? = null,
    val airDate: String? = null,
    val posterPath: String? = null,
    val episodes: List<TmdbEpisodeDetail> = emptyList(),
)

data class TmdbVideo(
    val id: String,
    val key: String,
    val name: String? = null,
    val site: String? = null,
    val type: String? = null,
    val official: Boolean? = null,
)

/** Thin Android-native TMDB v3 client. */
class TmdbApi(private val transport: TmdbTransport) {
    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = transport.isConfigured

    suspend fun trending(window: String = "day"): List<MediaItem> =
        items(fetch("/trending/all/$window"), MediaType.MOVIE)

    suspend fun popular(type: MediaType): List<MediaItem> =
        items(fetch("/${type.pathSegment}/popular"), type)

    suspend fun topRated(type: MediaType): List<MediaItem> =
        items(fetch("/${type.pathSegment}/top_rated"), type)

    suspend fun discover(type: MediaType, genreId: Int? = null): List<MediaItem> =
        items(
            fetch(
                "/discover/${type.pathSegment}",
                buildMap {
                    put("sort_by", "popularity.desc")
                    put("include_adult", "false")
                    genreId?.let { put("with_genres", it.toString()) }
                },
            ),
            type,
        )

    suspend fun search(query: String): List<MediaItem> =
        items(
            fetch(
                "/search/multi",
                mapOf("query" to query, "include_adult" to "false"),
            ),
            MediaType.MOVIE,
        )

    /** Movies only — the `movies:`/`films:` search scope. */
    suspend fun searchMovies(query: String): List<MediaItem> =
        items(
            fetch(
                "/search/movie",
                mapOf("query" to query, "include_adult" to "false"),
            ),
            MediaType.MOVIE,
        )

    /** Shows only — the `shows:`/`series:`/`tv:` search scope. */
    suspend fun searchShows(query: String): List<MediaItem> =
        items(
            fetch(
                "/search/tv",
                mapOf("query" to query, "include_adult" to "false"),
            ),
            MediaType.TV,
        )

    /** Actors/actresses matching the query, in TMDB's relevance order. */
    suspend fun searchPeople(query: String): List<PersonItem> =
        fetch(
            "/search/person",
            mapOf("query" to query, "include_adult" to "false"),
        ).array("results").mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            PersonItem(
                id = item.int("id") ?: return@mapNotNull null,
                name = item.string("name") ?: return@mapNotNull null,
                profilePath = item.string("profile_path"),
                knownFor = item.array("known_for")
                    .mapNotNull { known ->
                        (known as? JsonObject)?.let { it.string("title") ?: it.string("name") }
                    }
                    .take(3),
            )
        }

    /**
     * One page of movies whose primary release date falls inside
     * [`from`, `to`] (both "yyyy-MM-dd", inclusive), most popular first —
     * parity with Apple's TMDBService.discoverMoviesReleased.
     */
    suspend fun moviesReleased(from: String, to: String, page: Int = 1): List<MediaItem> =
        items(
            fetch(
                "/discover/movie",
                mapOf(
                    "sort_by" to "popularity.desc",
                    "include_adult" to "false",
                    "primary_release_date.gte" to from,
                    "primary_release_date.lte" to to,
                    "page" to page.toString(),
                ),
            ),
            MediaType.MOVIE,
        )

    suspend fun movieGenres(): List<Genre> =
        fetch("/genre/movie/list").array("genres").mapNotNull { element ->
            val objectValue = element as? JsonObject ?: return@mapNotNull null
            val id = objectValue.int("id") ?: return@mapNotNull null
            val name = objectValue.string("name") ?: return@mapNotNull null
            Genre(id, name)
        }

    suspend fun mediaDetail(ref: MediaRef): MediaDetail {
        val objectValue = fetch(
            "/${ref.mediaType.pathSegment}/${ref.id}",
            mapOf("append_to_response" to "credits"),
        )
        return when (ref.mediaType) {
            MediaType.MOVIE -> movieDetail(objectValue)
            MediaType.TV -> tvDetail(objectValue)
        }
    }

    suspend fun episodeDetail(
        showId: Int,
        season: Int,
        episode: Int,
    ): TmdbEpisodeDetail {
        require(showId > 0) { "Show id must be positive." }
        require(season >= 0) { "Season must not be negative." }
        require(episode > 0) { "Episode must be positive." }
        return fetch("/tv/$showId/season/$season/episode/$episode").episodeDetail()
    }

    suspend fun season(showId: Int, seasonNumber: Int): TmdbSeasonDetail {
        require(showId > 0) { "Show id must be positive." }
        require(seasonNumber >= 0) { "Season must not be negative." }
        val item = fetch("/tv/$showId/season/$seasonNumber")
        return TmdbSeasonDetail(
            seasonNumber = item.int("season_number") ?: seasonNumber,
            name = item.string("name") ?: "Season $seasonNumber",
            overview = item.nonBlankString("overview"),
            airDate = item.string("air_date"),
            posterPath = item.string("poster_path"),
            episodes = item.array("episodes").mapNotNull { element ->
                (element as? JsonObject)?.episodeDetail()
            },
        )
    }

    /** A person's biography record — the person page header. */
    suspend fun personDetail(personId: Int): PersonDetail {
        require(personId > 0) { "Person id must be positive." }
        val item = fetch("/person/$personId")
        return PersonDetail(
            id = item.int("id") ?: personId,
            name = item.string("name") ?: "Unknown",
            biography = item.nonBlankString("biography"),
            profilePath = item.string("profile_path"),
            birthday = item.nonBlankString("birthday"),
            deathday = item.nonBlankString("deathday"),
            placeOfBirth = item.nonBlankString("place_of_birth"),
            knownForDepartment = item.nonBlankString("known_for_department"),
        )
    }

    suspend fun filmography(personId: Int): List<MediaItem> {
        require(personId > 0) { "Person id must be positive." }
        val seen = mutableSetOf<MediaRef>()
        return items(
            fetch("/person/$personId/combined_credits").array("cast"),
            MediaType.MOVIE,
        )
            .filter { seen.add(it.ref) }
            .sortedByDescending { it.releaseDate.orEmpty() }
    }

    suspend fun videos(ref: MediaRef): List<TmdbVideo> {
        require(ref.id > 0) { "Media id must be positive." }
        return fetch("/${ref.mediaType.pathSegment}/${ref.id}/videos")
            .array("results")
            .mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                TmdbVideo(
                    id = item.string("id") ?: return@mapNotNull null,
                    key = item.string("key") ?: return@mapNotNull null,
                    name = item.string("name"),
                    site = item.string("site"),
                    type = item.string("type"),
                    official = item.boolean("official"),
                )
            }
    }

    suspend fun bestTrailer(ref: MediaRef): TmdbVideo? {
        val youtube = videos(ref).filter { it.site == "YouTube" }
        val trailers = youtube.filter { it.type == "Trailer" }
        return trailers.firstOrNull { it.official == true }
            ?: trailers.firstOrNull()
            ?: youtube.firstOrNull { it.type == "Teaser" }
    }

    /**
     * The title's certification in [regionCode], or null when the region has
     * none. Movies read `/release_dates` (theatrical precedence), TV reads
     * `/content_ratings`; both feed the Young Audience filter.
     */
    suspend fun contentCertification(ref: MediaRef, regionCode: String): String? =
        when (ref.mediaType) {
            MediaType.MOVIE -> parseMovieCertification(
                fetch("/movie/${ref.id}/release_dates"),
                regionCode,
            )
            MediaType.TV -> parseTvCertification(
                fetch("/tv/${ref.id}/content_ratings"),
                regionCode,
            )
        }

    private suspend fun fetch(
        path: String,
        parameters: Map<String, String> = emptyMap(),
    ): JsonObject {
        if (!transport.isConfigured) throw MissingTmdbCredentialsException()
        return json.parseToJsonElement(transport.get(path, parameters)).jsonObject
    }

    private fun items(payload: JsonObject, defaultType: MediaType): List<MediaItem> =
        mediaItems(payload.array("results"), defaultType)

    private fun items(payload: JsonArray, defaultType: MediaType): List<MediaItem> =
        mediaItems(payload, defaultType)

    private fun movieDetail(item: JsonObject): MediaDetail {
        val credits = item.objectValue("credits")
        val director = credits
            ?.array("crew")
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.string("job") == "Director" }
            ?.string("name")
        return MediaDetail(
            ref = MediaRef(item.int("id") ?: 0, MediaType.MOVIE),
            title = item.string("title") ?: "Untitled",
            tagline = item.nonBlankString("tagline"),
            overview = item.nonBlankString("overview"),
            posterPath = item.string("poster_path"),
            backdropPath = item.string("backdrop_path"),
            year = item.string("release_date").year(),
            runtimeMinutes = item.int("runtime"),
            genres = item.names("genres"),
            attribution = director?.let { "Directed by $it" },
            score = item.double("vote_average"),
            voteCount = item.int("vote_count"),
            cast = credits.cast(),
        )
    }

    private fun tvDetail(item: JsonObject): MediaDetail {
        val credits = item.objectValue("credits")
        val creator = item.array("created_by")
            .firstOrNull()
            ?.let { it as? JsonObject }
            ?.string("name")
        return MediaDetail(
            ref = MediaRef(item.int("id") ?: 0, MediaType.TV),
            title = item.string("name") ?: "Untitled",
            tagline = item.nonBlankString("tagline"),
            overview = item.nonBlankString("overview"),
            posterPath = item.string("poster_path"),
            backdropPath = item.string("backdrop_path"),
            year = item.string("first_air_date").year(),
            runtimeMinutes = item.array("episode_run_time").firstOrNull()?.primitiveInt(),
            genres = item.names("genres"),
            attribution = creator?.let { "Created by $it" },
            score = item.double("vote_average"),
            voteCount = item.int("vote_count"),
            cast = credits.cast(),
            seasonCount = item.int("number_of_seasons"),
            episodeCount = item.int("number_of_episodes"),
            seasons = item.seasonSummaries().map(TmdbSeasonSummary::toDomain),
        )
    }
}

/** Maps a TMDB result array (movies, TV, mixed multi-results) to MediaItems. */
internal fun mediaItems(payload: JsonArray, defaultType: MediaType): List<MediaItem> =
    payload.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val type = when (item.string("media_type")) {
            null -> defaultType
            "movie" -> MediaType.MOVIE
            "tv" -> MediaType.TV
            else -> return@mapNotNull null
        }
        val id = item.int("id") ?: return@mapNotNull null
        MediaItem(
            id = id,
            mediaType = type,
            title = item.string("title") ?: item.string("name") ?: "Untitled",
            overview = item.string("overview"),
            posterPath = item.string("poster_path"),
            backdropPath = item.string("backdrop_path"),
            voteAverage = item.double("vote_average"),
            releaseDate = item.string("release_date") ?: item.string("first_air_date"),
        )
    }

private fun JsonObject.string(key: String): String? =
    get(key)?.jsonPrimitive?.contentOrNull

private fun JsonObject.nonBlankString(key: String): String? =
    string(key)?.takeIf(String::isNotBlank)

private fun JsonObject.int(key: String): Int? =
    get(key)?.jsonPrimitive?.intOrNull

private fun JsonObject.double(key: String): Double? =
    get(key)?.jsonPrimitive?.doubleOrNull

private fun JsonObject.boolean(key: String): Boolean? =
    get(key)?.jsonPrimitive?.booleanOrNull

private fun JsonObject.array(key: String): JsonArray =
    (get(key) as? JsonArray) ?: JsonArray(emptyList())

private fun JsonObject.objectValue(key: String): JsonObject? = get(key) as? JsonObject

private fun JsonObject.names(key: String): List<String> =
    array(key).mapNotNull { (it as? JsonObject)?.string("name") }

/** Common mapping for the episode endpoint and a season's episode list. */
private fun JsonObject.episodeDetail(): TmdbEpisodeDetail = TmdbEpisodeDetail(
    id = int("id") ?: 0,
    name = string("name") ?: "Untitled",
    overview = string("overview"),
    stillPath = string("still_path"),
    airDate = string("air_date"),
    runtimeMinutes = int("runtime"),
    seasonNumber = int("season_number"),
    episodeNumber = int("episode_number"),
    voteAverage = double("vote_average"),
)

/**
 * Seasons in the order a season picker should show them. TMDB lists Specials
 * as season 0 and can carry announced-but-empty seasons, so empty ones are
 * dropped and Specials sort after the numbered seasons.
 */
private fun JsonObject.seasonSummaries(): List<TmdbSeasonSummary> =
    array("seasons").mapNotNull { element ->
        val season = element as? JsonObject ?: return@mapNotNull null
        val number = season.int("season_number") ?: return@mapNotNull null
        TmdbSeasonSummary(
            seasonNumber = number,
            name = season.string("name") ?: "Season $number",
            episodeCount = season.int("episode_count"),
            airDate = season.string("air_date"),
            posterPath = season.string("poster_path"),
            overview = season.nonBlankString("overview"),
        )
    }
        .filter { (it.episodeCount ?: 0) > 0 }
        .sortedWith(compareBy({ it.seasonNumber == 0 }, { it.seasonNumber }))

private fun TmdbSeasonSummary.toDomain(): SeasonSummary = SeasonSummary(
    seasonNumber = seasonNumber,
    name = name,
    episodeCount = episodeCount,
    airDate = airDate,
    posterPath = posterPath,
    overview = overview,
)

private fun JsonObject?.cast(): List<CastMember> = this
    ?.array("cast")
    ?.take(10)
    ?.mapNotNull { element ->
        val member = element as? JsonObject ?: return@mapNotNull null
        CastMember(
            id = member.int("id") ?: return@mapNotNull null,
            name = member.string("name") ?: return@mapNotNull null,
            character = member.string("character"),
            profilePath = member.string("profile_path"),
        )
    }
    .orEmpty()

private fun String?.year(): Int? = this
    ?.takeIf { it.length >= 4 }
    ?.take(4)
    ?.toIntOrNull()

private fun JsonElement.primitiveInt(): Int? = jsonPrimitive.intOrNull
