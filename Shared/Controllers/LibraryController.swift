//
//  LibraryController.swift
//  Edendale
//

import Foundation
import SwiftData
import SwiftVLC

@MainActor
@Observable
final class LibraryController {

    // MARK: - Supported extensions

    nonisolated static let supportedExtensions: Set<String> = [
        "mp4", "m4v", "mov", "mkv", "avi", "wmv", "flv", "webm",
        "ts", "m2ts", "mts", "mpg", "mpeg", "divx", "xvid",
        "3gp", "ogv", "rm", "rmvb", "vob", "asf", "f4v"
    ]

    // MARK: - Observable state

    var isImporting  = false
    var isEnriching  = false
    var errorMessage: String?

    /// Why a linked source couldn't be scanned, by `VideoFolder.id`. Shown on
    /// the source's row (SourceRow) instead of as a library-wide error on
    /// every visit; cleared by the next successful scan.
    var sourceStates: [UUID: SourceState] = [:]

    enum SourceState: Equatable {
        /// Unreachable right now: offline, server down, rate-limited.
        case offline(String)
        /// Its account or login is gone or refused: sign in again.
        case needsSignIn(String)

        var message: String {
            switch self {
            case .offline(let message), .needsSignIn(let message): message
            }
        }
    }

    /// What the last Choose More Videos did to a Google Drive source, by
    /// `VideoFolder.id`, shown on its row until the source's next scan.
    /// Unlike `sourceStates`, never a problem.
    var sourceNotes: [UUID: String] = [:]

    /// Remote sources scanned more recently than this are skipped by the
    /// automatic sweep on every library visit; ⌘R and Rescan still scan.
    nonisolated static let automaticRescanInterval: TimeInterval = 15 * 60

    /// Guards the on-appear sweep so overlapping view appearances (rapid tab
    /// switches, push/pop) don't scan the same source concurrently and
    /// double-add files. Not observed — it only gates work, never drives UI.
    @ObservationIgnored private var isRescanning = false

    /// A title TMDB had no match for is asked about again after this long
    /// by the automatic sweep; a forced rescan (⌘R, Rescan) asks at once.
    nonisolated static let unmatchedRetryInterval: TimeInterval = 7 * 24 * 60 * 60

    /// The running metadata sweep; a request while it runs queues one more
    /// pass instead of a second sweep over the same items.
    @ObservationIgnored private var enrichmentTask: Task<Void, Never>?
    @ObservationIgnored private var enrichmentQueued = false
    @ObservationIgnored private var enrichmentRetriesUnmatched = false

    // MARK: - Dependencies

    private let modelContext: ModelContext
    private let metadata: MetadataLookup
    /// Where a stored source's login or account comes from on rescans.
    private let connectors: ConnectorFactory.Environment
    private let enrichesMetadata: Bool

    /// Tests pass in-memory stores and either turn TMDB enrichment off or
    /// answer its lookups themselves.
    init(
        modelContext: ModelContext,
        connectors: ConnectorFactory.Environment = .live,
        enrichesMetadata: Bool = true,
        metadata: MetadataLookup? = nil
    ) {
        self.modelContext = modelContext
        self.connectors = connectors
        self.enrichesMetadata = enrichesMetadata
        self.metadata = metadata ?? .tmdb
        repairLegacyRemoteItems()
    }

    // MARK: - Import

    /// Imports a folder from the system file picker.
    /// Scans all video files, classifies them as movies or TV episodes,
    /// persists the index immediately, then kicks off TMDB enrichment in the background.
    func importFolder(url: URL) async {
        isImporting = true
        errorMessage = nil
        defer { isImporting = false }

        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }

        // Walk the tree, parse filenames, and probe durations off the main
        // actor — for a large folder this was a multi-second main-thread hang.
        // Only the model building below touches the main actor.
        let scanned = await scanFolder(root: url)

        let folder = VideoFolder(
            name: url.lastPathComponent,
            folderPath: url.path,
            bookmarkData: makeBookmark(for: url)
        )
        modelContext.insert(folder)

        for file in scanned {
            insert(file, into: folder)
        }

        save()

