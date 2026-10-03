package com.babasama.edendale.connectors

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Canonical, credential-free URLs for remote library items and folders (H.1,
 * Apple's `SourceURL`). The library persists them, so their shapes must stay
 * stable:
 *
 *     smb://host/share/path/Name.ext
 *     nfs://host/export/path/Name.ext
 *     sftp://host[:port]/path/Name.ext
 *     davs://host[:port]/path/Name.ext        (dav:// for plain HTTP)
 *     s3://<account>/<bucket>/<key path>/Name.ext
 *     gdrive://<account>/<fileId>/Name.ext
 *     onedrive://<account>/<driveId>/<itemId>/Name.ext
 *     dropbox://<account>/<fileId>/Name.ext    (the percent-encoded `id:…`)
 *
 * Every item URL ends with the real file name, so the filename parser runs
 * unchanged; a stable provider ID sits before it, so renames and Drive's
 * duplicate names don't collide. For the account providers the URL host is
 * the account key, which is also the key their credential is stored under,
 * as the host is for SMB.
 */
object SourceUrl {

    // MARK: - Account keys

    /**
     * The first 32 hex digits of SHA-256(`kind:subject`), where the subject is
     * Google's `sub`, the Microsoft user `id`, or Dropbox's `account_id`.
     * Hostname-safe, the same on every device, and it doesn't expose an email.
     */
    fun accountKey(kind: MediaSourceKind, subject: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("${kind.raw}:$subject".toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /** The account key for an S3 source: `endpoint|bucket|accessKeyID`, the endpoint lowercased without its trailing `/`. */
    fun s3AccountKey(endpoint: String, bucket: String, accessKeyId: String): String =
        accountKey(MediaSourceKind.S3, "${endpoint.lowercase().trim('/')}|$bucket|$accessKeyId")

    // MARK: - Building

    /**
     * An account-provider URL: `<scheme>://<account>/<id>/…/<name>`. [ids] are
     * the provider identifiers before the name (one for Drive and Dropbox,
     * drive then item for OneDrive). [query] carries listing hints on folder
     * URLs only, such as a Drive folder's shared drive.
     */
    fun accountItem(
        kind: MediaSourceKind,
        account: String,
        ids: List<String>,
        name: String,
        query: List<Pair<String, String>> = emptyList(),
    ): String {
        require(kind.isCloudAccount) { "accountItem is for account kinds" }
        val path = (ids + name).joinToString("/") { encodeSegment(it) }
        val queryPart = if (query.isEmpty()) "" else "?" + query.joinToString("&") { (key, value) ->
            "${encodeQuery(key)}=${encodeQuery(value)}"
        }
        return "${kind.scheme}://$account/$path$queryPart"
    }

    /** An S3 object or prefix URL, `s3://<account>/<bucket>/<key>`. A prefix (folder) key ends in `/`, as S3 spells it. */
    fun s3(account: String, bucket: String, key: String): String {
        val segments = listOf(bucket) + key.split('/')
        return "s3://$account/" + segments.joinToString("/") { encodeSegment(it) }
    }

    /** A server URL (SMB, NFS, SFTP, WebDAV), each path segment percent-encoded on its own. */
    fun server(
        scheme: String,
        host: String,
        port: Int? = null,
        pathSegments: List<String>,
        isDirectory: Boolean = false,
    ): String? {
        if (host.isBlank()) return null
        val authority = if (port != null) "$host:$port" else host
        var path = "/" + pathSegments.filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }
        if (isDirectory && !path.endsWith("/")) path += "/"
        return "$scheme://$authority$path"
    }

    // MARK: - Parsing

    /** The parts of an account-provider URL. */
    data class AccountItem(
        val kind: MediaSourceKind,
        val account: String,
        /** Provider identifiers, before the name. */
        val ids: List<String>,
        /** The file or folder name, decoded. */
        val name: String,
        val query: List<Pair<String, String>>,
    ) {
        fun queryValue(name: String): String? = query.firstOrNull { it.first == name }?.second
    }

    /** Parses `gdrive:`, `onedrive:`, and `dropbox:` URLs; null for other kinds or malformed URLs. */
    fun parseAccountItem(url: String): AccountItem? {
        val parts = split(url) ?: return null
        val kind = MediaSourceKind.fromScheme(parts.scheme)?.takeIf { it.isCloudAccount } ?: return null
        if (parts.host.isEmpty()) return null
        val segments = parts.rawPath.split('/').drop(1).map(::decode)
        val idCount = if (kind == MediaSourceKind.ONE_DRIVE) 2 else 1
        if (segments.size != idCount + 1 || segments.any { it.isEmpty() }) return null
        return AccountItem(
            kind = kind,
            account = parts.host,
            ids = segments.take(idCount),
            name = segments[idCount],
            query = parseQuery(parts.rawQuery),
        )
    }

    /** The parts of an S3 URL. */
    data class S3Item(
        val account: String,
        val bucket: String,
        /** The object key or prefix; prefixes end in `/`, and the bucket root is "". */
        val key: String,
    ) {
        val isPrefix: Boolean get() = key.isEmpty() || key.endsWith("/")
    }

    fun parseS3(url: String): S3Item? {
        val parts = split(url) ?: return null
        if (MediaSourceKind.fromScheme(parts.scheme) != MediaSourceKind.S3 || parts.host.isEmpty()) return null
        val segments = parts.rawPath.split('/').drop(1).map(::decode)
        val bucket = segments.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        return S3Item(account = parts.host, bucket = bucket, key = segments.drop(1).joinToString("/"))
    }

    /** The decoded path segments of a server URL (SMB, NFS, SFTP, WebDAV). */
    fun pathSegments(url: String): List<String> =
        split(url)?.rawPath?.split('/')?.filter { it.isNotEmpty() }?.map(::decode).orEmpty()

    /** The port of a server URL, when it names one. */
    fun port(url: String): Int? = split(url)?.port

    /**
     * The key a source's credential is stored under: the host for server
     * kinds, the account key (also the URL host) for account kinds.
     */
    fun credentialHost(url: String): String? = split(url)?.host?.lowercase()?.takeIf { it.isNotEmpty() }

    /** The decoded last path segment: the file or folder name. */
    fun fileName(url: String): String? = split(url)?.rawPath?.trimEnd('/')?.substringAfterLast('/')?.let(::decode)

    // MARK: - Encoding

    /**
     * RFC 3986 unreserved characters plus the sub-delimiters that are safe in
     * one path segment, ASCII only. `/`, `:`, `;`, `?`, `#`, `%`, and spaces
     * are always escaped, so a name can never split a segment.
     */
    private const val SEGMENT_ALLOWED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,=@"

    /** Query keys and values escape `&`, `=`, and `+` as well. */
    private const val QUERY_ALLOWED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$'()*,@:/"

    fun encodeSegment(segment: String): String = percentEncode(segment, SEGMENT_ALLOWED)

    private fun encodeQuery(value: String): String = percentEncode(value, QUERY_ALLOWED)

    private fun percentEncode(value: String, allowed: String): String {
        val out = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val char = (byte.toInt() and 0xFF).toChar()
            if (byte >= 0 && char in allowed) out.append(char) else out.append("%%%02X".format(byte.toInt() and 0xFF))
        }
        return out.toString()
    }

    /** Percent-decoding for paths: `+` stays a plus, malformed escapes stay as written. */
    fun decode(value: String): String {
        if ('%' !in value) return value
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            val char = value[index]
            val hex = if (char == '%' && index + 2 < value.length) {
                value.substring(index + 1, index + 3).toIntOrNull(16)
            } else {
                null
            }
            if (hex != null) {
                bytes.write(hex)
                index += 3
            } else {
                bytes.write(char.toString().toByteArray(Charsets.UTF_8))
                index += 1
            }
        }
        return bytes.toString(Charsets.UTF_8.name())
    }

    private fun parseQuery(rawQuery: String?): List<Pair<String, String>> =
        rawQuery?.split('&')?.filter { it.isNotEmpty() }?.map { pair ->
            decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
        }.orEmpty()

    private data class Parts(val scheme: String, val host: String, val port: Int?, val rawPath: String, val rawQuery: String?)

    /** `scheme://host[:port]/path[?query]`, split without java.net.URI's strictness about names. */
    private fun split(url: String): Parts? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd)
        val rest = url.substring(schemeEnd + 3).substringBefore('#')
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd).substringAfterLast('@')
        val afterAuthority = rest.substring(authorityEnd)
        val rawPath = afterAuthority.substringBefore('?')
        val rawQuery = afterAuthority.substringAfter('?', "").takeIf { '?' in afterAuthority }
        val portText = authority.substringAfterLast(':', "")
        val port = portText.toIntOrNull()
        val host = if (port != null) authority.substringBeforeLast(':') else authority
        return Parts(scheme, decode(host), port, rawPath, rawQuery)
    }
}
