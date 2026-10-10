package com.babasama.edendale.connectors

import com.babasama.edendale.remote.RemoteContentResolver
import com.babasama.edendale.remote.RemoteHttp
import com.babasama.edendale.remote.RemoteRequest
import com.babasama.edendale.remote.ServerLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.SAXParserFactory

/**
 * Where an S3-compatible bucket lives (H.5): AWS, Backblaze B2, Cloudflare
 * R2, Wasabi, or MinIO. Not secret, but stored with the key pair so an item
 * URL (whose host is the account key) finds everything it needs to sign.
 */
data class S3Configuration(
    /** `https://s3.us-east-1.amazonaws.com`, `https://<id>.r2.cloudflarestorage.com`, or `http://minio.local:9000`. */
    val endpoint: String,
    /** `us-east-1` for AWS's default, `auto` for Cloudflare R2. */
    val region: String,
    val bucket: String,
    /** `endpoint/bucket/key`, which MinIO and most self-hosted servers need; otherwise `bucket.endpoint/key`. */
    val usesPathStyle: Boolean,
)

/** AWS Signature Version 4 for S3 (H.5.1, Apple's `S3Signer`). Pure. */
class S3Signer(
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val region: String,
    private val service: String = "s3",
) {
    /** [request] signed with an `Authorization` header (listing). */
    fun sign(request: RemoteRequest, nowMillis: Long, payloadHash: String = EMPTY_PAYLOAD_HASH): RemoteRequest {
        val host = hostHeader(request.url) ?: return request
        val timestamp = timestamp(nowMillis)
        val day = timestamp.take(8)
        val headers = listOf("host" to host, "x-amz-content-sha256" to payloadHash, "x-amz-date" to timestamp)
        val signedHeaders = headers.joinToString(";") { it.first }
        val canonical = canonicalRequest(request.method, request.url, headers, signedHeaders, payloadHash)
        val scope = "$day/$region/$service/aws4_request"
        val signature = signature(stringToSign(timestamp, scope, canonical), day)
        return request
            .with("x-amz-date", timestamp)
            .with("x-amz-content-sha256", payloadHash)
            .with(
                "Authorization",
                "AWS4-HMAC-SHA256 Credential=$accessKeyId/$scope, SignedHeaders=$signedHeaders, Signature=$signature",
            )
    }

    /** A pre-signed GET URL for [url], valid for [expiresSeconds]. */
    fun presign(url: String, nowMillis: Long, expiresSeconds: Int = 3600): String? {
        val host = hostHeader(url) ?: return null
        val timestamp = timestamp(nowMillis)
        val day = timestamp.take(8)
        val scope = "$day/$region/$service/aws4_request"
        val base = url.substringBefore('?')
        val query = mutableListOf(
            "X-Amz-Algorithm" to "AWS4-HMAC-SHA256",
            "X-Amz-Credential" to "$accessKeyId/$scope",
            "X-Amz-Date" to timestamp,
            "X-Amz-Expires" to expiresSeconds.toString(),
            "X-Amz-SignedHeaders" to "host",
        )
        val unsigned = "$base?${canonicalQuery(query)}"
        val canonical = canonicalRequest("GET", unsigned, listOf("host" to host), "host", "UNSIGNED-PAYLOAD")
        query += "X-Amz-Signature" to signature(stringToSign(timestamp, scope, canonical), day)
        return "$base?${canonicalQuery(query)}"
    }

    fun canonicalRequest(
        method: String,
        url: String,
        headers: List<Pair<String, String>>,
        signedHeaders: String,
        payloadHash: String,
    ): String {
        // The path is already encoded once, segment by segment; S3 signs it as sent.
        val afterScheme = url.substringAfter("://")
        val path = afterScheme.substring(afterScheme.indexOf('/').takeIf { it >= 0 } ?: afterScheme.length)
            .substringBefore('?').ifEmpty { "/" }
        val rawQuery = url.substringAfter('?', "")
        val query = canonicalQuery(
            rawQuery.split('&').filter { it.isNotEmpty() }.map { pair ->
                SourceUrl.decode(pair.substringBefore('=')) to SourceUrl.decode(pair.substringAfter('=', ""))
            },
        )
        val canonicalHeaders = headers.joinToString("") { "${it.first}:${it.second.trim()}\n" }
        return listOf(method, path, query, canonicalHeaders, signedHeaders, payloadHash).joinToString("\n")
    }

    fun stringToSign(timestamp: String, scope: String, canonicalRequest: String): String =
        listOf("AWS4-HMAC-SHA256", timestamp, scope, hex(sha256(canonicalRequest.toByteArray()))).joinToString("\n")

    fun signature(stringToSign: String, day: String): String {
        var key = "AWS4$secretAccessKey".toByteArray()
        for (part in listOf(day, region, service, "aws4_request")) key = hmac(key, part)
        return hex(hmac(key, stringToSign))
    }

    companion object {
        const val EMPTY_PAYLOAD_HASH = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        /** AWS URI encoding: everything but the unreserved characters. */
        fun uriEncode(value: String): String {
            val out = StringBuilder()
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val char = (byte.toInt() and 0xFF).toChar()
                if (byte >= 0 && char in UNRESERVED) out.append(char) else out.append("%%%02X".format(byte.toInt() and 0xFF))
            }
            return out.toString()
        }

        /** Sorted by name, then value, with both URI-encoded. */
        fun canonicalQuery(items: List<Pair<String, String>>): String =
            items.map { uriEncode(it.first) to uriEncode(it.second) }
                .sortedWith(compareBy({ it.first }, { it.second }))
                .joinToString("&") { "${it.first}=${it.second}" }

        fun hostHeader(url: String): String? {
            val scheme = url.substringBefore("://", "").lowercase()
            val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')
            if (authority.isEmpty()) return null
            val port = authority.substringAfterLast(':', "").toIntOrNull()
            val host = if (port != null) authority.substringBeforeLast(':') else authority
            return if (port == null || (scheme == "https" && port == 443) || (scheme == "http" && port == 80)) host else "$host:$port"
        }

        private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

        fun timestamp(nowMillis: Long): String = TIMESTAMP.format(Instant.ofEpochMilli(nowMillis))

        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun hmac(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data.toByteArray())

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}

