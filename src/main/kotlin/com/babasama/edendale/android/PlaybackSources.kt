package com.babasama.edendale.android

import com.babasama.edendale.android.player.PlayerLogic
import com.babasama.edendale.connectors.MediaSourceKind
import java.net.URLDecoder

/**
 * The source a library copy was imported from, as the Play From menu
 * describes it (D.5). [isUnavailable] means its last scan failed (D.3).
 */
data class CopySource(
    val name: String,
    val kind: MediaSourceKind?,
    val isUnavailable: Boolean,
)

/**
 * The same movie or show can be imported from several sources — a local
 * folder and an SMB share, say — and each file stays its own record, tied to
 * the others by its TMDB id (D.5; Apple's `PlaybackSources`). These rules
 * decide the order, the copy Play starts, and how a show's episodes merge
 * across its copies. Pure: the JVM suite covers them.
 */
internal object PlaybackSources {

    /**
     * Copies in menu order: local folders first, then the rest by source name
     * (natural, case-insensitive), then by path so the order never depends on
     * scan order.
     */
    fun <T> comparator(source: (T) -> CopySource?, path: (T) -> String): Comparator<T> =
        compareBy<T> { source(it)?.kind != MediaSourceKind.LOCAL }
            .thenComparator { a, b -> PlayerLogic.naturalOrder.compare(source(a)?.name.orEmpty(), source(b)?.name.orEmpty()) }
            .thenComparator { a, b -> PlayerLogic.naturalOrder.compare(path(a), path(b)) }

    /** [primary] (the page's own copy) first, then [others] in [comparator] order. */
    fun <T> order(primary: T?, others: List<T>, source: (T) -> CopySource?, path: (T) -> String): List<T> =
        listOfNotNull(primary) + others.filter { it != primary }.sortedWith(comparator(source, path))

    /**
     * The copy Play starts: the first whose source isn't known to be offline
     * or signed out, or the first of all when every one is.
     */
    fun <T> preferred(copies: List<T>, isUnavailable: (T) -> Boolean): T? =
        copies.firstOrNull { !isUnavailable(it) } ?: copies.firstOrNull()

    /** One episode of a show across every imported copy of it. */
    data class EpisodeSlot<T>(val season: Int, val number: Int, val copies: List<T>) {
        val id: String get() = "$season-$number"
        val primary: T get() = copies.first()
    }

    /**
     * Every episode of every copy, one slot per season and episode number, in
     * airing order; each slot's copies follow [rank] (the page's own show
     * first, then the [comparator] order).
     */
    fun <T> episodeSlots(
        episodes: List<T>,
        season: (T) -> Int,
        number: (T) -> Int,
        rank: Comparator<T>,
    ): List<EpisodeSlot<T>> =
        episodes
            .groupBy { season(it) to number(it) }
            .map { (key, copies) -> EpisodeSlot(key.first, key.second, copies.sortedWith(rank)) }
            .sortedWith(compareBy({ it.season }, { it.number }))

    /** The file name of a stored path or URI, decoded. */
    fun fileName(path: String): String {
        val decoded = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
        return decoded.trimEnd('/').substringAfterLast('/').substringAfterLast(':')
    }

    /** The second line of a menu row: the source's kind and the file name ("SMB · Heat 2160p.mkv"). */
    fun detail(kindLabel: String, path: String): String = "$kindLabel · ${fileName(path)}"
}
