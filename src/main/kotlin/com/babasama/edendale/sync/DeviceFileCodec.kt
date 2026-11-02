package com.babasama.edendale.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Reads and writes device files (SYNC-PROTOCOL.md §1.2, §5, §6). A file that
 * fails any rule is skipped as a whole ([Decoded.Skipped]) and the warning
 * names the file; skipping never removes local data. An invalid `updateTime`
 * on a record is not a skip: it counts as 0 (§7.3). Pure Kotlin.
 */
object DeviceFileCodec {

    /** Why a file was skipped, for the warning (§5 "Validation"). Never carries record contents. */
    enum class SkipReason {
        NOT_JSON,
        /** A `format` above [DeviceFile.FORMAT]: "update Edendale to sync with <device>". */
        NEWER_FORMAT,
        OLDER_FORMAT,
        WRONG_SPACE,
        WRONG_DEVICE_ID,
        INVALID_DEVICE,
        TOO_LARGE,
        TOO_MANY_RECORDS,
        INVALID_RECORD,
        DUPLICATE_KEY,
    }

    sealed interface Decoded {
        data class Valid(val file: DeviceFile) : Decoded

        /** [deviceName] is known only when the header parsed; the UI names the device when it can, the file otherwise. */
        data class Skipped(val reason: SkipReason, val deviceName: String? = null) : Decoded
    }

    /** The stamp a record reads with when its `updateTime` is missing, fractional, or before 2000-01-01 (§7.3). */
    const val INVALID_STAMP = 0L

    /** 2000-01-01T00:00:00Z: the lowest valid `updateTime` (§7.3). */
    const val MIN_VALID_STAMP = 946_684_800_000L

    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - Writing

    /** The file's bytes: UTF-8 JSON without a byte-order mark, only the fields §5 and §6 define. */
    fun encode(file: DeviceFile, now: Long = Long.MIN_VALUE, keepTombstonesFor: Long = TOMBSTONE_RETENTION_MILLIS): ByteArray {
        val cutoff = if (now == Long.MIN_VALUE) Long.MIN_VALUE else now - keepTombstonesFor
        val progress = file.watchProgress.filterNot { it.isTombstone && it.updateTime < cutoff }
        val media = file.userMedia.filterNot { it.isTombstone && it.updateTime < cutoff }
        val body = buildJsonObject {
            put("format", JsonPrimitive(DeviceFile.FORMAT))
            put("space", JsonPrimitive(file.space))
            put(
                "device",
                buildJsonObject {
                    put("id", JsonPrimitive(file.device.id))
                    put("name", JsonPrimitive(SyncDeviceInfo.fileName(file.device.name)))
                    put("platform", JsonPrimitive(file.device.platform))
                    put("app", JsonPrimitive(file.device.app))
                },
            )
            put("updateTime", JsonPrimitive(DeviceFile(file.space, file.device, progress, media).updateTime))
            put("watchProgress", buildJsonArray { progress.forEach { add(encode(it)) } })
            put("userMedia", buildJsonArray { media.forEach { add(encode(it)) } })
        }
        return body.toString().toByteArray(Charsets.UTF_8)
    }

    /** A device may omit tombstones older than this when it writes its file (§6.3; decision 4). */
    const val TOMBSTONE_RETENTION_MILLIS = 365L * 24 * 60 * 60 * 1_000

