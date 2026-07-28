//
//  PlayerSession.swift
//  Edendale
//
//  App-level playback coordinator. Owns the VLC `Player`, the current
//  `PlaybackItem`, and the chrome state for the active session, so every
//  platform host presents the same player:
//    - iOS/iPadOS/visionOS/tvOS: full-screen cover over RootView
//    - macOS: a dedicated resizable "Now Playing" window scene
//

import Foundation
import SwiftVLC
#if canImport(UIKit)
import UIKit
#endif

/// Scene identifier for the dedicated macOS player window.
enum PlayerSceneID {
    static let window = "player"
}

@MainActor
@Observable
final class PlayerSession {

    // MARK: - State

    /// The item being played; non-nil while the player is presented.
    private(set) var item: PlaybackItem?

    /// Live VLC player for the session. Created on present, torn down on end,
    /// reused across episode/file switches so rate and volume carry over.
    private(set) var player: Player?

    /// Chrome (controls) state for the session.
    private(set) var chrome: PlayerChromeModel?

    private let library: LibraryController
    private let watchStore: WatchProgressStore
    private var eventTask: Task<Void, Never>?
    private var activePlaybackRequestID = UUID()

    #if os(visionOS)
    /// A spatial or multiview asset routed through the system visionOS
    /// player. Ordinary 2D and unsupported formats continue through VLC.
    private(set) var visionNativeItem: PlaybackItem?
    /// User-selected interpretation for ambiguous packed video. Automatic
    /// leaves routing to AVFoundation's metadata inspection.
    private(set) var visionFormatSelection = VisionFormatSelection()
    private var visionCurrentTime: TimeInterval = 0
    private var visionDuration: TimeInterval?
    private var visionLastSavedTime: TimeInterval?
    private var visionReachedEnd = false
    private var visionResumePositionOverride: Double?
    private var vlcResumePositionOverride: Double?
    #endif

    /// Whether a hosted `VideoPlayer` surface has attached a drawable to the
    /// player. The macOS window and visionOS cover can mount after
    /// `present(_:)`; playback there waits for this so libVLC never creates
    /// its video output without a surface (which plays audio but no video).
    private var surfaceReady = false
    /// A presented item is holding its playback start for `surfaceDidAttach`.
    private var awaitingSurface = false

    init(library: LibraryController, watchStore: WatchProgressStore) {
        self.library = library
        self.watchStore = watchStore
    }

    var isPresented: Bool { item != nil }

    // MARK: - Presenting

    /// Plays a transient file received through Finder or Files without adding
    /// it to the local library.
    func play(fileURL: URL) async {
        let requestID = beginPlaybackRequest()
        let newItem = await library.preparePlayback(fileURL: fileURL)
        await presentPrepared(newItem, requestID: requestID)
    }

    func play(movie: Movie) async {
        let requestID = beginPlaybackRequest()
        let newItem = await library.preparePlayback(movie: movie)
        await presentPrepared(newItem, requestID: requestID)
    }

    func play(episode: Episode) async {
        let requestID = beginPlaybackRequest()
        let newItem = await library.preparePlayback(episode: episode)
        await presentPrepared(newItem, requestID: requestID)
    }

    /// Plays another file covered by the current item's security scope —
    /// used by the file-list sidebar for folder siblings.
    func play(siblingURL: URL) {
        guard let scope = item?.scope else { return }
        let requestID = beginPlaybackRequest()
        let newItem = PlaybackItem(scope: scope.sibling(playURL: siblingURL))
        Task { await presentPrepared(newItem, requestID: requestID) }
    }

    private func beginPlaybackRequest() -> UUID {
        let requestID = UUID()
        activePlaybackRequestID = requestID
        #if os(visionOS)
        visionFormatSelection = VisionFormatSelection()
        visionResumePositionOverride = nil
        vlcResumePositionOverride = nil
        #endif
        return requestID
    }

