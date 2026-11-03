//
//  SyncCoordinator.swift
//  Edendale
//
//  The client procedure of SYNC-PROTOCOL.md §8 on this device: holds the
//  one sync location, runs a sync on launch, on return to the foreground,
//  ten seconds after the last local change (at once when playback stops or
//  the app goes to the background), and every five minutes while active;
//  backs off after failures; and keeps the per-file stamps so a file is
//  read only when it changed. One sync lists the space, reads and merges
//  the other devices' files into the local stores through a SyncLedger,
//  and rewrites this device's file when anything changed.
//
//  Records keyed by show, season, and episode name an episode the local
//  store keys by its own TMDB ID, so an episode from another device is
//  resolved through the library, then TMDB; one that cannot be resolved
//  yet waits in `pending` and is still written out verbatim.
//

import CryptoKit
import Foundation
import SwiftData

@MainActor
@Observable
final class SyncCoordinator {

    /// Posted to write pending changes at once: playback stopped, or the
    /// app is leaving the foreground.
    nonisolated static let flushRequested = Notification.Name("Edendale.Sync.flushRequested")

    static let locationKey = "sync.location"
    static let stateKey = "sync.state"
    static let debounce: Duration = .seconds(10)
    static let periodicInterval: Duration = .seconds(300)
    /// §8.2: 1, 2, 4, 8 … minutes, capped at an hour.
    static let backoffBase: TimeInterval = 60
    static let backoffCap: TimeInterval = 3600

    struct Status: Equatable, Sendable {
        var isSyncing = false
        var lastSyncedAt: Date?
        var lastError: String?
        /// One per device whose file was skipped or flagged, by name.
        var warnings: [String] = []
        /// The other devices seen in the space, by name.
        var devices: [String] = []
    }

    /// What survives relaunches besides the location: per-file stamps,
    /// device names, and records waiting for an episode ID.
    nonisolated struct State: Codable, Equatable, Sendable {
        var fileStamps: [String: String] = [:]
        var deviceNames: [String: String] = [:]
        var pendingProgress: [SyncProgressRecord] = []
        var lastSyncedAt: Date?
        var lastWrittenAt: Date?
        /// SHA-256 of the file last written, so an unchanged store is not
        /// rewritten.
        var lastWrittenDigest: String?
    }

    private(set) var location: SyncLocation?
    private(set) var status = Status()
    /// A location replaced or removed, whose file the user may delete.
    private(set) var previousLocation: SyncLocation?

    @ObservationIgnored private var state = State()
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let stamper: SyncStamper
    @ObservationIgnored private let watchStore: WatchProgressStore
    @ObservationIgnored private let userMediaStore: UserMediaStore
    @ObservationIgnored private let environment: ConnectorFactory.Environment
    @ObservationIgnored private let episodeResolver: @MainActor (Int, Int, Int) async -> Int?
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private let storageFactory: (SyncLocation) async throws -> any SyncStorage

    @ObservationIgnored private var dirty = true
    @ObservationIgnored private var syncAgain = false
    @ObservationIgnored private var failures = 0
    @ObservationIgnored private var nextAttemptAt: Date?
    @ObservationIgnored private var debounceTask: Task<Void, Never>?
    @ObservationIgnored private var periodicTask: Task<Void, Never>?
    @ObservationIgnored private var retryTask: Task<Void, Never>?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    @ObservationIgnored private var currentStorage: (any SyncStorage)?

    init(
        watchStore: WatchProgressStore,
        userMediaStore: UserMediaStore,
        stamper: SyncStamper = .shared,
        defaults: UserDefaults = AppIdentifiers.defaults,
        environment: ConnectorFactory.Environment = .live,
        episodeResolver: (@MainActor (Int, Int, Int) async -> Int?)? = nil,
        storageFactory: ((SyncLocation) async throws -> any SyncStorage)? = nil,
        now: @escaping () -> Date = { Date() }
    ) {
        self.watchStore = watchStore
        self.userMediaStore = userMediaStore
        self.stamper = stamper
        self.defaults = defaults
        self.environment = environment
        self.episodeResolver = episodeResolver ?? Self.libraryThenTMDBEpisodeResolver
        self.storageFactory = storageFactory ?? { try await SyncStorageFactory.make(for: $0, environment: environment) }
        self.now = now
        if let data = defaults.data(forKey: Self.locationKey) {
            location = try? JSONDecoder().decode(SyncLocation.self, from: data)
        }
        if let data = defaults.data(forKey: Self.stateKey) {
            state = (try? JSONDecoder().decode(State.self, from: data)) ?? State()
        }
        status.lastSyncedAt = state.lastSyncedAt
        status.devices = state.deviceNames.values.sorted()
    }

