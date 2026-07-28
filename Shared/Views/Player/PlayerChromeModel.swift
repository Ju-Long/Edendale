//
//  PlayerChromeModel.swift
//  Edendale
//
//  Controls-overlay state for one playback session: visibility with the
//  5-second auto-hide, side panels, playback speed (base rate + temporary
//  hold override), loop, auto-skip, and the transient gesture HUD.
//

import Foundation
import SwiftVLC

@MainActor
@Observable
final class PlayerChromeModel {

    // MARK: - Types

    enum SidePanel {
        /// Episode list (metadata) or folder file list (no metadata).
        case playlist
        /// Speed / subtitles / skip / loop / aspect adjustments.
        case settings
    }

    enum HUD: Equatable {
        case volume(Float)          // 0...1
        case mute(Bool)
        case brightness(Double)     // 0...1
        case speed(Float)           // temporary hold-speed rate
        case scrub(target: String, offset: String)
        case seek(by: Int)          // ±seconds from a double-tap
    }

    // MARK: - Visibility

    /// Whether the controls overlay is shown.
    private(set) var controlsVisible = true

    /// Open side panel, if any. Auto-hide pauses while a panel is open.
    var activePanel: SidePanel?

    /// Seconds of inactivity before the controls hide.
    static let autoHideDelay: Duration = .seconds(5)

    private var hideTask: Task<Void, Never>?

    // MARK: - Playback options

    /// User-chosen playback rate (the settings-panel speed). Reapplied
    /// after every media switch.
    private(set) var baseRate: Float = 1.0

    /// Temporary rate while a press-and-hold speed gesture is active.
    private(set) var holdRate: Float?

    var loopEnabled = false

    var skipRecap: Bool = AppIdentifiers.defaults.bool(forKey: DefaultsKey.skipRecap) {
        didSet { AppIdentifiers.defaults.set(skipRecap, forKey: DefaultsKey.skipRecap) }
    }

    var skipCredits: Bool = AppIdentifiers.defaults.bool(forKey: DefaultsKey.skipCredits) {
        didSet { AppIdentifiers.defaults.set(skipCredits, forKey: DefaultsKey.skipCredits) }
    }

    #if os(iOS)
    /// Automatically enter Picture in Picture when the app is backgrounded
    /// mid-playback. Defaults on. The system arms auto-PiP as long as the
    /// player is on screen; the host reads this flag to cancel a window the
    /// system opened on its own when the user has turned the preference off.
    var autoPiP: Bool = AppIdentifiers.defaults.object(forKey: DefaultsKey.autoPiP) as? Bool ?? true {
        didSet { AppIdentifiers.defaults.set(autoPiP, forKey: DefaultsKey.autoPiP) }
    }
    #endif

    /// Fill (crop) vs fit (letterbox) presentation. The host view applies
    /// this as a scale-to-cover transform on the video surface (see
    /// `PlayerScreen`); libVLC's own display-fit override is a no-op on the
    /// drawable vout this app renders into, so we don't route it through the
    /// player's `aspectRatio`.
    var aspectFill = false

    private enum DefaultsKey {
        static let skipRecap = "player.skipRecap"
        static let skipCredits = "player.skipCredits"
        static let autoPiP = "player.autoPiP"
    }

    // MARK: - Transient state

    /// Timeline drag in progress (bottom bar or hold-drag gesture).
    var isScrubbing = false
    /// Preview position (0...1) while scrubbing.
    var scrubPosition: Double = 0

    /// Player-image brightness, normalized to 0...1 for UI and mapped onto
    /// VLC's 0...2 adjustment range. A value of 0.5 is VLC's neutral 1.0.
    private(set) var brightnessLevel: Float = 0.5

    /// Gesture feedback HUD.
    private(set) var hud: HUD?
    private var hudTask: Task<Void, Never>?

    #if os(tvOS)
    /// The remote can reveal a timeline without mounting the full custom
    /// controls overlay (which receives an unwanted white focus plate).
    private(set) var timelineVisible = false
    private var timelineTask: Task<Void, Never>?
    private var remoteSeekTask: Task<Void, Never>?
    private var remoteSeekOriginSeconds: Double?
    private var remoteSeekOffsetSeconds: Double = 0
    #endif

    #if os(iOS)
    /// Whether the interface orientation is locked to its current one.
    private(set) var isOrientationLocked = false
    #endif

    // MARK: - End-of-media bookkeeping

    private var lastKnownTime: Duration = .zero
    /// Duration cached from time events — `player.duration` can reset to nil
    /// once playback stops, exactly when natural-end detection needs it.
    private var lastKnownDuration: Duration?
    private var creditsSkipped = false
    private var awaitingRecapSkip = false

