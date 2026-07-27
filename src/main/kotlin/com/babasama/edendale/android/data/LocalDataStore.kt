package com.babasama.edendale.android.data

import com.babasama.edendale.AndroidEdendaleCore
import com.babasama.edendale.domain.MediaRef
import com.babasama.edendale.domain.UserMediaRecord
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.tmdb.TmdbSession
import com.babasama.edendale.tmdb.UserMediaSyncResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map

class LocalDataStore(private val database: EdendaleDatabase) {

    /**
     * Fires once per local user-media write (favourite / watchlist / rating).
     * A later agent debounces this to trigger a TMDB account sync; emitting from
     * the store means player-driven and Mark-Watched writes are covered too.
     */
    val edits: SharedFlow<Unit> = sharedEdits.asSharedFlow()

    // MARK: - User Media (Favourites, Watchlist, Ratings)

    fun observeUserMedia(): Flow<List<UserMediaRecord>> {
        return database.userMediaDao().observeAll().map { list -> list.map { it.toDomain() } }
    }

    suspend fun getUserMedia(ref: MediaRef): UserMediaRecord? {
        return database.userMediaDao().get(ref.id, ref.mediaType.pathSegment)?.toDomain()
    }

    suspend fun setFavourite(
        ref: MediaRef,
        isFavourite: Boolean,
        title: String? = null,
        posterPath: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val current = getUserMedia(ref) ?: UserMediaRecord(ref.id, ref.mediaType)
        val updated = current.settingFavourite(isFavourite, now).withDisplay(title, posterPath)
        database.userMediaDao().upsert(UserMediaEntity.fromDomain(updated))
        sharedEdits.tryEmit(Unit)
    }

    suspend fun setWatchlist(
        ref: MediaRef,
        inWatchlist: Boolean,
        title: String? = null,
        posterPath: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val current = getUserMedia(ref) ?: UserMediaRecord(ref.id, ref.mediaType)
        val updated = current.settingWatchlist(inWatchlist, now).withDisplay(title, posterPath)
        database.userMediaDao().upsert(UserMediaEntity.fromDomain(updated))
        sharedEdits.tryEmit(Unit)
    }

    suspend fun setRating(
        ref: MediaRef,
        rating: Double?,
        title: String? = null,
        posterPath: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val current = getUserMedia(ref) ?: UserMediaRecord(ref.id, ref.mediaType)
        val updated = current.settingRating(rating, now).withDisplay(title, posterPath)
        database.userMediaDao().upsert(UserMediaEntity.fromDomain(updated))
        sharedEdits.tryEmit(Unit)
    }

    suspend fun syncWithTmdb(session: TmdbSession): UserMediaSyncResult {
        val localRecords = database.userMediaDao().all().map { it.toDomain() }
        val result = AndroidEdendaleCore.syncEngine().sync(session, localRecords, System.currentTimeMillis())
        database.userMediaDao().replaceAll(result.records.map { UserMediaEntity.fromDomain(it) })
        return result
    }

    // MARK: - Watch Progress

    fun observeWatchProgress(): Flow<List<WatchProgress>> {
        return database.watchProgressDao().observeAll().map { list -> list.map { it.toDomain() } }
    }

    suspend fun getWatchProgress(storageKey: String): WatchProgress? {
        return database.watchProgressDao().get(storageKey)?.toDomain()
    }

    suspend fun getAllWatchProgress(): List<WatchProgress> {
        return database.watchProgressDao().all().map { it.toDomain() }
    }

    suspend fun updateWatchProgress(progress: WatchProgress) {
        database.watchProgressDao().upsert(WatchProgressEntity.fromDomain(progress))
    }

    suspend fun deleteWatchProgress(storageKey: String) {
        database.watchProgressDao().delete(storageKey)
    }

    private companion object {
        /** All LocalDataStore instances publish into one app-wide edit stream. */
        val sharedEdits = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }
}
