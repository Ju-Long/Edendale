//
//  WatchProgressPlaybackSaveTests.swift
//  EdendaleTests
//
//  The player saves the position every ten seconds. Each save used to
//  refresh every page showing the library (directly, and again when Core
//  Data reported the save back as a change to both stores) and to start a
//  sync timed to land just before the next save, all on the main thread,
//  which cost the video a frame every ten seconds. Saves made while the
//  player is up now reach the store and Core Data at once, but the pages
//  only when it closes, and sync when playback stops.
//

import CoreData
import Foundation
import Observation
import Testing
@testable import Edendale

@MainActor
@Suite(.serialized)
struct WatchProgressPlaybackSaveTests {

    @Test func savesWhilePlayingReachTheStoreButThePagesOnlyWhenRefreshed() {
        let device = SyncTestDevice(storage: MemorySyncStorage())
        let store = device.watchStore
        let movie = Int.random(in: 1_000_000...9_000_000)

        let pages = Self.track { _ = store.inProgress }
        store.update(Self.progress(movie, 0.3), whilePlaying: true)
        #expect(!pages.changed)
        // The store has it at once, for anything reading it.
        #expect(store.progress(for: movie, mediaType: .movie)?.position == 0.3)
        #expect(store.inProgress.map(\.tmdbId) == [movie])

        store.refreshHeldChanges()
        #expect(pages.changed)
    }

    @Test func ordinarySavesRefreshThePagesAtOnce() {
        let device = SyncTestDevice(storage: MemorySyncStorage())
        let store = device.watchStore
        let movie = Int.random(in: 1_000_000...9_000_000)

        let pages = Self.track { _ = store.progress(for: movie, mediaType: .movie) }
        store.update(Self.progress(movie, 0.3))
        #expect(pages.changed)

        // Nothing held: refreshing changes nothing.
        let after = Self.track { _ = store.inProgress }
        store.refreshHeldChanges()
        #expect(!after.changed)
    }

    @Test func aSaveReportedBackByCoreDataLeavesThePagesAlone() async throws {
        let device = SyncTestDevice(storage: MemorySyncStorage())
        let store = device.watchStore
        let movie = Int.random(in: 1_000_000...9_000_000)
        let ref = MediaRef(id: movie, mediaType: .movie)
        device.userMediaStore.setFavorite(true, for: ref)
        store.update(Self.progress(movie, 0.3), whilePlaying: true)

        // Core Data posts a "remote" change for the app's own saves too
        // (the test store has that option off, so post it by hand).
        let progressPages = Self.track { _ = store.inProgress }
        let favoritePages = Self.track { _ = device.userMediaStore.isFavorite(ref) }
        NotificationCenter.default.post(name: .NSPersistentStoreRemoteChange, object: device.container.persistentStoreCoordinator)
        try await Task.sleep(for: .milliseconds(300))
        #expect(!progressPages.changed)
        #expect(!favoritePages.changed)

        // A row that really changed elsewhere (CloudKit) still refreshes them.
        let other = movie + 1
        let background = device.container.newBackgroundContext()
        try background.performAndWait {
            let row = NSEntityDescription.insertNewObject(forEntityName: "CDWatchProgress", into: background) as! CDWatchProgress
            row.apply(Self.progress(other, 0.6))
            try background.save()
        }
        NotificationCenter.default.post(name: .NSPersistentStoreRemoteChange, object: device.container.persistentStoreCoordinator)
        for _ in 0..<100 where !progressPages.changed {
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(progressPages.changed)
        #expect(store.progress(for: other, mediaType: .movie)?.position == 0.6)
        #expect(store.progress(for: movie, mediaType: .movie)?.position == 0.3)
    }

    @Test func savesWhilePlayingTellSyncToWaitForPlaybackToStop() async throws {
        let device = SyncTestDevice(storage: MemorySyncStorage())
        let movie = Int.random(in: 1_000_000...9_000_000)

        let playing = Posted()
        let observer = NotificationCenter.default.addObserver(
            forName: WatchProgressStore.didChangeLocally, object: nil, queue: nil
        ) { note in
            playing.values.append(note.userInfo?[WatchProgressStore.whilePlayingKey] as? Bool == true)
        }
        defer { NotificationCenter.default.removeObserver(observer) }
        device.watchStore.update(Self.progress(movie, 0.3), whilePlaying: true)
        device.watchStore.update(Self.progress(movie, 0.4))
        #expect(playing.values == [true, false])
    }

    @Test func syncWritesSavesMadeWhilePlayingWhenPlaybackStops() async throws {
        let storage = MemorySyncStorage()
        let device = SyncTestDevice(storage: storage)
        let movie = Int.random(in: 1_000_000...9_000_000)
        #expect(await device.sync())
        let writes = await storage.writes

        // No sync is set up ten seconds out, which would land just before
        // the player's next save…
        device.watchStore.update(Self.progress(movie, 0.3), whilePlaying: true)
        device.coordinator.noteLocalChange(whilePlaying: true)
        #expect(!Self.hasPendingChangeSync(device.coordinator))

        // …the stop writes it (PlayerSession.end posts flushRequested).
        device.coordinator.flush()
        for _ in 0..<200 where await storage.writes == writes {
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(await storage.writes == writes + 1)

        // Any other change still syncs after the pause.
        device.coordinator.noteLocalChange()
        #expect(Self.hasPendingChangeSync(device.coordinator))
    }

    // MARK: - Helpers

    /// Raised when anything read in `read` changes.
    private final class Observed: @unchecked Sendable {
        var changed = false
    }

    /// What each local-change notification said (posted on the main thread).
    private final class Posted: @unchecked Sendable {
        var values: [Bool] = []
    }

    private static func track(_ read: () -> Void) -> Observed {
        let observed = Observed()
        withObservationTracking(read) { observed.changed = true }
        return observed
    }

    private static func progress(_ movie: Int, _ position: Double) -> WatchProgress {
        WatchProgress(tmdbId: movie, mediaType: .movie, position: position, watchedSeconds: 6000)
    }

    /// The coordinator's ten-second change sync is waiting to run.
    private static func hasPendingChangeSync(_ coordinator: SyncCoordinator) -> Bool {
        let task = Mirror(reflecting: coordinator).children.first { $0.label == "debounceTask" }?.value
        return (task as? Task<Void, Never>) != nil
    }
}
