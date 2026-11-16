package com.babasama.edendale.android.sync

import com.babasama.edendale.android.data.EdendaleDatabase
import com.babasama.edendale.android.data.SyncProgressEntity
import com.babasama.edendale.android.data.SyncUserMediaEntity
import com.babasama.edendale.android.data.UserMediaEntity
import com.babasama.edendale.android.data.WatchProgressEntity
import com.babasama.edendale.domain.MediaType
import com.babasama.edendale.domain.UserMediaRecord
import com.babasama.edendale.domain.WatchMediaType
import com.babasama.edendale.domain.WatchProgress
import com.babasama.edendale.sync.DeviceFileCodec
import com.babasama.edendale.sync.ProgressRecord
import com.babasama.edendale.sync.SyncLedger
import com.babasama.edendale.sync.SyncLocalStore
import com.babasama.edendale.sync.SyncLog
import com.babasama.edendale.sync.SyncMediaType
import com.babasama.edendale.sync.UserMediaSyncRecord
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Conversions between the portable records and this device's rows. Pure. */
internal object SyncRows {
    fun toRecord(entity: SyncProgressEntity) = ProgressRecord(
        type = SyncMediaType.fromRaw(entity.type) ?: SyncMediaType.MOVIE,
        tmdbId = entity.tmdbId,
        season = entity.season,
        episode = entity.episode,
        position = entity.position,
        duration = entity.duration,
        completed = entity.completed,
        cleared = entity.cleared,
        device = entity.device,
        updateTime = entity.updateTime,
    )

    fun toEntity(record: ProgressRecord) = SyncProgressEntity(
        record.key, record.type.raw, record.tmdbId, record.season, record.episode,
        record.position, record.duration, record.completed, record.cleared, record.device, record.updateTime,
    )

    fun toRecord(entity: SyncUserMediaEntity) = UserMediaSyncRecord(
        type = SyncMediaType.fromRaw(entity.type) ?: SyncMediaType.MOVIE,
        tmdbId = entity.tmdbId,
        favourite = entity.favourite,
        watchlist = entity.watchlist,
        rating = entity.rating,
        review = entity.review,
        device = entity.device,
        updateTime = entity.updateTime,
    )

    fun toEntity(record: UserMediaSyncRecord) = SyncUserMediaEntity(
        record.key, record.type.raw, record.tmdbId, record.favourite, record.watchlist, record.rating, record.review, record.device, record.updateTime,
    )

    /**
     * This device's progress row as a portable record: seconds, not a
     * fraction. A row without a duration (Mark Watched, or one written before
     * v4) gets its duration from the fraction when it can, else 1 second with
     * the position at the end, which other devices read as completed.
     */
    fun toRecord(progress: WatchProgress, device: String, updateTime: Long): ProgressRecord? {
        val type: SyncMediaType
        val season: Int?
        val episode: Int?
        val tmdbId: Int
        when (progress.mediaType) {
            WatchMediaType.MOVIE -> {
                type = SyncMediaType.MOVIE
                tmdbId = progress.tmdbId
                season = null
                episode = null
            }
            WatchMediaType.EPISODE -> {
                type = SyncMediaType.TV
                tmdbId = progress.showTmdbId ?: return null
                season = progress.seasonNumber ?: return null
                episode = progress.episodeNumber ?: return null
            }
        }
        val fraction = progress.normalizedPosition
        val duration = when {
            progress.durationSeconds > 0 -> progress.durationSeconds
            fraction > 0 && progress.watchedSeconds > 0 -> progress.watchedSeconds / fraction
            else -> 1.0
        }
        val position = when {
            progress.durationSeconds > 0 || progress.watchedSeconds > 0 -> progress.watchedSeconds.coerceIn(0.0, duration)
            else -> fraction * duration
        }
        return ProgressRecord(type, tmdbId, season, episode, position, duration, progress.isCompleted, false, device, updateTime)
    }

    /**
     * The sync identities of this device's progress rows, as records stamped
     * 0, for a clear to cover. An episode row without its show, season, or
     * episode number never had a key, so it never synced and drops out.
     */
    fun identities(rows: List<WatchProgress>, device: String): List<ProgressRecord> = rows.mapNotNull { toRecord(it, device, 0L) }

