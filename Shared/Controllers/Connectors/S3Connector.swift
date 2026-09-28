//
//  S3Connector.swift
//  Edendale
//
//  S3-compatible buckets: AWS, Backblaze B2, Cloudflare R2, Wasabi, MinIO.
//  Requests are signed with AWS Signature Version 4 (CryptoKit HMAC-SHA256);
//  listing is `ListObjectsV2` with `delimiter=/`, so prefixes read as
//  folders; files stream through pre-signed GET URLs, re-signed after a 403
//  for expiry. Item URLs are `s3://<account>/<bucket>/<key>`, where the
//  account key hashes the endpoint, bucket, and access key ID; the endpoint,
//  region, and addressing style are stored with the key pair in the
//  Keychain (NetworkCredentialStore, kind `s3`).
//

import CryptoKit
import Foundation

nonisolated struct S3Connector: MediaConnector {
    let kind: MediaSourceKind = .s3
    let configuration: S3Configuration
    let credential: NetworkCredential
    var session: URLSession = WebDAVConnector.session
    var now: @Sendable () -> Date = { Date() }

    init(configuration: S3Configuration, credential: NetworkCredential) {
        self.configuration = configuration
        self.credential = credential
    }

    /// Rebuilds the connector for a stored source from its Keychain login.
    init?(sourceURL: URL, store: any SecretStore = KeychainStore.shared) {
        guard let item = SourceURL.parseS3(sourceURL),
              let credential = NetworkCredentialStore.credential(kind: .s3, host: item.account, store: store),
              let configuration = credential.s3
        else { return nil }
        self.configuration = configuration
        self.credential = credential
    }

    var accountKey: String {
        SourceURL.s3AccountKey(endpoint: configuration.endpoint, bucket: configuration.bucket, accessKeyID: credential.username)
    }

    var root: URL {
        SourceURL.s3(account: accountKey, bucket: configuration.bucket, key: "")
    }

    var accountLabel: String? { configuration.bucket }

    private var signer: S3Signer {
        S3Signer(accessKeyID: credential.username, secretAccessKey: credential.password, region: configuration.region)
    }

    // MARK: - Listing

    func list(directory: URL) async throws -> [ConnectorEntry] {
        guard let item = SourceURL.parseS3(directory), item.isPrefix else { throw ConnectorError.invalidAddress }
        var entries: [ConnectorEntry] = []
        var continuation: String?
        repeat {
            var query = [
                URLQueryItem(name: "list-type", value: "2"),
                URLQueryItem(name: "delimiter", value: "/"),
                URLQueryItem(name: "max-keys", value: "1000")
            ]
            if !item.key.isEmpty { query.append(URLQueryItem(name: "prefix", value: item.key)) }
            if let continuation { query.append(URLQueryItem(name: "continuation-token", value: continuation)) }

            let url = bucketURL(key: "", query: query)
            var request = URLRequest(url: url)
            signer.sign(&request, date: now())

            let data: Data
            let response: URLResponse
            do {
                (data, response) = try await session.data(for: request)
            } catch let error as URLError {
                throw WebDAVConnector.connectorError(for: error, host: configuration.endpoint.host() ?? "S3")
            }
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            guard status == 200 else { throw Self.error(status: status, body: data, host: configuration.endpoint.host() ?? "S3") }
            guard let page = Self.parseListing(data, account: accountKey, bucket: configuration.bucket, prefix: item.key) else {
                throw ConnectorError.listingFailed(path: item.key.isEmpty ? configuration.bucket : item.key)
            }
            entries += page.entries
            continuation = page.nextContinuationToken
        } while continuation != nil
        return ConnectorWalk.sorted(entries.filter { !$0.isHidden })
    }

    struct ListingPage {
        let entries: [ConnectorEntry]
        let nextContinuationToken: String?
    }

    /// Parses a `ListBucketResult`: `CommonPrefixes` are folders and
    /// `Contents` files; the prefix's own placeholder object is skipped.
    static func parseListing(_ data: Data, account: String, bucket: String, prefix: String) -> ListingPage? {
        guard let tree = XMLTree.parse(data), tree.name == "ListBucketResult" else { return nil }
        var entries: [ConnectorEntry] = []
        for common in tree.children("CommonPrefixes") {
            guard let key = common.value("Prefix"), key != prefix else { continue }
            let name = String(key.dropFirst(prefix.count).dropLast(key.hasSuffix("/") ? 1 : 0))
            guard !name.isEmpty else { continue }
            entries.append(ConnectorEntry(
                name: name,
                url: SourceURL.s3(account: account, bucket: bucket, key: key),
                isDirectory: true
            ))
        }
        for object in tree.children("Contents") {
            guard let key = object.value("Key"), key != prefix, !key.hasSuffix("/") else { continue }
            let name = String(key.dropFirst(prefix.count))
            guard !name.isEmpty, !name.contains("/") else { continue }
            entries.append(ConnectorEntry(
                name: name,
                url: SourceURL.s3(account: account, bucket: bucket, key: key),
                isDirectory: false,
                size: object.value("Size").flatMap { Int64($0) },
                modified: object.value("LastModified").flatMap(ProviderHTTP.ISO8601.date(from:))
            ))
        }
        let truncated = tree.value("IsTruncated") == "true"
        return ListingPage(
            entries: entries,
            nextContinuationToken: truncated ? tree.value("NextContinuationToken") : nil
        )
    }

    static func error(status: Int, body: Data, host: String) -> ConnectorError {
        let tree = XMLTree.parse(body)
        let code = tree?.value("Code") ?? ""
        switch (status, code) {
        case (_, "PermanentRedirect"), (_, "AuthorizationHeaderMalformed"), (301, _):
            // The bucket lives in another region than the one entered.
            return .bucketInAnotherRegion(region: tree?.value("Region"))
        case (_, "NoSuchBucket"):
            return .listingFailed(path: host)
        case (403, _), (_, "InvalidAccessKeyId"), (_, "SignatureDoesNotMatch"):
            return .authenticationFailed(host: host)
        case (404, _):
            return .notFound(provider: MediaSourceKind.s3.displayName)
        default:
            return .serverError(provider: MediaSourceKind.s3.displayName, status: status)
        }
    }

    // MARK: - Addressing

    /// The HTTPS (or local HTTP) URL of `key` in the bucket.
    func bucketURL(key: String, query: [URLQueryItem] = []) -> URL {
        Self.bucketURL(configuration: configuration, key: key, query: query)
    }

    static func bucketURL(configuration: S3Configuration, key: String, query: [URLQueryItem] = []) -> URL {
        var components = URLComponents(url: configuration.endpoint, resolvingAgainstBaseURL: false)!
        let encodedKey = key.split(separator: "/", omittingEmptySubsequences: false)
            .map { S3Signer.uriEncode(String($0)) }
            .joined(separator: "/")
        let basePath = components.percentEncodedPath.hasSuffix("/")
            ? String(components.percentEncodedPath.dropLast())
            : components.percentEncodedPath
        if configuration.usesPathStyle {
            components.percentEncodedPath = "\(basePath)/\(S3Signer.uriEncode(configuration.bucket))/\(encodedKey)"
        } else {
            components.host = "\(configuration.bucket).\(components.host ?? "")"
            components.percentEncodedPath = "\(basePath)/\(encodedKey)"
        }
        if !query.isEmpty {
            components.percentEncodedQuery = query
                .map { "\(S3Signer.uriEncode($0.name))=\(S3Signer.uriEncode($0.value ?? ""))" }
                .joined(separator: "&")
        }
        return components.url!
    }

    /// Path-style addressing unless the endpoint is AWS itself, and always
    /// for bucket names with dots (they break virtual-hosted TLS).
    static func defaultUsesPathStyle(endpoint: URL, bucket: String) -> Bool {
        if bucket.contains(".") { return true }
        return !(endpoint.host()?.hasSuffix("amazonaws.com") ?? false)
    }
}

