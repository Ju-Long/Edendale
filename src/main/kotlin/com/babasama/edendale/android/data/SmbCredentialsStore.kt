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

    private companion object {
        const val USER_SUFFIX = "_user"
    }

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

    /**
     * Every saved SMB login (host and user), for Settings → Accounts. Blocking
     * Keystore and disk access: call from a background dispatcher.
     */
    fun savedLogins(): List<SavedSmbLogin> =
        sharedPrefs.all.entries
            .filter { it.key.endsWith(USER_SUFFIX) && (it.value as? String)?.isNotBlank() == true }
            .map { SavedSmbLogin(host = it.key.removeSuffix(USER_SUFFIX), user = it.value as String) }
            .sortedBy { it.host.lowercase() }

    suspend fun removeCredentials(host: String) {
        withContext(Dispatchers.IO) {
            sharedPrefs.edit()
                .remove("${host}_user")
                .remove("${host}_pass")
                .commit()
        }
    }
}

/** One saved SMB login; the password never leaves the store. */
data class SavedSmbLogin(val host: String, val user: String)

/**
 * How many linked sources use each saved login (D.4): SMB sources whose URL
 * host matches, ignoring case, the way imports key the store. Pure.
 */
internal fun smbLoginUsage(logins: List<SavedSmbLogin>, sourceUris: List<String>): Map<SavedSmbLogin, Int> {
    val hosts = sourceUris.mapNotNull(::smbHostOf).map { it.lowercase() }
    return logins.associateWith { login -> hosts.count { it == login.host.lowercase() } }
}

/** The host of an `smb://host/share/…` URL, or null for anything else. Pure (no android.net.Uri). */
internal fun smbHostOf(url: String): String? {
    if (!url.startsWith("smb://", ignoreCase = true)) return null
    val authority = url.substring("smb://".length).substringBefore('/').substringAfterLast('@')
    val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    return host.takeIf { it.isNotBlank() }
}
