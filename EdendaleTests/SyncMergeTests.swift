//
//  SyncMergeTests.swift
//  EdendaleTests
//
//  SYNC-PROTOCOL.md Appendix A.1 (main, 915b40d): the merge cases M1–M8
//  every client must pass, stamping, tombstone retention, and the property
//  test of §7.4: any set of devices converges whatever the order their
//  files are read in, and reading a file twice changes nothing.
//

import Foundation
import Testing
@testable import Edendale

@Suite struct SyncMergeTests {

    static let deviceA = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"
    static let deviceB = "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b"
    /// 2026-11-02T00:00:00Z.
    static let now: Int64 = 1_793_577_600_000

    private func file(from device: String, progress: [SyncProgressRecord] = [], userMedia: [SyncUserMediaRecord] = []) -> SyncDeviceFile {
        SyncDeviceFile(
            space: "Family",
            device: SyncDeviceInfo(id: device, name: "Device", platform: "ios", app: "27.1"),
            watchProgress: progress,
            userMedia: userMedia
        )
    }

    private func movie(_ id: Int, position: Double, time: Int64, device: String) -> SyncProgressRecord {
        SyncProgressRecord(type: .movie, tmdbID: id, position: position, duration: 8160, completed: false, device: device, updateTime: time)
    }

    @Test func m1NewestWins() {
        var ledger = SyncLedger(progress: [.movie(603): movie(603, position: 100, time: 1_762_000_000_000, device: Self.deviceA)])
        let incoming = movie(603, position: 200, time: 1_762_000_001_000, device: Self.deviceB)
        let outcome = ledger.merge(file(from: Self.deviceB, progress: [incoming]), now: Self.now)
        #expect(ledger.progress[.movie(603)] == incoming)
        #expect(outcome.changedProgress == [.movie(603)])
        #expect(ledger.highWater == 1_762_000_001_000)
    }

    @Test func m2TieBreakOnDeviceID() {
        let fromB = SyncUserMediaRecord(type: .tv, tmdbID: 1399, favourite: true, device: Self.deviceB, updateTime: 1_762_000_002_000)
        let fromA = SyncUserMediaRecord(type: .tv, tmdbID: 1399, favourite: false, watchlist: true, device: Self.deviceA, updateTime: 1_762_000_002_000)

        var ledger = SyncLedger(userMedia: [.show(1399): fromB])
        ledger.merge(file(from: Self.deviceA, userMedia: [fromA]), now: Self.now)
        #expect(ledger.userMedia[.show(1399)] == fromA)

        // The other order gives the same result.
        var reversed = SyncLedger(userMedia: [.show(1399): fromA])
        let outcome = reversed.merge(file(from: Self.deviceB, userMedia: [fromB]), now: Self.now)
        #expect(reversed.userMedia[.show(1399)] == fromA)
        #expect(outcome.applied == 0)
    }

    @Test func m3TombstoneWins() throws {
        let rated = SyncUserMediaRecord(type: .tv, tmdbID: 1399, rating: 8.0, device: Self.deviceA, updateTime: 1_762_000_003_000)
        let tombstone = SyncUserMediaRecord(type: .tv, tmdbID: 1399, device: Self.deviceB, updateTime: 1_762_000_004_000)
        var ledger = SyncLedger(userMedia: [.show(1399): rated])
        ledger.merge(file(from: Self.deviceB, userMedia: [tombstone]), now: Self.now)
        #expect(ledger.userMedia[.show(1399)] == tombstone)
        #expect(ledger.userMedia[.show(1399)]?.isTombstone == true)

        // Writing the file out includes the tombstone.
        let written = ledger.records(now: Self.now)
        #expect(written.userMedia == [tombstone])
        let data = try SyncFileFormat.encode(file(from: Self.deviceB, userMedia: written.userMedia))
        let parsed = try SyncFileFormat.parse(data, fileName: "\(Self.deviceB).json", space: "Family")
        #expect(parsed.userMedia == [tombstone])
    }

    @Test func m4FarFutureGuard() {
        var ledger = SyncLedger()
        let ahead48h = movie(603, position: 300, time: 1_793_750_400_000, device: Self.deviceB)
        let outcome = ledger.merge(file(from: Self.deviceB, progress: [ahead48h]), now: Self.now)
        #expect(ledger.progress[.movie(603)] == nil)
        #expect(ledger.highWater == 0)
        #expect(outcome.farFutureSkipped == 1)

        let exactly24h = movie(603, position: 300, time: 1_793_664_000_000, device: Self.deviceB)
        #expect(ledger.merge(file(from: Self.deviceB, progress: [exactly24h]), now: Self.now).applied == 1)
        #expect(ledger.highWater == 1_793_664_000_000)

        let justPast = movie(604, position: 300, time: 1_793_664_000_001, device: Self.deviceB)
        let past = ledger.merge(file(from: Self.deviceB, progress: [justPast]), now: Self.now)
        #expect(past.farFutureSkipped == 1)
        #expect(ledger.progress[.movie(604)] == nil)
        #expect(ledger.highWater == 1_793_664_000_000)
    }

