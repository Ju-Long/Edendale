//
//  BufferedByteSourceTests.swift
//  EdendaleTests
//
//  BufferedByteSource (the SMB read path) against an in-memory server whose
//  connections can be dropped, refused, or slowed: read-ahead, a seek
//  jumping the read-ahead queue, reconnecting after a drop, giving up after
//  the retries, failing a bad first login at once, interrupts, cancel,
//  keep-alives, and eviction; and FFmpeg decoding a whole file through it
//  while the connection keeps dropping. Small chunks keep the fixtures tiny.
//

import Foundation
import Testing
@testable import Edendale

/// A file server whose state the tests change while the source reads.
final class FakeFileServer: @unchecked Sendable {
    let data: Data
    private let lock = NSLock()
    private var generation = 0
    private var refusing = false
    private var opens = 0
    private var reads: [Int64] = []
    private var keepAlives = 0
    private var readDelay: TimeInterval = 0
    private var failKeepAlive = false
    private var dropInterval = 0

    init(data: Data) { self.data = data }

    var openCount: Int { lock.withLock { opens } }
    var readOffsets: [Int64] { lock.withLock { reads } }
    var keepAliveCount: Int { lock.withLock { keepAlives } }
    var delay: TimeInterval {
        get { lock.withLock { readDelay } }
        set { lock.withLock { readDelay = newValue } }
    }

    /// Kills every open connection; `refuse` also turns new ones away.
    func drop(refuse: Bool = false) {
        lock.withLock {
            generation += 1
            refusing = refuse
        }
    }

    func restore() { lock.withLock { refusing = false } }
    /// Kills the connection on every `count`th read.
    func dropEvery(_ count: Int) { lock.withLock { dropInterval = count } }
    func failKeepAlives() { lock.withLock { failKeepAlive = true } }

    func open(_ error: NSErrorPointer) -> BufferedFile? {
        lock.lock()
        defer { lock.unlock() }
        opens += 1
        if refusing {
            error?.pointee = NSError(domain: "Test", code: 1, userInfo: [NSLocalizedDescriptionKey: "Host is down"])
            return nil
        }
        return FakeFile(server: self, generation: generation)
    }

    fileprivate func read(_ offset: Int64, _ buffer: UnsafeMutablePointer<UInt8>, _ length: Int, generation: Int) -> Int {
        let delay = lock.withLock { readDelay }
        if delay > 0 { Thread.sleep(forTimeInterval: delay) }
        return lock.withLock {
            guard generation == self.generation else { return -1 }
            reads.append(offset)
            if dropInterval > 0, reads.count % dropInterval == 0 {
                self.generation += 1
                return -1
            }
            guard offset < data.count else { return 0 }
            let count = min(length, data.count - Int(offset))
            data.withUnsafeBytes { raw in
                buffer.update(from: raw.baseAddress!.assumingMemoryBound(to: UInt8.self) + Int(offset), count: count)
            }
            return count
        }
    }

    fileprivate func keepAlive(generation: Int) -> Bool {
        lock.withLock {
            keepAlives += 1
            return !failKeepAlive && generation == self.generation
        }
    }
}

private final class FakeFile: NSObject, BufferedFile {
    let server: FakeFileServer
    let generation: Int

    init(server: FakeFileServer, generation: Int) {
        self.server = server
        self.generation = generation
    }

    var size: Int64 { Int64(server.data.count) }

    func read(atOffset offset: Int64, into buffer: UnsafeMutablePointer<UInt8>, length: Int, error: NSErrorPointer) -> Int {
        let count = server.read(offset, buffer, length, generation: generation)
        if count < 0 {
            error?.pointee = NSError(domain: "Test", code: 2, userInfo: [NSLocalizedDescriptionKey: "Connection reset"])
        }
        return count
    }

    func keepAlive() -> Bool {
        server.keepAlive(generation: generation)
    }
}

@Suite struct BufferedByteSourceTests {

    private static let chunk = 1024

    private func makeSource(
        _ server: FakeFileServer,
        aheadChunks: Int = 8,
        cacheChunks: Int = 16,
        retryDelays: [Double] = [0.01, 0.01, 0.01],
        keepAlive: TimeInterval = 0
    ) -> BufferedByteSource {
        let source = BufferedByteSource(host: "nas.local") { error in server.open(error) }
        source.chunkSize = Self.chunk
        source.readAheadBytes = aheadChunks * Self.chunk
        source.cacheBytes = cacheChunks * Self.chunk
        source.retryDelays = retryDelays.map { NSNumber(value: $0) }
        source.keepAliveInterval = keepAlive
        return source
    }

    private func readAll(_ source: BufferedByteSource, count: Int, step: Int = 700) async -> Data {
        var collected = Data()
        while collected.count < count {
            let (read, bytes) = await blockingRead(source, offset: Int64(collected.count), length: step)
            guard read > 0 else { break }
            collected.append(bytes)
        }
        return collected
    }

