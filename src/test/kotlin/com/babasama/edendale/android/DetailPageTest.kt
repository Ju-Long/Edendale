package com.babasama.edendale.android

import com.babasama.edendale.android.data.LibraryFolderEntity
import com.babasama.edendale.android.data.LibraryMovieEntity
import com.babasama.edendale.android.data.LibraryShowEntity
import com.babasama.edendale.domain.MediaDetail
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.MediaType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The detail page's pure rules: one page for TMDB titles and library movies
 * (Apple's `MediaDetailSource`), its header, the movie it plays, and the
 * parental gate it shares with the player.
 */
class DetailPageTest {

    // MARK: - Fixtures

    private val heat = MediaRef(949, MediaType.MOVIE)

    private fun movie(
        uri: String,
        title: String = "Heat",
        year: Int? = 1995,
        tmdbId: Int? = 949,
        folderUri: String? = "content://tree/movies",
        overview: String? = null,
        runtime: Int? = null,
        poster: String? = null,
        backdrop: String? = null,
    ) = LibraryMovieEntity(
        uri = uri,
        folderUri = folderUri,
        fileName = uri.substringAfterLast('/'),
        title = title,
        year = year,
        tmdbId = tmdbId,
        posterPath = poster,
        backdropPath = backdrop,
        overview = overview,
        runtimeMinutes = runtime,
        addedAtEpochMillis = 0L,
    )

    private fun folder(uri: String, name: String, kind: String, status: String? = null) =
        LibraryFolderEntity(treeUri = uri, displayName = name, addedAtEpochMillis = 0, kind = kind, status = status)

    private val folders = listOf(
        folder("smb://nas/films/", "NAS", "smb"),
        folder("content://tree/movies", "Movies", "local"),
    ).associateBy { it.treeUri }

    private val onNas = movie(uri = "smb://nas/films/Heat.mkv", folderUri = "smb://nas/films/")
    private val onPhone = movie(uri = "content://tree/movies/Heat.mkv")
    private val alien = movie(uri = "content://tree/movies/Alien.mkv", title = "Alien", year = 1979, tmdbId = 348)

    // MARK: - Header

    @Test
    fun `TMDB's record wins field by field`() {
        val row = movie(
            uri = onPhone.uri,
            title = "Heat Remastered",
            year = 1996,
            overview = "The row's overview.",
            runtime = 168,
            poster = "/row.jpg",
            backdrop = "/row-wide.jpg",
        )
        val detail = MediaDetail(
            ref = heat,
            title = "Heat",
            tagline = "A Los Angeles crime saga",
            overview = "TMDB's overview.",
            posterPath = "/tmdb.jpg",
            backdropPath = "/tmdb-wide.jpg",
            year = 1995,
            runtimeMinutes = 170,
            score = 7.9,
        )

        val header = detailHeader(detail, row, null)!!

        assertEquals(MediaType.MOVIE, header.mediaType)
        assertEquals("Heat", header.title)
        assertEquals("A Los Angeles crime saga", header.tagline)
        assertEquals("TMDB's overview.", header.overview)
        assertEquals("/tmdb.jpg", header.posterPath)
        assertEquals("/tmdb-wide.jpg", header.backdropPath)
        assertEquals(1995, header.year)
        assertEquals(170, header.runtimeMinutes)
        assertEquals(7.9, header.score)
    }

    @Test
    fun `without a TMDB record the row's own fields fill the header`() {
        val row = movie(
            uri = onPhone.uri,
            overview = "The row's overview.",
            runtime = 170,
            poster = "/row.jpg",
            backdrop = "/row-wide.jpg",
        )

        val header = detailHeader(null, row, null)!!

        assertEquals(MediaType.MOVIE, header.mediaType)
        assertEquals("Heat", header.title)
        assertEquals(1995, header.year)
        assertEquals("The row's overview.", header.overview)
        assertEquals("/row.jpg", header.posterPath)
        assertEquals("/row-wide.jpg", header.backdropPath)
        assertEquals(170, header.runtimeMinutes)
        // Only TMDB has a tagline and a score.
        assertNull(header.tagline)
        assertNull(header.score)
    }

    @Test
    fun `a field TMDB leaves empty falls back to the row`() {
        val row = movie(uri = onPhone.uri, overview = "The row's overview.", runtime = 170, backdrop = "/row-wide.jpg")
        val detail = MediaDetail(ref = heat, title = "Heat", overview = "  ", runtimeMinutes = 0, tagline = "")

        val header = detailHeader(detail, row, null)!!

        assertEquals("The row's overview.", header.overview)
        assertEquals(170, header.runtimeMinutes)
        assertEquals("/row-wide.jpg", header.backdropPath)
        assertNull(header.tagline)
    }

    @Test
    fun `an unmatched file gives its parsed title and year, and no TMDB title`() {
        val file = movie(uri = "content://tree/movies/Home.Movie.2019.mkv", title = "Home Movie", year = 2019, tmdbId = null)

        val header = detailHeader(null, file, null)!!

        assertEquals("Home Movie", header.title)
        assertEquals(2019, header.year)
        assertNull(header.overview)
        assertNull(header.backdropPath)
        assertNull(header.runtimeMinutes)
        assertNull(file.tmdbRef)
        assertEquals(heat, onPhone.tmdbRef)
    }

    @Test
    fun `a library show fills a show's header`() {
        val show = LibraryShowEntity(
            key = "severance",
            name = "Severance",
            tmdbId = null,
            posterPath = "/severance.jpg",
            backdropPath = null,
            overview = "Work and life, split.",
            firstAirYear = 2022,
        )

        val header = detailHeader(null, null, show)!!

        assertEquals(MediaType.TV, header.mediaType)
        assertEquals("Severance", header.title)
        assertEquals(2022, header.year)
        assertEquals("Work and life, split.", header.overview)
    }

    @Test
    fun `nothing loaded yet gives no header`() {
        assertNull(detailHeader(null, null, null))
    }

    // MARK: - The movie a page plays

    @Test
    fun `a library page plays the copy that was opened`() {
        val movies = listOf(onNas, onPhone, alien)

        assertEquals(onNas, pageMovie(DetailSource.LocalMovie(onNas.uri), movies, folders))
        assertEquals(onPhone, pageMovie(DetailSource.LocalMovie(onPhone.uri), movies, folders))
    }

    @Test
    fun `a TMDB movie page plays the first copy in Play From order`() {
        // The local folder leads Play From, whatever order the library lists them in.
        val page = pageMovie(DetailSource.Tmdb(heat), listOf(onNas, alien, onPhone), folders)

        assertEquals(onPhone, page)
        assertEquals(listOf(onPhone, onNas), movieCopies(page!!, listOf(onNas, alien, onPhone), folders))
    }

    @Test
    fun `a show, a title the library lacks, or a file that left has no movie to play`() {
        val movies = listOf(onNas, onPhone, alien)

        assertNull(pageMovie(DetailSource.Tmdb(MediaRef(949, MediaType.TV)), movies, folders))
        assertNull(pageMovie(DetailSource.Tmdb(MediaRef(680, MediaType.MOVIE)), movies, folders))
        assertNull(pageMovie(DetailSource.LocalMovie("content://tree/movies/Gone.mkv"), movies, folders))
        assertNull(pageMovie(DetailSource.LocalShow("severance"), movies, folders))
        assertNull(pageMovie(null, movies, folders))
    }

    @Test
    fun `an unmatched movie lists only itself in Play From`() {
        val file = movie(uri = "content://tree/movies/Home.mkv", title = "Home", tmdbId = null)
        val sameName = movie(uri = "smb://nas/films/Home.mkv", title = "Home", tmdbId = null, folderUri = "smb://nas/films/")

        assertEquals(listOf(file), movieCopies(file, listOf(file, sameName), folders))
        assertEquals(file, moviePlayStart(movieCopies(file, listOf(file, sameName), folders), folders, remembered = null)!!.copy)
    }

    // MARK: - Parental gate

    @Test
    fun `an active rule blocks a file without a TMDB match`() {
        assertTrue(detailRestricted(policyActive = true, ref = null, allows = { true }))
    }

    @Test
    fun `an active rule opens only titles verified as allowed`() {
        assertFalse(detailRestricted(policyActive = true, ref = heat, allows = { it == heat }))
        assertTrue(detailRestricted(policyActive = true, ref = heat, allows = { false }))
    }

    @Test
    fun `with no rule in force every page opens`() {
        assertFalse(detailRestricted(policyActive = false, ref = null, allows = { false }))
        assertFalse(detailRestricted(policyActive = false, ref = heat, allows = { false }))
    }

    // MARK: - Saved page

    @Test
    fun `an open page survives as text`() {
        listOf(
            DetailSource.Tmdb(MediaRef(603, MediaType.MOVIE)),
            DetailSource.Tmdb(MediaRef(1399, MediaType.TV)),
            DetailSource.LocalMovie("content://com.android.externalstorage.documents/tree/primary%3AMovies/document/primary%3AMovies%2FHeat.mkv"),
            DetailSource.LocalMovie("smb://nas/films/Heat: Director's Cut.mkv"),
            DetailSource.LocalShow("severance"),
        ).forEach { source ->
            assertEquals(source, DetailSource.decode(DetailSource.encode(source)))
        }
    }

    @Test
    fun `saved text that isn't a page opens nothing`() {
        listOf("", "tmdb", "tmdb:", "tmdb:movie", "tmdb:movie:abc", "tmdb:film:603", "movie:", "show:", "person:31").forEach {
            assertNull(DetailSource.decode(it), it)
        }
    }

    // MARK: - Focus scrolling

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Test
    fun `focus scrolls the page only as far as it must`() {
        // TV's own rule: put the item 30% down the screen, whatever it was.
        val pivot = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = offset - containerSize * 0.3f
        }
        val spec = OnScreenStaysBringIntoViewSpec(pivot)
        // Play, or a show's first episode, already wholly on screen: no scroll.
        assertEquals(0f, spec.calculateScrollDistance(offset = 1849f, size = 262f, containerSize = 2160f))
        // Partly below the edge: just far enough, plus 6% of the screen of room.
        assertEquals(1916f + 262f - 2160f + 129.6f, spec.calculateScrollDistance(1916f, 262f, 2160f), 0.01f)
        // Partly above: back up the same way.
        assertEquals(-100f - 129.6f, spec.calculateScrollDistance(-100f, 262f, 2160f), 0.01f)
        // Wholly off screen: the platform's rule.
        assertEquals(3000f - 648f, spec.calculateScrollDistance(3000f, 262f, 2160f), 0.01f)
        // Taller than the screen: the platform's rule too.
        assertEquals(-50f - 648f, spec.calculateScrollDistance(-50f, 2400f, 2160f), 0.01f)
    }
}
