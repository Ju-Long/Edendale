package com.babasama.edendale.oauth

import com.babasama.edendale.connectors.ConnectorException
import com.babasama.edendale.connectors.ConnectorFailure
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.remote.LocalHttpServer
import com.babasama.edendale.remote.OkHttpRemoteHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * H.6.T1, Apple's `OAuthTests`: the RFC 7636 PKCE vector, each provider's
 * authorization URL, token and device-code responses (RFC 8628: pending,
 * slow_down, declined, expired), the single-flight refresh, and no token in
 * any string that could reach a log or URL. Token endpoints are a local
 * server; no real credential is used.
 */
class OAuthTest {

    // MARK: - PKCE

    @Test
    fun `matches the RFC 7636 Appendix B vector`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun `verifiers are long, unreserved, and unique`() {
        val verifier = Pkce.makeVerifier()
        assertEquals(43, verifier.length)
        assertTrue(verifier.all { it.isLetterOrDigit() || it in "-._~" }, verifier)
        assertNotEquals(verifier, Pkce.makeVerifier())
        assertNotEquals(Pkce.makeState(), Pkce.makeState())
    }

    @Test
    fun `decodes JWT claims`() {
        val payload = Pkce.base64Url("""{"sub":"110169484474386276334","email":"me@example.com"}""".toByteArray())
        val claims = decodeJwtClaims("eyJhbGciOiJSUzI1NiJ9.$payload.signature")!!
        assertEquals("110169484474386276334", claims.claim("sub"))
        assertEquals("me@example.com", claims.claim("email"))
        assertNull(decodeJwtClaims("not-a-jwt"))
    }

    // MARK: - Authorization URLs

    private fun query(url: String) = url.substringAfter('?').split('&').associate {
        it.substringBefore('=') to com.babasama.edendale.connectors.SourceUrl.decode(it.substringAfter('='))
    }

    @Test
    fun `builds each provider's authorization URL`() {
        val google = CloudProviders.configuration(MediaSourceKind.GOOGLE_DRIVE, "1234-abc.apps.googleusercontent.com")!!
        assertEquals("com.googleusercontent.apps.1234-abc", google.callbackScheme)
        assertEquals("com.googleusercontent.apps.1234-abc:/oauth2redirect", google.redirectUri)
        assertNull(google.deviceAuthorizationEndpoint)
        val url = OAuthClient(google, OkHttpRemoteHttp()).authorizationUrl("state-1", "challenge-1")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        val values = query(url)
        assertEquals("1234-abc.apps.googleusercontent.com", values["client_id"])
        assertEquals("code", values["response_type"])
        assertEquals(google.redirectUri, values["redirect_uri"])
        assertEquals("openid email https://www.googleapis.com/auth/drive.readonly", values["scope"])
        assertEquals("state-1", values["state"])
        assertEquals("challenge-1", values["code_challenge"])
        assertEquals("S256", values["code_challenge_method"])
        assertEquals("select_account", values["prompt"])
        // No client secret, ever.
        assertNull(values["client_secret"])

        val dropbox = CloudProviders.configuration(MediaSourceKind.DROPBOX, "appkey")!!
        assertTrue("token_access_type=offline" in OAuthClient(dropbox, OkHttpRemoteHttp()).authorizationUrl("s", "c"))
        assertEquals("db-appkey://2/token", dropbox.redirectUri)

        val microsoft = CloudProviders.configuration(MediaSourceKind.ONE_DRIVE, "guid")!!
        assertEquals("https://login.microsoftonline.com/common/oauth2/v2.0/devicecode", microsoft.deviceAuthorizationEndpoint)
        assertEquals(listOf("Files.Read", "User.Read", "offline_access"), microsoft.scopes)
        assertEquals("msauth://com.babasama.edendale/VzSiQcXRmi2kyjzcA%2BmYLEtbGVs%3D", microsoft.redirectUri)
        // The registered redirect already holds %2B and %3D; they must reach Entra encoded again.
        assertEquals(microsoft.redirectUri, query(OAuthClient(microsoft, OkHttpRemoteHttp()).authorizationUrl("s", "c"))["redirect_uri"])
        assertTrue(CloudProviders.supportsDeviceCode(MediaSourceKind.ONE_DRIVE))
        assertTrue(!CloudProviders.supportsDeviceCode(MediaSourceKind.GOOGLE_DRIVE))
        // An empty client ID hides the provider.
        assertNull(CloudProviders.configuration(MediaSourceKind.DROPBOX, " "))
        assertNull(CloudProviders.configuration(MediaSourceKind.WEBDAV, "x"))
    }

