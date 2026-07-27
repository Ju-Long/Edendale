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
import java.io.InputStream
import java.io.EOFException

class SmbDataSource(
    private val context: Context,
) : BaseDataSource(true) {

    private val smbCredentialsStore = SmbCredentialsStore(context)
    private var smbFile: SmbFile? = null
    private var inputStream: InputStream? = null
    private var uri: Uri? = null
    private var bytesToRead: Long = 0
    private var opened: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        try {
            uri = dataSpec.uri
            val uriString = uri.toString()
            val host = uri?.host ?: ""

            val cifsContext = SmbClient.context(smbCredentialsStore.getCredentials(host))

            transferInitializing(dataSpec)

            smbFile = SmbFile(uriString, cifsContext)
            val fileLength = smbFile!!.length()

            // Note: SmbFile.getInputStream() doesn't support seeking directly easily if we just skip, but it is acceptable for basic playback.
            // jcifs-ng might support SmbRandomAccessFile but let's stick to InputStream for simplicity first.
            inputStream = smbFile!!.inputStream

            if (dataSpec.position > 0) {
                var skipped = 0L
                while (skipped < dataSpec.position) {
                    val s = inputStream!!.skip(dataSpec.position - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                if (skipped != dataSpec.position) {
                    throw EOFException()
                }
            }

            if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                bytesToRead = dataSpec.length
            } else {
                bytesToRead = fileLength - dataSpec.position
                if (bytesToRead < 0) bytesToRead = C.LENGTH_UNSET.toLong()
            }
        } catch (e: Exception) {
            throw e
        }

        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead == 0L) return C.RESULT_END_OF_INPUT

        val bytesRead: Int
        try {
            val bytesToReadNow = if (bytesToRead == C.LENGTH_UNSET.toLong()) length.toLong() else minOf(bytesToRead, length.toLong())
            bytesRead = inputStream!!.read(buffer, offset, bytesToReadNow.toInt())
        } catch (e: Exception) {
            throw e
        }

        if (bytesRead == -1) {
            if (bytesToRead != C.LENGTH_UNSET.toLong()) {
                throw EOFException()
            }
            return C.RESULT_END_OF_INPUT
        }

        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            bytesToRead -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            inputStream?.close()
        } catch (e: Exception) {
            // Ignore
        } finally {
            inputStream = null
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
}
