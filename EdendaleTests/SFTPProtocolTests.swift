//
//  SFTPProtocolTests.swift
//  EdendaleTests
//
//  SFTP version 3 encoding and decoding, byte for byte: the requests SFTP
//  sources send, the replies they read (including the attribute fields they
//  skip), framing across partial reads, and host-key fingerprints that match
//  `ssh-keygen -l`. The connection itself needs a server (see README).
//

import Foundation
import Testing
@testable import Edendale

@Suite struct SFTPProtocolTests {

    private func bytes(_ data: Data) -> [UInt8] { Array(data) }

    /// A reply packet as a server frames it.
    private func reply(_ build: (inout SFTPProtocol.Writer) -> Void) -> Data {
        var body = SFTPProtocol.Writer()
        build(&body)
        var packet = SFTPProtocol.Writer()
        packet.uint32(UInt32(body.data.count))
        return packet.data + body.data
    }

    @Test func encodesRequestsByteForByte() {
        #expect(bytes(SFTPProtocol.initialize()) == [0, 0, 0, 5, 1, 0, 0, 0, 3])
        #expect(bytes(SFTPProtocol.realpath(id: 1, path: ".")) == [0, 0, 0, 10, 16, 0, 0, 0, 1, 0, 0, 0, 1, 0x2E])
        // Read-only open: flags SSH_FXF_READ, empty attributes.
        #expect(bytes(SFTPProtocol.open(id: 7, path: "/a")) == [
            0, 0, 0, 19, 3, 0, 0, 0, 7, 0, 0, 0, 2, 0x2F, 0x61, 0, 0, 0, 1, 0, 0, 0, 0
        ])
        #expect(bytes(SFTPProtocol.read(id: 9, handle: Data([0xAB, 0xCD]), offset: 0x1_0000_0002, length: 32768)) == [
            0, 0, 0, 23, 5, 0, 0, 0, 9, 0, 0, 0, 2, 0xAB, 0xCD,
            0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0x80, 0
        ])
        #expect(bytes(SFTPProtocol.close(id: 2, handle: Data([1]))) == [0, 0, 0, 10, 4, 0, 0, 0, 2, 0, 0, 0, 1, 1])
    }

    @Test func decodesListingsWithEveryAttributeLayout() throws {
        var buffer = reply { w in
            w.byte(104)
            w.uint32(5)
            w.uint32(4)
            // A file with size, permissions, and times.
            w.string("Heat (1995).mkv")
            w.string("-rw-r--r--    1 me  staff  7 Jan  1 00:00 Heat (1995).mkv")
            w.uint32(0x1 | 0x4 | 0x8)
            w.uint64(7_516_192_768)
            w.uint32(0o100644)
            w.uint32(1)
            w.uint32(1_700_000_000)
            // A folder whose attributes also carry owners and an extension.
            w.string("Season 1")
            w.string("drwxr-xr-x ...")
            w.uint32(0x2 | 0x4 | 0x8000_0000)
            w.uint32(501)
            w.uint32(20)
            w.uint32(0o040755)
            w.uint32(1)
            w.string("vendor@example.com")
            w.string("value")
            // A symbolic link, and a name that isn't UTF-8 (Latin-1 "Café").
            w.string("Latest")
            w.string("lrwxr-xr-x ...")
            w.uint32(0x4)
            w.uint32(0o120777)
            w.string(Data([0x43, 0x61, 0x66, 0xE9]))
            w.string("")
            w.uint32(0)
        }
        let packets = try SFTPProtocol.takePackets(from: &buffer)
        #expect(buffer.isEmpty)
        guard case .name(let id, let entries) = try SFTPProtocol.decode(try #require(packets.first)) else {
            Issue.record("Expected a NAME reply")
            return
        }
        #expect(id == 5)
        #expect(entries.map(\.filename) == ["Heat (1995).mkv", "Season 1", "Latest", "Café"])
        #expect(entries[0].attributes.size == 7_516_192_768)
        #expect(entries[0].attributes.modified == 1_700_000_000)
        #expect(!entries[0].attributes.isDirectory)
        #expect(entries[1].attributes.isDirectory)
        #expect(entries[1].attributes.size == nil)
        #expect(entries[2].attributes.isSymbolicLink)
        #expect(entries[3].attributes == SFTPProtocol.Attributes())
    }

    @Test func decodesTheOtherReplies() throws {
        var buffer = reply { $0.byte(2); $0.uint32(3); $0.string("limits@openssh.com"); $0.string("1") }
        buffer += reply { $0.byte(102); $0.uint32(1); $0.string(Data([9, 8, 7])) }
        buffer += reply { $0.byte(103); $0.uint32(2); $0.string(Data([1, 2, 3, 4])) }
        buffer += reply { $0.byte(105); $0.uint32(3); $0.uint32(0x1); $0.uint64(42) }
        buffer += reply { $0.byte(101); $0.uint32(4); $0.uint32(2); $0.string("No such file"); $0.string("en") }
        // Some servers leave out the message and language tag.
        buffer += reply { $0.byte(101); $0.uint32(5); $0.uint32(1) }

        let replies = try SFTPProtocol.takePackets(from: &buffer).map(SFTPProtocol.decode)
        #expect(replies == [
            .version(3),
            .handle(id: 1, Data([9, 8, 7])),
            .data(id: 2, Data([1, 2, 3, 4])),
            .attributes(id: 3, SFTPProtocol.Attributes(size: 42)),
            .status(id: 4, code: SFTPProtocol.Status.noSuchFile.rawValue, message: "No such file"),
            .status(id: 5, code: SFTPProtocol.Status.eof.rawValue, message: "")
        ])
        #expect(replies.map(\.id) == [nil, 1, 2, 3, 4, 5])
    }

    @Test func framesPacketsSplitAcrossReads() throws {
        let first = reply { $0.byte(103); $0.uint32(1); $0.string(Data(repeating: 0xAA, count: 100)) }
        let second = reply { $0.byte(101); $0.uint32(2); $0.uint32(0); $0.string(""); $0.string("") }

        var buffer = first + second.prefix(7)
        #expect(try SFTPProtocol.takePackets(from: &buffer).count == 1)
        #expect(buffer == second.prefix(7))

        buffer += second.dropFirst(7)
        let rest = try SFTPProtocol.takePackets(from: &buffer)
        #expect(rest.count == 1)
        #expect(buffer.isEmpty)
        #expect(try SFTPProtocol.decode(rest[0]) == .status(id: 2, code: 0, message: ""))
    }

    @Test func refusesMalformedPackets() {
        var oversized = Data([0x7F, 0xFF, 0xFF, 0xFF, 103])
        #expect(throws: SFTPProtocol.DecodingError.self) {
            _ = try SFTPProtocol.takePackets(from: &oversized)
        }
        #expect(throws: SFTPProtocol.DecodingError.unknownType(250)) {
            _ = try SFTPProtocol.decode(Data([250, 0, 0, 0, 1]))
        }
        // A NAME reply that promises more entries than it carries.
        #expect(throws: SFTPProtocol.DecodingError.truncated) {
            _ = try SFTPProtocol.decode(Data([104, 0, 0, 0, 1, 0, 0, 0, 2]))
        }
    }

    /// Answers from `ssh-keygen -lf` for keys generated for this test.
    @Test func fingerprintsMatchSSHKeygen() {
        #expect(SFTPProtocol.fingerprint(openSSHPublicKey:
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIK6QWabgywKnlWMa6NnGVZCaWfP0glCGC4V0W/8HlsTr edendale-test"
        ) == "SHA256:zlzog2w4EauQF30MI712Zx+Y/3R5V2fDgoiWSU2h/t0")
        #expect(SFTPProtocol.fingerprint(openSSHPublicKey:
            "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBCAaz8Es2+hJ8igM3+ORqT4R02ofHWyhbnQmCuku8wCTB5X67m8DLyyqkE1onLjChUZC9864xxekJWFAA3NiaP0="
        ) == "SHA256:TKLRif70yHWH3oK7nqHIfFl2geOpqjP96zzTUpSSJsA")
        #expect(SFTPProtocol.fingerprint(openSSHPublicKey: "ssh-ed25519") == nil)
        #expect(SFTPHostKey(algorithm: "ssh-ed25519", fingerprint: "").typeName == "ED25519")
        #expect(SFTPHostKey(algorithm: "ecdsa-sha2-nistp384", fingerprint: "").typeName == "ECDSA")
    }
}