    @Test
    fun `reads the authorization code only with the matching state`() {
        val kind = MediaSourceKind.ONE_DRIVE
        assertEquals("abc", OAuthClient.authorizationCode("test.edendale://auth?code=abc&state=s1", "s1", kind))
        assertEquals(OAuthFailure.StateMismatch, assertFailsWith<OAuthException> {
            OAuthClient.authorizationCode("test.edendale://auth?code=abc&state=s1", "other", kind)
        }.failure)
        assertEquals(OAuthFailure.AuthorizationDenied, assertFailsWith<OAuthException> {
            OAuthClient.authorizationCode("test.edendale://auth?error=access_denied&state=s1", "s1", kind)
        }.failure)
        assertEquals(OAuthFailure.MissingAuthorizationCode, assertFailsWith<OAuthException> {
            OAuthClient.authorizationCode("test.edendale://auth?state=s1", "s1", kind)
        }.failure)
        assertEquals(OAuthFailure.Server("server_error", "Try later"), assertFailsWith<OAuthException> {
            OAuthClient.authorizationCode("test.edendale://auth?error=server_error&error_description=Try+later&state=s1", "s1", kind)
        }.failure)
    }

    @Test
    fun `form encoding escapes reserved characters`() {
        assertEquals("a%20b=x%2By%26z%3D1%2F2", OAuthClient.formEncode(listOf("a b" to "x+y&z=1/2")))
    }

    // MARK: - Token endpoint

    private val servers = mutableListOf<LocalHttpServer>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private fun server(handler: (LocalHttpServer.Request) -> LocalHttpServer.Response) =
        LocalHttpServer(handler).also { servers += it }

    private fun json(body: String, status: Int = 200) =
        LocalHttpServer.Response(status, mapOf("Content-Type" to "application/json"), body.toByteArray())

    private fun LocalHttpServer.Request.form(): Map<String, String> = String(body).split('&').associate {
        it.substringBefore('=') to com.babasama.edendale.connectors.SourceUrl.decode(it.substringAfter('='))
    }

    private fun configuration(port: Int, kind: MediaSourceKind = MediaSourceKind.ONE_DRIVE) = OAuthConfiguration(
        kind = kind,
        clientId = "client-123",
        authorizationEndpoint = "http://127.0.0.1:$port/authorize",
        tokenEndpoint = "http://127.0.0.1:$port/token",
        deviceAuthorizationEndpoint = "http://127.0.0.1:$port/devicecode",
        redirectUri = "test.edendale://auth",
        callbackScheme = "test.edendale",
        scopes = listOf("Files.Read", "offline_access"),
        sendsScopeToTokenEndpoint = true,
    )

    @Test
    fun `exchanges the code with the verifier`() = runBlocking {
        val server = server {
            json("""{"access_token":"access-1","token_type":"Bearer","expires_in":3600,"refresh_token":"refresh-1","scope":"Files.Read offline_access"}""")
        }
        val tokens = OAuthClient(configuration(server.port), OkHttpRemoteHttp()).exchange("the-code", "the-verifier")
        assertEquals("access-1", tokens.accessToken)
        assertEquals("refresh-1", tokens.refreshToken)
        assertEquals(3600, tokens.expiresInSeconds)
        assertEquals(listOf("Files.Read", "offline_access"), tokens.grantedScopes)

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("application/x-www-form-urlencoded", request.header("Content-Type"))
        val form = request.form()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-code", form["code"])
        assertEquals("the-verifier", form["code_verifier"])
        assertEquals("client-123", form["client_id"])
        assertEquals("test.edendale://auth", form["redirect_uri"])
        assertEquals("Files.Read offline_access", form["scope"])
        assertNull(form["client_secret"])
    }

