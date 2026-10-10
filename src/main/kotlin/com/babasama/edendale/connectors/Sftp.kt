package com.babasama.edendale.connectors

import com.babasama.edendale.remote.BufferedFile
import com.babasama.edendale.remote.ServerLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.Closeable
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Security
import java.util.Base64
import java.util.EnumSet

/** An SSH server's host key as Edendale shows and pins it (H.4). */
data class SshHostKey(
    /** `ssh-ed25519`, `ecdsa-sha2-nistp256`, `ssh-rsa`, … */
    val type: String,
    /** `SHA256:<base64 without padding>`, as `ssh-keygen -l` prints it. */
    val fingerprint: String,
)

/**
 * SFTP through sshj (H.4, D16): curve25519 and ECDH key exchange, Ed25519
 * and ECDSA host keys, and AES-GCM or ChaCha20-Poly1305, which is what a
 * current OpenSSH offers. Edendale owns host-key checking: trust on first
 * use, with the key pinned per host and port, and a changed key refused
 * until the viewer approves it again. Password login for now.
 */
object Sftp {
    const val DEFAULT_PORT = 22
    const val TIMEOUT_MILLIS = 15_000

    /** Pipelined reads: requests of 32 KiB, this many in flight. */
    private const val READS_IN_FLIGHT = 16

    private var providerReady = false

