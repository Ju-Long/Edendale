//
//  Persistence.swift
//  Edendale
//

import Foundation
import SwiftData
import CoreData

struct Persistence {

    // MARK: - SwiftData (local-only library data)

    static var sharedModelContainer: ModelContainer = {
        let schema = Schema([
            VideoFolder.self,
            Movie.self,
            TVShow.self,
            Episode.self
        ])
        // Library data is local-only: it references device-specific file paths and
        // security-scoped bookmarks. Disable CloudKit mirroring explicitly — otherwise
        // SwiftData defaults to `.automatic` and, because the app carries a CloudKit
        // entitlement (used by the WatchProgress store below), it would try to mirror
        // this store too and fail CloudKit's "all attributes/relationships optional" rule.
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false, cloudKitDatabase: .none)

        do {
            return try ModelContainer(for: schema, configurations: [config])
        } catch {
            fatalError("Could not create ModelContainer: \(error)")
        }
    }()

    // MARK: - CoreData + CloudKit (iCloud-synced watch progress)

    static var cloudPersistentContainer: NSPersistentCloudKitContainer = {
        let container = NSPersistentCloudKitContainer(name: "WatchProgress")

        guard let description = container.persistentStoreDescriptions.first else {
            fatalError("Missing persistent store description for WatchProgress")
        }

        // Point at the correct iCloud container
        description.cloudKitContainerOptions = NSPersistentCloudKitContainerOptions(
            containerIdentifier: AppIdentifiers.iCloudContainer
        )

        // Enable remote change notifications so we can merge iCloud pushes
        description.setOption(
            true as NSNumber,
            forKey: NSPersistentStoreRemoteChangeNotificationPostOptionKey
        )
        description.setOption(
            true as NSNumber,
            forKey: NSPersistentHistoryTrackingKey
        )

        container.loadPersistentStores { _, error in
            if let error {
                fatalError("Failed to load WatchProgress CoreData store: \(error)")
            }
        }

        container.viewContext.automaticallyMergesChangesFromParent = true
        container.viewContext.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy

        return container
    }()
}
