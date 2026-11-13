//
//  SyncTestSupport.swift
//  EdendaleTests
//
//  Two or more "devices" in one process: each gets its own in-memory
//  CoreData store (the app's model, loaded once), its own defaults suite
//  and secret store for the stamper, and a coordinator over a shared
//  in-memory sync location, so a full sync between them runs without a
//  network or a real folder.
//

import CoreData
import Foundation
@testable import Edendale

/// A thread-safe counter for stub handlers (RemoteByteSourceTests has its own Counter).
final class StubCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var value = 0
    /// Increments and returns the new value.
    func next() -> Int { lock.withLock { value += 1; return value } }
}

/// A sync location held in memory, shared by every device in a test.
actor MemorySyncStorage: SyncStorage {
    nonisolated let providerName = "memory"
    private var files: [String: (data: Data, revision: Int)] = [:]
    private var revision = 0
    private(set) var writes = 0
    private(set) var reads = 0
    /// Set to make every operation fail.
    var failure: Error?

    func listSpace() async throws -> [SyncFileEntry] {
        if let failure { throw failure }
        return files.map { SyncFileEntry(name: $0.key, stamp: "r\($0.value.revision)", size: Int64($0.value.data.count)) }
            .sorted { $0.name < $1.name }
    }

    func read(_ name: String) async throws -> Data {
        if let failure { throw failure }
        reads += 1
        guard let file = files[name] else { throw SyncStorageError.notFound(name) }
        return file.data
    }

    func write(_ name: String, data: Data) async throws {
        if let failure { throw failure }
        writes += 1
        revision += 1
        files[name] = (data, revision)
    }

    func delete(_ name: String) async throws {
        if let failure { throw failure }
        files[name] = nil
    }

    func contents(_ name: String) -> Data? { files[name]?.data }
    var names: [String] { files.keys.sorted() }

    /// Plants a file another platform wrote.
    func plant(_ name: String, _ data: Data) {
        revision += 1
        files[name] = (data, revision)
    }

    func fail(with error: Error?) {
        failure = error
    }
}

/// Holds every listing until `release()`, counting them.
actor GatedSyncStorage: SyncStorage {
    nonisolated let providerName = "gated"
    private let inner = MemorySyncStorage()
    private var waiting: [CheckedContinuation<Void, Never>] = []
    private var open = false
    private var nextFailure: Error?
    private(set) var listings = 0

    func listSpace() async throws -> [SyncFileEntry] {
        listings += 1
        if !open {
            await withCheckedContinuation { waiting.append($0) }
        }
        if let failure = nextFailure {
            nextFailure = nil
            throw failure
        }
        return try await inner.listSpace()
    }

    func release() {
        open = true
        waiting.forEach { $0.resume() }
        waiting = []
    }

    /// Makes the next listing fail once it is released.
    func failNextListing(with error: Error) {
        nextFailure = error
    }

    func read(_ name: String) async throws -> Data { try await inner.read(name) }
    func write(_ name: String, data: Data) async throws { try await inner.write(name, data: data) }
    func delete(_ name: String) async throws { try await inner.delete(name) }
}

@MainActor
final class SyncTestDevice {
    let container: NSPersistentCloudKitContainer
    let defaults: UserDefaults
    let suiteName: String
    let stamper: SyncStamper
    let watchStore: WatchProgressStore
    let userMediaStore: UserMediaStore
    let coordinator: SyncCoordinator

    init(
        storage: MemorySyncStorage,
        space: String = "Family",
        library: [SyncRecordKey: Int] = [:],
        seasons: (@MainActor (Int, Int) async -> [Int: Int]?)? = nil,
        now: @escaping () -> Date = { Date() }
    ) {
        suiteName = "EdendaleSyncTest-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        container = Self.makeContainer()
        stamper = SyncStamper(defaults: defaults, secrets: InMemorySecretStore())
        watchStore = WatchProgressStore(container: container, stamper: stamper)
        userMediaStore = UserMediaStore(container: container, stamper: stamper)
        coordinator = SyncCoordinator(
            watchStore: watchStore,
            userMediaStore: userMediaStore,
            stamper: stamper,
            defaults: defaults,
            libraryEpisodeIndex: { library },
            seasonEpisodeIDs: seasons ?? { _, _ in nil },
            storageFactory: { _ in storage },
            now: now
        )
        coordinator.setLocation(SyncLocation(provider: .folder, space: space, folderPath: "/memory"), startSync: false)
    }

    /// The device's ID as the file names it.
    var fileName: String { SyncFileFormat.fileName(deviceID: stamper.deviceID) }

    @discardableResult
    func sync() async -> Bool {
        await coordinator.runSync(reason: "test")
    }

    deinit {
        UserDefaults.standard.removePersistentDomain(forName: suiteName)
    }

    /// An in-memory store of the app's model, sharing the model object the
    /// app loaded so entity descriptions stay unique.
    static func makeContainer() -> NSPersistentCloudKitContainer {
        let model = Persistence.cloudPersistentContainer.managedObjectModel
        let container = NSPersistentCloudKitContainer(name: "WatchProgress", managedObjectModel: model)
        let description = NSPersistentStoreDescription()
        description.type = NSInMemoryStoreType
        description.cloudKitContainerOptions = nil
        container.persistentStoreDescriptions = [description]
        container.loadPersistentStores { _, error in
            precondition(error == nil, "in-memory store: \(String(describing: error))")
        }
        container.viewContext.automaticallyMergesChangesFromParent = true
        container.viewContext.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy
        return container
    }
}
