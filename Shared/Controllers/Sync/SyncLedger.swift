//
//  SyncLedger.swift
//  Edendale
//
//  The merge of SYNC-PROTOCOL.md §7, kept pure so Appendix A's cases and
//  the property test run without a store or a location: a set of records
//  keyed as §6 keys them, the high-water mark, and the three rules that
//  keep one `updateTime` field safe. Newest wins, the whole record at a
//  time; ties go to the lexically smaller device ID; a write is stamped at
//  least one past the highest stamp applied; and a stamp more than a day
//  ahead of this device's clock is refused and flagged.
//
//  SyncCoordinator builds a ledger from the local stores before each sync,
//  merges the other devices' files into it, and applies the keys that
//  changed back to the stores.
//

import Foundation

nonisolated struct SyncLedger: Equatable, Sendable {
    /// §7.1: records stamped further ahead than this are not applied.
    static let farFutureAllowance: Int64 = 86_400_000

    var progress: [SyncRecordKey: SyncProgressRecord]
    var userMedia: [SyncRecordKey: SyncUserMediaRecord]
    /// The highest `updateTime` applied or written (§7.2).
    var highWater: Int64

    init(
        progress: [SyncRecordKey: SyncProgressRecord] = [:],
        userMedia: [SyncRecordKey: SyncUserMediaRecord] = [:],
        highWater: Int64 = 0
    ) {
        self.progress = progress
        self.userMedia = userMedia
        self.highWater = highWater
    }

    /// What one file's merge did.
    struct Outcome: Equatable, Sendable {
        /// Keys whose record was replaced or inserted.
        var changedProgress: [SyncRecordKey] = []
        var changedUserMedia: [SyncRecordKey] = []
        /// Records skipped for a stamp past the far-future guard; the UI
        /// names the device.
        var farFutureSkipped = 0
        /// Records read, applied or not.
        var read = 0

        var applied: Int { changedProgress.count + changedUserMedia.count }
    }

    // MARK: - Merge

    /// Merges another device's validated file (§7.1). `now` is this
    /// device's UTC clock in milliseconds. Reading the same file twice
    /// changes nothing the second time.
    @discardableResult
    mutating func merge(_ file: SyncDeviceFile, now: Int64) -> Outcome {
        var outcome = Outcome()
        let limit = now + Self.farFutureAllowance

        for record in file.watchProgress {
            outcome.read += 1
            guard record.updateTime <= limit else {
                outcome.farFutureSkipped += 1
                continue
            }
            let key = record.key
            if record.stamp.wins(over: progress[key]?.stamp), progress[key] != record {
                progress[key] = record
                outcome.changedProgress.append(key)
            }
            highWater = max(highWater, record.updateTime)
        }

        for record in file.userMedia {
            outcome.read += 1
            guard record.updateTime <= limit else {
                outcome.farFutureSkipped += 1
                continue
            }
            let key = record.key
            if record.stamp.wins(over: userMedia[key]?.stamp), userMedia[key] != record {
                userMedia[key] = record
                outcome.changedUserMedia.append(key)
            }
            highWater = max(highWater, record.updateTime)
        }
        return outcome
    }

    // MARK: - Stamping

    /// The stamp for a local write (§7.2): `max(now, highWater + 1)`, which
    /// becomes the new high-water mark.
    mutating func stamp(now: Int64) -> Int64 {
        let stamp = Self.nextStamp(now: now, highWater: highWater)
        highWater = stamp
        return stamp
    }

    static func nextStamp(now: Int64, highWater: Int64) -> Int64 {
        max(now, highWater + 1)
    }

    /// Raises the high-water mark to a stamp applied by another path (a
    /// CloudKit record, for example) without writing anything.
    mutating func observe(_ updateTime: Int64) {
        highWater = max(highWater, updateTime)
    }

    // MARK: - Output

    /// The records this device writes (§5, §6.3): every record it holds,
    /// minus tombstones older than `tombstoneRetention` milliseconds.
    func records(now: Int64, tombstoneRetention: Int64 = SyncLedger.tombstoneRetention)
        -> (progress: [SyncProgressRecord], userMedia: [SyncUserMediaRecord]) {
        let cutoff = now - tombstoneRetention
        let progressOut = progress.values
            .filter { !$0.cleared || $0.updateTime >= cutoff }
            .sorted { $0.key < $1.key }
        let userMediaOut = userMedia.values
            .filter { !$0.isTombstone || $0.updateTime >= cutoff }
            .sorted { $0.key < $1.key }
        return (progressOut, userMediaOut)
    }

    /// 365 days (§6.3, decision 4), plus the day a stamp may legitimately
    /// run ahead of another device's clock (§7.1), so a tombstone exactly a
    /// year old is still written (Appendix A.1, M3).
    static let tombstoneRetention: Int64 = (365 + 1) * 86_400_000

    /// Milliseconds since 1970 for a date.
    static func milliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    static func date(milliseconds: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(milliseconds) / 1000)
    }
}
