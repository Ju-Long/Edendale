package com.babasama.edendale.android

/**
 * The TV home screen's Watch Next row (I.3): what Edendale publishes there and
 * how that reconciles with the rows the TV provider already holds. Pure, so
 * the JVM suite covers it; [WatchNextPublisher] carries it to the provider.
 *
 * The row mirrors Continue Watching: a part-watched title is a "continue"
 * program with its position, and a show's next episode is a "next" program.
 * Each movie and each show gets one row (Google's guidance for Watch Next),
 * keyed by its TMDB id; for a show, the most recently watched episode wins.
 */
internal object WatchNext {
    /** Every row Edendale writes carries this prefix in its internal provider id. */
    const val KEY_PREFIX = "edendale:"

    /** The in-app shelf's cap. */
    const val LIMIT = CONTINUE_WATCHING_LIMIT

    /** Continue Watching, newest first, as home-screen programs; the first entry per key wins. */
    fun programs(entries: List<ContinueEntry>): List<WatchNextProgram> =
        entries.map(::program).distinctBy { it.key }.take(LIMIT)

    fun program(entry: ContinueEntry): WatchNextProgram = WatchNextProgram(
        key = key(entry),
        isNext = entry.isNextUp,
        isEpisode = entry.isEpisode,
        title = entry.title,
        episodeTitle = entry.episodeTitle,
        season = entry.season,
        episode = entry.episode,
        artworkUrl = entry.backdropUrl ?: entry.posterUrl,
        isWideArtwork = entry.backdropUrl != null,
        positionMillis = if (entry.isNextUp) null else entry.positionMillis,
        durationMillis = entry.durationMillis,
        lastEngagementMillis = entry.lastWatchedEpochMillis,
        play = WatchNextPlay(
            uri = entry.uri,
            title = entry.title,
            tmdbId = entry.tmdbId,
            isEpisode = entry.isEpisode,
            showTmdbId = entry.showTmdbId,
            season = entry.season,
            episode = entry.episode,
        ),
    )

    /** One row per movie or show by TMDB id; an episode without its show's id, or a file without an id, stands alone. */
    fun key(entry: ContinueEntry): String {
        val id = entry.tmdbId
        val show = entry.showTmdbId
        return when {
            entry.isEpisode && show != null -> "${KEY_PREFIX}show:$show"
            id == null -> "${KEY_PREFIX}file:${entry.uri}"
            entry.isEpisode -> "${KEY_PREFIX}episode:$id"
            else -> "${KEY_PREFIX}movie:$id"
        }
    }

    /**
     * What to insert, update, and delete so the provider holds [desired].
     *
     * - A row without Edendale's prefix isn't ours and is left alone.
     * - A row for a title that's no longer wanted is deleted, and so is a
     *   second row for one title (the most recently engaged one stays).
     * - A row the viewer removed from the home screen (no longer browsable)
     *   stays as it is until they watch that title again; then it's replaced,
     *   since only the system can make a row browsable again.
     * - Any other row is rewritten only when its program changed.
     */
    fun changes(
        desired: List<WatchNextProgram>,
        published: List<PublishedWatchNextProgram>,
    ): WatchNextChanges {
        val wanted = desired.associateBy { it.key }
        val kept = HashMap<String, PublishedWatchNextProgram>()
        val deletes = mutableListOf<Long>()
        for (row in published.sortedByDescending { it.lastEngagementMillis }) {
            val key = row.key?.takeIf { it.startsWith(KEY_PREFIX) } ?: continue
            if (key !in wanted || key in kept) deletes += row.rowId else kept[key] = row
        }
        val inserts = mutableListOf<WatchNextProgram>()
        val updates = mutableListOf<Pair<Long, WatchNextProgram>>()
        for (program in wanted.values) {
            val row = kept[program.key]
            when {
                row == null -> inserts += program
                !row.isBrowsable -> if (program.lastEngagementMillis > row.lastEngagementMillis) {
                    deletes += row.rowId
                    inserts += program
                }
                row.signature != program.signature -> updates += row.rowId to program
            }
        }
        return WatchNextChanges(inserts = inserts, updates = updates, deletes = deletes)
    }
}

/** What a home-screen row opens: the request the shelf's card sends to the player. */
internal data class WatchNextPlay(
    val uri: String,
    val title: String,
    val tmdbId: Int?,
    val isEpisode: Boolean,
    val showTmdbId: Int?,
    val season: Int?,
    val episode: Int?,
)

/** One home-screen program as Edendale wants it. */
internal data class WatchNextProgram(
    val key: String,
    /** A show's next episode ("next"), rather than a title in progress ("continue"). */
    val isNext: Boolean,
    val isEpisode: Boolean,
    /** The movie's title, or the show's name for an episode. */
    val title: String,
    val episodeTitle: String?,
    val season: Int?,
    val episode: Int?,
    val artworkUrl: String?,
    /** 16:9 artwork; otherwise the 2:3 poster. */
    val isWideArtwork: Boolean,
    val positionMillis: Long?,
    val durationMillis: Long?,
    val lastEngagementMillis: Long,
    val play: WatchNextPlay,
) {
    /**
     * A 64-bit FNV-1a fingerprint of everything written to the row, stored
     * beside it so an unchanged program isn't rewritten. It hashes the
     * program's text, so it's the same in every process.
     */
    val signature: Long
        get() {
            var hash = FNV_OFFSET_BASIS
            for (character in toString()) {
                hash = (hash xor character.code.toLong()) * FNV_PRIME
            }
            return hash
        }

    private companion object {
        const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL
        const val FNV_PRIME = 0x100000001b3L
    }
}

/** One of the rows already in the provider, as much as reconciling needs. */
internal data class PublishedWatchNextProgram(
    val rowId: Long,
    val key: String?,
    val isBrowsable: Boolean,
    val lastEngagementMillis: Long,
    val signature: Long?,
)

internal data class WatchNextChanges(
    val inserts: List<WatchNextProgram> = emptyList(),
    val updates: List<Pair<Long, WatchNextProgram>> = emptyList(),
    val deletes: List<Long> = emptyList(),
)
