//
//  RemoteFileByteSource.swift
//  Edendale
//
//  NFS (libnfs, see EDRemoteFiles) and SFTP (SFTPConnection) files read
//  through BufferedByteSource, which fetches 1 MiB chunks ahead of FFmpeg on
//  its own thread and reconnects after a drop. The connection opens on the
//  first fetch, so mounting or the SSH handshake never runs on the main
//  thread.
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

/// A BlockingFileConnection as BufferedByteSource's file, with failures
/// turned into what the user reads.
nonisolated final class RemoteFileAdapter: NSObject, BufferedFile {
    private let connection: BlockingFileConnection
    private let kind: MediaSourceKind
    private let host: String

    init(connection: BlockingFileConnection, kind: MediaSourceKind, host: String) {
        self.connection = connection
        self.kind = kind
        self.host = host
    }

    var size: Int64 { connection.fileSize }

    func read(atOffset offset: Int64, into buffer: UnsafeMutablePointer<UInt8>, length: Int, error: NSErrorPointer) -> Int {
        do {
            return try connection.readBytes(at: offset, into: buffer, length: length)
        } catch let failure {
            error?.pointee = Self.describe(failure, kind: kind, host: host)
            return -1
        }
    }

    /// Neither protocol has a no-op request in these wrappers; one byte
    /// costs the same round trip.
    func keepAlive() -> Bool {
        var byte: UInt8 = 0
        return (try? connection.readBytes(at: 0, into: &byte, length: 1)) != nil
    }

    func abort() {
        connection.abortTransfer()
    }

    /// An NSError whose description is the message the player shows.
    static func describe(_ error: Error, kind: MediaSourceKind, host: String) -> NSError {
        let message: String
        if let failure = RemoteFileFailure(error) {
            let detail = (error as NSError).userInfo[NSDebugDescriptionErrorKey] as? String
            let mapped = failure.connectorError(kind: kind, host: host, detail: detail)
            message = (mapped as? LocalizedError)?.errorDescription ?? mapped.localizedDescription
        } else if let error = error as? LocalizedError, let description = error.errorDescription {
            message = description
        } else {
            message = String(localized: "Couldn't read this file from \(host).")
        }
        return NSError(domain: EDRemoteFileErrorDomain, code: (error as NSError).code,
                       userInfo: [NSLocalizedDescriptionKey: message])
    }
}

nonisolated extension BufferedByteSource {
    /// A buffered, reconnecting source over the connections `open` makes;
    /// `open` runs on the source's worker thread at the first read and at
    /// each reconnect.
    static func remoteFile(
        kind: MediaSourceKind,
        host: String,
        open: @escaping @Sendable () throws -> BlockingFileConnection
    ) -> BufferedByteSource {
        BufferedByteSource(host: host) { error in
            do {
                return RemoteFileAdapter(connection: try open(), kind: kind, host: host)
            } catch let failure {
                error?.pointee = RemoteFileAdapter.describe(failure, kind: kind, host: host)
                return nil
            }
        }
    }
}
