package com.babasama.edendale.android.data

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
        )
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

    @Query("SELECT * FROM watch_progress WHERE storageKey = :storageKey")
    suspend fun get(storageKey: String): WatchProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchProgressEntity)

    @Query("DELETE FROM watch_progress WHERE storageKey = :storageKey")
    suspend fun delete(storageKey: String)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFolder(folder: LibraryFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMovie(movie: LibraryMovieEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShow(show: LibraryShowEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEpisode(episode: LibraryEpisodeEntity)

    @Query("DELETE FROM library_folder WHERE treeUri = :treeUri")
    suspend fun deleteFolder(treeUri: String)

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
    ],
    version = 2,
    exportSchema = false,
)
abstract class EdendaleDatabase : RoomDatabase() {
    abstract fun userMediaDao(): UserMediaDao
    abstract fun watchProgressDao(): WatchProgressDao
    abstract fun libraryDao(): LibraryDao

    companion object {
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
