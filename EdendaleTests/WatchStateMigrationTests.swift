//
//  WatchStateMigrationTests.swift
//  EdendaleTests
//
//  The 27.1 watch-state model (version 2) adds sync fields to rows every
//  existing install already has. A store written by the shipped model must
//  open under the new one by lightweight migration (the app stops at
//  launch if it doesn't), keep every value, and give the new fields their
//  defaults. Then the rows from before 27.1 meet sync: the watchlist flag
//  comes from the local watchlist, and the first sync stamps them from
//  their own times.
//

import CoreData
import Foundation
import Testing
@testable import Edendale

@MainActor
@Suite(.serialized)
struct WatchStateMigrationTests {

    private func compiledModel(named name: String) throws -> NSManagedObjectModel {
        let momd = try #require(Bundle.main.url(forResource: "WatchProgress", withExtension: "momd"))
        let url = momd.appendingPathComponent("\(name).mom")
        return try #require(NSManagedObjectModel(contentsOf: url))
    }

    @Test func storesFromTheShippedModelMigrateAndKeepTheirValues() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("migration-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let storeURL = directory.appendingPathComponent("WatchProgress.sqlite")
        let watched = Date(timeIntervalSince1970: 1_760_000_000)

        // An install from before 27.1, on the model it shipped with.
        let shipped = try compiledModel(named: "WatchProgress")
        #expect(shipped.entitiesByName["CDWatchProgress"]?.attributesByName["updateTime"] == nil)
        do {
            let old = NSPersistentContainer(name: "WatchProgress", managedObjectModel: shipped)
            let description = NSPersistentStoreDescription(url: storeURL)
            description.setOption(true as NSNumber, forKey: NSPersistentHistoryTrackingKey)
            old.persistentStoreDescriptions = [description]
            var loadError: Error?
            old.loadPersistentStores { _, error in loadError = error }
            #expect(loadError == nil)
            let context = old.viewContext
            let progress = NSEntityDescription.insertNewObject(forEntityName: "CDWatchProgress", into: context)
            progress.setValue(Int32(603), forKey: "tmdbId")
            progress.setValue("movie", forKey: "mediaType")
            progress.setValue(0.5, forKey: "position")
            progress.setValue(8160.0, forKey: "watchedSeconds")
            progress.setValue(watched, forKey: "lastWatchedAt")
            let media = NSEntityDescription.insertNewObject(forEntityName: "CDUserMedia", into: context)
            media.setValue(Int32(1399), forKey: "tmdbId")
            media.setValue("tv", forKey: "mediaType")
            media.setValue(true, forKey: "isFavorite")
            media.setValue(8.0, forKey: "rating")
            media.setValue(watched, forKey: "updatedAt")
            try context.save()
            for store in old.persistentStoreCoordinator.persistentStores {
                try old.persistentStoreCoordinator.remove(store)
            }
        }

        // The update opens it as the app does, on the current model.
        let current = Persistence.cloudPersistentContainer.managedObjectModel
        #expect(current.entitiesByName["CDWatchProgress"]?.attributesByName["updateTime"] != nil)
        let container = NSPersistentCloudKitContainer(name: "WatchProgress", managedObjectModel: current)
        let description = NSPersistentStoreDescription(url: storeURL)
        description.cloudKitContainerOptions = nil
        description.setOption(true as NSNumber, forKey: NSPersistentStoreRemoteChangeNotificationPostOptionKey)
        description.setOption(true as NSNumber, forKey: NSPersistentHistoryTrackingKey)
        #expect(description.shouldMigrateStoreAutomatically)
        #expect(description.shouldInferMappingModelAutomatically)
        container.persistentStoreDescriptions = [description]
        var loadError: Error?
        container.loadPersistentStores { _, error in loadError = error }
        #expect(loadError == nil)
        defer {
            for store in container.persistentStoreCoordinator.persistentStores {
                try? container.persistentStoreCoordinator.remove(store)
            }
        }

        let rows = try container.viewContext.fetch(NSFetchRequest<NSManagedObject>(entityName: "CDWatchProgress"))
        let row = try #require(rows.first)
        #expect(row.value(forKey: "tmdbId") as? Int32 == 603)
        #expect(row.value(forKey: "position") as? Double == 0.5)
        #expect(row.value(forKey: "updateTime") as? Int64 == 0)
        #expect(row.value(forKey: "cleared") as? Bool == false)
        #expect(row.value(forKey: "deviceID") == nil)
        let mediaRows = try container.viewContext.fetch(NSFetchRequest<NSManagedObject>(entityName: "CDUserMedia"))
        let mediaRow = try #require(mediaRows.first)
        #expect(mediaRow.value(forKey: "rating") as? Double == 8.0)
        #expect(mediaRow.value(forKey: "watchlist") as? Bool == false)
        #expect(mediaRow.value(forKey: "review") == nil)

        // Sync meets the old rows: stamped from their own time, by this device.
        let suite = "EdendaleSyncTest-\(UUID().uuidString)"
        defer { UserDefaults.standard.removePersistentDomain(forName: suite) }
        let stamper = SyncStamper(defaults: UserDefaults(suiteName: suite)!, secrets: InMemorySecretStore())
        let watchStore = WatchProgressStore(container: container, stamper: stamper)
        let userMedia = UserMediaStore(container: container, stamper: stamper)
        let records = watchStore.allRecords()
        #expect(records.map(\.updateTime) == [SyncLedger.milliseconds(watched)])
        #expect(records.first?.deviceID == stamper.deviceID)
        #expect(userMedia.allRecords().first?.updateTime == SyncLedger.milliseconds(watched))
    }

