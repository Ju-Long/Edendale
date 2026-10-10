package com.babasama.edendale.android.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.S3Configuration
import com.babasama.edendale.connectors.SourceUrl
import com.babasama.edendale.remote.ServerLogin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Logins for servers other than SMB (H.3: WebDAV; SFTP and S3 follow), one
 * per kind, host, and port, encrypted and excluded from backup and device
 * transfer (D8). SMB keeps its own store ([SmbCredentialsStore]) and key
 * names. Blocking Keystore and disk access: call from a background thread.
 */
class ServerLoginStore(context: Context) {
    private val appContext = context.applicationContext

    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(kind: MediaSourceKind, host: String, port: Int?): ServerLogin? =
        preferences.getString(key(kind, host, port), null)?.let(::decode)

    /** The login for the server an item or folder URL names. */
    fun forUrl(kind: MediaSourceKind, url: String): ServerLogin? {
        val host = SourceUrl.credentialHost(url) ?: return null
        return get(kind, host, SourceUrl.port(url))
    }

    /** Saves [login]; a guest login (no user, no password) removes the saved one. */
    fun save(kind: MediaSourceKind, host: String, port: Int?, login: ServerLogin) {
        val editor = preferences.edit()
        if (login.isGuest) {
            editor.remove(key(kind, host, port))
        } else {
            val stored = buildJsonObject {
                put("user", JsonPrimitive(login.user))
                put("password", JsonPrimitive(login.password))
            }
            editor.putString(key(kind, host, port), stored.toString())
        }
        editor.commit()
    }

    /**
     * An S3 bucket's key pair and location (H.5), stored under its account
     * key, which is also its item URLs' host.
     */
    fun getS3(account: String): Pair<ServerLogin, S3Configuration>? {
        val stored = preferences.getString(key(MediaSourceKind.S3, account, null), null) ?: return null
        val login = decode(stored) ?: return null
        val configuration = decodeS3(stored) ?: return null
        return login to configuration
    }

    fun saveS3(account: String, login: ServerLogin, configuration: S3Configuration) {
        val stored = buildJsonObject {
            put("user", JsonPrimitive(login.user))
            put("password", JsonPrimitive(login.password))
            put(
                "s3",
                buildJsonObject {
                    put("endpoint", JsonPrimitive(configuration.endpoint))
                    put("region", JsonPrimitive(configuration.region))
                    put("bucket", JsonPrimitive(configuration.bucket))
                    put("pathStyle", JsonPrimitive(configuration.usesPathStyle))
                },
            )
        }
        preferences.edit().putString(key(MediaSourceKind.S3, account, null), stored.toString()).commit()
    }

    fun remove(kind: MediaSourceKind, host: String, port: Int?) {
        preferences.edit().remove(key(kind, host, port)).commit()
    }

    /** Every saved login, without its password, for Settings → Accounts. */
    fun all(): List<SavedServerLogin> = preferences.all.mapNotNull { (key, value) ->
        val parts = key.split(SEPARATOR)
        if (parts.size != 3) return@mapNotNull null
        val kind = MediaSourceKind.fromRaw(parts[0]) ?: return@mapNotNull null
        val text = value as? String ?: return@mapNotNull null
        val stored = decode(text) ?: return@mapNotNull null
        // An S3 login reads as its bucket and endpoint; the access key ID stays out of view.
        val detail = decodeS3(text)?.let { "${it.bucket} @ ${it.endpoint.substringAfter("://").substringBefore('/')}" }
        SavedServerLogin(kind, parts[1], parts[2].toIntOrNull(), stored.user, detail)
    }.sortedWith(compareBy({ it.kind.raw }, { it.host }))

    private fun decodeS3(stored: String): S3Configuration? = runCatching {
        val fields = Json.parseToJsonElement(stored).jsonObject["s3"]?.jsonObject ?: return null
        S3Configuration(
            endpoint = fields.getValue("endpoint").jsonPrimitive.content,
            region = fields.getValue("region").jsonPrimitive.content,
            bucket = fields.getValue("bucket").jsonPrimitive.content,
            usesPathStyle = fields.getValue("pathStyle").jsonPrimitive.content.toBoolean(),
        )
    }.getOrNull()

    private fun decode(stored: String): ServerLogin? = runCatching {
        val fields = Json.parseToJsonElement(stored).jsonObject
        ServerLogin(fields.getValue("user").jsonPrimitive.content, fields.getValue("password").jsonPrimitive.content)
    }.getOrNull()

    companion object {
        /** Listed in backup_rules.xml and data_extraction_rules.xml. */
        const val FILE_NAME = "edendale_server_logins"
        private const val SEPARATOR = "|"

        internal fun key(kind: MediaSourceKind, host: String, port: Int?) =
            listOf(kind.raw, host.lowercase(), port?.toString().orEmpty()).joinToString(SEPARATOR)
    }
}

/** One saved server login; the password never leaves the store. */
data class SavedServerLogin(
    val kind: MediaSourceKind,
    /** The server host, or the account key for S3. */
    val host: String,
    val port: Int?,
    val user: String,
    /** S3 only: `bucket @ endpoint host`. */
    val detail: String? = null,
) {
    /** `host` or `host:port`, as the viewer typed it. */
    val address: String get() = if (port != null) "$host:$port" else host
}

/**
 * How many linked sources use each saved login (D.4, H.3): sources of the
 * same kind on the same host and port. Pure.
 */
internal fun serverLoginUsage(logins: List<SavedServerLogin>, folders: List<LibraryFolderEntity>): Map<SavedServerLogin, Int> =
    logins.associateWith { login ->
        folders.count { folder ->
            SourceScanRules.kindOf(folder) == login.kind &&
                SourceUrl.credentialHost(folder.treeUri) == login.host.lowercase() &&
                SourceUrl.port(folder.treeUri) == login.port
        }
    }
