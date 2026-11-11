package com.babasama.edendale.android

import com.babasama.edendale.android.data.LibraryEpisodeEntity
import com.babasama.edendale.android.data.LibraryFolderEntity
import com.babasama.edendale.android.data.LibraryMovieEntity
import com.babasama.edendale.android.data.LibraryShowEntity
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.SearchScope
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One item per title across endpoints (27.1): which rows are the same file
 * through another link, which rows are one title, a show's page, and that
 * the tile, Continue Watching and the page start the same copy.
 */
class LibraryTitlesTest {

    // MARK: - Fixtures

    private val lan = "smb://longs-mac-studio.local/Extra/"
    private val tailscale = "smb://100.101.102.103/Extra/"

    private fun folder(uri: String, name: String = "Extra", kind: String = "smb", status: String? = null) =
        LibraryFolderEntity(treeUri = uri, displayName = name, addedAtEpochMillis = 0, kind = kind, status = status)

    private val folders = listOf(folder(lan), folder(tailscale)).associateBy { it.treeUri }

    private fun movie(
        uri: String,
        folderUri: String? = lan,
        title: String = "Heat",
        tmdbId: Int? = 949,
    ) = LibraryMovieEntity(
        uri = uri,
        folderUri = folderUri,
        fileName = uri.substringAfterLast('/'),
        title = title,
        year = 1995,
        tmdbId = tmdbId,
        posterPath = null,
        backdropPath = null,
        overview = null,
        runtimeMinutes = 170,
        addedAtEpochMillis = 0L,
    )

    private fun show(key: String = "severance", name: String = "Severance", tmdbId: Int? = 95396) = LibraryShowEntity(
        key = key,
        name = name,
        tmdbId = tmdbId,
        posterPath = null,
        backdropPath = null,
        overview = null,
        firstAirYear = 2022,
    )

    private fun episode(uri: String, showKey: String = "severance", season: Int = 1, number: Int = 1, folderUri: String? = lan) =
        LibraryEpisodeEntity(
            uri = uri,
            folderUri = folderUri,
            showKey = showKey,
            fileName = uri.substringAfterLast('/'),
            season = season,
            episode = number,
            tmdbId = 1000 + season * 100 + number,
            title = null,
            stillPath = null,
            runtimeMinutes = 50,
            addedAtEpochMillis = 0L,
        )

    private val heatLan = movie("smb://longs-mac-studio.local/Extra/Movies/Heat%20(1995).mkv")
    private val heatTailscale = movie("smb://100.101.102.103/Extra/Movies/Heat (1995).mkv", folderUri = tailscale)

    // MARK: - fileIdentity

    @Test
    fun `the LAN name and the Tailscale address reach the same file`() {
        val identity = fileIdentity(heatLan.uri)
        assertEquals("smb:/extra/movies/heat (1995).mkv", identity)
        assertEquals(identity, fileIdentity(heatTailscale.uri))
        // Host case, a port and a userinfo don't matter either.
        assertEquals(identity, fileIdentity("smb://LONGS-MAC-STUDIO.LOCAL:445/Extra/Movies/Heat%20(1995).mkv"))
        assertEquals(identity, fileIdentity("smb2://me@nas/Extra/Movies/HEAT (1995).mkv"))
    }

    @Test
    fun `a link to a subfolder of the same share still matches, other shares and paths don't`() {
        // The file URI carries the whole share path whichever folder was linked.
        assertEquals(fileIdentity(heatLan.uri), fileIdentity("smb://100.101.102.103/Extra/Movies/Heat (1995).mkv"))
        assertNotEquals(fileIdentity(heatLan.uri), fileIdentity("smb://nas/Public/Movies/Heat (1995).mkv"))
        assertNotEquals(fileIdentity(heatLan.uri), fileIdentity("smb://nas/Extra/Movies/Heat (1995) 4K.mkv"))
        // A different protocol is a different file as far as this rule knows.
        assertNotEquals(fileIdentity(heatLan.uri), fileIdentity("nfs://nas/Extra/Movies/Heat (1995).mkv"))
    }

