//
//  WatchProgressStore.swift
//  Edendale
//
//  Persists watch state to iCloud via CoreData + CloudKit.
//

import Foundation
import CoreData

@MainActor
@Observable
final class WatchProgressStore {

    private let container: NSPersistentCloudKitContainer
    private var viewContext: NSManagedObjectContext { container.viewContext }

    /// In-memory cache; mutating it triggers SwiftUI re-renders via @Observable.
    private(set) var progressMap: [String: WatchProgress] = [:]

    init(container: NSPersistentCloudKitContainer = Persistence.cloudPersistentContainer) {
        self.container = container
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

    // MARK: - Write

    func update(_ progress: WatchProgress) {
        let k = cacheKey(progress.tmdbId, progress.mediaType)
        progressMap[k] = progress

        let request = CDWatchProgress.fetchRequest(
            tmdbId: progress.tmdbId,
            mediaType: progress.mediaType
        )

        do {
            let cd = try viewContext.fetch(request).first ?? CDWatchProgress(context: viewContext)
            cd.apply(progress)
            try viewContext.save()
        } catch {
            print("[WatchProgressStore] Failed to save: \(error)")
        }
    }

    func markCompleted(tmdbId: Int, mediaType: WatchMediaType) {
        var p = progressMap[cacheKey(tmdbId, mediaType)]
            ?? WatchProgress(tmdbId: tmdbId, mediaType: mediaType)
        p.position = 1.0
        p.isCompleted = true
        p.lastWatchedAt = Date()
        update(p)
    }

    func remove(tmdbId: Int, mediaType: WatchMediaType) {
        let k = cacheKey(tmdbId, mediaType)
        progressMap.removeValue(forKey: k)

        let request = CDWatchProgress.fetchRequest(tmdbId: tmdbId, mediaType: mediaType)
        do {
            if let cd = try viewContext.fetch(request).first {
                viewContext.delete(cd)
                try viewContext.save()
            }
        } catch {
            print("[WatchProgressStore] Failed to remove: \(error)")
        }
    }

    // MARK: - Private

    private func cacheKey(_ tmdbId: Int, _ mediaType: WatchMediaType) -> String {
        "wp_\(mediaType.rawValue)_\(tmdbId)"
    }

    /// Reload all records from CoreData into the in-memory cache.
    private func loadAll() {
        let request = NSFetchRequest<CDWatchProgress>(entityName: "CDWatchProgress")
        do {
            let results = try viewContext.fetch(request)
            var rebuilt: [String: WatchProgress] = [:]
            for cd in results {
                let dto = cd.toDTO()
                rebuilt[cacheKey(dto.tmdbId, dto.mediaType)] = dto
            }
            progressMap = rebuilt
        } catch {
            print("[WatchProgressStore] Failed to load: \(error)")
        }
    }
}
