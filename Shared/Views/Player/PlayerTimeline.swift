//
//  PlayerTimeline.swift
//  Edendale
//
//  Bottom progress bar + timeline adjustment, usable on every platform:
//  drag to scrub with touch or mouse; on tvOS focus it and swipe/press
//  left-right to step ±10 seconds.
//

import SwiftUI
import SwiftVLC

struct PlayerTimeline: View {
    let chrome: PlayerChromeModel
    let player: Player

    #if os(tvOS)
    @FocusState private var isFocused: Bool
    #endif

    private var progress: Double {
        let value = chrome.isScrubbing ? chrome.scrubPosition : player.position
        return min(max(value, 0), 1)
    }

    var body: some View {
        GeometryReader { geo in
            let width = geo.size.width

            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Theme.surfaceHigh)
                    .frame(height: trackHeight)

                Capsule()
                    .fill(Theme.gold)
                    .frame(width: max(progress * width, trackHeight), height: trackHeight)

                Circle()
                    .fill(Theme.gold)
                    .frame(width: knobSize, height: knobSize)
                    .offset(x: progress * width - knobSize / 2)
            }
            .frame(height: geo.size.height)
            .contentShape(Rectangle())
            #if !os(tvOS)
            .gesture(scrubGesture(width: width))
            #endif
        }
        .frame(height: hitHeight)
        #if os(tvOS)
        .focusable()
        .focused($isFocused)
        .onMoveCommand { direction in
            switch direction {
            case .left: chrome.seek(bySeconds: -10)
            case .right: chrome.seek(bySeconds: 10)
            // onMoveCommand swallows every direction while focused, so
            // release focus vertically to let it land elsewhere.
            case .up, .down: isFocused = false
            @unknown default: break
            }
            chrome.showControls()
        }
        .scaleEffect(isFocused ? 1.02 : 1, anchor: .center)
        .animation(.easeOut(duration: 0.15), value: isFocused)
        // Gaining focus counts as activity — keep the chrome up while the
        // user is lining up a seek. Reaching the timeline also means focus
        // stepped out of an open side panel, which dismisses it (`closePanel`
        // keeps the chrome up as well).
        .onChange(of: isFocused) { _, focused in
            if focused { chrome.closePanel() }
        }
        #endif
        // The bar is drawn shapes plus a drag gesture, so without this it is
        // not an element at all — seeking would be unreachable off tvOS.
        .accessibilityElement()
        .accessibilityLabel("Timeline")
        .accessibilityValue(valueText)
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: chrome.seek(bySeconds: 10)
            case .decrement: chrome.seek(bySeconds: -10)
            @unknown default: break
            }
            chrome.showControls()
        }
    }

    /// Elapsed of total, e.g. "12:04 of 1:38:20" — the same two numbers the
    /// bottom bar prints either side of the bar.
    private var valueText: String {
        let elapsed = PlayerLogic.timestamp(currentSeconds)
        guard let duration = player.duration, duration.playbackSeconds > 0 else {
            return elapsed
        }
        return String(localized: "\(elapsed) of \(PlayerLogic.timestamp(duration))")
    }

    private var currentSeconds: Double {
        if chrome.isScrubbing, let duration = player.duration {
            return chrome.scrubPosition * duration.playbackSeconds
        }
        return player.currentTime.playbackSeconds
    }

    // MARK: - Metrics

    private var isEngaged: Bool {
        #if os(tvOS)
        isFocused
        #else
        chrome.isScrubbing
        #endif
    }

    private var trackHeight: CGFloat { isEngaged ? 8 : 5 }
    private var knobSize: CGFloat { isEngaged ? 18 : 12 }
    private var hitHeight: CGFloat { 32 }

    // MARK: - Scrubbing

    #if !os(tvOS)
    private func scrubGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                guard width > 0 else { return }
                chrome.isScrubbing = true
                chrome.scrubPosition = min(max(value.location.x / width, 0), 1)
                chrome.showControls()
            }
            .onEnded { _ in
                chrome.seek(toPosition: chrome.scrubPosition)
                chrome.isScrubbing = false
                chrome.showControls()
            }
    }
    #endif
}
