//
//  RemoteByteSource.swift
//  Edendale
//
//  Random-access bytes of a remote file for FFmpeg custom I/O (EDByteSource),
//  over one ephemeral URLSession: no disk cache or cookies, with the system's
//  certificate validation, HTTP/2, and proxy settings.
//
//  The file is read in 4 MiB `Range` chunks. While reads are sequential the
//  next chunk is prefetched, and up to 8 chunks (32 MiB) stay cached, so the
//  MKV cues or MP4 `moov` at the end of a file stay cached through the open.
//  FFmpeg reads on its worker queue and blocks here until a chunk arrives;
//  the wait polls FFmpeg's interrupt flag, and cancel() fails it at once.
//
//  Responses:
//    206                        serve the range
//    200 at offset 0            accept (the server ignored Range; Microsoft
//                               Graph documents this)
//    200 at any other offset    retry once, then fail the read
//    401                        refresh the token (or link) once, then retry
//    403 rate limit, 429, 5xx   back off 0.5 s, 1 s, 2 s with jitter, then fail
//    403 expired link, 410      resolve a new link once, then retry
//    404                        fail: "This file is no longer in <provider>"
//
//  Pre-authorized URLs, tokens, and request headers are never logged.
//

import Foundation

nonisolated final class RemoteByteSource: NSObject, ByteSource, @unchecked Sendable {

    struct Configuration: Sendable {
        var chunkSize: Int64 = 4 << 20
        var cachedChunks = 8
        var requestTimeout: TimeInterval = 30
        /// Delays before each retry of a rate-limited or failed request;
        /// its count is the number of retries.
        var backoffDelays: [TimeInterval] = [0.5, 1, 2]
        /// Jitter applied to each backoff delay, as a fraction.
        var backoffJitter: Double = 0.2
    }

    private let resolver: any RemoteContentResolver
    private let configuration: Configuration
    private let session: URLSession

    // Everything below is guarded by `condition`.
    private let condition = NSCondition()
    private var knownLength: Int64
    private var chunks: [Int64: Data] = [:]
    /// Chunk indices, least recently used first.
    private var recency: [Int64] = []
    private var loads: [Int64: Task<Void, Never>] = [:]
    /// Failed loads a reader was waiting for. A failed prefetch nobody
    /// waited on is dropped, so the read that reaches it tries again.
    private var failures: [Int64: Error] = [:]
    private var waiters: [Int64: Int] = [:]
    private var lastReadEnd: Int64 = -1
    private var isCancelled = false
    private var reason: String?

    /// - Parameters:
    ///   - length: The file size when the listing reported it; otherwise the
    ///     first response's `Content-Range` provides it.
    ///   - sessionConfiguration: Tests pass one with a stub `URLProtocol`.
    init(
        resolver: any RemoteContentResolver,
        length: Int64? = nil,
        configuration: Configuration = Configuration(),
        sessionConfiguration: URLSessionConfiguration? = nil
    ) {
        self.resolver = resolver
        self.configuration = configuration
        self.knownLength = length ?? -1
        let sessionConfiguration = sessionConfiguration ?? .ephemeral
        sessionConfiguration.urlCache = nil
        sessionConfiguration.httpCookieStorage = nil
        sessionConfiguration.requestCachePolicy = .reloadIgnoringLocalCacheData
        sessionConfiguration.timeoutIntervalForRequest = configuration.requestTimeout
        self.session = URLSession(configuration: sessionConfiguration)
        super.init()
    }

    deinit {
        session.invalidateAndCancel()
    }

    // MARK: - EDByteSource

    var length: Int64 {
        condition.lock()
        defer { condition.unlock() }
        return knownLength
    }

    var failureReason: String? {
        condition.lock()
        defer { condition.unlock() }
        return reason
    }

    func read(
        atOffset offset: Int64,
        into buffer: UnsafeMutablePointer<UInt8>,
        length requested: Int,
        shouldAbort: () -> Bool
    ) -> Int {
        guard requested > 0, offset >= 0 else { return 0 }
        condition.lock()
        defer { condition.unlock() }

        let index = offset / configuration.chunkSize
        while true {
            if isCancelled { return -1 }
            if knownLength >= 0, offset >= knownLength { return 0 }

            if let data = chunks[index] {
                touch(index)
                let start = Int(offset - index * configuration.chunkSize)
                // A chunk shorter than the offset ends the file.
                guard start < data.count else { return 0 }
                let count = min(requested, data.count - start)
                data.withUnsafeBytes { raw in
                    buffer.update(from: raw.baseAddress!.assumingMemoryBound(to: UInt8.self) + start, count: count)
                }
                let sequential = offset == lastReadEnd
                lastReadEnd = offset + Int64(count)
                if sequential || start + count == data.count {
                    prefetch(index + 1)
                }
                return count
            }

            if let error = failures.removeValue(forKey: index) {
                reason = Self.describe(error, provider: resolver.kind.displayName)
                return -1
            }
            if loads[index] == nil {
                startLoading(index)
            }
            // Wake at least every 50 ms to notice an interrupt.
            waiters[index, default: 0] += 1
            condition.wait(until: Date(timeIntervalSinceNow: 0.05))
            waiters[index, default: 1] -= 1
            if waiters[index] == 0 { waiters[index] = nil }
            if shouldAbort() { return -1 }
        }
    }

    func cancel() {
        condition.lock()
        isCancelled = true
        let running = Array(loads.values)
        loads.removeAll()
        chunks.removeAll()
        recency.removeAll()
        condition.broadcast()
        condition.unlock()
        running.forEach { $0.cancel() }
    }

    // MARK: - Chunk loading (called with `condition` locked)

    private func prefetch(_ index: Int64) {
        guard chunks[index] == nil, loads[index] == nil, failures[index] == nil else { return }
        if knownLength >= 0, index * configuration.chunkSize >= knownLength { return }
        // One chunk ahead is enough to hide a request's latency; more would
        // spend bandwidth on data a seek may throw away.
        guard loads.count < 2 else { return }
        startLoading(index)
    }

    private func startLoading(_ index: Int64) {
        loads[index] = Task.detached(priority: .userInitiated) { [self] in
            await load(index)
        }
    }

    private func touch(_ index: Int64) {
        recency.removeAll { $0 == index }
        recency.append(index)
    }

    private func load(_ index: Int64) async {
        let start = index * configuration.chunkSize
        let result: Result<Data, Error>
        do {
            result = .success(try await fillChunk(from: start))
        } catch {
            result = .failure(error)
        }
        finishLoading(index, start: start, result: result)
    }

    /// Stores a finished load and wakes the readers waiting for it.
    private func finishLoading(_ index: Int64, start: Int64, result: Result<Data, Error>) {
        condition.lock()
        defer {
            condition.broadcast()
            condition.unlock()
        }
        loads[index] = nil
        guard !isCancelled else { return }
        switch result {
        case .success(let data):
            chunks[index] = data
            touch(index)
            if data.count < Int(configuration.chunkSize), knownLength < 0 {
                // A short chunk without a stated total is the end of the file.
                knownLength = start + Int64(data.count)
            }
            while recency.count > configuration.cachedChunks, let oldest = recency.first, oldest != index {
                recency.removeFirst()
                chunks[oldest] = nil
            }
        case .failure(let error):
            if error is CancellationError { return }
            if waiters[index] != nil {
                failures[index] = error
            }
        }
    }

    /// Fetches one chunk, continuing where a server returned less of a range
    /// than was asked for.
    private func fillChunk(from start: Int64) async throws -> Data {
        var data = Data()
        var position = start
        let chunkEnd = start + configuration.chunkSize
        while position < chunkEnd {
            var end = chunkEnd
            let known = length
            if known >= 0 { end = min(end, known) }
            guard position < end else { break }

            let (piece, total) = try await fetch(position..<end)
            if let total { setLength(total) }
            data.append(piece)
            position += Int64(piece.count)
            // An empty or short piece without a larger stated total ends
            // the file.
            if piece.isEmpty { break }
            if position < end, total.map({ position >= $0 }) ?? true { break }
        }
        return data
    }

    private func setLength(_ total: Int64) {
        condition.lock()
        if knownLength < 0 { knownLength = total }
        condition.unlock()
    }

    // MARK: - HTTP

    /// One range, following the response table at the top of this file.
    private func fetch(_ range: Range<Int64>) async throws -> (Data, total: Int64?) {
        let provider = resolver.kind.displayName
        var refreshNext = false
        var refreshed = false
        var retried200 = false
        var backoffs = 0

        while true {
            try Task.checkCancellation()
            var request = try await resolver.contentRequest(refresh: refreshNext)
            refreshNext = false
            request.setValue("bytes=\(range.lowerBound)-\(range.upperBound - 1)", forHTTPHeaderField: "Range")
            // Byte offsets must refer to the file itself, never to a
            // compressed rendition of it.
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            request.timeoutInterval = configuration.requestTimeout
            request.cachePolicy = .reloadIgnoringLocalCacheData

            let response: HTTPURLResponse
            let body: Data
            do {
                (response, body) = try await transfer(request, limit: Int(range.count))
            } catch let error as URLError where ProviderResponse.isTransient(error) {
                guard backoffs < configuration.backoffDelays.count else { throw error }
                try await backoff(attempt: backoffs, retryAfter: nil)
                backoffs += 1
                continue
            }

            switch response.statusCode {
            case 206:
                let contentRange = Self.parseContentRange(response.value(forHTTPHeaderField: "Content-Range"))
                if let contentRange, contentRange.start != range.lowerBound {
                    throw ConnectorError.rangeRequestsUnsupported(provider: provider)
                }
                return (body, contentRange?.total)
            case 200:
                if range.lowerBound == 0 {
                    let total = response.expectedContentLength
                    return (body, total > 0 ? total : nil)
                }
                guard !retried200 else { throw ConnectorError.rangeRequestsUnsupported(provider: provider) }
                retried200 = true
                continue
            case 416:
                // The range starts at or past the end of the file.
                let total = Self.parseContentRange(response.value(forHTTPHeaderField: "Content-Range"))?.total
                return (Data(), total ?? range.lowerBound)
            default:
                switch ProviderResponse.action(
                    status: response.statusCode,
                    body: body,
                    headers: response.allHeaderFields,
                    kind: resolver.kind,
                    preauthorizedLink: resolver.usesPreauthorizedLinks
                ) {
                case .refresh:
                    guard !refreshed else {
                        throw resolver.usesPreauthorizedLinks
                            ? ConnectorError.accessDenied(provider: provider)
                            : ConnectorError.signInRequired(provider: provider)
                    }
                    refreshed = true
                    refreshNext = true
                case .backoff(let retryAfter):
                    guard backoffs < configuration.backoffDelays.count else {
                        throw ConnectorError.rateLimited(provider: provider)
                    }
                    try await backoff(attempt: backoffs, retryAfter: retryAfter)
                    backoffs += 1
                case .fail(let error):
                    throw error
                }
            }
        }
    }

    private func backoff(attempt: Int, retryAfter: TimeInterval?) async throws {
        let base = retryAfter ?? configuration.backoffDelays[attempt]
        let jitter = configuration.backoffJitter
        let delay = base * Double.random(in: (1 - jitter)...(1 + jitter))
        try await Task.sleep(for: .milliseconds(Int(delay * 1000)))
    }

    /// Runs one request, keeping at most `limit` bytes of a success body (a
    /// server that ignores `Range` would otherwise send the whole file) and
    /// 64 KiB of an error body.
    private func transfer(_ request: URLRequest, limit: Int) async throws -> (HTTPURLResponse, Data) {
        let transfer = ChunkTransfer(limit: limit, resolver: resolver)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                transfer.start(request, in: session, continuation: continuation)
            }
        } onCancel: {
            transfer.cancel()
        }
    }

    // MARK: - Helpers

    struct ContentRange: Equatable {
        let start: Int64
        let end: Int64?
        let total: Int64?
    }

    /// Parses `bytes 0-499/1234`, `bytes 0-499/*`, and `bytes */1234`.
    static func parseContentRange(_ header: String?) -> ContentRange? {
        guard let header = header?.trimmingCharacters(in: .whitespaces),
              header.lowercased().hasPrefix("bytes ")
        else { return nil }
        let spec = header.dropFirst("bytes ".count)
        let parts = spec.split(separator: "/", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return nil }
        let total = Int64(parts[1])
        if parts[0] == "*" { return ContentRange(start: 0, end: nil, total: total) }
        let bounds = parts[0].split(separator: "-", maxSplits: 1)
        guard bounds.count == 2, let start = Int64(bounds[0]), let end = Int64(bounds[1]) else { return nil }
        return ContentRange(start: start, end: end, total: total)
    }

    /// A message for the player. Transport errors say which provider was
    /// unreachable without repeating the URL.
    static func describe(_ error: Error, provider: String) -> String {
        if let error = error as? LocalizedError, let description = error.errorDescription {
            return description
        }
        if let error = error as? URLError {
            switch error.code {
            case .notConnectedToInternet:
                return String(localized: "You're offline. Connect to the internet to stream from \(provider).")
            case .appTransportSecurityRequiresSecureConnection:
                return ConnectorError.insecureConnection.localizedDescription
            case .serverCertificateUntrusted, .serverCertificateHasBadDate,
                 .serverCertificateNotYetValid, .serverCertificateHasUnknownRoot:
                return String(localized: "\(provider)'s certificate isn't trusted, so Edendale won't stream from it.")
            default:
                return String(localized: "Couldn't reach \(provider). Check your connection and try again.")
            }
        }
        return String(localized: "Couldn't read this file from \(provider).")
    }
}

