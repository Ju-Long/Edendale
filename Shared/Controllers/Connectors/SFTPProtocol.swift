//
//  SFTPProtocol.swift
//  Edendale
//
//  The read-only part of SFTP version 3 (draft-ietf-secsh-filexfer-02, which
//  OpenSSH and NAS servers speak) that SFTP sources use: the login folder,
//  directory listings, stat, and ranged reads of an open file. Only encoding
//  and decoding live here, so tests cover them without a server; the
//  connection is SFTPConnection.
//
//  Packets are a 4-byte big-endian length, a type byte, and the payload.
//  Strings are 4-byte length-prefixed bytes.
//

import CryptoKit
import Foundation

nonisolated enum SFTPProtocol {
    static let version: UInt32 = 3
    /// Larger packets are refused, so a misbehaving server can't make the
    /// app buffer without bound. Reads ask for far less (see SFTPConnection).
    static let maxPacketLength = 4 << 20

    /// `SSH_FXP_*` packet types.
    enum PacketType: UInt8 {
        case initialize = 1, version = 2, open = 3, close = 4, read = 5, fstat = 8
        case opendir = 11, readdir = 12, realpath = 16, stat = 17
        case status = 101, handle = 102, data = 103, name = 104, attributes = 105
    }

    /// `SSH_FX_*` status codes.
    enum Status: UInt32 {
        case ok = 0, eof = 1, noSuchFile = 2, permissionDenied = 3, failure = 4
        case badMessage = 5, noConnection = 6, connectionLost = 7, unsupported = 8
    }

    /// The attributes listings use; the rest are skipped.
    struct Attributes: Equatable, Sendable {
        var size: UInt64?
        var permissions: UInt32?
        var modified: UInt32?

        private static let typeMask: UInt32 = 0o170000
        var isDirectory: Bool { permissions.map { $0 & Self.typeMask == 0o040000 } ?? false }
        var isSymbolicLink: Bool { permissions.map { $0 & Self.typeMask == 0o120000 } ?? false }
    }

    struct NameEntry: Equatable, Sendable {
        let filename: String
        let attributes: Attributes
    }

    enum Response: Equatable, Sendable {
        case version(UInt32)
        case status(id: UInt32, code: UInt32, message: String)
        case handle(id: UInt32, Data)
        case data(id: UInt32, Data)
        case name(id: UInt32, [NameEntry])
        case attributes(id: UInt32, Attributes)

        /// The request this answers; `nil` for the version reply to `init`.
        var id: UInt32? {
            switch self {
            case .version: nil
            case .status(let id, _, _), .handle(let id, _), .data(let id, _),
                 .name(let id, _), .attributes(let id, _):
                id
            }
        }
    }

    enum DecodingError: Error, Equatable {
        case truncated
        case unknownType(UInt8)
        case oversized(Int)
    }

    // MARK: - Requests
    //
    // Each returns a whole packet, length included.

    static func initialize() -> Data {
        packet(.initialize) { $0.uint32(version) }
    }

    static func realpath(id: UInt32, path: String) -> Data {
        packet(.realpath) { $0.uint32(id); $0.string(path) }
    }

    static func opendir(id: UInt32, path: String) -> Data {
        packet(.opendir) { $0.uint32(id); $0.string(path) }
    }

    static func readdir(id: UInt32, handle: Data) -> Data {
        packet(.readdir) { $0.uint32(id); $0.string(handle) }
    }

    static func stat(id: UInt32, path: String) -> Data {
        packet(.stat) { $0.uint32(id); $0.string(path) }
    }

    /// Opens a file for reading (`SSH_FXF_READ`, no attributes).
    static func open(id: UInt32, path: String) -> Data {
        packet(.open) { $0.uint32(id); $0.string(path); $0.uint32(0x1); $0.uint32(0) }
    }

    static func fstat(id: UInt32, handle: Data) -> Data {
        packet(.fstat) { $0.uint32(id); $0.string(handle) }
    }

    static func read(id: UInt32, handle: Data, offset: UInt64, length: UInt32) -> Data {
        packet(.read) { $0.uint32(id); $0.string(handle); $0.uint64(offset); $0.uint32(length) }
    }

    static func close(id: UInt32, handle: Data) -> Data {
        packet(.close) { $0.uint32(id); $0.string(handle) }
    }

    private static func packet(_ type: PacketType, _ body: (inout Writer) -> Void) -> Data {
        var writer = Writer()
        writer.byte(type.rawValue)
        body(&writer)
        var packet = Writer()
        packet.uint32(UInt32(writer.data.count))
        return packet.data + writer.data
    }

    // MARK: - Responses

    /// Removes and returns every complete packet body (type and payload) at
    /// the front of `buffer`, leaving a partial one for the next read.
    static func takePackets(from buffer: inout Data) throws -> [Data] {
        var packets: [Data] = []
        var start = buffer.startIndex
        while buffer.endIndex - start >= 4 {
            let length = buffer[start..<start + 4].reduce(0) { $0 << 8 | Int($1) }
            guard length > 0, length <= maxPacketLength else { throw DecodingError.oversized(length) }
            guard buffer.endIndex - start - 4 >= length else { break }
            packets.append(Data(buffer[start + 4..<start + 4 + length]))
            start += 4 + length
        }
        buffer.removeSubrange(buffer.startIndex..<start)
        return packets
    }

    /// Decodes one packet body from `takePackets(from:)`.
    static func decode(_ body: Data) throws -> Response {
        var reader = Reader(body)
        let rawType = try reader.byte()
        guard let type = PacketType(rawValue: rawType) else { throw DecodingError.unknownType(rawType) }
        switch type {
        case .version:
            // Extension pairs may follow; nothing here needs them.
            return .version(try reader.uint32())
        case .status:
            let id = try reader.uint32()
            let code = try reader.uint32()
            // Some servers omit the message and language tag.
            let message = (try? reader.string()).map(decodeName) ?? ""
            return .status(id: id, code: code, message: message)
        case .handle:
            return .handle(id: try reader.uint32(), try reader.string())
        case .data:
            return .data(id: try reader.uint32(), try reader.string())
        case .name:
            let id = try reader.uint32()
            let count = try reader.uint32()
            var entries: [NameEntry] = []
            for _ in 0..<count {
                let filename = decodeName(try reader.string())
                _ = try reader.string() // `ls -l` style long name
                entries.append(NameEntry(filename: filename, attributes: try reader.attributes()))
            }
            return .name(id: id, entries)
        case .attributes:
            return .attributes(id: try reader.uint32(), try reader.attributes())
        default:
            throw DecodingError.unknownType(rawType)
        }
    }

    /// Version 3 names are bytes; nearly every server sends UTF-8, and a
    /// Latin-1 fallback keeps any other name listable.
    static func decodeName(_ bytes: Data) -> String {
        String(data: bytes, encoding: .utf8) ?? String(data: bytes, encoding: .isoLatin1) ?? ""
    }

    // MARK: - Host keys

    /// OpenSSH's fingerprint for a public key in `authorized_keys` form
    /// (`ssh-ed25519 AAAA… [comment]`): `SHA256:` and the unpadded Base64 of
    /// the key blob's SHA-256, as `ssh-keygen -l` prints it.
    static func fingerprint(openSSHPublicKey: String) -> String? {
        let fields = openSSHPublicKey.split(separator: " ")
        guard fields.count >= 2, let blob = Data(base64Encoded: String(fields[1])) else { return nil }
        let digest = Data(SHA256.hash(data: blob)).base64EncodedString()
        return "SHA256:" + digest.trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }

    // MARK: - Wire format

    struct Writer {
        private(set) var data = Data()

        mutating func byte(_ value: UInt8) {
            data.append(value)
        }

        mutating func uint32(_ value: UInt32) {
            withUnsafeBytes(of: value.bigEndian) { data.append(contentsOf: $0) }
        }

        mutating func uint64(_ value: UInt64) {
            withUnsafeBytes(of: value.bigEndian) { data.append(contentsOf: $0) }
        }

        mutating func string(_ value: Data) {
            uint32(UInt32(value.count))
            data.append(value)
        }

        mutating func string(_ value: String) {
            string(Data(value.utf8))
        }
    }

    struct Reader {
        private let data: Data
        private var offset: Int

        init(_ data: Data) {
            self.data = data
            self.offset = data.startIndex
        }

        mutating func byte() throws -> UInt8 {
            guard offset < data.endIndex else { throw DecodingError.truncated }
            defer { offset += 1 }
            return data[offset]
        }

        mutating func uint32() throws -> UInt32 {
            try bytes(4).reduce(0) { $0 << 8 | UInt32($1) }
        }

        mutating func uint64() throws -> UInt64 {
            try bytes(8).reduce(0) { $0 << 8 | UInt64($1) }
        }

        mutating func string() throws -> Data {
            try bytes(Int(try uint32()))
        }

        /// `ATTRS`: flags, then only the fields the flags announce.
        mutating func attributes() throws -> Attributes {
            let flags = try uint32()
            var attributes = Attributes()
            if flags & 0x1 != 0 { attributes.size = try uint64() }
            if flags & 0x2 != 0 { _ = try uint32(); _ = try uint32() } // uid, gid
            if flags & 0x4 != 0 { attributes.permissions = try uint32() }
            if flags & 0x8 != 0 { _ = try uint32(); attributes.modified = try uint32() } // atime, mtime
            if flags & 0x8000_0000 != 0 {
                let count = try uint32()
                for _ in 0..<count {
                    _ = try string()
                    _ = try string()
                }
            }
            return attributes
        }

        private mutating func bytes(_ count: Int) throws -> Data {
            guard count >= 0, data.endIndex - offset >= count else { throw DecodingError.truncated }
            defer { offset += count }
            return data[offset..<offset + count]
        }
    }
}
