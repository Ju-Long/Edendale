package com.babasama.edendale.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.babasama.edendale.WyzieSecrets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Securely stores the viewer's Wyzie API key.
 *
 * Everything is lazy so construction never performs a Keystore round trip on
 * the main thread. Callers read or mutate the store from a background thread.
 */
class WyzieKeyStore(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            "edendale_wyzie",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val buildKey: String
        get() = WyzieSecrets.apiKey.trim()

    fun userKey(): String = sharedPrefs.getString(KEY_API_KEY, null)?.trim().orEmpty()

    fun resolvedKey(): String = userKey().ifEmpty { buildKey }

    fun isConfigured(): Boolean = resolvedKey().isNotEmpty()

    fun hasUserKey(): Boolean = userKey().isNotEmpty()

    fun usesBuildKey(): Boolean = !hasUserKey() && buildKey.isNotEmpty()

    suspend fun save(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) {
            clear()
            return
        }
        withContext(Dispatchers.IO) {
            sharedPrefs.edit().putString(KEY_API_KEY, trimmed).commit()
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            sharedPrefs.edit().remove(KEY_API_KEY).commit()
        }
    }

    private companion object {
        const val KEY_API_KEY = "api_key"
    }
}
