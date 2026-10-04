package com.babasama.edendale.connectors

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.PublicKey
import java.util.Base64
import java.util.EnumSet

/**
 * H.4.1: sshj with default settings, on Android, against a current OpenSSH.
 * Runs only when given a server: `-e sftpHost 10.0.2.2 -e sftpPort 22222
 * -e sftpUser <user> -e sftpKey <base64 private key> -e sftpFingerprint
 * SHA256:…`, with a file at Movies/Heat.1995.mkv in the login directory.
 * The server (not this test) logs the negotiated algorithms.
 */
@RunWith(AndroidJUnit4::class)
class SftpOpenSshInstrumentedTest {

    @Test
    fun connectsListsAndReadsWithDefaultSettings() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("sftpHost")
        assumeTrue("no SFTP server given", host != null)
        val port = args.getString("sftpPort")!!.toInt()
        val expected = args.getString("sftpFingerprint")!!
        val key = String(Base64.getDecoder().decode(args.getString("sftpKey")!!))

        // The full Bouncy Castle provider in place of Android's stripped one.
        Sftp.ensureProvider()
        var presented: SshHostKey? = null
        val client = SSHClient(DefaultConfig()).apply {
            connectTimeout = Sftp.TIMEOUT_MILLIS
            timeout = Sftp.TIMEOUT_MILLIS
            addHostKeyVerifier(object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    presented = Sftp.hostKey(key)
                    return presented?.fingerprint == expected
                }

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            })
        }
        try {
            client.connect(host, port)
            assertEquals(SshHostKey("ssh-ed25519", expected), presented)
            client.authPublickey(args.getString("sftpUser")!!, client.loadKeys(key, null, null))
            client.newSFTPClient().use { sftp ->
                val home = sftp.canonicalize(".")
                val names = sftp.ls("$home/Movies").map { it.name }.sorted()
                assertEquals(listOf("Classics", "Heat.1995.mkv"), names)
                sftp.open("$home/Movies/Heat.1995.mkv", EnumSet.of(OpenMode.READ)).use { file ->
                    assertEquals(300_000L, file.length())
                    val buffer = ByteArray(100_000)
                    assertEquals(100_000, Sftp.readFully(file, 150_000, buffer, 0, buffer.size))
                    // The same bytes through single requests.
                    val check = ByteArray(1_000)
                    var read = 0
                    while (read < check.size) read += file.read(150_000L + read, check, read, check.size - read)
                    assertTrue(buffer.copyOf(1_000).contentEquals(check))
                }
            }
        } finally {
            client.disconnect()
        }
    }
}
