package com.babasama.edendale.android.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.UserMediaRecord
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import kotlinx.coroutines.flow.Flow

// Room persistence for the user's watch data. The database file rides
// Android Auto Backup to Google Drive (see AndroidManifest backup rules), so
// state survives device migration; the TMDB session prefs are excluded there.
// Rows mirror the UserMediaRecord / WatchProgress domain models exactly;
// mapping stays here so the rest of the app only sees domain types.

@Entity(tableName = "user_media", primaryKeys = ["tmdbId", "mediaType"])
data class UserMediaEntity(
    val tmdbId: Int,
    /** "movie" or "tv" (MediaType.pathSegment). */
    val mediaType: String,
    val title: String?,
    val posterPath: String?,
    val favourite: Boolean,
    val favouriteUpdatedAt: Long,
    val favouriteDirty: Boolean,
    val watchlist: Boolean,
    val watchlistUpdatedAt: Long,
    val watchlistDirty: Boolean,
    val rating: Double?,
    val ratingUpdatedAt: Long,
    val ratingDirty: Boolean,
) {
    fun toDomain(): UserMediaRecord = UserMediaRecord(
        tmdbId = tmdbId,
        mediaType = if (mediaType == "tv") MediaType.TV else MediaType.MOVIE,
        title = title,
        posterPath = posterPath,
        favourite = favourite,
        favouriteUpdatedAt = favouriteUpdatedAt,
        favouriteDirty = favouriteDirty,
        watchlist = watchlist,
        watchlistUpdatedAt = watchlistUpdatedAt,
        watchlistDirty = watchlistDirty,
        rating = rating,
        ratingUpdatedAt = ratingUpdatedAt,
        ratingDirty = ratingDirty,
    )

    companion object {
        fun fromDomain(record: UserMediaRecord): UserMediaEntity = UserMediaEntity(
            tmdbId = record.tmdbId,
            mediaType = record.mediaType.pathSegment,
            title = record.title,
            posterPath = record.posterPath,
            favourite = record.favourite,
            favouriteUpdatedAt = record.favouriteUpdatedAt,
            favouriteDirty = record.favouriteDirty,
            watchlist = record.watchlist,
            watchlistUpdatedAt = record.watchlistUpdatedAt,
            watchlistDirty = record.watchlistDirty,
            rating = record.rating,
            ratingUpdatedAt = record.ratingUpdatedAt,
            ratingDirty = record.ratingDirty,
        )
    }
}

@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    /** Domain storage key, e.g. "movie:603" / "episode:62085". */
    @PrimaryKey val storageKey: String,
    val tmdbId: Int,
    /** "movie" or "episode" (WatchMediaType). */
    val mediaType: String,
    val position: Double,
    val watchedSeconds: Double,
    val lastWatchedEpochMillis: Long,
    val isCompleted: Boolean,
    val showTmdbId: Int?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    /** The title's length in seconds when known (v4, for 27.1 sync's `duration`); 0 before then. */
    @ColumnInfo(defaultValue = "0")
    val durationSeconds: Double = 0.0,
) {
    fun toDomain(): WatchProgress = WatchProgress(
        tmdbId = tmdbId,
        mediaType = if (mediaType == "episode") WatchMediaType.EPISODE else WatchMediaType.MOVIE,
        position = position,
        watchedSeconds = watchedSeconds,
        lastWatchedEpochMillis = lastWatchedEpochMillis,
        isCompleted = isCompleted,
        showTmdbId = showTmdbId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        durationSeconds = durationSeconds,
    )

    companion object {
        fun fromDomain(record: WatchProgress): WatchProgressEntity = WatchProgressEntity(
            storageKey = record.storageKey,
            tmdbId = record.tmdbId,
            mediaType = record.mediaType.name.lowercase(),
            position = record.normalizedPosition,
            watchedSeconds = record.watchedSeconds,
            lastWatchedEpochMillis = record.lastWatchedEpochMillis,
            isCompleted = record.isCompleted,
            showTmdbId = record.showTmdbId,
            seasonNumber = record.seasonNumber,
            episodeNumber = record.episodeNumber,
            durationSeconds = record.durationSeconds,
        )
    }
}

