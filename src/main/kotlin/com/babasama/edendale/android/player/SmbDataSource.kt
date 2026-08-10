package com.babasama.edendale.android.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.babasama.edendale.android.data.SmbClient
import com.babasama.edendale.android.data.SmbCredentialsStore
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile

/**
 * Streams an `smb://` file to ExoPlayer over jcifs-ng. Nothing is copied to
 * disk or held in memory beyond the read-ahead window below.
 *
 * ExoPlayer opens a fresh [DataSpec] on every seek, and it seeks a lot at
 * startup: MP4 keeps its `moov` index and MKV its Cues near the end of the
 * file, so playback can't begin until the player has jumped to the tail and
 * back. Two things make that cheap here:
 *
 *  - [SmbRandomAccessFile] with [SmbRandomAccessFile.seek] gives a real
 *    positioned handle, so a seek is a pointer move, not a re-open plus a
 *    forward `skip` from zero.
 *  - Container parsing reads in tiny bursts (4–8 byte atom / EBML headers).
 *    Serving those one SMB round trip each is what made loading crawl, so a
 *    small forward read fills [readAhead] once and the burst drains from it.
 *    Reads at least as large as the buffer bypass it and land straight in the
 *    caller's array.
 */
class SmbDataSource(
    private val context: Context,
) : BaseDataSource(true) {

    private val smbCredentialsStore = SmbCredentialsStore(context)
    private var randomAccess: SmbRandomAccessFile? = null
    private var uri: Uri? = null
    private var bytesRemaining: Long = 0
    private var opened: Boolean = false

    private val readAhead = ByteArray(READ_AHEAD_BYTES)
    private var readAheadPosition = 0
    private var readAheadLimit = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val uriString = dataSpec.uri.toString()
        val host = dataSpec.uri.host.orEmpty()

        transferInitializing(dataSpec)

        val cifsContext = SmbClient.context(smbCredentialsStore.getCredentials(host))
        val file = SmbFile(uriString, cifsContext).openRandomAccess("r")
        randomAccess = file
        readAheadPosition = 0
        readAheadLimit = 0

        val fileLength = file.length()
        if (dataSpec.position > 0) {
            file.seek(dataSpec.position)
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else if (fileLength >= 0) {
            (fileLength - dataSpec.position).coerceAtLeast(0)
        } else {
            C.LENGTH_UNSET.toLong()
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // Anything already buffered ahead is served without touching the network.
        if (readAheadPosition < readAheadLimit) {
            val served = minOf(length, readAheadLimit - readAheadPosition)
            System.arraycopy(readAhead, readAheadPosition, buffer, offset, served)
            readAheadPosition += served
            return consumed(served)
        }

        val file = randomAccess ?: return C.RESULT_END_OF_INPUT
        val cap = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            minOf(bytesRemaining, length.toLong()).toInt()
        }

        // A large request is its own round trip; a small one primes the window
        // so the next handful of header reads are free.
        if (length >= readAhead.size) {
            val read = file.read(buffer, offset, cap)
            return if (read == -1) C.RESULT_END_OF_INPUT else consumed(read)
        }

        val fill = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            readAhead.size
        } else {
            minOf(bytesRemaining, readAhead.size.toLong()).toInt()
        }
        val filled = file.read(readAhead, 0, fill)
        if (filled == -1) return C.RESULT_END_OF_INPUT
        readAheadPosition = 0
        readAheadLimit = filled
        val served = minOf(length, filled)
        System.arraycopy(readAhead, 0, buffer, offset, served)
        readAheadPosition = served
        return consumed(served)
    }

    /** Books [count] bytes against the remaining total and the transfer listener. */
    private fun consumed(count: Int): Int {
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= count
        }
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        readAheadPosition = 0
        readAheadLimit = 0
        try {
            randomAccess?.close()
        } catch (e: Exception) {
            // A network share can vanish mid-close; nothing left to salvage.
        } finally {
            randomAccess = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource {
            return SmbDataSource(context)
        }
    }

    private companion object {
        /** One priming read for a burst of header-sized parser reads. */
        const val READ_AHEAD_BYTES = 512 * 1024
    }
}
