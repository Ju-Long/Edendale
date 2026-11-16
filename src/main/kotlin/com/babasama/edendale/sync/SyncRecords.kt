package com.babasama.edendale.sync

/**
 * The portable watch records of SYNC-PROTOCOL.md §5 and §6 (27.1, F4): one
 * `updateTime` and one writing `device` per record, keyed by TMDB identity so
 * nothing in them depends on local files. Pure Kotlin; the Android stores
 * project these onto the app's own tables and back ([com.babasama.edendale.android.sync]).
 */

/** `movie` or `tv`: the TMDB identity a record is keyed by. */
enum class SyncMediaType(val raw: String) {
    MOVIE("movie"),
    TV("tv"),
    ;

    companion object {
        fun fromRaw(raw: String?): SyncMediaType? = entries.firstOrNull { it.raw == raw }
    }
}

/** What every record carries: its key, the device that wrote this version, and when (§7). */
sealed interface SyncRecord {
    val key: String
    val device: String
    val updateTime: Long
}

/**
 * One watch-progress record (§6.1): `movie:<id>` or `tv:<id>:<season>:<episode>`.
 * A [cleared] record is a tombstone and carries no position, duration, or
 * completion. `updateTime` is also the last-watched time.
 */
data class ProgressRecord(
    val type: SyncMediaType,
    val tmdbId: Int,
    val season: Int? = null,
    val episode: Int? = null,
    /** Seconds. */
    val position: Double? = null,
    /** Seconds. */
    val duration: Double? = null,
    val completed: Boolean? = null,
    val cleared: Boolean = false,
    override val device: String,
    override val updateTime: Long,
) : SyncRecord {
    override val key: String get() = progressKey(type, tmdbId, season, episode)

    /** A tombstone (§6.1): the value was cleared at [updateTime]. */
    val isTombstone: Boolean get() = cleared

    companion object {
        fun progressKey(type: SyncMediaType, tmdbId: Int, season: Int?, episode: Int?): String =
            if (type == SyncMediaType.TV) "${type.raw}:$tmdbId:$season:$episode" else "${type.raw}:$tmdbId"

        /** A cleared record for [key]'s identity, as a clear is written (§5.3 "clearing is a write"). */
        fun tombstone(type: SyncMediaType, tmdbId: Int, season: Int?, episode: Int?, device: String, updateTime: Long) =
            ProgressRecord(type, tmdbId, season, episode, cleared = true, device = device, updateTime = updateTime)
    }
}

/**
 * One user-media record (§6.2): `movie:<id>` or `tv:<id>`. All four values
 * off or null is the tombstone; it is kept, never dropped.
 */
data class UserMediaSyncRecord(
    val type: SyncMediaType,
    val tmdbId: Int,
    val favourite: Boolean = false,
    val watchlist: Boolean = false,
    val rating: Double? = null,
    val review: String? = null,
    override val device: String,
    override val updateTime: Long,
) : SyncRecord {
    override val key: String get() = "${type.raw}:$tmdbId"

    val isTombstone: Boolean get() = !favourite && !watchlist && rating == null && review == null

    companion object {
        /** A review is at most this many Unicode scalar values (§5). */
        const val MAX_REVIEW_SCALARS = 10_000
    }
}

/** The `device` object at the top of a device file (§5). */
data class SyncDeviceInfo(
    /** A lowercase hyphenated version-4 UUID (§3). */
    val id: String,
    /** 1–64 characters, shown in the UI. */
    val name: String,
    /** One of §3's platform names; unknown values read as "another device". */
    val platform: String,
    /** The app version, informational. */
    val app: String,
) {
    companion object {
        val PLATFORMS = setOf("ios", "ipados", "macos", "tvos", "visionos", "android", "androidtv", "horizon", "windows", "xbox")
        private val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

        /** Whether [id] is a lowercase hyphenated version-4 UUID, the only form a device file name takes. */
        fun isDeviceId(id: String): Boolean = UUID_V4.matches(id)

        /** A device name as the file carries it: trimmed, 1–64 characters, never blank. */
        fun fileName(name: String): String = name.trim().ifEmpty { "Edendale" }.let { if (it.length > 64) it.substring(0, 64) else it }
    }
}

/** One device's whole merged watch state, as its file holds it (§5). */
data class DeviceFile(
    val space: String,
    val device: SyncDeviceInfo,
    val watchProgress: List<ProgressRecord>,
    val userMedia: List<UserMediaSyncRecord>,
) {
    /** The highest `updateTime` of any record, or 0 (§5). */
    val updateTime: Long
        get() = (watchProgress.asSequence().map { it.updateTime } + userMedia.asSequence().map { it.updateTime }).maxOrNull() ?: 0L

    companion object {
        const val FORMAT = 1

        /** A file over this many bytes is skipped with a warning (§5). */
        const val MAX_BYTES = 8 shl 20

        /** A file with more records than this in total is skipped (§5). */
        const val MAX_RECORDS = 100_000

        /** The file name of [deviceId]'s device file. */
        fun fileName(deviceId: String) = "$deviceId.json"

        /** The device ID a file name carries, or null for a name that isn't `<uuid>.json`. */
        fun deviceId(fileName: String): String? =
            fileName.removeSuffix(".json").takeIf { fileName.endsWith(".json") && SyncDeviceInfo.isDeviceId(it) }
    }
}

/** The space-name rules of §3. */
object SpaceName {
    const val MAX_LENGTH = 64
    private val FORBIDDEN = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    /** [input] trimmed, or null when it isn't a valid space name. */
    fun normalize(input: String): String? {
        val name = input.trim()
        if (name.isEmpty() || name == "." || name == "..") return null
        if (name.codePointCount(0, name.length) > MAX_LENGTH) return null
        if (name.any { it in FORBIDDEN || it.isISOControl() }) return null
        return name
    }

    fun isValid(input: String): Boolean = normalize(input) != null
}
