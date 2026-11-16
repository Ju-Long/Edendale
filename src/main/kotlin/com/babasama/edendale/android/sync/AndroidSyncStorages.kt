package com.babasama.edendale.android.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.babasama.edendale.android.EdendaleApplication
import com.babasama.edendale.android.data.ServerLoginStore
import com.babasama.edendale.android.data.SmbClient
import com.babasama.edendale.android.data.SmbCredentialsStore
import com.babasama.edendale.connectors.ConnectorException
import com.babasama.edendale.connectors.ConnectorFailure
import com.babasama.edendale.connectors.MediaSourceKind
import com.babasama.edendale.connectors.ProviderHttp
import com.babasama.edendale.connectors.WebDavConnector
import com.babasama.edendale.oauth.CloudProviders
import com.babasama.edendale.sync.DropboxSyncStorage
import com.babasama.edendale.sync.GoogleDriveSyncStorage
import com.babasama.edendale.sync.OneDriveSyncStorage
import com.babasama.edendale.sync.RemoteSyncFile
import com.babasama.edendale.sync.SftpSyncStorage
import com.babasama.edendale.sync.SyncLocation
import com.babasama.edendale.sync.SyncProvider
import com.babasama.edendale.sync.SyncStorage
import com.babasama.edendale.sync.WebDavSyncStorage
import jcifs.CIFSContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * A folder picked in the system picker as a sync location (SYNC-PROTOCOL.md
 * §4 "Folder"): `Edendale/<space>/` under the tree the viewer granted, with
 * the persisted Storage Access Framework permission. The file is written as
 * `<device-id>.json.tmp` and renamed over the target where the provider
 * allows a rename; otherwise it is rewritten in place. Modification time and
 * length make the stamp.
 */
class FolderSyncStorage(context: Context, private val treeUri: Uri) : SyncStorage {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    private fun root(): DocumentFile = DocumentFile.fromTreeUri(appContext, treeUri)?.takeIf { it.isDirectory && it.canRead() }
        ?: throw ConnectorException(ConnectorFailure.ListingFailed(treeUri.lastPathSegment.orEmpty()))

    private fun DocumentFile.child(name: String): DocumentFile? = listFiles().firstOrNull { it.name == name }

    private fun DocumentFile.directory(name: String, create: Boolean): DocumentFile? {
        child(name)?.let { return it.takeIf { it.isDirectory } ?: throw IOException("$name is a file") }
        return if (create) createDirectory(name) ?: throw IOException("Couldn't create $name") else null
    }

    private fun spaceFolder(space: String, create: Boolean): DocumentFile? =
        root().directory(SyncStorage.ROOT_FOLDER, create)?.directory(space, create)

    override suspend fun ensureSpace(space: String) = withContext(Dispatchers.IO) {
        spaceFolder(space, create = true)
        Unit
    }

    override suspend fun list(space: String): List<RemoteSyncFile> = withContext(Dispatchers.IO) {
        spaceFolder(space, create = false)?.listFiles().orEmpty().filter { it.isFile }.mapNotNull { file ->
            val name = file.name ?: return@mapNotNull null
            RemoteSyncFile(name, "${file.lastModified()}:${file.length()}", file.length())
        }
    }

    override suspend fun read(space: String, name: String, limit: Int): ByteArray = withContext(Dispatchers.IO) {
        val file = spaceFolder(space, create = false)?.child(name) ?: throw IOException("Missing $name")
        resolver.openInputStream(file.uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (out.size() < limit) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
                if (count < 0) break
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } ?: throw IOException("Couldn't open $name")
    }

    override suspend fun write(space: String, name: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val folder = spaceFolder(space, create = true) ?: throw IOException("No space folder")
        val temporaryName = "$name.tmp"
        folder.child(temporaryName)?.delete()
        val temporary = folder.createFile("application/octet-stream", temporaryName)
        if (temporary != null && temporary.name == temporaryName) {
            resolver.openOutputStream(temporary.uri, "wt")?.use { it.write(bytes) } ?: throw IOException("Couldn't write $temporaryName")
            folder.child(name)?.delete()
            val renamed = runCatching { DocumentsContract.renameDocument(resolver, temporary.uri, name) }.getOrNull()
            if (renamed != null) return@withContext
            // The provider wouldn't rename: fall back to writing the target in place.
            runCatching { temporary.delete() }
        } else {
            runCatching { temporary?.delete() }
        }
        val target = folder.child(name) ?: folder.createFile("application/json", name) ?: throw IOException("Couldn't create $name")
        resolver.openOutputStream(target.uri, "wt")?.use { it.write(bytes) } ?: throw IOException("Couldn't write $name")
    }

    override suspend fun delete(space: String, name: String) = withContext(Dispatchers.IO) {
        spaceFolder(space, create = false)?.child(name)?.delete()
        Unit
    }
}

/**
 * An SMB share as a sync location (§4): `Edendale/<space>/` under the
 * share folder the viewer chose, written as a temporary file and renamed
 * over the target, stamped by modification time and size.
 */
class SmbSyncStorage(baseUrl: String, private val context: CIFSContext) : SyncStorage {
    private val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    private fun spaceUrl(space: String) = base + SyncStorage.ROOT_FOLDER + "/" + space + "/"

