//
//  RemoteFileByteSource.swift
//  Edendale
//
//  EDByteSource over a blocking file connection: NFS (libnfs, see
//  EDRemoteFiles) or SFTP (SFTPConnection). The connection opens on the
//  first read, which FFmpeg makes on its worker queue, so mounting or the
//  SSH handshake never runs on the main thread. Reads fetch at least 1 MiB
//  at a time and serve FFmpeg's 64 KiB requests from that, cutting round
//  trips sixteenfold.
//

import Foundation

/// The NFS and SFTP connections, seen through one shape.
nonisolated protocol BlockingFileConnection: AnyObject {
    var fileSize: Int64 { get }
    /// Byte count, 0 at the end of the file.
    func readBytes(at offset: Int64, into buffer: UnsafeMutablePointer<UInt8>, length: Int) throws -> Int
    /// Fails a call in progress, when the transport allows it.
    func abortTransfer()
}

nonisolated extension NFSConnection: BlockingFileConnection {
    var fileSize: Int64 { size }

    func readBytes(at offset: Int64, into buffer: UnsafeMutablePointer<UInt8>, length: Int) throws -> Int {
        var error: NSError?
        let count = read(atOffset: offset, into: buffer, length: length, error: &error)
        if count < 0 { throw error ?? RemoteFileFailure.io.error }
        return count
    }

    /// libnfs's synchronous calls can't be interrupted; they time out.
    func abortTransfer() {}
}

/// An SFTP connection with the file it reads.
nonisolated final class SFTPFileConnection: BlockingFileConnection {
    let connection: SFTPConnection
    let fileSize: Int64

    init(connection: SFTPConnection, fileSize: Int64) {
        self.connection = connection
        self.fileSize = fileSize
    }

    func readBytes(at offset: Int64, into buffer: UnsafeMutablePointer<UInt8>, length: Int) throws -> Int {
        try connection.read(atOffset: offset, into: buffer, length: length)
    }

    func abortTransfer() {
        connection.abort()
    }
}

/// `EDRemoteFileError` codes, mirrored so Swift can switch on them.
nonisolated enum RemoteFileFailure: Int {
    case connect = 1, handshake, authentication, mount, notFound, io, cancelled

    init?(_ error: Error) {
        let error = error as NSError
        guard error.domain == EDRemoteFileErrorDomain else { return nil }
        self.init(rawValue: error.code)
    }

    var error: NSError {
        NSError(domain: EDRemoteFileErrorDomain, code: rawValue)
    }

    /// What the user reads for this failure on `host`.
    func connectorError(kind: MediaSourceKind, host: String, detail: String? = nil) -> Error {
        switch self {
        case .connect: ConnectorError.unreachable(host: host)
        case .handshake: ConnectorError.unreachable(host: host)
        case .authentication: ConnectorError.authenticationFailed(host: host)
        case .mount:
            ConnectorError.nfsMountFailed(
                host: host,
                reason: detail ?? String(localized: "the server refused the mount")
            )
        case .notFound: ConnectorError.notFound(provider: host)
        case .io: ConnectorError.unreachable(host: host)
        case .cancelled: CancellationError()
        }
    }
}

nonisolated final class RemoteFileByteSource: NSObject, ByteSource, @unchecked Sendable {
    private static let readAhead = 1 << 20

    private let kind: MediaSourceKind
    private let host: String
    private let open: @Sendable () throws -> BlockingFileConnection

    private let lock = NSLock()
    private var connection: BlockingFileConnection?
    private var isCancelled = false
    private var reason: String?
    private var buffer = Data()
    private var bufferOffset: Int64 = 0

    /// - Parameter open: Connects and opens the file; runs on FFmpeg's
    ///   worker queue at the first read.
    init(kind: MediaSourceKind, host: String, open: @escaping @Sendable () throws -> BlockingFileConnection) {
        self.kind = kind
        self.host = host
        self.open = open
        super.init()
    }

    var length: Int64 {
        lock.withLock { connection?.fileSize ?? -1 }
    }

    var failureReason: String? {
        lock.withLock { reason }
    }

    func read(
        atOffset offset: Int64,
        into destination: UnsafeMutablePointer<UInt8>,
        length requested: Int,
        shouldAbort: () -> Bool
    ) -> Int {
        guard requested > 0, offset >= 0 else { return 0 }
        guard let connection = openedConnection(), !shouldAbort() else { return -1 }

        let size = connection.fileSize
        if size >= 0, offset >= size { return 0 }

        // Serve from the read-ahead buffer when it covers the offset.
        let buffered = lock.withLock { () -> Int? in
            let start = offset - bufferOffset
            guard start >= 0, start < Int64(buffer.count) else { return nil }
            let count = min(requested, buffer.count - Int(start))
            buffer.withUnsafeBytes { raw in
                destination.update(
                    from: raw.baseAddress!.assumingMemoryBound(to: UInt8.self) + Int(start),
                    count: count
                )
            }
            return count
        }
        if let buffered { return buffered }

        var fetchLength = max(requested, Self.readAhead)
        if size >= 0 { fetchLength = Int(min(Int64(fetchLength), size - offset)) }
        var chunk = Data(count: fetchLength)
        do {
            let count = try chunk.withUnsafeMutableBytes { raw in
                try connection.readBytes(
                    at: offset,
                    into: raw.baseAddress!.assumingMemoryBound(to: UInt8.self),
                    length: fetchLength
                )
            }
            chunk.count = count
        } catch {
            fail(error)
            return -1
        }
        guard !chunk.isEmpty else { return 0 }
        let count = min(requested, chunk.count)
        chunk.withUnsafeBytes { raw in
            destination.update(from: raw.baseAddress!.assumingMemoryBound(to: UInt8.self), count: count)
        }
        lock.withLock {
            buffer = chunk
            bufferOffset = offset
        }
        return count
    }

    func cancel() {
        let connection = lock.withLock { () -> BlockingFileConnection? in
            isCancelled = true
            buffer = Data()
            return self.connection
        }
        connection?.abortTransfer()
    }

    // MARK: - Private

    private func openedConnection() -> BlockingFileConnection? {
        let state = lock.withLock { (isCancelled, connection) }
        if state.0 { return nil }
        if let connection = state.1 { return connection }
        do {
            let opened = try open()
            let cancelled = lock.withLock { () -> Bool in
                if !isCancelled { connection = opened }
                return isCancelled
            }
            if cancelled {
                opened.abortTransfer()
                return nil
            }
            return opened
        } catch {
            fail(error)
            return nil
        }
    }

    private func fail(_ error: Error) {
        let message: String?
        if error is CancellationError {
            // Cancelled on purpose; there's nothing to explain.
            message = nil
        } else if let failure = RemoteFileFailure(error) {
            let detail = (error as NSError).userInfo[NSDebugDescriptionErrorKey] as? String
            let mapped = failure.connectorError(kind: kind, host: host, detail: detail)
            message = mapped is CancellationError ? nil : mapped.localizedDescription
        } else if let error = error as? LocalizedError {
            message = error.errorDescription
        } else {
            message = String(localized: "Couldn't read this file from \(host).")
        }
        lock.withLock { reason = message }
    }
}
