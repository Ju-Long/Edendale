package com.babasama.edendale.oauth

import com.babasama.edendale.connectors.ConnectorException
import com.babasama.edendale.connectors.ConnectorFailure
import com.babasama.edendale.connectors.GoogleDriveConnector
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.SourceUrl
import com.babasama.edendale.oauth.OAuthClient.Companion.string
import com.babasama.edendale.remote.RemoteHttp
import com.babasama.edendale.remote.RemoteRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-provider OAuth settings, identity lookup, and revocation (H.6,
 * Apple's `CloudProviders`). Client IDs and app keys aren't secrets for PKCE
 * clients; they come from `secrets.json` and an empty one hides the provider.
 */
object CloudProviders {
    /**
     * Only the files the viewer picks in Google's picker (H.9.4). The picker
     * flow allows no other scope next to it, so `openid` and `email` are
     * gone too.
     */
    const val GOOGLE_DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    val GOOGLE_SCOPES = listOf(GOOGLE_DRIVE_FILE_SCOPE)

    /**
     * What Google's picker offers: the video types Drive assigns, listed one
     * by one because the `video/` wildcard leaves the picker empty. Without
     * a filter the picker shows folders but every file type too, which the
     * owner turned down (2026-10-18).
     */
    val GOOGLE_PICKER_MIME_TYPES = listOf(
        "video/mp4", "video/x-matroska", "video/quicktime", "video/x-msvideo",
        "video/webm", "video/mp2t", "video/mpeg", "video/x-m4v", "video/x-ms-wmv",
        "video/3gpp",
    )

    /**
     * Opens Google's picker inside the sign-in page. Files only: a picked
     * folder never grants what's inside it, so `allow_folder_selection` is
     * never sent. `prompt` must include `consent` for the picker, and
     * `consent` also makes every run return a refresh token.
     */
    val GOOGLE_PICKER_PARAMETERS = mapOf(
        "access_type" to "offline",
        "prompt" to "consent select_account",
        "trigger_onepick" to "true",
        "allow_multiple" to "true",
        "mimetypes" to GOOGLE_PICKER_MIME_TYPES.joinToString(","),
    )

    /** Reruns the picker for an account already linked: no account chooser, with the account's email as the hint. */
    fun googleChooseMoreParameters(loginHint: String?): Map<String, String> = buildMap {
        put("prompt", "consent")
        loginHint?.takeIf { it.isNotBlank() }?.let { put("login_hint", it) }
    }

    /** Whether [scopes] let Edendale read picked Drive files; an older build's Google accounts had `drive.readonly` (D20). */
    fun hasDriveFileAccess(scopes: List<String>): Boolean = GOOGLE_DRIVE_FILE_SCOPE in scopes

    val ONE_DRIVE_SCOPES = listOf("Files.Read", "User.Read", "offline_access")
    val DROPBOX_SCOPES = listOf("files.metadata.read", "files.content.read", "account_info.read")

    /** Personal and work or school Microsoft accounts both sign in through the `common` authority. */
    const val MICROSOFT_AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0/"

    /**
     * The Microsoft redirect the owner registered for the Entra app's Android platform: the package
     * name and a signing certificate's SHA-1 in base64, URL-encoded. Without MSAL nothing compares
     * the hash with the app's signature, so every build uses this one string.
     */
    const val MICROSOFT_REDIRECT_URI = "msauth://com.babasama.edendale/VzSiQcXRmi2kyjzcA%2BmYLEtbGVs%3D"

    /**
     * The Google redirect (D9): the Android OAuth client's custom URI scheme, which is the package
     * name, with the scheme turned on in the client's advanced settings. The manifest routes it to
     * `OAuthRedirectActivity`.
     */
    const val GOOGLE_REDIRECT_SCHEME = "com.babasama.edendale"
    const val GOOGLE_REDIRECT_URI = "$GOOGLE_REDIRECT_SCHEME:/oauth2redirect"

    fun configuration(kind: MediaSourceKind, clientId: String): OAuthConfiguration? {
        if (clientId.isBlank()) return null
        return when (kind) {
            MediaSourceKind.GOOGLE_DRIVE -> OAuthConfiguration(
                kind = kind,
                clientId = clientId,
                authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
                tokenEndpoint = "https://oauth2.googleapis.com/token",
                deviceAuthorizationEndpoint = null,
                redirectUri = GOOGLE_REDIRECT_URI,
                callbackScheme = GOOGLE_REDIRECT_SCHEME,
                scopes = GOOGLE_SCOPES,
                additionalAuthorizationParameters = GOOGLE_PICKER_PARAMETERS,
            )
            MediaSourceKind.ONE_DRIVE -> OAuthConfiguration(
                kind = kind,
                clientId = clientId,
                authorizationEndpoint = MICROSOFT_AUTHORITY + "authorize",
                tokenEndpoint = MICROSOFT_AUTHORITY + "token",
                deviceAuthorizationEndpoint = MICROSOFT_AUTHORITY + "devicecode",
                redirectUri = MICROSOFT_REDIRECT_URI,
                callbackScheme = "msauth",
                scopes = ONE_DRIVE_SCOPES,
                additionalAuthorizationParameters = mapOf("prompt" to "select_account"),
                sendsScopeToTokenEndpoint = true,
            )
            MediaSourceKind.DROPBOX -> {
                // The scheme Dropbox's own SDKs use for mobile apps.
                val scheme = "db-$clientId"
                OAuthConfiguration(
                    kind = kind,
                    clientId = clientId,
                    authorizationEndpoint = "https://www.dropbox.com/oauth2/authorize",
                    tokenEndpoint = "https://api.dropboxapi.com/oauth2/token",
                    deviceAuthorizationEndpoint = null,
                    redirectUri = "$scheme://2/token",
                    callbackScheme = scheme,
                    scopes = DROPBOX_SCOPES,
                    additionalAuthorizationParameters = mapOf("token_access_type" to "offline"),
                )
            }
            else -> null
        }
    }

    /**
     * Whether a TV can sign in on its own. Choosing Drive videos needs
     * Google's picker, which only a phone or tablet opens, and Dropbox has
     * no device flow, so both come to the TV through the handoff (I.2).
     */
    fun supportsDeviceCode(kind: MediaSourceKind): Boolean = kind == MediaSourceKind.ONE_DRIVE

    /** Who a fresh token belongs to. */
    data class Identity(val subject: String, val email: String?, val displayName: String?, val driveId: String? = null)

    /**
     * Drive `about.get` (its `permissionId` is the same for every Edendale
     * client, and `drive.file` may read it), Microsoft Graph `/me` and
     * `/me/drive`, or Dropbox `get_current_account`.
     */
    suspend fun identity(kind: MediaSourceKind, tokens: OAuthTokens, http: RemoteHttp): Identity = withContext(Dispatchers.IO) {
        when (kind) {
            MediaSourceKind.GOOGLE_DRIVE -> {
                val about = getJson(http, kind, GoogleDriveConnector.url("about", listOf("fields" to "user(permissionId,emailAddress,displayName)")), tokens.accessToken)
                val user = about["user"] as? JsonObject ?: throw OAuthException(kind, OAuthFailure.MalformedResponse)
                val subject = user.string("permissionId")?.takeIf { it.isNotEmpty() } ?: throw OAuthException(kind, OAuthFailure.MalformedResponse)
                Identity(subject, user.string("emailAddress"), user.string("displayName"))
            }
            MediaSourceKind.ONE_DRIVE -> {
                val me = getJson(http, kind, "https://graph.microsoft.com/v1.0/me?\$select=id,displayName,mail,userPrincipalName", tokens.accessToken)
                val drive = getJson(http, kind, "https://graph.microsoft.com/v1.0/me/drive?\$select=id", tokens.accessToken)
                Identity(
                    subject = me.string("id") ?: throw OAuthException(kind, OAuthFailure.MalformedResponse),
                    email = me.string("mail") ?: me.string("userPrincipalName"),
                    displayName = me.string("displayName"),
                    driveId = drive.string("id"),
                )
            }
            MediaSourceKind.DROPBOX -> {
                val request = RemoteRequest(
                    url = "https://api.dropboxapi.com/2/users/get_current_account",
                    headers = mapOf("Authorization" to "Bearer ${tokens.accessToken}"),
                    method = "POST",
                    body = ByteArray(0),
                )
                val response = execute(http, kind, request)
                val account = OAuthClient.parse(response) ?: throw OAuthException(kind, OAuthFailure.MalformedResponse)
                Identity(
                    subject = account.string("account_id") ?: throw OAuthException(kind, OAuthFailure.MalformedResponse),
                    email = account.string("email"),
                    displayName = (account["name"] as? JsonObject)?.string("display_name"),
                )
            }
            else -> throw OAuthException(kind, OAuthFailure.NotConfigured)
        }
    }

    /**
     * Whether Edendale can end its own access: Google and Dropbox can; a
     * Microsoft grant is removed on the account's app permissions page. Only
     * an explicit sign-out revokes: Google ends the whole grant, so revoking a
     * replaced refresh token after Choose More Videos would cut off the new
     * one and every TV's too.
     */
    fun supportsRevocation(kind: MediaSourceKind) = kind == MediaSourceKind.GOOGLE_DRIVE || kind == MediaSourceKind.DROPBOX

    /** Where the viewer manages app access themselves. */
    fun accessManagementUrl(kind: MediaSourceKind): String? = when (kind) {
        MediaSourceKind.GOOGLE_DRIVE -> "https://myaccount.google.com/connections"
        MediaSourceKind.ONE_DRIVE -> "https://account.live.com/consent/Manage"
        MediaSourceKind.DROPBOX -> "https://www.dropbox.com/account/connected_apps"
        else -> null
    }

    /** Best effort: ends the grant behind [refreshToken] (Google) or [accessToken] (Dropbox, which also disables its refresh token). */
    suspend fun revoke(kind: MediaSourceKind, refreshToken: String, accessToken: String?, http: RemoteHttp) = withContext(Dispatchers.IO) {
        val request = when (kind) {
            MediaSourceKind.GOOGLE_DRIVE -> RemoteRequest(
                url = "https://oauth2.googleapis.com/revoke",
                method = "POST",
                body = OAuthClient.formEncode(listOf("token" to refreshToken)).toByteArray(),
                contentType = "application/x-www-form-urlencoded",
            )
            MediaSourceKind.DROPBOX -> RemoteRequest(
                url = "https://api.dropboxapi.com/2/auth/token/revoke",
                headers = mapOf("Authorization" to "Bearer ${accessToken ?: return@withContext}"),
                method = "POST",
                body = ByteArray(0),
            )
            else -> return@withContext
        }
        runCatching { http.newCall(request, bodyLimit = 4_096).execute() }
    }

    private fun getJson(http: RemoteHttp, kind: MediaSourceKind, url: String, token: String): JsonObject {
        val request = RemoteRequest(url, mapOf("Authorization" to "Bearer $token", "Accept" to "application/json"))
        return OAuthClient.parse(execute(http, kind, request)) ?: throw OAuthException(kind, OAuthFailure.MalformedResponse)
    }

    private fun execute(http: RemoteHttp, kind: MediaSourceKind, request: RemoteRequest): ByteArray {
        val response = try {
            http.newCall(request, bodyLimit = 1 shl 20).execute()
        } catch (error: IOException) {
            throw OAuthException(kind, OAuthFailure.Http(0), error)
        }
        if (response.status !in 200..299) throw OAuthException(kind, OAuthFailure.Http(response.status))
        return response.body
    }
}

/**
 * A linked cloud account (H.6). The refresh token lives only in the
 * encrypted store; [toString] never shows it.
 */
data class CloudAccount(
    val kind: MediaSourceKind,
    /** Drive's `permissionId` (an older build stored Google's `sub`; D20), the Microsoft user `id`, or Dropbox's `account_id`. */
    val subject: String,
    val email: String?,
    val displayName: String?,
    val refreshToken: String,
    val scopes: List<String>,
    /** OneDrive: the user's default drive. */
    val driveId: String? = null,
) {
    /** The account key: item URLs' host and the store key. */
    val key: String get() = SourceUrl.accountKey(kind, subject)

    /** The email, else the name, for rows and source labels. */
    val label: String get() = email ?: displayName ?: kind.raw

    override fun toString() = "CloudAccount(${kind.raw}, $label)"
}

/**
 * A finished sign-in (H.6), with the Drive files picked in Google's picker
 * along the way (empty for other providers, and when the viewer picked
 * nothing).
 */
data class CloudSignIn(val account: CloudAccount, val pickedFileIds: List<String> = emptyList()) {
    companion object {
        /**
         * Stores the account a sign-in produced: checks the granted scopes,
         * asks who the token belongs to, and saves the account with its
         * refresh token. Blocking vault access runs on the IO dispatcher.
         */
        suspend fun store(
            kind: MediaSourceKind,
            configuration: OAuthConfiguration,
            response: OAuthTokens,
            http: RemoteHttp,
            vault: CloudAccountVault,
            tokens: CloudTokenProvider,
        ): CloudAccount {
            val scopes = grantedScopes(kind, configuration, response)
            val refreshToken = response.refreshToken?.takeIf { it.isNotEmpty() } ?: throw OAuthException(kind, OAuthFailure.MalformedResponse)
            val identity = CloudProviders.identity(kind, response, http)
            val account = CloudAccount(
                kind = kind,
                subject = identity.subject,
                email = identity.email,
                displayName = identity.displayName,
                refreshToken = refreshToken,
                scopes = scopes,
                driveId = identity.driveId,
            )
            withContext(Dispatchers.IO) { vault.save(account) }
            tokens.store(response, account)
            return account
        }

        /**
         * Keeps the tokens a Choose More Videos run returned for [account]
         * (H.9.6): the newest refresh token replaces the stored one, which is
         * never revoked, because Google would end the whole grant. Throws if
         * they belong to another Google account; nothing is stored then.
         */
        suspend fun storeChosenVideos(
            account: CloudAccount,
            configuration: OAuthConfiguration,
            response: OAuthTokens,
            http: RemoteHttp,
            vault: CloudAccountVault,
            tokens: CloudTokenProvider,
        ): CloudAccount {
            val kind = account.kind
            val scopes = grantedScopes(kind, configuration, response)
            val identity = CloudProviders.identity(kind, response, http)
            if (identity.subject != account.subject) {
                throw OAuthException(kind, OAuthFailure.DifferentAccount(identity.email ?: identity.displayName ?: kind.raw, account.label))
            }
            val updated = withContext(Dispatchers.IO) {
                val current = vault.account(kind, account.key) ?: account
                current.copy(
                    refreshToken = response.refreshToken?.takeIf { it.isNotEmpty() } ?: current.refreshToken,
                    scopes = scopes,
                    email = identity.email ?: current.email,
                    displayName = identity.displayName ?: current.displayName,
                ).also(vault::save)
            }
            tokens.store(response, updated)
            return updated
        }

        /** The scopes a token response granted. Google's consent page can leave Drive access unticked. */
        fun grantedScopes(kind: MediaSourceKind, configuration: OAuthConfiguration, response: OAuthTokens): List<String> {
            val scopes = response.grantedScopes ?: configuration.scopes
            if (kind == MediaSourceKind.GOOGLE_DRIVE && !CloudProviders.hasDriveFileAccess(scopes)) {
                throw OAuthException(kind, OAuthFailure.DriveAccessNotGranted)
            }
            return scopes
        }
    }
}

/** Somewhere to keep secrets: an encrypted store on Android, memory in tests. */
interface SecretStore {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

/**
 * Linked accounts, one store entry each (`cloud-account-<kind>-<accountKey>`:
 * provider, subject, email, name, refresh token, scopes), device-local and
 * excluded from backup (D8).
 */
class CloudAccountVault(private val store: SecretStore) {

    fun save(account: CloudAccount) {
        val entry = buildJsonObject {
            put("kind", JsonPrimitive(account.kind.raw))
            put("subject", JsonPrimitive(account.subject))
            put("email", account.email?.let(::JsonPrimitive) ?: JsonNull)
            put("displayName", account.displayName?.let(::JsonPrimitive) ?: JsonNull)
            put("refreshToken", JsonPrimitive(account.refreshToken))
            put("scopes", JsonArray(account.scopes.map(::JsonPrimitive)))
            put("driveId", account.driveId?.let(::JsonPrimitive) ?: JsonNull)
        }
        store.set(entryKey(account.kind, account.key), entry.toString())
    }

    fun account(kind: MediaSourceKind, key: String): CloudAccount? = store.get(entryKey(kind, key))?.let(::decode)

    fun accounts(kind: MediaSourceKind): List<CloudAccount> = all().filter { it.kind == kind }

    /** Every account, by provider and then label. */
    fun all(): List<CloudAccount> = store.keys()
        .filter { it.startsWith(PREFIX) }
        .mapNotNull { store.get(it)?.let(::decode) }
        .sortedWith(compareBy({ it.kind.raw }, { it.label.lowercase() }))

    fun remove(kind: MediaSourceKind, key: String) = store.remove(entryKey(kind, key))

    private fun decode(stored: String): CloudAccount? = runCatching {
        val fields = Json.parseToJsonElement(stored).jsonObject
        CloudAccount(
            kind = MediaSourceKind.fromRaw(fields.getValue("kind").jsonPrimitive.content) ?: return null,
            subject = fields.getValue("subject").jsonPrimitive.content,
            email = fields["email"]?.jsonPrimitive?.contentOrNull,
            displayName = fields["displayName"]?.jsonPrimitive?.contentOrNull,
            refreshToken = fields.getValue("refreshToken").jsonPrimitive.content,
            scopes = fields["scopes"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            driveId = fields["driveId"]?.jsonPrimitive?.contentOrNull,
        )
    }.getOrNull()

    companion object {
        private const val PREFIX = "cloud-account-"

        fun entryKey(kind: MediaSourceKind, key: String) = "$PREFIX${kind.raw}-$key"
    }
}

/**
 * Access tokens for linked accounts (H.6, Apple's `CloudTokenProvider`).
 * Tokens live only in memory; the refresh token stays in the vault. At most
 * one refresh runs per account and every caller waiting gets its result, so
 * a burst of 401s from parallel listing or streaming triggers one refresh.
 */
class CloudTokenProvider(
    private val vault: CloudAccountVault,
    private val http: RemoteHttp,
    private val configuration: (MediaSourceKind) -> OAuthConfiguration?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class Token(val value: String, val expiresAtMillis: Long)

    private val cache = ConcurrentHashMap<String, Token>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * A valid access token for the account. Pass the token a provider just
     * refused (HTTP 401) as [rejecting]: it is refreshed, unless another
     * caller has already replaced it.
     */
    suspend fun accessToken(kind: MediaSourceKind, accountKey: String, rejecting: String? = null): String {
        val cacheKey = "${kind.raw}:$accountKey"
        return locks.getOrPut(cacheKey) { Mutex() }.withLock {
            cache[cacheKey]?.takeIf { it.value != rejecting && it.expiresAtMillis - nowMillis() > EXPIRY_MARGIN_MILLIS }
                ?.let { return@withLock it.value }
            refresh(kind, accountKey).also { cache[cacheKey] = it }.value
        }
    }

    /** Seeds the cache with the token a sign-in just produced. */
    fun store(tokens: OAuthTokens, account: CloudAccount) {
        cache["${account.kind.raw}:${account.key}"] = Token(tokens.accessToken, nowMillis() + (tokens.expiresInSeconds ?: 3600) * 1_000L)
    }

    /** The cached token, if any, for a sign-out's revocation. */
    fun cachedToken(kind: MediaSourceKind, accountKey: String): String? = cache["${kind.raw}:$accountKey"]?.value

    /** Drops the cached token after a sign-out. */
    fun forget(kind: MediaSourceKind, accountKey: String) {
        cache.remove("${kind.raw}:$accountKey")
    }

    private suspend fun refresh(kind: MediaSourceKind, accountKey: String): Token {
        val account = vault.account(kind, accountKey) ?: throw ConnectorException(ConnectorFailure.SignInRequired(kind))
        val settings = configuration(kind) ?: throw ConnectorException(ConnectorFailure.NotConfigured(kind))
        val response = try {
            OAuthClient(settings, http).refresh(account.refreshToken)
        } catch (error: OAuthException) {
            if (error.failure == OAuthFailure.InvalidGrant) throw ConnectorException(ConnectorFailure.SignInRequired(kind), error)
            throw error
        }
        // Microsoft rotates refresh tokens; keep the newest one.
        response.refreshToken?.takeIf { it != account.refreshToken }?.let { rotated ->
            runCatching { vault.save(account.copy(refreshToken = rotated)) }
        }
        return Token(response.accessToken, nowMillis() + (response.expiresInSeconds ?: 3600) * 1_000L)
    }

    private companion object {
        /** Refreshes this long before a token's stated expiry. */
        const val EXPIRY_MARGIN_MILLIS = 120_000L
    }
}
