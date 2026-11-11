package com.babasama.edendale.sync

import com.babasama.edendale.connectors.MediaSourceKind

/**
 * Where a device syncs (FEATURES-27.1.md §5.2): the kinds of storage a
 * location can be, with the handoff's `provider` spelling (SYNC-PROTOCOL.md
 * §9.6) and the library source kind whose login or account it reuses.
 */
enum class SyncProvider(val raw: String, val kind: MediaSourceKind?) {
    /** A folder picked in the system picker; never transferred by handoff. */
    FOLDER("folder", MediaSourceKind.LOCAL),
    GOOGLE_DRIVE("googleDrive", MediaSourceKind.GOOGLE_DRIVE),
    ONE_DRIVE("oneDrive", MediaSourceKind.ONE_DRIVE),
    DROPBOX("dropbox", MediaSourceKind.DROPBOX),
    WEBDAV("webdav", MediaSourceKind.WEBDAV),
    SMB("smb", MediaSourceKind.SMB),
    SFTP("sftp", MediaSourceKind.SFTP),
    ;

    val isCloudAccount: Boolean get() = kind?.isCloudAccount == true
    val usesServerLogin: Boolean get() = kind?.usesServerLogin == true

    companion object {
        fun fromRaw(raw: String?): SyncProvider? = entries.firstOrNull { it.raw == raw }
        fun forKind(kind: MediaSourceKind): SyncProvider? = entries.firstOrNull { it.kind == kind }
    }
}

/**
 * A device's one sync location (§5.1): the provider, the space name, and
 * what finds the storage again. An account location names the linked
 * account's key; a server location names the saved login's host and port and
 * the folder inside it; a folder location keeps the picked tree's URI. Logins
 * themselves stay in their own encrypted stores.
 */
data class SyncLocation(
    val provider: SyncProvider,
    val space: String,
    /** Cloud providers: the linked account's key. */
    val accountKey: String? = null,
    /** Servers: the saved login's host. */
    val host: String? = null,
    val port: Int? = null,
    /** Servers: the base folder (a canonical `dav(s)://` URL, an `smb://host/share/…/` URL, or an absolute SFTP path); folders: the tree URI. */
    val path: String? = null,
    /** Google Drive: the space folder's Drive ID once known, for handoffs across ecosystems (§6.4). */
    val googleFolderId: String? = null,
) {
    init {
        require(SpaceName.isValid(space)) { "Invalid space name" }
    }

    /**
     * Whether [other] reads and writes the same device files: the same
     * storage, the same folder in it, and the same space. The Google folder
     * ID a sync learns later doesn't make it another location.
     */
    fun sameFiles(other: SyncLocation): Boolean =
        provider == other.provider &&
            space == other.space &&
            accountKey == other.accountKey &&
            host.equals(other.host, ignoreCase = true) &&
            port == other.port &&
            path == other.path

    companion object {
        /**
         * The location to remember as left behind once [next] replaces
         * [current] (FEATURES-27.1.md §5.1; SYNC-PROTOCOL.md §4 "One location
         * per device"): its files stay until the viewer deletes this device's
         * file there, and the app offers to. The latest one replaced wins over
         * an older [remembered] one, as on Apple; a remembered location that
         * [next] uses again is forgotten, so the app never offers to delete
         * the file it is about to write.
         */
        fun previousAfter(current: SyncLocation?, next: SyncLocation, remembered: SyncLocation?): SyncLocation? = when {
            current != null && !current.sameFiles(next) -> current
            remembered != null && remembered.sameFiles(next) -> null
            else -> remembered
        }
    }
}
