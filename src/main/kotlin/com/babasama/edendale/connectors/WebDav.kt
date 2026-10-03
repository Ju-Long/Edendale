package com.babasama.edendale.connectors

import com.babasama.edendale.remote.HttpAuthSession
import com.babasama.edendale.remote.RemoteContentResolver
import com.babasama.edendale.remote.RemoteFailure
import com.babasama.edendale.remote.RemoteHttp
import com.babasama.edendale.remote.RemoteRequest
import com.babasama.edendale.remote.RemoteSourceException
import com.babasama.edendale.remote.ServerLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory

/**
 * WebDAV addresses and listings (H.3, Apple's `WebDAVConnector`): Nextcloud
 * and ownCloud (`/remote.php/dav/files/<user>/`), Synology, QNAP, pCloud,
 * Koofr, or `rclone serve webdav`. Item URLs are `davs://host[:port]/path`
 * for HTTPS and `dav://` for plain HTTP. Pure.
 */
object WebDav {

    const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:displayname/></d:prop></d:propfind>"""

    /**
     * `https://host/path` → `davs://host/path/`, `http://…` → `dav://…`; a bare
     * host means HTTPS. Drops a login, query, or fragment typed into the address.
     */
    fun canonicalRoot(address: String): String? {
        var text = address.trim()
        if (text.isEmpty()) return null
        if ("://" !in text) text = "https://$text"
        val scheme = when (text.substringBefore("://").lowercase()) {
            "https", "davs", "webdavs" -> "davs"
            "http", "dav", "webdav" -> "dav"
            else -> return null
        }
        val rest = text.substringAfter("://").substringBefore('#').substringBefore('?')
        val authority = rest.substringBefore('/').substringAfterLast('@')
        if (authority.isEmpty() || authority.startsWith(":")) return null
        var path = rest.substring(rest.indexOf('/').takeIf { it >= 0 } ?: rest.length)
        if (path.isEmpty()) path = "/"
        if (!path.endsWith("/")) path += "/"
        return "$scheme://$authority$path"
    }

    /** Collections are always addressed with a trailing slash: servers redirect the bare form. */
    fun directoryUrl(url: String): String = if (url.endsWith("/")) url else "$url/"

    /** The HTTP address behind a canonical `dav(s)://` URL. */
    fun httpUrl(url: String): String? = when (url.substringBefore("://", "").lowercase()) {
        "davs" -> "https://" + url.substringAfter("://")
        "dav" -> "http://" + url.substringAfter("://")
        else -> null
    }

    /**
     * The entries of a `multistatus` response, without the listed folder
     * itself; null when the body isn't one. Element names match by local
     * name, so any namespace prefix works (Apache's `lp1:` included).
     */
    fun parseMultistatus(xml: ByteArray, requestUrl: String, canonicalDirectory: String): List<ConnectorEntry>? {
        val responses = parseResponses(xml) ?: return null
        val listedPath = normalizedPath(pathOf(requestUrl, requestUrl) ?: return null)
        val scheme = canonicalDirectory.substringBefore("://")
        val host = SourceUrl.credentialHost(canonicalDirectory) ?: return null
        val port = SourceUrl.port(canonicalDirectory)

        return responses.mapNotNull { response ->
            val path = pathOf(response.href ?: return@mapNotNull null, requestUrl) ?: return@mapNotNull null
            if (normalizedPath(path) == listedPath) return@mapNotNull null
            val segments = path.split('/').filter { it.isNotEmpty() }
            val name = segments.lastOrNull() ?: return@mapNotNull null
            val url = SourceUrl.server(scheme, host, port, segments, isDirectory = response.isCollection)
                ?: return@mapNotNull null
            ConnectorEntry(
                name = name,
                url = url,
                isDirectory = response.isCollection,
                size = if (response.isCollection) null else response.size,
                modifiedEpochMillis = response.modified?.let(::parseHttpDate),
            )
        }
    }

    /** RFC 1123 (`Tue, 15 Nov 1994 12:45:26 GMT`), the WebDAV date format. */
    fun parseHttpDate(value: String): Long? = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }.parse(value.trim())?.time
    }.getOrNull()

    /** The decoded path of an `href`: an absolute URL or an absolute path, percent-encoded or not. */
    private fun pathOf(href: String, base: String): String? {
        val trimmed = href.trim()
        val rawPath = when {
            "://" in trimmed -> trimmed.substringAfter("://").let { rest ->
                rest.indexOf('/').takeIf { it >= 0 }?.let { rest.substring(it) } ?: "/"
            }
            trimmed.startsWith("/") -> trimmed
            // A relative href resolves against the listed folder.
            else -> pathOf(base, base)?.let { directoryUrl(it) + trimmed } ?: return null
        }.substringBefore('?').substringBefore('#')
        return SourceUrl.decode(rawPath)
    }

    private fun normalizedPath(path: String): String = path.trimEnd('/').ifEmpty { "/" }

    private class Response {
        var href: String? = null
        var isCollection = false
        var size: Long? = null
        var modified: String? = null
    }

    /** `response` elements with the properties their 200 `propstat` blocks report. */
    private fun parseResponses(xml: ByteArray): List<Response>? {
        val responses = mutableListOf<Response>()
        var sawMultistatus = false
        val handler = object : DefaultHandler() {
            private val stack = ArrayDeque<String>()
            private val text = StringBuilder()
            private var current: Response? = null
            // One propstat's properties, kept only when its status is 200 (or missing).
            private var propCollection = false
            private var propSize: Long? = null
            private var propModified: String? = null
            private var propStatus: String? = null

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                val name = local(localName, qName)
                if (stack.isEmpty() && name == "multistatus") sawMultistatus = true
                when (name) {
                    "response" -> current = Response()
                    "propstat" -> {
                        propCollection = false
                        propSize = null
                        propModified = null
                        propStatus = null
                    }
                    "collection" -> if ("resourcetype" in stack) propCollection = true
                }
                stack.addLast(name)
                text.setLength(0)
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val name = local(localName, qName)
                stack.removeLastOrNull()
                val value = text.toString().trim()
                val response = current
                when (name) {
                    "href" -> if (response != null && stack.lastOrNull() == "response") response.href = value
                    "getcontentlength" -> propSize = value.toLongOrNull()
                    "getlastmodified" -> propModified = value.ifEmpty { null }
                    "status" -> if (stack.lastOrNull() == "propstat") propStatus = value
                    "propstat" -> if (response != null && (propStatus == null || " 200" in propStatus!!)) {
                        if (propCollection) response.isCollection = true
                        if (response.size == null) response.size = propSize
                        if (response.modified == null) response.modified = propModified
                    }
                    "response" -> response?.let { responses += it }.also { current = null }
                }
                text.setLength(0)
            }

            private fun local(localName: String?, qName: String?): String =
                (localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':') ?: "").lowercase()
        }
        return try {
            val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
            // No external entities or DTDs from a server's response.
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            factory.newSAXParser().parse(InputSource(ByteArrayInputStream(xml)), handler)
            if (sawMultistatus) responses else null
        } catch (error: Exception) {
            null
        }
    }
}

/**
 * A WebDAV server (H.3): listing with `PROPFIND` and `Depth: 1` (most servers
 * disable `infinity`, so enumeration walks breadth-first), answering Basic
 * or Digest with the saved login. Plain HTTP waits for D10.
 */
class WebDavConnector(
    /** The folder the viewer entered, canonical (`davs://…/`). */
    root: String,
    login: ServerLogin?,
    private val http: RemoteHttp,
    /** Plain `dav://` waits for D10; the JVM suite's local server turns it on. */
    private val allowPlainHttp: Boolean = false,
) : MediaConnector {
    override val kind = MediaSourceKind.WEBDAV
    override val root: String = WebDav.directoryUrl(root)
    val auth = HttpAuthSession(login)
    override val accountLabel: String? = login?.user?.takeIf { it.isNotEmpty() }

    override suspend fun list(directory: String): List<ConnectorEntry> = withContext(Dispatchers.IO) {
        val folder = WebDav.directoryUrl(directory)
        if (!allowPlainHttp && folder.startsWith("dav://", ignoreCase = true)) throw ConnectorException(ConnectorFailure.InsecureConnection)
        val httpUrl = WebDav.httpUrl(folder) ?: throw ConnectorException(ConnectorFailure.InvalidAddress)
        val host = SourceUrl.credentialHost(folder) ?: throw ConnectorException(ConnectorFailure.InvalidAddress)
        val path = SourceUrl.pathSegments(folder).joinToString("/", prefix = "/")
        val request = RemoteRequest(
            url = httpUrl,
            headers = mapOf("Depth" to "1"),
            method = "PROPFIND",
            body = WebDav.PROPFIND_BODY.toByteArray(Charsets.UTF_8),
            contentType = "application/xml; charset=utf-8",
        )
        val response = try {
            auth.execute(http, request, bodyLimit = MAX_LISTING_BYTES)
        } catch (error: IOException) {
            throw ConnectorException(ConnectorFailure.Unreachable(host), error)
        }
        when (response.status) {
            207 -> Unit
            401, 403 -> throw ConnectorException(ConnectorFailure.AuthenticationFailed(host))
            // 200 or 405 here means the address isn't a WebDAV folder.
            else -> throw ConnectorException(ConnectorFailure.ListingFailed(path))
        }
        val entries = WebDav.parseMultistatus(response.body, httpUrl, folder)
            ?: throw ConnectorException(ConnectorFailure.ListingFailed(path))
        entries.filterNot { it.isHidden }.sortedWith(FOLDERS_FIRST)
    }

    companion object {
        /** A listing larger than this is cut off and fails to parse rather than exhausting memory. */
        const val MAX_LISTING_BYTES = 32 shl 20

        val FOLDERS_FIRST: Comparator<ConnectorEntry> =
            compareBy<ConnectorEntry> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
}

/**
 * A WebDAV file's bytes for the player (H.3): a GET to the file, with the
 * server's challenge answered by the saved login. A 401 makes the byte
 * source ask again, which fetches a fresh challenge (a new Digest nonce).
 */
class WebDavContentResolver(
    private val url: String,
    private val auth: HttpAuthSession,
    private val http: RemoteHttp,
) : RemoteContentResolver {
    override val kind = MediaSourceKind.WEBDAV

    override fun contentRequest(refresh: Boolean): RemoteRequest {
        val httpUrl = WebDav.httpUrl(url) ?: throw IOException("Not a WebDAV URL")
        val request = RemoteRequest(httpUrl)
        if (refresh) {
            // Ask without a login to get the server's current challenge.
            val probe = http.newCall(request.with("Range", "bytes=0-0"), bodyLimit = 1).execute()
            if (probe.status == 401 && !auth.learn(probe.header("WWW-Authenticate"))) {
                throw RemoteSourceException(kind, RemoteFailure.SignInRequired)
            }
        }
        return auth.authorize(request)
    }
}