    @Test
    fun `dav and davs are one WebDAV kind`() {
        assertEquals(
            fileIdentity("dav://127.0.0.1:18080/share/Heat.mkv"),
            fileIdentity("davs://nas.tail1234.ts.net/share/Heat.mkv"),
        )
        assertEquals("webdav:/share/heat.mkv", fileIdentity("dav://127.0.0.1:18081/share/Heat.mkv"))
    }

    @Test
    fun `S3 matches by bucket and key whatever the endpoint's account key`() {
        assertEquals(fileIdentity("s3://aaaa/films/Heat.mkv"), fileIdentity("s3://bbbb/films/Heat.mkv"))
        assertNotEquals(fileIdentity("s3://aaaa/films/Heat.mkv"), fileIdentity("s3://aaaa/other/Heat.mkv"))
        // A prefix is a folder, not a file.
        assertNull(fileIdentity("s3://aaaa/films/"))
    }

    @Test
    fun `cloud files match by the provider's file id`() {
        assertEquals(fileIdentity("gdrive://acct1/FILEID/Heat.mkv"), fileIdentity("gdrive://acct2/FILEID/Heat%20renamed.mkv"))
        assertNotEquals(fileIdentity("gdrive://acct1/FILEID/Heat.mkv"), fileIdentity("gdrive://acct1/OTHER/Heat.mkv"))
        assertEquals("onedrive:drive/item", fileIdentity("onedrive://acct/drive/item/Heat.mkv"))
        assertNotEquals(fileIdentity("dropbox://a/id:1/Heat.mkv"), fileIdentity("gdrive://a/id:1/Heat.mkv"))
    }

    @Test
    fun `overlapping folder grants agree on a document`() {
        val viaMovies = "content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2FHeat.mkv"
        val viaRoot = "content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3AMovies%2FHeat.mkv"
        assertEquals(fileIdentity(viaMovies), fileIdentity(viaRoot))
        assertEquals("content:com.android.externalstorage.documents/primary:Movies/Heat.mkv", fileIdentity(viaMovies))
    }

    @Test
    fun `anything else has no identity`() {
        assertNull(fileIdentity("file:///sdcard/Heat.mkv"))
        assertNull(fileIdentity("https://example.com/Heat.mkv"))
        assertNull(fileIdentity("Heat.mkv"))
        assertNull(fileIdentity("content://tree/movies/Heat.mkv"))
    }

    // MARK: - libraryTitles

    @Test
    fun `unmatched aliases of one file are one title`() {
        val a = heatLan.copy(tmdbId = null)
        val b = heatTailscale.copy(tmdbId = null)
        val titles = movieTitles(listOf(a, b), folders)
        assertEquals(1, titles.size)
        assertEquals("file:smb:/extra/movies/heat (1995).mkv", titles.single().key)
        assertNull(titles.single().tmdbId)
        // Play From order: the LAN link before Tailscale.
        assertEquals(listOf(a, b), titles.single().copies)
    }

    @Test
    fun `an alias whose match failed joins its matched twin and takes its id`() {
        val pending = heatTailscale.copy(tmdbId = null)
        val titles = movieTitles(listOf(pending, heatLan), folders)
        assertEquals(1, titles.size)
        assertEquals("tmdb:949", titles.single().key)
        assertEquals(949, titles.single().tmdbId)
        assertEquals(heatLan, titles.single().display)
    }

    @Test
    fun `different files matched to one TMDB title are one title, transitively`() {
        val remux = movie("smb://longs-mac-studio.local/Extra/Movies/Heat 2160p.mkv")
        val pendingAlias = movie("smb://100.101.102.103/Extra/Movies/Heat 2160p.mkv", folderUri = tailscale, tmdbId = null)
        val titles = movieTitles(listOf(heatLan, pendingAlias, remux), folders)
        assertEquals(1, titles.size)
        assertEquals(3, titles.single().copies.size)
    }

    @Test
    fun `unmatched files at different paths stay apart even with one parsed name`() {
        val one = movie("smb://nas/Extra/Movies/Heat.mkv", tmdbId = null)
        val two = movie("smb://nas/Extra/Old/Heat.mkv", tmdbId = null)
        val titles = movieTitles(listOf(one, two), emptyMap())
        assertEquals(2, titles.size)
        assertEquals(listOf("file:smb:/extra/movies/heat.mkv", "file:smb:/extra/old/heat.mkv"), titles.map { it.key })
    }

