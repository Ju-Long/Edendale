//
//  HTTPStub.swift
//  EdendaleTests
//
//  A URLProtocol that answers requests from handlers registered per host,
//  for sessions built with `HTTPStub.session()`. Each test registers its own
//  unique host, so suites running in parallel never see each other's
//  traffic, and no test touches the network or a real account.
//

import Foundation

struct RecordedRequest: Sendable {
    let url: URL
    let method: String
    let headers: [String: String]
    let body: Data?

    func header(_ name: String) -> String? {
        headers.first { $0.key.lowercased() == name.lowercased() }?.value
    }

    /// Form fields of an `application/x-www-form-urlencoded` body.
    var formFields: [String: String] {
        guard let body, let text = String(data: body, encoding: .utf8) else { return [:] }
        var fields: [String: String] = [:]
        for pair in text.split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1).map(String.init)
            let name = parts[0].removingPercentEncoding ?? parts[0]
            let value = parts.count > 1 ? (parts[1].removingPercentEncoding ?? parts[1]) : ""
            fields[name] = value
        }
        return fields
    }

    var jsonBody: [String: Any]? {
        body.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
    }

    func queryValue(_ name: String) -> String? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == name }?.value
    }
}

final class HTTPStub: URLProtocol, @unchecked Sendable {
    typealias Handler = @Sendable (RecordedRequest) throws -> (Int, [String: String], Data)

    private static let lock = NSLock()
    nonisolated(unsafe) private static var handlers: [String: Handler] = [:]
    nonisolated(unsafe) private static var log: [String: [RecordedRequest]] = [:]

    /// A host no other test uses.
    static func uniqueHost(_ label: String = "stub") -> String {
        "\(label)-\(UUID().uuidString.prefix(8).lowercased()).test"
    }

    static func register(host: String, handler: @escaping Handler) {
        lock.withLock {
            handlers[host] = handler
            log[host] = []
        }
    }

    static func unregister(host: String) {
        lock.withLock {
            handlers[host] = nil
            log[host] = nil
        }
    }

    static func requests(to host: String) -> [RecordedRequest] {
        lock.withLock { log[host] ?? [] }
    }

    static func configuration() -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HTTPStub.self]
        return configuration
    }

    static func session() -> URLSession {
        URLSession(configuration: configuration())
    }

    // MARK: - URLProtocol

    override class func canInit(with request: URLRequest) -> Bool {
        guard let host = request.url?.host() else { return false }
        return lock.withLock { handlers[host] != nil }
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let url = request.url, let host = url.host() else {
            client?.urlProtocol(self, didFailWithError: URLError(.badURL))
            return
        }
        let recorded = RecordedRequest(
            url: url,
            method: request.httpMethod ?? "GET",
            headers: request.allHTTPHeaderFields ?? [:],
            body: request.httpBody ?? Self.read(request.httpBodyStream)
        )
        let handler = Self.lock.withLock { () -> Handler? in
            Self.log[host, default: []].append(recorded)
            return Self.handlers[host]
        }
        do {
            guard let handler else { throw URLError(.cannotConnectToHost) }
            let (status, headers, data) = try handler(recorded)
            let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}

    private static func read(_ stream: InputStream?) -> Data? {
        guard let stream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            guard count > 0 else { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

// MARK: - Canned responses

enum StubResponse {
    /// Serves `data` as a file with `Range` support: 206 with Content-Range
    /// for a range, 416 past the end, 200 without a Range header.
    static func file(_ data: Data, request: RecordedRequest) -> (Int, [String: String], Data) {
        let total = data.count
        guard let header = request.header("Range"), header.hasPrefix("bytes=") else {
            return (200, ["Content-Length": String(total)], data)
        }
        let bounds = header.dropFirst("bytes=".count).split(separator: "-", omittingEmptySubsequences: false)
        let start = Int(bounds.first ?? "") ?? 0
        guard start < total else {
            return (416, ["Content-Range": "bytes */\(total)"], Data())
        }
        let requestedEnd = bounds.count > 1 ? Int(bounds[1]) ?? (total - 1) : total - 1
        let end = min(requestedEnd, total - 1)
        return (
            206,
            ["Content-Range": "bytes \(start)-\(end)/\(total)", "Content-Length": String(end - start + 1)],
            data.subdata(in: start..<(end + 1))
        )
    }

    static func json(_ object: Any, status: Int = 200, headers: [String: String] = [:]) -> (Int, [String: String], Data) {
        let data = (try? JSONSerialization.data(withJSONObject: object)) ?? Data()
        return (status, headers.merging(["Content-Type": "application/json"]) { $1 }, data)
    }

    static func text(_ string: String, status: Int, headers: [String: String] = [:]) -> (Int, [String: String], Data) {
        (status, headers, Data(string.utf8))
    }
}