    /** A received record as this device's row, given the row key and TMDB id the projection resolved. */
    fun toProgress(record: ProgressRecord, tmdbId: Int, mediaType: WatchMediaType): WatchProgress {
        val duration = record.duration ?: 0.0
        val position = record.position ?: 0.0
        return WatchProgress(
            tmdbId = tmdbId,
            mediaType = mediaType,
            position = if (record.completed == true) 1.0 else if (duration > 0) (position / duration).coerceIn(0.0, 1.0) else 0.0,
            watchedSeconds = position,
            lastWatchedEpochMillis = record.updateTime,
            isCompleted = record.completed == true,
            showTmdbId = if (record.type == SyncMediaType.TV) record.tmdbId else null,
            seasonNumber = record.season,
            episodeNumber = record.episode,
            durationSeconds = duration,
        )
    }

    fun toRecord(record: UserMediaRecord, device: String, updateTime: Long, review: String? = null) = UserMediaSyncRecord(
        type = if (record.mediaType == MediaType.TV) SyncMediaType.TV else SyncMediaType.MOVIE,
        tmdbId = record.tmdbId,
        favourite = record.favourite,
        watchlist = record.watchlist,
        rating = record.rating?.takeIf { DeviceFileCodec.isValidRating(it) },
        review = review,
        device = device,
        updateTime = updateTime,
    )

    /** A record's user-media key for a row (`movie:<id>` or `tv:<id>`), which the app's `storageKey` already spells. */
    fun userMediaKey(record: UserMediaRecord): String = record.storageKey
}

/**
 * The device's ledger in Room plus [SyncSettingsStore] (SYNC-PROTOCOL.md
 * §7, §8). A merge keeps the received records verbatim in the sync tables
 * and projects the changed keys onto `user_media` and `watch_progress`, where
 * this device has a row to update or the library knows the episode; an
 * episode nobody here knows stays in the sync table only and still travels on
 * to other devices. Local writes come in through [SyncRecorder].
 */
class RoomSyncLocalStore(private val database: EdendaleDatabase, private val settings: SyncSettingsStore) : SyncLocalStore {
    private val sync get() = database.syncDao()
    private val mutex = Mutex()

    override suspend fun ledger(): SyncLedger = mutex.withLock {
        SyncLedger(
            progress = sync.allProgress().associate { it.key to SyncRows.toRecord(it) },
            userMedia = sync.allUserMedia().associate { it.key to SyncRows.toRecord(it) },
            highWater = settings.highWater,
        )
    }

    override suspend fun applyMerge(merge: SyncLedger.Merge) = mutex.withLock {
        val progress = merge.changedProgress.mapNotNull { merge.ledger.progress[it] }
        val media = merge.changedUserMedia.mapNotNull { merge.ledger.userMedia[it] }
        sync.upsertProgress(progress.map(SyncRows::toEntity))
        sync.upsertUserMedia(media.map(SyncRows::toEntity))
        settings.raiseHighWater(merge.ledger.highWater)
        for (record in progress) project(record)
        for (record in media) project(record)
        if (merge.changed) settings.dirty = true
    }

    /** A received progress record onto this device's `watch_progress`, where it has a row to update or the library knows the episode. */
    private suspend fun project(record: ProgressRecord) {
        val dao = database.watchProgressDao()
        when (record.type) {
            SyncMediaType.MOVIE -> {
                val key = "movie:${record.tmdbId}"
                if (record.cleared) dao.delete(key) else dao.upsert(WatchProgressEntity.fromDomain(SyncRows.toProgress(record, record.tmdbId, WatchMediaType.MOVIE)))
            }
            SyncMediaType.TV -> {
                val season = record.season ?: return
                val episode = record.episode ?: return
                val existing = dao.findEpisode(record.tmdbId, season, episode)
                val episodeId = existing?.tmdbId ?: dao.libraryEpisodeId(record.tmdbId, season, episode) ?: return
                if (record.cleared) {
                    existing?.let { dao.delete(it.storageKey) }
                } else {
                    dao.upsert(WatchProgressEntity.fromDomain(SyncRows.toProgress(record, episodeId, WatchMediaType.EPISODE)))
                }
            }
        }
    }

