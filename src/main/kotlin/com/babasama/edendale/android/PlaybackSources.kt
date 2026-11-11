package com.babasama.edendale.android

import com.babasama.edendale.android.player.PlayerLogic
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.SourceEndpoint
import com.babasama.edendale.connectors.SourceUrl
import java.net.URLDecoder
import java.util.IdentityHashMap

/**
 * The source a library copy was imported from, as the Play From menu
 * describes it (D.5). [isUnavailable] means its last scan failed (D.3).
 * [endpoint] is where it's reached from (27.1), the menu's first line.
 */
data class CopySource(
    val name: String,
    val kind: MediaSourceKind?,
    val isUnavailable: Boolean,
    val endpoint: SourceEndpoint = SourceEndpoint.ThisDevice,
)

/**
 * The same movie or show can be imported from several sources — a local
 * folder and an SMB share, say — and each file stays its own record, tied to
 * the others by its TMDB id (D.5; Apple's `PlaybackSources`). These rules
 * decide the order, the copy Play starts, and how a show's episodes merge
 * across its copies. Pure: the JVM suite covers them.
 */
internal object PlaybackSources {

    /** [endpointRank]: a local folder or file, on this device. */
    const val RANK_DEVICE = 0

    /** [endpointRank]: a host on the local network. */
    const val RANK_LAN = 1

    /** [endpointRank]: a host reached through an overlay network such as Tailscale. */
    const val RANK_OVERLAY = 2

    /** [endpointRank]: anything else — public hosts and storage accounts. */
    const val RANK_OTHER = 3

    /**
     * Copies in menu order: local folders first, then by how near the
     * endpoint is ([endpointRank]), then by endpoint, source name (natural,
     * case-insensitive), and path, so the order never depends on scan order.
     * With one share linked over the LAN and through Tailscale, both
     * reachable, the LAN link comes first rather than whichever URL sorts
     * first.
     *
     * Each copy's place is worked out once per comparator: a sort compares a
     * copy many times, and its endpoint comes from parsing the source's URL.
     */
    fun <T> comparator(source: (T) -> CopySource?, path: (T) -> String): Comparator<T> {
        val places = IdentityHashMap<T, Place>()
        fun place(copy: T): Place = places.getOrPut(copy) {
            val from = source(copy)
            val at = path(copy)
            Place(from?.kind != MediaSourceKind.LOCAL, rank(from, at), from?.endpoint?.sortKey.orEmpty(), from?.name.orEmpty(), at)
        }
        return Comparator { a, b ->
            val first = place(a)
            val second = place(b)
            compareValues(first.isRemote, second.isRemote).takeIf { it != 0 }
                ?: compareValues(first.rank, second.rank).takeIf { it != 0 }
                ?: PlayerLogic.naturalOrder.compare(first.endpoint, second.endpoint).takeIf { it != 0 }
                ?: PlayerLogic.naturalOrder.compare(first.name, second.name).takeIf { it != 0 }
                ?: PlayerLogic.naturalOrder.compare(first.path, second.path)
        }
    }

    /** What [comparator] orders a copy by, in order. */
    private class Place(val isRemote: Boolean, val rank: Int, val endpoint: String, val name: String, val path: String)

    /** A server endpoint knows its host (an S3 bucket on a LAN MinIO, say); anything else ranks by its URL. */
    private fun rank(source: CopySource?, path: String): Int =
        (source?.endpoint as? SourceEndpoint.Server)?.let { hostRank(it.host) } ?: endpointRank(path)

    /**
     * How near the copy at [uri] is: this device ([RANK_DEVICE]) for a
     * `content://` or file URI, else its host's [hostRank]. Storage accounts
     * and S3 rank [RANK_OTHER]: their URL's host is an account key, not a
     * place on the network.
     */
    fun endpointRank(uri: String): Int {
        val scheme = uri.substringBefore("://", "").lowercase()
        if (scheme.isEmpty() || scheme == "content" || scheme == "file") return RANK_DEVICE
        val kind = MediaSourceKind.forSourceUri(uri)
        if (kind != null && (kind.isCloudAccount || kind == MediaSourceKind.S3)) return RANK_OTHER
        return SourceUrl.credentialHost(uri)?.let(::hostRank) ?: RANK_OTHER
    }

