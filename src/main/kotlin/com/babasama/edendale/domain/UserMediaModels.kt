package com.babasama.edendale.domain

/** TMDB's rating domain: 0.5–10 in half-point steps; null means unrated. */
fun sanitizeTmdbRating(value: Double?): Double? = value
    ?.let { (it * 2).toInt() / 2.0 }
    ?.coerceIn(0.5, 10.0)

/**
 * Per-title user state (favourite / watchlist / rating) keyed by TMDB id +
 * media type. Each field carries its own last-write timestamp so replicas on
 * other devices merge field-wise (newest wins), and a dirty flag so changes
 * made while disconnected are pushed on the next TMDB account sync.
 *
 * The optional display snapshot (title/poster) lets watchlist and favourite
 * shelves render without a network fetch per stored id.
 */
data class UserMediaRecord(
    val tmdbId: Int,
    val mediaType: MediaType,
    val title: String? = null,
    val posterPath: String? = null,
    val favourite: Boolean = false,
    val favouriteUpdatedAt: Long = 0L,
    val favouriteDirty: Boolean = false,
    val watchlist: Boolean = false,
    val watchlistUpdatedAt: Long = 0L,
    val watchlistDirty: Boolean = false,
    val rating: Double? = null,
    val ratingUpdatedAt: Long = 0L,
    val ratingDirty: Boolean = false,
) {
    val ref: MediaRef get() = MediaRef(tmdbId, mediaType)
    val storageKey: String get() = "${mediaType.pathSegment}:$tmdbId"

    /**
     * True while the record still says something: a set flag or rating, or a
     * pending un-set that has not been pushed to TMDB yet. Records without
     * state are pruned rather than stored.
     */
    val hasState: Boolean
        get() = favourite || watchlist || rating != null ||
            favouriteDirty || watchlistDirty || ratingDirty

    fun settingFavourite(value: Boolean, nowMillis: Long): UserMediaRecord =
        copy(favourite = value, favouriteUpdatedAt = nowMillis, favouriteDirty = true)

    fun settingWatchlist(value: Boolean, nowMillis: Long): UserMediaRecord =
        copy(watchlist = value, watchlistUpdatedAt = nowMillis, watchlistDirty = true)

    fun settingRating(value: Double?, nowMillis: Long): UserMediaRecord =
        copy(rating = sanitizeTmdbRating(value), ratingUpdatedAt = nowMillis, ratingDirty = true)

    /** Fills the display snapshot without disturbing state or timestamps. */
    fun withDisplay(title: String?, posterPath: String?): UserMediaRecord = copy(
        title = title ?: this.title,
        posterPath = posterPath ?: this.posterPath,
    )
}