        enrichPendingMetadata()
    }

    // MARK: - Network sources

    /// Links a remote source folder picked in the browse UI: lists every
    /// video beneath it, classifies the file names locally, persists, then
    /// enriches in the background. Any login is already in the Keychain
    /// (saved by the Link Source flow); listings and persisted paths stay
    /// credential-free.
    ///
    /// - Parameter displayPath: The readable location shown with the
    ///   source, e.g. "OneDrive › Movies".
    func importRemoteFolder(
        connector: any MediaConnector,
        folderURL: URL,
        displayName: String,
        displayPath: String? = nil
    ) async {
        // One Google Drive source per account (R6): linking it again rescans.
        if connector.kind == .googleDrive,
           let existing = source(kind: .googleDrive, folderPath: folderURL.absoluteString) {
            await rescanRemoteFolder(existing)
            return
        }

        isImporting = true
        errorMessage = nil
        defer { isImporting = false }

        let entries: [ConnectorEntry]
        do {
            entries = try await connector.enumerateVideos(under: folderURL)
        } catch is CancellationError {
            return
        } catch {
            errorMessage = error.localizedDescription
            return
        }

        let scanned = await descriptors(for: entries)

        let folder = VideoFolder(
            name: displayName,
            folderPath: folderURL.absoluteString,
            sourceKind: connector.kind,
            username: connector.accountLabel
        )
        folder.displayPath = displayPath
        if connector.kind.isCloudAccount || connector.kind == .s3 {
            folder.accountKey = folderURL.host(percentEncoded: false)
        }
        folder.lastScannedAt = Date()
        modelContext.insert(folder)

        for file in scanned {
            insert(file, into: folder)
        }

        save()

        enrichPendingMetadata()
    }

    /// Rebuilds the connector for a stored network source (login or account
    /// from the Keychain); `nil` for local folders, or when the source's
    /// login or account is gone.
    private func connector(for folder: VideoFolder) -> (any MediaConnector)? {
        guard let url = folder.remoteURL else { return nil }
        return ConnectorFactory.connector(forSource: url, kind: folder.sourceKind, environment: connectors)
    }

    private func source(kind: MediaSourceKind, folderPath: String) -> VideoFolder? {
        let folders = (try? modelContext.fetch(FetchDescriptor<VideoFolder>())) ?? []
        return folders.first { $0.sourceKind == kind && $0.folderPath == folderPath }
    }

    // MARK: - Google Drive

    /// Links an account's Google Drive source, "Videos you chose", or
    /// rescans it when this device already has it. `signIn` carries the
    /// files Google's picker just returned (none when an account already
    /// linked was chosen, or one handed to Apple TV): picking a removed
    /// video again brings it back. Returns how many of those picks are new
    /// to the library, and says so on the source's row.
    @discardableResult
    func addChosenVideos(_ signIn: CloudAccountStore.SignIn) async -> Int {
        let account = signIn.account
        let picked = Set(signIn.pickedFileIDs)
        let rootURL = GoogleDriveConnector.chosenFolderURL(accountKey: account.key)
        let before: Set<String>
        if let folder = source(kind: .googleDrive, folderPath: rootURL.absoluteString) {
            folder.hiddenItemIDs = folder.hiddenItemIDs?.filter { !picked.contains($0) }
            before = Self.driveFileIDs(in: folder)
            await rescanRemoteFolder(folder)
        } else {
            guard let connector = ConnectorFactory.connector(for: account, environment: connectors) else { return 0 }
            before = []
            await importRemoteFolder(
                connector: connector,
                folderURL: rootURL,
                displayName: GoogleDriveConnector.chosenFolderName,
                displayPath: "\(MediaSourceKind.googleDrive.displayName) › \(GoogleDriveConnector.chosenFolderName)"
            )
        }
        guard let folder = source(kind: .googleDrive, folderPath: rootURL.absoluteString) else { return 0 }
        let added = picked.intersection(Self.driveFileIDs(in: folder).subtracting(before)).count
        if !picked.isEmpty, sourceStates[folder.id] == nil {
            sourceNotes[folder.id] = switch added {
            case 0: String(localized: "The videos you chose were already in your library.")
            case 1: String(localized: "Added 1 video.")
            default: String(localized: "Added \(added) videos.")
            }
        }
        return added
    }

    #if !os(tvOS)
    /// Choose More Videos (R7): reruns Google's picker for `account`, then
    /// rescans its source here, adding one if this device has none.
    @discardableResult
    func chooseMoreVideos(for account: CloudAccount, accounts: CloudAccountStore) async throws -> Int {
        await addChosenVideos(try await accounts.chooseMoreVideos(for: account))
    }
    #endif

    /// D1: removes Google Drive sources linked before `drive.file`, which
    /// are Drive folders rather than "Videos you chose". CloudAccountStore
    /// removes their accounts, unless another device's migration already
    /// did through the iCloud Keychain. Returns how many went.
    @discardableResult
    func removeGoogleDriveSourcesFromBeforeDriveFile() -> Int {
        let folders = (try? modelContext.fetch(FetchDescriptor<VideoFolder>())) ?? []
        let outdated = folders.filter { folder in
            folder.sourceKind == .googleDrive
                && !(folder.remoteURL.map(GoogleDriveConnector.isChosenFolder) ?? false)
        }
        for folder in outdated {
            sourceStates[folder.id] = nil
            sourceNotes[folder.id] = nil
            modelContext.delete(folder)
        }
        if !outdated.isEmpty { save() }
        return outdated.count
    }

    /// The Drive file ID in a stored item path; `nil` for other kinds.
    nonisolated static func driveFileID(fromPath path: String) -> String? {
        guard let url = URL(string: path),
              let item = SourceURL.parseAccountItem(url), item.kind == .googleDrive
        else { return nil }
        return item.ids.first
    }

    /// Entries a scan of `folder` adds: none of its hidden Drive files (D2).
    nonisolated static func visibleEntries(_ entries: [ConnectorEntry], hiding hidden: [String]?) -> [ConnectorEntry] {
        guard let hidden, !hidden.isEmpty else { return entries }
        let ids = Set(hidden)
        return entries.filter { entry in
            guard let id = driveFileID(fromPath: entry.url.absoluteString) else { return true }
            return !ids.contains(id)
        }
    }

    private static func driveFileIDs(in folder: VideoFolder) -> Set<String> {
        let paths = folder.movies.map(\.filePath) + folder.tvShows.flatMap(\.episodes).map(\.filePath)
        return Set(paths.compactMap(driveFileID(fromPath:)))
    }

    /// Remembers removed Drive files on their source, so rescans skip them.
    private func hideDriveItems(_ paths: [String], in folder: VideoFolder?) {
        guard let folder, folder.sourceKind == .googleDrive else { return }
        let ids = paths.compactMap(Self.driveFileID(fromPath:))
        guard !ids.isEmpty else { return }
        var hidden = folder.hiddenItemIDs ?? []
        hidden += ids.filter { !hidden.contains($0) }
        folder.hiddenItemIDs = hidden
    }

    // MARK: - Rescan

    /// Re-scans every linked source, adding files that appeared since the last
    /// scan. Meant to run when the library view appears so files added outside
    /// the app (new downloads, files dropped on a network share) show up without
    /// a manual rescan. Re-entrancy is guarded, so it's safe to call on every
    /// appearance; each folder's own scan skips already-known paths.
    ///
    /// Listing a remote source is network traffic, and metered API calls for
    /// cloud providers, so the automatic sweep skips a remote source scanned
    /// in the last 15 minutes. `force` (⌘R, Rescan Library) scans every one.
    func rescanAllFolders(force: Bool = false, now: Date = Date()) async {
        guard !isRescanning else { return }
        isRescanning = true
        defer { isRescanning = false }

        let folders = (try? modelContext.fetch(FetchDescriptor<VideoFolder>())) ?? []
        for folder in folders where force || Self.needsAutomaticRescan(folder, now: now) {
            await scanForNewFiles(folder)
        }
        // Every visit also finishes what an earlier sweep left: an import
        // interrupted by quitting, lookups made while offline, and episodes
        // added to a show that was already matched.
        enrichPendingMetadata(retryingUnmatched: force)
    }

    /// Local folders always; remote sources once their last scan is older
    /// than `automaticRescanInterval`.
    static func needsAutomaticRescan(_ folder: VideoFolder, now: Date) -> Bool {
        guard folder.isRemote, folder.sourceKind.throttlesAutomaticRescans,
              let lastScannedAt = folder.lastScannedAt
        else { return true }
        return now.timeIntervalSince(lastScannedAt) >= automaticRescanInterval
    }

    /// Re-scans a folder and adds any newly discovered files (a source's
    /// Rescan), then asks TMDB again about titles it had no match for.
    func rescanFolder(_ folder: VideoFolder) async {
        await scanForNewFiles(folder)
        enrichPendingMetadata(retryingUnmatched: true)
    }

    private func scanForNewFiles(_ folder: VideoFolder) async {
        if folder.isRemote {
            await rescanRemoteFolder(folder)
            return
        }
        guard let url = folder.resolvedURL() else { return }
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }

        let knownPaths: Set<String> = Set(folder.movies.map(\.filePath))
            .union(folder.tvShows.flatMap(\.episodes).map(\.filePath))

        // Enumeration + duration probing run off the main actor; known paths
        // are skipped inside the scan so nothing is re-probed. This sweep runs
        // on every library appearance, so it must never block the main thread.
        let scanned = await scanFolder(root: url, known: knownPaths)
        guard !scanned.isEmpty else { return }

        for file in scanned {
            insert(file, into: folder)
        }
        save()
        enrichPendingMetadata()
    }

    /// Re-walks a network source and adds newly discovered files. A failure
    /// is recorded on the source (see `sourceStates`) rather than reported
    /// as a library-wide error.
    private func rescanRemoteFolder(_ folder: VideoFolder) async {
        guard let rootURL = folder.remoteURL else { return }
        sourceNotes[folder.id] = nil
        guard let connector = connector(for: folder) else {
            sourceStates[folder.id] = .needsSignIn(
                ConnectorError.signInRequired(provider: folder.sourceKind.displayName).localizedDescription
            )
            return
        }

        let knownPaths: Set<String> = Set(folder.movies.map(\.filePath))
            .union(folder.tvShows.flatMap(\.episodes).map(\.filePath))

        let entries: [ConnectorEntry]
        do {
            entries = try await connector.enumerateVideos(under: rootURL)
        } catch is CancellationError {
            return
        } catch let error as ConnectorError where error.needsUserAction {
            sourceStates[folder.id] = .needsSignIn(error.localizedDescription)
            return
        } catch {
            sourceStates[folder.id] = .offline(error.localizedDescription)
            return
        }
        sourceStates[folder.id] = nil
        folder.lastScannedAt = Date()

        let newEntries = Self.visibleEntries(entries, hiding: folder.hiddenItemIDs)
            .filter { !knownPaths.contains($0.url.absoluteString) }
        guard !newEntries.isEmpty else {
            save()
            return
        }

        let scanned = await descriptors(for: newEntries)
        for file in scanned {
            insert(file, into: folder)
        }
        save()
        enrichPendingMetadata()
    }

    // MARK: - Playback preparation

    /// Builds a one-shot playback request for a file handed to Edendale by
    /// Finder or Files. The file is deliberately not added to the library:
    /// its security scope stays alive only for the playback session.
    func preparePlayback(fileURL: URL) async -> PlaybackItem {
        do {
            guard fileURL.isFileURL else {
                throw LibraryError.invalidFileURL
            }
            guard isVideoFile(fileURL) else {
                throw LibraryError.unsupportedFileType(fileURL.lastPathComponent)
            }

            let accessed = fileURL.startAccessingSecurityScopedResource()
            guard accessed || FileManager.default.isReadableFile(atPath: fileURL.path) else {
                throw LibraryError.accessDenied(fileURL.lastPathComponent)
            }

            let scope = PlaybackScope(
                playURL: fileURL,
                accessedURL: accessed ? fileURL : nil
            )
            if isICloudItem(fileURL) {
                try await triggerCloudDownload(url: fileURL)
            }
            return PlaybackItem(scope: scope)
        } catch {
            return PlaybackItem(failed: error.localizedDescription)
        }
    }

    /// Builds a playback request for a movie: resolves the security-scoped
    /// bookmark (refreshing it if stale), starts scoped access, and triggers an
    /// iCloud download when needed. Resolution/access failures come back as a
    /// `PlaybackItem` carrying a message rather than throwing, so the player can
    /// show the reason instead of a silent black screen.
    func preparePlayback(movie: Movie) async -> PlaybackItem {
        if let scope = remoteScope(filePath: movie.filePath) {
            notePlayed(movie)
            return PlaybackItem(scope: scope, movie: movie)
        }
        do {
            let scope = try resolveScope(
                bookmark: movie.bookmarkData,
                filePath: movie.filePath,
                folder: movie.folder
            ) { movie.bookmarkData = $0 }
            if movie.isICloudItem { try await triggerCloudDownload(url: scope.url) }
            notePlayed(movie)
            return PlaybackItem(scope: scope, movie: movie)
        } catch {
            return PlaybackItem(failed: error.localizedDescription)
        }
    }

    /// Builds a playback request for an episode, falling back to the parent
    /// show's folder bookmark when the episode itself has none.
    func preparePlayback(episode: Episode) async -> PlaybackItem {
        if let scope = remoteScope(filePath: episode.filePath) {
            notePlayed(episode)
            return PlaybackItem(scope: scope, episode: episode)
        }
        do {
            let scope = try resolveScope(
                bookmark: episode.bookmarkData,
                filePath: episode.filePath,
                folder: episode.show?.folder
            ) { episode.bookmarkData = $0 }
            if episode.isICloudItem { try await triggerCloudDownload(url: scope.url) }
            notePlayed(episode)
            return PlaybackItem(scope: scope, episode: episode)
        } catch {
            return PlaybackItem(failed: error.localizedDescription)
        }
    }

    // MARK: - Copies across sources

    /// Remembers the copy that started playing, so Play, Continue Watching,
    /// and the episode menus default to the source (or endpoint) the viewer
    /// chose last. Library data: it stays on this device.
    func notePlayed(_ movie: Movie, at date: Date = Date()) {
        movie.lastPlayedAt = date
        save()
    }

    /// Remembers the copy of the show (and with it the source) an episode
    /// was played from; see `notePlayed(_:)` for movies.
    func notePlayed(_ episode: Episode, at date: Date = Date()) {
        guard let show = episode.show else { return }
        show.lastPlayedAt = date
        save()
    }

    /// The show's other copies: the same title imported from other sources
    /// or reached through other endpoints.
    func otherCopies(of show: TVShow) -> [TVShow] {
        let key = PlaybackSources.copyKey(show)
        let shows = (try? modelContext.fetch(FetchDescriptor<TVShow>())) ?? []
        let others = shows.filter { $0.id != show.id && PlaybackSources.copyKey($0) == key }
        return Array(PlaybackSources.order(primary: show, others: others, folder: \.folder).dropFirst())
    }

    /// Whether the last scan of `folder` couldn't reach it or was refused.
    func isOffline(_ folder: VideoFolder?) -> Bool {
        guard let folder else { return false }
        return sourceStates[folder.id] != nil
    }

    /// Playback scope for a network item. SMB gets the host's Keychain login
    /// injected into an in-memory URL for libsmb2; every other kind plays its
    /// stored credential-free URL, and FFmpegDecoder fetches the login or
    /// account through ConnectorFactory. Nothing scoped to release.
    private func remoteScope(filePath: String) -> PlaybackScope? {
        guard filePath.contains("://"),
              let url = URL(string: filePath),
              let kind = MediaSourceKind(url: url), kind.isRemote
        else { return nil }

        guard kind == .smb, let host = url.host() else {
            return PlaybackScope(playURL: url, accessedURL: nil)
        }
        let credential = NetworkCredentialStore.credential(kind: .smb, host: host)
        let playURL = VLCNetworkBrowser.authenticatedURL(url, credential: credential) ?? url
        return PlaybackScope(playURL: playURL, accessedURL: nil)
    }

    /// Heals items imported before remote paths were stored as full URLs:
    /// early imports saved only the URL's path plus a generic bookmark, so
    /// playback resolved to a credential-free address and failed SMB auth.
    /// Rebuilds each item's URL from its folder's host and drops the bogus
    /// bookmark. Idempotent — repaired items no longer match the predicate.
    private func repairLegacyRemoteItems() {
        let folders = (try? modelContext.fetch(FetchDescriptor<VideoFolder>())) ?? []
        var repaired = false

        // Only SMB sources predate full-URL paths.
        for folder in folders where folder.sourceKind == .smb {
            guard let root = folder.remoteURL,
                  var components = URLComponents(url: root, resolvingAgainstBaseURL: false)
            else { continue }

            for movie in folder.movies where !movie.filePath.contains("://") {
                components.path = movie.filePath
                guard let url = components.url else { continue }
                movie.filePath = url.absoluteString
                movie.bookmarkData = nil
                repaired = true
            }
            for episode in folder.tvShows.flatMap(\.episodes)
            where !episode.filePath.contains("://") {
                components.path = episode.filePath
                guard let url = components.url else { continue }
                episode.filePath = url.absoluteString
                episode.bookmarkData = nil
                repaired = true
            }
        }

        if repaired { save() }
    }

    // MARK: - Security-scoped resolution

    /// Resolves a file to a `PlaybackScope` with security-scoped access already
    /// started. Tries the item's own bookmark first, then the parent folder's
    /// (a folder bookmark grants access to every file beneath it), and finally a
    /// raw readable path. Stale bookmarks are refreshed and persisted.
    private func resolveScope(
        bookmark: Data?,
        filePath: String,
        folder: VideoFolder?,
        persistItemBookmark: (Data) -> Void
    ) throws -> PlaybackScope {
        let fileURL = URL(fileURLWithPath: filePath)

        // 1. The item's own security-scoped bookmark: scope URL == play URL.
        if let bookmark {
            var stale = false
            if let url = try? URL(
                resolvingBookmarkData: bookmark,
                options: .securityScoped,
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            ) {
                let started = url.startAccessingSecurityScopedResource()
                if stale, let refreshed = makeBookmark(for: url) {
                    persistItemBookmark(refreshed)
                    save()
                }
                if started || FileManager.default.isReadableFile(atPath: url.path) {
                    return PlaybackScope(playURL: url, accessedURL: started ? url : nil)
                }
            }
        }

        // 2. Parent-folder bookmark: start the folder's scope, play the file inside.
        if let folder, let folderBookmark = folder.bookmarkData {
            var stale = false
            if let folderURL = try? URL(
                resolvingBookmarkData: folderBookmark,
                options: .securityScoped,
                relativeTo: nil,
                bookmarkDataIsStale: &stale
            ) {
                let started = folderURL.startAccessingSecurityScopedResource()
                if stale, let refreshed = makeBookmark(for: folderURL) {
                    folder.bookmarkData = refreshed
                    save()
                }
                if started || FileManager.default.isReadableFile(atPath: filePath) {
                    return PlaybackScope(playURL: fileURL, accessedURL: started ? folderURL : nil)
                }
            }
        }

        // 3. No bookmark resolved. A readable path (unsandboxed / same container)
        //    still plays; otherwise the sandbox denies the open, so fail loudly.
        guard FileManager.default.isReadableFile(atPath: filePath) else {
            throw LibraryError.accessDenied(fileURL.lastPathComponent)
        }
        return PlaybackScope(playURL: fileURL, accessedURL: nil)
    }

    // MARK: - Removal

    /// Removes a source and its items. A saved login or linked account is
    /// kept: deleting a synchronizable Keychain item would also sign the
    /// user's other devices out. Settings → Accounts forgets them.
    func removeFolder(_ folder: VideoFolder) {
        sourceStates[folder.id] = nil
        sourceNotes[folder.id] = nil
        modelContext.delete(folder)
        save()
    }

    /// A removed Google Drive video stays hidden from later scans (D2).
    func removeMovie(_ movie: Movie) {
        hideDriveItems([movie.filePath], in: movie.folder)
        modelContext.delete(movie)
        save()
    }

    func removeTVShow(_ show: TVShow) {
        hideDriveItems(show.episodes.map(\.filePath), in: show.folder)
        modelContext.delete(show)
        save()
    }

    // MARK: - Scanning (off the main actor)

    /// Everything needed to build a model object for one video file, resolved
    /// off the main actor so the disk walk and per-file VLC probe never block
    /// the UI. Sendable so the background scan can hand a batch back to the main
    /// actor, which turns each into a `Movie`/`Episode`.
    private struct ScannedFile: Sendable {
        let parsed: MediaParser.ParsedMedia
        let storedPath: String
        let bookmark: Data?
        let duration: TimeInterval
        let isICloud: Bool
    }

    /// Walks `root`, parses each filename, and probes local durations — all off
    /// the main actor. `known` stored-paths are skipped, so a rescan probes only
    /// newly-seen files. `@concurrent` forces this onto the global executor:
    /// under nonisolated-nonsending semantics a bare `nonisolated async` would
    /// otherwise run on the caller (main), which is the hang we're avoiding.
    @concurrent nonisolated private func scanFolder(
        root: URL,
        known: Set<String> = []
    ) async -> [ScannedFile] {
        var results: [ScannedFile] = []
        for fileURL in collectVideoURLs(in: root) {
            guard !known.contains(storedPath(for: fileURL)) else { continue }
            // Duration probing over the network would drag the import out to
            // one remote read per file; network items stay at 0 (--:--).
            let duration = fileURL.isFileURL ? (await vlcDuration(of: fileURL) ?? 0) : 0
            results.append(descriptor(for: fileURL, duration: duration))
        }
        return results
    }

    /// Builds descriptors for an already-listed set of entries (network
    /// sources, whose tree is fetched asynchronously by the connector). No
    /// duration probe — a remote read per file would cost one round trip
    /// each — but a duration the provider listed (Google Drive, OneDrive) is
    /// kept, so those items show their runtime instead of `--:--`. Runs off
    /// the main actor so filename parsing doesn't jam the UI on a large source.
    @concurrent nonisolated private func descriptors(for entries: [ConnectorEntry]) async -> [ScannedFile] {
        entries.map { descriptor(for: $0.url, duration: $0.duration ?? 0) }
    }

    /// Resolves one file URL into a `ScannedFile`: filename parse, stored path,
    /// security-scoped bookmark, and iCloud flag. Pure URL/filesystem work, safe
    /// to run off the main actor.
    nonisolated private func descriptor(for url: URL, duration: TimeInterval = 0) -> ScannedFile {
        ScannedFile(
            parsed: MediaParser.parse(fileURL: url),
            storedPath: storedPath(for: url),
            bookmark: makeBookmark(for: url),
            duration: duration,
            isICloud: isICloudItem(url)
        )
    }

    // MARK: - Classification

    /// Builds the model object for a scanned file and appends it to `folder`
    /// (or inserts it standalone when folder is nil). Main-actor only: pure
    /// SwiftData mutation, with the disk and network work already done.
    private func insert(_ file: ScannedFile, into folder: VideoFolder?) {
        switch file.parsed {

        case .movie(let title, let year):
            let movie = Movie(
                localTitle: title,
                filePath: file.storedPath,
                bookmarkData: file.bookmark,
                releaseYear: year,
                isICloudItem: file.isICloud
            )
            movie.duration = file.duration
            if let folder {
                folder.movies.append(movie)
            } else {
                modelContext.insert(movie)
            }

        case .episode(let showName, let season, let episode):
            let show = resolveShow(named: showName, in: folder)
            let ep = Episode(
                localTitle: "\(showName) \(String(format: "S%02dE%02d", season, episode))",
                filePath: file.storedPath,
                bookmarkData: file.bookmark,
                seasonNumber: season,
                episodeNumber: episode,
                isICloudItem: file.isICloud
            )
            ep.duration = file.duration
            show.episodes.append(ep)
            if folder == nil { modelContext.insert(show) }
        }
    }

    /// Returns the existing TVShow with a matching name in the folder,
    /// or inserts a new one. Creates a standalone show when folder is nil.
    private func resolveShow(named name: String, in folder: VideoFolder?) -> TVShow {
        let existing = folder?.tvShows.first {
            $0.name.lowercased() == name.lowercased()
        }
        if let existing { return existing }

        let show = TVShow(name: name)
        modelContext.insert(show)
        folder?.tvShows.append(show)
        return show
    }

    // MARK: - TMDB Enrichment

    /// Matches every title in the library that still lacks TMDB metadata,
    /// on a task of the controller's own, so leaving the page that asked
    /// doesn't cancel it. A title is indexed once it has a `tmdbId`; one
    /// TMDB had no match for carries `metadataCheckedAt` and waits
    /// `unmatchedRetryInterval` (or a forced rescan) before it's asked
    /// about again. A lookup that fails — offline, rate-limited, no
    /// credentials — marks nothing and ends the pass, and the next sweep
    /// (every library visit, each rescan, each launch) picks up from there.
    /// Calls while a sweep runs queue one more pass and return its task.
    @discardableResult
    func enrichPendingMetadata(retryingUnmatched: Bool = false) -> Task<Void, Never>? {
        guard enrichesMetadata else { return nil }
        enrichmentQueued = true
        enrichmentRetriesUnmatched = enrichmentRetriesUnmatched || retryingUnmatched
        if let enrichmentTask { return enrichmentTask }
        let task = Task { [weak self] in
            guard let self else { return }
            await self.runEnrichmentPasses()
        }
        enrichmentTask = task
        return task
    }

    /// Titles a sweep would look up now: no TMDB match yet, and not marked
    /// unmatched within `unmatchedRetryInterval`. Episodes count once their
    /// show is matched. Zero means everything matchable is indexed.
    func pendingMetadataCount(now: Date = Date()) -> Int {
        let due = { (checked: Date?) in Self.isDue(checked, now: now, retryingUnmatched: false) }
        return unmatchedMovies().filter { due($0.metadataCheckedAt) }.count
            + unmatchedShows().filter { due($0.metadataCheckedAt) }.count
            + unmatchedEpisodes().filter { due($0.metadataCheckedAt) }.count
    }

    nonisolated static func isDue(_ checkedAt: Date?, now: Date, retryingUnmatched: Bool) -> Bool {
        guard let checkedAt, !retryingUnmatched else { return true }
        return now.timeIntervalSince(checkedAt) >= unmatchedRetryInterval
    }

    private func runEnrichmentPasses() async {
        isEnriching = true
        defer {
            isEnriching = false
            enrichmentTask = nil
        }
        while enrichmentQueued {
            enrichmentQueued = false
            let retrying = enrichmentRetriesUnmatched
            enrichmentRetriesUnmatched = false
            await sweepMetadata(retryingUnmatched: retrying, now: Date())
        }
    }

    /// TMDB can't be reached or refuses the request, so every lookup after
    /// this one would fail the same way.
    private struct EnrichmentHalted: Error {}

    private func sweepMetadata(retryingUnmatched: Bool, now: Date) async {
        let due = { (checked: Date?) in Self.isDue(checked, now: now, retryingUnmatched: retryingUnmatched) }
        do {
            for movie in unmatchedMovies() where due(movie.metadataCheckedAt) {
                try await enrichMovie(movie, now: now)
            }
            for show in unmatchedShows() where due(show.metadataCheckedAt) {
                try await enrichShow(show, now: now)
            }
            // Fetched after the shows so the ones just matched are included,
            // along with episodes added to a show matched in an earlier sweep.
            for episode in unmatchedEpisodes() where due(episode.metadataCheckedAt) {
                try await enrichEpisode(episode, now: now)
            }
        } catch {
            // EnrichmentHalted: what's left stays unmarked for the next sweep.
        }
        save()
    }

    private func unmatchedMovies() -> [Movie] {
        let descriptor = FetchDescriptor<Movie>(predicate: #Predicate { $0.tmdbId == nil })
        return (try? modelContext.fetch(descriptor)) ?? []
    }

    private func unmatchedShows() -> [TVShow] {
        let descriptor = FetchDescriptor<TVShow>(predicate: #Predicate { $0.tmdbId == nil })
        return (try? modelContext.fetch(descriptor)) ?? []
    }

    /// Episodes without a match whose show has one; an unmatched show's
    /// episodes have nothing to be looked up against yet.
    private func unmatchedEpisodes() -> [Episode] {
        let descriptor = FetchDescriptor<Episode>(predicate: #Predicate { $0.tmdbId == nil })
        return ((try? modelContext.fetch(descriptor)) ?? []).filter { $0.show?.tmdbId != nil }
    }

    private func enrichMovie(_ movie: Movie, now: Date) async throws {
        let title = movie.localTitle, year = movie.releaseYear
        let result = try await lookup { try await self.metadata.searchMovie(title, year) }
        // Removed from the library while TMDB answered.
        guard !Self.isGone(movie) else { return }
        guard let result else {
            movie.metadataCheckedAt = now
            return
        }

        movie.tmdbId      = result.id
        movie.tmdbTitle   = result.title
        movie.overview    = result.overview
        movie.posterPath  = result.posterPath
        movie.backdropPath = result.backdropPath
        movie.voteAverage = result.voteAverage
        movie.metadataCheckedAt = nil
        if let dateStr = result.releaseDate {
            movie.releaseYear = Int(String(dateStr.prefix(4)))
        }
        save()
    }

    private func enrichShow(_ show: TVShow, now: Date) async throws {
        let name = show.name
        let result = try await lookup { try await self.metadata.searchTV(name) }
        guard !Self.isGone(show) else { return }
        guard let result else {
            show.metadataCheckedAt = now
            return
        }

        show.tmdbId      = result.id
        show.tmdbName    = result.name
        show.overview    = result.overview
        show.posterPath  = result.posterPath
        show.backdropPath = result.backdropPath
        show.firstAirDate = result.firstAirDate
        show.metadataCheckedAt = nil
        save()
    }

    private func enrichEpisode(_ episode: Episode, now: Date) async throws {
        guard let showId = episode.show?.tmdbId else { return }
        let season = episode.seasonNumber, number = episode.episodeNumber
        let detail = try await lookup { try await self.metadata.fetchEpisode(showId, season, number) }
        guard !Self.isGone(episode) else { return }
        guard let detail else {
            // TMDB has no such episode under that numbering.
            episode.metadataCheckedAt = now
            return
        }

        episode.tmdbId       = detail.id
        episode.episodeTitle = detail.name
        episode.overview     = detail.overview
        episode.stillPath    = detail.stillPath
        episode.airDate      = detail.airDate
        episode.voteAverage  = detail.voteAverage
        episode.metadataCheckedAt = nil
        save()
    }

    /// One lookup: `nil` when TMDB answered without a usable match (no
    /// results, a 404, a payload that didn't decode); `EnrichmentHalted`
    /// when it couldn't be reached or refused the request.
    private func lookup<Result>(_ request: () async throws -> Result?) async throws -> Result? {
        do {
            return try await request()
        } catch where Self.haltsEnrichment(error) {
            throw EnrichmentHalted()
        } catch {
            return nil
        }
    }

    nonisolated static func haltsEnrichment(_ error: any Error) -> Bool {
        if error is CancellationError { return true }
        if let error = error as? URLError {
            return error.code != .badURL && error.code != .unsupportedURL
        }
        switch error as? TMDBError {
        case .missingCredentials: return true
        case .badStatus(let code): return code == 401 || code == 403 || code == 429 || code >= 500
        case nil: return false
        }
    }

    private static func isGone(_ model: some PersistentModel) -> Bool {
        model.modelContext == nil || model.isDeleted
    }

    // MARK: - VLC metadata

    /// Duration in seconds from VLC's metadata parse, or nil when the file
    /// can't be opened or reports no duration. `@concurrent` keeps the media
    /// open/parse off the main actor no matter who calls it.
    @concurrent nonisolated private func vlcDuration(of url: URL) async -> TimeInterval? {
        guard let media    = try? Media(url: url),
              let metadata = try? await media.parse(),
              let duration = metadata.duration
        else { return nil }
        return duration.playbackSeconds
    }

    // MARK: - iCloud

    nonisolated private func isICloudItem(_ url: URL) -> Bool {
        (try? url.resourceValues(forKeys: [.isUbiquitousItemKey]).isUbiquitousItem) == true
    }

    /// Signals iCloud to start downloading the file so VLC can stream it immediately.
    private func triggerCloudDownload(url: URL) async throws {
        let values = try url.resourceValues(forKeys: [.ubiquitousItemDownloadingStatusKey])
        guard values.ubiquitousItemDownloadingStatus != .current else { return }
        try FileManager.default.startDownloadingUbiquitousItem(at: url)
    }

    // MARK: - Helpers

    nonisolated private func collectVideoURLs(in folderURL: URL) -> [URL] {
        let fm = FileManager.default
        guard let enumerator = fm.enumerator(
            at: folderURL,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [.skipsHiddenFiles, .skipsPackageDescendants]
        ) else { return [] }

        return (enumerator.allObjects as? [URL] ?? []).filter { isVideoFile($0) }
    }

    nonisolated private func isVideoFile(_ url: URL) -> Bool {
        Self.supportedExtensions.contains(url.pathExtension.lowercased())
    }

    // MARK: - Sources

    /// How many linked sources reach `host` through `kind`'s login, or
    /// through the cloud account with that key (Settings → Accounts).
    func sourceCount(kind: MediaSourceKind, host: String) -> Int {
        let folders = (try? modelContext.fetch(FetchDescriptor<VideoFolder>())) ?? []
        return folders.filter { folder in
            folder.sourceKind == kind
                && folder.remoteURL?.host(percentEncoded: false)?.lowercased() == host.lowercased()
        }.count
    }

    /// What `filePath` persists: a plain filesystem path for local files, the
    /// full canonical URL string for network items — `remoteScope` keys off
    /// the scheme to re-inject the Keychain credential at playback.
    nonisolated private func storedPath(for url: URL) -> String {
        url.isFileURL ? url.path : url.absoluteString
    }

    nonisolated private func makeBookmark(for url: URL) -> Data? {
        // Non-file URLs still produce a generic bookmark that resolves back
        // to the bare URL — which would hijack playback with a credential-free
        // address. Bookmarks are for security-scoped local access only.
        guard url.isFileURL else { return nil }
        return try? url.bookmarkData(options: .securityScoped, includingResourceValuesForKeys: nil, relativeTo: nil)
    }

    @discardableResult
    private func save() -> Bool {
        do {
            try modelContext.save()
            return true
        } catch {
            errorMessage = String(localized: "Failed to save library: \(error.localizedDescription)")
            return false
        }
    }
}

// MARK: - Metadata lookups

/// The TMDB lookups library enrichment makes. Tests answer them without
/// the network.
struct MetadataLookup {
    var searchMovie: (_ title: String, _ year: Int?) async throws -> TMDBMovieResult?
    var searchTV: (_ name: String) async throws -> TMDBTVResult?
    var fetchEpisode: (_ showId: Int, _ season: Int, _ episode: Int) async throws -> TMDBEpisodeDetail

    static let tmdb = MetadataLookup(
        searchMovie: { try await TMDBService.shared.searchMovie(title: $0, year: $1) },
        searchTV: { try await TMDBService.shared.searchTV(name: $0) },
        fetchEpisode: { try await TMDBService.shared.fetchEpisode(showId: $0, season: $1, episode: $2) }
    )
}

// MARK: - Errors

enum LibraryError: Error, LocalizedError {
    case accessDenied(String)
    case invalidFileURL
    case unsupportedFileType(String)

    var errorDescription: String? {
        switch self {
        case .accessDenied(let name):
            String(localized: "Can't access \(name). It may be on a disconnected drive, or its folder needs to be re-added to restore permission.")
        case .invalidFileURL:
            String(localized: "Edendale can only open local video files.")
        case .unsupportedFileType(let name):
            String(localized: "Edendale can't open \(name) because its file type isn't supported.")
        }
    }
}