// MARK: - One request

/// The delegate of a single data task: collects the body up to a limit,
/// answers authentication challenges from the resolver's login, and
/// resumes its continuation exactly once.
private nonisolated final class ChunkTransfer: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let limit: Int
    private let resolver: any RemoteContentResolver
    private let lock = NSLock()
    private var continuation: CheckedContinuation<(HTTPURLResponse, Data), Error>?
    private var task: URLSessionDataTask?
    private var response: HTTPURLResponse?
    private var body = Data()
    private var bodyLimit: Int
    private var cancelled = false

    init(limit: Int, resolver: any RemoteContentResolver) {
        self.limit = limit
        self.bodyLimit = limit
        self.resolver = resolver
    }

    func start(
        _ request: URLRequest,
        in session: URLSession,
        continuation: CheckedContinuation<(HTTPURLResponse, Data), Error>
    ) {
        lock.lock()
        if cancelled {
            lock.unlock()
            continuation.resume(throwing: CancellationError())
            return
        }
        self.continuation = continuation
        let task = session.dataTask(with: request)
        task.delegate = self
        self.task = task
        lock.unlock()
        task.resume()
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let task = self.task
        lock.unlock()
        task?.cancel()
        finish(.failure(CancellationError()))
    }

    private func finish(_ result: Result<(HTTPURLResponse, Data), Error>) {
        lock.lock()
        let continuation = self.continuation
        self.continuation = nil
        lock.unlock()
        continuation?.resume(with: result)
    }

    // MARK: URLSessionDataDelegate

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        lock.lock()
        let http = response as? HTTPURLResponse
        self.response = http
        if let http, !(200..<300).contains(http.statusCode) {
            // Enough of an error body to classify it.
            bodyLimit = 65_536
        }
        lock.unlock()
        completionHandler(http == nil ? .cancel : .allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let room = bodyLimit - body.count
        if room > 0 {
            body.append(data.prefix(room))
        }
        let full = body.count >= bodyLimit
        let response = self.response
        let collected = body
        lock.unlock()

        if full, let response {
            // The server sent more than was asked for (an ignored Range):
            // keep what's needed and stop the transfer.
            finish(.success((response, collected)))
            dataTask.cancel()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        let response = self.response
        let collected = body
        lock.unlock()
        if let error {
            finish(.failure(error))
        } else if let response {
            finish(.success((response, collected)))
        } else {
            finish(.failure(URLError(.badServerResponse)))
        }
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let method = challenge.protectionSpace.authenticationMethod
        guard method == NSURLAuthenticationMethodHTTPBasic || method == NSURLAuthenticationMethodHTTPDigest,
              challenge.previousFailureCount == 0,
              let credential = resolver.credential(for: challenge.protectionSpace)
        else {
            completionHandler(.performDefaultHandling, nil)
            return
        }
        completionHandler(.useCredential, credential)
    }
}