/**
 * The portable watch-progress records of 27.1 sync (SYNC-PROTOCOL.md §6.1),
 * one row per key, exactly as the device file carries them: the merged view
 * of every device in the space, tombstones included. `watch_progress` stays
 * this device's own, file-keyed view and is projected from here.
 */
@Entity(tableName = "sync_watch_progress")
data class SyncProgressEntity(
    /** `movie:<id>` or `tv:<id>:<season>:<episode>`. */
    @PrimaryKey val key: String,
    val type: String,
    val tmdbId: Int,
    val season: Int?,
    val episode: Int?,
    val position: Double?,
    val duration: Double?,
    val completed: Boolean?,
    val cleared: Boolean,
    val device: String,
    val updateTime: Long,
)

/** The portable user-media records of 27.1 sync (§6.2); `user_media` is projected from here. */
@Entity(tableName = "sync_user_media")
data class SyncUserMediaEntity(
    /** `movie:<id>` or `tv:<id>`. */
    @PrimaryKey val key: String,
    val type: String,
    val tmdbId: Int,
    val favourite: Boolean,
    val watchlist: Boolean,
    val rating: Double?,
    val review: String?,
    val device: String,
    val updateTime: Long,
)

@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_watch_progress")
    suspend fun allProgress(): List<SyncProgressEntity>

    @Query("SELECT * FROM sync_user_media")
    suspend fun allUserMedia(): List<SyncUserMediaEntity>

    @Query("SELECT * FROM sync_watch_progress WHERE `key` = :key")
    suspend fun progress(key: String): SyncProgressEntity?

    @Query("SELECT * FROM sync_user_media WHERE `key` = :key")
    suspend fun userMedia(key: String): SyncUserMediaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProgress(entities: List<SyncProgressEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserMedia(entities: List<SyncUserMediaEntity>)

    @Query("DELETE FROM sync_watch_progress")
    suspend fun clearProgress()

    @Query("DELETE FROM sync_user_media")
    suspend fun clearUserMedia()

    @Query("SELECT COUNT(*) FROM sync_watch_progress")
    suspend fun progressCount(): Int

    @Query("SELECT COUNT(*) FROM sync_user_media")
    suspend fun userMediaCount(): Int

    /** Progress records that aren't tombstones: what Delete Watch History still has to clear, here or from other devices. */
    @Query("SELECT COUNT(*) FROM sync_watch_progress WHERE cleared = 0")
    fun observeLiveProgressCount(): Flow<Int>

    /** This device's own view (`watch_progress`), emptied inside the transactions below. */
    @Query("DELETE FROM watch_progress")
    suspend fun deleteLocalProgress()

    /** Delete Watch History with sync on: the tombstones and the emptied local view land together or not at all. */
    @Transaction
    suspend fun replaceProgress(tombstones: List<SyncProgressEntity>) {
        upsertProgress(tombstones)
        deleteLocalProgress()
    }

    /** Delete Watch History with sync off: both progress tables emptied, with no tombstones. */
    @Transaction
    suspend fun forgetProgress() {
        clearProgress()
        deleteLocalProgress()
    }
}

@Dao
interface UserMediaDao {
    @Query("SELECT * FROM user_media")
    fun observeAll(): Flow<List<UserMediaEntity>>

    @Query("SELECT * FROM user_media")
    suspend fun all(): List<UserMediaEntity>

