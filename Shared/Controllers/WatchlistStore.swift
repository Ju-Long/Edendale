//
//  WatchlistStore.swift
//  Edendale
//
//  SwiftData is the source of truth for Watchlist. Local mutations are saved
//  first and mirrored to TMDB when an account is connected. Pending actions
//  are durable so a failed or interrupted request is retried on the next sync.
//

import CoreData
import Foundation
import SwiftData

protocol WatchlistRemoteClient: Sendable {
    func watchlistItems(_ type: TMDBMediaType) async throws -> [TMDBMediaItem]
    func setWatchlist(_ inWatchlist: Bool, for ref: MediaRef) async throws
}

extension TMDBAccountClient: WatchlistRemoteClient {}

@MainActor
@Observable
final class WatchlistStore {
    private let modelContext: ModelContext
    private let account: any WatchlistRemoteClient
    private let isAuthenticated: () -> Bool

    /// A compact observable index used by detail-page toggle controls.
    private(set) var items: [WatchlistItem] = []
    private(set) var refs: Set<MediaRef> = []
    private(set) var isSyncing = false
    private(set) var lastSyncError: String?

    @ObservationIgnored private var isRunningRemoteWork = false
    @ObservationIgnored private var pendingPushRequested = false
    @ObservationIgnored private var fullSyncRequested = false
    @ObservationIgnored private var fullSyncInProgress = false

    convenience init(modelContext: ModelContext) {
        self.init(
            modelContext: modelContext,
            account: TMDBAccountClient(),
            isAuthenticated: { TMDBUserSession.current != nil },
            legacyContext: Persistence.cloudPersistentContainer.viewContext
        )
    }

    init(
        modelContext: ModelContext,
        account: any WatchlistRemoteClient,
        isAuthenticated: @escaping () -> Bool,
        legacyContext: NSManagedObjectContext?
    ) {
        self.modelContext = modelContext
        self.account = account
        self.isAuthenticated = isAuthenticated
        migrateLegacyWatchlist(from: legacyContext)
        reloadIndex()
    }

    // MARK: - Read

    func isInWatchlist(_ ref: MediaRef) -> Bool {
        refs.contains(ref)
    }

    // MARK: - Local-first writes

    func toggle(_ ref: MediaRef, metadata: WatchlistMetadata? = nil) {
        setWatchlist(!isInWatchlist(ref), for: ref, metadata: metadata)
    }

    func setWatchlist(
        _ inWatchlist: Bool,
        for ref: MediaRef,
        metadata: WatchlistMetadata? = nil
    ) {
        do {
            let existing = try item(for: ref)
            guard existing != nil || inWatchlist else { return }

            let item = existing ?? WatchlistItem(ref: ref, metadata: metadata)
            if existing == nil { modelContext.insert(item) }
            if let metadata { item.apply(metadata) }

            let stateChanged = item.isInWatchlist != inWatchlist
            item.isInWatchlist = inWatchlist
            item.updatedAt = Date()
            if stateChanged || existing == nil {
                item.pendingAction = inWatchlist ? .add : .remove
                if inWatchlist { item.dateAdded = Date() }
            }

            try modelContext.save()
            reloadIndex()
            if stateChanged || existing == nil { requestPendingPush() }
        } catch {
            lastSyncError = error.localizedDescription
        }
    }

    func remove(_ item: WatchlistItem) {
        setWatchlist(false, for: item.ref, metadata: WatchlistMetadata(item.snapshot))
    }

    /// Keeps a locally saved card fresh when its full detail page is loaded.
    func updateMetadata(_ detail: MediaDetail) {
        guard isInWatchlist(detail.ref) else { return }
        do {
            guard let item = try item(for: detail.ref) else { return }
            item.apply(WatchlistMetadata(detail))
            try modelContext.save()
            reloadIndex()
        } catch {
            lastSyncError = error.localizedDescription
        }
    }

    // MARK: - TMDB sync

    /// Flushes durable local mutations, then makes confirmed local rows match
    /// the connected account's complete movie and TV watchlists.
    func syncFromTMDB() async {
        guard isAuthenticated() else { return }
        guard !fullSyncRequested, !fullSyncInProgress else { return }
        fullSyncRequested = true
        await runRemoteWorkIfNeeded()
    }

    private func requestPendingPush() {
        guard isAuthenticated() else { return }
        pendingPushRequested = true
        Task { [weak self] in
            await self?.runRemoteWorkIfNeeded()
        }
    }

    /// Serializes change pushes and lifecycle pulls. If another request
    /// arrives during an await, the loop picks it up before going idle.
    private func runRemoteWorkIfNeeded() async {
        guard !isRunningRemoteWork, isAuthenticated() else { return }
        isRunningRemoteWork = true
        isSyncing = true
        lastSyncError = nil
        defer {
            isRunningRemoteWork = false
            isSyncing = false
        }

        while isAuthenticated() {
            let shouldPull = fullSyncRequested
            let shouldPush = pendingPushRequested || shouldPull
            guard shouldPush else { break }

            fullSyncRequested = false
            if shouldPull { fullSyncInProgress = true }
            pendingPushRequested = false
            await pushPendingChanges()
            if shouldPull {
                await pullFromTMDB()
                fullSyncInProgress = false
            }
        }
    }

