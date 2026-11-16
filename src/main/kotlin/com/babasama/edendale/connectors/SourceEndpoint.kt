package com.babasama.edendale.connectors

/**
 * Where a library source is reached from, as Play From names it (27.1): this
 * device, a server's address, or a storage account. One share linked twice —
 * as `smb://longs-mac-studio.local/Extra` and through Tailscale as
 * `smb://100.101.102.103/Extra` — gives both copies the share's name, so the
 * folder name can't tell them apart; the endpoint can. Pure: the JVM suite
 * covers it.
 */
sealed interface SourceEndpoint {
    /** Orders endpoints without any translated text: "" for this device, the address, or `kind:identity`. */
    val sortKey: String

    /** A local folder, or a file added on its own. */
    data object ThisDevice : SourceEndpoint {
        override val sortKey: String get() = ""
    }

    /** A server: its host, lowercased, and its port when it isn't the scheme's default. */
    data class Server(val host: String, val port: Int? = null) : SourceEndpoint {
        val address: String get() = if (port != null) "$host:$port" else host
        override val sortKey: String get() = address
    }

    /**
     * A storage account: a cloud provider with the account's email (else its
     * name), or S3 with its bucket when the endpoint isn't known. [identity]
     * is null when nothing names the account.
     */
    data class Account(val kind: MediaSourceKind, val identity: String?) : SourceEndpoint {
        override val sortKey: String get() = "${kind.raw}:${identity.orEmpty().lowercase()}"
    }

    companion object {
        /**
         * The endpoint of a source of [kind] linked at [treeUri]; [endpoint] is
         * what the library recorded where the URL can't say (v5): a cloud
         * account's email or name, or an S3 endpoint URL. A null kind, a local
         * folder, and a file added on its own (no [treeUri]) are this device.
         */
        fun of(kind: MediaSourceKind?, treeUri: String?, endpoint: String?): SourceEndpoint {
            if (kind == null || kind == MediaSourceKind.LOCAL || treeUri == null) return ThisDevice
            val recorded = endpoint?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                kind.isCloudAccount -> Account(kind, recorded)
                // The URL's host is the account key, a hash; the endpoint URL names the server.
                kind == MediaSourceKind.S3 ->
                    recorded?.let { server(if ("://" in it) it else "https://$it") }
                        ?: Account(kind, SourceUrl.parseS3(treeUri)?.bucket)
                else -> server(treeUri) ?: Account(kind, null)
            }
        }

        /**
         * The server a URL connects to: the host lowercased without its login
         * or a trailing dot (IPv6 keeps its brackets), and the port only when
         * it isn't the scheme's default. Null when the URL names no host.
         */
        fun server(url: String): Server? {
            val scheme = url.substringBefore("://", "").lowercase()
            if (scheme.isEmpty()) return null
            val host = SourceUrl.credentialHost(url)?.trimEnd('.')?.takeIf { it.isNotEmpty() } ?: return null
            val port = SourceUrl.port(url)?.takeIf { it != defaultPort(scheme) }
            return Server(host, port)
        }

        /** The port a scheme connects to when its URL names none. */
        fun defaultPort(scheme: String): Int? = when (scheme.lowercase()) {
            "smb", "smb2" -> 445
            "nfs" -> 2049
            "sftp", "ssh" -> 22
            "davs", "https" -> 443
            "dav", "http" -> 80
            else -> null
        }

        /** A cloud account's identity for its label: the email, else the name it goes by. */
        fun accountIdentity(email: String?, displayName: String?): String? =
            email?.trim()?.takeIf { it.isNotEmpty() } ?: displayName?.trim()?.takeIf { it.isNotEmpty() }

        /**
         * The account a cloud source's readable path starts with ("me@example.com
         * › Movies"), for a source linked before the library recorded it. Null
         * when the path starts with the provider's raw name, which an account
         * with neither email nor name was given.
         */
        fun accountFromDisplayPath(kind: MediaSourceKind, displayPath: String?): String? =
            displayPath?.substringBefore(" › ")?.trim()?.takeIf { it.isNotEmpty() && it != kind.raw }

        /**
         * The text that names [endpoint]: "This device", "longs-mac-studio.local",
         * "100.101.102.103", "Google Drive - me@example.com". The defaults are
         * English so the JVM tests need no Android resources; screens pass the
         * translated provider names and templates.
         */
        fun label(
            endpoint: SourceEndpoint,
            provider: (MediaSourceKind) -> String = ::englishProvider,
            thisDevice: String = "This device",
            withIdentity: (provider: String, identity: String) -> String = { name, identity -> "$name - $identity" },
        ): String = when (endpoint) {
            ThisDevice -> thisDevice
            is Server -> endpoint.address
            is Account -> endpoint.identity?.let { withIdentity(provider(endpoint.kind), it) } ?: provider(endpoint.kind)
        }

        private fun englishProvider(kind: MediaSourceKind): String = when (kind) {
            MediaSourceKind.LOCAL -> "Local Folder"
            MediaSourceKind.SMB -> "SMB"
            MediaSourceKind.NFS -> "NFS"
            MediaSourceKind.SFTP -> "SFTP"
            MediaSourceKind.WEBDAV -> "WebDAV"
            MediaSourceKind.S3 -> "S3-Compatible Storage"
            MediaSourceKind.GOOGLE_DRIVE -> "Google Drive"
            MediaSourceKind.ONE_DRIVE -> "OneDrive"
            MediaSourceKind.DROPBOX -> "Dropbox"
        }
    }
}