    @Test
    fun `titles keep the order of their first row and fall back to the uri for a key`() {
        val alien = movie("content://tree/Alien.mkv", folderUri = null, title = "Alien", tmdbId = null)
        val titles = movieTitles(listOf(alien, heatLan, heatTailscale), folders)
        assertEquals(listOf("uri:content://tree/Alien.mkv", "tmdb:949"), titles.map { it.key })
    }

    @Test
    fun `rows matched to different titles never join, even through one path`() {
        // A file replaced in place, or one path on two unrelated hosts.
        val heat = movie("smb://nas-a/Extra/Movies/Film.mkv", title = "Heat", tmdbId = 949)
        val ronin = movie("smb://nas-b/Extra/Movies/Film.mkv", title = "Ronin", tmdbId = 8195)
        val pending = movie("smb://nas-c/Extra/Movies/Film.mkv", title = "Film", tmdbId = null)
        val titles = movieTitles(listOf(heat, ronin, pending), emptyMap())
        assertEquals(listOf("tmdb:949", "tmdb:8195"), titles.map { it.key })
        // The unmatched alias joins the first title it reaches, and each title keeps one id.
        assertEquals(listOf(listOf(949, null), listOf(8195)), titles.map { t -> t.copies.map { it.tmdbId }.sortedBy { it == null } })
    }

    @Test
    fun `a poster keeps its focus key when its title is matched`() {
        val pending = movieTitles(listOf(heatLan.copy(tmdbId = null), heatTailscale.copy(tmdbId = null)), folders).single()
        val matched = movieTitles(listOf(heatLan, heatTailscale.copy(tmdbId = null)), folders).single()
        assertNotEquals(pending.key, matched.key)
        assertEquals(pending.posterKey, matched.posterKey)
        val before = showTitles(listOf(show(tmdbId = null)), emptyList()).single()
        val after = showTitles(listOf(show()), emptyList()).single()
        assertEquals(before.posterKey, after.posterKey)
    }

    // MARK: - Shows

    @Test
    fun `an episode reached through two links counts once`() {
        val shows = listOf(show())
        val episodes = listOf(
            episode("smb://longs-mac-studio.local/Extra/TV/Severance/S01E01.mkv"),
            episode("smb://100.101.102.103/Extra/TV/Severance/S01E01.mkv", folderUri = tailscale),
            episode("smb://longs-mac-studio.local/Extra/TV/Severance/S01E02.mkv", number = 2),
        )
        val titles = showTitles(shows, episodes)
        assertEquals(1, titles.size)
        assertEquals(2, titles.single().episodeCount)
    }

    @Test
    fun `show rows sharing a TMDB id are one title, unmatched rows stay apart`() {
        val shows = listOf(
            show(key = "the office", name = "The Office", tmdbId = null),
            show(key = "the office (us)", name = "The Office (US)", tmdbId = 2316),
            show(key = "the office us", name = "The Office US", tmdbId = 2316),
        )
        val episodes = listOf(
            episode("smb://nas/TV/a/S01E01.mkv", showKey = "the office (us)"),
            episode("smb://nas/TV/b/S01E01.mkv", showKey = "the office us"),
            episode("smb://nas/TV/b/S01E02.mkv", showKey = "the office us", number = 2),
        )
        val titles = showTitles(shows, episodes)
        assertEquals(listOf("key:the office", "tmdb:2316"), titles.map { it.key })
        assertEquals("the office (us)", titles[1].display.key)
        assertEquals(2, titles[1].episodeCount)
        assertEquals(0, titles[0].episodeCount)
    }

    // MARK: - Search

    @Test
    fun `search returns one title per merged movie, matching any copy's name`() {
        val renamed = heatTailscale.copy(title = "Heat Directors Cut", tmdbId = null)
        val titles = movieTitles(listOf(heatLan, renamed), folders)
        val byOwnName = localMatches("heat", SearchScope.ALL, false, titles, emptyList())
        assertEquals(1, byOwnName.movies.size)
        assertEquals(2, byOwnName.movies.single().copies.size)
        // The alias's own parsed name finds the title too.
        assertEquals(1, localMatches("directors", SearchScope.ALL, false, titles, emptyList()).movies.size)
    }

    // MARK: - The same copy everywhere

