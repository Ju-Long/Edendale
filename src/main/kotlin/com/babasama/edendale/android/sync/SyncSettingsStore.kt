package com.babasama.edendale.android.sync

import android.content.Context
import android.content.SharedPreferences
import com.babasama.edendale.sync.SyncLocation
import com.babasama.edendale.sync.SyncProvider
import com.babasama.edendale.sync.SyncWarning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * What 27.1 sync keeps on this device outside the record tables
 * (FEATURES-27.1.md §5.5; SYNC-PROTOCOL.md §3, §7.2, §8): the device ID,
 * the one location (and the one it replaced, until the viewer deletes or
 * keeps this device's file there), the high-water mark, the stamps last read per file,
 * whether local changes await a write, and the last sync's outcome for the
 * UI. The file is excluded from cloud backup and device transfer in
 * `backup_rules.xml` and `data_extraction_rules.xml`, because a device ID
 * restored onto another device breaks the tie-break. Logins live in their
 * own encrypted stores, never here. Blocking disk access: call off the main
 * thread.
 */
class SyncSettingsStore(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _changes = MutableStateFlow(0)

    /** Bumps whenever the location, status, or device changes, for the UI. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    private fun changed() {
        _changes.value += 1
    }

    /** This device's ID (§3), made on first use and never copied to another device. */
    val deviceId: String
        get() = synchronized(lock) {
            preferences.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().lowercase().also {
                preferences.edit().putString(KEY_DEVICE_ID, it).commit()
            }
        }

    /** The name shown to other devices (`device.name`), or null for the system's name. */
    var deviceName: String?
        get() = preferences.getString(KEY_DEVICE_NAME, null)?.takeIf { it.isNotBlank() }
        set(value) {
            preferences.edit().putString(KEY_DEVICE_NAME, value?.trim()?.takeIf { it.isNotEmpty() }).commit()
            changed()
        }

    /** The one sync location, or null while sync is off. */
    var location: SyncLocation?
        get() = preferences.getString(KEY_LOCATION, null)?.let(::decodeLocation)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_LOCATION) else putString(KEY_LOCATION, encodeLocation(value))
                // A new location starts from an empty view of the space.
                remove(KEY_STAMPS)
                remove(KEY_LAST_SYNC)
                remove(KEY_LAST_ERROR)
                remove(KEY_WARNINGS)
                putBoolean(KEY_DIRTY, true)
            }.commit()
            changed()
        }

    /**
     * The location a new one replaced, while this device's file may still be
     * there (FEATURES-27.1.md §5.1): Settings → Sync offers to delete it or
     * keep it. Null once answered.
     */
    var previousLocation: SyncLocation?
        get() = preferences.getString(KEY_PREVIOUS_LOCATION, null)?.let(::decodeLocation)
        set(value) {
            preferences.edit().apply {
                if (value == null) remove(KEY_PREVIOUS_LOCATION) else putString(KEY_PREVIOUS_LOCATION, encodeLocation(value))
            }.commit()
            changed()
        }

    /** The highest `updateTime` applied or written (§7.2). */
    var highWater: Long
        get() = preferences.getLong(KEY_HIGH_WATER, 0L)
        set(value) {
            preferences.edit().putLong(KEY_HIGH_WATER, value).commit()
        }

    /** The next stamp for a local write (§7.2), raising the high-water mark; one caller at a time. */
    fun nextStamp(now: Long): Long = synchronized(lock) {
        val stamp = maxOf(now, highWater + 1)
        highWater = stamp
        stamp
    }

    /** Raises the high-water mark to [value] if higher. */
    fun raiseHighWater(value: Long) = synchronized(lock) {
        if (value > highWater) highWater = value
    }

    var dirty: Boolean
        get() = preferences.getBoolean(KEY_DIRTY, false)
        set(value) {
            preferences.edit().putBoolean(KEY_DIRTY, value).commit()
        }

    /** Stamps last read per file name (§4). */
    var stamps: Map<String, String>
        get() = preferences.getString(KEY_STAMPS, null)?.let { text ->
            runCatching { Json.parseToJsonElement(text).jsonObject.mapValues { (_, value) -> (value as JsonPrimitive).content } }.getOrNull()
        }.orEmpty()
        set(value) {
            val json = buildJsonObject { value.forEach { (name, stamp) -> put(name, JsonPrimitive(stamp)) } }
            preferences.edit().putString(KEY_STAMPS, json.toString()).commit()
        }

    var lastSyncAt: Long?
        get() = preferences.getLong(KEY_LAST_SYNC, 0L).takeIf { it > 0 }
        set(value) {
            preferences.edit().apply { if (value == null) remove(KEY_LAST_SYNC) else putLong(KEY_LAST_SYNC, value) }.commit()
            changed()
        }

    /** The last failure's class, for the UI; never a URL, a token, or a server's words. */
    var lastError: String?
        get() = preferences.getString(KEY_LAST_ERROR, null)
        set(value) {
            preferences.edit().apply { if (value == null) remove(KEY_LAST_ERROR) else putString(KEY_LAST_ERROR, value) }.commit()
            changed()
        }

    /** The last sync's warnings (§5 "Validation", §7.1), by device name or file. */
    var warnings: List<SyncWarning>
        get() = preferences.getStringSet(KEY_WARNINGS, null).orEmpty().mapNotNull(::decodeWarning)
        set(value) {
            preferences.edit().putStringSet(KEY_WARNINGS, value.map(::encodeWarning).toSet()).commit()
            changed()
        }

    /** Forgets everything but the device ID: used when sync is turned off. */
    fun clearLocation() {
        location = null
    }

    private fun encodeLocation(location: SyncLocation): String = buildJsonObject {
        put("provider", JsonPrimitive(location.provider.raw))
        put("space", JsonPrimitive(location.space))
        put("accountKey", location.accountKey?.let(::JsonPrimitive) ?: JsonNull)
        put("host", location.host?.let(::JsonPrimitive) ?: JsonNull)
        put("port", location.port?.let(::JsonPrimitive) ?: JsonNull)
        put("path", location.path?.let(::JsonPrimitive) ?: JsonNull)
        put("googleFolderId", location.googleFolderId?.let(::JsonPrimitive) ?: JsonNull)
    }.toString()

    private fun decodeLocation(text: String): SyncLocation? = runCatching {
        val json: JsonObject = Json.parseToJsonElement(text).jsonObject
        fun string(name: String) = (json[name] as? JsonPrimitive)?.contentOrNull
        SyncLocation(
            provider = SyncProvider.fromRaw(string("provider")) ?: return null,
            space = string("space") ?: return null,
            accountKey = string("accountKey"),
            host = string("host"),
            port = (json["port"] as? JsonPrimitive)?.intOrNull,
            path = string("path"),
            googleFolderId = string("googleFolderId"),
        )
    }.getOrNull()

    private fun encodeWarning(warning: SyncWarning): String = when (warning) {
        is SyncWarning.FileSkipped -> "skipped|${warning.reason.name}|${warning.fileName}|${warning.deviceName.orEmpty()}"
        is SyncWarning.FarFuture -> "farFuture|${warning.records}|${warning.deviceName}"
    }

    private fun decodeWarning(text: String): SyncWarning? {
        val parts = text.split('|', limit = 4)
        return when (parts.firstOrNull()) {
            "skipped" -> runCatching {
                SyncWarning.FileSkipped(parts[2], parts.getOrNull(3)?.takeIf { it.isNotEmpty() }, com.babasama.edendale.sync.DeviceFileCodec.SkipReason.valueOf(parts[1]))
            }.getOrNull()
            "farFuture" -> parts.getOrNull(2)?.let { SyncWarning.FarFuture(it, parts[1].toIntOrNull() ?: 1) }
            else -> null
        }
    }

    companion object {
        /** Listed in backup_rules.xml and data_extraction_rules.xml. */
        const val FILE_NAME = "edendale_sync"
        private const val KEY_DEVICE_ID = "device-id"
        private const val KEY_DEVICE_NAME = "device-name"
        private const val KEY_LOCATION = "location"
        private const val KEY_PREVIOUS_LOCATION = "previous-location"
        private const val KEY_HIGH_WATER = "high-water"
        private const val KEY_DIRTY = "dirty"
        private const val KEY_STAMPS = "stamps"
        private const val KEY_LAST_SYNC = "last-sync"
        private const val KEY_LAST_ERROR = "last-error"
        private const val KEY_WARNINGS = "warnings"
    }
}
