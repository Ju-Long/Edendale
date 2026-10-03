package com.babasama.edendale.connectors

/**
 * Where a library source's files live (H.1; Apple's `MediaSourceKind`). The
 * raw values are persisted in `library_folder.kind` (D.2), so a value must
 * never be renamed.
 */
enum class MediaSourceKind(val raw: String) {
    /** A folder picked through the system document picker (`content://`). */
    LOCAL("local"),
    SMB("smb"),
    NFS("nfs"),
    SFTP("sftp"),
    WEBDAV("webdav"),
    S3("s3"),
    GOOGLE_DRIVE("gdrive"),
    ONE_DRIVE("onedrive"),
    DROPBOX("dropbox"),
    ;

    val isRemote: Boolean get() = this != LOCAL

    companion object {
        fun fromRaw(raw: String?): MediaSourceKind? = entries.firstOrNull { it.raw == raw }

        /**
         * The kind a stored source URI belongs to, from its scheme: the D.2
         * backfill for rows written before `kind` existed, and the value new
         * rows record. Null for a URI no connector reads.
         */
        fun forSourceUri(uri: String): MediaSourceKind? = when (uri.substringBefore("://", "").lowercase()) {
            "content" -> LOCAL
            "smb" -> SMB
            "nfs" -> NFS
            "sftp" -> SFTP
            "dav", "davs" -> WEBDAV
            "s3" -> S3
            "gdrive" -> GOOGLE_DRIVE
            "onedrive" -> ONE_DRIVE
            "dropbox" -> DROPBOX
            else -> null
        }
    }
}

/**
 * A source's last scan outcome (D.3), stored in `library_folder.status`; null
 * means the last scan worked (or none ran yet).
 */
enum class SourceStatus(val raw: String) {
    /** The host didn't answer or timed out. */
    OFFLINE("offline"),

    /** The server refused the saved login, or there is none. */
    NEEDS_SIGN_IN("needsSignIn"),
    ;

    companion object {
        fun fromRaw(raw: String?): SourceStatus? = entries.firstOrNull { it.raw == raw }
    }
}
