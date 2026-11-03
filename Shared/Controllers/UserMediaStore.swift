//
//  UserMediaStore.swift
//  Edendale
//
//  Per-title account state — favourite, the user's own rating, the
//  portable watchlist flag, and review text from other platforms.
//  Local-first: every change lands in the iCloud CoreData store immediately
//  (same container as WatchProgressStore), then favourites and ratings are
//  pushed to the connected TMDB account best-effort when one is signed in.
//  The watchlist's cards and its TMDB mirror live in WatchlistStore; the
//  flag here is what the 27.1 sync record carries (SYNC-PROTOCOL.md §6.2),
//  stamped with every other field so the whole record wins or loses at
//  once. A cleared row is kept as a tombstone, never deleted.
//

import Foundation
import CoreData

@MainActor
@Observable
final class UserMediaStore {

    private let container: NSPersistentCloudKitContainer
    private var viewContext: NSManagedObjectContext { container.viewContext }
    private let account = TMDBAccountClient()
    private let stamper: SyncStamper

    /// In-memory cache of rows with something set; mutating it triggers
    /// SwiftUI re-renders via @Observable.
    private(set) var states: [String: UserMediaState] = [:]
    /// Rows with nothing set, kept for the sync file.
    @ObservationIgnored private(set) var tombstones: [String: UserMediaState] = [:]

    /// Called when a record from another device (a sync file, or a
    /// CloudKit row) changes a title's watchlist flag, so WatchlistStore
    /// can mirror it. Wired by EdendaleApp.
    @ObservationIgnored var onWatchlistSynced: ((MediaRef, Bool) -> Void)?

    init(container: NSPersistentCloudKitContainer = Persistence.cloudPersistentContainer, stamper: SyncStamper = .shared) {
        self.container = container
        self.stamper = stamper
        loadAll(mirroringWatchlist: false)

        // Observe remote CloudKit changes and merge into cache.
        Task { @MainActor [weak self] in
            for await _ in NotificationCenter.default.notifications(
                named: .NSPersistentStoreRemoteChange,
                object: container.persistentStoreCoordinator
            ) {
                self?.loadAll(mirroringWatchlist: true)
            }
        }
    }

    // MARK: - Read

    func state(for ref: MediaRef) -> UserMediaState {
        states[cacheKey(ref)] ?? tombstones[cacheKey(ref)] ?? UserMediaState(ref: ref)
    }

    func isFavorite(_ ref: MediaRef) -> Bool { state(for: ref).isFavorite }
    func rating(for ref: MediaRef) -> Double? { state(for: ref).rating }

