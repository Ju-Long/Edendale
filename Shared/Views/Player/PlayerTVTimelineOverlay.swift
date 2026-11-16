//
//  PlayerTVTimelineOverlay.swift
//  Edendale
//
//  Lightweight, non-focusable tvOS timeline. The full custom controls
//  overlay is intentionally not mounted on Apple TV because its hidden
//  focus catcher renders as an opaque white surface.
//

#if os(tvOS)
import SwiftUI

struct PlayerTVTimelineOverlay: View {
    let chrome: PlayerChromeModel
    let player: PlaybackEngine

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var progress: Double {
        let value = chrome.isScrubbing ? chrome.scrubPosition : player.position
        return min(max(value, 0), 1)
    }

    private var displayedSeconds: Double {
        if chrome.isScrubbing, let duration = player.duration {
            return chrome.scrubPosition * duration.playbackSeconds
        }
        return player.currentTime.playbackSeconds
    }

    var body: some View {
        VStack(spacing: 0) {
            Spacer()

            HStack(spacing: 24) {
                Text(PlayerLogic.timestamp(displayedSeconds))
                    .foregroundStyle(Theme.textPrimary)

                GeometryReader { proxy in
                    ZStack(alignment: .leading) {
                        Capsule()
                            .fill(Theme.surfaceHigh)
                        Capsule()
                            .fill(Theme.gold)
                            .frame(width: progress * proxy.size.width)
                        playhead
                            .position(
                                x: progress * proxy.size.width,
                                y: proxy.size.height / 2
                            )
                    }
                }
                .frame(height: 8)

                Text(PlayerLogic.timestamp(player.duration ?? .zero))
                    .foregroundStyle(Theme.textSecondary)
            }
            .font(Typography.titleLG)
            .monospacedDigit()
            .padding(.horizontal, 80)
            .padding(.bottom, 64)
            .padding(.top, 100)
            .background {
                LinearGradient(
                    colors: [.clear, Theme.background],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea()
            }
        }
        .allowsHitTesting(false)
        // Elapsed, bar, and duration are one read-out that changes as the
        // remote seeks.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Timeline")
        .accessibilityValue(
            String(
                localized: "\(PlayerLogic.timestamp(displayedSeconds)) of \(PlayerLogic.timestamp(player.duration ?? .zero))"
            )
        )
        .accessibilityAddTraits(.updatesFrequently)
    }

    /// The playhead circle. During a jog it stretches into a capsule whose
    /// chevrons point the way it's winding, one to three for the speed.
    private var playhead: some View {
        let jog = chrome.jog
        let tier = jog?.speedTier ?? 0
        return ZStack {
            Capsule()
                .fill(Theme.gold)
            if let jog {
                HStack(spacing: -6) {
                    ForEach(0..<tier, id: \.self) { _ in
                        Image(jog.forward ? .chevronRight : .chevronLeft)
                    }
                }
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(Theme.background)
                .transition(.opacity)
            }
        }
        .frame(width: jog == nil ? 18 : 40 + CGFloat(tier) * 24, height: jog == nil ? 18 : 32)
        .shadow(color: .black.opacity(0.35), radius: 6, y: 2)
        .animation(reduceMotion ? nil : .spring(duration: 0.25, bounce: 0.2), value: jog)
    }
}
#endif
