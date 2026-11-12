//
//  WatchProgressStore.swift
//  Edendale
//
//  Persists watch state to iCloud via CoreData + CloudKit, and since 27.1
//  stamps every write for the file sync (SYNC-PROTOCOL.md §7.2): a local
//  change gets `max(now, highWater + 1)` and this device's ID, a removal
//  becomes a tombstone row that stays, and a record applied from another
//  device's file is stored verbatim. Rows that arrive through CloudKit keep
//  the stamp and device they were written with, so a device in both iCloud
//  and a sync space bridges them (FEATURES-27.1.md §5.5).
//

import Foundation
import CoreData

@MainActor
@Observable
final class WatchProgressStore {

    /// Posted after a local change (never after a synced one), so sync
    /// can write this device's file after a pause.
    nonisolated static let didChangeLocally = Notification.Name("Edendale.WatchState.didChangeLocally")

    private let container: NSPersistentCloudKitContainer
    private var viewContext: NSManagedObjectContext { container.viewContext }
    private let stamper: SyncStamper

    /// In-memory cache of live rows; mutating it triggers SwiftUI re-renders via @Observable.
    private(set) var progressMap: [String: WatchProgress] = [:]
    /// Cleared rows, kept for the sync file.
    @ObservationIgnored private(set) var tombstones: [String: WatchProgress] = [:]

    init(container: NSPersistentCloudKitContainer = Persistence.cloudPersistentContainer, stamper: SyncStamper = .shared) {
        self.container = container
        self.stamper = stamper
        loadAll()

        // Observe remote CloudKit changes and merge into cache.
        Task { @MainActor [weak self] in
            for await _ in NotificationCenter.default.notifications(
                named: .NSPersistentStoreRemoteChange,
                object: container.persistentStoreCoordinator
            ) {
                self?.loadAll()
            }
        }
    }

    // MARK: - Read

    func progress(for tmdbId: Int, mediaType: WatchMediaType) -> WatchProgress? {
        progressMap[cacheKey(tmdbId, mediaType)]
    }

    func isWatched(_ tmdbId: Int, mediaType: WatchMediaType) -> Bool {
        progressMap[cacheKey(tmdbId, mediaType)]?.isCompleted == true
    }

    /// Everything partially watched, most recent first — drives Continue Watching.
    var inProgress: [WatchProgress] {
        progressMap.values
            .filter { !$0.isCompleted && $0.position > 0 }
            .sorted { $0.lastWatchedAt > $1.lastWatchedAt }
    }

    /// Every row, tombstones included, for this device's sync file. Rows
    /// from before 27.1 are stamped once from their last-watched time
    /// (ties by this device's ID), in one save, so they can be written out.
    func allRecords() -> [WatchProgress] {
        var records: [WatchProgress] = []
        var legacy: [WatchProgress] = []
        for record in Array(progressMap.values) + Array(tombstones.values) {
            if record.updateTime == 0 || record.deviceID == nil {
                var stamped = record
                stamped.updateTime = max(SyncLedger.milliseconds(record.lastWatchedAt), SyncFileFormat.minimumStamp)
                stamped.deviceID = stamper.deviceID
                legacy.append(stamped)
                records.append(stamped)
            } else {
                records.append(record)
            }
        }
        if !legacy.isEmpty { persist(legacy, local: false) }
        return records
    }

    /// The local episode ID of every row that names its show, season, and
    /// episode: how another device's file keys an episode.
    func episodeIndex() -> [SyncRecordKey: Int] {
        var index: [SyncRecordKey: Int] = [:]
        for record in Array(progressMap.values) + Array(tombstones.values) where record.mediaType == .episode {
            if let key = record.syncKey { index[key] = record.tmdbId }
        }
        return index
    }

    // MARK: - Write

    func update(_ progress: WatchProgress) {
        var stamped = progress
        let stamp = stamper.stamp()
        stamped.updateTime = stamp.updateTime
        stamped.deviceID = stamp.device
        stamped.cleared = false
        persist([stamped], local: true)
    }

