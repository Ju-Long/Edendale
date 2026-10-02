//
//  Persistence.swift
//  Edendale
//

import Foundation
import SwiftData
import CoreData

struct Persistence {

    /// Hosted unit tests must not open the user's stores or initialize
    /// entitlement-dependent CloudKit services. UI tests launch a separate
    /// application process without XCTest loaded and retain normal storage.
    static let isRunningUnitTests: Bool = {
        #if DEBUG
        let environment = ProcessInfo.processInfo.environment
        return environment["XCTestConfigurationFilePath"] != nil
            || environment["XCTestBundlePath"] != nil
            || NSClassFromString("XCTestCase") != nil
        #else
        return false
        #endif
    }()

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
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: isRunningUnitTests, cloudKitDatabase: .none)

        do {
            return try ModelContainer(for: schema, configurations: [config])
        } catch {
            fatalError("Could not create ModelContainer: \(error)")
        }
    }()

    // MARK: - SwiftData (local TMDB watchlist mirror)

    /// Portable account state stays independent from device-specific library
    /// paths and access grants, even though both use SwiftData locally.
    static var watchlistModelContainer: ModelContainer = {
        let schema = Schema([WatchlistItem.self])
        let config = ModelConfiguration(
            "Watchlist",
            schema: schema,
            isStoredInMemoryOnly: isRunningUnitTests,
            cloudKitDatabase: .none
        )

        do {
            return try ModelContainer(for: schema, configurations: [config])
        } catch {
            fatalError("Could not create Watchlist ModelContainer: \(error)")
        }
    }()

    // MARK: - SwiftData (downloaded subtitles kept on this device)

    /// Which downloaded subtitle files belong to which videos. Device-local
    /// like the library; the files themselves live in Caches (see
    /// `SubtitleCacheStore`). A store that can't open falls back to memory:
    /// losing the cache only means downloading a subtitle again.
    static var subtitleCacheModelContainer: ModelContainer = {
        let schema = Schema([CachedSubtitle.self])
        let config = ModelConfiguration(
            "SubtitleCache",
            schema: schema,
            isStoredInMemoryOnly: isRunningUnitTests,
            cloudKitDatabase: .none
        )
        let fallback = ModelConfiguration(
            "SubtitleCache",
            schema: schema,
            isStoredInMemoryOnly: true,
            cloudKitDatabase: .none
        )

        do {
            return try ModelContainer(for: schema, configurations: [config])
        } catch {
            print("[Persistence] Subtitle cache store unavailable, keeping it in memory: \(error)")
        }
        do {
            return try ModelContainer(for: schema, configurations: [fallback])
        } catch {
            fatalError("Could not create SubtitleCache ModelContainer: \(error)")
        }
    }()

    // MARK: - CoreData + CloudKit (iCloud-synced watch progress)

    static var cloudPersistentContainer: NSPersistentCloudKitContainer = {
        let container = NSPersistentCloudKitContainer(name: "WatchProgress")

        guard let description = container.persistentStoreDescriptions.first else {
            fatalError("Missing persistent store description for WatchProgress")
        }

        if isRunningUnitTests {
            description.type = NSInMemoryStoreType
            description.url = nil
            description.cloudKitContainerOptions = nil
        } else {
            description.cloudKitContainerOptions = NSPersistentCloudKitContainerOptions(
                containerIdentifier: AppIdentifiers.iCloudContainer
            )
        }

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
