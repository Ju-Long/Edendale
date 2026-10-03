package com.babasama.edendale.android.player

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.babasama.edendale.android.data.SmbBufferedFile
import com.babasama.edendale.android.data.SmbClient
import com.babasama.edendale.android.data.SmbCredentialsStore
import com.babasama.edendale.remote.BufferedByteSource
import com.babasama.edendale.remote.BufferedSourceConfig
import com.babasama.edendale.remote.ReusableSourceSlot
import java.io.IOException

/**
 * Streams an `smb://` file to ExoPlayer over jcifs-ng through a
 * [BufferedByteSource] (D.1): a worker thread keeps megabytes ahead of the
 * read position in memory, reconnects with backoff when the connection drops,
 * and keeps an idle connection alive while playback is paused. Nothing is
 * written to disk.
 *
 * ExoPlayer reopens this source on every seek, and seeks a lot at startup
 * (MP4 keeps its index and MKV its Cues near the end of the file). The
 * buffered source — connection and cache — is kept across close → open for
 * the same file and released when another file opens or after 30 s without
 * an open.
 */
class SmbDataSource(
    context: Context,
) : BaseDataSource(true) {

    private val appContext = context.applicationContext
    private val smbCredentialsStore = SmbCredentialsStore(appContext)
    private val config = deviceConfig(appContext)
    private val slot = ReusableSourceSlot<BufferedByteSource>()

    private var source: BufferedByteSource? = null
    private var uri: Uri? = null
    private var position: Long = 0
    private var bytesRemaining: Long = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val url = dataSpec.uri.toString()
        val host = dataSpec.uri.host.orEmpty()
        transferInitializing(dataSpec)

        val buffered = slot.acquire(url) {
            val credentials = smbCredentialsStore.getCredentials(host)
            BufferedByteSource(
                host = host,
                opener = { SmbBufferedFile.open(url, SmbClient.context(credentials)) },
                config = config,
            )
        }
        val fileLength = try {
            buffered.open(dataSpec.position)
        } catch (e: IOException) {
            // A source whose first open failed never retries; the next open starts fresh.
            slot.releaseNow()
            throw e
        }
        source = buffered
        position = dataSpec.position
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            fileLength >= 0 -> (fileLength - dataSpec.position).coerceAtLeast(0)
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val buffered = source ?: return C.RESULT_END_OF_INPUT
        val want = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(bytesRemaining, length.toLong()).toInt()
        }
        val count = buffered.read(position, buffer, offset, want)
        if (count < 0) return C.RESULT_END_OF_INPUT
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

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(context)
    }

    private companion object {
        fun deviceConfig(context: Context): BufferedSourceConfig {
            val manager = context.getSystemService(ActivityManager::class.java)
            return BufferedSourceConfig.forDevice(
                isLowRamDevice = manager?.isLowRamDevice == true,
                memoryClassMb = manager?.memoryClass ?: Int.MAX_VALUE,
            )
        }
    }
}
