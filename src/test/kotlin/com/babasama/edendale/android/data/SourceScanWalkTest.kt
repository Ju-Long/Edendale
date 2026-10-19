package com.babasama.edendale.android.data

import com.babasama.edendale.connectors.ConnectorEntry
import com.babasama.edendale.connectors.GoogleDriveConnector
import com.babasama.edendale.connectors.GoogleDriveHiddenItems
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
 * A scan walks the linked folder, not the connector's root: for an S3
 * bucket the root is everything the account holds, and linking one folder
 * used to import all of it. A Google Drive source is "Videos you chose"
 * (H.9.4): one listing of the picked files, without the ones removed on this
 * device (D21).
 */
class SourceScanWalkTest {

    private val servers = mutableListOf<LocalHttpServer>()

    @AfterTest
    fun tearDown() = servers.forEach { it.close() }

    private fun json(body: String) =
        LocalHttpServer.Response(200, mapOf("Content-Type" to "application/json"), body.toByteArray())

    private val account = CloudAccount(
        kind = MediaSourceKind.GOOGLE_DRIVE,
        subject = "01234567890123456789",
        email = "me@example.com",
        displayName = "Me",
        refreshToken = "refresh",
        scopes = listOf("https://www.googleapis.com/auth/drive.file"),
    )

    @Test
    fun `a Google Drive source scans the picked files once and skips the hidden ones`() = runBlocking {
        val server = LocalHttpServer({ request: LocalHttpServer.Request ->
            when (request.path) {
                "/drive/v3/about" -> json("""{"user":{"emailAddress":"me@example.com"}}""")
                "/drive/v3/files" -> json(
                    """{"files":[{"id":"s1","name":"Sequels","mimeType":"application/vnd.google-apps.folder"},
                    {"id":"h1","name":"Heat (1995).mkv","mimeType":"video/x-matroska","size":"7"},
                    {"id":"t2","name":"Terminator 2 (1991).mp4","mimeType":"video/mp4","size":"9"},
                    {"id":"a3","name":"Alien (1979).mkv","mimeType":"video/x-matroska","size":"3"}]}""",
                )
                else -> LocalHttpServer.Response(404)
            }
        }).also { servers += it }
        val http = RewritingHttp("www.googleapis.com", server.port)
        val provider = ProviderHttp(MediaSourceKind.GOOGLE_DRIVE, account.key, seededTokens(account, http), http, listOf(10, 10, 10))
        val connector = GoogleDriveConnector(account, provider)
        val folder = LibraryFolderEntity(
            treeUri = GoogleDriveConnector.chosenFolderUrl(account.key),
            displayName = "Videos you chose",
            addedAtEpochMillis = 0,
            kind = MediaSourceKind.GOOGLE_DRIVE.raw,
            accountKey = account.key,
        )

        // Terminator 2 was removed from the library on this device.
        val enumeration = SourceScanRules.enumerate(connector, folder, hidden = setOf("t2"))

        assertTrue(enumeration.complete)
        assertEquals(listOf("Alien (1979).mkv", "Heat (1995).mkv"), enumeration.videos.map { it.name })
        // One listing of everything picked; the picked folder isn't walked, since its contents were never granted.
        val queries = server.requests.filter { it.path == "/drive/v3/files" }.map { it.queryValue("q")?.let(SourceUrl::decode) }
        assertEquals(listOf("trashed = false"), queries)

        // With nothing hidden, every picked video comes back.
        assertEquals(3, SourceScanRules.enumerate(connector, folder).videos.size)
    }

    @Test
    fun `hidden Drive IDs come from removed items and go when picked again`() {
        val source = GoogleDriveConnector.chosenFolderUrl(account.key)
        val heat = SourceUrl.accountItem(MediaSourceKind.GOOGLE_DRIVE, account.key, listOf("h1"), "Heat (1995).mkv")
        val alien = SourceUrl.accountItem(MediaSourceKind.GOOGLE_DRIVE, account.key, listOf("a3"), "Alien (1979).mkv")
        // Only Drive item URLs hide anything; the source's own URL and other kinds don't.
        val hidden = GoogleDriveHiddenItems.hiding(emptySet(), listOf(heat, source, "smb://nas/films/Heat.mkv"))
        assertEquals(setOf("h1"), hidden)
        assertEquals(setOf("h1", "a3"), GoogleDriveHiddenItems.hiding(hidden, listOf(alien, heat)))
        // Picking a removed video again brings it back.
        assertEquals(setOf("a3"), GoogleDriveHiddenItems.unhiding(setOf("h1", "a3"), listOf("h1", "new")))

        val entries = listOf(
            ConnectorEntry("Heat (1995).mkv", heat, isDirectory = false),
            ConnectorEntry("Alien (1979).mkv", alien, isDirectory = false),
            ConnectorEntry("Heat.mkv", "smb://nas/films/Heat.mkv", isDirectory = false),
        )
        assertEquals(listOf("Alien (1979).mkv", "Heat.mkv"), GoogleDriveHiddenItems.visible(entries, setOf("h1")).map { it.name })
        assertEquals(entries, GoogleDriveHiddenItems.visible(entries, emptySet()))
    }

    @Test
    fun `Remove from Library is offered only where removing lasts`() {
        // A Drive source's scans skip hidden IDs, and a file added on its own is never rescanned.
        assertTrue(SourceScanRules.removalLasts(GoogleDriveConnector.chosenFolderUrl(account.key)))
        assertTrue(SourceScanRules.removalLasts(null))
        // Every other source's next scan would bring the file straight back.
        listOf(
            "content://com.android.externalstorage.documents/tree/primary%3AMovies",
            "smb://nas/films/",
            "davs://cloud.example.com/remote.php/dav/files/me/Films/",
            SourceUrl.accountItem(MediaSourceKind.GOOGLE_DRIVE, account.key, listOf("m0"), "Movies"),
            SourceUrl.accountItem(MediaSourceKind.ONE_DRIVE, "acct", listOf("drive", "item"), "Films"),
        ).forEach { assertTrue(!SourceScanRules.removalLasts(it), it) }
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