    @Test func m5MonotonicStamping() {
        var ledger = SyncLedger(highWater: 1_762_000_005_000)
        #expect(ledger.stamp(now: 1_762_000_000_000) == 1_762_000_005_001)
        #expect(ledger.highWater == 1_762_000_005_001)

        var ahead = SyncLedger(highWater: 1_762_000_005_000)
        #expect(ahead.stamp(now: 1_762_000_009_000) == 1_762_000_009_000)
        #expect(ahead.highWater == 1_762_000_009_000)
    }

    @Test func m6InvalidStampsCountAsZero() throws {
        // Seconds instead of milliseconds, and no updateTime at all.
        let json = """
        {"format":1,"space":"Family","device":{"id":"\(Self.deviceB)","name":"B","platform":"android","app":"27.1"},
         "updateTime":0,
         "watchProgress":[
           {"type":"movie","tmdbId":603,"position":1,"duration":2,"completed":false,"device":"\(Self.deviceB)","updateTime":1500000000},
           {"type":"movie","tmdbId":604,"position":1,"duration":2,"completed":false,"device":"\(Self.deviceB)"}
         ],"userMedia":[]}
        """
        let file = try SyncFileFormat.parse(Data(json.utf8), fileName: "\(Self.deviceB).json", space: "Family")
        #expect(file.watchProgress.map(\.updateTime) == [0, 0])

        let local = movie(603, position: 100, time: 1_762_000_000_000, device: Self.deviceA)
        var ledger = SyncLedger(progress: [.movie(603): local], highWater: 1_762_000_000_000)
        let outcome = ledger.merge(file, now: Self.now)
        #expect(ledger.progress[.movie(603)] == local)
        #expect(ledger.progress[.movie(604)]?.updateTime == 0)
        #expect(outcome.changedProgress == [.movie(604)])
        #expect(ledger.highWater == 1_762_000_000_000)
    }

