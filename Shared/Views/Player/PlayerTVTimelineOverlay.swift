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
import SwiftVLC

struct PlayerTVTimelineOverlay: View {
    let chrome: PlayerChromeModel
    let player: Player

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
    }
}
#endif
