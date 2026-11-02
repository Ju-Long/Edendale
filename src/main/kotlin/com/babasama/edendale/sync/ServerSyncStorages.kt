package com.babasama.edendale.sync

import com.babasama.edendale.connectors.ConnectorException
import com.babasama.edendale.connectors.ConnectorFailure
import com.babasama.edendale.connectors.Sftp
import com.babasama.edendale.connectors.WebDav
import com.babasama.edendale.connectors.WebDavConnector
import com.babasama.edendale.remote.ServerLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * A WebDAV server as a sync location (SYNC-PROTOCOL.md §4): `MKCOL` for the
 * folders, `PUT` for the file, `PROPFIND` depth 1 for `getetag` and
 * `getlastmodified`. [base] is the configured folder, canonical
 * (`davs://host/path/`); the files live under `<base>Edendale/<space>/`.
 */
class WebDavSyncStorage(private val connector: WebDavConnector, base: String) : SyncStorage {
    private val base = WebDav.directoryUrl(base)

    private fun rootUrl() = base + SyncStorage.ROOT_FOLDER + "/"
    private fun spaceUrl(space: String) = rootUrl() + com.babasama.edendale.connectors.SourceUrl.encodeSegment(space) + "/"
    private fun fileUrl(space: String, name: String) = spaceUrl(space) + com.babasama.edendale.connectors.SourceUrl.encodeSegment(name)

    override suspend fun ensureSpace(space: String) {
        connector.mkcol(rootUrl())
        connector.mkcol(spaceUrl(space))
    }

    override suspend fun list(space: String): List<RemoteSyncFile> {
        val entries = try {
            connector.list(spaceUrl(space))
        } catch (error: ConnectorException) {
            // A folder that isn't there yet lists as empty; the write creates it.
            if (error.failure is ConnectorFailure.ListingFailed) return emptyList() else throw error
        }
        return entries.filterNot { it.isDirectory }.map { entry ->
            RemoteSyncFile(entry.name, entry.changeTag ?: entry.modifiedEpochMillis?.toString() ?: "", entry.size)
        }
    }

    override suspend fun read(space: String, name: String, limit: Int): ByteArray = connector.get(fileUrl(space, name), limit)

    override suspend fun write(space: String, name: String, bytes: ByteArray) {
        ensureSpace(space)
        connector.put(fileUrl(space, name), bytes)
    }

    override suspend fun delete(space: String, name: String) = connector.delete(fileUrl(space, name))
}

/**
 * An SSH server as a sync location (§4): folders created as needed, the file
 * written as `<device-id>.json.tmp` and renamed over the target, and the
 * listing's modification times as stamps. One connection per operation; the
 * host key must already be pinned ([Sftp.HostKeyPins]).
 */
class SftpSyncStorage(
    private val host: String,
    private val port: Int,
    private val login: ServerLogin,
    private val pins: Sftp.HostKeyPins,
    /** The absolute folder the files live under; `Edendale/<space>/` goes inside it. */
    basePath: String,
) : SyncStorage {
    private val base = "/" + basePath.split('/').filter { it.isNotEmpty() }.joinToString("/")

    private fun spacePath(space: String) = listOf(base.trimEnd('/'), SyncStorage.ROOT_FOLDER, space).joinToString("/").replace("//", "/")

    private suspend fun <T> withSession(work: (Sftp.Session) -> T): T = withContext(Dispatchers.IO) {
        Sftp.open(host, port, login, pins.pinned(host, port)).use { session ->
            try {
                work(session)
            } catch (error: ConnectorException) {
                throw error
            } catch (error: IOException) {
                throw ConnectorException(ConnectorFailure.ListingFailed(spacePath("")), error)
            }
        }
    }

    override suspend fun ensureSpace(space: String) = withSession { session -> Sftp.mkdirs(session, spacePath(space)) }

    override suspend fun list(space: String): List<RemoteSyncFile> = withSession { session ->
        Sftp.listFiles(session, spacePath(space)).map { RemoteSyncFile(it.name, "${it.modifiedEpochMillis ?: 0}:${it.size ?: -1}", it.size) }
    }

    override suspend fun read(space: String, name: String, limit: Int): ByteArray =
        withSession { session -> Sftp.readAll(session, spacePath(space) + "/" + name, limit) }

    override suspend fun write(space: String, name: String, bytes: ByteArray) = withSession { session ->
        Sftp.mkdirs(session, spacePath(space))
        Sftp.writeReplacing(session, spacePath(space) + "/" + name, bytes)
    }

    override suspend fun delete(space: String, name: String) = withSession { session -> Sftp.remove(session, spacePath(space) + "/" + name) }
}
