package com.babasama.edendale.sync

import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * What the engine keeps between syncs on this device (SYNC-PROTOCOL.md §8):
 * the ledger, the stamp last read per other device file, and whether local
 * changes are waiting to be written. The Android store is Room plus a
 * preferences file excluded from backup; tests keep it in memory.
 */
interface SyncLocalStore {
    suspend fun ledger(): SyncLedger

    /** Keeps the merged [ledger], applying the changed keys to the app's own tables where it has any. */
    suspend fun applyMerge(merge: SyncLedger.Merge)

    /** Stamps read per file name (§4), kept so an unchanged file is skipped. */
    suspend fun stamps(): Map<String, String>

    suspend fun saveStamps(stamps: Map<String, String>)

    /** Whether the ledger changed since this device last wrote its file (by the user or by a merge). */
    suspend fun isDirty(): Boolean

    suspend fun markWritten()
}

/** One sync's outcome, for the UI and the log (§8.2 step 4). Counts and names only. */
data class SyncReport(
    val filesListed: Int,
    val filesRead: Int,
    val recordsChanged: Int,
    val wrote: Boolean,
    /** Files skipped with a warning, by device name where the header parsed, else by file name. */
    val warnings: List<SyncWarning>,
)

/** A warning the UI shows after a sync (§5 "Validation", §7.1). */
sealed interface SyncWarning {
    /** Another device's file was skipped. */
    data class FileSkipped(val fileName: String, val deviceName: String?, val reason: DeviceFileCodec.SkipReason) : SyncWarning

    /** Records stamped more than a day ahead were not applied; the device's clock is wrong. */
    data class FarFuture(val deviceName: String, val records: Int) : SyncWarning
}

/**
 * One sync (§8.2): list the space, read and merge every other device file
 * whose stamp changed, write this device's file when anything changed
 * locally since the last write, and record the stamps. A listing that fails
 * leaves the store untouched; a write that fails is retried by the next
 * trigger ([SyncBackoff]). Reading a file twice changes nothing. Pure Kotlin.
 */