    private func presentPrepared(_ newItem: PlaybackItem, requestID: UUID) async {
        guard activePlaybackRequestID == requestID else { return }

        #if os(visionOS)
        if newItem.url != nil,
           let inspection = try? await VisionMediaInspector.inspect(newItem),
           inspection.prefersNativeAVKitPlayback {
            guard activePlaybackRequestID == requestID else { return }
            presentNativeVisionItem(newItem)
            return
        }
        #endif

        guard activePlaybackRequestID == requestID else { return }
        present(newItem)
    }

    func present(_ newItem: PlaybackItem) {
        #if os(visionOS)
        if visionNativeItem != nil {
            saveVisionProgress(completed: visionReachedEnd)
            resetVisionPlaybackState()
        }
        #endif

        let player = self.player ?? Player()
        self.player = player
        let chrome = self.chrome ?? PlayerChromeModel(session: self, watchStore: self.watchStore)
        self.chrome = chrome

        // Assign after chrome exists so hosts observing `item` render a
        // fully-formed session.
        item = newItem

        guard newItem.url != nil else { return }
        #if os(iOS) || os(macOS) || os(visionOS)
        // The host may still be presenting; hold playback until its video
        // surface attaches (see `surfaceDidAttach`). Item switches inside an
        // already-presented host start straight away.
        if surfaceReady {
            startPlayback()
        } else {
            awaitingSurface = true
        }
        #else
        startPlayback()
        #endif
        setIdleTimerDisabled(true)
    }

    /// Reported by the hosting scene's `VideoPlayer` once its surface has
    /// handed libVLC a drawable; starts any playback waiting on it.
    func surfaceDidAttach() {
        surfaceReady = true
        guard awaitingSurface else { return }
        awaitingSurface = false
        startPlayback()
    }

    private func startPlayback() {
        guard let player, let chrome, let url = item?.url else { return }
        do {
            try player.play(url: url)
            #if os(visionOS)
            let resumePosition = vlcResumePositionOverride
            vlcResumePositionOverride = nil
            chrome.playbackDidStart(resumePosition: resumePosition)
            #else
            chrome.playbackDidStart()
            #endif
        } catch {
            item = PlaybackItem(failed: error.localizedDescription)
        }
        startEventLoop()
    }

    /// Ends the session: stops playback, releases the scoped file access,
    /// and dismisses the player on every platform (hosts observe `item`).
    func end() {
        activePlaybackRequestID = UUID()

        #if os(visionOS)
        if visionNativeItem != nil {
            saveVisionProgress(completed: visionReachedEnd)
            resetVisionPlaybackState()
        }
        #endif

        eventTask?.cancel()
        eventTask = nil
        chrome?.sessionWillEnd()
        player?.stop()
        player = nil
        chrome = nil
        item = nil
        surfaceReady = false
        awaitingSurface = false
        #if os(visionOS)
        visionFormatSelection = VisionFormatSelection()
        visionResumePositionOverride = nil
        vlcResumePositionOverride = nil
        #endif
        setIdleTimerDisabled(false)
    }

    #if os(visionOS)
    private func presentNativeVisionItem(
        _ newItem: PlaybackItem,
        initialPosition: Double? = nil
    ) {
        if visionNativeItem != nil {
            saveVisionProgress(completed: visionReachedEnd)
        }

        eventTask?.cancel()
        eventTask = nil
        chrome?.sessionWillEnd()
        player?.stop()
        player = nil
        chrome = nil
        surfaceReady = false
        awaitingSurface = false

        visionCurrentTime = 0
        visionDuration = nil
        visionLastSavedTime = nil
        visionReachedEnd = false
        visionResumePositionOverride = initialPosition
        visionNativeItem = newItem
        item = newItem
        setIdleTimerDisabled(true)
    }

    func visionInitialPosition(for playbackItem: PlaybackItem) -> Double? {
        if let visionResumePositionOverride {
            return visionResumePositionOverride
        }
        guard let key = progressKey(for: playbackItem),
              let progress = watchStore.progress(for: key.id, mediaType: key.type),
              !progress.isCompleted,
              progress.position > 0,
              progress.position < 1
        else { return nil }
        return progress.position
    }

