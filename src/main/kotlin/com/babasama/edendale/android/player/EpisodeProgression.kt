package com.babasama.edendale.android.player

/**
 * Plain data representation of an episode for progression calculation.
 * Completely free of Room / Android / Media3 imports.
 */
data class EpisodeCandidate(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String? = null,
)

/**
 * Plain data representation of a TV show and its episodes.
 */
data class ShowCandidate(
    val tmdbId: Int?,
    val name: String = "",
    val episodes: List<EpisodeCandidate> = emptyList(),
)

/**
 * Progress entry for computing Continue Watching next-up candidates.
 */
data class CompletedProgressEntry(
    val tmdbId: Int,
    val isEpisode: Boolean,
    val isCompleted: Boolean,
    val showTmdbId: Int? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val lastWatchedEpochMillis: Long = 0L,
)

/**
 * Furthest completed season/episode for a show.
 */
data class HighestCompleted(
    val season: Int,
    val episode: Int,
    val lastWatchedEpochMillis: Long,
)

/**
 * Candidate for Continue Watching next-up card.
 */
data class NextUpCandidate(
    val show: ShowCandidate,
    val episode: EpisodeCandidate,
    val lastWatchedEpochMillis: Long,
)

/**
 * Pure rules for episode progression, auto-advance, the Up Next card, and
 * Continue Watching next-up suggestions (DIFF §3.4).
 */
object EpisodeProgression {

    /** How close to the end (in milliseconds) the Up Next card appears (30 seconds). */
    const val UPCOMING_PREVIEW_MILLIS: Long = 30_000L

    /**
     * Returns true if (season, episode) is strictly after (otherSeason, otherEpisode).
     */
    fun isAfter(season: Int, episode: Int, otherSeason: Int, otherEpisode: Int): Boolean =
        season > otherSeason || (season == otherSeason && episode > otherEpisode)

    /**
     * Finds the stored episode with the smallest (season, episode) strictly after [current],
     * crossing seasons. Duplicate files of the current episode are skipped; specials (season 0)
     * advance among themselves and then into season 1, while main seasons never fall back to season 0.
     * Returns null when [current] is not in the show or is the last episode.
     */
    fun nextEpisode(current: EpisodeCandidate, episodes: List<EpisodeCandidate>): EpisodeCandidate? {
        if (episodes.none { it.id == current.id }) return null

        var best: EpisodeCandidate? = null
        for (candidate in episodes) {
            if (!isAfter(candidate.season, candidate.episode, current.season, current.episode)) continue
            if (best == null || isAfter(best.season, best.episode, candidate.season, candidate.episode)) {
                best = candidate
            }
        }
        return best
    }

    /**
     * Returns the episode to show in the Up Next card, or null when hidden.
     * Appears only when the remaining time is > 0 and <= 30 s, Loop is off,
     * the duration is known (> 0), and a successor episode exists.
     */
    fun upcomingEpisode(
        timeMillis: Long,
        durationMillis: Long?,
        loopEnabled: Boolean,
        current: EpisodeCandidate?,
        episodes: List<EpisodeCandidate>?,
    ): EpisodeCandidate? {
        if (durationMillis == null || durationMillis <= 0L) return null
        if (loopEnabled || current == null || episodes == null) return null
        val remainingMillis = durationMillis - timeMillis
        if (remainingMillis > UPCOMING_PREVIEW_MILLIS || remainingMillis <= 0L) return null
        return nextEpisode(current, episodes)
    }

    /**
     * Determines the furthest completed (season, episode) per show TMDB ID.
     * Non-episodes, incomplete progress, and missing IDs are ignored.
     */
    fun highestCompletedPerShow(entries: List<CompletedProgressEntry>): Map<Int, HighestCompleted> {
        val result = mutableMapOf<Int, HighestCompleted>()
        for (entry in entries) {
            if (!entry.isEpisode || !entry.isCompleted) continue
            val showId = entry.showTmdbId ?: continue
            val season = entry.seasonNumber ?: continue
            val episodeNumber = entry.episodeNumber ?: continue

            val existing = result[showId]
            if (existing == null || isAfter(season, episodeNumber, existing.season, existing.episode)) {
                result[showId] = HighestCompleted(season, episodeNumber, entry.lastWatchedEpochMillis)
            }
        }
        return result
    }

    /**
     * Computes the next-up episode for every show with a completed episode and nothing in progress.
     * Result is sorted by most recently watched epoch descending.
     */
    fun nextUpEpisodes(
        allProgress: List<CompletedProgressEntry>,
        inProgressShowTmdbIds: Set<Int>,
        shows: List<ShowCandidate>,
    ): List<NextUpCandidate> {
        val highest = highestCompletedPerShow(allProgress)
        val candidates = mutableMapOf<Int, Pair<ShowCandidate, EpisodeCandidate>>()

        for (show in shows) {
            val showId = show.tmdbId ?: continue
            if (showId in inProgressShowTmdbIds) continue
            val completed = highest[showId] ?: continue

            for (episode in show.episodes) {
                if (!isAfter(episode.season, episode.episode, completed.season, completed.episode)) continue
                val existing = candidates[showId]
                if (existing != null && !isAfter(existing.second.season, existing.second.episode, episode.season, episode.episode)) {
                    continue
                }
                candidates[showId] = Pair(show, episode)
            }
        }

        return candidates.values
            .map { (show, episode) ->
                val lastWatched = highest[show.tmdbId]?.lastWatchedEpochMillis ?: 0L
                NextUpCandidate(show, episode, lastWatched)
            }
            .sortedByDescending { it.lastWatchedEpochMillis }
    }
}