    @Test
    fun `the tile, Continue Watching and the page start the same copy`() {
        val lanOffline = folders + (lan to folder(lan, status = "offline"))
        val movies = listOf(heatLan, heatTailscale)
        val record = WatchProgress(tmdbId = 949, mediaType = WatchMediaType.MOVIE, position = 0.5, lastWatchedEpochMillis = 1, isCompleted = false)
        for ((sources, remembered) in listOf(folders to null, lanOffline to null, folders to tailscale, lanOffline to lan)) {
            val title = movieTitles(movies, sources).single()
            // The tile opens the page for its display copy; the page plays its copies.
            val page = pageMovie(DetailSource.LocalMovie(title.display.uri), movies, sources)!!
            val pageStart = moviePlayStart(movieCopies(page, movies, sources), sources, remembered)!!
            val card = continueWatching(
                listOf(record),
                movies,
                emptyList(),
                emptyList(),
                folders = sources.values.toList(),
                lastSources = remembered?.let { mapOf("movie:949" to it) }.orEmpty(),
            ).single()
            assertEquals(card.uri, pageStart.copy.uri, "sources=$sources remembered=$remembered")
            assertEquals(card.remembersSource, pageStart.remembersSource)
        }
    }

    @Test
    fun `a pending alias that plays first plays as its matched title`() {
        // The LAN link was just added and isn't matched yet; it ranks first.
        val pendingLan = heatLan.copy(tmdbId = null, title = "Heat (1995)")
        val matched = heatTailscale.copy(posterPath = "/heat.jpg")
        val record = WatchProgress(tmdbId = 949, mediaType = WatchMediaType.MOVIE, position = 0.5, lastWatchedEpochMillis = 1, isCompleted = false)
        val card = continueWatching(listOf(record), listOf(pendingLan, matched), emptyList(), emptyList(), folders = folders.values.toList()).single()
        assertEquals(pendingLan.uri, card.uri)
        assertEquals(949, card.tmdbId)
        assertEquals("movie:949", card.progressKey)
        assertEquals("Heat", card.title)
        assertTrue(card.posterUrl.orEmpty().endsWith("/heat.jpg"))
    }

    @Test
    fun `Play passes over an offline remembered source without forgetting it`() {
        val lanOffline = folders + (lan to folder(lan, status = "offline"))
        val start = moviePlayStart(listOf(heatLan, heatTailscale), lanOffline, remembered = lan)!!
        assertEquals(heatTailscale, start.copy)
        assertFalse(start.remembersSource)
        val back = moviePlayStart(listOf(heatLan, heatTailscale), folders, remembered = lan)!!
        assertEquals(heatLan, back.copy)
        assertTrue(back.remembersSource)
    }

    @Test
    fun `the page lists an unmatched movie's path aliases`() {
        val a = heatLan.copy(tmdbId = null)
        val b = heatTailscale.copy(tmdbId = null)
        val other = movie("smb://longs-mac-studio.local/Extra/Movies/Alien.mkv", title = "Alien", tmdbId = null)
        assertEquals(listOf(b, a), movieCopies(b, listOf(a, other, b), folders))
        assertEquals(b, lastPlayedCopy(listOf(a, b), { it.folderUri }, tailscale))
        assertNull(lastPlayedCopy(listOf(a, b), { it.folderUri }, null))
    }

    @Test
    fun `the player plays a pending episode alias under the episode's id`() {
        val pendingLan = episode("smb://longs-mac-studio.local/Extra/TV/Severance/S01E01.mkv").copy(tmdbId = null, title = null)
        val matched = episode("smb://100.101.102.103/Extra/TV/Severance/S01E01.mkv", folderUri = tailscale).copy(title = "Good News About Hell")
        val rows = listOf(PlaybackSources.PlayerEpisode(pendingLan, listOf(pendingLan, matched)))
        val entry = com.babasama.edendale.android.player.episodeEntries(rows, 95396, null) { folders.copySource(it.folderUri) }.single()
        assertEquals(pendingLan.uri, entry.uri)
        assertEquals(matched.tmdbId, entry.tmdbId)
        assertEquals("Good News About Hell", entry.title)
        assertEquals(listOf(matched.tmdbId, matched.tmdbId), entry.copies.map { it.tmdbId })
    }

    // MARK: - A show's page

