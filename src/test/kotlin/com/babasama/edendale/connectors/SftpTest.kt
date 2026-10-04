package com.babasama.edendale.connectors

import com.babasama.edendale.remote.BufferedByteSource
import com.babasama.edendale.remote.ServerLogin
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.auth.pubkey.AcceptAllPublickeyAuthenticator
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * H.4.T1: host-key fingerprints match `ssh-keygen -l` for Ed25519, ECDSA,
 * and RSA keys; trust on first use with pin-and-compare; and listing and
 * ranged reads against an in-process SFTP server (Apache MINA SSHD).
 */
class SftpTest {

    // MARK: - Fingerprints

    private fun publicKey(line: String) =
        Buffer.PlainBuffer(Base64.getDecoder().decode(line.split(' ')[1])).readPublicKey()

    @Test
    fun `fingerprints match ssh-keygen`() {
        // Each line and fingerprint from `ssh-keygen -t <type>` and `ssh-keygen -l -E sha256`.
        val keys = listOf(
            Triple(
                "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIER1e9qNykPPy6Ta1cQY0D/nmuQgPbCPa/vcEulEzbrc",
                "ssh-ed25519",
                "SHA256:5gCfmojQrQBFRy3mTR35KVVzN2VsXnn3h+miLwdB6Q0",
            ),
            Triple(
                "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBICKTgYGLr/R7EYKRPborQLGbTqkf2PuSWQ2OrqPrqV5lGYGEiugMcJLpr/d1fsczkqddjDnFDVN2a5RJ1mHIbw=",
                "ecdsa-sha2-nistp256",
                "SHA256:RA5bEtqCvP5lunyfZ97+r40cMplQ9ya1f7MoEu9yWOs",
            ),
            Triple(
                "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDh8xc1lIrA5PIERGeboLRaqOPxAKpWuJLIw+8/XdYyBO0DEhPaTc4sEBuam5Dxl0QvG2dM7qdE+JCVnkIQAccTonI8t+WImNA0xnTBXo6CKMU8u/pz/pas1C9bpN+/yVA/JsO8a//W4mMqxMKy0ychCSWbGa3/N0iW9NEZtcCIE0UIMgehpGwl8zjwX0w93ElIdnoFBw9fWbGCjGhOfiBrvFf6DPaxQhLYfbck6VuefaJ+z4v7s+uFLng51uStq+Ps3mR/Lywil3w44oVsAlx96nrCanzxOBTTAUZufhzWLBE1hhgryOcysaA9vQf6z/f7XgNS4veYWR+2sGQ5/yiv",
                "ssh-rsa",
                "SHA256:J1p/GZ93e6kt/Pbz6aiMtBoygrUx1+Rvc5K8EpRJ4Pk",
            ),
        )
        Sftp.ensureProvider()
        keys.forEach { (line, type, fingerprint) ->
            assertEquals(SshHostKey(type, fingerprint), Sftp.hostKey(publicKey(line)))
        }
    }

    @Test
    fun `addresses read as host, port, and path`() {
        assertEquals(Triple("nas.local", 22, null), com.babasama.edendale.android.data.sftpAddress("nas.local"))
        assertEquals(Triple("nas.local", 2222, "/home/me"), com.babasama.edendale.android.data.sftpAddress("sftp://me@nas.local:2222/home/me/"))
        assertEquals(null, com.babasama.edendale.android.data.sftpAddress("  "))
        assertEquals("sftp://nas.local/home/me/Films%20&%20TV/", Sftp.url("nas.local", 22, "/home/me/Films & TV", isDirectory = true))
        assertEquals("sftp://nas.local:2222/a/Heat.mkv", Sftp.url("nas.local", 2222, "/a/Heat.mkv", isDirectory = false))
        assertEquals("/home/me/Films & TV", Sftp.path("sftp://nas.local/home/me/Films%20&%20TV/"))
    }

    // MARK: - An in-process server

    private lateinit var root: Path
    private lateinit var server: SshServer
    private val hostKeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val login = ServerLogin("me", "secret")
    private val data = ByteArray(300_000) { ((it * 13) xor (it shr 5)).toByte() }

    private fun start(passwords: Boolean = true) {
        server = SshServer.setUpDefaultServer().apply {
            host = "127.0.0.1"
            port = 0
            keyPairProvider = KeyPairProvider.wrap(hostKeyPair)
            if (passwords) {
                passwordAuthenticator = PasswordAuthenticator { user, password, _ -> user == "me" && password == "secret" }
            } else {
                publickeyAuthenticator = AcceptAllPublickeyAuthenticator.INSTANCE
            }
            subsystemFactories = listOf(SftpSubsystemFactory())
            fileSystemFactory = VirtualFileSystemFactory(root)
        }
        server.start()
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("edendale-sftp")
        Files.createDirectories(root.resolve("Movies/Classics"))
        Files.createDirectories(root.resolve(".Trash"))
        Files.write(root.resolve("Movies/Heat.1995.mkv"), data)
        Files.write(root.resolve("Movies/Classics/Alien.1979.mp4"), ByteArray(10))
        Files.write(root.resolve("Movies/notes.txt"), ByteArray(3))
        Files.write(root.resolve(".Trash/Old.2001.mkv"), ByteArray(3))
        // A link to a folder reads as a folder.
        Files.createSymbolicLink(root.resolve("Linked"), root.resolve("Movies/Classics"))
    }

