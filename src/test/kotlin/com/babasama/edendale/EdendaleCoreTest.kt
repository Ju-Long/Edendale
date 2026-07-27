package com.babasama.edendale

import com.babasama.edendale.domain.MediaItem
import com.babasama.edendale.domain.MediaParser
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.ParsedMedia
import com.babasama.edendale.domain.TmdbImageSize
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EdendaleCoreTest {

    @Test
    fun platformNameIsNotBlank() {
        assertTrue(platformName.isNotBlank())
        assertTrue(EdendaleCore.description.contains(platformName))
    }

    @Test
    fun tmdbSecretsAreAccessible() {
        // The generated TmdbSecrets object must exist and be readable whether
        // or not secrets.json is present. Codegen trims values, so this holds
        // for both empty and real credentials — without printing them.
        assertTrue(TmdbSecrets.readAccessToken == TmdbSecrets.readAccessToken.trim())
        assertTrue(TmdbSecrets.apiKey == TmdbSecrets.apiKey.trim())
    }

    @Test
    fun parserClassifiesCommonEpisodeNamesWithoutNetwork() {
        val standard = assertIs<ParsedMedia.Episode>(MediaParser.parse("Slow.Horses.S03E04.2160p.mkv"))
        assertEquals("Slow Horses", standard.showName)
        assertEquals(3, standard.season)
        assertEquals(4, standard.episode)

        val alternate = assertIs<ParsedMedia.Episode>(MediaParser.parse("The Bear 2x07.mp4"))
        assertEquals("The Bear", alternate.showName)
        assertEquals(2, alternate.season)
        assertEquals(7, alternate.episode)
    }

    @Test
    fun parserExtractsMovieYearAndCleansTitle() {
        val movie = assertIs<ParsedMedia.Movie>(MediaParser.parse("/Movies/Past_Lives-(2023).mkv"))
        assertEquals("Past Lives", movie.title)
        assertEquals(2023, movie.year)

        val terminalYear = assertIs<ParsedMedia.Movie>(MediaParser.parse("Movie.Title.2024.mkv"))
        assertEquals("Movie Title", terminalYear.title)
        assertEquals(2024, terminalYear.year)

        val undated = assertIs<ParsedMedia.Movie>(MediaParser.parse("Perfect.Days.mkv"))
        assertEquals("Perfect Days", undated.title)
        assertNull(undated.year)
    }

    @Test
    fun mediaDomainDerivesStableDisplayValues() {
        val item = MediaItem(
            id = 1,
            mediaType = MediaType.MOVIE,
            title = "Example",
            posterPath = "/poster.jpg",
            releaseDate = "2026-07-18",
        )
        assertEquals(2026, item.year)
        assertEquals(
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            item.posterUrl(TmdbImageSize.POSTER_LARGE),
        )

        val progress = WatchProgress(1, WatchMediaType.MOVIE, position = 1.4)
        assertEquals(1.0, progress.normalizedPosition)
        assertEquals("movie:1", progress.storageKey)
    }
}