    func handleVisionPlayerEvent(_ event: VisionAVPlayerEvent, itemID: UUID) {
        guard visionNativeItem?.id == itemID else { return }

        switch event {
        case .ready(let duration):
            visionDuration = duration

        case .started:
            visionResumePositionOverride = nil

        case .progress(let currentTime, let duration):
            visionCurrentTime = currentTime
            if let duration { visionDuration = duration }

            if visionLastSavedTime == nil
                || abs(currentTime - visionLastSavedTime!) >= 5 {
                visionLastSavedTime = currentTime
                saveVisionProgress(completed: false)
            }

        case .ended:
            visionReachedEnd = true
            if let visionDuration { visionCurrentTime = visionDuration }
            saveVisionProgress(completed: true)
            end()

        case .dismissalRequested:
            end()

        case .failed(let failure):
            if visionForcedLayout != nil, let playbackItem = visionNativeItem {
                let position = currentVisionPosition
                visionFormatSelection = VisionFormatSelection(preset: .twoDimensional)
                vlcResumePositionOverride = position
                present(playbackItem)
            } else {
                present(PlaybackItem(failed: failure.localizedDescription))
            }

        case .stopped:
            break
        }
    }

    private func saveVisionProgress(completed: Bool) {
        guard let playbackItem = visionNativeItem,
              let duration = visionDuration,
              duration > 0,
              let key = progressKey(for: playbackItem)
        else { return }

        let position = completed
            ? 1
            : min(max(visionCurrentTime / duration, 0), 1)
        guard completed || position > 0 else { return }

        let progress = WatchProgress(
            tmdbId: key.id,
            mediaType: key.type,
            position: position,
            watchedSeconds: min(visionCurrentTime, duration),
            isCompleted: completed,
            showTmdbId: playbackItem.episode?.show?.tmdbId,
            seasonNumber: playbackItem.episode?.seasonNumber,
            episodeNumber: playbackItem.episode?.episodeNumber,
            lastWatchedAt: Date()
        )
        watchStore.update(progress)
    }

    private func progressKey(for playbackItem: PlaybackItem) -> (
        id: Int,
        type: WatchMediaType
    )? {
        if let tmdbId = playbackItem.movie?.tmdbId {
            return (tmdbId, .movie)
        }
        if let tmdbId = playbackItem.episode?.tmdbId {
            return (tmdbId, .episode)
        }
        return nil
    }

    private func resetVisionPlaybackState() {
        visionNativeItem = nil
        visionCurrentTime = 0
        visionDuration = nil
        visionLastSavedTime = nil
        visionReachedEnd = false
    }

    /// The explicit packed layout applied by the visionOS 26 compositor.
    /// Automatic/native metadata and forced 2D both leave this nil.
    var visionForcedLayout: VisionFormatLayout? {
        guard let layout = visionFormatSelection.resolvedLayout,
              layout.packing != .none
        else { return nil }
        return layout
    }

    /// Changes the high-level media interpretation without dismissing the
    /// player. Preset changes intentionally clear advanced overrides.
    func selectVisionFormat(_ preset: VisionFormatPreset) {
        guard item != nil, visionFormatSelection.preset != preset
                || visionFormatSelection.packingOverride != nil
                || visionFormatSelection.projectionOverride != nil
                || visionFormatSelection.eyeOrderOverride != nil
        else { return }

        // Keep the active forced layout in place while metadata inspection is
        // asynchronous. Clearing it first would briefly reattach the untagged
        // asset to AVKit and could fail before routing reaches VLC.
        if preset == .automatic {
            selectAutomaticVisionFormat()
            return
        }

        visionFormatSelection = VisionFormatSelection(preset: preset)
        rerouteCurrentVisionItem()
    }

