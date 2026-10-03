//
//  RemoteByteSourceTests.swift
//  EdendaleTests
//
//  RemoteByteSource against a URLProtocol stub that serves bytes with Range
//  support: chunking, prefetch, cached backward seeks, a 401 answered by one
//  token refresh, a 410 by a new link, an ignored Range, backoff, and
//  cancellation. Small chunks keep the fixtures tiny.
//

import Foundation
import Testing
@testable import Edendale

/// Hands out requests to the stub host, counting refreshes. A refresh bumps
/// the token (or link) version the stub can check.
final class StubResolver: RemoteContentResolver, @unchecked Sendable {
    let kind: MediaSourceKind
    let usesPreauthorizedLinks: Bool
    private let host: String
    private let lock = NSLock()
    private var refreshes = 0
    private var version = 0

    init(host: String, kind: MediaSourceKind = .googleDrive, preauthorized: Bool = false) {
        self.host = host
        self.kind = kind
        self.usesPreauthorizedLinks = preauthorized
    }

    var refreshCount: Int { lock.withLock { refreshes } }

    func contentRequest(refresh: Bool) async throws -> URLRequest {
        let version = lock.withLock { () -> Int in
            if refresh {
                refreshes += 1
                self.version += 1
            }
            return self.version
        }
        var request = URLRequest(url: URL(string: "https://\(host)/file.mkv?link=\(version)")!)
        if !usesPreauthorizedLinks {
            request.setValue("Bearer token\(version)", forHTTPHeaderField: "Authorization")
        }
        return request
    }
}

/// FFmpeg reads on its own queue and blocks; tests do the same.
func blockingRead(
    _ source: any ByteSource,
    offset: Int64,
    length: Int,
    abort: @escaping @Sendable () -> Bool = { false }
) async -> (count: Int, data: Data) {
    nonisolated(unsafe) let source = source
    return await withCheckedContinuation { continuation in
        DispatchQueue.global().async {
            var buffer = [UInt8](repeating: 0, count: length)
            let count = buffer.withUnsafeMutableBufferPointer { pointer in
                source.read(atOffset: offset, into: pointer.baseAddress!, length: length, shouldAbort: abort)
            }
            continuation.resume(returning: (count, Data(buffer.prefix(max(count, 0)))))
        }
    }
}

func makeTestData(count: Int) -> Data {
    Data((0..<count).map { UInt8(truncatingIfNeeded: ($0 * 31) ^ ($0 >> 7)) })
}

@Suite struct RemoteByteSourceTests {

    private static let chunk: Int64 = 1024

    private func makeSource(
        resolver: StubResolver,
        cachedChunks: Int = 4
    ) -> RemoteByteSource {
        RemoteByteSource(
            resolver: resolver,
            configuration: RemoteByteSource.Configuration(
                chunkSize: Self.chunk,
                cachedChunks: cachedChunks,
                requestTimeout: 5,
                backoffDelays: [0.01, 0.01, 0.01],
                backoffJitter: 0
            ),
            sessionConfiguration: HTTPStub.configuration()
        )
    }

    private func ranges(_ host: String) -> [String] {
        HTTPStub.requests(to: host).compactMap { $0.header("Range") }
    }

    @Test func readsTheWholeFileInRangeChunks() async throws {
        let host = HTTPStub.uniqueHost()
        let data = makeTestData(count: 5000)
        HTTPStub.register(host: host) { StubResponse.file(data, request: $0) }
        defer { HTTPStub.unregister(host: host) }
        let source = makeSource(resolver: StubResolver(host: host))
        defer { source.cancel() }

        #expect(source.length == -1)
        var collected = Data()
        while collected.count < data.count {
            let (count, bytes) = await blockingRead(source, offset: Int64(collected.count), length: 700)
            #expect(count > 0)
            collected.append(bytes)
        }
        #expect(collected == data)
        // The first Content-Range told it the size.
        #expect(source.length == 5000)
        let (end, _) = await blockingRead(source, offset: 5000, length: 10)
        #expect(end == 0)
        // Every request asked for a whole, chunk-aligned range.
        for range in ranges(host) {
            let start = Int64(range.dropFirst("bytes=".count).split(separator: "-")[0])!
            #expect(start % Self.chunk == 0)
        }
    }

