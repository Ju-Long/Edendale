package com.babasama.edendale.android.sync

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.babasama.edendale.android.BuildConfig
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.android.isTelevisionDevice
import com.babasama.edendale.sync.SyncBackoff
import com.babasama.edendale.sync.SyncDeviceInfo
import com.babasama.edendale.sync.SyncEngine
import com.babasama.edendale.sync.SyncForegroundTracker
import com.babasama.edendale.sync.SyncLocation
import com.babasama.edendale.sync.SyncLog
import com.babasama.edendale.sync.SyncReport
import com.babasama.edendale.sync.SyncTriggers
import com.babasama.edendale.sync.SyncWarning
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What Settings → Sync shows (FEATURES-27.1.md §5.1): the location, the device, and the last sync. */
data class SyncStatus(
    val location: SyncLocation? = null,
    val deviceId: String = "",
    val deviceName: String = "",
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    /** The last failure, as a throwable to turn into the viewer's message; null after a success. */
    val lastError: Throwable? = null,
    val warnings: List<SyncWarning> = emptyList(),
    val problem: SyncLocationProblem? = null,
    /** The location a new one replaced, while this device's file may still be there (§5.1). */
    val previousLocation: SyncLocation? = null,
) {
    val isOn: Boolean get() = location != null
}

/** Where Settings → Sync → Delete Watch History stands; it outlives the page, so leaving Settings stops nothing. */
sealed interface HistoryDeletion {
    data object Idle : HistoryDeletion

    data object Running : HistoryDeletion

    /**
     * Deleted here. With [othersFollow] (sync on), the tombstones are in this
     * device's file at the location, but a sync only ever writes its own
     * file (§8.2): every other device's file there keeps its copy until that
     * device next syncs, and one that no longer syncs keeps it until Remove a
     * Device deletes its file. So the line claims no more than this device.
     */
    data class Done(val othersFollow: Boolean) : HistoryDeletion

    /** Deleted here; the location couldn't be reached, so the tombstones wait for the next sync that can write them. */
    data class Pending(val location: SyncLocation) : HistoryDeletion

    /** The database refused the change, so nothing was deleted. */
    data object Failed : HistoryDeletion
}

/**
 * Runs sync on this device (SYNC-PROTOCOL.md §8.1; FEATURES-27.1.md §5.4,
 * §8): on launch, on return to the foreground, 10 seconds after the last
 * local change (at once when playback stops or the app goes to the
 * background, with a one-off WorkManager job behind it while a write is
 * pending), every 5 minutes in the foreground, and in the background
 * through a periodic WorkManager job with a network constraint. One sync at
 * a time; failures back off 1, 2, 4, 8 minutes up to an hour, except that
 * launch and return to the foreground sync at once, as Sync Now does
 * ([SyncTriggers]). Lives for the process ([EdendaleApplication.sync]).
 */
class SyncCoordinator(private val app: EdendaleApplication) {
    val settings = SyncSettingsStore(app)
    private val store = RoomSyncLocalStore(app.database, settings)
    private val factory = SyncStorageFactory(app)
    val recorder = SyncRecorder(store, settings, onLocalChange = ::onLocalChange)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()
    private val _historyDeletion = MutableStateFlow<HistoryDeletion>(HistoryDeletion.Idle)
    val historyDeletion: StateFlow<HistoryDeletion> = _historyDeletion.asStateFlow()

    private var debounce: Job? = null
    private var periodic: Job? = null
    private val foreground = SyncForegroundTracker()
    private var consecutiveFailures = 0
    private var nextAllowedAt = 0L

    /** The platform name this device writes into its file and the handoff (§3). */
    val platform: String get() = if (app.isTelevisionDevice()) "androidtv" else "android"