    private fun encode(record: ProgressRecord): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(record.type.raw))
        put("tmdbId", JsonPrimitive(record.tmdbId))
        if (record.type == SyncMediaType.TV) {
            put("season", JsonPrimitive(record.season ?: 0))
            put("episode", JsonPrimitive(record.episode ?: 0))
        }
        if (record.cleared) {
            put("cleared", JsonPrimitive(true))
        } else {
            put("position", JsonPrimitive(record.position ?: 0.0))
            put("duration", JsonPrimitive(record.duration ?: 0.0))
            put("completed", JsonPrimitive(record.completed ?: false))
        }
        put("device", JsonPrimitive(record.device))
        put("updateTime", JsonPrimitive(record.updateTime))
    }

    private fun encode(record: UserMediaSyncRecord): JsonObject = buildJsonObject {
        put("type", JsonPrimitive(record.type.raw))
        put("tmdbId", JsonPrimitive(record.tmdbId))
        put("favourite", JsonPrimitive(record.favourite))
        put("watchlist", JsonPrimitive(record.watchlist))
        put("rating", record.rating?.let(::JsonPrimitive) ?: JsonNull)
        put("review", record.review?.let(::JsonPrimitive) ?: JsonNull)
        put("device", JsonPrimitive(record.device))
        put("updateTime", JsonPrimitive(record.updateTime))
    }

    // MARK: - Reading

    /**
     * Parses and validates the file named [fileName] read from the [space]
     * folder. [byteCount] is the size the listing reported (or the bytes'
     * own length) for the 8 MiB limit, which applies before parsing.
     */
    fun decode(bytes: ByteArray, space: String, fileName: String, byteCount: Long = bytes.size.toLong()): Decoded {
        if (byteCount > DeviceFile.MAX_BYTES || bytes.size > DeviceFile.MAX_BYTES) return Decoded.Skipped(SkipReason.TOO_LARGE)
        val root = runCatching { json.parseToJsonElement(bytes.decodeToString()) }.getOrNull() as? JsonObject
            ?: return Decoded.Skipped(SkipReason.NOT_JSON)
        val deviceName = (root["device"] as? JsonObject)?.string("name")
        val format = root.integer("format") ?: return Decoded.Skipped(SkipReason.NOT_JSON, deviceName)
        if (format > DeviceFile.FORMAT) return Decoded.Skipped(SkipReason.NEWER_FORMAT, deviceName)
        if (format < DeviceFile.FORMAT) return Decoded.Skipped(SkipReason.OLDER_FORMAT, deviceName)
        if (root.string("space") != space) return Decoded.Skipped(SkipReason.WRONG_SPACE, deviceName)

        val device = root["device"] as? JsonObject ?: return Decoded.Skipped(SkipReason.INVALID_DEVICE, deviceName)
        val id = device.string("id") ?: return Decoded.Skipped(SkipReason.INVALID_DEVICE, deviceName)
        if (DeviceFile.deviceId(fileName) != id) return Decoded.Skipped(SkipReason.WRONG_DEVICE_ID, deviceName)
        val name = device.string("name")?.trim()?.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= 64 }
            ?: return Decoded.Skipped(SkipReason.INVALID_DEVICE, deviceName)
        val info = SyncDeviceInfo(id, name, device.string("platform").orEmpty(), device.string("app").orEmpty())

        val progressArray = root["watchProgress"]?.let { it as? JsonArray ?: return Decoded.Skipped(SkipReason.INVALID_RECORD, name) } ?: JsonArray(emptyList())
        val mediaArray = root["userMedia"]?.let { it as? JsonArray ?: return Decoded.Skipped(SkipReason.INVALID_RECORD, name) } ?: JsonArray(emptyList())
        if (progressArray.size + mediaArray.size > DeviceFile.MAX_RECORDS) return Decoded.Skipped(SkipReason.TOO_MANY_RECORDS, name)

        val progress = progressArray.map { element ->
            decodeProgress(element as? JsonObject ?: return Decoded.Skipped(SkipReason.INVALID_RECORD, name))
                ?: return Decoded.Skipped(SkipReason.INVALID_RECORD, name)
        }
        if (progress.map { it.key }.toSet().size != progress.size) return Decoded.Skipped(SkipReason.DUPLICATE_KEY, name)
        val media = mediaArray.map { element ->
            decodeUserMedia(element as? JsonObject ?: return Decoded.Skipped(SkipReason.INVALID_RECORD, name))
                ?: return Decoded.Skipped(SkipReason.INVALID_RECORD, name)
        }
        if (media.map { it.key }.toSet().size != media.size) return Decoded.Skipped(SkipReason.DUPLICATE_KEY, name)
        return Decoded.Valid(DeviceFile(space, info, progress, media))
    }

    /** One `watchProgress` record (§6.1), or null when any field breaks its rule. */
    internal fun decodeProgress(record: JsonObject): ProgressRecord? {
        val type = SyncMediaType.fromRaw(record.string("type")) ?: return null
        val tmdbId = record.integer("tmdbId")?.takeIf { it >= 1 }?.let { if (it > Int.MAX_VALUE) return null else it.toInt() } ?: return null
        val season = record.integer("season")
        val episode = record.integer("episode")
        when (type) {
            SyncMediaType.TV -> if (season == null || episode == null || season < 0 || episode < 0 || season > Int.MAX_VALUE || episode > Int.MAX_VALUE) return null
            SyncMediaType.MOVIE -> if (record.containsValue("season") || record.containsValue("episode")) return null
        }
        if (record.containsValue("season") && season == null) return null
        if (record.containsValue("episode") && episode == null) return null
        val cleared = record.boolean("cleared") ?: if (record.containsValue("cleared")) return null else false
        val device = record.string("device") ?: return null
        val updateTime = record.stamp()
        if (cleared) {
            if (record.containsValue("position") || record.containsValue("duration") || record.containsValue("completed")) return null
            return ProgressRecord(type, tmdbId, season?.toInt(), episode?.toInt(), cleared = true, device = device, updateTime = updateTime)
        }
        val position = record.number("position")?.takeIf { it.isFinite() && it >= 0 } ?: return null
        val duration = record.number("duration")?.takeIf { it.isFinite() && it > 0 } ?: return null
        val completed = record.boolean("completed") ?: return null
        return ProgressRecord(type, tmdbId, season?.toInt(), episode?.toInt(), position, duration, completed, false, device, updateTime)
    }

    /** One `userMedia` record (§6.2), or null when any field breaks its rule. */
    internal fun decodeUserMedia(record: JsonObject): UserMediaSyncRecord? {
        val type = SyncMediaType.fromRaw(record.string("type")) ?: return null
        val tmdbId = record.integer("tmdbId")?.takeIf { it >= 1 && it <= Int.MAX_VALUE }?.toInt() ?: return null
        val favourite = record.boolean("favourite") ?: return null
        val watchlist = record.boolean("watchlist") ?: return null
        val rating = when (val element = record["rating"]) {
            null, JsonNull -> null
            is JsonPrimitive -> element.doubleOrNull?.takeIf { isValidRating(it) } ?: return null
            else -> return null
        }
        val review = when (val element = record["review"]) {
            null, JsonNull -> null
            is JsonPrimitive -> if (element.isString) element.content.takeIf { it.codePointCount(0, it.length) <= UserMediaSyncRecord.MAX_REVIEW_SCALARS } ?: return null else return null
            else -> return null
        }
        val device = record.string("device") ?: return null
        return UserMediaSyncRecord(type, tmdbId, favourite, watchlist, rating, review, device, record.stamp())
    }

    /** 0.5 to 10.0 in steps of 0.5 (§6.2). */
    fun isValidRating(value: Double): Boolean = value.isFinite() && value >= 0.5 && value <= 10.0 && (value * 2) % 1.0 == 0.0

    /** Whether [stamp] is a valid `updateTime` (§7.3); an invalid one reads as [INVALID_STAMP]. */
    fun isValidStamp(stamp: Long): Boolean = stamp >= MIN_VALID_STAMP

    // MARK: - JSON plumbing

    private fun JsonObject.containsValue(name: String): Boolean = this[name].let { it != null && it != JsonNull }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** An integer field; a fraction or a non-number reads as null (§1.2). */
    private fun JsonObject.integer(name: String): Long? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val text = primitive.content
        return text.toLongOrNull() ?: text.toDoubleOrNull()?.takeIf { it.isFinite() && it == Math.floor(it) && Math.abs(it) < 9.007199254740992E15 }?.toLong()
    }

    private fun JsonObject.number(name: String): Double? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.doubleOrNull
    }

    private fun JsonObject.boolean(name: String): Boolean? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return primitive.content.toBooleanStrictOrNull()
    }

    /** A record's `updateTime` as it counts in the merge: the integer, or 0 when missing, fractional, or before 2000 (§7.3). */
    private fun JsonObject.stamp(): Long {
        val value = integer("updateTime") ?: return INVALID_STAMP
        return if (isValidStamp(value)) value else INVALID_STAMP
    }

    @Suppress("unused")
    private fun JsonElement.asObjectOrNull(): JsonObject? = runCatching { jsonObject }.getOrNull()
}
