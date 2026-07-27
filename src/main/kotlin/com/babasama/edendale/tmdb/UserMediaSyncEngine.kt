package com.babasama.edendale.tmdb

import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.UserMediaRecord

/** Outcome of one sync round; [records] fully replaces the local collection. */
data class UserMediaSyncResult(
    val records: List<UserMediaRecord>,
    val pushed: Int,
    val pulled: Int,
)

/**
 * One deterministic TMDB account sync round for favourites, watchlist, and
 * ratings (watch time has no TMDB API and never goes through here):
 *
 *  1. Pull the account's favourite/watchlist/rated lists (movies + TV).
 *  2. Per record and per field: a locally *dirty* field wins — its value is
 *     pushed to TMDB when it differs from the remote state, then the flag is
 *     cleared. A clean field adopts the remote value.
 *  3. Titles that exist only remotely materialize as local records, so a
 *     fresh install inherits the account's existing lists.
 *
 * Stateless records are pruned from the result.
 */
class UserMediaSyncEngine(private val api: TmdbAccountApi) {

    suspend fun sync(
        session: TmdbSession,
        local: List<UserMediaRecord>,
        nowMillis: Long,
    ): UserMediaSyncResult {
        val remoteFavourites = api.favourites(session).associateBy(MediaItem::key)
        val remoteWatchlist = api.watchlist(session).associateBy(MediaItem::key)
        val remoteRatings = api.rated(session).associateBy { it.item.key }

        val localByKey = local.associateBy(UserMediaRecord::storageKey)
        val remoteOnly = (remoteFavourites.values + remoteWatchlist.values +
            remoteRatings.values.map(TmdbRatedItem::item))
            .filter { it.key !in localByKey }
            .associateBy(MediaItem::key)

        var pushed = 0
        var pulled = 0

        val records = buildList {
            localByKey.values.forEach { record -> add(record) }
            remoteOnly.values.forEach { item ->
                add(
                    UserMediaRecord(
                        tmdbId = item.id,
                        mediaType = item.mediaType,
                        title = item.title,
                        posterPath = item.posterPath,
                    ),
                )
            }
        }.map { start ->
            var record = start
            val key = record.storageKey

            val remoteFavourite = key in remoteFavourites
            if (record.favouriteDirty) {
                if (record.favourite != remoteFavourite) {
                    api.setFavourite(session, record.ref, record.favourite)
                    pushed += 1
                }
                record = record.copy(favouriteDirty = false)
            } else if (record.favourite != remoteFavourite) {
                record = record.copy(
                    favourite = remoteFavourite,
                    favouriteUpdatedAt = nowMillis,
                )
                pulled += 1
            }

            val remoteWatchlisted = key in remoteWatchlist
            if (record.watchlistDirty) {
                if (record.watchlist != remoteWatchlisted) {
                    api.setWatchlist(session, record.ref, record.watchlist)
                    pushed += 1
                }
                record = record.copy(watchlistDirty = false)
            } else if (record.watchlist != remoteWatchlisted) {
                record = record.copy(
                    watchlist = remoteWatchlisted,
                    watchlistUpdatedAt = nowMillis,
                )
                pulled += 1
            }

            val remoteRating = remoteRatings[key]?.rating
            if (record.ratingDirty) {
                if (record.rating != remoteRating) {
                    api.setRating(session, record.ref, record.rating)
                    pushed += 1
                }
                record = record.copy(ratingDirty = false)
            } else if (record.rating != remoteRating) {
                record = record.copy(
                    rating = remoteRating,
                    ratingUpdatedAt = nowMillis,
                )
                pulled += 1
            }

            val display = remoteFavourites[key]
                ?: remoteWatchlist[key]
                ?: remoteRatings[key]?.item
            display?.let { record = record.withDisplay(it.title, it.posterPath) }
            record
        }.filter(UserMediaRecord::hasState)

        return UserMediaSyncResult(records = records, pushed = pushed, pulled = pulled)
    }
}

private val MediaItem.key: String get() = "${mediaType.pathSegment}:$id"