    @Test func m7SkippedFiles() throws {
        func body(deviceID: String = SyncMergeTests.deviceB, format: Int = 1, space: String = "Family") -> Data {
            Data("""
            {"format":\(format),"space":"\(space)","device":{"id":"\(deviceID)","name":"B","platform":"windows","app":"27.1"},
             "updateTime":1762000001000,
             "watchProgress":[{"type":"movie","tmdbId":603,"position":1,"duration":2,"completed":true,"device":"\(deviceID)","updateTime":1762000001000}],
             "userMedia":[]}
            """.utf8)
        }
        #expect(throws: SyncFileFormat.Rejection.deviceIDMismatch) {
            try SyncFileFormat.parse(body(deviceID: Self.deviceA), fileName: "\(Self.deviceB).json", space: "Family")
        }
        #expect(throws: SyncFileFormat.Rejection.newerFormat(2)) {
            try SyncFileFormat.parse(body(format: 2), fileName: "\(Self.deviceB).json", space: "Family")
        }
        #expect(throws: SyncFileFormat.Rejection.spaceMismatch) {
            try SyncFileFormat.parse(body(space: "Other"), fileName: "\(Self.deviceB).json", space: "Family")
        }
        let huge = Data(count: 9 * 1024 * 1024)
        #expect(throws: SyncFileFormat.Rejection.tooLarge) {
            try SyncFileFormat.parse(huge, fileName: "\(Self.deviceB).json", space: "Family")
        }
        // A skipped file changes nothing: the ledger never saw it.
        let ledger = SyncLedger(progress: [.movie(603): movie(603, position: 1, time: 1_762_000_000_000, device: Self.deviceA)], highWater: 1_762_000_000_000)
        #expect(ledger.highWater == 1_762_000_000_000)
    }

    @Test func m8OrderIndependenceAndIdempotence() {
        let a = file(from: Self.deviceA,
                     progress: [movie(603, position: 100, time: 1_762_000_000_000, device: Self.deviceA)],
                     userMedia: [SyncUserMediaRecord(type: .tv, tmdbID: 1399, rating: 8.0, device: Self.deviceA, updateTime: 1_762_000_003_000)])
        let b = file(from: Self.deviceB,
                     progress: [movie(603, position: 200, time: 1_762_000_001_000, device: Self.deviceB)],
                     userMedia: [SyncUserMediaRecord(type: .tv, tmdbID: 1399, device: Self.deviceB, updateTime: 1_762_000_004_000)])

        var ab = SyncLedger()
        ab.merge(a, now: Self.now); ab.merge(b, now: Self.now); ab.merge(a, now: Self.now); ab.merge(b, now: Self.now)
        var ba = SyncLedger()
        ba.merge(b, now: Self.now); ba.merge(a, now: Self.now)
        let second = ba.merge(b, now: Self.now)
        let third = ba.merge(a, now: Self.now)
        #expect(ab == ba)
        #expect(second.applied == 0)
        #expect(third.applied == 0)
        #expect(ab.progress[.movie(603)]?.device == Self.deviceB)
        #expect(ab.userMedia[.show(1399)]?.isTombstone == true)
    }

    // MARK: - Property test (§7.4)

    @Test(arguments: 1...12)
    func devicesConvergeRegardlessOfReadOrder(seed: Int) {
        var generator = SplitMix(seed: UInt64(seed) &* 0x9E37_79B9_7F4A_7C15)
        let deviceCount = Int.random(in: 2...5, using: &generator)
        let devices = (0..<deviceCount).map { index in
            String(format: "%08x-0000-4000-8000-%012x", index * 7919, index)
        }
        // Each device writes a file of random records over a small key set,
        // with stamps drawn so ties happen.
        let keys = [SyncRecordKey.movie(1), .movie(2), .episode(show: 3, season: 1, episode: 1), .episode(show: 3, season: 1, episode: 2)]
        let userKeys = [SyncRecordKey.movie(1), .show(3), .show(4)]
        let base: Int64 = 1_762_000_000_000
        var files: [SyncDeviceFile] = []
        for device in devices {
            var progress: [SyncProgressRecord] = []
            for key in keys where Bool.random(using: &generator) {
                let cleared = Int.random(in: 0..<4, using: &generator) == 0
                progress.append(SyncProgressRecord(
                    type: key.type, tmdbID: key.tmdbID, season: key.season, episode: key.episode,
                    position: cleared ? nil : Double(Int.random(in: 0...1000, using: &generator)),
                    duration: cleared ? nil : 1000, completed: cleared ? nil : Bool.random(using: &generator),
                    cleared: cleared, device: device, updateTime: base + Int64(Int.random(in: 0..<6, using: &generator)) * 1000
                ))
            }
            var userMedia: [SyncUserMediaRecord] = []
            for key in userKeys where Bool.random(using: &generator) {
                userMedia.append(SyncUserMediaRecord(
                    type: key.type, tmdbID: key.tmdbID,
                    favourite: Bool.random(using: &generator), watchlist: Bool.random(using: &generator),
                    rating: Bool.random(using: &generator) ? Double(Int.random(in: 1...20, using: &generator)) / 2 : nil,
                    device: device, updateTime: base + Int64(Int.random(in: 0..<6, using: &generator)) * 1000
                ))
            }
            files.append(file(from: device, progress: progress, userMedia: userMedia))
        }

        // Every device starts from its own file and reads the others in a
        // random order, some of them twice.
        var ledgers: [SyncLedger] = []
        for (index, own) in files.enumerated() {
            var ledger = SyncLedger()
            ledger.merge(own, now: Self.now)
            var order = files.indices.filter { $0 != index }.shuffled(using: &generator)
            if Bool.random(using: &generator), let repeated = order.randomElement(using: &generator) {
                order.append(repeated)
            }
            for other in order {
                ledger.merge(files[other], now: Self.now)
            }
            ledgers.append(ledger)
        }
        for ledger in ledgers.dropFirst() {
            #expect(ledger.progress == ledgers[0].progress)
            #expect(ledger.userMedia == ledgers[0].userMedia)
            #expect(ledger.highWater == ledgers[0].highWater)
        }
        // Re-reading every file changes nothing.
        var again = ledgers[0]
        for file in files {
            #expect(again.merge(file, now: Self.now).applied == 0)
        }
    }

    // MARK: - Writing

    @Test func oldTombstonesAreOmittedWhenWriting() {
        let yearAgo = Self.now - 370 * 86_400_000
        let recent = Self.now - 10 * 86_400_000
        let ledger = SyncLedger(
            progress: [
                .movie(1): SyncProgressRecord(type: .movie, tmdbID: 1, cleared: true, device: Self.deviceA, updateTime: yearAgo),
                .movie(2): SyncProgressRecord(type: .movie, tmdbID: 2, cleared: true, device: Self.deviceA, updateTime: recent),
                .movie(3): SyncProgressRecord(type: .movie, tmdbID: 3, position: 1, duration: 2, completed: false, device: Self.deviceA, updateTime: yearAgo)
            ],
            userMedia: [
                .movie(1): SyncUserMediaRecord(type: .movie, tmdbID: 1, device: Self.deviceA, updateTime: yearAgo),
                .movie(2): SyncUserMediaRecord(type: .movie, tmdbID: 2, favourite: true, device: Self.deviceA, updateTime: yearAgo)
            ]
        )
        let written = ledger.records(now: Self.now)
        #expect(written.progress.map(\.tmdbID) == [2, 3])
        #expect(written.userMedia.map(\.tmdbID) == [2])
    }

    @Test func stampsOrderByTimeThenDevice() {
        let a = SyncStamp(updateTime: 10, device: Self.deviceA)
        let b = SyncStamp(updateTime: 10, device: Self.deviceB)
        let later = SyncStamp(updateTime: 11, device: Self.deviceB)
        #expect(a.wins(over: b))
        #expect(!b.wins(over: a))
        #expect(later.wins(over: a))
        #expect(a.wins(over: nil))
        #expect(!a.wins(over: a))
    }
}

/// A seeded generator so each property-test case is reproducible.
struct SplitMix: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
