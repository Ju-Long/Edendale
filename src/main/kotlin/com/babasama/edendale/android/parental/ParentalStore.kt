package com.babasama.edendale.android.parental

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Where the parental controls keep the PIN record, the ceiling and switches,
 * the back-off state, and the cached certification lists (O-D7). A seam so
 * the controls are tested without Android; every method is synchronous and
 * blocking, and the controls call them from `Dispatchers.IO`.
 */
interface ParentalStore {
    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun getBoolean(key: String, default: Boolean): Boolean

    fun putBoolean(key: String, value: Boolean)

    fun getLong(key: String, default: Long): Long

    fun putLong(key: String, value: Long)

    fun contains(key: String): Boolean

    fun remove(key: String)

    /** Calls [listener] with each changed key until the returned handle is closed. */
    fun addListener(listener: (String) -> Unit): AutoCloseable
}

/**
 * The shipping store: `edendale_parental`, an EncryptedSharedPreferences file
 * under an Android Keystore key, in the `TmdbSessionStore` shape. It is
 * excluded from backup and device transfer in both rule files, so a restored
 * device keeps the Young Audience preset but has no PIN. Everything is lazy
 * so construction never touches the Keystore on the main thread; reads and
 * writes block and belong on a background thread.
 */
class EncryptedParentalStore(context: Context) : ParentalStore {
    private val appContext = context.applicationContext

    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val preferences: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // SharedPreferences holds its listeners weakly; this list keeps them alive
    // for as long as the store does.
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).commit()
    }

    override fun getBoolean(key: String, default: Boolean): Boolean = preferences.getBoolean(key, default)

    override fun putBoolean(key: String, value: Boolean) {
        preferences.edit().putBoolean(key, value).commit()
    }

    override fun getLong(key: String, default: Long): Long = preferences.getLong(key, default)

    override fun putLong(key: String, value: Long) {
        preferences.edit().putLong(key, value).commit()
    }

    override fun contains(key: String): Boolean = preferences.contains(key)

    override fun remove(key: String) {
        preferences.edit().remove(key).commit()
    }

    override fun addListener(listener: (String) -> Unit): AutoCloseable {
        val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null) listener(key)
        }
        listeners += changeListener
        preferences.registerOnSharedPreferenceChangeListener(changeListener)
        return AutoCloseable {
            preferences.unregisterOnSharedPreferenceChangeListener(changeListener)
            listeners -= changeListener
        }
    }

    companion object {
        /** Listed in backup_rules.xml and data_extraction_rules.xml. */
        const val FILE_NAME = "edendale_parental"
    }
}

/** A map-backed [ParentalStore] for the hermetic tests; a second controls object over the same store is a "relaunch". */
class InMemoryParentalStore : ParentalStore {
    private val values = ConcurrentHashMap<String, Any>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    override fun getString(key: String): String? = values[key] as? String

    override fun putString(key: String, value: String) = put(key, value)

    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default

    override fun putBoolean(key: String, value: Boolean) = put(key, value)

    override fun getLong(key: String, default: Long): Long = (values[key] as? Number)?.toLong() ?: default

    override fun putLong(key: String, value: Long) = put(key, value)

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun remove(key: String) {
        values.remove(key)
        listeners.forEach { it(key) }
    }

    override fun addListener(listener: (String) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Every key present, for tests that check what was and was not written. */
    fun keys(): Set<String> = values.keys.toSet()

    private fun put(key: String, value: Any) {
        values[key] = value
        listeners.forEach { it(key) }
    }
}