    func setVisionPacking(_ packing: VisionFramePacking) {
        guard item != nil, visionFormatSelection.packingOverride != packing else { return }
        visionFormatSelection.packingOverride = packing
        rerouteCurrentVisionItem()
    }

    func setVisionProjection(_ projection: VisionVideoProjection) {
        guard item != nil, visionFormatSelection.projectionOverride != projection else { return }
        visionFormatSelection.projectionOverride = projection
        rerouteCurrentVisionItem()
    }

    func setVisionEyeOrder(_ order: VisionEyeOrder) {
        guard item != nil, visionFormatSelection.eyeOrderOverride != order else { return }
        visionFormatSelection.eyeOrderOverride = order
        rerouteCurrentVisionItem()
    }

    private func rerouteCurrentVisionItem() {
        guard let playbackItem = item else { return }
        activePlaybackRequestID = UUID()
        let position = visionNativeItem != nil
            ? currentVisionPosition
            : normalizedVLCPosition

        guard visionForcedLayout != nil else {
            if visionNativeItem != nil {
                vlcResumePositionOverride = position
                present(playbackItem)
            }
            return
        }

        guard #available(visionOS 26.0, *) else {
            if visionNativeItem != nil {
                vlcResumePositionOverride = position
                present(playbackItem)
            }
            return
        }

        presentNativeVisionItem(playbackItem, initialPosition: position)
    }

    private func selectAutomaticVisionFormat() {
        guard let playbackItem = item else { return }
        let startingPosition = visionNativeItem != nil
            ? currentVisionPosition
            : normalizedVLCPosition
        let requestID = UUID()
        activePlaybackRequestID = requestID

        Task { [weak self] in
            let prefersNative = (try? await VisionMediaInspector.inspect(playbackItem))?
                .prefersNativeAVKitPlayback ?? false
            guard let self,
                  self.activePlaybackRequestID == requestID,
                  self.item?.id == playbackItem.id
            else { return }

            let latestPosition = self.visionNativeItem != nil
                ? (self.currentVisionPosition ?? startingPosition)
                : (self.normalizedVLCPosition ?? startingPosition)
            self.visionFormatSelection = VisionFormatSelection()

            if prefersNative {
                self.presentNativeVisionItem(
                    playbackItem,
                    initialPosition: latestPosition
                )
            } else if self.visionNativeItem != nil {
                self.vlcResumePositionOverride = latestPosition
                self.present(playbackItem)
            }
        }
    }

    private var currentVisionPosition: Double? {
        if let visionResumePositionOverride {
            return visionResumePositionOverride
        }
        guard let visionDuration, visionDuration > 0 else { return nil }
        let position = visionCurrentTime / visionDuration
        return position > 0 && position < 1 ? position : nil
    }

    private var normalizedVLCPosition: Double? {
        guard let player else { return nil }
        let position = Double(player.position)
        return position > 0 && position < 1 ? position : nil
    }
    #endif

    /// Keeps the screen awake while a session is active (UIKit platforms).
    private func setIdleTimerDisabled(_ disabled: Bool) {
        #if canImport(UIKit)
        UIApplication.shared.isIdleTimerDisabled = disabled
        #endif
    }

    // MARK: - Event handling

    /// Watches the player's event stream for end-of-media so loop and
    /// auto-exit behave the same on every platform.
    private func startEventLoop() {
        eventTask?.cancel()
        guard let player else { return }
        let events = player.events
        eventTask = Task { [weak self] in
            for await event in events {
                guard let self, let chrome = self.chrome else { return }
                switch event {
                case .stateChanged(.stopped), .mediaStopping:
                    guard chrome.reachedEndNaturally else { continue }
                    if chrome.loopEnabled {
                        self.replayCurrent()
                    } else {
                        self.end()
                    }
                case .timeChanged(let time):
                    chrome.playbackTimeChanged(time)
                default:
                    break
                }
            }
        }
    }

    private func replayCurrent() {
        guard let player, let url = item?.url else { return }
        try? player.play(url: url)
        chrome?.playbackDidStart(resuming: false)
    }
}