    @Test
    fun `an invalid grant means sign in again`() {
        val server = server { json("""{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""", 400) }
        val error = assertFailsWith<OAuthException> {
            runBlocking { OAuthClient(configuration(server.port), OkHttpRemoteHttp()).refresh("old") }
        }
        assertEquals(OAuthFailure.InvalidGrant, error.failure)
        val form = server.requests.single().form()
        assertEquals("refresh_token", form["grant_type"])
        assertEquals("old", form["refresh_token"])
    }

    // MARK: - Device code

    @Test
    fun `starts a device authorization`() = runBlocking {
        val server = server {
            json("""{"device_code":"device-1","user_code":"ABCD-EFGH","verification_uri":"https://microsoft.com/devicelogin","expires_in":900,"interval":5,"message":"To sign in…"}""")
        }
        val authorization = OAuthClient(configuration(server.port), OkHttpRemoteHttp()).startDeviceAuthorization(nowMillis = 1_000_000)
        assertEquals("device-1", authorization.deviceCode)
        assertEquals("ABCD-EFGH", authorization.userCode)
        assertEquals("https://microsoft.com/devicelogin", authorization.verificationUri)
        // Microsoft sends no verification_uri_complete.
        assertNull(authorization.verificationUriComplete)
        assertEquals(1_900_000L, authorization.expiresAtMillis)
        assertEquals(5, authorization.intervalSeconds)
        val form = server.requests.single().form()
        assertEquals("client-123", form["client_id"])
        assertEquals("Files.Read offline_access", form["scope"])
        assertTrue("device-1" !in authorization.toString())
    }

    @Test
    fun `accepts Google's verification URL spelling`() = runBlocking {
        val server = server { json("""{"device_code":"d","user_code":"U","verification_url":"https://www.google.com/device","expires_in":1800}""") }
        val authorization = OAuthClient(configuration(server.port), OkHttpRemoteHttp()).startDeviceAuthorization()
        assertEquals("https://www.google.com/device", authorization.verificationUri)
        assertEquals(5, authorization.intervalSeconds)
    }

    private fun authorization(expiresAt: Long = Long.MAX_VALUE, interval: Int = 5) =
        DeviceAuthorization("device-1", "U", "https://example.com", null, expiresAt, interval)

    @Test
    fun `polls through pending and slow_down to approval`() = runBlocking {
        val polls = AtomicInteger()
        val server = server {
            when (polls.incrementAndGet()) {
                1 -> json("""{"error":"authorization_pending"}""", 400)
                2 -> json("""{"error":"slow_down"}""", 400)
                else -> json("""{"access_token":"a","refresh_token":"r","expires_in":3600}""")
            }
        }
        val waits = mutableListOf<Int>()
        val tokens = OAuthClient(configuration(server.port), OkHttpRemoteHttp()).waitForDeviceAuthorization(
            authorization(),
            sleepSeconds = { waits += it },
        )
        assertEquals("r", tokens.refreshToken)
        assertEquals(3, server.requests.size)
        assertTrue(server.requests.all { it.form()["grant_type"] == "urn:ietf:params:oauth:grant-type:device_code" })
        assertTrue(server.requests.all { it.form()["device_code"] == "device-1" })
        // slow_down adds five seconds to the interval.
        assertEquals(listOf(5, 5, 10), waits)
    }

    @Test
    fun `reports a declined or expired code`() {
        val responses = AtomicInteger()
        val server = server {
            if (responses.incrementAndGet() == 1) json("""{"error":"authorization_declined"}""", 400) else json("""{"error":"expired_token"}""", 400)
        }
        val client = OAuthClient(configuration(server.port), OkHttpRemoteHttp())
        assertEquals(OAuthFailure.AuthorizationDenied, assertFailsWith<OAuthException> { runBlocking { client.pollDeviceAuthorization(authorization()) } }.failure)
        assertEquals(OAuthFailure.DeviceCodeExpired, assertFailsWith<OAuthException> { runBlocking { client.pollDeviceAuthorization(authorization()) } }.failure)
    }

