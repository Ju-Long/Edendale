//
//  PlayerGestureLayer.swift
//  Edendale
//
//  Touch-first hidden controls (iOS/iPadOS, and visionOS where they make
//  sense; tvOS has none of these):
//    - single tap            show/hide the controls
//    - double tap L / R      seek −10s / +10s
//    - double tap center     play/pause
//    - vertical swipe L / R  brightness (iOS only) / player volume
//    - press-and-hold L / R  0.5× / 1.5× until released
//    - press-and-hold + horizontal drag   scrub the timeline
//

#if os(iOS) || os(visionOS)
import SwiftUI
import SwiftVLC

struct PlayerGestureLayer: View {
    let chrome: PlayerChromeModel
    let player: Player

    private enum Side { case left, right }

    private enum Mode: Equatable {
        case idle
        /// Touch down; waiting to become a swipe, a hold, or just a tap.
        case pending
        case verticalAdjust(side: Side, baseline: Double)
        case holdSpeed
        case scrub
        /// Recognized as something we don't handle (e.g. stray horizontal
        /// drag without a hold).
        case ignored
    }

    @State private var mode: Mode = .idle
    @State private var latestTranslation: CGSize = .zero
    @State private var holdTask: Task<Void, Never>?
    /// Timeline position when a hold-drag scrub began.
    @State private var scrubBase: Double = 0

    /// Movement below this is still a "hold", above it a swipe.
    private static let swipeThreshold: CGFloat = 14
    /// Horizontal travel that turns a hold into timeline scrubbing.
    private static let scrubThreshold: CGFloat = 30
    /// Vertical points for a full 0→1 sweep of brightness/volume.
    private static let adjustTravel: CGFloat = 280

    var body: some View {
        GeometryReader { geo in
            HStack(spacing: 0) {
                tapArea(.left)
                tapArea(.center)
                tapArea(.right)
            }
            .contentShape(Rectangle())
            .simultaneousGesture(trackGesture(size: geo.size))
            // Every control on this layer is an invisible gesture zone, and
            // VoiceOver claims the taps and swipes that drive them. One
            // element for the whole surface, with the gestures restated as
            // actions, is the only way these stay reachable.
            .accessibilityElement()
            .accessibilityLabel("Video")
            .accessibilityHint("Shows or hides the playback controls.")
            .accessibilityAction { chrome.toggleControls() }
            .accessibilityActions {
                Button(player.isPlaying ? String(localized: "Pause") : String(localized: "Play")) {
                    chrome.togglePlayPause()
                }
                Button(String(localized: "Back 10 seconds")) { chrome.seek(bySeconds: -10) }
                Button(String(localized: "Forward 10 seconds")) { chrome.seek(bySeconds: 10) }
            }
        }
    }

    // MARK: - Taps

    /// The three equal-width tap columns. `Side` stays two-valued for the
    /// drag state machine (brightness/volume/hold-speed still split at the
    /// midline); tap zones are their own thing.
    private enum TapZone { case left, center, right }

    private func tapArea(_ zone: TapZone) -> some View {
        Color.clear
            .contentShape(Rectangle())
            .onTapGesture(count: 2) {
                switch zone {
                case .left: chrome.seek(bySeconds: -10)
                case .center: chrome.togglePlayPause()
                case .right: chrome.seek(bySeconds: 10)
                }
            }
            .onTapGesture {
                chrome.toggleControls()
            }
    }

    // MARK: - Press / swipe state machine

