package com.babasama.edendale.android.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.remote.RemoteByteSource
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteSourceException
import com.babasama.edendale.remote.ReusableSourceSlot
import java.io.IOException

/**
 * The byte source for a remote item URL (H.2): each provider's connector
 * registers its resolver here as Section H adds it. Null when the source's
 * account or login is missing, so playback asks the viewer to sign in again.
 */
internal object RemotePlayback {
    fun byteSource(context: Context, url: String, kind: MediaSourceKind): RemoteByteSource? = when (kind) {
        else -> null
    }
}

/**
 * Streams an HTTP provider's item (`dav`, `davs`, `s3`, `gdrive`, `onedrive`,
 * `dropbox`) through a [RemoteByteSource] (H.2). Media3's own HTTP data
 * source never sees a provider's URL or token. Like [SmbDataSource], the byte
 * source and its cached chunks survive the close → open of a seek.
 */
class RemoteDataSource(context: Context) : BaseDataSource(true) {
    private val appContext = context.applicationContext
    private val slot = ReusableSourceSlot<RemoteByteSource>()

    private var source: RemoteByteSource? = null
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
        val remote = try {
            slot.acquire(url) {
                RemotePlayback.byteSource(appContext, url, kind)
                    ?: throw RemoteSourceException(kind, RemoteFailure.SignInRequired)
            }
        } catch (error: IOException) {
            slot.releaseNow()
            throw error
        }
        source = remote
        position = dataSpec.position
        val length = remote.length
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
