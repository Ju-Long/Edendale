package com.babasama.edendale.android

import com.babasama.edendale.android.data.LibraryEpisodeEntity
import com.babasama.edendale.android.data.LibraryFolderEntity
import com.babasama.edendale.android.data.LibraryMovieEntity
import com.babasama.edendale.android.data.LibraryShowEntity
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.SourceUrl

/**
 * One item per title across the endpoints a library reaches it through
 * (27.1). One share linked twice — over the LAN as `longs-mac-studio.local`
 * and through Tailscale as `100.x` — gives every file two rows, and a title
 * enrichment hasn't matched yet has no TMDB id to tie them. These rules say
 * which rows are the same title, so Downloaded and Search show one poster
 * and the detail page's Play From lists every copy. Pure: the JVM suite
 * covers them.
 */

// MARK: - The same file through another link

/**
 * What a file is apart from the link that reaches it: the source kind and
 * the decoded path with the host and port dropped, lowercased, for SMB, NFS,
 * SFTP and WebDAV (`dav` and `davs` are one kind); bucket and key for S3,
 * whose URL host is an account key that differs per endpoint; the provider's
 * file id(s) for Google Drive, OneDrive and Dropbox; the provider authority
 * and decoded document id for a `content://` document, so overlapping folder
 * grants agree. Null for anything else.
 *
 * The whole path counts, share name included, so a link to a subfolder of
 * the same share still matches, and two shares' files never do.
 */
internal fun fileIdentity(uri: String): String? {
    val scheme = uri.substringBefore("://", "").lowercase()
    if (scheme.isEmpty()) return null
    if (scheme == "content") return contentIdentity(uri)
    return when (val kind = MediaSourceKind.fromScheme(scheme)) {
        MediaSourceKind.SMB, MediaSourceKind.NFS, MediaSourceKind.SFTP, MediaSourceKind.WEBDAV -> {
            val path = SourceUrl.pathSegments(uri).takeIf { it.isNotEmpty() } ?: return null
            "${kind.raw}:/" + path.joinToString("/").lowercase()
        }
        MediaSourceKind.S3 -> SourceUrl.parseS3(uri)?.takeUnless { it.isPrefix }?.let { "s3:${it.bucket}/${it.key}" }
        MediaSourceKind.GOOGLE_DRIVE, MediaSourceKind.ONE_DRIVE, MediaSourceKind.DROPBOX ->
            SourceUrl.parseAccountItem(uri)?.let { item -> "${kind.raw}:" + item.ids.joinToString("/") }
        else -> null
    }
}

/** `content://<authority>/…/document/<id>`: the authority and the decoded document id. */
private fun contentIdentity(uri: String): String? {
    val rest = uri.substringAfter("://")
    val authority = rest.substringBefore('/').takeIf { it.isNotEmpty() } ?: return null
    val document = rest.substringAfterLast("/document/", "").takeIf { it.isNotEmpty() } ?: return null
    val id = runCatching { java.net.URLDecoder.decode(document.replace("+", "%2B"), "UTF-8") }.getOrDefault(document)
    return "content:$authority/$id"
}

// MARK: - Titles

/**
 * One title and every copy of it, [copies] in Play From order. [key] is
 * stable across scans: `tmdb:<id>` once any copy is matched, else
 * `file:<identity>`, else `uri:<uri>`.
 */
internal data class LibraryTitle<T>(val key: String, val copies: List<T>, val tmdbId: Int?) {
    val primary: T get() = copies.first()
}

/**
 * [rows] as titles: rows that share a TMDB id, or the same file
 * ([fileIdentity]) through different links, are one title, and the tie is
 * transitive, so an alias whose match failed or is pending joins its matched
 * twin and the title takes the twin's id. Rows matched to different TMDB
 * titles never join, and files at different paths stay apart, even when
 * they parse to the same name, until enrichment matches them to one TMDB
 * title. Titles keep the order of their first row (the DAO's); each title's
 * copies follow [order] when given.
 */
