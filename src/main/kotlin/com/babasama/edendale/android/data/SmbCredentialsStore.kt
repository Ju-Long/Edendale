package com.babasama.edendale.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Securely stores SMB credentials (username, password) keyed by the host.
 *
 * Everything is lazy: the first Keystore round trip happens on whichever
 * background thread touches the store, never on the main thread at construction.
 */
class SmbCredentialsStore(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val sharedPrefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            "edendale_smb_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * Null means "no credentials were given" so callers fall back to guest.
     * A blank username used to be stored and then replayed as an NTLM login,
     * which servers reject. An empty password with a real username is valid.
     */
    fun getCredentials(host: String): Pair<String, String>? {
        val user = sharedPrefs.getString("${host}_user", null)?.takeIf { it.isNotBlank() }
            ?: return null
        return user to sharedPrefs.getString("${host}_pass", null).orEmpty()
    }

    suspend fun saveCredentials(host: String, user: String, pass: String) {
        withContext(Dispatchers.IO) {
            val editor = sharedPrefs.edit()
            if (user.isBlank()) {
                editor.remove("${host}_user").remove("${host}_pass")
            } else {
                editor.putString("${host}_user", user).putString("${host}_pass", pass)
            }
            editor.commit()
        }
    }

    suspend fun removeCredentials(host: String) {
        withContext(Dispatchers.IO) {
            sharedPrefs.edit()
                .remove("${host}_user")
                .remove("${host}_pass")
                .commit()
        }
    }
}