class SyncEngine(
    private val storage: SyncStorage,
    private val store: SyncLocalStore,
    private val device: SyncDeviceInfo,
    private val space: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Runs one sync; throws [IOException] when the location can't be listed
     * or the file can't be written. With [readEveryFile], every other file is
     * read again, changed or not, so the ledger takes in anything an earlier
     * read held back behind a stamp that hasn't moved since (a record the
     * far-future guard refused that is now in range): Delete Watch History
     * reads this way before it clears, so its tombstones cover those keys too.
     */
    @Throws(IOException::class)
    suspend fun sync(readEveryFile: Boolean = false): SyncReport {
        storage.ensureSpace(space)
        val listed = storage.list(space)
        SyncLog.log(TAG, "listed ${listed.size} file(s) in space")
        val known = if (readEveryFile) emptyMap() else store.stamps()
        val stamps = known.toMutableMap()
        val warnings = mutableListOf<SyncWarning>()
        var read = 0
        var changed = 0
        val ownFile = DeviceFile.fileName(device.id)
        val listedNames = listed.map { it.name }.toSet()
        // Forget the stamps of files that are gone, so a file that reappears is read again.
        stamps.keys.retainAll { it in listedNames }

        for (file in listed) {
            if (file.name == ownFile) continue
            val deviceId = DeviceFile.deviceId(file.name) ?: continue
            if (known[file.name] == file.stamp) continue
            if (file.size != null && file.size > DeviceFile.MAX_BYTES) {
                warnings += SyncWarning.FileSkipped(file.name, null, DeviceFileCodec.SkipReason.TOO_LARGE)
                stamps[file.name] = file.stamp
                SyncLog.log(TAG, "skipped ${file.name}: TOO_LARGE (${file.size} bytes)")
                continue
            }
            val bytes = try {
                storage.read(space, file.name)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                // Try again next time; the stamp stays unknown.
                SyncLog.log(TAG, "read ${file.name} failed: ${SyncLog.describe(error)}")
                continue
            }
            read += 1
            when (val decoded = DeviceFileCodec.decode(bytes, space, file.name, file.size ?: bytes.size.toLong())) {
                is DeviceFileCodec.Decoded.Skipped -> {
                    warnings += SyncWarning.FileSkipped(file.name, decoded.deviceName, decoded.reason)
                    SyncLog.log(TAG, "skipped ${file.name}: ${decoded.reason}")
                }
                is DeviceFileCodec.Decoded.Valid -> {
                    val merge = store.ledger().merge(decoded.file, clock())
                    if (merge.farFutureSkipped > 0) {
                        warnings += SyncWarning.FarFuture(decoded.file.device.name, merge.farFutureSkipped)
                        SyncLog.log(TAG, "far-future guard: ${merge.farFutureSkipped} record(s) from $deviceId not applied")
                    }
                    if (merge.changed) {
                        store.applyMerge(merge)
                        changed += merge.changedProgress.size + merge.changedUserMedia.size
                    } else if (merge.ledger.highWater != store.ledger().highWater) {
                        store.applyMerge(merge)
                    }
                    SyncLog.log(TAG, "merged ${file.name}: ${merge.changedProgress.size + merge.changedUserMedia.size} record(s) changed")
                }
            }
            stamps[file.name] = file.stamp
        }

        var wrote = false
        if (store.isDirty()) {
            val bytes = DeviceFileCodec.encode(store.ledger().file(space, device), clock())
            storage.write(space, ownFile, bytes)
            store.markWritten()
            wrote = true
            SyncLog.log(TAG, "wrote $ownFile (${bytes.size} bytes)")
        }
        store.saveStamps(stamps)
        return SyncReport(listed.size, read, changed, wrote, warnings)
    }

    /** Removes this device's file from the space (§8.3, and when leaving a location). */
    @Throws(IOException::class)
    suspend fun removeOwnFile() {
        storage.delete(space, DeviceFile.fileName(device.id))
        SyncLog.log(TAG, "deleted own file")
    }

    /** Removes another device's file (§8.3 "Removing a device"). */
    @Throws(IOException::class)
    suspend fun removeDevice(deviceId: String) {
        storage.delete(space, DeviceFile.fileName(deviceId))
        SyncLog.log(TAG, "deleted ${DeviceFile.fileName(deviceId)}")
    }

    companion object {
        const val TAG = "Sync"
    }
}

/**
 * The retry schedule of §8.2: failures back off 1, 2, 4, 8 minutes, capped
 * at an hour, reset by a success. Pure.
 */
object SyncBackoff {
    const val FIRST_DELAY_MILLIS = 60_000L
    const val MAX_DELAY_MILLIS = 60 * 60_000L

    /** The wait after [consecutiveFailures] failures in a row (1 → 1 min, 2 → 2 min, …); 0 means no wait. */
    fun delayMillis(consecutiveFailures: Int): Long {
        if (consecutiveFailures <= 0) return 0L
        val shift = minOf(consecutiveFailures - 1, 20)
        return minOf(FIRST_DELAY_MILLIS shl shift, MAX_DELAY_MILLIS)
    }
}

/**
 * When the client syncs (§8.1): on launch and on return to the foreground,
 * 10 seconds after the last local change (at once when playback stops or the
 * app goes to the background), and every 5 minutes in the foreground. Pure
 * rules for the Android coordinator and its tests.
 */
object SyncTriggers {
    const val DEBOUNCE_MILLIS = 10_000L
    const val PERIODIC_MILLIS = 5 * 60_000L

    /**
     * FEATURES-27.1.md §8, "a one-off job after local changes": leaving the
     * foreground with a write still pending also hands it to a one-off
     * background job, which outlives the process and waits for a network;
     * the job writes only if the write is still pending when it runs, since
     * the sync started on leaving usually gets there first.
     */
    fun flushJobNeeded(syncOn: Boolean, writePending: Boolean): Boolean = syncOn && writePending
}