    /**
     * A received user-media record onto `user_media`: the three values this
     * platform shows, with a field marked dirty when its value changed so the
     * optional TMDB account sync pushes it too (FEATURES-27.1.md §5.5).
     */
    private suspend fun project(record: UserMediaSyncRecord) {
        val dao = database.userMediaDao()
        val mediaType = if (record.type == SyncMediaType.TV) MediaType.TV else MediaType.MOVIE
        val current = dao.get(record.tmdbId, mediaType.pathSegment)?.toDomain() ?: UserMediaRecord(record.tmdbId, mediaType)
        var updated = current
        if (current.favourite != record.favourite) updated = updated.copy(favourite = record.favourite, favouriteUpdatedAt = record.updateTime, favouriteDirty = true)
        if (current.watchlist != record.watchlist) updated = updated.copy(watchlist = record.watchlist, watchlistUpdatedAt = record.updateTime, watchlistDirty = true)
        if (current.rating != record.rating) updated = updated.copy(rating = record.rating, ratingUpdatedAt = record.updateTime, ratingDirty = true)
        if (updated == current && current.hasState) return
        if (!updated.hasState) dao.delete(record.tmdbId, mediaType.pathSegment) else dao.upsert(UserMediaEntity.fromDomain(updated))
    }

    override suspend fun stamps(): Map<String, String> = settings.stamps

    override suspend fun saveStamps(stamps: Map<String, String>) {
        settings.stamps = stamps
    }

    override suspend fun isDirty(): Boolean = settings.dirty

    override suspend fun markWritten() {
        settings.dirty = false
    }

    // MARK: - Local writes (SyncRecorder)

    /** Records this device's own write of [record], stamped (§7.2), and marks the ledger dirty. */
    suspend fun recordLocal(record: ProgressRecord) = mutex.withLock {
        sync.upsertProgress(listOf(SyncRows.toEntity(record)))
        settings.dirty = true
    }

    suspend fun recordLocal(record: UserMediaSyncRecord) = mutex.withLock {
        sync.upsertUserMedia(listOf(SyncRows.toEntity(record)))
        settings.dirty = true
    }

    suspend fun progressRecord(key: String): ProgressRecord? = sync.progress(key)?.let(SyncRows::toRecord)

    suspend fun userMediaRecord(key: String): UserMediaSyncRecord? = sync.userMedia(key)?.let(SyncRows::toRecord)

    /**
     * Brings this device's existing rows into the ledger when sync is first
     * turned on: each row stamped with its own last-write time when that is
     * a valid stamp, else with the current clock, and this device as writer.
     * Rows already in the ledger are left alone.
     */
    suspend fun importExisting(deviceId: String, now: Long) = mutex.withLock {
        val knownProgress = sync.allProgress().map { it.key }.toSet()
        val knownMedia = sync.allUserMedia().map { it.key }.toSet()
        val progress = database.watchProgressDao().all().map { it.toDomain() }.mapNotNull { row ->
            val stamp = row.lastWatchedEpochMillis.takeIf(DeviceFileCodec::isValidStamp) ?: now
            SyncRows.toRecord(row, deviceId, stamp)
        }.filter { it.key !in knownProgress }
        val media = database.userMediaDao().all().map { it.toDomain() }.filter { it.hasState }.map { row ->
            val stamp = maxOf(row.favouriteUpdatedAt, row.watchlistUpdatedAt, row.ratingUpdatedAt).takeIf(DeviceFileCodec::isValidStamp) ?: now
            SyncRows.toRecord(row, deviceId, stamp)
        }.filter { it.key !in knownMedia }
        sync.upsertProgress(progress.map(SyncRows::toEntity))
        sync.upsertUserMedia(media.map(SyncRows::toEntity))
        (progress.map { it.updateTime } + media.map { it.updateTime }).maxOrNull()?.let(settings::raiseHighWater)
        if (progress.isNotEmpty() || media.isNotEmpty()) settings.dirty = true
    }

    // MARK: - Delete Watch History