    @Test
    fun `stops polling once the code has expired`() {
        val server = server { json("""{"error":"authorization_pending"}""", 400) }
        var clock = 0L
        val error = assertFailsWith<OAuthException> {
            runBlocking {
                OAuthClient(configuration(server.port), OkHttpRemoteHttp()).waitForDeviceAuthorization(
                    authorization(expiresAt = 12_000),
                    nowMillis = { clock },
                    sleepSeconds = { clock += it * 1_000L },
                )
            }
        }
        assertEquals(OAuthFailure.DeviceCodeExpired, error.failure)
        // Polled at 5 s and 10 s; at 15 s the code had expired.
        assertEquals(2, server.requests.size)
    }

    // MARK: - Accounts and tokens

    private class MemoryStore : SecretStore {
        val values = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun get(key: String) = values[key]
        override fun set(key: String, value: String) {
            values[key] = value
        }
        override fun remove(key: String) {
            values.remove(key)
        }
        override fun keys(): Set<String> = values.keys.toSet()
    }

    private fun account(refreshToken: String = "refresh-1") = CloudAccount(
        kind = MediaSourceKind.ONE_DRIVE,
        subject = "user-1",
        email = "me@example.com",
        displayName = null,
        refreshToken = refreshToken,
        scopes = emptyList(),
        driveId = "drive-1",
    )

    private fun tokens(port: Int, vault: CloudAccountVault, now: () -> Long = System::currentTimeMillis) =
        CloudTokenProvider(vault, OkHttpRemoteHttp(), { configuration(port) }, now)

    @Test
    fun `the vault keeps accounts by kind and key`() {
        val vault = CloudAccountVault(MemoryStore())
        val drive = account().copy(kind = MediaSourceKind.GOOGLE_DRIVE, subject = "s1", email = "b@example.com", refreshToken = "r1")
        val dropbox = account().copy(kind = MediaSourceKind.DROPBOX, subject = "s1", email = "a@example.com", refreshToken = "r2")
        vault.save(drive)
        vault.save(dropbox)
        assertEquals(listOf(MediaSourceKind.DROPBOX, MediaSourceKind.GOOGLE_DRIVE), vault.all().map { it.kind })
        assertEquals(listOf("r1"), vault.accounts(MediaSourceKind.GOOGLE_DRIVE).map { it.refreshToken })
        assertNull(vault.account(MediaSourceKind.DROPBOX, drive.key))
        assertEquals(drive, vault.account(MediaSourceKind.GOOGLE_DRIVE, drive.key))
        vault.remove(MediaSourceKind.GOOGLE_DRIVE, drive.key)
        assertEquals(listOf(MediaSourceKind.DROPBOX), vault.all().map { it.kind })
        assertTrue(CloudAccountVault.entryKey(MediaSourceKind.DROPBOX, dropbox.key).startsWith("cloud-account-dropbox-"))
    }

    @Test
    fun `concurrent requests share one refresh`() = runBlocking {
        val refreshes = AtomicInteger()
        val server = server {
            val count = refreshes.incrementAndGet()
            Thread.sleep(200)
            json("""{"access_token":"access-$count","expires_in":3600}""")
        }
        val vault = CloudAccountVault(MemoryStore()).apply { save(account()) }
        val provider = tokens(server.port, vault)
        val results = (0 until 10).map {
            async(kotlinx.coroutines.Dispatchers.IO) { provider.accessToken(MediaSourceKind.ONE_DRIVE, account().key) }
        }.awaitAll()
        assertTrue(results.all { it == "access-1" }, results.toString())
        assertEquals(1, refreshes.get())
        // Cached afterwards.
        assertEquals("access-1", provider.accessToken(MediaSourceKind.ONE_DRIVE, account().key))
        assertEquals(1, refreshes.get())
    }