    var isOn: Bool { location != nil }
    var deviceID: String { stamper.deviceID }

    // MARK: - Lifecycle

    /// Observes local changes and runs the launch sync.
    func start() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: WatchProgressStore.didChangeLocally, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.noteLocalChange() }
        })
        observers.append(center.addObserver(forName: Self.flushRequested, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.flush() }
        })
        sceneDidBecomeActive()
    }

    /// Launch and return to the foreground: read, then write if needed.
    func sceneDidBecomeActive() {
        startPeriodicSync()
        requestSync(reason: "foreground")
    }

    /// Leaving the foreground: pending changes go out at once, and iOS
    /// is asked for a background refresh later.
    func sceneDidEnterBackground() {
        periodicTask?.cancel()
        periodicTask = nil
        #if os(iOS)
        SyncBackgroundRefresh.schedule()
        if location != nil, dirty {
            debounceTask?.cancel()
            debounceTask = nil
            SyncBackgroundRefresh.keepAlive { [weak self] in
                _ = await self?.runSync(reason: "background-flush")
            }
            return
        }
        #endif
        flush()
    }

    /// A local change: this device's file is written after a pause, so
    /// progress during playback coalesces into the latest position.
    func noteLocalChange() {
        dirty = true
        guard location != nil else { return }
        debounceTask?.cancel()
        debounceTask = Task { [weak self] in
            try? await Task.sleep(for: Self.debounce)
            guard !Task.isCancelled else { return }
            self?.requestSync(reason: "change")
        }
    }

    /// Writes now if there is anything to write.
    func flush() {
        guard location != nil else { return }
        debounceTask?.cancel()
        debounceTask = nil
        if dirty { requestSync(reason: "flush") }
    }

    /// The user asked: runs regardless of backoff.
    func syncNow() {
        nextAttemptAt = nil
        requestSync(reason: "manual")
    }

    private func startPeriodicSync() {
        periodicTask?.cancel()
        periodicTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: Self.periodicInterval)
                guard !Task.isCancelled else { return }
                self?.requestSync(reason: "periodic")
            }
        }
    }

    private func requestSync(reason: String) {
        guard location != nil else { return }
        if let nextAttemptAt, now() < nextAttemptAt, reason != "manual" {
            return
        }
        if status.isSyncing {
            syncAgain = true
            return
        }
        Task { await self.runSync(reason: reason) }
    }

    // MARK: - Location

    /// Chooses a location (or none). The local store is untouched; the
    /// old location's file stays until the user deletes it.
    func setLocation(_ newLocation: SyncLocation?, startSync: Bool = true) {
        if let location, newLocation.map({ !$0.sameStorage(as: location) || $0.space != location.space }) ?? true {
            previousLocation = location
        }
        location = newLocation
        state = State()
        dirty = true
        failures = 0
        nextAttemptAt = nil
        status = Status()
        currentStorage = nil
        persistLocation()
        persistState()
        if newLocation != nil {
            SyncLog.info("sync location set: \(newLocation?.provider.rawValue ?? "none")")
            if startSync { requestSync(reason: "manual") }
        } else {
            SyncLog.info("sync location removed")
        }
    }

    /// Deletes this device's file from a location that was replaced or
    /// removed (§4), then forgets it.
    func deleteFileFromPreviousLocation() async throws {
        guard let previous = previousLocation else { return }
        let storage = try await storageFactory(previous)
        try await storage.delete(SyncFileFormat.fileName(deviceID: deviceID))
        previousLocation = nil
    }

    func forgetPreviousLocation() {
        previousLocation = nil
    }

    /// Deletes another device's file from the space (§8.3).
    func removeDevice(fileName: String) async throws {
        guard let location else { return }
        let storage = try await storageFactory(location)
        try await storage.delete(fileName)
        state.fileStamps[fileName] = nil
        if let id = SyncFileFormat.deviceID(ofFileName: fileName) {
            state.deviceNames[id] = nil
        }
        status.devices = state.deviceNames.values.sorted()
        persistState()
    }

    /// The other devices' files, by name, for "Remove device".
    var knownDevices: [(fileName: String, name: String)] {
        state.deviceNames.map { (SyncFileFormat.fileName(deviceID: $0.key), $0.value) }.sorted { $0.name < $1.name }
    }

    private func persistLocation() {
        if let location, let data = try? JSONEncoder().encode(location) {
            defaults.set(data, forKey: Self.locationKey)
        } else {
            defaults.removeObject(forKey: Self.locationKey)
        }
    }

    private func persistState() {
        if let data = try? JSONEncoder().encode(state) {
            defaults.set(data, forKey: Self.stateKey)
        }
    }

    // MARK: - One sync (§8.2)

    @discardableResult
    func runSync(reason: String) async -> Bool {
        guard let location, !status.isSyncing else { return false }
        status.isSyncing = true
        defer { status.isSyncing = false }
        syncAgain = false
        let began = now()
        SyncLog.info("sync begin reason=\(reason) provider=\(location.provider.rawValue)")

        do {
            let storage = try await storageFactory(location)
            currentStorage = storage
            let myFile = SyncFileFormat.fileName(deviceID: deviceID)
            let nowMilliseconds = SyncLedger.milliseconds(began)

            // 1. List.
            let entries = try await storage.listSpace()
            var ledger = buildLedger(now: nowMilliseconds)
            var warnings: [String] = []
            var applied = 0
            var readFiles = 0

            // 2. Read and merge what changed.
            for entry in entries where entry.name != myFile {
                guard let fileDeviceID = entry.deviceID else { continue }
                if state.fileStamps[entry.name] == entry.stamp { continue }
                let deviceLabel = state.deviceNames[fileDeviceID] ?? String(localized: "another device")
                if let size = entry.size, size > SyncFileFormat.maxFileBytes {
                    warnings.append(String(localized: "The file from \(deviceLabel) is too large to read."))
                    SyncLog.warning("skipped \(entry.name): too large (\(size) bytes)")
                    state.fileStamps[entry.name] = entry.stamp
                    continue
                }
                let data: Data
                do {
                    data = try await storage.read(entry.name)
                } catch {
                    SyncLog.warning("read \(entry.name) failed: \(SyncLog.describe(error))")
                    continue
                }
                readFiles += 1
                let file: SyncDeviceFile
                do {
                    file = try SyncFileFormat.parse(data, fileName: entry.name, space: location.space)
                } catch let rejection as SyncFileFormat.Rejection {
                    warnings.append(Self.message(for: rejection, device: deviceLabel))
                    SyncLog.warning("skipped \(entry.name): \(SyncLog.describe(rejection))")
                    state.fileStamps[entry.name] = entry.stamp
                    continue
                } catch {
                    continue
                }
                let outcome = ledger.merge(file, now: nowMilliseconds)
                state.deviceNames[file.device.id] = file.device.name
                if outcome.farFutureSkipped > 0 {
                    warnings.append(String(localized: "\(file.device.name) has records dated more than a day ahead; they were ignored. Check that device's clock."))
                    SyncLog.warning("\(entry.name): \(outcome.farFutureSkipped) records past the far-future guard")
                }
                applied += outcome.applied
                await apply(outcome, from: ledger)
                state.fileStamps[entry.name] = entry.stamp
                SyncLog.info("merged \(entry.name): read=\(outcome.read) applied=\(outcome.applied) stamp=\(entry.stamp)")
            }
            // Files that disappeared are forgotten; an empty listing clears nothing (§8.2).
            let present = Set(entries.map(\.name))
            for name in state.fileStamps.keys where !present.contains(name) {
                state.fileStamps[name] = nil
            }
            await resolvePending(ledger: &ledger)

            // 3. Write this device's file when it would differ from the last
            // one written: by the user's changes, or by what step 2 applied.
            let records = ledger.records(now: nowMilliseconds)
            let file = SyncDeviceFile(
                space: location.space,
                device: SyncDeviceInfo(
                    id: deviceID, name: SyncStamper.deviceName, platform: SyncStamper.platformName, app: SyncStamper.appVersion
                ),
                watchProgress: records.progress,
                userMedia: records.userMedia
            )
            let data = try SyncFileFormat.encode(file)
            let digest = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            if digest != state.lastWrittenDigest || !present.contains(myFile) {
                try await storage.write(myFile, data: data)
                dirty = false
                state.lastWrittenAt = began
                state.lastWrittenDigest = digest
                SyncLog.info("wrote \(myFile): progress=\(records.progress.count) userMedia=\(records.userMedia.count) bytes=\(data.count)")
            } else {
                dirty = false
            }
            stamper.observe(ledger.highWater)

            // 4. Record the sync.
            if let drive = storage as? GoogleDriveSyncStorage, let folderID = await drive.resolvedFolderID,
               self.location?.googleFolderID != folderID {
                self.location?.googleFolderID = folderID
                persistLocation()
            }
            state.lastSyncedAt = began
            persistState()
            status.lastSyncedAt = began
            status.lastError = nil
            status.warnings = warnings
            status.devices = state.deviceNames.values.sorted()
            failures = 0
            nextAttemptAt = nil
            retryTask?.cancel()
            SyncLog.info("sync end files=\(entries.count) read=\(readFiles) applied=\(applied) warnings=\(warnings.count)")
            if syncAgain {
                syncAgain = false
                requestSync(reason: "queued")
            }
            return true
        } catch {
            failures += 1
            let delay = min(Self.backoffBase * pow(2, Double(failures - 1)), Self.backoffCap)
            nextAttemptAt = now().addingTimeInterval(delay)
            status.lastError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            SyncLog.warning("sync failed: \(SyncLog.describe(error)); retry in \(Int(delay))s")
            scheduleRetry(after: delay)
            return false
        }
    }

    private func scheduleRetry(after delay: TimeInterval) {
        retryTask?.cancel()
        retryTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled else { return }
            self?.nextAttemptAt = nil
            self?.requestSync(reason: "retry")
        }
    }

    private static func message(for rejection: SyncFileFormat.Rejection, device: String) -> String {
        switch rejection {
        case .newerFormat:
            String(localized: "Update Edendale to sync with \(device).")
        case .tooLarge, .tooManyRecords:
            String(localized: "The file from \(device) is too large to read.")
        default:
            String(localized: "The file from \(device) couldn't be read and was skipped.")
        }
    }

    // MARK: - Local stores ⇄ ledger

    /// Every local record as the ledger sees it, with records still
    /// waiting for an episode ID.
    func buildLedger(now: Int64) -> SyncLedger {
        var ledger = SyncLedger(highWater: stamper.highWater)
        for progress in watchStore.allRecords() {
            guard let record = Self.record(for: progress) else { continue }
            ledger.progress[record.key] = record
            ledger.observe(record.updateTime)
        }
        for state in userMediaStore.allRecords() {
            let record = Self.record(for: state)
            ledger.userMedia[record.key] = record
            ledger.observe(record.updateTime)
        }
        for record in state.pendingProgress where record.stamp.wins(over: ledger.progress[record.key]?.stamp) {
            ledger.progress[record.key] = record
        }
        return ledger
    }

    /// Applies the records a merge changed to the local stores.
    private func apply(_ outcome: SyncLedger.Outcome, from ledger: SyncLedger) async {
        for key in outcome.changedUserMedia {
            guard let record = ledger.userMedia[key] else { continue }
            userMediaStore.applySynced(Self.state(for: record))
        }
        for key in outcome.changedProgress {
            guard let record = ledger.progress[key] else { continue }
            await applyProgress(record)
        }
    }

    private func applyProgress(_ record: SyncProgressRecord) async {
        switch record.type {
        case .movie:
            watchStore.applySynced(Self.progress(for: record, tmdbId: record.tmdbID))
        case .tv:
            guard let season = record.season, let episode = record.episode else { return }
            var tmdbId = watchStore.episodeTmdbID(showTmdbId: record.tmdbID, season: season, episode: episode)
            if tmdbId == nil { tmdbId = await episodeResolver(record.tmdbID, season, episode) }
            if let tmdbId {
                watchStore.applySynced(Self.progress(for: record, tmdbId: tmdbId))
                state.pendingProgress.removeAll { $0.key == record.key }
            } else {
                state.pendingProgress.removeAll { $0.key == record.key }
                state.pendingProgress.append(record)
            }
        }
    }

    /// Retries the episode records that could not be resolved earlier.
    private func resolvePending(ledger: inout SyncLedger) async {
        guard !state.pendingProgress.isEmpty else { return }
        for record in state.pendingProgress.prefix(50) {
            guard ledger.progress[record.key] == record else {
                state.pendingProgress.removeAll { $0.key == record.key }
                continue
            }
            await applyProgress(record)
        }
    }

    // MARK: - Record mapping

    /// A local row as a sync record: seconds from the fraction and the
    /// duration kept in `watchedSeconds`. A row with no duration (an
    /// episode marked watched by hand) is written as one second long, so
    /// its completion still travels.
    static func record(for progress: WatchProgress) -> SyncProgressRecord? {
        guard let key = progress.syncKey else { return nil }
        let stamp = SyncStamp(updateTime: progress.updateTime, device: progress.deviceID ?? "")
        if progress.cleared {
            return SyncProgressRecord(
                type: key.type, tmdbID: key.tmdbID, season: key.season, episode: key.episode,
                cleared: true, device: stamp.device, updateTime: stamp.updateTime
            )
        }
        let duration = progress.watchedSeconds > 0 ? progress.watchedSeconds : 1
        let fraction = min(max(progress.position, 0), 1)
        return SyncProgressRecord(
            type: key.type, tmdbID: key.tmdbID, season: key.season, episode: key.episode,
            position: fraction * duration, duration: duration, completed: progress.isCompleted,
            device: stamp.device, updateTime: stamp.updateTime
        )
    }

    static func progress(for record: SyncProgressRecord, tmdbId: Int) -> WatchProgress {
        let duration = record.duration ?? 0
        let fraction = duration > 0 ? min(max((record.position ?? 0) / duration, 0), 1) : 0
        return WatchProgress(
            tmdbId: tmdbId,
            mediaType: record.type == .movie ? .movie : .episode,
            position: record.cleared ? 0 : fraction,
            watchedSeconds: record.cleared ? 0 : duration,
            isCompleted: record.cleared ? false : (record.completed ?? false),
            showTmdbId: record.type == .tv ? record.tmdbID : nil,
            seasonNumber: record.season,
            episodeNumber: record.episode,
            lastWatchedAt: SyncLedger.date(milliseconds: record.updateTime),
            updateTime: record.updateTime,
            deviceID: record.device,
            cleared: record.cleared
        )
    }

    static func record(for state: UserMediaState) -> SyncUserMediaRecord {
        SyncUserMediaRecord(
            type: state.ref.mediaType == .movie ? .movie : .tv,
            tmdbID: state.ref.id,
            favourite: state.isFavorite,
            watchlist: state.isInWatchlist,
            rating: state.rating,
            review: state.review,
            device: state.deviceID ?? "",
            updateTime: state.updateTime
        )
    }

    static func state(for record: SyncUserMediaRecord) -> UserMediaState {
        UserMediaState(
            ref: MediaRef(id: record.tmdbID, mediaType: record.type == .movie ? .movie : .tv),
            isFavorite: record.favourite,
            isInWatchlist: record.watchlist,
            rating: record.rating,
            review: record.review,
            updatedAt: SyncLedger.date(milliseconds: record.updateTime),
            updateTime: record.updateTime,
            deviceID: record.device
        )
    }

    // MARK: - Episode resolution

    /// An episode's TMDB ID from the library, then from TMDB.
    @MainActor
    static func libraryThenTMDBEpisodeResolver(showTmdbId: Int, season: Int, episode: Int) async -> Int? {
        if let id = libraryEpisodeID(showTmdbId: showTmdbId, season: season, episode: episode) { return id }
        guard TMDBService.shared.isConfigured else { return nil }
        return try? await TMDBService.shared.fetchEpisode(showId: showTmdbId, season: season, episode: episode).id
    }

    @MainActor
    static func libraryEpisodeID(showTmdbId: Int, season: Int, episode: Int) -> Int? {
        let context = Persistence.sharedModelContainer.mainContext
        var descriptor = FetchDescriptor<Episode>(
            predicate: #Predicate { $0.seasonNumber == season && $0.episodeNumber == episode && $0.tmdbId != nil }
        )
        descriptor.fetchLimit = 200
        let candidates = (try? context.fetch(descriptor)) ?? []
        return candidates.first { $0.show?.tmdbId == showTmdbId }?.tmdbId
    }
}