internal fun <T> libraryTitles(
    rows: List<T>,
    tmdbId: (T) -> Int?,
    uri: (T) -> String,
    order: Comparator<T>? = null,
): List<LibraryTitle<T>> {
    val parent = IntArray(rows.size) { it }
    fun root(index: Int): Int {
        var at = index
        while (parent[at] != at) {
            parent[at] = parent[parent[at]]
            at = parent[at]
        }
        return at
    }
    val identities = rows.map { fileIdentity(uri(it)) }
    // Each title's TMDB id, kept at its root: rows matched to two different
    // titles never unite, even through the same path, so a file renamed in
    // place or one path on two unrelated hosts can't fold one title into
    // another.
    val idAtRoot = arrayOfNulls<Int>(rows.size)
    rows.forEachIndexed { index, row -> idAtRoot[index] = tmdbId(row) }
    val firstWithKey = HashMap<String, Int>()
    rows.forEachIndexed { index, row ->
        listOfNotNull(tmdbId(row)?.let { "tmdb:$it" }, identities[index]?.let { "file:$it" }).forEach { key ->
            val seen = firstWithKey.putIfAbsent(key, index)
            if (seen != null) {
                val a = root(seen)
                val b = root(index)
                val ids = listOfNotNull(idAtRoot[a], idAtRoot[b]).distinct()
                if (a != b && ids.size <= 1) {
                    // The earlier row stays the root, so a title keeps its first row's place.
                    val (keep, join) = if (a < b) a to b else b to a
                    parent[join] = keep
                    idAtRoot[keep] = ids.firstOrNull()
                }
            }
        }
    }
    val groups = LinkedHashMap<Int, MutableList<Int>>()
    rows.indices.forEach { groups.getOrPut(root(it)) { mutableListOf() }.add(it) }
    return groups.values.map { members ->
        val id = members.firstNotNullOfOrNull { tmdbId(rows[it]) }
        val key = when {
            id != null -> "tmdb:$id"
            else -> members.firstNotNullOfOrNull { identities[it] }?.let { "file:$it" } ?: "uri:${uri(rows[members.first()])}"
        }
        val copies = members.map { rows[it] }
        LibraryTitle(key, if (order == null) copies else copies.sortedWith(order), id)
    }
}

/** Movies as titles, each title's copies in Play From order (D.5, 27.1). */
internal fun movieTitles(
    movies: List<LibraryMovieEntity>,
    folders: Map<String, LibraryFolderEntity>,
): List<LibraryTitle<LibraryMovieEntity>> =
    libraryTitles(movies, { it.tmdbId }, { it.uri }, PlaybackSources.comparator({ folders.copySource(it.folderUri) }, { it.uri }))

/**
 * The copy a title's poster shows and opens: the first matched one, whose
 * artwork and name come from TMDB, else the first in Play From order.
 */
internal val LibraryTitle<LibraryMovieEntity>.display: LibraryMovieEntity
    get() = copies.firstOrNull { it.tmdbId != null } ?: primary

/**
 * The poster's key for TV's focus return ([rememberPosterReturn]): one of
 * its files, which stays the same when enrichment matches the title while
 * its page is open, where [LibraryTitle.key] moves from `file:` to `tmdb:`.
 */
internal val LibraryTitle<LibraryMovieEntity>.posterKey: String
    get() = "movie:" + copies.minOf { it.uri }

/**
 * A movie's copies — its title's — with [primary] (the page's own) first and
 * the others in Play From order. Copies are the rows [libraryTitles] puts in
 * one title: the same TMDB id, or the same file through another link.
 */
internal fun movieCopies(
    primary: LibraryMovieEntity,
    movies: List<LibraryMovieEntity>,
    folders: Map<String, LibraryFolderEntity>,
): List<LibraryMovieEntity> {
    val title = movieTitles(movies, folders).firstOrNull { title -> title.copies.any { it.uri == primary.uri } }
    val others = title?.copies.orEmpty().filter { it.uri != primary.uri }
    return listOf(primary) + others
}

/** The copy Play starts, and whether playing it may become the title's remembered source (27.1). */
internal data class PlayStart<T>(val copy: T, val remembersSource: Boolean)

/**
 * What Play on a movie's page starts among [copies]: the copy on the source
 * the movie last played from ([remembered]), else the first reachable one in
 * Play From order — Continue Watching's rule, so the card and the page start
 * the same copy. A start that passed over the remembered source (its last
 * scan failed) doesn't replace it.
 */