/** S3 addressing, listings, and errors (H.5). Pure. */
object S3 {
    /** The HTTPS (or local HTTP) URL of [key] in the bucket. */
    fun bucketUrl(configuration: S3Configuration, key: String, query: List<Pair<String, String>> = emptyList()): String {
        val endpoint = configuration.endpoint.trimEnd('/')
        val scheme = endpoint.substringBefore("://")
        val authority = endpoint.substringAfter("://").substringBefore('/')
        val basePath = endpoint.substringAfter("://").substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }
        val encodedKey = key.split('/').joinToString("/") { S3Signer.uriEncode(it) }
        val url = if (configuration.usesPathStyle) {
            "$scheme://$authority$basePath/${S3Signer.uriEncode(configuration.bucket)}/$encodedKey"
        } else {
            "$scheme://${configuration.bucket}.$authority$basePath/$encodedKey"
        }
        if (query.isEmpty()) return url
        return url + "?" + query.joinToString("&") { "${S3Signer.uriEncode(it.first)}=${S3Signer.uriEncode(it.second)}" }
    }

    /** Path-style unless the endpoint is AWS itself, and always for bucket names with dots (they break virtual-hosted TLS). */
    fun defaultUsesPathStyle(endpoint: String, bucket: String): Boolean {
        if ('.' in bucket) return true
        val host = endpoint.substringAfter("://").substringBefore('/').substringBefore(':')
        return !host.endsWith("amazonaws.com")
    }

    class ListingPage(val entries: List<ConnectorEntry>, val nextContinuationToken: String?)

    /** A `ListBucketResult`: `CommonPrefixes` are folders and `Contents` files; the prefix's own placeholder is skipped. */
    fun parseListing(xml: ByteArray, account: String, bucket: String, prefix: String): ListingPage? {
        val document = parseXml(xml) ?: return null
        if (document.name != "listbucketresult") return null
        val entries = mutableListOf<ConnectorEntry>()
        for (common in document.children("commonprefixes")) {
            val key = common.value("prefix") ?: continue
            if (key == prefix) continue
            val name = key.removePrefix(prefix).removeSuffix("/")
            if (name.isEmpty()) continue
            entries += ConnectorEntry(name, SourceUrl.s3(account, bucket, key), isDirectory = true)
        }
        for (item in document.children("contents")) {
            val key = item.value("key") ?: continue
            if (key == prefix || key.endsWith("/")) continue
            val name = key.removePrefix(prefix)
            if (name.isEmpty() || '/' in name) continue
            entries += ConnectorEntry(
                name = name,
                url = SourceUrl.s3(account, bucket, key),
                isDirectory = false,
                size = item.value("size")?.toLongOrNull(),
                modifiedEpochMillis = item.value("lastmodified")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
            )
        }
        val truncated = document.value("istruncated") == "true"
        return ListingPage(entries, if (truncated) document.value("nextcontinuationtoken") else null)
    }

    /** What a failed S3 response means for the viewer. */
    fun failure(status: Int, body: ByteArray, host: String): ConnectorFailure {
        val document = parseXml(body)
        val code = document?.value("code").orEmpty()
        return when {
            // The bucket lives in another region than the one entered.
            code == "PermanentRedirect" || code == "AuthorizationHeaderMalformed" || status == 301 ->
                ConnectorFailure.BucketInAnotherRegion(document?.value("region"))
            code == "NoSuchBucket" || status == 404 -> ConnectorFailure.ListingFailed(host)
            status == 403 || code == "InvalidAccessKeyId" || code == "SignatureDoesNotMatch" ->
                ConnectorFailure.AuthenticationFailed(host)
            else -> ConnectorFailure.ServerError(MediaSourceKind.S3, status)
        }
    }

    /** A minimal element tree by local name (lowercased), enough for S3's flat responses. */
    internal class Node(val name: String) {
        val nodes = mutableListOf<Node>()
        var text = ""

        fun children(name: String) = nodes.filter { it.name == name }
        fun value(name: String): String? = nodes.firstOrNull { it.name == name }?.text
    }

    internal fun parseXml(xml: ByteArray): Node? = runCatching {
        val root = Node("")
        val stack = ArrayDeque<Node>().apply { addLast(root) }
        val text = StringBuilder()
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                val name = (localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfter(':') ?: "").lowercase()
                val node = Node(name)
                stack.last().nodes += node
                stack.addLast(node)
                text.setLength(0)
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                text.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                val node = stack.removeLast()
                if (node.nodes.isEmpty()) node.text = text.toString().trim()
                text.setLength(0)
            }
        }
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.newSAXParser().parse(InputSource(ByteArrayInputStream(xml)), handler)
        root.nodes.firstOrNull()
    }.getOrNull()
}

