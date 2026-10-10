package com.babasama.edendale.connectors

import com.babasama.edendale.domain.MediaParser
import com.babasama.edendale.domain.ParsedMedia
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * H.1.T1, Apple's `ConnectorTests` (SourceURLTests and ConnectorWalkTests):
 * canonical source URLs round-trip for every kind, account keys match
 * Apple's, and the default enumeration walks trees the way imports rely on.
 */
class ConnectorTest {

    // MARK: - Account keys

    @Test
    fun `account keys hash the kind and subject`() {
        // Apple's expected strings: the first 32 hex digits of SHA-256("kind:subject").
        assertEquals("fc33299258dcfba4155a09b5ec6ea6c9", SourceUrl.accountKey(MediaSourceKind.GOOGLE_DRIVE, "110169484474386276334"))
        assertEquals("efaa7701612dd41d3d0a949ca736bce4", SourceUrl.accountKey(MediaSourceKind.ONE_DRIVE, "48d31887-5fad-4d73-a9f5-3c356e68a038"))
        assertEquals("7e1576915dd882c6cd3cb73befcb3ddd", SourceUrl.accountKey(MediaSourceKind.DROPBOX, "dbid:AAH4f99T0taONIb-OurWxbNQ6ywGRopQngc"))
        assertEquals(
            "a8e9cfc310a2f9a91163345e524e29b4",
            SourceUrl.s3AccountKey("https://S3.us-east-1.amazonaws.com/", "examplebucket", "AKIAIOSFODNN7EXAMPLE"),
        )
        // The same subject on another provider is another account.
        assertNotEquals(SourceUrl.accountKey(MediaSourceKind.GOOGLE_DRIVE, "x"), SourceUrl.accountKey(MediaSourceKind.DROPBOX, "x"))
    }

    // MARK: - Account item URLs

    private val names = listOf(
        "The.Matrix.1999.mkv",
        "Heat (1995) #1: Director's Cut? 50%.mkv",
        "Amélie.2001.mkv",
        "Slash/In/Name.S01E02.mkv",
        "  spaced  .mp4",
    )

    @Test
    fun `account item URLs round trip`() {
        val account = SourceUrl.accountKey(MediaSourceKind.GOOGLE_DRIVE, "subject")
        for (name in names) {
            val drive = SourceUrl.accountItem(MediaSourceKind.GOOGLE_DRIVE, account, listOf("1a2B-c_3"), name)
            assertTrue(drive.startsWith("gdrive://$account/"), drive)
            val parsed = SourceUrl.parseAccountItem(drive)!!
            assertEquals(MediaSourceKind.GOOGLE_DRIVE, parsed.kind)
            assertEquals(account, parsed.account)
            assertEquals(listOf("1a2B-c_3"), parsed.ids)
            assertEquals(name, parsed.name)
            // The filename parser reads the real file name.
            assertEquals(name, SourceUrl.fileName(drive))
            // Nothing in a name can add a segment, a query, or a fragment.
            assertFalse(drive.substringAfter("$account/").let { ' ' in it || '?' in it || '#' in it }, drive)

            val oneDrive = SourceUrl.accountItem(MediaSourceKind.ONE_DRIVE, account, listOf("b!drive", "01ITEM"), name)
            assertEquals(listOf("b!drive", "01ITEM"), SourceUrl.parseAccountItem(oneDrive)?.ids)
            assertEquals(name, SourceUrl.parseAccountItem(oneDrive)?.name)

            val dropbox = SourceUrl.accountItem(MediaSourceKind.DROPBOX, account, listOf("id:a4ayc_80_OEAAAAAAAAAXw"), name)
            assertTrue("/id%3Aa4ayc_80_OEAAAAAAAAAXw/" in dropbox, dropbox)
            assertEquals(listOf("id:a4ayc_80_OEAAAAAAAAAXw"), SourceUrl.parseAccountItem(dropbox)?.ids)
        }
    }

    @Test
    fun `provider URLs classify by file name`() {
        val account = SourceUrl.accountKey(MediaSourceKind.DROPBOX, "s")
        val movie = SourceUrl.accountItem(MediaSourceKind.DROPBOX, account, listOf("id:1"), "The.Matrix.1999.mkv")
        val parsedMovie = MediaParser.parse(SourceUrl.fileName(movie)!!)
        assertTrue(parsedMovie is ParsedMedia.Movie)
        assertEquals("The Matrix", parsedMovie.title)
        assertEquals(1999, parsedMovie.year)

        val episode = SourceUrl.accountItem(MediaSourceKind.ONE_DRIVE, account, listOf("d", "i"), "Show.Name.S01E02.mkv")
        val parsedEpisode = MediaParser.parse(SourceUrl.fileName(episode)!!)
        assertTrue(parsedEpisode is ParsedMedia.Episode)
        assertEquals("Show Name", parsedEpisode.showName)
        assertEquals(1, parsedEpisode.season)
        assertEquals(2, parsedEpisode.episode)
    }