    private unowned let session: PlayerSession
    private let watchStore: WatchProgressStore
    private var lastSavedTime: Duration?
    /// Saved fractional position (0...1) to seek to once the resumed media
    /// reports a duration and becomes seekable. Cleared after it is applied.
    private var pendingResumePosition: Double?

    init(session: PlayerSession, watchStore: WatchProgressStore) {
        self.session = session
        self.watchStore = watchStore
    }

    private var player: Player? { session.player }

    // MARK: - Visibility control

    /// Shows the controls and restarts the auto-hide countdown. Call on any
    /// user activity that should keep the chrome up.
    func showControls() {
        controlsVisible = true
        #if os(tvOS)
        // The full controls carry their own timeline; drop the lightweight
        // remote overlay so a stale 4-second hide can't flash it later.
        hideTimeline()
        #endif
        scheduleAutoHide()
    }

    /// Tap on an empty part of the overlay: hide when visible, show when not.
    func toggleControls() {
        if controlsVisible {
            hideControls()
        } else {
            showControls()
        }
    }

    func hideControls() {
        hideTask?.cancel()
        controlsVisible = false
    }

    /// Auto-hide only makes sense while actively playing with nothing open.
    private var canAutoHide: Bool {
        activePanel == nil
            && !isScrubbing
            && (player?.isPlaying ?? false)
    }

    private func scheduleAutoHide() {
        hideTask?.cancel()
        hideTask = Task { [weak self] in
            try? await Task.sleep(for: Self.autoHideDelay)
            guard let self, !Task.isCancelled, self.canAutoHide else { return }
            self.controlsVisible = false
        }
    }

    func openPanel(_ panel: SidePanel) {
        activePanel = activePanel == panel ? nil : panel
        showControls()
    }

    func closePanel() {
        activePanel = nil
        showControls()
    }

    // MARK: - Transport

    func togglePlayPause() {
        player?.togglePlayPause()
        showControls()
    }

    func seek(bySeconds seconds: Int) {
        player?.seek(by: .seconds(seconds))
        showHUD(.seek(by: seconds))
    }

    /// Commits a timeline position (0...1).
    func seek(toPosition position: Double) {
        player?.position = min(max(position, 0), 1)
    }

    // MARK: - Audio and image levels

    func toggleMute() {
        guard let player else { return }
        player.isMuted.toggle()
        showHUD(.mute(player.isMuted))
    }

    func adjustVolume(by delta: Float) {
        guard let player else { return }
        setVolume(PlayerLogic.adjustedLevel(player.volume, by: delta))
    }

    /// Every volume change explicitly unmutes, including touch gestures.
    func setVolume(_ level: Float) {
        guard let player else { return }
        let normalized = min(max(level, 0), 1)
        player.isMuted = false
        player.volume = normalized
        showHUD(.volume(normalized))
    }

    /// Adjusts the brightness of the rendered video, rather than attempting
    /// to control a system display (which has no public API on macOS or
    /// visionOS). This keeps the keyboard behavior consistent everywhere.
    func adjustBrightness(by delta: Float) {
        brightnessLevel = PlayerLogic.adjustedLevel(brightnessLevel, by: delta)
        applyBrightness()
        showHUD(.brightness(Double(brightnessLevel)))
    }

    private func applyBrightness() {
        player?.withAdjustments { adjustments in
            adjustments.isEnabled = brightnessLevel != 0.5
            adjustments.brightness = brightnessLevel * 2
        }
    }

    // MARK: - Speed

    func increaseRate() { setBaseRate(PlayerLogic.incrementedRate(baseRate)) }
    func decreaseRate() { setBaseRate(PlayerLogic.decrementedRate(baseRate)) }
    func resetRate() { setBaseRate(1.0) }

    func setBaseRate(_ rate: Float) {
        baseRate = PlayerLogic.normalizedRate(rate)
        if holdRate == nil {
            try? player?.setRate(baseRate)
        }
        showControls()
    }

    /// Press-and-hold speed override (0.5× on the left, 1.5× on the right).
    func beginHoldRate(_ rate: Float) {
        holdRate = rate
        try? player?.setRate(rate)
        showHUD(.speed(rate), sticky: true)
    }

    func endHoldRate() {
        guard holdRate != nil else { return }
        holdRate = nil
        try? player?.setRate(baseRate)
        dismissHUD()
    }

    // MARK: - HUD