    /**
     * Sync on: every progress key in the ledger or on this device becomes a
     * tombstone by [deviceId] at [stamp] ([SyncLedger.clearingAllProgress]),
     * and this device's own rows go, in one transaction. The store is marked
     * dirty first, so a crash between the two can only cause one extra write,
     * never tombstones that are never written. Returns how many keys were
     * cleared. Logs counts only.
     */
    suspend fun clearAllProgress(deviceId: String, stamp: Long): Int = mutex.withLock {
        val ledger = SyncLedger(progress = sync.allProgress().associate { it.key to SyncRows.toRecord(it) }, highWater = settings.highWater)
        val rows = database.watchProgressDao().all().map { it.toDomain() }
        val (cleared, keys) = ledger.clearingAllProgress(stamp, deviceId, SyncRows.identities(rows, deviceId))
        if (keys.isNotEmpty()) settings.dirty = true
        sync.replaceProgress(keys.map { SyncRows.toEntity(cleared.progress.getValue(it)) })
        settings.raiseHighWater(cleared.highWater)
        SyncLog.log(SyncCoordinator.TAG, "watch history cleared: ${keys.size} key(s) tombstoned, ${rows.size} row(s) deleted here")
        keys.size
    }

    /**
     * Sync off: this device's rows and every progress record in the ledger
     * go, tombstones included, and none are written, so nothing reaches
     * another device; turning sync on later brings back what they hold.
     */
    suspend fun forgetAllProgress() = mutex.withLock {
        sync.forgetProgress()
        SyncLog.log(SyncCoordinator.TAG, "watch history forgotten on this device")
    }
}

/**
 * Where the app's own writes become portable records (FEATURES-27.1.md §5.3
 * "every client"): [com.babasama.edendale.android.data.LocalDataStore] and
 * the library repository call these after each write, the TMDB account sync
 * after each round. Each record is stamped `max(now, highWater + 1)` with
 * this device's ID, and the coordinator is told so it can write after the
 * 10-second debounce (§8.1).
 */
class SyncRecorder(
    private val store: RoomSyncLocalStore,
    private val settings: SyncSettingsStore,
    private val onLocalChange: () -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val deviceId: String get() = settings.deviceId

    suspend fun progressWritten(progress: WatchProgress) {
        val record = SyncRows.toRecord(progress, deviceId, settings.nextStamp(clock())) ?: return
        store.recordLocal(record)
        onLocalChange()
    }

    /** A cleared progress row becomes a tombstone (§5.3 "clearing is a write"), when its identity is known. */
    suspend fun progressCleared(previous: WatchProgress?) {
        val identity = previous?.let { SyncRows.toRecord(it, deviceId, 0L) } ?: return
        store.recordLocal(ProgressRecord.tombstone(identity.type, identity.tmdbId, identity.season, identity.episode, deviceId, settings.nextStamp(clock())))
        onLocalChange()
    }

    suspend fun userMediaChanged(record: UserMediaRecord) {
        // Reviews aren't written on Android: a received review travels on untouched unless the row's own values change.
        val previous = store.userMediaRecord(SyncRows.userMediaKey(record))
        val next = SyncRows.toRecord(record, deviceId, settings.nextStamp(clock()), review = previous?.review)
        if (previous != null && previous.favourite == next.favourite && previous.watchlist == next.watchlist && previous.rating == next.rating) return
        store.recordLocal(next)
        onLocalChange()
    }

    /** After a TMDB account round: every record whose values changed, or that the round pruned, is a local write stamped now (§5.5). */
    suspend fun tmdbRoundApplied(before: List<UserMediaRecord>, after: List<UserMediaRecord>) {
        val previous = before.associateBy { it.storageKey }
        val next = after.associateBy { it.storageKey }
        (previous.keys + next.keys).forEach { key ->
            val old = previous[key]
            val new = next[key]
            val changed = old == null || new == null || old.favourite != new.favourite || old.watchlist != new.watchlist || old.rating != new.rating
            if (!changed) return@forEach
            userMediaChanged(new ?: (old ?: return@forEach).copy(favourite = false, watchlist = false, rating = null))
        }
    }

    /** Brings existing rows into the ledger (first enable). */
    suspend fun importExisting() = store.importExisting(deviceId, clock())
}