    /**
     * [RANK_LAN] for a host on the local network: an mDNS `.local` name, a
     * single-label name, or a private, link-local, or loopback address (IPv6's
     * unique-local range included). [RANK_OVERLAY] for Tailscale: its
     * 100.64.0.0/10 addresses, MagicDNS `*.ts.net` names, and its
     * fd7a:115c:a1e0::/48 IPv6 range. [RANK_OTHER] for everything else.
     */
    fun hostRank(host: String): Int {
        val name = host.lowercase().trimEnd('.')
        if (name.startsWith("[") || ':' in name) {
            return ipv6Rank(name.removePrefix("[").substringBefore(']').substringBefore('%'))
        }
        ipv4(name)?.let { return ipv4Rank(it) }
        return when {
            name.isEmpty() -> RANK_OTHER
            name.endsWith(".ts.net") -> RANK_OVERLAY
            name.endsWith(".local") || '.' !in name -> RANK_LAN
            else -> RANK_OTHER
        }
    }

    private fun ipv4(text: String): IntArray? {
        val octets = text.split('.').map { part ->
            part.takeIf { it.length in 1..3 && it.all(Char::isDigit) }?.toInt()?.takeIf { it <= 255 }
        }
        return if (octets.size == 4 && octets.all { it != null }) octets.map { it!! }.toIntArray() else null
    }