    /// Every row, tombstones included, for this device's sync file. Rows
    /// from before 27.1 are stamped once from their `updatedAt`, in one save.
    func allRecords() -> [UserMediaState] {
        var records: [UserMediaState] = []
        var legacy: [UserMediaState] = []
        for record in Array(states.values) + Array(tombstones.values) {
            if record.updateTime == 0 || record.deviceID == nil {
                var stamped = record
                stamped.updateTime = max(SyncLedger.milliseconds(record.updatedAt), SyncFileFormat.minimumStamp)
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

    /// Records a watchlist change made here (WatchlistStore calls this),
    /// stamping the whole record. TMDB's watchlist is WatchlistStore's.
    func setWatchlistFlag(_ inWatchlist: Bool, for ref: MediaRef) {
        var s = state(for: ref)
        guard s.isInWatchlist != inWatchlist else { return }
        s.isInWatchlist = inWatchlist
        save(s)
    }

    // MARK: - Watchlist at launch

    /// Brings the portable watchlist flag and the local watchlist into
    /// step (27.1). The flag lived only in WatchlistStore before, so rows
    /// never stamped take it from the local watchlist, and a title listed
    /// there with no row gets one, both stamped like other rows from
    /// before 27.1 at their first sync. Without this, the first sync would
    /// tell other devices these titles left the watchlist. A stamped row
    /// that disagrees changed on another device while nothing was
    /// listening, so the watchlist follows it: the returned flags.
    ///
    /// - Parameters:
    ///   - listed: titles the local watchlist shows.
    ///   - dates: when each listed title was last changed there.
    func reconcileWatchlist(listed: Set<MediaRef>, dates: [MediaRef: Date]) -> [(MediaRef, Bool)] {
        var seeded: [UserMediaState] = []
        var toMirror: [(MediaRef, Bool)] = []
        var seen: Set<MediaRef> = []
        for row in Array(states.values) + Array(tombstones.values) {
            seen.insert(row.ref)
            let isListed = listed.contains(row.ref)
            guard row.isInWatchlist != isListed else { continue }
            if row.updateTime == 0 || row.deviceID == nil {
                var updated = row
                updated.isInWatchlist = isListed
                if let date = dates[row.ref], date > updated.updatedAt { updated.updatedAt = date }
                seeded.append(updated)
            } else {
                toMirror.append((row.ref, row.isInWatchlist))
            }
        }
        for ref in listed where !seen.contains(ref) {
            seeded.append(UserMediaState(ref: ref, isInWatchlist: true, updatedAt: dates[ref] ?? Date()))
        }
        if !seeded.isEmpty { persist(seeded, local: false) }
        return toMirror
    }

    // MARK: - Sync

    /// Stores records another device wrote, verbatim, in one save, and
    /// tells the watchlist about each flag that changed.
    func applySynced(_ records: [UserMediaState]) {
        guard !records.isEmpty else { return }
        let changedFlags = records.compactMap { record -> (MediaRef, Bool)? in
            state(for: record.ref).isInWatchlist != record.isInWatchlist ? (record.ref, record.isInWatchlist) : nil
        }
        persist(records, local: false)
        stamper.observe(stamps: records.map(\.updateTime))
        for (ref, flag) in changedFlags {
            onWatchlistSynced?(ref, flag)
        }
    }

    func applySynced(_ state: UserMediaState) {
        applySynced([state])
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

    /// A local write: stamped, saved, and announced.
    private func save(_ state: UserMediaState) {
        var state = state
        state.updatedAt = Date()
        let stamp = stamper.stamp()
        state.updateTime = stamp.updateTime
        state.deviceID = stamp.device
        persist([state], local: true)
    }

    private func persist(_ records: [UserMediaState], local: Bool) {
        for state in records {
            let key = cacheKey(state.ref)
            if state.isEmpty {
                states.removeValue(forKey: key)
                tombstones[key] = state
            } else {
                tombstones.removeValue(forKey: key)
                states[key] = state
            }
            let request = CDUserMedia.fetchRequest(ref: state.ref)
            do {
                let cd = try viewContext.fetch(request).first
                    // By entity name: `CDUserMedia(context:)` asks the class for its
                    // entity, which is ambiguous once another model version is loaded.
                    ?? NSEntityDescription.insertNewObject(forEntityName: "CDUserMedia", into: viewContext) as! CDUserMedia
                cd.apply(state)
            } catch {
                print("[UserMediaStore] Failed to fetch for saving: \(error)")
            }
        }
        do {
            if viewContext.hasChanges { try viewContext.save() }
        } catch {
            print("[UserMediaStore] Failed to save: \(error)")
        }
        if local {
            NotificationCenter.default.post(name: WatchProgressStore.didChangeLocally, object: nil)
        }
    }

    private func cacheKey(_ ref: MediaRef) -> String {
        "um_\(ref.mediaType.rawValue)_\(ref.id)"
    }

    /// Reload all records from CoreData into the in-memory cache. With
    /// `mirroringWatchlist`, watchlist flags that changed (rows another
    /// Apple device wrote, arriving through CloudKit) reach WatchlistStore.
    private func loadAll(mirroringWatchlist: Bool) {
        let request = NSFetchRequest<CDUserMedia>(entityName: "CDUserMedia")
        request.shouldRefreshRefetchedObjects = true
        do {
            let results = try viewContext.fetch(request)
            var rebuilt: [String: UserMediaState] = [:]
            var cleared: [String: UserMediaState] = [:]
            var stamps: [Int64] = []
            var flagged: [(MediaRef, Bool)] = []
            for cd in results {
                let dto = cd.toDTO()
                let key = cacheKey(dto.ref)
                if dto.isEmpty {
                    cleared[key] = dto
                } else {
                    rebuilt[key] = dto
                }
                stamps.append(dto.updateTime)
                let before = (states[key] ?? tombstones[key])?.isInWatchlist ?? false
                if mirroringWatchlist, dto.isInWatchlist != before {
                    flagged.append((dto.ref, dto.isInWatchlist))
                }
            }
            states = rebuilt
            tombstones = cleared
            stamper.observe(stamps: stamps)
            for (ref, flag) in flagged {
                onWatchlistSynced?(ref, flag)
            }
        } catch {
            print("[UserMediaStore] Failed to load: \(error)")
        }
    }
}
