package com.babasama.edendale.android.data

import android.net.Uri
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties

/**
 * One jcifs configuration for the whole app, shared by the library scanner and
 * the playback data source.
 *
 * The library defaults negotiate from SMB1 — which current Windows and NAS
 * builds refuse outright — and stop at SMB 2.1, so shares that require SMB3 are
 * unreachable. The defaults also leave the socket timeouts long enough that an
 * unreachable host reads as a hang rather than an error. Both are pinned here.
 */
internal object SmbClient {

    private val base: CIFSContext by lazy {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", "8000")
            setProperty("jcifs.smb.client.responseTimeout", "20000")
            setProperty("jcifs.smb.client.soTimeout", "35000")
        }
        BaseContext(PropertyConfiguration(properties))
    }

    /** Guest access when no username was stored, NTLM otherwise. */
    fun context(credentials: Pair<String, String>?): CIFSContext = when (credentials) {
        null -> base.withGuestCrendentials()
        else -> base.withCredentials(
            NtlmPasswordAuthenticator(null, credentials.first, credentials.second),
        )
    }

    /**
     * Accepts what people actually type — `192.168.1.10`, `192.168.1.10/media`,
     * `smb://192.168.1.10/media`, `\\192.168.1.10\media` — and returns a
     * canonical `smb://host/share/` URL, or null when no host can be read.
     */
    fun normalizeUrl(input: String): String? {
        val path = input.trim()
            .replace('\\', '/')
            .removePrefix("smb://")
            .removePrefix("//")
            .trim('/')
        if (path.isEmpty()) return null
        if (path.substringBefore('/').isEmpty()) return null
        return "smb://$path/"
    }

    /**
     * The directories directly under [url], which is the share list when [url]
     * is a bare `smb://host/`. Administrative shares (`IPC$`, `C$`) are dropped:
     * they are never media and several of them refuse to be listed at all.
     *
     * Blocking network IO — call it from a background dispatcher.
     */
    fun listDirectories(url: String, credentials: Pair<String, String>?): List<String> =
        SmbFile(url, context(credentials))
            .listFiles()
            .orEmpty()
            .filter { entry -> runCatching { entry.isDirectory }.getOrDefault(false) }
            .map { entry -> entry.name.trimEnd('/') }
            .filterNot { name -> name.endsWith("$") }
            .sortedBy { name -> name.lowercase() }

    /**
     * The host an SMB source belongs to, or null for a local SAF tree. This is
     * the key the credential store is written under, so removal must derive it
     * exactly the way import did.
     */
    fun hostOf(treeUri: String): String? =
        if (treeUri.startsWith("smb://")) Uri.parse(treeUri).host?.takeIf { it.isNotBlank() } else null
}
