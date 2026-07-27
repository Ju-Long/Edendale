package com.babasama.edendale.domain

/**
 * Device ↔ cloud-replica reconciliation. Purely local: TMDB is not consulted
 * here (that is UserMediaSyncEngine's job). Any future Android replica
 * transport should use these deterministic merge rules.
 */

/**
 * Merges two user-media collections field-wise: for each of favourite,
 * watchlist, and rating independently, the side with the newer per-field
 * timestamp wins (value, timestamp, and dirty flag travel together). On a
 * timestamp tie the side with a pending push wins so an offline change is
 * never silently dropped. Stateless records are pruned from the result.
 */
fun mergeUserMedia(
    first: List<UserMediaRecord>,
    second: List<UserMediaRecord>,
): List<UserMediaRecord> {
    val byKey = LinkedHashMap<String, UserMediaRecord>()
    first.forEach { byKey[it.storageKey] = it }
    second.forEach { record ->
        byKey[record.storageKey] = byKey[record.storageKey]
            ?.mergedWith(record)
            ?: record
    }
    return byKey.values.filter(UserMediaRecord::hasState)
}

private fun UserMediaRecord.mergedWith(other: UserMediaRecord): UserMediaRecord {
    val favouriteWinner = fieldWinner(
        other,
        favouriteUpdatedAt, favouriteDirty,
        other.favouriteUpdatedAt, other.favouriteDirty,
    )
    val watchlistWinner = fieldWinner(
        other,
        watchlistUpdatedAt, watchlistDirty,
        other.watchlistUpdatedAt, other.watchlistDirty,
    )
    val ratingWinner = fieldWinner(
        other,
        ratingUpdatedAt, ratingDirty,
        other.ratingUpdatedAt, other.ratingDirty,
    )
    return UserMediaRecord(
        tmdbId = tmdbId,
        mediaType = mediaType,
        title = title ?: other.title,
        posterPath = posterPath ?: other.posterPath,
        favourite = favouriteWinner.favourite,
        favouriteUpdatedAt = favouriteWinner.favouriteUpdatedAt,
        favouriteDirty = favouriteWinner.favouriteDirty,
        watchlist = watchlistWinner.watchlist,
        watchlistUpdatedAt = watchlistWinner.watchlistUpdatedAt,
        watchlistDirty = watchlistWinner.watchlistDirty,
        rating = ratingWinner.rating,
        ratingUpdatedAt = ratingWinner.ratingUpdatedAt,
        ratingDirty = ratingWinner.ratingDirty,
    )
}

private fun UserMediaRecord.fieldWinner(
    other: UserMediaRecord,
    ownUpdatedAt: Long,
    ownDirty: Boolean,
    otherUpdatedAt: Long,
    otherDirty: Boolean,
): UserMediaRecord = when {
    ownUpdatedAt > otherUpdatedAt -> this
    ownUpdatedAt < otherUpdatedAt -> other
    otherDirty && !ownDirty -> other
    else -> this
}

/** Merges two watch-progress collections: per title, the newest write wins. */
fun mergeWatchProgress(
    first: List<WatchProgress>,
    second: List<WatchProgress>,
): List<WatchProgress> {
    val byKey = LinkedHashMap<String, WatchProgress>()
    first.forEach { byKey[it.storageKey] = it }
    second.forEach { record ->
        val existing = byKey[record.storageKey]
        if (existing == null ||
            record.lastWatchedEpochMillis > existing.lastWatchedEpochMillis
        ) {
            byKey[record.storageKey] = record
        }
    }
    return byKey.values.toList()
}