    /// Marks an item watched. An episode passes its show and numbers: they
    /// are how Continue Watching finds the next episode and how the sync
    /// file names the record (SYNC-PROTOCOL.md §6.1); without them a mark
    /// made by hand neither suggested the next episode nor reached devices
    /// syncing through files. Context a row already has is kept.
    func markCompleted(
        tmdbId: Int,
        mediaType: WatchMediaType,
        showTmdbId: Int? = nil,
        seasonNumber: Int? = nil,
        episodeNumber: Int? = nil
    ) {
        var p = progressMap[cacheKey(tmdbId, mediaType)]
            ?? tombstones[cacheKey(tmdbId, mediaType)]
            ?? WatchProgress(tmdbId: tmdbId, mediaType: mediaType)
        p.position = 1.0
        p.isCompleted = true
        p.lastWatchedAt = Date()
        if mediaType == .episode {
            p.showTmdbId = p.showTmdbId.flatMap { $0 > 0 ? $0 : nil } ?? showTmdbId
            p.seasonNumber = p.seasonNumber ?? seasonNumber
            p.episodeNumber = p.episodeNumber ?? episodeNumber
        }
        update(p)
    }

    /// Clears the progress: the row stays as a tombstone with a new stamp,
    /// so an older copy on another device cannot bring it back.
    func remove(tmdbId: Int, mediaType: WatchMediaType) {
        let key = cacheKey(tmdbId, mediaType)
        guard var p = progressMap[key] ?? tombstones[key] else { return }
        let stamp = stamper.stamp()
        p.position = 0
        p.watchedSeconds = 0
        p.isCompleted = false
        p.lastWatchedAt = Date()
        p.cleared = true
        p.updateTime = stamp.updateTime
        p.deviceID = stamp.device
        persist([p], local: true)
    }

    // MARK: - Sync

    /// Stores records another device wrote, verbatim (stamps and devices
    /// included), in one save and with no local-change notification.
    func applySynced(_ records: [WatchProgress]) {
        guard !records.isEmpty else { return }
        persist(records, local: false)
        stamper.observe(stamps: records.map(\.updateTime))
    }

    func applySynced(_ progress: WatchProgress) {
        applySynced([progress])
    }

    // MARK: - Private

    private func persist(_ records: [WatchProgress], local: Bool) {
        for progress in records {
            let key = cacheKey(progress.tmdbId, progress.mediaType)
            if progress.cleared {
                progressMap.removeValue(forKey: key)
                tombstones[key] = progress
            } else {
                tombstones.removeValue(forKey: key)
                progressMap[key] = progress
            }
            let request = CDWatchProgress.fetchRequest(tmdbId: progress.tmdbId, mediaType: progress.mediaType)
            do {
                let cd = try viewContext.fetch(request).first
                    // By entity name: `CDWatchProgress(context:)` asks the class for its
                    // entity, which is ambiguous once another model version is loaded.
                    ?? NSEntityDescription.insertNewObject(forEntityName: "CDWatchProgress", into: viewContext) as! CDWatchProgress
                cd.apply(progress)
            } catch {
                print("[WatchProgressStore] Failed to fetch for saving: \(error)")
            }
        }
        do {
            if viewContext.hasChanges { try viewContext.save() }
        } catch {
            print("[WatchProgressStore] Failed to save: \(error)")
        }
        if local {
            NotificationCenter.default.post(name: Self.didChangeLocally, object: nil)
        }
    }

    private func cacheKey(_ tmdbId: Int, _ mediaType: WatchMediaType) -> String {
        "wp_\(mediaType.rawValue)_\(tmdbId)"
    }

    /// Reload all records from CoreData into the in-memory cache.
    private func loadAll() {
        let request = NSFetchRequest<CDWatchProgress>(entityName: "CDWatchProgress")
        request.shouldRefreshRefetchedObjects = true
        do {
            let results = try viewContext.fetch(request)
            var rebuilt: [String: WatchProgress] = [:]
            var cleared: [String: WatchProgress] = [:]
            var stamps: [Int64] = []
            for cd in results {
                let dto = cd.toDTO()
                let key = cacheKey(dto.tmdbId, dto.mediaType)
                if dto.cleared {
                    cleared[key] = dto
                } else {
                    rebuilt[key] = dto
                }
                stamps.append(dto.updateTime)
            }
            progressMap = rebuilt
            tombstones = cleared
            // Stamps that arrived through CloudKit order this device's
            // next writes after them (§7.2); the stamper ignores any more
            // than a day ahead (§7.1).
            stamper.observe(stamps: stamps)
        } catch {
            print("[WatchProgressStore] Failed to load: \(error)")
        }
    }
}