    private suspend fun <T> blocking(work: () -> T): T = withContext(Dispatchers.IO) {
        try {
            work()
        } catch (error: IOException) {
            throw error
        } catch (error: Exception) {
            throw IOException(error.javaClass.simpleName, error)
        }
    }

    override suspend fun ensureSpace(space: String) = blocking { SmbClient.mkdirs(spaceUrl(space), context) }

    override suspend fun list(space: String): List<RemoteSyncFile> = blocking {
        SmbClient.listFiles(spaceUrl(space), context).map { (name, size, modified) -> RemoteSyncFile(name, "$modified:$size", size) }
    }

    override suspend fun read(space: String, name: String, limit: Int): ByteArray = blocking { SmbClient.read(spaceUrl(space) + name, context, limit) }

    override suspend fun write(space: String, name: String, bytes: ByteArray) = blocking {
        SmbClient.mkdirs(spaceUrl(space), context)
        SmbClient.writeReplacing(spaceUrl(space) + name, context, bytes)
    }

    override suspend fun delete(space: String, name: String) = blocking { SmbClient.delete(spaceUrl(space) + name, context) }
}

/** Why a location can't be opened right now, for the UI (FEATURES-27.1.md §10 decision 5). */
sealed interface SyncLocationProblem {
    /** The linked account's token lacks the write scope the provider needs: sign in again. */
    data class NeedsConsent(val kind: MediaSourceKind) : SyncLocationProblem

    /** The account or saved login the location names is gone from this device. */
    data object LoginMissing : SyncLocationProblem
}

class SyncLocationException(val problem: SyncLocationProblem) : IOException(problem.toString())

/**
 * Opens the storage behind a [SyncLocation] with the logins this device
 * already holds (FEATURES-27.1.md §5.2): the cloud account from the vault
 * (which must carry the provider's write scope), the server login from its
 * store, or the picked folder's tree URI.
 */
class SyncStorageFactory(private val app: EdendaleApplication) {
    private val serverLogins by lazy { ServerLoginStore(app) }
    private val smbCredentials by lazy { SmbCredentialsStore(app) }

    /** Whether the linked account named by [location] has the scope the provider needs to write (null when there is no account). */
    fun problem(location: SyncLocation): SyncLocationProblem? {
        val kind = location.provider.kind ?: return null
        return when {
            location.provider.isCloudAccount -> {
                val account = app.cloudAccounts.vault.account(kind, location.accountKey.orEmpty()) ?: return SyncLocationProblem.LoginMissing
                if (!CloudProviders.hasSyncWriteAccess(kind, account.scopes)) SyncLocationProblem.NeedsConsent(kind) else null
            }
            location.provider == SyncProvider.SMB -> null
            location.provider.usesServerLogin -> if (serverLogins.get(kind, location.host.orEmpty(), location.port) == null) SyncLocationProblem.LoginMissing else null
            else -> null
        }
    }

    @Throws(IOException::class)
    fun open(location: SyncLocation): SyncStorage {
        problem(location)?.let { throw SyncLocationException(it) }
        val cloud = app.cloudAccounts
        return when (location.provider) {
            SyncProvider.FOLDER -> FolderSyncStorage(app, Uri.parse(location.path ?: throw SyncLocationException(SyncLocationProblem.LoginMissing)))
            SyncProvider.GOOGLE_DRIVE -> GoogleDriveSyncStorage(provider(location), location.googleFolderId)
            SyncProvider.ONE_DRIVE -> OneDriveSyncStorage(provider(location), cloud.http)
            SyncProvider.DROPBOX -> DropboxSyncStorage(provider(location))
            SyncProvider.WEBDAV -> {
                val base = location.path ?: throw SyncLocationException(SyncLocationProblem.LoginMissing)
                val login = serverLogins.get(MediaSourceKind.WEBDAV, location.host.orEmpty(), location.port)
                WebDavSyncStorage(WebDavConnector(base, login, cloud.http), base)
            }
            SyncProvider.SMB -> {
                val base = location.path ?: throw SyncLocationException(SyncLocationProblem.LoginMissing)
                SmbSyncStorage(base, SmbClient.context(smbCredentials.getCredentials(location.host.orEmpty())))
            }
            SyncProvider.SFTP -> {
                val host = location.host ?: throw SyncLocationException(SyncLocationProblem.LoginMissing)
                val port = location.port ?: com.babasama.edendale.connectors.Sftp.DEFAULT_PORT
                val login = serverLogins.get(MediaSourceKind.SFTP, host, port) ?: serverLogins.get(MediaSourceKind.SFTP, host, null)
                    ?: throw SyncLocationException(SyncLocationProblem.LoginMissing)
                SftpSyncStorage(host, port, login, app.libraryRepository.sshHostKeys, location.path ?: "/")
            }
        }
    }

    private fun provider(location: SyncLocation): ProviderHttp {
        val kind = location.provider.kind ?: throw SyncLocationException(SyncLocationProblem.LoginMissing)
        return ProviderHttp(kind, location.accountKey ?: throw SyncLocationException(SyncLocationProblem.LoginMissing), app.cloudAccounts.tokens, app.cloudAccounts.http)
    }
}
