//
//  PlayerHUDView.swift
//  Edendale
//
//  Transient gesture feedback pill — volume/brightness level, temporary
//  hold speed, double-tap seeks, and hold-drag scrub position.
//

import SwiftUI

struct PlayerHUDView: View {
    let chrome: PlayerChromeModel

    var body: some View {
        VStack {
            if let hud = chrome.hud {
                pill(for: hud)
                    .transition(.opacity)
            }
            Spacer()
        }
        .padding(.top, 72)
        .allowsHitTesting(false)
        .animation(.easeInOut(duration: 0.15), value: chrome.hud)
    }

    @ViewBuilder
    private func pill(for hud: PlayerChromeModel.HUD) -> some View {
        HStack(spacing: 12) {
            switch hud {
            case .volume(let level):
                caption(String(localized: "Volume"))
                levelBar(Double(level))
            case .mute(let isMuted):
                caption(String(localized: "Audio"))
                value(isMuted ? String(localized: "Muted") : String(localized: "Unmuted"))
            case .brightness(let level):
                caption(String(localized: "Brightness"))
                levelBar(level)
            case .speed(let rate):
                caption(String(localized: "Speed"))
                value(PlayerLogic.rateLabel(rate))
            case .seek(let seconds):
                value(
                    String(localized: "\(abs(seconds))s"),
                    icon: seconds < 0 ? .backward : .forward
                )
            case .scrub(let target, let offset):
                value(target)
                caption(offset)
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
        .glassBackground(in: Capsule())
        // Caption, value, and the unlabeled level bar are one piece of
        // feedback — and the bar's fill is the whole message for volume and
        // brightness, so it has to be spelled out.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(description(for: hud))
        .accessibilityAddTraits(.updatesFrequently)
    }

    /// The pill said in words, including the levels the bar only draws.
    private func description(for hud: PlayerChromeModel.HUD) -> String {
        switch hud {
        case .volume(let level):
            String(localized: "Volume \(percent(Double(level)))")
        case .mute(let isMuted):
            isMuted ? String(localized: "Muted") : String(localized: "Unmuted")
        case .brightness(let level):
            String(localized: "Brightness \(percent(level))")
        case .speed(let rate):
            String(localized: "Speed \(PlayerLogic.rateLabel(rate))")
        case .seek(let seconds):
            seconds < 0
                ? String(localized: "Back \(abs(seconds)) seconds")
                : String(localized: "Forward \(seconds) seconds")
        case .scrub(let target, let offset):
            "\(target), \(offset)"
        }
    }

    private func percent(_ level: Double) -> String {
        min(max(level, 0), 1).formatted(.percent.precision(.fractionLength(0)))
    }

    private func caption(_ text: String) -> some View {
        Text(text).labelCaps()
    }

    private func value(_ text: String, icon: ImageResource? = nil) -> some View {
        HStack(spacing: 12) {
            if let icon {
                Image(icon)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 16, height: 16)
                    .foregroundStyle(Theme.gold)
            }

            Text(text)
                .font(Typography.titleLG)
                .monospacedDigit()
                .foregroundStyle(Theme.textPrimary)
        }
    }

    private func levelBar(_ level: Double) -> some View {
        ZStack(alignment: .leading) {
            Capsule()
                .fill(Theme.surfaceHigh)
            Capsule()
                .fill(Theme.gold)
                .frame(width: max(4, 120 * min(max(level, 0), 1)))
        }
        .frame(width: 120, height: 5)
    }
}