    /// Shows gesture feedback; non-sticky HUDs fade out shortly after the
    /// last update, sticky ones stay until `dismissHUD()`.
    func showHUD(_ content: HUD, sticky: Bool = false) {
        hud = content
        hudTask?.cancel()
        guard !sticky else { return }
        hudTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            self?.hud = nil
        }
    }

    func dismissHUD() {
        hudTask?.cancel()
        hud = nil
    }

    // MARK: - tvOS remote timeline

    #if os(tvOS)
    /// Accumulates repeated left/right input into one preview. The final
    /// position is committed after a short pause in remote input.
    func remoteSeek(bySeconds seconds: Int) {
        guard let player else { return }
        guard let duration = player.duration else {
            seek(bySeconds: seconds)
            return
        }
        let durationSeconds = duration.playbackSeconds
        guard durationSeconds > 0 else { return }

        if remoteSeekOriginSeconds == nil {
            remoteSeekOriginSeconds = min(
                max(player.currentTime.playbackSeconds, 0),
                durationSeconds
            )
            remoteSeekOffsetSeconds = 0
        }

        remoteSeekOffsetSeconds += Double(seconds)
        let origin = remoteSeekOriginSeconds ?? 0
        scrubPosition = PlayerLogic.seekPosition(
            fromSeconds: origin,
            bySeconds: remoteSeekOffsetSeconds,
            durationSeconds: durationSeconds
        )
        isScrubbing = true
        timelineVisible = true
        timelineTask?.cancel()

        let targetSeconds = scrubPosition * durationSeconds
        let appliedOffset = targetSeconds - origin
        showHUD(
            .scrub(
                target: PlayerLogic.timestamp(targetSeconds),
                offset: (appliedOffset < 0 ? "−" : "+")
                    + PlayerLogic.timestamp(abs(appliedOffset))
            ),
            sticky: true
        )
        scheduleRemoteSeekCommit(durationSeconds: durationSeconds)
    }

    func showTimeline() {
        timelineVisible = true
        scheduleTimelineHide()
    }

    func hideTimeline() {
        timelineTask?.cancel()
        timelineVisible = false
    }

    /// Handles Menu while the lightweight timeline/HUD is presented.
    /// Returns true when something was dismissed instead of exiting.
    func dismissRemotePresentation() -> Bool {
        if isScrubbing {
            cancelRemoteSeek()
            return true
        }
        guard timelineVisible || hud != nil else { return false }
        hideTimeline()
        dismissHUD()
        return true
    }

    private func scheduleRemoteSeekCommit(durationSeconds: Double) {
        remoteSeekTask?.cancel()
        remoteSeekTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(800))
            guard !Task.isCancelled else { return }
            self?.commitRemoteSeek(durationSeconds: durationSeconds)
        }
    }

    private func commitRemoteSeek(durationSeconds: Double) {
        guard isScrubbing, let origin = remoteSeekOriginSeconds else { return }
        let position = scrubPosition
        let targetSeconds = position * durationSeconds
        let appliedOffset = targetSeconds - origin

        seek(toPosition: position)
        resetRemoteSeekState()
        showHUD(
            .scrub(
                target: PlayerLogic.timestamp(targetSeconds),
                offset: (appliedOffset < 0 ? "−" : "+")
                    + PlayerLogic.timestamp(abs(appliedOffset))
            )
        )
        scheduleTimelineHide()
    }

    private func cancelRemoteSeek() {
        remoteSeekTask?.cancel()
        resetRemoteSeekState()
        hideTimeline()
        dismissHUD()
    }

    private func resetRemoteSeekState() {
        isScrubbing = false
        remoteSeekOriginSeconds = nil
        remoteSeekOffsetSeconds = 0
    }

    private func scheduleTimelineHide() {
        timelineTask?.cancel()
        timelineTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled, self?.isScrubbing == false else { return }
            self?.timelineVisible = false
        }
    }
    #endif

    // MARK: - Orientation lock (iOS / iPadOS)

    #if os(iOS)
    func toggleOrientationLock() {
        isOrientationLocked.toggle()
        if isOrientationLocked {
            OrientationLock.lockToCurrent()
        } else {
            OrientationLock.unlock()
        }
        showControls()
    }
    #endif

    // MARK: - Session lifecycle

    /// Called after each `play(url:)`. `resuming` is false for a loop
    /// restart, which must always begin from the top rather than seek back
    /// to the near-end position the just-finished playthrough saved.
    func playbackDidStart(
        resuming: Bool = true,
        resumePosition: Double? = nil
    ) {
        lastKnownTime = .zero
        lastKnownDuration = nil
        creditsSkipped = false
        awaitingRecapSkip = skipRecap
        lastSavedTime = nil
        pendingResumePosition = resumePosition
            ?? (resuming ? savedResumePosition() : nil)
        if holdRate == nil, baseRate != 1.0 {
            try? player?.setRate(baseRate)
        }
        if brightnessLevel != 0.5 {
            applyBrightness()
        }
        showControls()
    }

    func sessionWillEnd() {
        let time = lastKnownTime
        if let duration = lastKnownDuration ?? player?.duration {
            saveProgress(time: time, duration: duration)
        }
        
        hideTask?.cancel()
        hudTask?.cancel()
        #if os(tvOS)
        timelineTask?.cancel()
        remoteSeekTask?.cancel()
        #endif
        #if os(iOS)
        if isOrientationLocked {
            OrientationLock.unlock()
            isOrientationLocked = false
        }
        #endif
    }

    /// Drives auto-skip off the player's time events.
    func playbackTimeChanged(_ time: Duration) {
        guard let player else { return }

        // Resume: seek to the saved stop position before recording anything.
        // Wait until libVLC has parsed a duration AND reports the media as
        // seekable — at the first few time events it often isn't yet, and
        // giving up there would restart playback from the top. Returning
        // early until then also keeps the opening seconds from overwriting
        // the timestamp we're about to seek back to (`lastKnownTime` stays
        // at zero, so a stop mid-wait saves nothing rather than clobbering).
        if let target = pendingResumePosition {
            guard let duration = player.duration, player.isSeekable else {
                // Give up only after playing a while without ever becoming
                // seekable (e.g. a live stream), so saving isn't blocked forever.
                if time > .seconds(15) { pendingResumePosition = nil }
                return
            }
            pendingResumePosition = nil
            awaitingRecapSkip = false
            // Resuming straight into the credits window shouldn't trip
            // auto-skip and bounce the viewer back out immediately.
            if skipCredits,
               let creditsStart = PlayerLogic.creditsStart(duration: duration),
               duration * target >= creditsStart {
                creditsSkipped = true
            }
            lastKnownDuration = duration
            lastKnownTime = duration * target
            lastSavedTime = duration * target
            player.position = target
            return
        }

        lastKnownTime = time
        if let duration = player.duration { lastKnownDuration = duration }

        if awaitingRecapSkip {
            // Wait until duration is known so short files are never skipped.
            if let duration = player.duration {
                awaitingRecapSkip = false
                if let target = PlayerLogic.recapSkipTarget(duration: duration), time < target {
                    player.seek(to: target)
                }
            }
        }

        if skipCredits, !creditsSkipped,
           let creditsStart = PlayerLogic.creditsStart(duration: player.duration),
           time >= creditsStart {
            creditsSkipped = true
            if loopEnabled {
                // Credits are over as far as the viewer cares — restart.
                player.seek(to: .zero)
            } else {
                session.end()
            }
        }

        // Save progress every ~5 seconds.
        if let duration = player.duration {
            if lastSavedTime == nil || abs(time.playbackSeconds - lastSavedTime!.playbackSeconds) > 5.0 {
                lastSavedTime = time
                saveProgress(time: time, duration: duration)
            }
        }
    }

    /// The saved fractional position for the current item when it was left
    /// partway through, so playback can continue where the viewer stopped.
    /// `nil` for un-tracked files, finished items, or ones never started.
    private func savedResumePosition() -> Double? {
        guard let item = session.item else { return nil }
        let key: (id: Int, type: WatchMediaType)?
        if let movie = item.movie, let id = movie.tmdbId {
            key = (id, .movie)
        } else if let episode = item.episode, let id = episode.tmdbId {
            key = (id, .episode)
        } else {
            key = nil
        }
        guard let key,
              let progress = watchStore.progress(for: key.id, mediaType: key.type),
              !progress.isCompleted,
              progress.position > 0, progress.position < 1
        else { return nil }
        return progress.position
    }

    private func saveProgress(time: Duration, duration: Duration) {
        let position = time.playbackSeconds / duration.playbackSeconds
        guard position > 0, position <= 1.0, let item = session.item else { return }

        if let movie = item.movie, let tmdbId = movie.tmdbId {
            let progress = WatchProgress(
                tmdbId: tmdbId,
                mediaType: .movie,
                position: position,
                watchedSeconds: duration.playbackSeconds,
                isCompleted: reachedEndNaturally,
                lastWatchedAt: Date()
            )
            watchStore.update(progress)
        } else if let episode = item.episode, let tmdbId = episode.tmdbId {
            let progress = WatchProgress(
                tmdbId: tmdbId,
                mediaType: .episode,
                position: position,
                watchedSeconds: duration.playbackSeconds,
                isCompleted: reachedEndNaturally,
                showTmdbId: episode.show?.tmdbId,
                seasonNumber: episode.seasonNumber,
                episodeNumber: episode.episodeNumber,
                lastWatchedAt: Date()
            )
            watchStore.update(progress)
        }
    }

    /// Whether the last stop happened at (or near) the end of the media,
    /// as opposed to a user-initiated stop.
    var reachedEndNaturally: Bool {
        PlayerLogic.isNaturalEnd(
            time: lastKnownTime,
            duration: player?.duration ?? lastKnownDuration
        )
    }
}
