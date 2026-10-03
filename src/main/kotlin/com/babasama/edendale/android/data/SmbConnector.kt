package com.babasama.edendale.android.data

import com.babasama.edendale.android.AppStrings
import com.babasama.edendale.connectors.ConnectorEntry
import com.babasama.edendale.connectors.MediaConnector
import com.babasama.edendale.connectors.MediaSourceKind
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An SMB share through jcifs (H.1.2). Entries carry jcifs's own URL
 * spelling (`SmbFile.url`), which is what the library has always stored for
 * SMB items, so existing rows keep matching on rescan.
 */
internal class SmbConnector(
    /** The source's `smb://host/share/folder/` URL. */
    override val root: String,
    private val credentials: Pair<String, String>?,
    private val strings: AppStrings,
) : MediaConnector {
    override val kind = MediaSourceKind.SMB
    override val accountLabel: String? = credentials?.first?.takeIf { it.isNotBlank() }
    private val context = SmbClient.context(credentials)

    override suspend fun validate() = withContext(Dispatchers.IO) {
        val folder = SmbFile(root, context)
        if (!folder.exists()) error(strings.shareUnreachable)
        if (!folder.isDirectory) error(strings.addressIsFile)
    }

    /**
     * jcifs answers `isDirectory` and `isFile` from the directory listing, so
     * a failure here means the folder itself couldn't be read; it fails the
     * whole folder rather than dropping one entry, so a partial listing is
     * never mistaken for deleted files.
     */
    override suspend fun list(directory: String): List<ConnectorEntry> = withContext(Dispatchers.IO) {
        SmbFile(directory, context).listFiles().orEmpty().mapNotNull { file ->
            val name = file.name?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            when {
                file.isDirectory -> ConnectorEntry(name, file.url.toString(), isDirectory = true)
                file.isFile -> ConnectorEntry(
                    name = name,
                    url = file.url.toString(),
                    isDirectory = false,
                    size = file.length(),
                    modifiedEpochMillis = file.lastModified().takeIf { it > 0 },
                )
                else -> null
            }
        }
    }
}