    @Test
    fun `a rejected token is refreshed once and rotated tokens are kept`() = runBlocking {
        val refreshes = AtomicInteger()
        val server = server {
            val count = refreshes.incrementAndGet()
            json("""{"access_token":"access-$count","refresh_token":"refresh-${count + 1}","expires_in":3600}""")
        }
        val vault = CloudAccountVault(MemoryStore()).apply { save(account()) }
        val provider = tokens(server.port, vault)
        val key = account().key
        val first = provider.accessToken(MediaSourceKind.ONE_DRIVE, key)
        val second = provider.accessToken(MediaSourceKind.ONE_DRIVE, key, rejecting = first)
        assertEquals("access-2", second)
        // A caller holding the already-replaced token doesn't refresh again.
        assertEquals("access-2", provider.accessToken(MediaSourceKind.ONE_DRIVE, key, rejecting = first))
        assertEquals(2, refreshes.get())
        // Microsoft rotates refresh tokens; the newest one is stored.
        assertEquals("refresh-3", vault.account(MediaSourceKind.ONE_DRIVE, key)?.refreshToken)
        assertEquals("refresh-2", server.requests.last().form()["refresh_token"])
    }

    @Test
    fun `a revoked grant means sign in again`() {
        val server = server { json("""{"error":"invalid_grant"}""", 400) }
        val vault = CloudAccountVault(MemoryStore()).apply { save(account()) }
        val provider = tokens(server.port, vault)
        val revoked = assertFailsWith<ConnectorException> { runBlocking { provider.accessToken(MediaSourceKind.ONE_DRIVE, account().key) } }
        assertEquals(ConnectorFailure.SignInRequired(MediaSourceKind.ONE_DRIVE), revoked.failure)
        // So does an account that isn't there at all.
        val missing = assertFailsWith<ConnectorException> { runBlocking { provider.accessToken(MediaSourceKind.ONE_DRIVE, "missing") } }
        assertEquals(ConnectorFailure.SignInRequired(MediaSourceKind.ONE_DRIVE), missing.failure)
    }

    @Test
    fun `expiring tokens are refreshed early`() = runBlocking {
        val refreshes = AtomicInteger()
        val server = server { json("""{"access_token":"access-${refreshes.incrementAndGet()}","expires_in":60}""") }
        val vault = CloudAccountVault(MemoryStore()).apply { save(account()) }
        val provider = tokens(server.port, vault)
        // A 60-second token is inside the two-minute refresh margin.
        provider.accessToken(MediaSourceKind.ONE_DRIVE, account().key)
        provider.accessToken(MediaSourceKind.ONE_DRIVE, account().key)
        assertEquals(2, refreshes.get())
    }

    @Test
    fun `no token reaches a string that could be logged`() {
        val secrets = listOf("access-secret", "refresh-secret", "id-secret")
        val strings = listOf(
            OAuthTokens("access-secret", 3600, "refresh-secret", idToken = "id-secret").toString(),
            account(refreshToken = "refresh-secret").toString(),
            OAuthException(MediaSourceKind.DROPBOX, OAuthFailure.InvalidGrant).message.orEmpty(),
        )
        strings.forEach { text -> secrets.forEach { secret -> assertTrue(secret !in text, text) } }
    }

    @Test
    fun `identities come from each provider's account endpoint`() = runBlocking {
        val server = server { request ->
            when {
                request.path.endsWith("/me/drive") -> json("""{"id":"drive-9"}""")
                else -> json("""{"id":"user-9","displayName":"Me","userPrincipalName":"me@contoso.com"}""")
            }
        }
        // Point Graph at the local server by rewriting the host in a thin transport.
        val local = OkHttpRemoteHttp()
        val http = com.babasama.edendale.remote.RemoteHttp { request, limit ->
            local.newCall(
                com.babasama.edendale.remote.RemoteRequest(
                    request.url.replace("https://graph.microsoft.com", "http://127.0.0.1:${server.port}"),
                    request.headers,
                    request.method,
                    request.body,
                    request.contentType,
                ),
                limit,
            )
        }
        val identity = CloudProviders.identity(MediaSourceKind.ONE_DRIVE, OAuthTokens("t"), http)
        assertEquals(CloudProviders.Identity("user-9", "me@contoso.com", "Me", "drive-9"), identity)
        assertTrue(server.requests.all { it.header("Authorization") == "Bearer t" })

        val payload = Pkce.base64Url("""{"sub":"g-1","email":"g@example.com","name":"G"}""".toByteArray())
        assertEquals(
            CloudProviders.Identity("g-1", "g@example.com", "G"),
            CloudProviders.identity(MediaSourceKind.GOOGLE_DRIVE, OAuthTokens("t", idToken = "h.$payload.s"), http),
        )
    }
}