    @Query("SELECT * FROM user_media WHERE tmdbId = :tmdbId AND mediaType = :mediaType")
    suspend fun get(tmdbId: Int, mediaType: String): UserMediaEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserMediaEntity)

    @Query("DELETE FROM user_media WHERE tmdbId = :tmdbId AND mediaType = :mediaType")
    suspend fun delete(tmdbId: Int, mediaType: String)

    @Query("DELETE FROM user_media")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<UserMediaEntity>)

    /** Applies a synced collection atomically (sync results replace the table). */
    @Transaction
    suspend fun replaceAll(entities: List<UserMediaEntity>) {
        clear()
        upsertAll(entities)
    }
}

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress")
    fun observeAll(): Flow<List<WatchProgressEntity>>

    @Query("SELECT * FROM watch_progress")
    suspend fun all(): List<WatchProgressEntity>

    /** Titles and episodes with progress or a watched mark, for Settings → Sync → Watch History. */
    @Query("SELECT COUNT(*) FROM watch_progress")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM watch_progress WHERE storageKey = :storageKey")
    suspend fun get(storageKey: String): WatchProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE storageKey = :storageKey")
    suspend fun delete(storageKey: String)

    /** This device's row for an episode, by the show, season, and episode numbers a sync record is keyed by (27.1). */
    @Query("SELECT * FROM watch_progress WHERE mediaType = 'episode' AND showTmdbId = :showTmdbId AND seasonNumber = :season AND episodeNumber = :episode LIMIT 1")
    suspend fun findEpisode(showTmdbId: Int, season: Int, episode: Int): WatchProgressEntity?

    /** The TMDB id of an episode the library knows, for projecting a synced episode record onto this device's rows (27.1). */
    @Query(
        "SELECT e.tmdbId FROM library_episode e JOIN library_show s ON e.showKey = s.`key` " +
            "WHERE s.tmdbId = :showTmdbId AND e.season = :season AND e.episode = :episode AND e.tmdbId IS NOT NULL LIMIT 1",
    )
    suspend fun libraryEpisodeId(showTmdbId: Int, season: Int, episode: Int): Int?
}

// Local library index. Selected documents keep working across restarts because
// import takes persistable URI permissions; these rows are the survivable index
// the session-only picker lacked. Enrichment fields (tmdbId, poster, runtime…)
// arrive later from background TMDB lookups and stay null until then.

@Entity(tableName = "library_folder")
data class LibraryFolderEntity(
    /** SAF tree URI string; also the identity of the source. */
    @PrimaryKey val treeUri: String,
    val displayName: String,
    val addedAtEpochMillis: Long,
    /** `MediaSourceKind.raw` (v3): `local`, `smb`, and Section H's providers. */
    val kind: String? = null,
    /** A readable location, such as `Google Drive › My Drive › Movies` (v3). */
    val displayPath: String? = null,
    /** The saved login or account the source reads with, when it has one (v3). */
    val accountKey: String? = null,
    /** Epoch milliseconds of the last successful scan (v3, D.3). */
    val lastScannedAt: Long? = null,
    /** Reserved for providers' change feeds (v3). */
    val changeCursor: String? = null,
    /** `SourceStatus.raw` after a failed scan, or null (v3, D.3). */
    val status: String? = null,
    /**
     * Where the source is, when its URL can't say (v5): a cloud account's
     * email (else its name) or an S3 endpoint URL. Null for local folders and
     * servers, whose host is in [treeUri]. Play From names copies by it.
     */
    val endpoint: String? = null,
    /**
     * Epoch milliseconds when a scan of this source began and hasn't
     * committed yet (v5). Still set after a restart, it means the process
     * died mid-scan, so the scan is done again.
     */
    val scanStartedAt: Long? = null,
)

@Entity(tableName = "library_movie")
data class LibraryMovieEntity(
    /** SAF document URI string. */
    @PrimaryKey val uri: String,
    /** Owning folder treeUri, or null for individually picked files. */
    val folderUri: String?,
    val fileName: String,
    val title: String,
    val year: Int?,
    val tmdbId: Int?,
    val posterPath: String?,
    val backdropPath: String?,
    val overview: String?,
    val runtimeMinutes: Int?,
    val addedAtEpochMillis: Long,
)