    @Test
    fun `folder URLs carry listing hints`() {
        val url = SourceUrl.accountItem(
            MediaSourceKind.GOOGLE_DRIVE, "abc", listOf("folder1"), "Team Films",
            query = listOf("drive" to "0AB"),
        )
        val parsed = SourceUrl.parseAccountItem(url)!!
        assertEquals("Team Films", parsed.name)
        assertEquals("0AB", parsed.queryValue("drive"))
        assertNull(parsed.queryValue("other"))
    }

    @Test
    fun `malformed account URLs are rejected`() {
        assertNull(SourceUrl.parseAccountItem("gdrive://abc/onlyname.mkv"))
        assertNull(SourceUrl.parseAccountItem("onedrive://abc/d/Name.mkv"))
        assertNull(SourceUrl.parseAccountItem("smb://nas/share/Name.mkv"))
        assertNull(SourceUrl.parseAccountItem("dropbox:///id/Name.mkv"))
        assertNull(SourceUrl.parseAccountItem("not a url"))
        assertFailsWith<IllegalArgumentException> {
            SourceUrl.accountItem(MediaSourceKind.SMB, "a", listOf("b"), "c.mkv")
        }
    }

    // MARK: - S3 and server URLs

    @Test
    fun `S3 URLs round trip keys and prefixes`() {
        val obj = SourceUrl.s3(account = "acct", bucket = "films", key = "Movies/Heat (1995)/Heat 1995.mkv")
        assertEquals("s3://acct/films/Movies/Heat%20(1995)/Heat%201995.mkv", obj)
        val parsed = SourceUrl.parseS3(obj)!!
        assertEquals("films", parsed.bucket)
        assertEquals("Movies/Heat (1995)/Heat 1995.mkv", parsed.key)
        assertFalse(parsed.isPrefix)
        assertEquals("Heat 1995.mkv", SourceUrl.fileName(obj))

        val prefix = SourceUrl.s3(account = "acct", bucket = "films", key = "Movies/")
        assertEquals("Movies/", SourceUrl.parseS3(prefix)?.key)
        assertEquals(true, SourceUrl.parseS3(prefix)?.isPrefix)
        val root = SourceUrl.s3(account = "acct", bucket = "films", key = "")
        assertEquals("s3://acct/films/", root)
        assertEquals("", SourceUrl.parseS3(root)?.key)
        assertNull(SourceUrl.parseS3("s3://acct/"))
        assertNull(SourceUrl.parseS3("gdrive://acct/films/x"))
    }

    @Test
    fun `server URLs encode each segment`() {
        val url = SourceUrl.server(
            scheme = "sftp", host = "nas.local", port = 2222,
            pathSegments = listOf("home", "me", "Films & TV", "Heat #1.mkv"),
        )!!
        assertEquals("sftp://nas.local:2222/home/me/Films%20&%20TV/Heat%20%231.mkv", url)
        assertEquals(listOf("home", "me", "Films & TV", "Heat #1.mkv"), SourceUrl.pathSegments(url))
        assertEquals(2222, SourceUrl.port(url))
        assertEquals("nas.local", SourceUrl.credentialHost(url))
        val folder = SourceUrl.server(scheme = "nfs", host = "nas", pathSegments = listOf("export", "video"), isDirectory = true)
        assertEquals("nfs://nas/export/video/", folder)
        assertNull(SourceUrl.port(folder!!))
        assertNull(SourceUrl.server(scheme = "nfs", host = " ", pathSegments = emptyList()))
        // Account kinds store credentials under the account key, which is the host.
        assertEquals("acct", SourceUrl.credentialHost("s3://acct/films/Heat.mkv"))
    }

    @Test
    fun `schemes map to persisted kinds`() {
        assertEquals(MediaSourceKind.SMB, MediaSourceKind.fromScheme("smb2"))
        assertEquals(MediaSourceKind.WEBDAV, MediaSourceKind.fromScheme("DAV"))
        assertEquals(MediaSourceKind.WEBDAV, MediaSourceKind.fromScheme("davs"))
        assertEquals(MediaSourceKind.GOOGLE_DRIVE, MediaSourceKind.fromScheme("gdrive"))
        assertNull(MediaSourceKind.fromScheme("https"))
        // Raw values are persisted: never rename one.
        assertEquals(
            listOf("local", "smb", "nfs", "sftp", "webdav", "s3", "gdrive", "onedrive", "dropbox"),
            MediaSourceKind.entries.map { it.raw },
        )
        assertEquals(
            listOf(MediaSourceKind.GOOGLE_DRIVE, MediaSourceKind.ONE_DRIVE, MediaSourceKind.DROPBOX),
            MediaSourceKind.entries.filter { it.isCloudAccount },
        )
        assertEquals(
            listOf(MediaSourceKind.SMB, MediaSourceKind.SFTP, MediaSourceKind.WEBDAV, MediaSourceKind.S3),
            MediaSourceKind.entries.filter { it.usesServerLogin },
        )
        assertEquals(
            listOf(MediaSourceKind.WEBDAV, MediaSourceKind.S3, MediaSourceKind.GOOGLE_DRIVE, MediaSourceKind.ONE_DRIVE, MediaSourceKind.DROPBOX),
            MediaSourceKind.entries.filter { it.streamsOverHttp },
        )
        // Every remote kind's scheme maps back to it.
        MediaSourceKind.entries.filter { it.isRemote }.forEach { assertEquals(it, MediaSourceKind.fromScheme(it.scheme!!)) }
    }