    private fun ipv4Rank(octets: IntArray): Int {
        val (a, b) = octets[0] to octets[1]
        return when {
            a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) -> RANK_LAN
            a == 100 && b in 64..127 -> RANK_OVERLAY
            else -> RANK_OTHER
        }
    }

    private fun ipv6Rank(address: String): Int {
        val groups = ipv6Groups(address) ?: return RANK_OTHER
        val first = groups[0]
        return when {
            first == 0xfd7a && groups[1] == 0x115c && groups[2] == 0xa1e0 -> RANK_OVERLAY
            (first and 0xffc0) == 0xfe80 || (first and 0xfe00) == 0xfc00 -> RANK_LAN
            groups.take(7).all { it == 0 } && groups[7] == 1 -> RANK_LAN
            // An IPv4-mapped address (::ffff:a.b.c.d) is that IPv4 address.
            groups.take(5).all { it == 0 } && groups[5] == 0xffff ->
                ipv4Rank(intArrayOf(groups[6] shr 8, groups[6] and 0xff, groups[7] shr 8, groups[7] and 0xff))
            else -> RANK_OTHER
        }
    }

    /** The eight 16-bit groups of an IPv6 address, `::` expanded; null when it isn't one. */
    private fun ipv6Groups(address: String): IntArray? {
        fun parse(part: String): List<Int>? {
            if (part.isEmpty()) return emptyList()
            return part.split(':').flatMap { group ->
                if ('.' in group) {
                    val octets = ipv4(group) ?: return null
                    listOf((octets[0] shl 8) or octets[1], (octets[2] shl 8) or octets[3])
                } else {
                    val hex = group.takeIf { it.length in 1..4 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
                    listOf(hex?.toInt(16) ?: return null)
                }
            }
        }
        val halves = address.split("::")
        val groups = when (halves.size) {
            1 -> parse(address)?.takeIf { it.size == 8 }
            2 -> {
                val head = parse(halves[0]) ?: return null
                val tail = parse(halves[1]) ?: return null
                val missing = 8 - head.size - tail.size
                if (missing < 1) null else head + List(missing) { 0 } + tail
            }
            else -> null
        }
        return groups?.toIntArray()
    }

    /** [primary] (the page's own copy) first, then [others] in [comparator] order. */
    fun <T> order(primary: T?, others: List<T>, source: (T) -> CopySource?, path: (T) -> String): List<T> =
        listOfNotNull(primary) + others.filter { it != primary }.sortedWith(comparator(source, path))

    /**
     * The copy Play starts: the first whose source isn't known to be offline
     * or signed out, or the first of all when every one is.
     */
    fun <T> preferred(copies: List<T>, isUnavailable: (T) -> Boolean): T? =
        copies.firstOrNull { !isUnavailable(it) } ?: copies.firstOrNull()

    // MARK: - The source a title last played from (27.1)

    /** The source key of files added on their own, which count as one source. */
    const val LOOSE_FILES = "loose"

    /**
     * A copy's source, as the playback memory and the player's episode list
     * compare them: the linked source's `treeUri` (its identity, never its
     * name, which two links of one share have in common), or [LOOSE_FILES].
     */
    fun sourceKey(folderUri: String?): String = folderUri ?: LOOSE_FILES

    /**
     * The playback memory's key for a show: its TMDB id once enrichment
     * matched it, else the parsed show key. An episode played before the
     * match is remembered under both ([showMemoryKeys]), so the choice
     * survives the match.
     */
    fun showMemoryKey(showTmdbId: Int?, showKey: String?): String? = when {
        showTmdbId != null -> "show:$showTmdbId"
        showKey != null -> "showKey:$showKey"
        else -> null
    }

    /** Every key a played episode of a show is remembered under. */
    fun showMemoryKeys(showTmdbId: Int?, showKey: String?): List<String> =
        listOfNotNull(showTmdbId?.let { "show:$it" }, showKey?.let { "showKey:$it" })

    /** The playback memory's key for a movie; none without a TMDB id. */
    fun movieMemoryKey(tmdbId: Int?): String? = tmdbId?.let { "movie:$it" }

    /**
     * The source a show last played from, in [sources] (the playback memory):
     * by its TMDB id first, then by any of its [showKeys] (every copy of the
     * show, merged by id, has its own).
     */
    fun rememberedShowSource(sources: Map<String, String>, showTmdbId: Int?, showKeys: Iterable<String>): String? =
        showTmdbId?.let { sources["show:$it"] } ?: showKeys.firstNotNullOfOrNull { sources["showKey:$it"] }

    /** [copies] with those on the [remembered] source first, the rest in their order. */
    fun <T> rememberedFirst(copies: List<T>, sourceKey: (T) -> String, remembered: String?): List<T> =
        if (remembered == null) copies else copies.sortedBy { sourceKey(it) != remembered }

    /**
     * The copy a tap on an episode, or a resume, starts outside the player:
     * one on the [remembered] source first, then the first reachable copy in
     * [copies]' order. A remembered source whose last scan failed still falls
     * back, as nothing proves it answers.
     */
    fun <T> startingCopy(copies: List<T>, sourceKey: (T) -> String, remembered: String?, isUnavailable: (T) -> Boolean): T? =
        preferred(rememberedFirst(copies, sourceKey, remembered), isUnavailable)

    /**
     * Whether a copy that Play, Resume, or a Continue Watching or Watch Next
     * card [started] on its own may become the title's remembered source once
     * it plays. Not when the launch passed over a copy on the [remembered]
     * source because that source's last scan failed: a stale "offline" flag
     * would otherwise move the title off the viewer's choice for good. A
     * remembered source with no copy of this title wasn't passed over, so the
     * copy that plays counts. A Play From pick is the viewer's choice and
     * always counts, without asking this.
     */
    fun <T> remembersSource(copies: List<T>, started: T, sourceKey: (T) -> String, remembered: String?): Boolean =
        remembered == null || sourceKey(started) == remembered || copies.none { sourceKey(it) == remembered }

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

    /** One row of the player's episode list: the copy it plays and every copy of the episode, in [comparator] order. */
    data class PlayerEpisode<T>(val chosen: T, val copies: List<T>)

    /**
     * The player's episode list, which Up Next and auto-advance walk (C.2
     * with D.5): [playing] for its own episode; for the others, a copy on the
     * anchor source — the playing copy's, else the [remembered] one — then the
     * first reachable copy in [comparator] order. The playing copy's source
     * counts as reachable whatever its last scan said: the file playing proves
     * it answers, and a stale "offline" flag (common with one share linked
     * over the LAN and through Tailscale) used to move the next episode to the
     * other link. Listing every copy made advancing take whichever the
     * database returned first, even one on an offline source.
     */
    fun <T> playerEpisodeRows(
        episodes: List<T>,
        playing: T?,
        season: (T) -> Int,
        number: (T) -> Int,
        sourceKey: (T) -> String,
        source: (T) -> CopySource,
        path: (T) -> String,
        remembered: String? = null,
    ): List<PlayerEpisode<T>> {
        val anchor = playing?.let(sourceKey) ?: remembered
        val menuOrder = comparator(source, path)
        val anchorFirst = compareBy<T> { anchor == null || sourceKey(it) != anchor }.then(menuOrder)
        return episodeSlots(episodes, season, number, anchorFirst).map { slot ->
            val chosen = if (playing != null && playing in slot.copies) {
                playing
            } else {
                preferred(slot.copies) { source(it).isUnavailable && !(playing != null && sourceKey(it) == anchor) }
                    ?: slot.primary
            }
            PlayerEpisode(chosen, slot.copies.sortedWith(menuOrder))
        }
    }

    /**
     * [source] as the player describes copies while [playing] plays: its
     * source answers, whatever its last scan said ([playerEpisodeRows]' rule),
     * so none of that source's copies reads "Unavailable" in an episode row's
     * Play From. Nothing playing changes nothing.
     */
    fun <T> whilePlaying(playing: T?, sourceKey: (T) -> String, source: (T) -> CopySource): (T) -> CopySource {
        val answering = playing?.let(sourceKey) ?: return source
        return { copy -> source(copy).let { if (it.isUnavailable && sourceKey(copy) == answering) it.copy(isUnavailable = false) else it } }
    }

    /** One copy per episode: the [chosen][PlayerEpisode.chosen] copies of [playerEpisodeRows]. */
    fun <T> playerEpisodes(
        episodes: List<T>,
        playing: T?,
        season: (T) -> Int,
        number: (T) -> Int,
        sourceKey: (T) -> String,
        source: (T) -> CopySource,
        path: (T) -> String,
        remembered: String? = null,
    ): List<T> = playerEpisodeRows(episodes, playing, season, number, sourceKey, source, path, remembered).map { it.chosen }

    // MARK: - Menu text

    /** The file name of a stored path or URI, decoded. */
    fun fileName(path: String): String {
        val decoded = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrDefault(path)
        return decoded.trimEnd('/').substringAfterLast('/').substringAfterLast(':')
    }

    /**
     * The second line of a menu row: the source's kind and the file name
     * ("SMB · Heat 2160p.mkv"), with the linked [folder] between them when
     * given; just the file name without a [kindLabel].
     */
    fun detail(kindLabel: String?, path: String, folder: String? = null): String =
        listOfNotNull(kindLabel, folder?.takeIf { it.isNotBlank() }, fileName(path)).joinToString(" · ")

    /** One row of a Play From menu: its two lines. */
    data class MenuRow(val title: String, val detail: String)

    /**
     * A Play From menu's lines (D.5, 27.1): first where the copy is reached,
     * [label] of its endpoint ("longs-mac-studio.local", "Google Drive -
     * me@example.com"); then [detail], whose kind is left out for an account,
     * since the first line already names the provider. Rows that would still
     * read the same — one host or account, the same file name — add their
     * linked folder's name, so they can be told apart.
     */
    fun <T> menuRows(
        copies: List<T>,
        source: (T) -> CopySource,
        path: (T) -> String,
        label: (SourceEndpoint) -> String,
        kindLabel: (MediaSourceKind?) -> String,
    ): List<MenuRow> {
        val sources = copies.map(source)
        val titles = sources.map { label(it.endpoint) }
        val readings = titles.zip(copies.map { fileName(path(it)) })
        val repeated = readings.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return copies.mapIndexed { index, copy ->
            val from = sources[index]
            MenuRow(
                title = titles[index],
                detail = detail(
                    kindLabel = if (from.endpoint is SourceEndpoint.Account) null else kindLabel(from.kind),
                    path = path(copy),
                    folder = from.name.takeIf { readings[index] in repeated },
                ),
            )
        }
    }
}
