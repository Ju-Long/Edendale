//
//  WebDAVConnector.swift
//  Edendale
//
//  WebDAV servers: Nextcloud and ownCloud (`/remote.php/dav/files/<user>/`),
//  Synology, QNAP, pCloud, Koofr, or `rclone serve webdav` in front of
//  another service. URLs are `davs://host[:port]/path` for HTTPS and
//  `dav://` for plain HTTP, which App Transport Security allows only for
//  local addresses (NSAllowsLocalNetworking): `.local` names, unqualified
//  names, and IP addresses. Anything else must use valid HTTPS.
//
//  Listing is `PROPFIND` with `Depth: 1` (most servers disable `infinity`),
//  so enumeration walks breadth-first. Basic and Digest logins answer
//  URLSession's authentication challenges; the login lives in
//  NetworkCredentialStore, keyed by host.
//

import Foundation

nonisolated struct WebDAVConnector: MediaConnector {
    let kind: MediaSourceKind = .webdav
    /// The folder the user entered, canonical (`davs://` or `dav://`).
    let root: URL
    let credential: NetworkCredential?
    var session: URLSession

    /// A dedicated ephemeral session: no cookies, cache, or stored logins
    /// beyond the ones each request's challenge handler supplies. Shared by
    /// requests that carry their own authorization (S3's signatures); WebDAV
    /// listings use `loginSession(for:)`.
    static let session = makeSession()

    /// The session for requests made with `credential`. A session keeps the
    /// first login a server accepts and sends it with every later request to
    /// that server, so each login gets its own: with one shared session, a
    /// wrong or missing password still "connected" using an earlier login.
    static func loginSession(for credential: NetworkCredential?) -> URLSession {
        sessions.session(for: credential)
    }

    private static let sessions = LoginSessions()

    static func makeSession() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.httpCookieStorage = nil
        configuration.urlCredentialStorage = nil
        configuration.timeoutIntervalForRequest = 30
        return URLSession(configuration: configuration)
    }

    /// Builds a connector from what the user typed: a full `https://…` or
    /// `http://…` address, or a bare host with an optional path.
    init?(address: String, credential: NetworkCredential?) {
        guard let root = Self.canonicalRoot(from: address) else { return nil }
        self.root = root
        self.credential = credential
        self.session = Self.loginSession(for: credential)
    }

    /// Rebuilds the connector for a stored source, with its saved login.
    init?(sourceURL: URL, store: any SecretStore = KeychainStore.shared) {
        guard MediaSourceKind(url: sourceURL) == .webdav, let host = sourceURL.host() else { return nil }
        self.root = Self.directoryURL(sourceURL)
        self.credential = NetworkCredentialStore.credential(kind: .webdav, host: host, store: store)
        self.session = Self.loginSession(for: credential)
    }

    var accountLabel: String? {
        guard let credential, !credential.isGuest else { return nil }
        return credential.username
    }

    // MARK: - Listing

    static let propfindBody = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:displayname/></d:prop></d:propfind>
        """

    func list(directory: URL) async throws -> [ConnectorEntry] {
        let directory = Self.directoryURL(directory)
        guard let httpURL = Self.httpURL(for: directory) else { throw ConnectorError.invalidAddress }

        var request = URLRequest(url: httpURL)
        request.httpMethod = "PROPFIND"
        request.setValue("1", forHTTPHeaderField: "Depth")
        request.setValue("application/xml; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(Self.propfindBody.utf8)

        let host = directory.host() ?? directory.absoluteString
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(
                for: request,
                delegate: WebDAVChallengeHandler(credential: credential)
            )
        } catch let error as URLError {
            throw Self.connectorError(for: error, host: host)
        }

        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        switch status {
        case 207:
            break
        case 401, 403:
            throw ConnectorError.authenticationFailed(host: host)
        case 404:
            throw ConnectorError.listingFailed(path: directory.path())
        default:
            // 200 or 405 here means the address isn't a WebDAV folder.
            throw ConnectorError.listingFailed(path: directory.path())
        }
        guard let entries = Self.parseMultistatus(data, requestURL: httpURL, canonicalDirectory: directory) else {
            throw ConnectorError.listingFailed(path: directory.path())
        }
        return ConnectorWalk.sorted(entries.filter { !$0.isHidden })
    }

    /// Entries of a `multistatus` response, without the listed folder itself.
    static func parseMultistatus(_ data: Data, requestURL: URL, canonicalDirectory: URL) -> [ConnectorEntry]? {
        guard let tree = XMLTree.parse(data), tree.name == "multistatus" else { return nil }
        let listedPath = normalizedPath(requestURL.path(percentEncoded: false))

        return tree.children("response").compactMap { response -> ConnectorEntry? in
            guard let href = response.value("href"),
                  let resolved = resolve(href: href, against: requestURL)
            else { return nil }
            let path = resolved.path(percentEncoded: false)
            guard normalizedPath(path) != listedPath else { return nil }

            // Properties can be split across several propstat blocks; take
            // the ones the server answered with 200.
            let props = response.children("propstat")
                .filter { ($0.value("status") ?? "200").contains(" 200") || $0.value("status") == nil }
                .compactMap { $0.child("prop") }
            let isCollection = props.contains { $0.child("resourcetype")?.child("collection") != nil }
            let size = props.lazy.compactMap { $0.value("getcontentlength").flatMap { Int64($0) } }.first
            let modified = props.lazy.compactMap { $0.value("getlastmodified").flatMap(parseHTTPDate) }.first

            let segments = path.split(separator: "/").map(String.init)
            guard let name = segments.last, !name.isEmpty,
                  let url = SourceURL.server(
                    scheme: canonicalDirectory.scheme ?? "davs",
                    host: canonicalDirectory.host() ?? "",
                    port: canonicalDirectory.port,
                    pathSegments: segments,
                    isDirectory: isCollection
                  )
            else { return nil }
            return ConnectorEntry(
                name: name,
                url: url,
                isDirectory: isCollection,
                size: isCollection ? nil : size,
                modified: modified
            )
        }
    }

    /// An `href` is an absolute URL or an absolute path, percent-encoded by
    /// well-behaved servers; some send raw spaces.
    static func resolve(href: String, against base: URL) -> URL? {
        let trimmed = href.trimmingCharacters(in: .whitespacesAndNewlines)
        if let url = URL(string: trimmed, relativeTo: base) {
            return url.absoluteURL
        }
        let encoded = trimmed.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? trimmed
        return URL(string: encoded, relativeTo: base)?.absoluteURL
    }

    private static func normalizedPath(_ path: String) -> String {
        var path = path
        while path.hasSuffix("/") { path.removeLast() }
        return path.isEmpty ? "/" : path
    }

    /// RFC 1123 (`Tue, 15 Nov 1994 12:45:26 GMT`), the WebDAV date format.
    static func parseHTTPDate(_ string: String) -> Date? {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "GMT")
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
        return formatter.date(from: string.trimmingCharacters(in: .whitespaces))
    }

    // MARK: - Addresses

    /// `https://host/path` → `davs://host/path/`; `http://…` → `dav://…`. A bare
    /// host means HTTPS.
    static func canonicalRoot(from address: String) -> URL? {
        var text = address.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return nil }
        if !text.contains("://") { text = "https://" + text }
        guard var components = URLComponents(string: text),
              let host = components.host, !host.isEmpty
        else { return nil }
        switch components.scheme?.lowercased() {
        case "https", "davs", "webdavs": components.scheme = "davs"
        case "http", "dav", "webdav": components.scheme = "dav"
        default: return nil
        }
        components.user = nil
        components.password = nil
        components.query = nil
        components.fragment = nil
        if !components.percentEncodedPath.hasSuffix("/") {
            components.percentEncodedPath += "/"
        }
        return components.url
    }

    /// Collections are always addressed with a trailing slash: servers
    /// redirect the bare form, and a redirected PROPFIND can turn into a GET.
    static func directoryURL(_ url: URL) -> URL {
        guard !url.absoluteString.hasSuffix("/"),
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        else { return url }
        components.percentEncodedPath += "/"
        return components.url ?? url
    }

    /// The HTTP address behind a canonical `dav(s)://` URL.
    static func httpURL(for url: URL) -> URL? {
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return nil }
        switch components.scheme?.lowercased() {
        case "davs": components.scheme = "https"
        case "dav": components.scheme = "http"
        default: return nil
        }
        return components.url
    }

    static func connectorError(for error: URLError, host: String) -> Error {
        switch error.code {
        case .appTransportSecurityRequiresSecureConnection:
            ConnectorError.insecureConnection
        case .userAuthenticationRequired, .userCancelledAuthentication:
            ConnectorError.authenticationFailed(host: host)
        case .cancelled:
            CancellationError()
        default:
            ConnectorError.unreachable(host: host)
        }
    }
}

// MARK: - Authentication

/// Answers one request's Basic or Digest challenge with the saved login,
/// once. Without a login, or after it was refused, the 401 response itself
/// comes back and reads as "didn't accept the username and password".
nonisolated final class WebDAVChallengeHandler: NSObject, URLSessionTaskDelegate, Sendable {
    private let credential: NetworkCredential?

    init(credential: NetworkCredential?) {
        self.credential = credential
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didReceive challenge: URLAuthenticationChallenge
    ) async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        let method = challenge.protectionSpace.authenticationMethod
        guard method == NSURLAuthenticationMethodHTTPBasic || method == NSURLAuthenticationMethodHTTPDigest else {
            return (.performDefaultHandling, nil)
        }
        guard let credential, !credential.isGuest, challenge.previousFailureCount == 0 else {
            return (.performDefaultHandling, nil)
        }
        return (.useCredential, URLCredential(user: credential.username, password: credential.password, persistence: .forSession))
    }
}

/// The sessions behind `WebDAVConnector.loginSession(for:)`, one per login
/// (guests share one). There are only ever a few: one per linked server
/// login, plus any mistyped ones, which are dropped oldest first.
private nonisolated final class LoginSessions: @unchecked Sendable {
    private static let limit = 8

    private let lock = NSLock()
    private var sessions: [NetworkCredential?: URLSession] = [:]
    private var order: [NetworkCredential?] = []

    func session(for credential: NetworkCredential?) -> URLSession {
        let key = credential?.isGuest == false ? credential : nil
        let (session, dropped) = lock.withLock { () -> (URLSession, URLSession?) in
            if let session = sessions[key] {
                return (session, nil)
            }
            let session = WebDAVConnector.makeSession()
            sessions[key] = session
            order.append(key)
            guard order.count > Self.limit else { return (session, nil) }
            return (session, sessions.removeValue(forKey: order.removeFirst()))
        }
        // Requests already under way on a dropped session still finish.
        dropped?.finishTasksAndInvalidate()
        return session
    }
}

// MARK: - Streaming

/// Streams a WebDAV file with `Range` GETs, answering login challenges.
nonisolated struct WebDAVContentResolver: RemoteContentResolver {
    let kind: MediaSourceKind = .webdav
    let fileURL: URL
    let credential: NetworkCredential?

    func contentRequest(refresh: Bool) async throws -> URLRequest {
        guard let url = WebDAVConnector.httpURL(for: fileURL) else { throw ConnectorError.invalidAddress }
        return URLRequest(url: url)
    }

    func credential(for protectionSpace: URLProtectionSpace) -> URLCredential? {
        guard let credential, !credential.isGuest else { return nil }
        return URLCredential(user: credential.username, password: credential.password, persistence: .forSession)
    }
}