    // MARK: - Enumeration

    /** A connector over an in-memory tree, keyed by folder URL. */
    private class TreeConnector(
        override val root: String,
        val tree: Map<String, List<ConnectorEntry>>,
        val failing: Set<String>,
    ) : MediaConnector {
        override val kind = MediaSourceKind.WEBDAV

        override suspend fun list(directory: String): List<ConnectorEntry> {
            if (directory in failing) error("listing failed: $directory")
            return tree[directory].orEmpty()
        }
    }

    private fun folder(path: String) = "davs://nas.local$path/"
    private fun file(path: String) = ConnectorEntry(path.substringAfterLast('/'), "davs://nas.local$path", isDirectory = false)
    private fun directory(path: String) = ConnectorEntry(path.substringAfterLast('/'), folder(path), isDirectory = true)

    @Test
    fun `walks breadth first skipping hidden and non-video files`() = runBlocking {
        val connector = TreeConnector(
            root = folder(""),
            tree = mapOf(
                folder("") to listOf(directory("/Movies"), directory("/.Trash"), file("/Heat.1995.mkv"), file("/notes.txt")),
                folder("/Movies") to listOf(file("/Movies/Alien.1979.mp4"), directory("/Movies/Broken"), file("/Movies/.hidden.mkv")),
                folder("/.Trash") to listOf(file("/.Trash/Old.2001.mkv")),
            ),
            failing = setOf(folder("/Movies/Broken")),
        )
        val enumeration = connector.enumerateVideos(connector.root)
        assertEquals(listOf("Heat.1995.mkv", "Alien.1979.mp4"), enumeration.videos.map { it.name })
        // A broken branch is skipped, and the walk says it didn't see everything.
        assertFalse(enumeration.complete)
    }

    @Test
    fun `a whole tree is a complete walk`() = runBlocking {
        val connector = TreeConnector(
            root = folder(""),
            tree = mapOf(folder("") to listOf(directory("/A"), file("/Heat.1995.mkv")), folder("/A") to listOf(file("/A/Alien.1979.mkv"))),
            failing = emptySet(),
        )
        val enumeration = connector.enumerateVideos(connector.root)
        assertEquals(2, enumeration.videos.size)
        assertTrue(enumeration.complete)
    }

    @Test
    fun `a failing top folder throws`() {
        val connector = TreeConnector(root = folder(""), tree = emptyMap(), failing = setOf(folder("")))
        assertFailsWith<IllegalStateException> { runBlocking { connector.enumerateVideos(connector.root) } }
    }

    @Test
    fun `cycles and runaway trees stop`() = runBlocking {
        // A shortcut loop lists the same folder again.
        val loop = TreeConnector(
            root = folder("/a"),
            tree = mapOf(folder("/a") to listOf(directory("/a"), file("/a/Heat.1995.mkv"))),
            failing = emptySet(),
        )
        val looped = loop.enumerateVideos(loop.root)
        assertEquals(1, looped.videos.size)
        assertTrue(looped.complete)

        var calls = 0
        val deep = ConnectorWalk.videos(folder("/0"), maxDirectories = 5) {
            calls += 1
            listOf(ConnectorEntry("d$calls", folder("/$calls"), isDirectory = true))
        }
        assertTrue(deep.videos.isEmpty())
        assertEquals(5, calls)
        // Stopping at the cap leaves folders unread.
        assertFalse(deep.complete)
    }

    @Test
    fun `entries know videos and hidden files`() {
        assertTrue(ConnectorEntry("Heat.MKV", "x", isDirectory = false).isVideo)
        assertFalse(ConnectorEntry("Heat.mkv", "x", isDirectory = true).isVideo)
        assertFalse(ConnectorEntry("notes.txt", "x", isDirectory = false).isVideo)
        assertTrue(ConnectorEntry("._Heat.mkv", "x", isDirectory = false).isHidden)
        assertFalse(ConnectorEntry("@eaDir", "x", isDirectory = true).isHidden)
    }
}