    @AfterTest
    fun tearDown() {
        if (::server.isInitialized) server.stop(true)
        root.toFile().deleteRecursively()
    }

    private val presentedFingerprint get() = Sftp.fingerprint(hostKeyPair.public)

    private fun pins(fingerprint: String?) = Sftp.HostKeyPins { _, _ -> fingerprint }

    @Test
    fun `trust on first use pins the key and refuses a different one`() {
        start()
        val key = Sftp.fetchHostKey("127.0.0.1", server.port)
        assertEquals(SshHostKey("ecdsa-sha2-nistp256", presentedFingerprint), key)

        // Not approved yet: the key comes back for the viewer to compare.
        val unverified = assertFailsWith<ConnectorException> { Sftp.open("127.0.0.1", server.port, login, null) }
        assertEquals(ConnectorFailure.HostKeyUnverified("127.0.0.1", key), unverified.failure)
        assertTrue(unverified.failure.needsUserAction)

        // A different key from the one approved is refused until approved again.
        val changed = assertFailsWith<ConnectorException> { Sftp.open("127.0.0.1", server.port, login, "SHA256:somethingElse") }
        assertEquals(ConnectorFailure.HostKeyMismatch("127.0.0.1", key), changed.failure)

        Sftp.open("127.0.0.1", server.port, login, key.fingerprint).use { session ->
            assertTrue(session.ssh.isAuthenticated)
        }
    }

    @Test
    fun `a wrong password and a server without password logins say so`() {
        start()
        val refused = assertFailsWith<ConnectorException> {
            Sftp.open("127.0.0.1", server.port, ServerLogin("me", "wrong"), presentedFingerprint)
        }
        assertEquals(ConnectorFailure.AuthenticationFailed("127.0.0.1"), refused.failure)
        server.stop(true)

        start(passwords = false)
        val keysOnly = assertFailsWith<ConnectorException> { Sftp.open("127.0.0.1", server.port, login, presentedFingerprint) }
        assertEquals(ConnectorFailure.PasswordLoginUnavailable("127.0.0.1"), keysOnly.failure)
    }

    @Test
    fun `an unreachable server says so`() {
        val error = assertFailsWith<ConnectorException> { Sftp.fetchHostKey("127.0.0.1", 1) }
        assertEquals(ConnectorFailure.Unreachable("127.0.0.1"), error.failure)
    }

    @Test
    fun `lists folders first, follows links, and walks on one connection`() = runBlocking {
        start()
        val connector = SftpConnector("127.0.0.1", server.port, login, pins(presentedFingerprint))
        val home = connector.homeDirectory()
        assertEquals("/", home)

        val top = connector.list(connector.root)
        assertEquals(listOf(".Trash", "Linked", "Movies"), top.map { it.name })
        assertTrue(top.first { it.name == "Linked" }.isDirectory)
        val movies = connector.list(top.first { it.name == "Movies" }.url)
        assertEquals(listOf("Classics", "Heat.1995.mkv", "notes.txt"), movies.map { it.name })
        assertEquals(data.size.toLong(), movies.first { it.name == "Heat.1995.mkv" }.size)
        assertEquals("sftp://127.0.0.1:${server.port}/Movies/Heat.1995.mkv", movies.first { it.name == "Heat.1995.mkv" }.url)

        val enumeration = connector.enumerateVideos(connector.root)
        // Hidden folders are skipped; the link reaches Alien a second time by another path.
        assertEquals(setOf("Heat.1995.mkv", "Alien.1979.mp4"), enumeration.videos.map { it.name }.toSet())
        assertTrue(enumeration.videos.none { "Trash" in it.url })
        assertTrue(enumeration.complete)
    }

    @Test
    fun `ranged reads through the buffered source`() {
        start()
        val url = "sftp://127.0.0.1:${server.port}/Movies/Heat.1995.mkv"
        SftpBufferedFile.open(url, login, presentedFingerprint).use { file ->
            assertEquals(data.size.toLong(), file.size)
            val buffer = ByteArray(70_000)
            assertEquals(70_000, file.read(123_456, buffer, 0, 70_000))
            assertContentEquals(data.copyOfRange(123_456, 193_456), buffer)
            // The end of the file reads short, then empty.
            assertEquals(100, file.read(data.size - 100L, buffer, 0, 1_000))
            assertEquals(0, file.read(data.size.toLong(), buffer, 0, 1_000))
            assertTrue(file.keepAlive())
        }

        val source = BufferedByteSource("127.0.0.1", { SftpBufferedFile.open(url, login, presentedFingerprint) })
        try {
            assertEquals(data.size.toLong(), source.open(200_000))
            val buffer = ByteArray(5_000)
            var read = 0
            while (read < buffer.size) {
                val count = source.read(200_000L + read, buffer, read, buffer.size - read)
                assertTrue(count > 0)
                read += count
            }
            assertContentEquals(data.copyOfRange(200_000, 205_000), buffer)
        } finally {
            source.close()
        }
    }
}
