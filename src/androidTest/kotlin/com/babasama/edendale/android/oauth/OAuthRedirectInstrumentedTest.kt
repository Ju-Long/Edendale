package com.babasama.edendale.android.oauth

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.oauth.CloudAccountVault
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * H.6.3: a provider's redirect reaches the waiting sign-in through the
 * manifest's redirect activity, and the account vault round-trips through
 * the encrypted store on a device.
 */
@RunWith(AndroidJUnit4::class)
class OAuthRedirectInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun open(uri: String) = context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    @Test
    fun aMicrosoftRedirectReachesTheWaitingSignIn() = runBlocking {
        val callback = withTimeout(10_000) {
            OAuthRedirects.await("state-ms") { open("msauth.com.babasama.edendale://auth?code=abc&state=state-ms") }
        }
        assertEquals("msauth.com.babasama.edendale://auth?code=abc&state=state-ms", callback)
    }

    @Test
    fun aDropboxRedirectUsesTheBuildsScheme() = runBlocking {
        // This build's secrets.json has no app key, so the scheme is db-unset.
        val callback = withTimeout(10_000) {
            OAuthRedirects.await("state-db") { open("db-unset://2/token?code=xyz&state=state-db") }
        }
        assertEquals("db-unset://2/token?code=xyz&state=state-db", callback)
    }

    @Test
    fun aRedirectNobodyWaitsForIsDropped() {
        assertFalse(OAuthRedirects.deliver(Uri.parse("msauth.com.babasama.edendale://auth?code=abc&state=nobody")))
        assertFalse(OAuthRedirects.deliver(Uri.parse("msauth.com.babasama.edendale://auth?code=abc")))
    }

    @Test
    fun theVaultRoundTripsThroughTheEncryptedStore() {
        val store = EncryptedSecretStore(context, "edendale_cloud_accounts_test")
        val vault = CloudAccountVault(store)
        val account = CloudAccount(
            kind = MediaSourceKind.ONE_DRIVE,
            subject = "user-1",
            email = "me@example.com",
            displayName = "Me",
            refreshToken = "refresh-1",
            scopes = listOf("Files.Read"),
            driveId = "drive-1",
        )
        try {
            vault.save(account)
            assertEquals(account, CloudAccountVault(EncryptedSecretStore(context, "edendale_cloud_accounts_test")).account(account.kind, account.key))
        } finally {
            vault.remove(account.kind, account.key)
        }
        assertNull(vault.account(account.kind, account.key))
    }
}