    /// Successful writes remain pending until a subsequent list pull confirms
    /// them. That prevents a briefly stale TMDB list from undoing a local tap.
    private func pushPendingChanges() async {
        let pending: [WatchlistItem]
        do {
            pending = try allItems()
                .filter { $0.pendingAction != .none }
                .sorted { $0.updatedAt < $1.updatedAt }
        } catch {
            lastSyncError = error.localizedDescription
            return
        }

        for item in pending {
            guard isAuthenticated() else { return }
            let action = item.pendingAction
            guard action != .none else { continue }
            do {
                try await account.setWatchlist(action == .add, for: item.ref)
            } catch {
                lastSyncError = error.localizedDescription
            }
        }
    }

    private func pullFromTMDB() async {
        do {
            async let movies = account.watchlistItems(.movie)
            async let shows = account.watchlistItems(.tv)
            let remote = try await movies + shows
            try reconcile(with: remote)
        } catch {
            lastSyncError = error.localizedDescription
        }
    }

    /// Remote state is authoritative only for rows with no unconfirmed local
    /// mutation. Pending adds/removes win until TMDB's list reflects them.
    private func reconcile(with remoteItems: [TMDBMediaItem]) throws {
        let localItems = try allItems()
        var localByKey = Dictionary(
            uniqueKeysWithValues: localItems.map { ($0.storageKey, $0) }
        )
        let remoteKeys = Set(remoteItems.map { WatchlistItem.key(for: $0.ref) })

        for remote in remoteItems {
            let key = WatchlistItem.key(for: remote.ref)
            let local: WatchlistItem
            if let existing = localByKey[key] {
                local = existing
            } else {
                local = WatchlistItem(
                    ref: remote.ref,
                    metadata: WatchlistMetadata(remote),
                    pendingAction: .none
                )
                modelContext.insert(local)
                localByKey[key] = local
            }

            local.apply(WatchlistMetadata(remote))
            switch local.pendingAction {
            case .remove:
                // A local removal has not appeared on TMDB yet.
                local.isInWatchlist = false
            case .add, .none:
                local.isInWatchlist = true
                local.pendingAction = .none
            }
        }

        for local in localItems where !remoteKeys.contains(local.storageKey) {
            switch local.pendingAction {
            case .add:
                // Keep the local addition until TMDB confirms it.
                local.isInWatchlist = true
            case .remove, .none:
                // A pending removal is now confirmed; a confirmed row absent
                // remotely was removed outside Edendale.
                modelContext.delete(local)
            }
        }

        try modelContext.save()
        reloadIndex()
    }

    // MARK: - Persistence helpers

    private func item(for ref: MediaRef) throws -> WatchlistItem? {
        let key = WatchlistItem.key(for: ref)
        var descriptor = FetchDescriptor<WatchlistItem>(
            predicate: #Predicate { $0.storageKey == key }
        )
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first
    }

    private func allItems() throws -> [WatchlistItem] {
        try modelContext.fetch(FetchDescriptor<WatchlistItem>())
    }

    private func reloadIndex() {
        do {
            items = try allItems().sorted { $0.dateAdded > $1.dateAdded }
            refs = Set(items.filter(\.isInWatchlist).map(\.ref))
        } catch {
            lastSyncError = error.localizedDescription
        }
    }

    /// Moves watchlist flags written by older Edendale builds out of the
    /// CoreData/CloudKit account-state row and into the new local SwiftData
    /// store. The legacy attribute remains in the model for store migration.
    private func migrateLegacyWatchlist(from legacyContext: NSManagedObjectContext?) {
        guard let legacyContext else { return }
        let request = NSFetchRequest<CDUserMedia>(entityName: "CDUserMedia")
        request.predicate = NSPredicate(format: "inWatchlist == YES")

        do {
            let legacyItems = try legacyContext.fetch(request)
            guard !legacyItems.isEmpty else { return }
            var existingKeys = Set(try allItems().map(\.storageKey))

            for legacy in legacyItems {
                let type = TMDBMediaType(rawValue: legacy.mediaType) ?? .movie
                let ref = MediaRef(id: Int(legacy.tmdbId), mediaType: type)
                if existingKeys.insert(WatchlistItem.key(for: ref)).inserted {
                    modelContext.insert(
                        WatchlistItem(
                            ref: ref,
                            pendingAction: .add,
                            dateAdded: legacy.updatedAt ?? Date()
                        )
                    )
                }
            }
            try modelContext.save()

            for legacy in legacyItems { legacy.inWatchlist = false }
            if legacyContext.hasChanges { try legacyContext.save() }
        } catch {
            lastSyncError = error.localizedDescription
        }
    }
}