    private func trackGesture(size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                latestTranslation = value.translation
                switch mode {
                case .idle:
                    mode = .pending
                    scheduleHold(startLocation: value.startLocation, width: size.width)
                case .pending:
                    classifyPending(value: value, width: size.width)
                case .verticalAdjust(let side, let baseline):
                    applyVerticalAdjust(side: side, baseline: baseline, translation: value.translation)
                case .holdSpeed:
                    if abs(value.translation.width) > Self.scrubThreshold {
                        chrome.endHoldRate()
                        beginScrub()
                    }
                case .scrub:
                    updateScrub(translation: value.translation, width: size.width)
                case .ignored:
                    break
                }
            }
            .onEnded { _ in
                holdTask?.cancel()
                holdTask = nil
                switch mode {
                case .holdSpeed:
                    chrome.endHoldRate()
                case .scrub:
                    chrome.seek(toPosition: chrome.scrubPosition)
                    chrome.isScrubbing = false
                    chrome.dismissHUD()
                default:
                    break
                }
                mode = .idle
                latestTranslation = .zero
            }
    }

    /// After 0.4s of stillness a touch becomes a press-and-hold: slow-mo on
    /// the left half, speed-up on the right.
    private func scheduleHold(startLocation: CGPoint, width: CGFloat) {
        holdTask?.cancel()
        holdTask = Task {
            try? await Task.sleep(for: .milliseconds(400))
            guard !Task.isCancelled, mode == .pending else { return }
            let travel = hypot(latestTranslation.width, latestTranslation.height)
            guard travel < Self.swipeThreshold else { return }
            mode = .holdSpeed
            #if os(iOS)
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            #endif
            chrome.beginHoldRate(startLocation.x < width / 2 ? 0.5 : 1.5)
        }
    }

    private func classifyPending(value: DragGesture.Value, width: CGFloat) {
        let dx = value.translation.width
        let dy = value.translation.height
        guard hypot(dx, dy) > Self.swipeThreshold else { return }
        holdTask?.cancel()
        holdTask = nil

        guard abs(dy) > abs(dx) else {
            // Horizontal drags only scrub after a hold — see the spec.
            mode = .ignored
            return
        }

        let side: Side = value.startLocation.x < width / 2 ? .left : .right
        switch side {
        case .left:
            #if os(iOS)
            mode = .verticalAdjust(side: .left, baseline: ScreenBrightness.current)
            #else
            mode = .ignored  // visionOS has no per-app brightness control.
            #endif
        case .right:
            mode = .verticalAdjust(side: .right, baseline: Double(player.volume))
        }
    }

    private func applyVerticalAdjust(side: Side, baseline: Double, translation: CGSize) {
        let delta = Double(-translation.height / Self.adjustTravel)
        let level = min(max(baseline + delta, 0), 1)
        switch side {
        case .left:
            #if os(iOS)
            ScreenBrightness.current = level
            chrome.showHUD(.brightness(level))
            #endif
        case .right:
            chrome.setVolume(Float(level))
        }
    }

    // MARK: - Hold-drag scrubbing

    private func beginScrub() {
        scrubBase = player.position
        chrome.isScrubbing = true
        chrome.scrubPosition = scrubBase
        mode = .scrub
    }

    private func updateScrub(translation: CGSize, width: CGFloat) {
        guard let duration = player.duration else { return }
        let seconds = duration.playbackSeconds
        let target = PlayerLogic.scrubTarget(
            basePosition: scrubBase,
            translation: Double(translation.width),
            width: Double(width),
            durationSeconds: seconds
        )
        chrome.scrubPosition = target
        let offset = (target - scrubBase) * seconds
        chrome.showHUD(
            .scrub(
                target: PlayerLogic.timestamp(target * seconds),
                offset: (offset < 0 ? "−" : "+") + PlayerLogic.timestamp(abs(offset))
            ),
            sticky: true
        )
    }
}

// MARK: - Screen brightness (iOS)

#if os(iOS)
/// Read/write access to the device screen's brightness.
@MainActor
private enum ScreenBrightness {
    static var current: Double {
        get { Double(activeScreen?.brightness ?? 0.5) }
        set { activeScreen?.brightness = CGFloat(newValue) }
    }

    private static var activeScreen: UIScreen? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        return (scenes.first { $0.activationState == .foregroundActive } ?? scenes.first)?.screen
    }
}
#endif
#endif
