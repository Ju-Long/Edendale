package com.babasama.edendale.sync

/**
 * The merge of SYNC-PROTOCOL.md §7 (FEATURES-27.1.md §5.3): for each key the
 * record with the highest `updateTime` wins as a whole, ties go to the
 * lexically smaller device ID, a device never stamps below the highest value
 * it has applied, and a stamp more than a day ahead of the reader's clock is
 * not applied. Deterministic, idempotent, and independent of read order;
 * Appendix A.1's cases are `SyncVectorsTest`. Pure Kotlin, immutable.
 */
data class SyncLedger(
    val progress: Map<String, ProgressRecord> = emptyMap(),
    val userMedia: Map<String, UserMediaSyncRecord> = emptyMap(),
    /** The highest `updateTime` this device has applied or written (§7.2). */
    val highWater: Long = 0L,
) {
    /** What one file's merge did: the ledger after it, the keys whose record changed, and whether a far-future stamp was skipped. */
    data class Merge(
        val ledger: SyncLedger,
        val changedProgress: Set<String>,
        val changedUserMedia: Set<String>,
        /** Records refused by the far-future guard (§7.1); the UI warns naming the device. */
        val farFutureSkipped: Int,
    ) {
        val changed: Boolean get() = changedProgress.isNotEmpty() || changedUserMedia.isNotEmpty()
    }

    /** Applies another device's [file] read at [now] (the reader's UTC clock in milliseconds). */
    fun merge(file: DeviceFile, now: Long): Merge = merge(file.watchProgress, file.userMedia, now)

    fun merge(progressRecords: List<ProgressRecord>, mediaRecords: List<UserMediaSyncRecord>, now: Long): Merge {
        val limit = now + FAR_FUTURE_MILLIS
        var skipped = 0
        var water = highWater
        val newProgress = progress.toMutableMap()
        val changedProgress = mutableSetOf<String>()
        progressRecords.forEach { record ->
            if (record.updateTime > limit) {
                skipped += 1
                return@forEach
            }
            if (wins(record, newProgress[record.key])) {
                newProgress[record.key] = record
                changedProgress += record.key
            }
            water = maxOf(water, record.updateTime)
        }
        val newMedia = userMedia.toMutableMap()
        val changedMedia = mutableSetOf<String>()
        mediaRecords.forEach { record ->
            if (record.updateTime > limit) {
                skipped += 1
                return@forEach
            }
            if (wins(record, newMedia[record.key])) {
                newMedia[record.key] = record
                changedMedia += record.key
            }
            water = maxOf(water, record.updateTime)
        }
        return Merge(SyncLedger(newProgress, newMedia, water), changedProgress, changedMedia, skipped)
    }

    /** The stamp for a local write at [now] (§7.2), and the ledger with its high-water mark raised to it. */
    fun stamp(now: Long): Pair<Long, SyncLedger> {
        val stamp = maxOf(now, highWater + 1)
        return stamp to copy(highWater = stamp)
    }

    /** This device's own write of [record], stamped by [stamp]. */
    fun writing(record: ProgressRecord): SyncLedger = copy(progress = progress + (record.key to record), highWater = maxOf(highWater, record.updateTime))

    fun writing(record: UserMediaSyncRecord): SyncLedger = copy(userMedia = userMedia + (record.key to record), highWater = maxOf(highWater, record.updateTime))

    /**
     * Delete Watch History with sync on. The protocol can only say "cleared"
     * per key (§6.1), so every live progress record, and each key in
     * [alsoKnown] the ledger lacks (this device's rows that never reached
     * it), becomes a tombstone written by [device] at [stamp], one local
     * write taken with §7.2 so it wins over everything already applied. A
     * tombstone already held keeps its stamp and device: re-stamping it would
     * only churn the files. User media is untouched. Returns the ledger and
     * the keys cleared; with nothing to clear, this same ledger and no keys.
     * Keyed by map, so no key can reach a file twice.
     */
    fun clearingAllProgress(stamp: Long, device: String, alsoKnown: Collection<ProgressRecord> = emptyList()): Pair<SyncLedger, Set<String>> {
        val cleared = LinkedHashMap<String, ProgressRecord>()
        progress.values.filterNot { it.cleared }.forEach { cleared[it.key] = it.clearedBy(device, stamp) }
        alsoKnown.forEach { record ->
            if (record.key !in progress && record.key !in cleared) cleared[record.key] = record.clearedBy(device, stamp)
        }
        if (cleared.isEmpty()) return this to emptySet()
        return copy(progress = progress + cleared, highWater = maxOf(highWater, stamp)) to cleared.keys
    }

    private fun ProgressRecord.clearedBy(device: String, stamp: Long) = ProgressRecord.tombstone(type, tmdbId, season, episode, device, stamp)

    /** The device file this ledger writes out: the merged view, every tombstone included (the codec prunes old ones). */
    fun file(space: String, device: SyncDeviceInfo): DeviceFile =
        DeviceFile(space, device, progress.values.sortedBy { it.key }, userMedia.values.sortedBy { it.key })

    companion object {
        /** A stamp more than this far ahead of the reader's clock is not applied (§7.1). */
        const val FAR_FUTURE_MILLIS = 86_400_000L

        /**
         * §7.1: an absent local record, a newer stamp, or an equal stamp from
         * the lexically smaller device. A record identical to the local one
         * (same stamp, same device) never wins, so re-reading changes nothing.
         */
        fun <R : SyncRecord> wins(incoming: R, local: R?): Boolean = when {
            local == null -> true
            incoming.updateTime > local.updateTime -> true
            incoming.updateTime < local.updateTime -> false
            else -> compareDevices(incoming.device, local.device) < 0
        }

        /** Bytewise on the lowercase UUID (§7.1): the UTF-8 bytes of ASCII strings order like their chars. */
        fun compareDevices(a: String, b: String): Int {
            val x = a.toByteArray(Charsets.UTF_8)
            val y = b.toByteArray(Charsets.UTF_8)
            for (index in 0 until minOf(x.size, y.size)) {
                val difference = (x[index].toInt() and 0xFF) - (y[index].toInt() and 0xFF)
                if (difference != 0) return difference
            }
            return x.size - y.size
        }
    }
}