internal fun moviePlayStart(
    copies: List<LibraryMovieEntity>,
    folders: Map<String, LibraryFolderEntity>,
    remembered: String?,
): PlayStart<LibraryMovieEntity>? {
    val copy = preferredCopy(null, copies, { it.folderUri }, { it.uri }, folders, remembered) ?: return null
    return PlayStart(copy, PlaybackSources.remembersSource(copies, copy, { PlaybackSources.sourceKey(it.folderUri) }, remembered))
}

/**
 * The copy on the source a title last played from, which Play From checks
 * and marks "Last played" (27.1); null when none is.
 */
internal fun <T> lastPlayedCopy(copies: List<T>, folderUri: (T) -> String?, remembered: String?): T? =
    remembered?.let { source -> copies.firstOrNull { PlaybackSources.sourceKey(folderUri(it)) == source } }

// MARK: - Shows

/**
 * One show across its library rows. A show's rows are keyed by its parsed
 * name, so both links of one share already give one row; rows that parsed
 * differently ("The Office" and "The Office (US)") still merge once
 * enrichment matches them to one TMDB id. [episodeCount] counts each
 * episode once, however many copies it has.
 */
internal data class ShowTitle(
    val key: String,
    val shows: List<LibraryShowEntity>,
    val episodeCount: Int,
) {
    /** The row the poster shows and opens: the first matched one, else the first. */
    val display: LibraryShowEntity get() = shows.firstOrNull { it.tmdbId != null } ?: shows.first()

    /** The poster's key for TV's focus return: a row's own key, which a match doesn't change. */
    val posterKey: String get() = "show:" + shows.minOf { it.key }
}

/** [shows] as titles, in the order of each title's first row. */
internal fun showTitles(shows: List<LibraryShowEntity>, episodes: List<LibraryEpisodeEntity>): List<ShowTitle> {
    val episodesByShow = episodes.groupBy { it.showKey }
    return shows
        .groupBy { show -> show.tmdbId?.let { "tmdb:$it" } ?: "key:${show.key}" }
        .map { (key, rows) ->
            val slots = rows.flatMap { episodesByShow[it.key].orEmpty() }.mapTo(HashSet()) { it.season to it.episode }
            ShowTitle(key, rows, slots.size)
        }
}

// MARK: - A show's page

/** A show's detail page: the page's own show row, its episodes merged across copies, and its remembered source. */
internal data class PageShow(
    val show: LibraryShowEntity,
    /** Every copy's rows: the page's own and those sharing its TMDB id. */
    val copyKeys: Set<String>,
    val slots: List<PlaybackSources.EpisodeSlot<LibraryEpisodeEntity>>,
    /** The source key the show last played from on this device (27.1), if any. */
    val remembered: String?,
)

/**
 * The library show a detail page lists episodes for: the row a library page
 * was opened from, or for a TMDB show page the library's row with its id
 * (Apple's page resolves its library match the same way). Every row of the
 * show — the same key, or the same TMDB id — merges into one slot per season
 * and episode, the page's own rows first, then Play From order (D.5). Null
 * for a movie and for a show the library doesn't hold.
 */
internal fun pageShow(
    source: DetailSource?,
    shows: List<LibraryShowEntity>,
    episodes: List<LibraryEpisodeEntity>,
    folders: Map<String, LibraryFolderEntity>,
    lastSources: Map<String, String>,
): PageShow? {
    val show = when (source) {
        is DetailSource.LocalShow -> shows.firstOrNull { it.key == source.key }
        is DetailSource.Tmdb -> source.ref.takeIf { it.mediaType == com.babasama.edendale.domain.MediaType.TV }
            ?.let { ref -> shows.firstOrNull { it.tmdbId == ref.id } }
        is DetailSource.LocalMovie, null -> null
    } ?: return null
    val copyKeys = shows.filter { it.key == show.key || (show.tmdbId != null && it.tmdbId == show.tmdbId) }
        .mapTo(LinkedHashSet()) { it.key }
    val rank = compareBy<LibraryEpisodeEntity> { it.showKey != show.key }
        .then(PlaybackSources.comparator({ folders.copySource(it.folderUri) }, { it.uri }))
    val slots = PlaybackSources.episodeSlots(
        episodes.filter { it.showKey in copyKeys },
        season = { it.season },
        number = { it.episode },
        rank = rank,
    )
    val remembered = PlaybackSources.rememberedShowSource(lastSources, show.tmdbId, listOf(show.key) + copyKeys.sorted())
    return PageShow(show, copyKeys, slots, remembered)
}
