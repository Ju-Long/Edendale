package com.babasama.edendale.sync

import java.io.IOException

/** One file in a space folder as a listing reports it (SYNC-PROTOCOL.md §4). */
data class RemoteSyncFile(
    /** `<device-id>.json`, or anything else the folder holds (skipped). */
    val name: String,
    /** Modification time, ETag, Drive `modifiedTime`, Graph `eTag`, or Dropbox `rev`: skip the file when it hasn't changed. */
    val stamp: String,
    /** Bytes, when the listing reports it; a file over 8 MiB is skipped before download. */
    val size: Long? = null,
)

/**
 * A sync location's storage (§4): a picked folder, a cloud account, or a
 * server, each creating `Edendale/<space-name>/`, listing it with a change
 * stamp per file, and reading, replacing, and deleting one device file.
 * Failures are [IOException]s (connector failures included); the engine
 * turns them into backoff, never into data changes.
 */
interface SyncStorage {
    /** Creates `Edendale/` and the space folder when missing. */
    @Throws(IOException::class)
    suspend fun ensureSpace(space: String)

    /** The space folder's files; an empty list for an empty or missing folder is not an instruction to clear anything (§8.2). */
    @Throws(IOException::class)
    suspend fun list(space: String): List<RemoteSyncFile>

    /** The file's bytes, at most [limit]; a longer file is cut off (and then fails validation). */
    @Throws(IOException::class)
    suspend fun read(space: String, name: String, limit: Int = DeviceFile.MAX_BYTES + 1): ByteArray

    /** Replaces the file in one step: upload and replace, or write a temporary file and rename (§4). */
    @Throws(IOException::class)
    suspend fun write(space: String, name: String, bytes: ByteArray)

    /** Removes a device's file (§8.3). Removing a file that isn't there is not an error. */
    @Throws(IOException::class)
    suspend fun delete(space: String, name: String)

    companion object {
        /** The folder at the root of every location (§4). */
        const val ROOT_FOLDER = "Edendale"
    }
}
