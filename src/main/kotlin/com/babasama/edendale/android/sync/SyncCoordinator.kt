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
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.babasama.edendale.android.BuildConfig
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.android.isTelevisionDevice
import com.babasama.edendale.sync.SyncBackoff
import com.babasama.edendale.sync.SyncDeviceInfo
import com.babasama.edendale.sync.SyncEngine
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
) {
    val isOn: Boolean get() = location != null
}

/**
 * Runs sync on this device (SYNC-PROTOCOL.md §8.1; FEATURES-27.1.md §5.4,
 * §8): on launch, on return to the foreground, 10 seconds after the last
 * local change (at once when playback stops or the app goes to the
 * background), every 5 minutes in the foreground, and in the background
 * through a periodic WorkManager job with a network constraint. One sync at
 * a time; failures back off 1, 2, 4, 8 minutes up to an hour. Lives for the
 * process ([EdendaleApplication.sync]).
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

    private var debounce: Job? = null
    private var periodic: Job? = null
    private var startedActivities = 0
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
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) onForeground()
            }
            override fun onActivityStopped(activity: Activity) {
                if (--startedActivities == 0) onBackground()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        if (settings.location != null) {
            requestSync(reason = "launch")
            scheduleBackgroundWork()
        }
    }

    private fun onForeground() {
        if (settings.location == null) return
        requestSync(reason = "foreground")
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

    private fun requestSync(reason: String) {
        scope.launch { runSync(reason) }
    }

    /** One sync, if a location is set and the backoff allows; returns the report, or null when nothing ran. */
    suspend fun runSync(reason: String, ignoreBackoff: Boolean = false): SyncReport? {
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
                val report = engine.sync()
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
     */
    suspend fun enable(location: SyncLocation) = withContext(Dispatchers.IO) {
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
        WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
        refreshStatus()
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
        )
    }

    /** Background sync (FEATURES-27.1.md §8): every 15 minutes with a network, deferred by Doze; best effort. */
    private fun scheduleBackgroundWork() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    companion object {
        const val TAG = "SyncCoordinator"
        const val WORK_NAME = "edendale-sync"
    }
}

/** The periodic background sync (§8). Credential-encrypted storage opens after the first unlock; before that the work waits. */
class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? EdendaleApplication ?: return Result.failure()
        val report = try {
            app.sync.runSync(reason = "background")
        } catch (error: IOException) {
            null
        }
        return if (report != null || app.sync.settings.location == null) Result.success() else Result.retry()
    }
}
