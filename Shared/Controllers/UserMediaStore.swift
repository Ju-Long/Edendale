//
//  UserMediaStore.swift
//  Edendale
//
//  Per-title account state — favourite and the user's own rating.
//  Local-first: every change lands in the iCloud CoreData store immediately
//  (same container as WatchProgressStore), then is pushed to the connected
//  TMDB account best-effort when one is signed in. Watchlist has its own
//  local SwiftData store and TMDB sync path (WatchlistStore).
//

import Foundation
import CoreData

@MainActor
@Observable
final class UserMediaStore {

    private let container: NSPersistentCloudKitContainer
    private var viewContext: NSManagedObjectContext { container.viewContext }
    private let account = TMDBAccountClient()

    /// In-memory cache; mutating it triggers SwiftUI re-renders via @Observable.
    private(set) var states: [String: UserMediaState] = [:]

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

    func state(for ref: MediaRef) -> UserMediaState {
        states[cacheKey(ref)] ?? UserMediaState(ref: ref)
    }

    func isFavorite(_ ref: MediaRef) -> Bool { state(for: ref).isFavorite }
    func rating(for ref: MediaRef) -> Double? { state(for: ref).rating }

    // MARK: - Write

    func toggleFavorite(_ ref: MediaRef) {
        setFavorite(!isFavorite(ref), for: ref)
    }

    func setFavorite(_ favorite: Bool, for ref: MediaRef) {
        var s = state(for: ref)
        s.isFavorite = favorite
        save(s)
        pushToTMDB { try await $0.setFavorite(favorite, for: ref) }
    }

    /// Sets (0.5...10) or clears (nil) the user's rating.
    func setRating(_ value: Double?, for ref: MediaRef) {
        var s = state(for: ref)
        s.rating = value
        save(s)
        pushToTMDB { try await $0.setRating(value, for: ref) }
    }

    // MARK: - TMDB sync

    /// Merges the connected account's favorites and ratings with local state, both ways
    /// and additively: anything on TMDB is adopted locally, anything local
    /// that TMDB lacks is pushed up. Removals only travel through explicit
    /// user actions, never through sync — safer when either side is stale.
    func syncFromTMDB() async {
        guard TMDBUserSession.current != nil else { return }
        do {
            async let favoriteMovies = account.favorites(.movie)
            async let favoriteShows = account.favorites(.tv)
            async let ratedMovies = account.rated(.movie)
            async let ratedShows = account.rated(.tv)

            let favorites = Set(try await favoriteMovies + favoriteShows)
            let ratings = Dictionary(
                (try await ratedMovies + ratedShows).map { ($0.ref, $0.rating) },
                uniquingKeysWith: { first, _ in first }
            )

            // TMDB → local.
            for ref in favorites where !isFavorite(ref) {
                var s = state(for: ref); s.isFavorite = true; save(s)
            }
            for (ref, value) in ratings where rating(for: ref) == nil {
                var s = state(for: ref); s.rating = value; save(s)
            }

            // Local → TMDB.
            for s in states.values {
                if s.isFavorite && !favorites.contains(s.ref) {
                    try await account.setFavorite(true, for: s.ref)
                }
                if let value = s.rating, ratings[s.ref] == nil {
                    try await account.setRating(value, for: s.ref)
                }
            }
        } catch {
            print("[UserMediaStore] TMDB sync failed: \(error)")
        }
    }

    /// Pulls the connected account's state for a single title and makes local
    /// state match it — used when a detail page opens so its favourite and
    /// rating controls reflect what TMDB currently holds,
    /// including changes made on another device or the web. Unlike the launch
    /// `syncFromTMDB()`, this title is authoritative: a value cleared on TMDB
    /// is cleared locally too, since the user is looking at exactly this title
    /// and expects it to be in sync.
    func refreshFromTMDB(_ ref: MediaRef) async {
        guard TMDBUserSession.current != nil else { return }
        do {
            let remote = try await account.accountState(for: ref)
            var s = state(for: ref)
            guard s.isFavorite != remote.isFavorite
                || s.rating != remote.rating else { return }
            s.isFavorite = remote.isFavorite
            s.rating = remote.rating
            save(s)
        } catch {
            print("[UserMediaStore] TMDB refresh failed for \(ref): \(error)")
        }
    }

    // MARK: - Private

    /// Local state is already saved; the TMDB mirror is best effort and a
    /// failure (offline, signed out mid-flight) never blocks or reverts it.
    private func pushToTMDB(_ operation: @escaping @Sendable (TMDBAccountClient) async throws -> Void) {
        guard TMDBUserSession.current != nil else { return }
        Task { [account] in
            do {
                try await operation(account)
            } catch {
                print("[UserMediaStore] TMDB push failed: \(error)")
            }
        }
    }

    private func save(_ state: UserMediaState) {
        var state = state
        state.updatedAt = Date()

        let key = cacheKey(state.ref)
        let request = CDUserMedia.fetchRequest(ref: state.ref)
        do {
            if state.isEmpty {
                states.removeValue(forKey: key)
                if let cd = try viewContext.fetch(request).first {
                    viewContext.delete(cd)
                    try viewContext.save()
                }
            } else {
                states[key] = state
                let cd = try viewContext.fetch(request).first ?? CDUserMedia(context: viewContext)
                cd.apply(state)
                try viewContext.save()
            }
        } catch {
            print("[UserMediaStore] Failed to save: \(error)")
        }
    }

    private func cacheKey(_ ref: MediaRef) -> String {
        "um_\(ref.mediaType.rawValue)_\(ref.id)"
    }

    /// Reload all records from CoreData into the in-memory cache.
    private func loadAll() {
        let request = NSFetchRequest<CDUserMedia>(entityName: "CDUserMedia")
        do {
            let results = try viewContext.fetch(request)
            var rebuilt: [String: UserMediaState] = [:]
            for cd in results {
                let dto = cd.toDTO()
                if !dto.isEmpty { rebuilt[cacheKey(dto.ref)] = dto }
            }
            states = rebuilt
        } catch {
            print("[UserMediaStore] Failed to load: \(error)")
        }
    }
}