/**
 * An S3-compatible bucket (H.5.2): `ListObjectsV2` with `delimiter=/`, so
 * prefixes read as folders. Requests are signed with SigV4.
 */
class S3Connector(
    val configuration: S3Configuration,
    /** The access key ID and secret access key. */
    private val login: ServerLogin,
    private val http: RemoteHttp,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : MediaConnector {
    override val kind = MediaSourceKind.S3
    val accountKey: String = SourceUrl.s3AccountKey(configuration.endpoint, configuration.bucket, login.user)
    override val root: String = SourceUrl.s3(accountKey, configuration.bucket, "")
    override val accountLabel: String = configuration.bucket
    private val signer = S3Signer(login.user, login.password, configuration.region)
    private val host = configuration.endpoint.substringAfter("://").substringBefore('/').substringBefore(':')

    override suspend fun list(directory: String): List<ConnectorEntry> = withContext(Dispatchers.IO) {
        val item = SourceUrl.parseS3(directory)?.takeIf { it.isPrefix } ?: throw ConnectorException(ConnectorFailure.InvalidAddress)
        val entries = mutableListOf<ConnectorEntry>()
        var continuation: String? = null
        do {
            val query = buildList {
                add("list-type" to "2")
                add("delimiter" to "/")
                add("max-keys" to "1000")
                if (item.key.isNotEmpty()) add("prefix" to item.key)
                continuation?.let { add("continuation-token" to it) }
            }
            val request = signer.sign(RemoteRequest(S3.bucketUrl(configuration, "", query)), nowMillis())
            val response = try {
                http.newCall(request, bodyLimit = 32 shl 20).execute()
            } catch (error: IOException) {
                throw ConnectorException(ConnectorFailure.transport(error, host), error)
            }
            if (response.status != 200) throw ConnectorException(S3.failure(response.status, response.body, host))
            val page = S3.parseListing(response.body, accountKey, configuration.bucket, item.key)
                ?: throw ConnectorException(ConnectorFailure.ListingFailed(item.key.ifEmpty { configuration.bucket }))
            entries += page.entries
            continuation = page.nextContinuationToken
        } while (continuation != null)
        entries.filterNot { it.isHidden }.sortedWith(WebDavConnector.FOLDERS_FIRST)
    }
}

/** An S3 object's bytes through a pre-signed GET, signed again when the byte source refreshes (H.5.2). */
class S3ContentResolver(
    private val configuration: S3Configuration,
    private val login: ServerLogin,
    private val key: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : RemoteContentResolver {
    override val kind = MediaSourceKind.S3
    override val usesPreauthorizedLinks = true

    override fun contentRequest(refresh: Boolean): RemoteRequest {
        val signer = S3Signer(login.user, login.password, configuration.region)
        val url = signer.presign(S3.bucketUrl(configuration, key), nowMillis()) ?: throw IOException("Not an S3 address")
        return RemoteRequest(url)
    }
}