    private func waitUntil(_ condition: () -> Bool, timeout: TimeInterval = 3) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition(), Date() < deadline {
            try await Task.sleep(for: .milliseconds(10))
        }
    }

    @Test func readsTheWholeFileAndReadsAhead() async throws {
        let server = FakeFileServer(data: makeTestData(count: 32 * 1024))
        let source = makeSource(server)
        defer { source.cancel() }

        #expect(source.length == -1)
        let (count, bytes) = await blockingRead(source, offset: 0, length: 100)
        #expect(count == 100)
        #expect(bytes == server.data.prefix(100))
        #expect(source.length == Int64(server.data.count))
        // Chunks up to eight ahead of the read position load unasked, and
        // no further.
        try await waitUntil { server.readOffsets.contains(8 * 1024) }
        #expect(server.readOffsets.contains(8 * 1024))
        try await Task.sleep(for: .milliseconds(100))
        #expect(!server.readOffsets.contains(9 * 1024))

        #expect(await readAll(source, count: server.data.count) == server.data)
        let (end, _) = await blockingRead(source, offset: Int64(server.data.count), length: 10)
        #expect(end == 0)
        // Every fetch was a whole, chunk-aligned chunk, and none repeated.
        #expect(server.readOffsets.allSatisfy { $0 % Int64(Self.chunk) == 0 })
        #expect(Set(server.readOffsets).count == server.readOffsets.count)
        #expect(server.openCount == 1)
    }

    @Test func aSeekJumpsTheReadAheadQueue() async throws {
        let server = FakeFileServer(data: makeTestData(count: 256 * 1024))
        server.delay = 0.03
        let source = makeSource(server, aheadChunks: 64, cacheChunks: 80)
        defer { source.cancel() }

        _ = await blockingRead(source, offset: 0, length: 100)
        // Read-ahead now has 64 chunks (about 2 s) to fetch; the seek waits
        // for one or two of them, not all.
        let started = Date()
        let (count, bytes) = await blockingRead(source, offset: 200_000, length: 100)
        #expect(count == 100)
        #expect(bytes == server.data.subdata(in: 200_000..<200_100))
        #expect(Date().timeIntervalSince(started) < 0.5)
    }

    @Test func reconnectsAfterADroppedConnection() async throws {
        let server = FakeFileServer(data: makeTestData(count: 40 * 1024))
        let source = makeSource(server, aheadChunks: 2, cacheChunks: 4)
        defer { source.cancel() }

        var collected = Data()
        while collected.count < server.data.count {
            if collected.count == 10 * 1024 || collected.count == 25 * 1024 { server.drop() }
            let (read, bytes) = await blockingRead(source, offset: Int64(collected.count), length: 1024)
            #expect(read > 0)
            guard read > 0 else { break }
            collected.append(bytes)
        }
        #expect(collected == server.data)
        #expect(server.openCount == 3)
        #expect(source.failureReason == nil)
    }

    @Test func ridesOutAShortOutage() async throws {
        let server = FakeFileServer(data: makeTestData(count: 16 * 1024))
        let source = makeSource(server, aheadChunks: 1, cacheChunks: 3, retryDelays: [0.05, 0.1, 0.2, 0.4])
        defer { source.cancel() }

        _ = await blockingRead(source, offset: 0, length: 100)
        try await waitUntil { server.readOffsets.contains(1024) }
        server.drop(refuse: true)
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) { server.restore() }
        let (count, bytes) = await blockingRead(source, offset: 8 * 1024, length: 100)
        #expect(count == 100)
        #expect(bytes == server.data.subdata(in: 8192..<8292))
    }

    @Test func failsTheReadOnceEveryRetryHasAndRecoversLater() async throws {
        let server = FakeFileServer(data: makeTestData(count: 16 * 1024))
        let source = makeSource(server, aheadChunks: 1, cacheChunks: 3)
        defer { source.cancel() }

        _ = await blockingRead(source, offset: 0, length: 100)
        try await waitUntil { server.readOffsets.contains(1024) }
        server.drop(refuse: true)
        let opensBefore = server.openCount
        let (count, _) = await blockingRead(source, offset: 8 * 1024, length: 100)
        #expect(count == -1)
        // One open per retry.
        #expect(server.openCount - opensBefore == 3)
        #expect(source.failureReason?.contains("Lost the connection to nas.local") == true)
        #expect(source.failureReason?.contains("Host is down") == true)

        server.restore()
        let (later, bytes) = await blockingRead(source, offset: 8 * 1024, length: 100)
        #expect(later == 100)
        #expect(bytes == server.data.subdata(in: 8192..<8292))
    }

    @Test func aFailedFirstLoginFailsAtOnce() async throws {
        let server = FakeFileServer(data: makeTestData(count: 4096))
        server.drop(refuse: true)
        let source = makeSource(server, retryDelays: [1, 1, 1])
        defer { source.cancel() }

        let started = Date()
        let (count, _) = await blockingRead(source, offset: 0, length: 100)
        #expect(count == -1)
        #expect(Date().timeIntervalSince(started) < 0.5)
        #expect(server.openCount == 1)
        #expect(source.failureReason == "Host is down")
        // Every later read fails the same way, without reconnecting.
        let (again, _) = await blockingRead(source, offset: 0, length: 100)
        #expect(again == -1)
        #expect(server.openCount == 1)
    }

    @Test func anInterruptFailsOnlyTheBlockedRead() async throws {
        let server = FakeFileServer(data: makeTestData(count: 4096))
        server.delay = 0.5
        let source = makeSource(server, aheadChunks: 1, cacheChunks: 3)
        defer { source.cancel() }

        let interrupted = Flag()
        let started = Date()
        async let result = blockingRead(source, offset: 0, length: 64) { interrupted.isSet }
        try await Task.sleep(for: .milliseconds(100))
        interrupted.set()
        let (count, _) = await result
        #expect(count == -1)
        #expect(Date().timeIntervalSince(started) < 0.45)
        #expect(source.failureReason == nil)
        let (next, bytes) = await blockingRead(source, offset: 0, length: 64)
        #expect(next == 64)
        #expect(bytes == server.data.prefix(64))
    }

    @Test func cancelFailsABlockedReadAtOnce() async throws {
        let server = FakeFileServer(data: makeTestData(count: 4096))
        server.delay = 2
        let source = makeSource(server)

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

    @Test func keepsAnIdleConnectionAliveAndReopensADeadOne() async throws {
        let server = FakeFileServer(data: makeTestData(count: 8 * 1024))
        let source = makeSource(server, aheadChunks: 1, cacheChunks: 3, keepAlive: 0.1)
        defer { source.cancel() }

        _ = await blockingRead(source, offset: 0, length: 100)
        try await waitUntil { server.keepAliveCount >= 2 }
        #expect(server.keepAliveCount >= 2)
        #expect(server.openCount == 1)

        // A failed keep-alive drops the connection; the next fetch reopens it.
        server.failKeepAlives()
        let before = server.keepAliveCount
        try await waitUntil { server.keepAliveCount > before }
        let (count, bytes) = await blockingRead(source, offset: 6 * 1024, length: 100)
        #expect(count == 100)
        #expect(bytes == server.data.subdata(in: 6144..<6244))
        #expect(server.openCount == 2)
    }

    @Test func evictsChunksFarBehindTheReadPosition() async throws {
        let server = FakeFileServer(data: makeTestData(count: 32 * 1024))
        let source = makeSource(server, aheadChunks: 2, cacheChunks: 6)
        defer { source.cancel() }

        #expect(await readAll(source, count: server.data.count) == server.data)
        let fetchesOfFirstChunk = server.readOffsets.filter { $0 == 0 }.count
        let (count, bytes) = await blockingRead(source, offset: 0, length: 100)
        #expect(count == 100)
        #expect(bytes == server.data.prefix(100))
        #expect(server.readOffsets.filter { $0 == 0 }.count == fetchesOfFirstChunk + 1)
    }

    @Test func ffmpegDecodesAWholeFileWhileTheConnectionKeepsDropping() async throws {
        let bundle = Bundle(for: FakeFile.self)
        let url = try #require(bundle.url(forResource: "decoder-h264-aac", withExtension: "mkv")
            ?? bundle.url(forResource: "decoder-h264-aac", withExtension: "mkv", subdirectory: "Fixtures"))
        let server = FakeFileServer(data: try Data(contentsOf: url))
        server.dropEvery(7)
        let source = makeSource(server, aheadChunks: 4, cacheChunks: 8)

        /// Video and audio frame counts, decoding to the end.
        func decodeAll(_ open: @escaping (EDFFmpegReader) throws -> Void) async throws -> (Int, Int) {
            try await withCheckedThrowingContinuation { continuation in
                DispatchQueue.global().async {
                    let reader = EDFFmpegReader(hardwareDecoding: false)
                    defer { reader.close() }
                    continuation.resume(with: Result {
                        try open(reader)
                        var video = 0
                        var audio = 0
                        while !reader.atEnd {
                            let frames = try reader.readBatch()
                            video += frames.filter { $0.pixelBuffer != nil }.count
                            audio += frames.filter { $0.audioSampleBuffer != nil }.count
                        }
                        return (video, audio)
                    })
                }
            }
        }

        let local = try await decodeAll { try $0.open(url: url) }
        nonisolated(unsafe) let remote = source
        let buffered = try await decodeAll { try $0.open(byteSource: remote, name: url.lastPathComponent) }
        #expect(local.0 > 0 && local.1 > 0)
        #expect(buffered == local)
        // It really did reconnect, many times over.
        #expect(server.openCount > 3)
    }
}
