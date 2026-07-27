package com.babasama.edendale.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.babasama.edendale.tmdb.TmdbSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class StoredTmdbSession(
    val session: TmdbSession,
    val accountLabel: String?,
)

/** Keeps the personal TMDB session encrypted with an Android Keystore key. */
class TmdbSessionStore(context: Context) {
    private val appContext = context.applicationContext

    private val masterKey by lazy {
        MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val preferences: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            PREFERENCES_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    suspend fun load(): StoredTmdbSession? = withContext(Dispatchers.IO) {
        val sessionId = preferences.getString(KEY_SESSION_ID, null) ?: return@withContext null
        val accountId = preferences.getInt(KEY_ACCOUNT_ID, -1).takeIf { it > 0 }
            ?: return@withContext null
        StoredTmdbSession(
            session = TmdbSession(sessionId = sessionId, accountId = accountId),
            accountLabel = preferences.getString(KEY_ACCOUNT_LABEL, null),
        )
    }

    suspend fun save(session: TmdbSession, accountLabel: String?) {
        withContext(Dispatchers.IO) {
            preferences.edit()
                .putString(KEY_SESSION_ID, session.sessionId)
                .putInt(KEY_ACCOUNT_ID, session.accountId)
                .apply {
                    if (accountLabel.isNullOrBlank()) remove(KEY_ACCOUNT_LABEL)
                    else putString(KEY_ACCOUNT_LABEL, accountLabel)
                }
                .commit()
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) { preferences.edit().clear().commit() }
    }

    private companion object {
        const val PREFERENCES_NAME = "edendale_tmdb_session"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_ACCOUNT_ID = "account_id"
        const val KEY_ACCOUNT_LABEL = "account_label"
    }
}