    @Test func prefetchesTheNextChunkDuringSequentialReads() async throws {
        let host = HTTPStub.uniqueHost()
        let data = makeTestData(count: 8 * 1024)
        HTTPStub.register(host: host) { StubResponse.file(data, request: $0) }
        defer { HTTPStub.unregister(host: host) }
        let source = makeSource(resolver: StubResolver(host: host))
        defer { source.cancel() }

        _ = await blockingRead(source, offset: 0, length: 512)
        _ = await blockingRead(source, offset: 512, length: 512)
        // The second read was sequential: chunk 1 loads before anyone asks.
        let deadline = Date().addingTimeInterval(3)
        while !ranges(host).contains("bytes=1024-2047"), Date() < deadline {
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(ranges(host).contains("bytes=1024-2047"))
    }

    @Test func servesBackwardSeeksFromTheCache() async throws {
        let host = HTTPStub.uniqueHost()
        let data = makeTestData(count: 4096)
        HTTPStub.register(host: host) { StubResponse.file(data, request: $0) }
        defer { HTTPStub.unregister(host: host) }
        let source = makeSource(resolver: StubResolver(host: host))
        defer { source.cancel() }

        _ = await blockingRead(source, offset: 3000, length: 100)
        _ = await blockingRead(source, offset: 10, length: 100)
        let before = HTTPStub.requests(to: host).count
        let (count, bytes) = await blockingRead(source, offset: 3050, length: 20)
        #expect(count == 20)
        #expect(bytes == data.subdata(in: 3050..<3070))
        #expect(HTTPStub.requests(to: host).count == before)
    }

    @Test func refreshesTheTokenOnceAfterA401() async throws {
        let host = HTTPStub.uniqueHost()
        let data = makeTestData(count: 2048)
        HTTPStub.register(host: host) { request in
            request.header("Authorization") == "Bearer token0"
                ? StubResponse.text("expired", status: 401)
                : StubResponse.file(data, request: request)
        }
        defer { HTTPStub.unregister(host: host) }
        let resolver = StubResolver(host: host)
        let source = makeSource(resolver: resolver)
        defer { source.cancel() }

        let (count, bytes) = await blockingRead(source, offset: 0, length: 100)
        #expect(count == 100)
        #expect(bytes == data.prefix(100))
        #expect(resolver.refreshCount == 1)
    }

    @Test func aRejectedRefreshedTokenAsksForSignIn() async throws {
        let host = HTTPStub.uniqueHost()
        HTTPStub.register(host: host) { _ in StubResponse.text("no", status: 401) }
        defer { HTTPStub.unregister(host: host) }
        let resolver = StubResolver(host: host)
        let source = makeSource(resolver: resolver)
        defer { source.cancel() }

        let (count, _) = await blockingRead(source, offset: 0, length: 100)
        #expect(count == -1)
        #expect(resolver.refreshCount == 1)
        #expect(source.failureReason == ConnectorError.signInRequired(provider: "Google Drive").localizedDescription)
    }

    @Test func resolvesANewLinkAfterA410() async throws {
        let host = HTTPStub.uniqueHost()
        let data = makeTestData(count: 3000)
        HTTPStub.register(host: host) { request in
            request.queryValue("link") == "0"
                ? StubResponse.text("gone", status: 410)
                : StubResponse.file(data, request: request)
        }
        defer { HTTPStub.unregister(host: host) }
        let resolver = StubResolver(host: host, kind: .dropbox, preauthorized: true)
        let source = makeSource(resolver: resolver)
        defer { source.cancel() }

        let (count, bytes) = await blockingRead(source, offset: 1500, length: 200)
        #expect(count == 200)
        #expect(bytes == data.subdata(in: 1500..<1700))
        #expect(resolver.refreshCount == 1)
        // A pre-authorized link never carries a token.
        #expect(HTTPStub.requests(to: host).allSatisfy { $0.header("Authorization") == nil })
    }

    @Test func acceptsAServerThatIgnoresRangeAtTheStart() async throws {
        let host = HTTPStub.uniqueHost()
        let data = makeTestData(count: 5000)
        HTTPStub.register(host: host) { _ in (200, ["Content-Length": "5000"], data) }
        defer { HTTPStub.unregister(host: host) }
        let source = makeSource(resolver: StubResolver(host: host))
        defer { source.cancel() }

        let (count, bytes) = await blockingRead(source, offset: 0, length: 300)
        #expect(count == 300)
        #expect(bytes == data.prefix(300))
        #expect(source.length == 5000)
    }

    @Test func anIgnoredRangeLaterInTheFileFailsAfterOneRetry() async throws {
        let host = HTTPStub.uniqueHost()
        let data = makeTestData(count: 5000)
        HTTPStub.register(host: host) { _ in (200, ["Content-Length": "5000"], data) }
        defer { HTTPStub.unregister(host: host) }
        let source = makeSource(resolver: StubResolver(host: host))
        defer { source.cancel() }

        let (count, _) = await blockingRead(source, offset: 3000, length: 100)
        #expect(count == -1)
        #expect(ranges(host).filter { $0 == "bytes=2048-3071" }.count == 2)
        #expect(source.failureReason == ConnectorError.rangeRequestsUnsupported(provider: "Google Drive").localizedDescription)
    }

    @Test func backsOffWhenRateLimited() async throws {
        let host = HTTPStub.uniqueHost()
        let data = makeTestData(count: 2000)
        let attempts = Counter()
        HTTPStub.register(host: host) { request in
            attempts.increment() <= 2
                ? StubResponse.text("slow down", status: 429, headers: ["Retry-After": "0"])
                : StubResponse.file(data, request: request)
        }
        defer { HTTPStub.unregister(host: host) }
        let source = makeSource(resolver: StubResolver(host: host))
        defer { source.cancel() }

        let (count, _) = await blockingRead(source, offset: 0, length: 64)
        #expect(count == 64)
        #expect(attempts.value == 3)
    }

    @Test func persistentServerErrorsFailAfterTheBackoffs() async throws {
        let host = HTTPStub.uniqueHost()
        HTTPStub.register(host: host) { _ in StubResponse.text("down", status: 503) }
        defer { HTTPStub.unregister(host: host) }
        let source = makeSource(resolver: StubResolver(host: host))
        defer { source.cancel() }

        let (count, _) = await blockingRead(source, offset: 0, length: 64)
        #expect(count == -1)
        #expect(HTTPStub.requests(to: host).count == 4)
        #expect(source.failureReason == ConnectorError.rateLimited(provider: "Google Drive").localizedDescription)
    }

    @Test func aMissingFileSaysItsGone() async throws {
        let host = HTTPStub.uniqueHost()
        HTTPStub.register(host: host) { _ in StubResponse.text("missing", status: 404) }
        defer { HTTPStub.unregister(host: host) }
        let source = makeSource(resolver: StubResolver(host: host, kind: .oneDrive, preauthorized: true))
        defer { source.cancel() }

        let (count, _) = await blockingRead(source, offset: 0, length: 64)
        #expect(count == -1)
        #expect(source.failureReason == "This file is no longer in OneDrive.")
    }

    @Test func driveRateLimitsInA403AreRetried() async throws {
        let host = HTTPStub.uniqueHost()
        let data = makeTestData(count: 2000)
        let attempts = Counter()
        let rateLimit = #"{"error":{"errors":[{"domain":"usageLimits","reason":"userRateLimitExceeded"}],"code":403}}"#
        HTTPStub.register(host: host) { request in
            attempts.increment() == 1
                ? StubResponse.text(rateLimit, status: 403)
                : StubResponse.file(data, request: request)
        }
        defer { HTTPStub.unregister(host: host) }
        let source = makeSource(resolver: StubResolver(host: host))
        defer { source.cancel() }

        let (count, _) = await blockingRead(source, offset: 0, length: 64)
        #expect(count == 64)
    }

    @Test func cancelFailsABlockedReadAtOnce() async throws {
        let host = HTTPStub.uniqueHost()
        let data = makeTestData(count: 2000)
        HTTPStub.register(host: host) { request in
            Thread.sleep(forTimeInterval: 2)
            return StubResponse.file(data, request: request)
        }
        defer { HTTPStub.unregister(host: host) }
        let source = makeSource(resolver: StubResolver(host: host))

        let started = Date()
        async let result = blockingRead(source, offset: 0, length: 64)
        try await Task.sleep(for: .milliseconds(150))
        source.cancel()
        let (count, _) = await result
        #expect(count == -1)
        #expect(Date().timeIntervalSince(started) < 1.5)
        let (after, _) = await blockingRead(source, offset: 0, length: 64)
        #expect(after == -1)
    }

    @Test func anInterruptFailsOnlyTheBlockedRead() async throws {
        let host = HTTPStub.uniqueHost()
        let data = makeTestData(count: 2000)
        HTTPStub.register(host: host) { request in
            Thread.sleep(forTimeInterval: 0.5)
            return StubResponse.file(data, request: request)
        }
        defer { HTTPStub.unregister(host: host) }
        let source = makeSource(resolver: StubResolver(host: host))
        defer { source.cancel() }

        let interrupted = Flag()
        let started = Date()
        async let result = blockingRead(source, offset: 0, length: 64) { interrupted.isSet }
        try await Task.sleep(for: .milliseconds(100))
        interrupted.set()
        let (count, _) = await result
        #expect(count == -1)
        #expect(Date().timeIntervalSince(started) < 0.45)
        // FFmpeg reads on after a seek clears the interrupt.
        let (next, bytes) = await blockingRead(source, offset: 0, length: 64)
        #expect(next == 64)
        #expect(bytes == data.prefix(64))
    }

    @Test func aRangePastTheEndIsTheEndOfTheFile() async throws {
        let host = HTTPStub.uniqueHost()
        let data = makeTestData(count: 2048)
        HTTPStub.register(host: host) { StubResponse.file(data, request: $0) }
        defer { HTTPStub.unregister(host: host) }
        // Exactly two chunks, size unknown until the server says.
        let source = makeSource(resolver: StubResolver(host: host))
        defer { source.cancel() }

        let (count, _) = await blockingRead(source, offset: 2048, length: 64)
        #expect(count == 0)
        #expect(source.length == 2048)
    }

    @Test func parsesContentRangeHeaders() {
        #expect(RemoteByteSource.parseContentRange("bytes 0-499/1234") == .init(start: 0, end: 499, total: 1234))
        #expect(RemoteByteSource.parseContentRange("bytes 500-999/*") == .init(start: 500, end: 999, total: nil))
        #expect(RemoteByteSource.parseContentRange("bytes */1234") == .init(start: 0, end: nil, total: 1234))
        #expect(RemoteByteSource.parseContentRange("items 0-1/2") == nil)
        #expect(RemoteByteSource.parseContentRange(nil) == nil)
    }

    @Test func classifiesProviderResponses() {
        func action(_ status: Int, _ body: String = "", preauthorized: Bool = false, kind: MediaSourceKind = .googleDrive) -> ProviderResponse.Action {
            ProviderResponse.action(status: status, body: Data(body.utf8), headers: [:], kind: kind, preauthorizedLink: preauthorized)
        }
        #expect(action(401) == .refresh)
        #expect(action(410) == .refresh)
        #expect(action(403, #"{"error":{"errors":[{"reason":"rateLimitExceeded"}]}}"#) == .backoff(nil))
        #expect(action(403, #"{"error":{"errors":[{"reason":"cannotDownloadAbusiveFile"}]}}"#) == .fail(.abusiveFile))
        #expect(action(403, "<Error><Code>AccessDenied</Code><Message>Request has expired</Message></Error>", preauthorized: true, kind: .s3) == .refresh)
        #expect(action(403, "forbidden") == .fail(.accessDenied(provider: "Google Drive")))
        #expect(action(404) == .fail(.notFound(provider: "Google Drive")))
        #expect(action(409, #"{"error_summary":"path/not_found/"}"#, kind: .dropbox) == .fail(.notFound(provider: "Dropbox")))
        #expect(action(429) == .backoff(nil))
        #expect(action(503) == .backoff(nil))
        #expect(ProviderResponse.action(status: 429, body: Data(), headers: ["Retry-After": "3"], kind: .oneDrive, preauthorizedLink: false) == .backoff(3))
        #expect(action(418) == .fail(.serverError(provider: "Google Drive", status: 418)))
    }
}

final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    /// Increments and returns the new value.
    func increment() -> Int {
        lock.withLock {
            count += 1
            return count
        }
    }

    var value: Int { lock.withLock { count } }
}

final class Flag: @unchecked Sendable {
    private let lock = NSLock()
    private var raised = false

    func set() { lock.withLock { raised = true } }
    var isSet: Bool { lock.withLock { raised } }
}
