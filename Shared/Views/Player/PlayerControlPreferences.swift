//
//  PlayerControlPreferences.swift
//  Edendale
//
//  The viewer's quick-control preferences, set in Settings ▸ App Controls:
//  how far a skip jumps back and forward, and the speeds a press-and-hold
//  on the left or right side of the video engages. Device-local, like the
//  other player preferences. The player reads them at the moment of each
//  gesture, so a change applies without restarting playback.
//

import Foundation
import Observation

/// How far one skip jumps. Each length has matching arrow-rotate glyphs.
nonisolated enum SkipInterval: Int, CaseIterable, Identifiable, Sendable {
    case ten = 10
    case fifteen = 15
    case thirty = 30

    var id: Int { rawValue }
    var seconds: Int { rawValue }
}

/// Which way a skip jumps.
nonisolated enum SkipDirection: Sendable {
    case backward, forward
}

/// The side of the picture a press-and-hold rests on.
nonisolated enum HoldSide: Sendable {
    case left, right
}

@MainActor
@Observable
final class PlayerControlPreferences {
    nonisolated static let skipBackwardKey = "player.skipBackwardSeconds"
    nonisolated static let skipForwardKey = "player.skipForwardSeconds"
    nonisolated static let holdLeftRateKey = "player.holdLeftRate"
    nonisolated static let holdRightRateKey = "player.holdRightRate"

    static let defaultSkipInterval: SkipInterval = .ten
    /// Slow motion on the left and fast-forward on the right until changed.
    static let defaultHoldLeftRate: Float = 0.5
    static let defaultHoldRightRate: Float = 2.0
    /// Hold speeds move in quarter steps across the player's whole speed
    /// range: the familiar 0.25× … 3.00× presets.
    static let holdRateStep: Float = 0.25
    static var holdRateRange: ClosedRange<Float> { PlayerLogic.minRate...PlayerLogic.maxRate }

    var skipBackwardInterval: SkipInterval {
        didSet {
            defaults.set(skipBackwardInterval.rawValue, forKey: Self.skipBackwardKey)
            onSkipIntervalsChange?()
        }
    }

    var skipForwardInterval: SkipInterval {
        didSet {
            defaults.set(skipForwardInterval.rawValue, forKey: Self.skipForwardKey)
            onSkipIntervalsChange?()
        }
    }

    /// Called after either skip length changes, so the system transport
    /// controls can offer the new lengths.
    @ObservationIgnored var onSkipIntervalsChange: (() -> Void)?

    /// Speed while the left side of the video is held. Set through
    /// `setHoldRate(_:for:)`, which keeps it on the stepper's grid.
    private(set) var holdLeftRate: Float
    /// Speed while the right side of the video is held.
    private(set) var holdRightRate: Float

    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults? = nil) {
        let defaults = defaults ?? AppIdentifiers.defaults
        self.defaults = defaults
        skipBackwardInterval = Self.storedInterval(defaults, forKey: Self.skipBackwardKey)
        skipForwardInterval = Self.storedInterval(defaults, forKey: Self.skipForwardKey)
        holdLeftRate = Self.storedRate(
            defaults,
            forKey: Self.holdLeftRateKey,
            fallback: Self.defaultHoldLeftRate
        )
        holdRightRate = Self.storedRate(
            defaults,
            forKey: Self.holdRightRateKey,
            fallback: Self.defaultHoldRightRate
        )
    }

    // MARK: - Skipping

    func skipInterval(for direction: SkipDirection) -> SkipInterval {
        switch direction {
        case .backward: skipBackwardInterval
        case .forward: skipForwardInterval
        }
    }

    /// The signed seek a skip in `direction` performs: negative going back.
    func skipOffset(for direction: SkipDirection) -> Int {
        let seconds = skipInterval(for: direction).seconds
        return direction == .backward ? -seconds : seconds
    }

    // MARK: - Press-and-hold speed

    func holdRate(for side: HoldSide) -> Float {
        switch side {
        case .left: holdLeftRate
        case .right: holdRightRate
        }
    }

    func setHoldRate(_ rate: Float, for side: HoldSide) {
        let normalized = Self.normalizedHoldRate(rate)
        switch side {
        case .left:
            holdLeftRate = normalized
            defaults.set(normalized, forKey: Self.holdLeftRateKey)
        case .right:
            holdRightRate = normalized
            defaults.set(normalized, forKey: Self.holdRightRateKey)
        }
    }

    /// Snaps a rate onto the quarter-step grid inside the supported range.
    static func normalizedHoldRate(_ rate: Float) -> Float {
        guard rate.isFinite else { return holdRateRange.lowerBound }
        let snapped = (rate / holdRateStep).rounded() * holdRateStep
        return min(max(snapped, holdRateRange.lowerBound), holdRateRange.upperBound)
    }

    // MARK: - Stored values

    /// Missing or unrecognized values fall back to the default length.
    private static func storedInterval(_ defaults: UserDefaults, forKey key: String) -> SkipInterval {
        SkipInterval(rawValue: defaults.integer(forKey: key)) ?? defaultSkipInterval
    }

    private static func storedRate(_ defaults: UserDefaults, forKey key: String, fallback: Float) -> Float {
        guard defaults.object(forKey: key) != nil else { return fallback }
        return normalizedHoldRate(defaults.float(forKey: key))
    }
}
