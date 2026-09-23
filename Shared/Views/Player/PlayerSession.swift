//
//  PlayerSession.swift
//  Edendale
//
//  App-level playback coordinator. Owns the `PlaybackEngine`, the current
//  `PlaybackItem`, and the chrome state for the active session, so every
//  platform host presents the same player:
//    - iOS/iPadOS/visionOS/tvOS: full-screen cover over RootView
//    - macOS: a dedicated resizable "Now Playing" window scene
//

import Foundation
import CoreMedia
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

    /// Observable playback engine for the session. Created on first present,
    /// reused across media switches to preserve PiP, video surface, and
    /// drawable bindings. Media switches stop the old media (awaiting
    /// completion) before loading new media, so delayed old-media events
    /// never corrupt transport state.
    private(set) var player: PlaybackEngine?

    /// Chrome (controls) state for the session.
    private(set) var chrome: PlayerChromeModel?

    let segmentSkipping: PlayerSegmentController

    private let library: LibraryController
    private let watchStore: WatchProgressStore
    private let audioEnhancement: AudioEnhancementController
    private let videoAdjustment: VideoAdjustmentController
    /// Backs persisted player preferences (loop, fill, per-content choices).
    private let defaults: UserDefaults
    private let preferencesStore: PlayerPreferencesStore
    private let nowPlayingBridge = NowPlayingBridge()
    #if !os(macOS)
    private let audioSessionManager = AudioSessionManager()
    #endif
    private var activePlaybackRequestID = UUID()
    /// Keeps the previous item's security scope alive until the next
    /// file starts playing, so the outgoing media's native resources
    /// (decode thread, file handles) are not revoked mid-teardown.
    private var retainedPreviousScope: PlaybackScope?

    #if os(visionOS)
    /// A spatial or multiview asset routed through the system visionOS
    /// player. Ordinary 2D and unsupported formats continue through the
    /// new pipeline.
    private(set) var visionNativeItem: PlaybackItem?
    /// User-selected interpretation for ambiguous packed video. Automatic
    /// leaves routing to AVFoundation's metadata inspection.
    private(set) var visionFormatSelection = VisionFormatSelection()
    private var visionCurrentTime: TimeInterval = 0
    private var visionDuration: TimeInterval?
    private var visionLastSavedTime: TimeInterval?
    private var visionReachedEnd = false
    private var visionResumePositionOverride: Double?
    private var decoderResumePositionOverride: Double?
    #endif

    /// Whether a hosted `EnhancedVideoPlayer` surface has attached its
    /// MTKView to the session. The macOS window and visionOS cover can
    /// mount after `present(_:)`; playback there waits for this so the
    /// decoder never outputs frames without a render surface.
    private var surfaceReady = false
    /// A presented item is holding its playback start for `surfaceDidAttach`.
    private var awaitingSurface = false
    private var pictureInPictureRestoreCompletion: ((Bool) -> Void)?

    init(
        library: LibraryController,
        watchStore: WatchProgressStore,
        audioEnhancement: AudioEnhancementController? = nil,
        videoAdjustment: VideoAdjustmentController? = nil,
        segmentSkipping: PlayerSegmentController? = nil,
        defaults: UserDefaults? = nil
    ) {
        let defaults = defaults ?? AppIdentifiers.defaults
        self.library = library
        self.watchStore = watchStore
        self.defaults = defaults
        self.preferencesStore = PlayerPreferencesStore(defaults: defaults)
        self.audioEnhancement = audioEnhancement ?? AudioEnhancementController(defaults: defaults)
        self.videoAdjustment = videoAdjustment ?? VideoAdjustmentController(defaults: defaults)
        self.segmentSkipping = segmentSkipping ?? PlayerSegmentController(defaults: defaults)
    }

    var isPresented: Bool { item != nil }
    private(set) var isHiddenForPictureInPicture = false
    var isPlayerPresented: Bool { isPresented && !isHiddenForPictureInPicture }

    func pictureInPictureDidStart() {
        guard isPresented else { return }
        isHiddenForPictureInPicture = true
        chrome?.hideControls()
        setIdleTimerDisabled(false)
    }

    func restoreFromPictureInPicture(completion: ((Bool) -> Void)? = nil) {
        guard isPresented else { completion?(false); return }
        pictureInPictureRestoreCompletion = completion
        isHiddenForPictureInPicture = false
        setIdleTimerDisabled(true)
        if surfaceReady {
            pictureInPictureRestoreCompletion?(true)
            pictureInPictureRestoreCompletion = nil
        }
    }

    func surfaceDidDetach() {
        surfaceReady = false
    }

    func pictureInPictureDidStop() {
        // Restore requests arrive before didStop. Closing PiP without restoring
        // ends the retained playback session and releases its file access.
        if isHiddenForPictureInPicture { end() }
    }

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

    /// Whether an automatic advance is preparing the next episode.
    /// Scoped to a request generation so a newer manual play wins.
    private var advanceRequestID: UUID?
    private var advanceInFlight: Bool { advanceRequestID != nil }

    private func beginPlaybackRequest() -> UUID {
        let requestID = UUID()
        activePlaybackRequestID = requestID
        advanceRequestID = nil
        #if os(visionOS)
        visionFormatSelection = VisionFormatSelection()
        visionResumePositionOverride = nil
        decoderResumePositionOverride = nil
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
        if isHiddenForPictureInPicture {
            restoreFromPictureInPicture()
            #if os(iOS)
            player?.pipSource.stop()
            #endif
        }
        debugPrint("[PlayerSession.present] called — url=\(newItem.url?.lastPathComponent ?? "nil"), scope=\(newItem.scope == nil ? "nil" : "exists"), error=\(newItem.errorMessage ?? "none")")

        #if os(visionOS)
        if visionNativeItem != nil {
            saveVisionProgress(completed: visionReachedEnd)
            resetVisionPlaybackState()
        }
        #endif

        // Save outgoing progress and per-content preferences before changing item.
        chrome?.saveProgressBeforeSwitch()
        saveContentPreferences()

        let engine = self.player ?? PlaybackEngine()
        let isNewEngine = self.player == nil
        self.player = engine
        #if os(iOS)
        engine.onPictureInPictureStarted = { [weak self] in self?.pictureInPictureDidStart() }
        engine.onPictureInPictureStopped = { [weak self] in self?.pictureInPictureDidStop() }
        engine.pipSource.onRestoreUI = { [weak self] completion in
            guard let self else { completion(false); return }
            self.restoreFromPictureInPicture(completion: completion)
        }
        #endif
        debugPrint("[PlayerSession.present] engine \(isNewEngine ? "CREATED" : "REUSED"), state=\(engine.state)")

        let chrome = self.chrome ?? PlayerChromeModel(
            session: self,
            watchStore: self.watchStore,
            defaults: defaults
        )
        self.chrome = chrome

        let needsStop = engine.state != .idle && engine.state != .stopped
        let generation = activePlaybackRequestID

        // Assign new item after chrome exists so hosts observing `item`
        // render a fully-formed session. Retain old scope alongside.
        let oldScope = item?.scope
        item = newItem

        segmentSkipping.begin(itemID: newItem.id, media: newItem.segmentLookup)

        guard newItem.url != nil else {
            debugPrint("[PlayerSession.present] ❌ url is nil — bailing out")
            return
        }
        setIdleTimerDisabled(true)

        debugPrint("[PlayerSession.present] needsStop=\(needsStop), surfaceReady=\(surfaceReady)")
        if needsStop {
            engine.stop()
            retainedPreviousScope = oldScope
            beginPlaybackOnReady()
        } else {
            retainedPreviousScope = oldScope
            beginPlaybackOnReady()
        }
    }

    /// Starts playback once the engine is idle and the surface is ready.
    /// On platforms that require a drawable, waits for `surfaceDidAttach`.
    private func beginPlaybackOnReady() {
        #if os(iOS) || os(macOS) || os(visionOS)
        debugPrint("[PlayerSession.beginPlaybackOnReady] surfaceReady=\(surfaceReady)")
        if surfaceReady {
            startPlayback()
        } else {
            debugPrint("[PlayerSession.beginPlaybackOnReady] ⏳ waiting for surface attach...")
            awaitingSurface = true
        }
        #else
        startPlayback()
        #endif
    }

    /// Reported by the hosting scene's `EnhancedVideoPlayer` once its Metal
    /// surface is ready; starts any playback waiting on it.
    func surfaceDidAttach() {
        debugPrint("[PlayerSession.surfaceDidAttach] called — awaitingSurface=\(awaitingSurface)")
        surfaceReady = true
        pictureInPictureRestoreCompletion?(true)
        pictureInPictureRestoreCompletion = nil
        guard awaitingSurface else { return }
        awaitingSurface = false
        debugPrint("[PlayerSession.surfaceDidAttach] ▶️ proceeding to startPlayback")
        startPlayback()
    }

    private func startPlayback() {
        guard let engine = player, let chrome, let url = item?.url else {
            debugPrint("[PlayerSession.startPlayback] ❌ guard failed — player=\(player == nil ? "nil" : "exists"), chrome=\(self.chrome == nil ? "nil" : "exists"), url=\(item?.url?.lastPathComponent ?? "nil")")
            return
        }
        debugPrint("[PlayerSession.startPlayback] ▶️ starting — url=\(url.lastPathComponent)")
        let generation = activePlaybackRequestID

        // Wire end-of-media and time callbacks
        engine.onEnded = { [weak self] in
            guard let self,
                  self.activePlaybackRequestID == generation,
                  let chrome = self.chrome
            else { return }
            guard chrome.reachedEndNaturally else { return }
            if chrome.loopEnabled {
                self.replayCurrent()
            } else {
                self.advanceToNextOrEnd()
            }
        }
        engine.onTimeChanged = { [weak self] time in
            guard let self,
                  self.activePlaybackRequestID == generation,
                  let chrome = self.chrome
            else { return }
            chrome.playbackTimeChanged(time)
            self.nowPlayingBridge.updateElapsedTime()
        }
        #if !os(macOS)
        engine.onSystemVolumeChanged = { [weak self] level in
            self?.chrome?.showHUD(.volume(level))
        }
        #endif

        Task {
            do {
                debugPrint("[PlayerSession.startPlayback] opening url: \(url)")
                try await engine.open(url: url)
                guard self.activePlaybackRequestID == generation else {
                    debugPrint("[PlayerSession.startPlayback] ❌ generation mismatch after open")
                    return
                }
                debugPrint("[PlayerSession.startPlayback] ✅ engine.open succeeded — state=\(engine.state), duration=\(engine.duration?.playbackSeconds ?? -1)s")

                if let currentItem = self.item,
                   let prefs = self.preferencesStore.preferences(for: currentItem) {
                    self.preferencesStore.apply(prefs, to: chrome, player: engine)
                }

                videoAdjustment.apply(to: engine)
                audioEnhancement.apply(to: engine)

                #if !os(macOS)
                await audioSessionManager.activate(for: engine)
                guard self.activePlaybackRequestID == generation else {
                    debugPrint("[PlayerSession.startPlayback] ❌ generation mismatch after audio session activation")
                    audioSessionManager.deactivate()
                    return
                }
                #endif
                nowPlayingBridge.attach(
                    to: engine,
                    title: item?.displayTitle,
                    artworkURL: nil
                )

                debugPrint("[PlayerSession.startPlayback] calling engine.play()")
                engine.play()
                debugPrint("[PlayerSession.startPlayback] ✅ engine.play() returned — isPlaying=\(engine.isPlaying), state=\(engine.state)")

                #if os(visionOS)
                let resumePosition = decoderResumePositionOverride
                decoderResumePositionOverride = nil
                chrome.playbackDidStart(resumePosition: resumePosition)
                #else
                chrome.playbackDidStart()
                #endif
            } catch {
                debugPrint("[PlayerSession.startPlayback] ❌ CAUGHT ERROR: \(error)")
                guard self.activePlaybackRequestID == generation else { return }
                self.item = PlaybackItem(failed: error.localizedDescription)
            }
        }
    }

    /// Ends the session: stops playback, releases the scoped file access,
    /// and dismisses the player on every platform (hosts observe `item`).
    func end() {
        pictureInPictureRestoreCompletion?(false)
        pictureInPictureRestoreCompletion = nil
        segmentSkipping.end()
        advanceRequestID = nil
        activePlaybackRequestID = UUID()

        #if os(visionOS)
        if visionNativeItem != nil {
            saveVisionProgress(completed: visionReachedEnd)
            resetVisionPlaybackState()
        }
        #endif

        chrome?.sessionWillEnd()
        saveContentPreferences()
        stopAndRetirePlayer()
        chrome = nil
        item = nil
        isHiddenForPictureInPicture = false
        surfaceReady = false
        awaitingSurface = false
        #if os(visionOS)
        visionFormatSelection = VisionFormatSelection()
        visionResumePositionOverride = nil
        decoderResumePositionOverride = nil
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

        chrome?.sessionWillEnd()
        stopAndRetirePlayer()
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
        // Spatial AVKit presentation does not yet expose a skip action.
        segmentSkipping.begin(itemID: newItem.id, media: nil)
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
            advanceToNextOrEnd()

        case .dismissalRequested:
            end()

        case .failed(let failure):
            if visionForcedLayout != nil, let playbackItem = visionNativeItem {
                let position = currentVisionPosition
                visionFormatSelection = VisionFormatSelection(preset: .twoDimensional)
                decoderResumePositionOverride = position
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
        // asset to AVKit and could fail before routing reaches the decoder.
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
            : normalizedDecoderPosition

        guard visionForcedLayout != nil else {
            if visionNativeItem != nil {
                decoderResumePositionOverride = position
                present(playbackItem)
            }
            return
        }

        guard #available(visionOS 26.0, *) else {
            if visionNativeItem != nil {
                decoderResumePositionOverride = position
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
            : normalizedDecoderPosition
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
                : (self.normalizedDecoderPosition ?? startingPosition)
            self.visionFormatSelection = VisionFormatSelection()

            if prefersNative {
                self.presentNativeVisionItem(
                    playbackItem,
                    initialPosition: latestPosition
                )
            } else if self.visionNativeItem != nil {
                self.decoderResumePositionOverride = latestPosition
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

    private var normalizedDecoderPosition: Double? {
        guard let engine = player else { return nil }
        let position = engine.position
        return position > 0 && position < 1 ? position : nil
    }
    #endif

    /// Keeps the screen awake while a session is active (UIKit platforms).
    private func setIdleTimerDisabled(_ disabled: Bool) {
        #if canImport(UIKit)
        UIApplication.shared.isIdleTimerDisabled = disabled
        #endif
    }

    /// Stops the engine and releases it from the session (setting it nil) so
    /// the next present creates a fresh one. Called only by `end()` and
    /// visionOS routing.
    private func stopAndRetirePlayer() {
        guard let engine = player else { return }
        nowPlayingBridge.detach()
        #if !os(macOS)
        audioSessionManager.deactivate()
        #endif
        videoAdjustment.detach()
        audioEnhancement.detach()
        engine.onEnded = nil
        engine.onTimeChanged = nil
        engine.onSystemVolumeChanged = nil
        engine.close()
        self.player = nil
        retainedPreviousScope = nil
    }

    // MARK: - Episode progression

    /// Manual skip only. Bounded credits seek within the current file; only
    /// a terminal credits range enters the completion/episode transition path.
    func skipCurrentSegment() {
        guard let engine = player, let chrome,
              let action = segmentSkipping.consumeSkip(
                at: engine.currentTime.playbackSeconds,
                duration: engine.duration?.playbackSeconds,
                isSeekable: engine.isSeekable
              )
        else { return }
        switch action {
        case .seek(let target):
            engine.seek(to: .seconds(target))
            // Paused seeks may not emit another native time event. Keep
            // progress and prompt state current if the user closes now.
            chrome.playbackTimeChanged(.seconds(target))
        case .finish:
            if chrome.loopEnabled {
                chrome.saveCompletionProgress()
                replayCurrent()
            } else {
                advanceToNextOrEnd()
            }
        }
    }

    /// Advances to the next locally stored episode or ends the session.
    /// Idempotent: concurrent calls (credits-skip + buffered stop event)
    /// collapse into one transition. The advance request is established
    /// synchronously so a newer manual `play` (which calls
    /// `beginPlaybackRequest`) invalidates it. No further time events
    /// or saves run after this returns — the caller must not continue
    /// processing the old media.
    func advanceToNextOrEnd() {
        guard !advanceInFlight else { return }

        chrome?.saveCompletionProgress()

        guard let currentEpisode = item?.episode,
              let show = currentEpisode.show,
              let next = PlayerLogic.nextEpisode(after: currentEpisode, in: show)
        else {
            end()
            return
        }

        let requestID = beginPlaybackRequest()
        advanceRequestID = requestID

        Task { [weak self] in
            guard let self,
                  self.advanceRequestID == requestID
            else { return }
            let newItem = await self.library.preparePlayback(episode: next)
            guard self.advanceRequestID == requestID else { return }
            await self.presentPrepared(newItem, requestID: requestID)
            // visionOS inspection can suspend before presentation. Keep the
            // advance guarded throughout that work, without clearing a newer
            // request that may have replaced it while we were suspended.
            if self.advanceRequestID == requestID {
                self.advanceRequestID = nil
            }
        }
    }

    private func replayCurrent() {
        guard let engine = player, let url = item?.url else { return }
        if let item { segmentSkipping.begin(itemID: item.id, media: item.segmentLookup) }
        engine.close()

        let generation = activePlaybackRequestID
        engine.onEnded = { [weak self] in
            guard let self,
                  self.activePlaybackRequestID == generation,
                  let chrome = self.chrome
            else { return }
            guard chrome.reachedEndNaturally else { return }
            if chrome.loopEnabled {
                self.replayCurrent()
            } else {
                self.advanceToNextOrEnd()
            }
        }
        engine.onTimeChanged = { [weak self] time in
            guard let self,
                  self.activePlaybackRequestID == generation,
                  let chrome = self.chrome
            else { return }
            chrome.playbackTimeChanged(time)
            self.nowPlayingBridge.updateElapsedTime()
        }

        Task {
            try? await engine.open(url: url)
            guard self.activePlaybackRequestID == generation else { return }

            if let currentItem = self.item,
               let prefs = self.preferencesStore.preferences(for: currentItem) {
                self.preferencesStore.apply(prefs, to: chrome!, player: engine)
            }

            nowPlayingBridge.attach(
                to: engine,
                title: item?.displayTitle,
                artworkURL: nil
            )

            engine.play()
            chrome?.playbackDidStart(resuming: false)
        }
    }

    private func saveContentPreferences() {
        guard let item, let chrome, let player else { return }
        let prefs = preferencesStore.snapshot(chrome: chrome, player: player)
        preferencesStore.save(prefs, for: item)
    }
}