    private val shows = listOf(
        show(),
        show(key = "severance (2022)", name = "Severance (2022)"),
        show(key = "other", name = "Other", tmdbId = 1),
    )
    private val episodes = listOf(
        episode("smb://100.101.102.103/Extra/TV/Severance/S01E01.mkv", folderUri = tailscale),
        episode("smb://longs-mac-studio.local/Extra/TV/Severance/S01E01.mkv"),
        episode("smb://longs-mac-studio.local/Extra/TV/Severance/S01E02.mkv", number = 2),
        episode("smb://nas/TV/Severance (2022)/S00E01.mkv", showKey = "severance (2022)", season = 0, number = 1, folderUri = null),
        episode("smb://nas/TV/Other/S01E01.mkv", showKey = "other"),
    )

    @Test
    fun `a show's page merges every copy into one slot per episode`() {
        val page = pageShow(DetailSource.LocalShow("severance"), shows, episodes, folders, emptyMap())!!
        assertEquals(setOf("severance", "severance (2022)"), page.copyKeys)
        assertEquals(listOf("0-1", "1-1", "1-2"), page.slots.map { it.id })
        // The page's own rows first, then Play From order: LAN before Tailscale.
        assertEquals(
            listOf("smb://longs-mac-studio.local/Extra/TV/Severance/S01E01.mkv", "smb://100.101.102.103/Extra/TV/Severance/S01E01.mkv"),
            page.slots[1].copies.map { it.uri },
        )
        assertNull(page.remembered)
    }

    @Test
    fun `a TMDB show page resolves its library match and its remembered source`() {
        val memory = mapOf("show:95396" to tailscale)
        val page = pageShow(DetailSource.Tmdb(MediaRef(95396, MediaType.TV)), shows, episodes, folders, memory)!!
        assertEquals("severance", page.show.key)
        assertEquals(tailscale, page.remembered)
        assertNull(pageShow(DetailSource.Tmdb(MediaRef(95396, MediaType.MOVIE)), shows, episodes, folders, memory))
        assertNull(pageShow(DetailSource.Tmdb(MediaRef(7, MediaType.TV)), shows, episodes, folders, memory))
        assertNull(pageShow(DetailSource.LocalShow("gone"), shows, episodes, folders, memory))
        assertNull(pageShow(DetailSource.LocalMovie("x"), shows, episodes, folders, memory))
    }

    @Test
    fun `an unmatched show's page stays apart from its namesakes and remembers by key`() {
        val unmatched = listOf(show(key = "pilot", name = "Pilot", tmdbId = null), show(key = "pilot 2", name = "Pilot 2", tmdbId = null))
        val rows = listOf(
            episode("smb://nas/TV/Pilot/S01E01.mkv", showKey = "pilot"),
            episode("smb://nas/TV/Pilot 2/S01E01.mkv", showKey = "pilot 2"),
        )
        val page = pageShow(DetailSource.LocalShow("pilot"), unmatched, rows, emptyMap(), mapOf("showKey:pilot" to lan))!!
        assertEquals(setOf("pilot"), page.copyKeys)
        assertEquals(1, page.slots.size)
        assertEquals(lan, page.remembered)
    }

    @Test
    fun `an unmatched library show's page fails closed while a rule is on`() {
        val unmatched = show(tmdbId = null)
        assertNull(pageGateRef(null, null, unmatched))
        assertTrue(detailRestricted(policyActive = true, ref = pageGateRef(null, null, unmatched)) { true })
        assertFalse(detailRestricted(policyActive = false, ref = pageGateRef(null, null, unmatched)) { true })
        // Matched: judged by its TMDB title.
        val matched = pageGateRef(null, null, show())
        assertEquals(MediaRef(95396, MediaType.TV), matched)
        assertFalse(detailRestricted(policyActive = true, ref = matched) { true })
        assertTrue(detailRestricted(policyActive = true, ref = matched) { false })
        // A TMDB page's own title wins.
        assertEquals(MediaRef(1, MediaType.TV), pageGateRef(MediaRef(1, MediaType.TV), null, show()))
    }

    @Test
    fun `a library show's page survives process death`() {
        val source = DetailSource.LocalShow("severance (2022)")
        assertEquals(source, DetailSource.decode(DetailSource.encode(source)))
    }
}
