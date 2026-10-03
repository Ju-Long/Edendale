//
//  SourceURL.swift
//  Edendale
//
//  Canonical, credential-free URLs for remote library items and folders.
//  These are what `Movie.filePath`, `Episode.filePath`, and
//  `VideoFolder.folderPath` persist, so their shapes must stay stable:
//
//    smb://host/share/path/Name.ext
//    nfs://host/export/path/Name.ext
//    sftp://host[:port]/path/Name.ext
//    davs://host[:port]/path/Name.ext        (dav:// for plain HTTP)
//    s3://<account>/<bucket>/<key path>/Name.ext
//    gdrive://<account>/<fileId>/Name.ext
//    onedrive://<account>/<driveId>/<itemId>/Name.ext
//    dropbox://<account>/<fileId>/Name.ext    (the percent-encoded `id:…`)
//
//  Every item URL ends with the real file name, so MediaParser and the
//  extension filter work unchanged; a stable provider ID sits before it, so
//  renames and Drive's duplicate names don't collide. For the account
//  providers the URL host is an account key (see `accountKey`), which is
//  also the key their Keychain credential is stored under, as the host is
//  for SMB.
//

import CryptoKit
import Foundation

nonisolated enum SourceURL {

    // MARK: - Account keys

    /// The first 32 hex digits of SHA-256(`kind:subject`), where the subject
    /// is Google's `sub`, the Microsoft user `id`, Dropbox's `account_id`, or
    /// for S3 the endpoint, bucket, and access key ID. Hostname-safe,
    /// identical on every device, and it doesn't expose an email address.
    static func accountKey(kind: MediaSourceKind, subject: String) -> String {
        let digest = SHA256.hash(data: Data("\(kind.rawValue):\(subject)".utf8))
        return digest.prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    /// The account key for an S3 source.
    static func s3AccountKey(endpoint: URL, bucket: String, accessKeyID: String) -> String {
        let normalizedEndpoint = endpoint.absoluteString.lowercased()
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        return accountKey(kind: .s3, subject: "\(normalizedEndpoint)|\(bucket)|\(accessKeyID)")
    }

    // MARK: - Building

    /// An account-provider URL: `<scheme>://<account>/<id>/…/<name>`.
    /// `ids` are the provider identifiers before the name (one for Drive and
    /// Dropbox, drive then item for OneDrive). `query` carries listing hints
    /// on folder URLs only (e.g. a Drive folder's shared drive).
    static func accountItem(
        kind: MediaSourceKind,
        account: String,
        ids: [String],
        name: String,
        query: [URLQueryItem] = []
    ) -> URL {
        precondition(kind.isCloudAccount || kind == .s3, "accountItem is for account-keyed kinds")
        var components = URLComponents()
        components.scheme = kind.scheme
        components.host = account
        components.percentEncodedPath = "/" + (ids + [name]).map(encodeSegment).joined(separator: "/")
        if !query.isEmpty {
            components.queryItems = query
        }
        // Every segment is percent-encoded and the host is hex.
        return components.url!
    }

    /// An S3 object or prefix URL: `s3://<account>/<bucket>/<key>`. A prefix
    /// (folder) key ends in `/`, as S3 itself spells it.
    static func s3(account: String, bucket: String, key: String) -> URL {
        var components = URLComponents()
        components.scheme = MediaSourceKind.s3.scheme
        components.host = account
        let keySegments = key.split(separator: "/", omittingEmptySubsequences: false).map(String.init)
        components.percentEncodedPath = "/" + ([bucket] + keySegments).map(encodeSegment).joined(separator: "/")
        return components.url!
    }

    /// A server URL (SMB, NFS, SFTP, WebDAV): the path's segments are
    /// percent-encoded individually.
    static func server(
        scheme: String,
        host: String,
        port: Int? = nil,
        pathSegments: [String],
        isDirectory: Bool = false
    ) -> URL? {
        var components = URLComponents()
        components.scheme = scheme
        components.host = host
        components.port = port
        var path = "/" + pathSegments.filter { !$0.isEmpty }.map(encodeSegment).joined(separator: "/")
        if isDirectory, !path.hasSuffix("/") {
            path += "/"
        }
        components.percentEncodedPath = path
        return components.url
    }

    // MARK: - Parsing

    /// The parts of an account-provider URL.
    struct AccountItem: Equatable, Sendable {
        let kind: MediaSourceKind
        let account: String
        /// Provider identifiers, before the name.
        let ids: [String]
        /// The file or folder name (decoded).
        let name: String
        let query: [URLQueryItem]

        func queryValue(_ name: String) -> String? {
            query.first { $0.name == name }?.value
        }
    }

    /// Parses `gdrive:`, `onedrive:`, and `dropbox:` URLs. Returns `nil` for
    /// other kinds or malformed URLs.
    static func parseAccountItem(_ url: URL) -> AccountItem? {
        guard let kind = MediaSourceKind(url: url), kind.isCloudAccount,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let account = components.host, !account.isEmpty
        else { return nil }

        let segments = components.percentEncodedPath
            .split(separator: "/", omittingEmptySubsequences: false)
            .dropFirst()
            .map { $0.removingPercentEncoding ?? String($0) }
        let idCount = kind == .oneDrive ? 2 : 1
        guard segments.count == idCount + 1,
              segments.allSatisfy({ !$0.isEmpty })
        else { return nil }

        return AccountItem(
            kind: kind,
            account: account,
            ids: Array(segments.prefix(idCount)),
            name: segments[idCount],
            query: components.queryItems ?? []
        )
    }

    /// The parts of an S3 URL.
    struct S3Item: Equatable, Sendable {
        let account: String
        let bucket: String
        /// The object key or prefix; prefixes end in `/`, the bucket root is "".
        let key: String

        var isPrefix: Bool { key.isEmpty || key.hasSuffix("/") }
    }

    static func parseS3(_ url: URL) -> S3Item? {
        guard MediaSourceKind(url: url) == .s3,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let account = components.host, !account.isEmpty
        else { return nil }
        let segments = components.percentEncodedPath
            .split(separator: "/", omittingEmptySubsequences: false)
            .dropFirst()
            .map { $0.removingPercentEncoding ?? String($0) }
        guard let bucket = segments.first, !bucket.isEmpty else { return nil }
        return S3Item(
            account: account,
            bucket: bucket,
            key: segments.dropFirst().joined(separator: "/")
        )
    }

    /// The decoded path segments of a server URL (SMB, NFS, SFTP, WebDAV).
    static func pathSegments(of url: URL) -> [String] {
        guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return []
        }
        return components.percentEncodedPath
            .split(separator: "/")
            .map { $0.removingPercentEncoding ?? String($0) }
    }

    /// The key a source's Keychain credential is stored under: the host for
    /// server kinds, the account key for account kinds (also the URL host).
    static func credentialHost(of url: URL) -> String? {
        url.host(percentEncoded: false)?.lowercased()
    }

    // MARK: - Encoding

    /// RFC 3986 unreserved characters plus the sub-delimiters that are safe
    /// inside one path segment, ASCII only (`CharacterSet.alphanumerics`
    /// would let non-ASCII letters through unescaped). `/`, `:`, `;`, `?`,
    /// `#`, `%`, and spaces are always escaped, so a name can never split a
    /// segment.
    private static let segmentAllowed = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,=@"
    )

    static func encodeSegment(_ segment: String) -> String {
        segment.addingPercentEncoding(withAllowedCharacters: segmentAllowed) ?? segment
    }
}
