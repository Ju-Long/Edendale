package com.babasama.edendale.android.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * D.2.T2: the 2 → 3 migration keeps every row and fills in each source's
 * `kind`. Runs against Room's exported schemas in the androidTest assets.
 */
@RunWith(AndroidJUnit4::class)
class EdendaleMigrationInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EdendaleDatabase::class.java,
    )

    @Test
    fun migrate2To3KeepsEveryRowAndFillsInKind() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO library_folder (treeUri, displayName, addedAtEpochMillis) VALUES " +
                    "('content://com.android.externalstorage.documents/tree/primary%3AMovies', 'Movies', 100), " +
                    "('smb://nas/media/', 'media', 200), " +
                    "('file:///odd', 'Odd', 300)",
            )
            execSQL(
                "INSERT INTO library_movie (uri, folderUri, fileName, title, year, tmdbId, posterPath, " +
                    "backdropPath, overview, runtimeMinutes, addedAtEpochMillis) VALUES " +
                    "('smb://nas/media/Alien.mkv', 'smb://nas/media/', 'Alien.mkv', 'Alien', 1979, 348, " +
                    "'/p.jpg', '/b.jpg', 'In space', 117, 5)",
            )
            execSQL(
                "INSERT INTO watch_progress (storageKey, tmdbId, mediaType, position, watchedSeconds, " +
                    "lastWatchedEpochMillis, isCompleted, showTmdbId, seasonNumber, episodeNumber) VALUES " +
                    "('movie:348', 348, 'movie', 0.5, 3500.0, 999, 0, NULL, NULL, NULL)",
            )
            execSQL(
                "INSERT INTO user_media (tmdbId, mediaType, title, posterPath, favourite, favouriteUpdatedAt, " +
                    "favouriteDirty, watchlist, watchlistUpdatedAt, watchlistDirty, rating, ratingUpdatedAt, " +
                    "ratingDirty) VALUES (348, 'movie', 'Alien', '/p.jpg', 1, 1, 0, 0, 0, 0, 8.5, 2, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, EdendaleDatabase.MIGRATION_2_3)

        db.query(
            "SELECT treeUri, displayName, addedAtEpochMillis, kind, displayPath, accountKey, " +
                "lastScannedAt, changeCursor, status FROM library_folder ORDER BY addedAtEpochMillis",
        ).use { cursor ->
            assertEquals(3, cursor.count)
            val expected = listOf(
                Triple("Movies", 100L, "local"),
                Triple("media", 200L, "smb"),
                Triple("Odd", 300L, null),
            )
            expected.forEach { (name, added, kind) ->
                cursor.moveToNext()
                assertEquals(name, cursor.getString(1))
                assertEquals(added, cursor.getLong(2))
                assertEquals(kind, cursor.getString(3))
                for (column in 4..8) assertNull(cursor.getString(column))
            }
        }
        db.query("SELECT title, tmdbId, runtimeMinutes FROM library_movie").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("Alien", cursor.getString(0))
            assertEquals(348, cursor.getInt(1))
            assertEquals(117, cursor.getInt(2))
        }
        db.query("SELECT position, lastWatchedEpochMillis FROM watch_progress").use { cursor ->
            cursor.moveToFirst()
            assertEquals(0.5, cursor.getDouble(0), 0.0)
            assertEquals(999L, cursor.getLong(1))
        }
        db.query("SELECT rating, ratingDirty FROM user_media").use { cursor ->
            cursor.moveToFirst()
            assertEquals(8.5, cursor.getDouble(0), 0.0)
            assertEquals(1, cursor.getInt(1))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
