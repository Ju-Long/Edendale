//
//  PlayerDeviceStatus.swift
//  Edendale
//
//  The time and battery level in the player's top bar on an iPhone held
//  sideways. A Face ID iPhone draws no status bar in landscape whatever an
//  app asks for, so the player shows its own while its controls are up,
//  as the status bar does in portrait. An iPhone with a Home button keeps
//  the system's status bar (the app asks for it) and this stays away.
//

import SwiftUI
#if os(iOS)
import UIKit
#endif

/// The rules, free of UIKit so the macOS test host can check them.
enum PlayerDeviceStatus {
    /// Only where the system shows none: a compact height (an iPhone on its
    /// side) and no status-bar inset along the top.
    static func isShown(compactHeight: Bool, topInset: CGFloat) -> Bool {
        compactHeight && topInset < 1
    }

    /// The battery glyph for a level from 0 to 1, or `nil` while the level
    /// is unknown (UIKit reports -1).
    static func batterySymbol(level: Float, isCharging: Bool) -> String? {
        guard level >= 0 else { return nil }
        if isCharging { return "battery.100percent.bolt" }
        switch level {
        case ..<0.13: return "battery.0percent"
        case ..<0.38: return "battery.25percent"
        case ..<0.63: return "battery.50percent"
        case ..<0.88: return "battery.75percent"
        default: return "battery.100percent"
        }
    }
}

#if os(iOS)
/// The clock and battery, styled after the status bar they stand in for.
struct PlayerDeviceStatusView: View {
    private struct Battery: Equatable {
        var level: Float
        var isCharging: Bool

        static var current: Battery {
            let device = UIDevice.current
            return Battery(
                level: device.batteryLevel,
                isCharging: device.batteryState == .charging || device.batteryState == .full
            )
        }
    }

    @State private var battery = Battery(level: -1, isCharging: false)

    var body: some View {
        HStack(spacing: 10) {
            // Follows the device's 12- or 24-hour setting.
            TimelineView(.everyMinute) { context in
                Text(context.date, format: .dateTime.hour().minute())
            }
            if let symbol = PlayerDeviceStatus.batterySymbol(level: battery.level, isCharging: battery.isCharging) {
                HStack(spacing: 4) {
                    Text(percent)
                    Image(systemName: symbol)
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Battery")
                .accessibilityValue(percent)
            }
        }
        .font(Typography.text(15, weight: .semibold))
        .monospacedDigit()
        .foregroundStyle(Theme.textPrimary)
        .allowsHitTesting(false)
        .onAppear {
            UIDevice.current.isBatteryMonitoringEnabled = true
            battery = .current
        }
        .onDisappear { UIDevice.current.isBatteryMonitoringEnabled = false }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.batteryLevelDidChangeNotification)) { _ in
            battery = .current
        }
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.batteryStateDidChangeNotification)) { _ in
            battery = .current
        }
    }

    private var percent: String {
        Double(battery.level).formatted(.percent.precision(.fractionLength(0)))
    }
}
#endif
