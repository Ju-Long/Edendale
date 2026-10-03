package com.babasama.edendale.android.data

import com.babasama.edendale.remote.BufferedFile
import jcifs.CIFSContext
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile

/**
 * One open SMB file for [com.babasama.edendale.remote.BufferedByteSource]
 * (D.1.2): a positioned jcifs-ng handle, so a fetch is a seek plus one read.
 */
internal class SmbBufferedFile private constructor(
    private val smbFile: SmbFile,
    private val handle: SmbRandomAccessFile,
) : BufferedFile {

    override val size: Long = runCatching { handle.length() }.getOrDefault(-1L)

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        handle.seek(position)
        val count = handle.read(buffer, offset, length)
        return if (count < 0) 0 else count
    }

    /**
     * A metadata query: jcifs caches attributes for a few seconds, so after the
     * 20 s idle wait this is a real round trip.
     */
    override fun keepAlive(): Boolean = runCatching { handle.length() >= 0 }.getOrDefault(false)

    override fun close() {
        runCatching { handle.close() }
        runCatching { smbFile.close() }
    }

    companion object {
        /** Connects and opens [url] read-only; blocking network I/O. */
        fun open(url: String, context: CIFSContext): SmbBufferedFile {
            val file = SmbFile(url, context)
            return try {
                SmbBufferedFile(file, file.openRandomAccess("r"))
            } catch (e: Exception) {
                runCatching { file.close() }
                throw e
            }
        }
    }
}