    @Test func theWatchlistFlagComesFromTheLocalWatchlist() throws {
        let suite = "EdendaleSyncTest-\(UUID().uuidString)"
        defer { UserDefaults.standard.removePersistentDomain(forName: suite) }
        let stamper = SyncStamper(defaults: UserDefaults(suiteName: suite)!, secrets: InMemorySecretStore())
        let store = UserMediaStore(container: SyncTestDevice.makeContainer(), stamper: stamper)
        let favourite = MediaRef(id: 603, mediaType: .movie)
        let changedElsewhere = MediaRef(id: 1399, mediaType: .tv)
        let removedElsewhere = MediaRef(id: 1396, mediaType: .tv)
        let onlyListed = MediaRef(id: 680, mediaType: .movie)
        let rated = Date(timeIntervalSince1970: 1_760_000_000)
        let added = Date(timeIntervalSince1970: 1_761_000_000)

        // A row from before 27.1 (never stamped), and rows other devices stamped.
        store.applySynced([
            UserMediaState(ref: favourite, isFavorite: true, updatedAt: rated),
            UserMediaState(ref: changedElsewhere, isInWatchlist: true, updateTime: 1_762_000_000_000,
                           deviceID: "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b"),
            UserMediaState(ref: removedElsewhere, rating: 7, updateTime: 1_762_000_000_001,
                           deviceID: "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b")
        ])

        let mirrors = store.reconcileWatchlist(
            listed: [favourite, onlyListed, removedElsewhere],
            dates: [favourite: added, onlyListed: added, removedElsewhere: added]
        )
        // Old rows take the flag; a listed title without a row gets one.
        #expect(store.state(for: favourite).isInWatchlist)
        #expect(store.state(for: favourite).isFavorite)
        #expect(store.state(for: favourite).updatedAt == added)
        #expect(store.state(for: favourite).updateTime == 0)
        #expect(store.state(for: onlyListed).isInWatchlist)
        // Stamped rows win: the watchlist follows them.
        let flags = Dictionary(mirrors.map { ($0.0, $0.1) }, uniquingKeysWith: { first, _ in first })
        #expect(flags == [changedElsewhere: true, removedElsewhere: false])
        #expect(!store.state(for: removedElsewhere).isInWatchlist)

        // The first sync writes the seeded rows from their own times.
        let records = Dictionary(store.allRecords().map { ($0.ref, $0) }, uniquingKeysWith: { first, _ in first })
        #expect(records[favourite]?.updateTime == SyncLedger.milliseconds(added))
        #expect(records[onlyListed]?.updateTime == SyncLedger.milliseconds(added))
        #expect(records[changedElsewhere]?.updateTime == 1_762_000_000_000)
        // Nothing changes the second time.
        #expect(store.reconcileWatchlist(listed: [favourite, onlyListed, changedElsewhere], dates: [:]).isEmpty)
    }
}
