package com.babasama.edendale.android.oauth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.babasama.edendale.CloudSecrets
import com.babasama.edendale.android.MainActivity
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.oauth.CloudAccountVault
import com.babasama.edendale.oauth.CloudProviders
import com.babasama.edendale.oauth.CloudTokenProvider
import com.babasama.edendale.oauth.OAuthClient
import com.babasama.edendale.oauth.OAuthConfiguration
import com.babasama.edendale.oauth.OAuthException
import com.babasama.edendale.oauth.OAuthFailure
import com.babasama.edendale.oauth.Pkce
import com.babasama.edendale.oauth.SecretStore
import com.babasama.edendale.remote.OkHttpRemoteHttp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Linked cloud accounts on this device (H.6): the encrypted vault, the token
 * provider every connector and byte source asks, and sign-in and sign-out.
 * One per process ([EdendaleApplication.cloudAccounts]).
 */
class CloudAccounts(context: Context) {
    private val appContext = context.applicationContext
    val http = OkHttpRemoteHttp()
    val vault = CloudAccountVault(EncryptedSecretStore(appContext, FILE_NAME))
    val tokens = CloudTokenProvider(vault, http, ::configuration)

    /** The provider's OAuth settings, or null when this build has no client ID for it. */
    fun configuration(kind: MediaSourceKind): OAuthConfiguration? = CloudProviders.configuration(kind, clientId(kind))

    /**
     * Whether Link Source offers the provider: it has a client ID, and for
     * Google Drive the owner has chosen how Android signs in (D9).
     */
    fun isOffered(kind: MediaSourceKind): Boolean = kind != MediaSourceKind.GOOGLE_DRIVE && configuration(kind) != null

    private fun clientId(kind: MediaSourceKind): String = when (kind) {
        MediaSourceKind.GOOGLE_DRIVE -> CloudSecrets.googleClientId
        MediaSourceKind.ONE_DRIVE -> CloudSecrets.microsoftClientId
        MediaSourceKind.DROPBOX -> CloudSecrets.dropboxAppKey
        else -> ""
    }

    /**
     * Signs in through a Custom Tab (the authorization code with PKCE),
     * then stores the account. Throws [OAuthException]; cancelling the
     * calling coroutine abandons the sign-in.
     */
    suspend fun signIn(activity: Activity, kind: MediaSourceKind): CloudAccount {
        val configuration = configuration(kind) ?: throw OAuthException(kind, OAuthFailure.NotConfigured)
        val client = OAuthClient(configuration, http)
        val verifier = Pkce.makeVerifier()
        val state = Pkce.makeState()
        val callback = OAuthRedirects.await(state) {
            CustomTabsIntent.Builder().setShowTitle(true).build()
                .launchUrl(activity, Uri.parse(client.authorizationUrl(state, Pkce.challenge(verifier))))
        }
        val code = OAuthClient.authorizationCode(callback, state, kind)
        val response = client.exchange(code, verifier)
        val identity = CloudProviders.identity(kind, response, http)
        val refreshToken = response.refreshToken ?: throw OAuthException(kind, OAuthFailure.MalformedResponse)
        val account = CloudAccount(
            kind = kind,
            subject = identity.subject,
            email = identity.email,
            displayName = identity.displayName,
            refreshToken = refreshToken,
            scopes = response.grantedScopes ?: configuration.scopes,
            driveId = identity.driveId,
        )
        withContext(Dispatchers.IO) { vault.save(account) }
        tokens.store(response, account)
        return account
    }

    /**
     * Removes the account from this device. With [revoke], also ends
     * Edendale's access at the provider where it allows that (Google,
     * Dropbox). Its sources stay and ask to sign in again.
     */
    suspend fun signOut(account: CloudAccount, revoke: Boolean) {
        val accessToken = tokens.cachedToken(account.kind, account.key)
        withContext(Dispatchers.IO) { vault.remove(account.kind, account.key) }
        tokens.forget(account.kind, account.key)
        if (revoke && CloudProviders.supportsRevocation(account.kind)) {
            CloudProviders.revoke(account.kind, account.refreshToken, accessToken, http)
        }
    }

    companion object {
        /** Listed in backup_rules.xml and data_extraction_rules.xml. */
        const val FILE_NAME = "edendale_cloud_accounts"
    }
}

/** [SecretStore] over EncryptedSharedPreferences. Blocking Keystore and disk access: call from a background thread. */
class EncryptedSecretStore(context: Context, fileName: String) : SecretStore {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            appContext,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(key: String): String? = preferences.getString(key, null)
    override fun set(key: String, value: String) {
        preferences.edit().putString(key, value).commit()
    }
    override fun remove(key: String) {
        preferences.edit().remove(key).commit()
    }
    override fun keys(): Set<String> = preferences.all.keys
}

/**
 * Sign-ins waiting for their redirect, by `state`. The redirect activity
 * delivers what the browser sent; a redirect nobody is waiting for is
 * dropped.
 */
object OAuthRedirects {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Runs [launch] and waits for the redirect carrying [state]. */
    suspend fun await(state: String, launch: () -> Unit): String {
        val deferred = CompletableDeferred<String>()
        pending[state] = deferred
        try {
            launch()
            return deferred.await()
        } finally {
            pending.remove(state)
        }
    }

    /** Hands [uri] to the sign-in whose state it carries. */
    fun deliver(uri: Uri): Boolean {
        val state = uri.getQueryParameter("state") ?: return false
        return pending[state]?.complete(uri.toString()) == true
    }
}

/**
 * Where the providers redirect after sign-in (H.6.3): hands the redirect to
 * the waiting sign-in, then brings the app back over the Custom Tab.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let(OAuthRedirects::deliver)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
