package com.babasama.edendale.android.data

import com.babasama.edendale.connectors.ConnectorEntry
import com.babasama.edendale.connectors.GoogleDriveConnector
import com.babasama.edendale.connectors.MediaConnector
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.ProviderHttp
import com.babasama.edendale.connectors.RewritingHttp
import com.babasama.edendale.connectors.SourceUrl
import com.babasama.edendale.connectors.seededTokens
import com.babasama.edendale.oauth.CloudAccount
import com.babasama.edendale.remote.LocalHttpServer
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A scan walks the linked folder, not the connector's root: for a cloud
 * account or an S3 bucket the root is everything the account holds, and
 * linking one Drive folder used to import all of Drive.
 */
class SourceScanWalkTest {

    private val servers = mutableListOf<LocalHttpServer>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private fun json(body: String) =
        LocalHttpServer.Response(200, mapOf("Content-Type" to "application/json"), body.toByteArray())

    private val account = CloudAccount(
        kind = MediaSourceKind.GOOGLE_DRIVE,
        subject = "110169484474386276334",
        email = "me@example.com",
        displayName = "Me",
        refreshToken = "refresh",
        scopes = emptyList(),
    )

    @Test
    fun `a linked Drive folder imports only what is inside it`() = runBlocking {
        val server = LocalHttpServer({ request: LocalHttpServer.Request ->
            when (request.path) {
                "/drive/v3/about" -> json("""{"user":{"emailAddress":"me@example.com"}}""")
                "/drive/v3/files" -> when (request.queryValue("q")?.let(SourceUrl::decode)) {
                    "'films' in parents and trashed = false" -> json(
                        """{"files":[{"id":"s1","name":"Sequels","mimeType":"application/vnd.google-apps.folder"},
                        {"id":"h1","name":"Heat (1995).mkv","mimeType":"video/x-matroska","size":"7"}]}""",
                    )
                    "'s1' in parents and trashed = false" -> json(
                        """{"files":[{"id":"t2","name":"Terminator 2 (1991).mp4","mimeType":"video/mp4","size":"9"}]}""",
                    )
                    // Anything else (My Drive, Shared with me) holds a video that mustn't be imported.
                    else -> json("""{"files":[{"id":"x1","name":"Elsewhere (2001).mkv","mimeType":"video/x-matroska","size":"1"}]}""")
                }
                else -> LocalHttpServer.Response(404)
            }
        }).also { servers += it }
        val http = RewritingHttp("www.googleapis.com", server.port)
        val provider = ProviderHttp(MediaSourceKind.GOOGLE_DRIVE, account.key, seededTokens(account, http), http, listOf(10, 10, 10))
        val connector = GoogleDriveConnector(account, provider)
        val folder = LibraryFolderEntity(
            treeUri = connector.folderUrl("films", "Films"),
            displayName = "Films",
            addedAtEpochMillis = 0,
            kind = MediaSourceKind.GOOGLE_DRIVE.raw,
            accountKey = account.key,
        )

        val enumeration = SourceScanRules.enumerate(connector, folder)

        assertTrue(enumeration.complete)
        assertEquals(listOf("Heat (1995).mkv", "Terminator 2 (1991).mp4"), enumeration.videos.map { it.name })
        val queries = server.requests.filter { it.path == "/drive/v3/files" }.map { it.queryValue("q")?.let(SourceUrl::decode) }
        assertEquals(listOf("'films' in parents and trashed = false", "'s1' in parents and trashed = false"), queries)
    }

    @Test
    fun `the walk starts at the linked prefix of an account-wide connector`() = runBlocking {
        val listed = mutableListOf<String>()
        val bucket = object : MediaConnector {
            override val kind = MediaSourceKind.S3
            override val root = "s3://key@host/bucket/"
            override suspend fun validate() = Unit
            override suspend fun list(directory: String): List<ConnectorEntry> {
                listed += directory
                return when (directory) {
                    root -> listOf(
                        ConnectorEntry("Films", "${root}Films/", isDirectory = true),
                        ConnectorEntry("Shows", "${root}Shows/", isDirectory = true),
                    )
                    "${root}Films/" -> listOf(ConnectorEntry("Heat (1995).mkv", "${root}Films/Heat%20(1995).mkv", isDirectory = false))
                    else -> listOf(ConnectorEntry("Severance S01E01.mkv", "${root}Shows/Severance%20S01E01.mkv", isDirectory = false))
                }
            }
        }
        val folder = LibraryFolderEntity(treeUri = "${bucket.root}Films/", displayName = "Films", addedAtEpochMillis = 0, kind = "s3")

        val enumeration = SourceScanRules.enumerate(bucket, folder)

        assertEquals(listOf("Heat (1995).mkv"), enumeration.videos.map { it.name })
        assertEquals(listOf("${bucket.root}Films/"), listed)
    }
}