@Entity(tableName = "library_show")
data class LibraryShowEntity(
    /** Lowercased show name — episodes join on this before TMDB ids exist. */
    @PrimaryKey val key: String,
    val name: String,
    val tmdbId: Int?,
    val posterPath: String?,
    val backdropPath: String?,
    val overview: String?,
    val firstAirYear: Int?,
)

@Entity(tableName = "library_episode")
data class LibraryEpisodeEntity(
    @PrimaryKey val uri: String,
    val folderUri: String?,
    val showKey: String,
    val fileName: String,
    val season: Int,
    val episode: Int,
    val tmdbId: Int?,
    val title: String?,
    val stillPath: String?,
    val runtimeMinutes: Int?,
    val addedAtEpochMillis: Long,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library_folder ORDER BY addedAtEpochMillis")
    fun observeFolders(): Flow<List<LibraryFolderEntity>>

    @Query("SELECT * FROM library_movie ORDER BY title")
    fun observeMovies(): Flow<List<LibraryMovieEntity>>

    @Query("SELECT * FROM library_show ORDER BY name")
    fun observeShows(): Flow<List<LibraryShowEntity>>

    @Query("SELECT * FROM library_episode ORDER BY showKey, season, episode")
    fun observeEpisodes(): Flow<List<LibraryEpisodeEntity>>

    @Query("SELECT * FROM library_folder")
    suspend fun folders(): List<LibraryFolderEntity>

    /** One source's row as it is now, which a scan checks as it begins; null once removed. */
    @Query("SELECT * FROM library_folder WHERE treeUri = :treeUri")
    suspend fun folder(treeUri: String): LibraryFolderEntity?

    @Query("SELECT * FROM library_movie")
    suspend fun movies(): List<LibraryMovieEntity>

    @Query("SELECT * FROM library_show")
    suspend fun shows(): List<LibraryShowEntity>

    @Query("SELECT * FROM library_episode")
    suspend fun episodes(): List<LibraryEpisodeEntity>

    @Query("SELECT * FROM library_episode WHERE showKey = :showKey ORDER BY season, episode")
    suspend fun episodesForShow(showKey: String): List<LibraryEpisodeEntity>

    @Query("SELECT * FROM library_movie WHERE tmdbId = :tmdbId LIMIT 1")
    suspend fun movieByTmdbId(tmdbId: Int): LibraryMovieEntity?

    @Query("SELECT * FROM library_show WHERE tmdbId = :tmdbId LIMIT 1")
    suspend fun showByTmdbId(tmdbId: Int): LibraryShowEntity?

    @Query("SELECT * FROM library_episode WHERE tmdbId = :tmdbId LIMIT 1")
    suspend fun episodeByTmdbId(tmdbId: Int): LibraryEpisodeEntity?

    /** Every imported copy of a movie (D.5). */
    @Query("SELECT * FROM library_movie WHERE tmdbId = :tmdbId")
    suspend fun moviesByTmdbId(tmdbId: Int): List<LibraryMovieEntity>

    /** Every imported copy of an episode (D.5). */
    @Query("SELECT * FROM library_episode WHERE tmdbId = :tmdbId")
    suspend fun episodesByTmdbId(tmdbId: Int): List<LibraryEpisodeEntity>

    // The player's playlist panel resolves what it is playing by URI — the
    // exact string handed to PlayerActivity is these tables' primary key —
    // and lists neighbours from the same imported source.

    @Query("SELECT * FROM library_show WHERE key = :key LIMIT 1")
    suspend fun showByKey(key: String): LibraryShowEntity?

    @Query("SELECT * FROM library_movie WHERE uri = :uri LIMIT 1")
    suspend fun movieByUri(uri: String): LibraryMovieEntity?

    @Query("SELECT * FROM library_episode WHERE uri = :uri LIMIT 1")
    suspend fun episodeByUri(uri: String): LibraryEpisodeEntity?

    @Query("SELECT * FROM library_movie WHERE folderUri = :folderUri")
    suspend fun moviesInFolder(folderUri: String): List<LibraryMovieEntity>

    @Query("SELECT * FROM library_episode WHERE folderUri = :folderUri")
    suspend fun episodesInFolder(folderUri: String): List<LibraryEpisodeEntity>

    // What a scan plans against (SourceScanRules.plan): the URIs and show
    // keys alone, not whole rows.

    @Query("SELECT uri FROM library_movie WHERE folderUri = :treeUri")
    suspend fun movieUrisInFolder(treeUri: String): List<String>

    @Query("SELECT uri FROM library_episode WHERE folderUri = :treeUri")
    suspend fun episodeUrisInFolder(treeUri: String): List<String>

    /** Every movie and episode URI, whichever source holds it. */
    @Query("SELECT uri FROM library_movie UNION ALL SELECT uri FROM library_episode")
    suspend fun libraryUris(): List<String>

    @Query("SELECT `key` FROM library_show")
    suspend fun showKeys(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM library_folder WHERE treeUri = :treeUri)")
    suspend fun folderExists(treeUri: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: LibraryFolderEntity)

    /**
     * Records where a source is reached from (v5; a cloud account's email or
     * an S3 endpoint URL). An update rather than an upsert: a folder row read
     * earlier may be stale, and writing it back would undo a scan's status.
     */
    @Query("UPDATE library_folder SET endpoint = :endpoint WHERE treeUri = :treeUri")
    suspend fun setFolderEndpoint(treeUri: String, endpoint: String?)

    // A scan adds rows and never rewrites them: IGNORE keeps a row that is
    // already there, its TMDB match included, where REPLACE reset it.

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMovies(movies: List<LibraryMovieEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertShows(shows: List<LibraryShowEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEpisodes(episodes: List<LibraryEpisodeEntity>)

    // TMDB matches update the row in place: a write of the whole row would
    // bring back one a scan deleted while the match was being looked up.

    @Query(
        "UPDATE library_movie SET tmdbId = :tmdbId, posterPath = :posterPath, backdropPath = :backdropPath, " +
            "overview = :overview, runtimeMinutes = :runtimeMinutes, year = :year WHERE uri = :uri",
    )
    suspend fun updateMovieMatch(
        uri: String,
        tmdbId: Int,
        posterPath: String?,
        backdropPath: String?,
        overview: String?,
        runtimeMinutes: Int?,
        year: Int?,
    )

    @Query(
        "UPDATE library_show SET tmdbId = :tmdbId, posterPath = :posterPath, backdropPath = :backdropPath, " +
            "overview = :overview, firstAirYear = :firstAirYear WHERE `key` = :key",
    )
    suspend fun updateShowMatch(
        key: String,
        tmdbId: Int,
        posterPath: String?,
        backdropPath: String?,
        overview: String?,
        firstAirYear: Int?,
    )

    @Query(
        "UPDATE library_episode SET tmdbId = :tmdbId, title = :title, stillPath = :stillPath, " +
            "runtimeMinutes = :runtimeMinutes WHERE uri = :uri",
    )
    suspend fun updateEpisodeMatch(uri: String, tmdbId: Int, title: String?, stillPath: String?, runtimeMinutes: Int?)

    /**
     * Titles still waiting for a TMDB match: unmatched movies and shows, and
     * the unmatched episodes of matched shows (an unmatched show's episodes
     * wait for the show). The launch resume runs a pass while any are left.
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM library_movie WHERE tmdbId IS NULL) + " +
            "(SELECT COUNT(*) FROM library_show WHERE tmdbId IS NULL) + " +
            "(SELECT COUNT(*) FROM library_episode e JOIN library_show s ON e.showKey = s.`key` " +
            "WHERE e.tmdbId IS NULL AND s.tmdbId IS NOT NULL)",
    )
    suspend fun pendingEnrichmentCount(): Int

    @Query("DELETE FROM library_folder WHERE treeUri = :treeUri")
    suspend fun deleteFolder(treeUri: String)

    /** A scan of the source began (v5); the scan's commit or a recorded failure clears it. */
    @Query("UPDATE library_folder SET scanStartedAt = :at WHERE treeUri = :treeUri")
    suspend fun markScanStarted(treeUri: String, at: Long)

    /**
     * Sources whose scan began and never ended, because the process died
     * mid-scan; the next launch scans them again.
     */
    @Query("SELECT * FROM library_folder WHERE scanStartedAt IS NOT NULL ORDER BY addedAtEpochMillis")
    suspend fun interruptedFolders(): List<LibraryFolderEntity>

    /** A scan committed: clears a failure status and the scan marker, and records when (D.3). */
    @Query("UPDATE library_folder SET status = NULL, lastScannedAt = :at, scanStartedAt = NULL WHERE treeUri = :treeUri")
    suspend fun finishScan(treeUri: String, at: Long)

    /**
     * A scan failed: records why on the source, keeping its last good scan
     * time (D.3). A recorded failure also ends the scan, so a source that is
     * offline or signed out isn't scanned again at every launch.
     */
    @Query("UPDATE library_folder SET status = :status, scanStartedAt = NULL WHERE treeUri = :treeUri")
    suspend fun markFolderFailed(treeUri: String, status: String)

    @Query("DELETE FROM library_movie WHERE folderUri = :treeUri")
    suspend fun deleteMoviesInFolder(treeUri: String)

    @Query("DELETE FROM library_episode WHERE folderUri = :treeUri")
    suspend fun deleteEpisodesInFolder(treeUri: String)

    @Query("DELETE FROM library_movie WHERE uri = :uri")
    suspend fun deleteMovie(uri: String)

    @Query("DELETE FROM library_episode WHERE uri = :uri")
    suspend fun deleteEpisode(uri: String)

    @Query("DELETE FROM library_movie WHERE uri IN (:uris)")
    suspend fun deleteMovies(uris: List<String>)

    @Query("DELETE FROM library_episode WHERE uri IN (:uris)")
    suspend fun deleteEpisodes(uris: List<String>)

    @Query("DELETE FROM library_show WHERE key NOT IN (SELECT DISTINCT showKey FROM library_episode)")
    suspend fun pruneOrphanShows()

    /** Stores what [plan] adds, in one transaction; rows already there stay as they are. */
    @Transaction
    suspend fun insertPlanned(plan: ScanPlan) {
        insertShows(plan.newShows)
        insertMovies(plan.newMovies)
        insertEpisodes(plan.newEpisodes)
    }

    /**
     * Commits one scan of [treeUri] (D.3): stale rows go, new ones arrive,
     * and the source is marked scanned, all together or not at all. A
     * process that dies first leaves the source's `scanStartedAt` set, so
     * the scan is done again at the next launch. Stale rows go a batch at a
     * time: Android 8 to 11 bind at most 999 values per statement. Returns
     * false, writing nothing, when the source was removed while it was being
     * listed, so its rows can't outlive it.
     */
    @Transaction
    suspend fun applyScan(treeUri: String, plan: ScanPlan, scannedAt: Long): Boolean {
        if (!folderExists(treeUri)) return false
        SourceScanRules.statementBatches(plan.staleMovieUris).forEach { deleteMovies(it) }
        SourceScanRules.statementBatches(plan.staleEpisodeUris).forEach { deleteEpisodes(it) }
        insertPlanned(plan)
        // After the inserts: a show whose old episodes went stale keeps its
        // row, and its match, when its renamed files are among the new ones.
        pruneOrphanShows()
        finishScan(treeUri, scannedAt)
        return true
    }

    /** Removes a source and everything scanned from it in one transaction. */
    @Transaction
    suspend fun removeFolderTree(treeUri: String) {
        deleteMoviesInFolder(treeUri)
        deleteEpisodesInFolder(treeUri)
        deleteFolder(treeUri)
        pruneOrphanShows()
    }
}

@Database(
    entities = [
        UserMediaEntity::class,
        WatchProgressEntity::class,
        LibraryFolderEntity::class,
        LibraryMovieEntity::class,
        LibraryShowEntity::class,
        LibraryEpisodeEntity::class,
        SyncProgressEntity::class,
        SyncUserMediaEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class EdendaleDatabase : RoomDatabase() {
    abstract fun userMediaDao(): UserMediaDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun libraryDao(): LibraryDao
    abstract fun syncDao(): SyncDao

    companion object {
        /**
         * v5: each source's `endpoint` (a cloud account's email or an S3
         * endpoint, for Play From's labels) and `scanStartedAt` (an unfinished
         * scan, redone after a restart). Both start null; existing rows keep
         * every value.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `library_folder` ADD COLUMN `endpoint` TEXT")
                db.execSQL("ALTER TABLE `library_folder` ADD COLUMN `scanStartedAt` INTEGER")
            }
        }

        /**
         * v4 (27.1 sync): the portable record tables, and the title's duration
         * on `watch_progress`. Existing rows keep every value.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `watch_progress` ADD COLUMN `durationSeconds` REAL NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_watch_progress` (`key` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`tmdbId` INTEGER NOT NULL, `season` INTEGER, `episode` INTEGER, `position` REAL, `duration` REAL, " +
                        "`completed` INTEGER, `cleared` INTEGER NOT NULL, `device` TEXT NOT NULL, `updateTime` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`key`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_user_media` (`key` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`tmdbId` INTEGER NOT NULL, `favourite` INTEGER NOT NULL, `watchlist` INTEGER NOT NULL, `rating` REAL, " +
                        "`review` TEXT, `device` TEXT NOT NULL, `updateTime` INTEGER NOT NULL, PRIMARY KEY(`key`))",
                )
            }
        }

        /**
         * v3 gives each source its kind, a readable path, an account, and its
         * scan state (D.2). Existing rows keep every value; `kind` is filled
         * in from the URI's scheme by the same rule new rows use.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf(
                    "kind" to "TEXT",
                    "displayPath" to "TEXT",
                    "accountKey" to "TEXT",
                    "lastScannedAt" to "INTEGER",
                    "changeCursor" to "TEXT",
                    "status" to "TEXT",
                ).forEach { (column, type) ->
                    db.execSQL("ALTER TABLE `library_folder` ADD COLUMN `$column` $type")
                }
                val uris = buildList {
                    db.query("SELECT `treeUri` FROM `library_folder`").use { cursor ->
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                }
                uris.forEach { uri ->
                    val kind = MediaSourceKind.forSourceUri(uri) ?: return@forEach
                    db.execSQL(
                        "UPDATE `library_folder` SET `kind` = ? WHERE `treeUri` = ?",
                        arrayOf<Any>(kind.raw, uri),
                    )
                }
            }
        }

        /** v2 adds the local library index; user data tables are untouched. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_folder` (`treeUri` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, `addedAtEpochMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`treeUri`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_movie` (`uri` TEXT NOT NULL, " +
                        "`folderUri` TEXT, `fileName` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                        "`year` INTEGER, `tmdbId` INTEGER, `posterPath` TEXT, `backdropPath` TEXT, " +
                        "`overview` TEXT, `runtimeMinutes` INTEGER, " +
                        "`addedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`uri`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_show` (`key` TEXT NOT NULL, " +
                        "`name` TEXT NOT NULL, `tmdbId` INTEGER, `posterPath` TEXT, " +
                        "`backdropPath` TEXT, `overview` TEXT, `firstAirYear` INTEGER, " +
                        "PRIMARY KEY(`key`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_episode` (`uri` TEXT NOT NULL, " +
                        "`folderUri` TEXT, `showKey` TEXT NOT NULL, `fileName` TEXT NOT NULL, " +
                        "`season` INTEGER NOT NULL, `episode` INTEGER NOT NULL, `tmdbId` INTEGER, " +
                        "`title` TEXT, `stillPath` TEXT, `runtimeMinutes` INTEGER, " +
                        "`addedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`uri`))",
                )
            }
        }
    }
}
