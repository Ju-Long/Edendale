package com.babasama.edendale.android.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A scan's commit (LibraryDao.applyScan) on a real SQLite: the rows, the
 * scan time, and the cleared `scanStartedAt` land together or not at all;
 * a scan never resets a TMDB match; and thousands of stale rows go even on
 * Android 8 to 11, which bind at most 999 values per statement.
 */
@RunWith(AndroidJUnit4::class)
class LibraryDaoInstrumentedTest {

    private lateinit var database: EdendaleDatabase
    private val dao get() = database.libraryDao()

    private val share = "smb://nas/media/"

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            EdendaleDatabase::class.java,
        ).build()
    }

    @After
    fun close() = database.close()

    private fun folder(scanStartedAt: Long? = 100, status: String? = "offline") = LibraryFolderEntity(
        treeUri = share,
        displayName = "media",
        addedAtEpochMillis = 1,
        kind = "smb",
        lastScannedAt = 50,
        status = status,
        scanStartedAt = scanStartedAt,
    )

    private fun movie(name: String, tmdbId: Int? = null) = LibraryMovieEntity(
        uri = "$share$name",
        folderUri = share,
        fileName = name,
        title = name.substringBeforeLast('.'),
        year = null,
        tmdbId = tmdbId,
        posterPath = tmdbId?.let { "/p$it.jpg" },
        backdropPath = null,
        overview = null,
        runtimeMinutes = null,
        addedAtEpochMillis = 2,
    )

    private fun show(key: String, tmdbId: Int? = null) = LibraryShowEntity(
        key = key,
        name = key,
        tmdbId = tmdbId,
        posterPath = null,
        backdropPath = null,
        overview = null,
        firstAirYear = null,
    )

    private fun episode(showKey: String, number: Int, tmdbId: Int? = null): LibraryEpisodeEntity {
        val name = "$showKey.S01E${number.toString().padStart(2, '0')}.mkv"
        return LibraryEpisodeEntity(
            uri = "$share$name",
            folderUri = share,
            showKey = showKey,
            fileName = name,
            season = 1,
            episode = number,
            tmdbId = tmdbId,
            title = null,
            stillPath = null,
            runtimeMinutes = null,
            addedAtEpochMillis = 2,
        )
    }

    private fun plan(
        staleMovies: List<String> = emptyList(),
        staleEpisodes: List<String> = emptyList(),
        movies: List<LibraryMovieEntity> = emptyList(),
        shows: List<LibraryShowEntity> = emptyList(),
        episodes: List<LibraryEpisodeEntity> = emptyList(),
    ) = ScanPlan(staleMovies, staleEpisodes, movies, shows, episodes)

    private val renamedAndNew
        get() = plan(
            staleMovies = listOf("${share}Ronin (1998).mkv"),
            movies = listOf(movie("Heat (1995).mkv")),
            shows = listOf(show("severance")),
            episodes = listOf(episode("severance", 1)),
        )

    @Test
    fun applyScanCommitsRowsScanTimeAndMarkerTogether() = runBlocking<Unit> {
        dao.upsertFolder(folder(scanStartedAt = null))
        dao.insertMovies(listOf(movie("Ronin (1998).mkv")))
        dao.markScanStarted(share, 100)
        assertEquals(listOf(share), dao.interruptedFolders().map { it.treeUri })

        assertTrue(dao.applyScan(share, renamedAndNew, scannedAt = 200))

        assertEquals(listOf("${share}Heat (1995).mkv"), dao.movies().map { it.uri })
        assertEquals(listOf("severance"), dao.shows().map { it.key })
        assertEquals(listOf("${share}severance.S01E01.mkv"), dao.episodes().map { it.uri })
        val source = dao.folders().single()
        assertEquals(200L, source.lastScannedAt)
        assertNull(source.scanStartedAt)
        assertNull(source.status)
        assertTrue(dao.interruptedFolders().isEmpty())
    }

    @Test
    fun aFailedCommitChangesNothingAndLeavesTheScanToRedo() = runBlocking<Unit> {
        dao.upsertFolder(folder())
        dao.insertMovies(listOf(movie("Ronin (1998).mkv")))
        // The commit's last write fails, after its deletes and inserts ran.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_scan_commit BEFORE UPDATE OF lastScannedAt ON library_folder " +
                "BEGIN SELECT RAISE(ABORT, 'commit failed'); END",
        )

        assertTrue(runCatching { dao.applyScan(share, renamedAndNew, scannedAt = 200) }.isFailure)

        assertEquals(listOf("${share}Ronin (1998).mkv"), dao.movies().map { it.uri })
        assertTrue(dao.shows().isEmpty())
        assertTrue(dao.episodes().isEmpty())
        val source = dao.folders().single()
        assertEquals(50L, source.lastScannedAt)
        assertEquals(100L, source.scanStartedAt)
        assertEquals(listOf(share), dao.interruptedFolders().map { it.treeUri })
    }

    @Test
    fun aSourceRemovedMidScanGetsNoRows() = runBlocking<Unit> {
        assertFalse(dao.applyScan(share, renamedAndNew, scannedAt = 200))
        assertTrue(dao.movies().isEmpty())
        assertTrue(dao.shows().isEmpty())
        assertTrue(dao.episodes().isEmpty())
        assertTrue(dao.folders().isEmpty())
    }

    @Test
    fun aScanNeverResetsAMatch() = runBlocking<Unit> {
        dao.upsertFolder(folder())
        dao.insertMovies(listOf(movie("Heat (1995).mkv", tmdbId = 949)))
        dao.insertShows(listOf(show("severance", tmdbId = 95396)))
        dao.insertEpisodes(listOf(episode("severance", 1, tmdbId = 1971153)))

        assertTrue(dao.applyScan(share, renamedAndNew, scannedAt = 200))

        val heat = dao.movies().single()
        assertEquals(949, heat.tmdbId)
        assertEquals("/p949.jpg", heat.posterPath)
        assertEquals(95396, dao.shows().single().tmdbId)
        assertEquals(1971153, dao.episodes().single().tmdbId)
    }

    @Test
    fun thousandsOfStaleRowsGoInOneCommit() = runBlocking<Unit> {
        dao.upsertFolder(folder())
        dao.insertMovies((1..1_500).map { movie("Movie $it.mkv") })
        dao.insertShows(listOf(show("show")))
        dao.insertEpisodes((1..1_500).map { episode("show", it) })

        // Planned and committed in one outer transaction, as a scan stores its results.
        val committed = database.withTransaction {
            dao.applyScan(
                share,
                plan(staleMovies = dao.movieUrisInFolder(share), staleEpisodes = dao.episodeUrisInFolder(share)),
                scannedAt = 200,
            )
        }

        assertTrue(committed)
        assertTrue(dao.movies().isEmpty())
        assertTrue(dao.episodes().isEmpty())
        assertTrue(dao.shows().isEmpty())
        assertEquals(200L, dao.folders().single().lastScannedAt)
    }

    /**
     * What a scan checks once it has claimed a source (LibraryRepository.scan):
     * the row as another scan's commit, a recorded failure, or a removal left
     * it while this one waited its turn.
     */
    @Test
    fun aWaitingScanSeesTheSourceAsTheOtherScanLeftIt() = runBlocking<Unit> {
        dao.upsertFolder(folder(status = null))
        val interrupted = dao.folder(share)
        assertTrue(interrupted != null && SourceScanRules.isInterrupted(interrupted))

        // Committed meantime: neither the launch resume nor the sweep lists it again.
        assertTrue(dao.applyScan(share, plan(), scannedAt = 200))
        val committed = dao.folder(share)!!
        assertFalse(SourceScanRules.isInterrupted(committed))
        assertFalse(SourceScanRules.shouldAutoRescan(committed, 200 + 60_000))

        // A recorded failure ends it for the resume as well.
        dao.markScanStarted(share, 300)
        dao.markFolderFailed(share, "offline")
        assertFalse(SourceScanRules.isInterrupted(dao.folder(share)!!))

        dao.removeFolderTree(share)
        assertNull(dao.folder(share))
    }

    @Test
    fun aRecordedFailureEndsTheScanAndMatchesOnlyUpdateRows() = runBlocking<Unit> {
        dao.upsertFolder(folder(scanStartedAt = null, status = null))
        dao.markScanStarted(share, 300)
        assertEquals(300L, dao.interruptedFolders().single().scanStartedAt)
        dao.markFolderFailed(share, "offline")
        assertTrue(dao.interruptedFolders().isEmpty())
        assertEquals("offline", dao.folders().single().status)
        assertEquals(50L, dao.folders().single().lastScannedAt)

        // A match for a row a scan deleted meanwhile doesn't bring it back.
        dao.updateMovieMatch("${share}Gone.mkv", 1, null, null, null, null, null)
        assertTrue(dao.movies().isEmpty())

        dao.insertMovies(listOf(movie("A.mkv"), movie("B.mkv", tmdbId = 2)))
        dao.insertShows(listOf(show("matched", tmdbId = 3), show("unmatched")))
        dao.insertEpisodes(listOf(episode("matched", 1), episode("matched", 2, tmdbId = 4), episode("unmatched", 1)))
        // A, the unmatched show, and matched E01; an unmatched show's episodes wait for it.
        assertEquals(3, dao.pendingEnrichmentCount())

        dao.updateMovieMatch("${share}A.mkv", 5, "/a.jpg", null, "Overview", 120, 1999)
        dao.updateShowMatch("unmatched", 6, "/u.jpg", null, null, 2020)
        val a = dao.movieByUri("${share}A.mkv")
        assertEquals(5, a?.tmdbId)
        assertEquals(120, a?.runtimeMinutes)
        assertEquals("A", a?.title)
        assertEquals(6, dao.showByKey("unmatched")?.tmdbId)
        assertEquals(2, dao.pendingEnrichmentCount())

        dao.updateEpisodeMatch("${share}matched.S01E01.mkv", 7, "Pilot", "/s.jpg", 57)
        dao.updateEpisodeMatch("${share}unmatched.S01E01.mkv", 8, null, null, null)
        assertEquals(0, dao.pendingEnrichmentCount())
        assertEquals("Pilot", dao.episodeByUri("${share}matched.S01E01.mkv")?.title)
    }
}