// MARK: - Signature Version 4

nonisolated struct S3Signer: Sendable {
    let accessKeyID: String
    let secretAccessKey: String
    let region: String
    var service = "s3"

    static let emptyPayloadHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /// Signs a request with an `Authorization` header (listing).
    func sign(_ request: inout URLRequest, date: Date, payloadHash: String = S3Signer.emptyPayloadHash) {
        guard let url = request.url, let host = Self.hostHeader(for: url) else { return }
        let timestamp = Self.timestamp(date)
        let day = String(timestamp.prefix(8))
        // URLSession sends this Host itself; it is signed, not set.
        request.setValue(timestamp, forHTTPHeaderField: "x-amz-date")
        request.setValue(payloadHash, forHTTPHeaderField: "x-amz-content-sha256")

        let headers = [
            ("host", host),
            ("x-amz-content-sha256", payloadHash),
            ("x-amz-date", timestamp)
        ]
        let signedHeaders = headers.map(\.0).joined(separator: ";")
        let canonical = canonicalRequest(
            method: request.httpMethod ?? "GET",
            url: url,
            headers: headers,
            signedHeaders: signedHeaders,
            payloadHash: payloadHash
        )
        let scope = "\(day)/\(region)/\(service)/aws4_request"
        let signature = self.signature(stringToSign: stringToSign(timestamp: timestamp, scope: scope, canonicalRequest: canonical), day: day)
        request.setValue(
            "AWS4-HMAC-SHA256 Credential=\(accessKeyID)/\(scope), SignedHeaders=\(signedHeaders), Signature=\(signature)",
            forHTTPHeaderField: "Authorization"
        )
    }

    /// A pre-signed GET URL for `url`, valid for `expires` seconds.
    func presign(_ url: URL, date: Date, expires: Int = 3600) -> URL? {
        guard let host = Self.hostHeader(for: url),
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        else { return nil }
        let timestamp = Self.timestamp(date)
        let day = String(timestamp.prefix(8))
        let scope = "\(day)/\(region)/\(service)/aws4_request"
        var query = [
            ("X-Amz-Algorithm", "AWS4-HMAC-SHA256"),
            ("X-Amz-Credential", "\(accessKeyID)/\(scope)"),
            ("X-Amz-Date", timestamp),
            ("X-Amz-Expires", String(expires)),
            ("X-Amz-SignedHeaders", "host")
        ]
        components.percentEncodedQuery = Self.canonicalQuery(query)
        guard let unsigned = components.url else { return nil }
        let canonical = canonicalRequest(
            method: "GET",
            url: unsigned,
            headers: [("host", host)],
            signedHeaders: "host",
            payloadHash: "UNSIGNED-PAYLOAD"
        )
        let signature = self.signature(stringToSign: stringToSign(timestamp: timestamp, scope: scope, canonicalRequest: canonical), day: day)
        query.append(("X-Amz-Signature", signature))
        components.percentEncodedQuery = Self.canonicalQuery(query)
        return components.url
    }

    func canonicalRequest(
        method: String,
        url: URL,
        headers: [(String, String)],
        signedHeaders: String,
        payloadHash: String
    ) -> String {
        // The path is already encoded once, segment by segment; S3 signs it
        // as sent, without encoding it again.
        let path = url.path(percentEncoded: true)
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.percentEncodedQuery ?? ""
        let canonicalQuery = Self.canonicalQuery(query.split(separator: "&").map { pair in
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            let name = String(parts[0]).removingPercentEncoding ?? String(parts[0])
            let value = parts.count > 1 ? (String(parts[1]).removingPercentEncoding ?? String(parts[1])) : ""
            return (name, value)
        })
        let canonicalHeaders = headers.map { "\($0.0):\($0.1.trimmingCharacters(in: .whitespaces))\n" }.joined()
        return [
            method,
            path.isEmpty ? "/" : path,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ].joined(separator: "\n")
    }

    func stringToSign(timestamp: String, scope: String, canonicalRequest: String) -> String {
        [
            "AWS4-HMAC-SHA256",
            timestamp,
            scope,
            Self.hex(SHA256.hash(data: Data(canonicalRequest.utf8)))
        ].joined(separator: "\n")
    }

    func signature(stringToSign: String, day: String) -> String {
        var key = SymmetricKey(data: Data("AWS4\(secretAccessKey)".utf8))
        for part in [day, region, service, "aws4_request"] {
            key = SymmetricKey(data: Data(HMAC<SHA256>.authenticationCode(for: Data(part.utf8), using: key)))
        }
        return Self.hex(HMAC<SHA256>.authenticationCode(for: Data(stringToSign.utf8), using: key))
    }

    // MARK: Encoding

    /// Sorted by name, then value, with both URI-encoded.
    static func canonicalQuery(_ items: [(String, String)]) -> String {
        let encoded: [(name: String, value: String)] = items.map { (uriEncode($0.0), uriEncode($0.1)) }
        let sorted = encoded.sorted { lhs, rhs in
            lhs.name == rhs.name ? lhs.value < rhs.value : lhs.name < rhs.name
        }
        return sorted.map { "\($0.name)=\($0.value)" }.joined(separator: "&")
    }

    /// AWS URI encoding: everything but the unreserved characters.
    static func uriEncode(_ string: String) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return string.addingPercentEncoding(withAllowedCharacters: allowed) ?? string
    }

    static func hostHeader(for url: URL) -> String? {
        guard let host = url.host() else { return nil }
        if let port = url.port, !(url.scheme == "https" && port == 443), !(url.scheme == "http" && port == 80) {
            return "\(host):\(port)"
        }
        return host
    }

    static func timestamp(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        return formatter.string(from: date)
    }

    static func hex<D: Sequence>(_ digest: D) -> String where D.Element == UInt8 {
        digest.map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - Streaming

/// Streams an object through a pre-signed URL, re-signing on refresh.
nonisolated struct S3ContentResolver: RemoteContentResolver {
    let kind: MediaSourceKind = .s3
    let configuration: S3Configuration
    let credential: NetworkCredential
    let key: String
    var usesPreauthorizedLinks: Bool { true }

    func contentRequest(refresh: Bool) async throws -> URLRequest {
        let signer = S3Signer(accessKeyID: credential.username, secretAccessKey: credential.password, region: configuration.region)
        let url = S3Connector.bucketURL(configuration: configuration, key: key)
        guard let signed = signer.presign(url, date: Date()) else { throw ConnectorError.invalidAddress }
        return URLRequest(url: signed)
    }
}