    fun deviceName(): String = settings.deviceName
        ?: runCatching { Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME) }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: Build.MODEL

    private fun deviceInfo() = SyncDeviceInfo(settings.deviceId, deviceName(), platform, BuildConfig.VERSION_NAME)

    fun start() {
        refreshStatus()
        // The launch sync runs as the first activity starts, not here: a process
        // WorkManager starts for the background job syncs through the job alone.
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                foreground.activityStarted()?.let(::onMoment)
            }
            override fun onActivityStopped(activity: Activity) {
                foreground.activityStopped(activity.isChangingConfigurations)?.let(::onMoment)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        if (settings.location != null) scheduleBackgroundWork()
    }

    private fun onMoment(moment: SyncForegroundTracker.Moment) = when (moment) {
        SyncForegroundTracker.Moment.LAUNCH -> onForeground(reason = "launch")
        SyncForegroundTracker.Moment.FOREGROUND -> onForeground(reason = "foreground")
        SyncForegroundTracker.Moment.BACKGROUND -> onBackground()
    }

    /**
     * The app opened, cold or from the background: sync at once, past any
     * backoff, so the watch progress the viewer sees (the hero's Resume,
     * Continue Watching) is what the other devices last wrote.
     */
    private fun onForeground(reason: String) {
        if (settings.location == null) return
        requestSync(reason = reason, ignoreBackoff = true)
        periodic?.cancel()
        periodic = scope.launch {
            while (true) {
                delay(SyncTriggers.PERIODIC_MILLIS)
                requestSync(reason = "periodic")
            }
        }
    }

    private fun onBackground() {
        periodic?.cancel()
        periodic = null
        debounce?.cancel()
        if (settings.location != null) requestSync(reason = "background")
        if (SyncTriggers.flushJobNeeded(settings.location != null, settings.dirty)) enqueueFlushWork()
    }

    /** A local write: write the file 10 seconds after the last one (§8.1). */
    private fun onLocalChange() {
        if (settings.location == null) return
        debounce?.cancel()
        debounce = scope.launch {
            delay(SyncTriggers.DEBOUNCE_MILLIS)
            requestSync(reason = "change")
        }
    }

    /** Playback stopped: write pending progress at once (§8.1). */
    fun flushSoon() {
        if (settings.location == null) return
        debounce?.cancel()
        requestSync(reason = "playback stopped")
    }

    /** Sync Now in Settings: ignores the backoff. */
    fun syncNow() {
        nextAllowedAt = 0L
        requestSync(reason = "manual")
    }

    private fun requestSync(reason: String, ignoreBackoff: Boolean = false) {
        scope.launch { runSync(reason, ignoreBackoff) }
    }

    /**
     * One sync, if a location is set and the backoff allows; returns the
     * report, or null when nothing ran. [readEveryFile] reads unchanged files
     * too ([SyncEngine.sync]).
     */
    suspend fun runSync(reason: String, ignoreBackoff: Boolean = false, readEveryFile: Boolean = false): SyncReport? {
        val location = settings.location ?: return null
        if (!ignoreBackoff && System.currentTimeMillis() < nextAllowedAt) {
            SyncLog.log(TAG, "$reason: waiting out the backoff")
            return null
        }
        return syncMutex.withLock {
            _status.value = _status.value.copy(syncing = true)
            try {
                val storage = factory.open(location)
                val engine = SyncEngine(storage, store, deviceInfo(), location.space)
                SyncLog.log(TAG, "sync ($reason) starting")
                val report = engine.sync(readEveryFile)
                // A Google Drive location learns its space folder's ID for handoffs across ecosystems (§6.4).
                if (storage is com.babasama.edendale.sync.GoogleDriveSyncStorage && location.googleFolderId == null) {
                    storage.spaceFolderId(location.space)?.let { settings.location = location.copy(googleFolderId = it) }
                }
                consecutiveFailures = 0
                nextAllowedAt = 0L
                settings.lastSyncAt = System.currentTimeMillis()
                settings.lastError = null
                settings.warnings = report.warnings
                _status.value = _status.value.copy(syncing = false, lastError = null, warnings = report.warnings, lastSyncAt = settings.lastSyncAt, problem = null)
                // A successful sync writes whatever is dirty, so a waiting history deletion has now reached the location.
                if (_historyDeletion.value is HistoryDeletion.Pending) _historyDeletion.value = HistoryDeletion.Done(othersFollow = true)
                report
            } catch (cancelled: CancellationException) {
                _status.value = _status.value.copy(syncing = false)
                throw cancelled
            } catch (error: Exception) {
                consecutiveFailures += 1
                nextAllowedAt = System.currentTimeMillis() + SyncBackoff.delayMillis(consecutiveFailures)
                settings.lastError = SyncLog.describe(error)
                SyncLog.log(TAG, "sync ($reason) failed: ${SyncLog.describe(error)}; failures=$consecutiveFailures")
                _status.value = _status.value.copy(syncing = false, lastError = error, problem = (error as? SyncLocationException)?.problem)
                null
            }
        }
    }

    /**
     * Turns sync on with [location] (§5.1): this device's existing rows join
     * the ledger, the device ID is made if needed, and a first sync runs.
     * A different location it replaces is remembered, so Settings can offer
     * to delete this device's file there ([deletePreviousFile]).
     */
    suspend fun enable(location: SyncLocation) = withContext(Dispatchers.IO) {
        val previous = SyncLocation.previousAfter(settings.location, location, settings.previousLocation)
        if (previous != settings.previousLocation) settings.previousLocation = previous
        settings.location = location
        recorder.importExisting()
        refreshStatus()
        scheduleBackgroundWork()
        runSync(reason = "enable", ignoreBackoff = true)
    }

    /**
     * Turns sync off, or switches away from a location (§4 "One location per
     * device"): the local store stays; with [deleteOwnFile] this device's
     * file is removed from the old location first.
     */
    suspend fun disable(deleteOwnFile: Boolean) = withContext(Dispatchers.IO) {
        val location = settings.location
        if (deleteOwnFile && location != null) {
            runCatching { SyncEngine(factory.open(location), store, deviceInfo(), location.space).removeOwnFile() }
                .onFailure { SyncLog.log(TAG, "deleting own file failed: ${SyncLog.describe(it)}") }
        }
        periodic?.cancel()
        debounce?.cancel()
        settings.clearLocation()
        consecutiveFailures = 0
        nextAllowedAt = 0L
        // The tombstones stay in the ledger for whichever location comes next; the old one is no longer waited on.
        if (_historyDeletion.value is HistoryDeletion.Pending) _historyDeletion.value = HistoryDeletion.Idle
        WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(app).cancelUniqueWork(FLUSH_WORK_NAME)
        refreshStatus()
    }

    /**
     * Deletes this device's file from the location [enable] replaced (§5.1;
     * SYNC-PROTOCOL.md §4), then forgets that location. When the old
     * location can't be reached the failure comes back and the location
     * stays remembered for another try.
     */
    suspend fun deletePreviousFile(): Result<Unit> = withContext(Dispatchers.IO) {
        val previous = settings.previousLocation ?: return@withContext Result.success(Unit)
        val result = runCatching { SyncEngine(factory.open(previous), store, deviceInfo(), previous.space).removeOwnFile() }
        result.onFailure { SyncLog.log(TAG, "deleting own file in the old location failed: ${SyncLog.describe(it)}") }
        if (result.isSuccess) settings.previousLocation = null
        refreshStatus()
        result
    }

    /** Keep It: this device's file stays in the old location, and Settings stops asking. */
    fun keepPreviousFile() {
        settings.previousLocation = null
        refreshStatus()
    }

    /**
     * Settings → Sync → Delete Watch History: every progress record and
     * watched mark goes; favourites, the watchlist, ratings, and reviews
     * stay. With sync off, only this device's tables are emptied and no
     * tombstone is written, so turning sync on later brings back what the
     * other devices hold. With sync on, clearing is a write (SYNC-PROTOCOL.md
     * §6.1, §7): see [clearHistoryEverywhere]. Runs in the coordinator's
     * own scope, not the page's, so closing Settings doesn't cut it short.
     */
    fun deleteWatchHistory() {
        val current = _historyDeletion.value
        if (current == HistoryDeletion.Running || !_historyDeletion.compareAndSet(current, HistoryDeletion.Running)) return
        scope.launch {
            _historyDeletion.value = try {
                val location = settings.location
                if (location == null) {
                    syncMutex.withLock { store.forgetAllProgress() }
                    HistoryDeletion.Done(othersFollow = false)
                } else {
                    clearHistoryEverywhere(location)
                }
            } catch (cancelled: CancellationException) {
                _historyDeletion.value = HistoryDeletion.Idle
                throw cancelled
            } catch (error: Exception) {
                SyncLog.log(TAG, "deleting watch history failed: ${SyncLog.describe(error)}")
                HistoryDeletion.Failed
            }
        }
    }

    /**
     * Every file is read again in full, not only the changed ones
     * ([SyncEngine.sync] `readEveryFile`), so the ledger holds every key
     * another device wrote and the high-water mark passes every stamp; then
     * each key gets one tombstone stamped as a local write and the file is
     * written at once. Offline, the read fails and the clear covers the keys
     * already known; the store stays dirty, so the next sync that reaches
     * [location] writes the tombstones. [runSync] takes the sync lock itself,
     * so it is never called while holding it.
     */
    private suspend fun clearHistoryEverywhere(location: SyncLocation): HistoryDeletion {
        runSync(reason = "watch history read", ignoreBackoff = true, readEveryFile = true)
        val cleared = syncMutex.withLock { store.clearAllProgress(settings.deviceId, settings.nextStamp(System.currentTimeMillis())) }
        debounce?.cancel()
        val report = runSync(reason = "watch history write", ignoreBackoff = true)
        val stillOn = settings.location != null
        // Nothing cleared (rows that never synced) means nothing for the other devices to follow.
        return if (report != null || cleared == 0 || !stillOn) HistoryDeletion.Done(othersFollow = stillOn && cleared > 0) else HistoryDeletion.Pending(location)
    }

    /** The page was left: a finished deletion's line has been seen. A running or waiting one keeps its state. */
    fun acknowledgeHistoryDeletion() {
        val current = _historyDeletion.value
        if (current is HistoryDeletion.Done || current == HistoryDeletion.Failed) _historyDeletion.compareAndSet(current, HistoryDeletion.Idle)
    }

    /** Removes another device's file from the space (§8.3). */
    suspend fun removeDevice(deviceId: String): Boolean = withContext(Dispatchers.IO) {
        val location = settings.location ?: return@withContext false
        runCatching { SyncEngine(factory.open(location), store, deviceInfo(), location.space).removeDevice(deviceId) }.isSuccess
    }

    /** The other devices in the space, from the last listing, for Remove Device. */
    suspend fun otherDevices(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val location = settings.location ?: return@withContext emptyList()
        runCatching {
            val storage = factory.open(location)
            storage.list(location.space).mapNotNull { file ->
                val id = com.babasama.edendale.sync.DeviceFile.deviceId(file.name) ?: return@mapNotNull null
                if (id == settings.deviceId) return@mapNotNull null
                val name = runCatching {
                    val bytes = storage.read(location.space, file.name)
                    (com.babasama.edendale.sync.DeviceFileCodec.decode(bytes, location.space, file.name) as? com.babasama.edendale.sync.DeviceFileCodec.Decoded.Valid)?.file?.device?.name
                }.getOrNull()
                id to (name ?: id)
            }
        }.getOrDefault(emptyList())
    }

    fun refreshStatus() {
        val location = settings.location
        _status.value = _status.value.copy(
            location = location,
            deviceId = settings.deviceId,
            deviceName = deviceName(),
            lastSyncAt = settings.lastSyncAt,
            warnings = settings.warnings,
            problem = location?.let(factory::problem),
            previousLocation = settings.previousLocation,
        )
    }

    /** Background sync (FEATURES-27.1.md §8): every 15 minutes with a network, deferred by Doze; best effort. */
    private fun scheduleBackgroundWork() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * FEATURES-27.1.md §8: the one-off job after local changes. WorkManager
     * keeps it across process death and runs it once a network is up; one
     * is enough however many changes led here, so a waiting one is kept.
     */
    private fun enqueueFlushWork() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            // The sync started on leaving usually finishes first; the job then finds nothing to write.
            .setInitialDelay(SyncTriggers.DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(SyncWorker.KEY_FLUSH to true))
            .build()
        WorkManager.getInstance(app).enqueueUniqueWork(FLUSH_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val TAG = "SyncCoordinator"
        const val WORK_NAME = "edendale-sync"
        const val FLUSH_WORK_NAME = "edendale-sync-flush"
    }
}

/**
 * The periodic background sync (§8), and the one-off flush after local
 * changes ([KEY_FLUSH]), which ends at once when nothing is left to write.
 * Credential-encrypted storage opens after the first unlock; before that the
 * work waits.
 */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? EdendaleApplication ?: return Result.failure()
        val settings = app.sync.settings
        if (inputData.getBoolean(KEY_FLUSH, false) && !SyncTriggers.flushJobNeeded(settings.location != null, settings.dirty)) return Result.success()
        val report = try {
            app.sync.runSync(reason = "background")
        } catch (error: IOException) {
            null
        }
        return if (report != null || app.sync.settings.location == null) Result.success() else Result.retry()
    }

    companion object {
        /** Input flag of the one-off flush job. */
        const val KEY_FLUSH = "flush"
    }
}
