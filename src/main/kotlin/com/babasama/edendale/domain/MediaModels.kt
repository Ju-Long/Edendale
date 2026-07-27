package com.babasama.edendale.domain

/** The two title types returned by TMDB browse and search endpoints. */
enum class MediaType(val pathSegment: String) {
    MOVIE("movie"),
    TV("tv"),
}

/** Lightweight, platform-neutral handle used to request a title's details. */
data class MediaRef(
    val id: Int,
    val mediaType: MediaType,
)

/** A movie or show rendered in shelves, search results, and collections. */
data class MediaItem(
    val id: Int,
    val mediaType: MediaType,
    val title: String,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val voteAverage: Double? = null,
    val releaseDate: String? = null,
) {
    val ref: MediaRef get() = MediaRef(id, mediaType)
    val year: Int?
        get() = releaseDate
            ?.takeIf { it.length >= 4 }
            ?.take(4)
            ?.toIntOrNull()

    fun posterUrl(size: TmdbImageSize = TmdbImageSize.POSTER): String? =
        tmdbImageUrl(posterPath, size)

    fun backdropUrl(size: TmdbImageSize = TmdbImageSize.BACKDROP): String? =
        tmdbImageUrl(backdropPath, size)
}

enum class TmdbImageSize(val pathSegment: String) {
    POSTER("w342"),
    POSTER_LARGE("w500"),
    BACKDROP("w780"),
    /** Cast-row and people-grid portraits. */
    PROFILE("w185"),
    /** The person page's hero portrait. */
    PROFILE_LARGE("h632"),
    ORIGINAL("original"),
}

fun tmdbImageUrl(path: String?, size: TmdbImageSize): String? =
    path?.let { "https://image.tmdb.org/t/p/${size.pathSegment}$it" }

data class Genre(
    val id: Int,
    val name: String,
)

/** An actor/actress from people search: portrait plus best-known titles. */
data class PersonItem(
    val id: Int,
    val name: String,
    val profilePath: String? = null,
    val knownFor: List<String> = emptyList(),
) {
    fun profileUrl(size: TmdbImageSize = TmdbImageSize.PROFILE): String? =
        tmdbImageUrl(profilePath, size)
}

data class CastMember(
    val id: Int,
    val name: String,
    val character: String? = null,
    val profilePath: String? = null,
) {
    fun profileUrl(size: TmdbImageSize = TmdbImageSize.PROFILE): String? =
        tmdbImageUrl(profilePath, size)
}

/**
 * A person's full TMDB record — the header of a person page. The filmography
 * itself stays a separate call (`/person/{id}/combined_credits`) so the two
 * can be fetched concurrently.
 */
data class PersonDetail(
    val id: Int,
    val name: String,
    val biography: String? = null,
    val profilePath: String? = null,
    val birthday: String? = null,
    val deathday: String? = null,
    val placeOfBirth: String? = null,
    val knownForDepartment: String? = null,
) {
    fun profileUrl(size: TmdbImageSize = TmdbImageSize.PROFILE_LARGE): String? =
        tmdbImageUrl(profilePath, size)

    /**
     * "1956 – 2016 · Concord, California" — the vitals line under the name.
     * Null when TMDB knows neither a date nor a place.
     */
    val vitals: String?
        get() {
            val born = birthday?.take(4)?.takeIf { it.length == 4 }
            val died = deathday?.take(4)?.takeIf { it.length == 4 }
            val lifespan = when {
                born != null && died != null -> "$born – $died"
                born != null -> born
                died != null -> "– $died"
                else -> null
            }
            return listOfNotNull(lifespan, placeOfBirth)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" · ")
        }
}

/** One entry in a show's season list, surfaced on [MediaDetail.seasons]. */
data class SeasonSummary(
    val seasonNumber: Int,
    val name: String,
    val episodeCount: Int? = null,
    val airDate: String? = null,
    val posterPath: String? = null,
    val overview: String? = null,
) {
    fun posterUrl(size: TmdbImageSize = TmdbImageSize.POSTER): String? =
        tmdbImageUrl(posterPath, size)
}

/** Full title data used by Android's hero and detail UIs. */
data class MediaDetail(
    val ref: MediaRef,
    val title: String,
    val tagline: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val year: Int? = null,
    val runtimeMinutes: Int? = null,
    val genres: List<String> = emptyList(),
    val attribution: String? = null,
    val score: Double? = null,
    val voteCount: Int? = null,
    val cast: List<CastMember> = emptyList(),
    val seasonCount: Int? = null,
    val episodeCount: Int? = null,
    val seasons: List<SeasonSummary> = emptyList(),
) {
    fun posterUrl(size: TmdbImageSize = TmdbImageSize.POSTER_LARGE): String? =
        tmdbImageUrl(posterPath, size)

    fun backdropUrl(size: TmdbImageSize = TmdbImageSize.ORIGINAL): String? =
        tmdbImageUrl(backdropPath, size)
}

enum class WatchMediaType { MOVIE, EPISODE }

/** Persisted Android watch state. */
data class WatchProgress(
    val tmdbId: Int,
    val mediaType: WatchMediaType,
    val position: Double = 0.0,
    val watchedSeconds: Double = 0.0,
    val lastWatchedEpochMillis: Long = 0L,
    val isCompleted: Boolean = false,
    val showTmdbId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
) {
    val normalizedPosition: Double get() = position.coerceIn(0.0, 1.0)
    val storageKey: String get() = "${mediaType.name.lowercase()}:$tmdbId"
}

sealed interface ParsedMedia {
    data class Movie(val title: String, val year: Int?) : ParsedMedia
    data class Episode(val showName: String, val season: Int, val episode: Int) : ParsedMedia
}
