package com.babasama.edendale.android.player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.babasama.edendale.android.data.ServerLoginStore
import com.babasama.edendale.android.data.SshHostKeyStore
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.connectors.DropboxContentResolver
import com.babasama.edendale.connectors.OneDriveContentResolver
import com.babasama.edendale.connectors.ProviderHttp
import com.babasama.edendale.connectors.S3ContentResolver
import com.babasama.edendale.connectors.Sftp
import com.babasama.edendale.connectors.SftpBufferedFile
import com.babasama.edendale.connectors.SourceUrl
import com.babasama.edendale.connectors.WebDavContentResolver
import com.babasama.edendale.remote.HttpAuthSession
import com.babasama.edendale.remote.OkHttpRemoteHttp
import com.babasama.edendale.remote.BufferedByteSource
import com.babasama.edendale.remote.BufferedSourceConfig
import com.babasama.edendale.remote.RemoteByteSource
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteSourceException
import com.babasama.edendale.remote.ReusableSourceSlot
import java.io.Closeable
import java.io.IOException

/**
 * A remote file the data source reads: an HTTP provider's byte source (H.2)
 * or a buffered SFTP connection (H.4).
 */
internal interface RemoteReader : Closeable {
    /** Prepares a read from [position]; the file's length, or -1 while unknown. */
    fun open(position: Long): Long

    /** Reads at [position]; the count, or 0 or less at the end of the file. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

private class HttpReader(private val source: RemoteByteSource) : RemoteReader {
    override fun open(position: Long): Long = source.length
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) = source.read(position, buffer, offset, length)
    override fun close() = source.close()
}

private class BufferedReader(private val source: BufferedByteSource) : RemoteReader {
    override fun open(position: Long): Long = source.open(position)
    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int) = source.read(position, buffer, offset, length)
    override fun close() = source.close()
}

/**
 * The reader for a remote item URL (H.2): each provider's connector registers
 * here as Section H adds it. Null when the source's account or login is
 * missing, so playback asks the viewer to sign in again.
 */
internal object RemotePlayback {
    fun reader(context: Context, url: String, kind: MediaSourceKind): RemoteReader? =
        if (kind == MediaSourceKind.SFTP) sftpReader(context, url) else byteSource(context, url, kind)?.let(::HttpReader)

    /** SFTP reads through D.1's buffered source: read-ahead, reconnect, and keep-alive (H.4). */
    private fun sftpReader(context: Context, url: String): RemoteReader? {
        val host = SourceUrl.credentialHost(url) ?: return null
        val port = SourceUrl.port(url) ?: Sftp.DEFAULT_PORT
        val login = ServerLoginStore(context).get(MediaSourceKind.SFTP, host, port) ?: return null
        val pinned = SshHostKeyStore(context).pinned(host, port)
        val manager = context.getSystemService(ActivityManager::class.java)
        val config = BufferedSourceConfig.forDevice(
            isLowRamDevice = manager?.isLowRamDevice == true,
            memoryClassMb = manager?.memoryClass ?: Int.MAX_VALUE,
        )
        return BufferedReader(BufferedByteSource(host, { SftpBufferedFile.open(url, login, pinned) }, config))
    }

    fun byteSource(context: Context, url: String, kind: MediaSourceKind): RemoteByteSource? = when (kind) {
        // WebDAV works as a guest too, so a missing login isn't a reason to stop (H.3).
        MediaSourceKind.WEBDAV -> RemoteByteSource(
            WebDavContentResolver(url, HttpAuthSession(ServerLoginStore(context).forUrl(kind, url)), http),
            http,
        )
        MediaSourceKind.S3 -> {
            val item = SourceUrl.parseS3(url)
            item?.let { ServerLoginStore(context).getS3(it.account) }?.let { (login, configuration) ->
                RemoteByteSource(S3ContentResolver(configuration, login, item.key), http)
            }
        }
        MediaSourceKind.ONE_DRIVE -> SourceUrl.parseAccountItem(url)?.takeIf { it.ids.size == 2 }?.let { item ->
            val cloud = (context.applicationContext as EdendaleApplication).cloudAccounts
            cloud.vault.account(kind, item.account)?.let { account ->
                val provider = ProviderHttp(kind, account.key, cloud.tokens, cloud.http)
                RemoteByteSource(OneDriveContentResolver(item.ids[0], item.ids[1], provider), cloud.http)
            }
        }
        MediaSourceKind.DROPBOX -> SourceUrl.parseAccountItem(url)?.let { item ->
            val cloud = (context.applicationContext as EdendaleApplication).cloudAccounts
            cloud.vault.account(kind, item.account)?.let { account ->
                val provider = ProviderHttp(kind, account.key, cloud.tokens, cloud.http)
                RemoteByteSource(DropboxContentResolver(item.ids.first(), provider), cloud.http)
            }
        }
        else -> null
    }

    private val http by lazy { OkHttpRemoteHttp() }
}

/**
 * Streams an HTTP provider's item (`dav`, `davs`, `s3`, `gdrive`, `onedrive`,
 * `dropbox`) through a [RemoteByteSource] (H.2). Media3's own HTTP data
 * source never sees a provider's URL or token. Like [SmbDataSource], the byte
 * source and its cached chunks survive the close → open of a seek.
 */
class RemoteDataSource(context: Context) : BaseDataSource(true) {
    private val appContext = context.applicationContext
    private val slot = ReusableSourceSlot<RemoteReader>()

    private var source: RemoteReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val url = dataSpec.uri.toString()
        val kind = dataSpec.uri.scheme?.let(MediaSourceKind::fromScheme)
            ?: throw IOException("No connector reads ${dataSpec.uri.scheme} URLs")
        transferInitializing(dataSpec)
        val remote: RemoteReader
        val length: Long
        try {
            remote = slot.acquire(url) {
                RemotePlayback.reader(appContext, url, kind)
                    ?: throw RemoteSourceException(kind, RemoteFailure.SignInRequired)
            }
            length = remote.open(dataSpec.position)
        } catch (error: IOException) {
            // A source whose first open failed never retries; the next open starts fresh.
            slot.releaseNow()
            throw error
        }
        source = remote
        position = dataSpec.position
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            length >= 0 -> (length - dataSpec.position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val remote = source ?: return C.RESULT_END_OF_INPUT
        val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length else minOf(bytesRemaining, length.toLong()).toInt()
        val count = remote.read(position, buffer, offset, want)
        if (count <= 0) return C.RESULT_END_OF_INPUT
        position += count
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        source = null
        // Kept for the reopen that follows a seek; released when idle.
        slot.releaseLater()
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}

/**
 * The base source under Media3's `DefaultDataSource`, which hands it every
 * scheme it doesn't know: `smb` goes to [SmbDataSource], and the other
 * remote schemes to [RemoteDataSource] (H.2).
 */
class EdendaleDataSource(context: Context) : DataSource {
    private val smb = SmbDataSource(context)
    private val remote = RemoteDataSource(context)
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        smb.addTransferListener(transferListener)
        remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val target = when (dataSpec.uri.scheme?.lowercase()) {
            "smb" -> smb
            else -> remote
        }
        current = target
        return target.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

    override fun close() {
        current?.close()
        current = null
    }
}