    /**
     * sshj needs the full Bouncy Castle provider. Android ships a stripped one
     * named "BC"; it's replaced under the same name, last in line, so the rest
     * of the app keeps its default providers.
     */
    @Synchronized
    fun ensureProvider() {
        if (providerReady) return
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)?.javaClass != BouncyCastleProvider::class.java) {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.addProvider(BouncyCastleProvider())
        }
        SecurityUtils.setRegisterBouncyCastle(false)
        SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME)
        providerReady = true
    }

    fun fingerprint(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
    }

    fun hostKey(key: PublicKey) = SshHostKey(KeyType.fromKey(key).toString(), fingerprint(key))

    /** A host key pinned for [host] and [port], or none yet. */
    fun interface HostKeyPins {
        fun pinned(host: String, port: Int): String?
    }

    /**
     * Accepts the key whose fingerprint was pinned and remembers what the
     * server presented, so a refusal can say whether the key was new or changed.
     */
    private class PinnedVerifier(private val expected: String?) : HostKeyVerifier {
        @Volatile var presented: SshHostKey? = null

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val presentedKey = hostKey(key)
            presented = presentedKey
            return expected != null && presentedKey.fingerprint == expected
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    private fun client(verifier: HostKeyVerifier): SSHClient {
        ensureProvider()
        return SSHClient(DefaultConfig()).apply {
            connectTimeout = TIMEOUT_MILLIS
            timeout = TIMEOUT_MILLIS
            addHostKeyVerifier(verifier)
        }
    }

    /** Connects without logging in, for the key the viewer approves. Blocking. */
    fun fetchHostKey(host: String, port: Int): SshHostKey {
        var presented: SshHostKey? = null
        val client = client(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                presented = hostKey(key)
                return true
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        try {
            client.connect(host, port)
        } catch (error: Exception) {
            throw ConnectorException(failure(error, host, null), error)
        } finally {
            runCatching { client.disconnect() }
        }
        return presented ?: throw ConnectorException(ConnectorFailure.SecureConnectionFailed(host))
    }

    /** An open SSH connection with its SFTP channel. */
    class Session internal constructor(val ssh: SSHClient, val sftp: SFTPClient) : Closeable {
        override fun close() {
            runCatching { sftp.close() }
            runCatching { ssh.disconnect() }
        }
    }

    /**
     * Connects, checks the server's key against the one pinned, logs in with
     * [login], and opens SFTP. Blocking; failures are [ConnectorException]s.
     */
    fun open(host: String, port: Int, login: ServerLogin, pinnedFingerprint: String?): Session {
        val verifier = PinnedVerifier(pinnedFingerprint)
        val client = client(verifier)
        try {
            client.connect(host, port)
            client.authPassword(login.user, login.password)
            return Session(client, client.newSFTPClient())
        } catch (error: Exception) {
            val presented = verifier.presented
            val allowed = runCatching { client.userAuth.allowedMethods.toList() }.getOrDefault(emptyList())
            runCatching { client.disconnect() }
            val failure = when {
                presented != null && pinnedFingerprint == null -> ConnectorFailure.HostKeyUnverified(host, presented)
                presented != null && presented.fingerprint != pinnedFingerprint -> ConnectorFailure.HostKeyMismatch(host, presented)
                error is UserAuthException && allowed.isNotEmpty() && "password" !in allowed -> ConnectorFailure.PasswordLoginUnavailable(host)
                else -> failure(error, host, client)
            }
            throw ConnectorException(failure, error)
        }
    }

    /** What an sshj failure means for the viewer. */
    internal fun failure(error: Throwable, host: String, client: SSHClient?): ConnectorFailure = when {
        error is UserAuthException -> ConnectorFailure.AuthenticationFailed(host)
        error is TransportException && error.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE ->
            ConnectorFailure.HostKeyUnverified(host, null)
        error is TransportException && (
            error.disconnectReason == DisconnectReason.KEY_EXCHANGE_FAILED ||
                error.message?.contains("Unable to reach a settlement") == true
            ) -> ConnectorFailure.SecureConnectionFailed(host)
        // The server accepted the login but wouldn't start the SFTP subsystem.
        error is ConnectionException && client?.isAuthenticated == true -> ConnectorFailure.SftpUnavailable(host)
        error is ConnectException || error is UnknownHostException || error is SocketTimeoutException ->
            ConnectorFailure.Unreachable(host)
        else -> ConnectorFailure.Unreachable(host)
    }

    /** The decoded absolute path of an `sftp://` URL. */
    fun path(url: String): String = "/" + SourceUrl.pathSegments(url).joinToString("/")

    fun url(host: String, port: Int, path: String, isDirectory: Boolean): String? =
        SourceUrl.server("sftp", host, port.takeIf { it != DEFAULT_PORT }, path.split('/'), isDirectory)

    /** Reads a file in pipelined 32 KiB requests. */
    internal fun readFully(file: RemoteFile, position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        file.ReadAheadRemoteFileInputStream(READS_IN_FLIGHT, position, length.toLong()).use { stream ->
            var total = 0
            while (total < length) {
                val count = stream.read(buffer, offset + total, length - total)
                if (count < 0) break
                total += count
            }
            return total
        }
    }

    internal fun openFile(session: Session, path: String): RemoteFile = session.sftp.open(path, EnumSet.of(OpenMode.READ))
}

/**
 * Folders on an SSH server, read over SFTP (H.4, Apple's `SFTPConnector`).
 * URLs are `sftp://host[:port]/path/Name.ext` with absolute server paths.
 */
class SftpConnector(
    val host: String,
    val port: Int,
    private val login: ServerLogin,
    private val pins: Sftp.HostKeyPins,
    /** Where browsing starts: the login directory when linking. */
    private val startPath: String = "/",
) : MediaConnector {
    override val kind = MediaSourceKind.SFTP
    override val root: String = Sftp.url(host, port, startPath, isDirectory = true) ?: "sftp://$host/"
    override val accountLabel: String? = login.user.takeIf { it.isNotEmpty() }

    private fun <T> withSession(work: (Sftp.Session) -> T): T =
        Sftp.open(host, port, login, pins.pinned(host, port)).use(work)

    override suspend fun list(directory: String): List<ConnectorEntry> = withContext(Dispatchers.IO) {
        withSession { session -> entries(session, Sftp.path(directory)) }
    }

    /** One connection for the whole walk: an SSH handshake per folder would dominate the scan. */
    override suspend fun enumerateVideos(folder: String): Enumeration = withContext(Dispatchers.IO) {
        val session = Sftp.open(host, port, login, pins.pinned(host, port))
        try {
            ConnectorWalk.videos(folder) { directory -> entries(session, Sftp.path(directory)) }
        } finally {
            session.close()
        }
    }

    /** The login directory, where browsing starts after linking. */
    suspend fun homeDirectory(): String = withContext(Dispatchers.IO) {
        withSession { session -> session.sftp.canonicalize(".") }
    }

    private fun entries(session: Sftp.Session, path: String): List<ConnectorEntry> {
        val listing = try {
            session.sftp.ls(path)
        } catch (error: IOException) {
            throw ConnectorException(ConnectorFailure.ListingFailed(path), error)
        }
        val base = if (path.endsWith("/")) path else "$path/"
        return listing.mapNotNull { info ->
            var attributes = info.attributes
            if (attributes.type == FileMode.Type.SYMLINK) {
                // Follow the link to see whether it's a folder or a file; a dangling one is skipped.
                attributes = runCatching { session.sftp.stat(base + info.name) }.getOrNull() ?: return@mapNotNull null
            }
            val isDirectory = attributes.type == FileMode.Type.DIRECTORY
            if (!isDirectory && attributes.type != FileMode.Type.REGULAR) return@mapNotNull null
            ConnectorEntry(
                name = info.name,
                url = Sftp.url(host, port, base + info.name, isDirectory) ?: return@mapNotNull null,
                isDirectory = isDirectory,
                size = if (isDirectory) null else attributes.size.takeIf { it >= 0 },
                modifiedEpochMillis = attributes.mtime.takeIf { it > 0 }?.let { it * 1_000 },
            )
        }.sortedWith(WebDavConnector.FOLDERS_FIRST)
    }
}

/**
 * One open SFTP file for the buffered byte source (H.4, D.1's read-ahead and
 * reconnect): reads are pipelined 32 KiB requests.
 */
class SftpBufferedFile private constructor(
    private val session: Sftp.Session,
    private val file: RemoteFile,
) : BufferedFile {
    override val size: Long = runCatching { file.length() }.getOrDefault(-1L)

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        Sftp.readFully(file, position, buffer, offset, length)

    override fun keepAlive(): Boolean = runCatching { session.ssh.isConnected && file.length() >= 0 }.getOrDefault(false)

    override fun abort() {
        runCatching { session.ssh.disconnect() }
    }

    override fun close() {
        runCatching { file.close() }
        session.close()
    }

    companion object {
        /** Connects and opens [url] read-only; blocking network I/O. */
        fun open(url: String, login: ServerLogin, pinnedFingerprint: String?): SftpBufferedFile {
            val host = SourceUrl.credentialHost(url) ?: throw ConnectorException(ConnectorFailure.InvalidAddress)
            val session = Sftp.open(host, SourceUrl.port(url) ?: Sftp.DEFAULT_PORT, login, pinnedFingerprint)
            return try {
                SftpBufferedFile(session, Sftp.openFile(session, Sftp.path(url)))
            } catch (error: IOException) {
                session.close()
                throw ConnectorException(ConnectorFailure.ListingFailed(Sftp.path(url)), error)
            }
        }
    }
}
