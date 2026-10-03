//
//  SubtitleAppearance.swift
//  Edendale
//
//  The viewer's look for text subtitles, set in Settings ▸ Subtitles: the
//  typeface, the text colour, and the colour and opacity of the box behind
//  each cue. Device-local, like the other player preferences. The overlay
//  reads it on every frame, so a change applies to the cue on screen.
//  Bitmap (PGS / VobSub) subtitles keep their authored pixels.
//
//  Colours are named presets rather than a free picker: tvOS has no system
//  colour picker, and a fixed palette keeps every choice legible.
//

import Observation
import SwiftUI

/// The subtitle typeface, drawn from the system families every platform
/// ships, so a choice made on one device never falls back on another.
public nonisolated enum SubtitleFontStyle: String, CaseIterable, Identifiable, Sendable {
    case system
    case rounded
    case serif
    case monospaced

    public var id: String { rawValue }
}

nonisolated enum SubtitleTextColor: String, CaseIterable, Identifiable, Sendable {
    /// The archive's parchment, the look subtitles have always had.
    case parchment
    case white
    case yellow
    case cyan
    case green
    case black

    var id: String { rawValue }
}

nonisolated enum SubtitleBackgroundColor: String, CaseIterable, Identifiable, Sendable {
    /// The archive's ink, the look subtitles have always had.
    case ink
    case black
    case charcoal
    case navy
    case white

    var id: String { rawValue }
}

@MainActor
@Observable
final class SubtitleAppearance {
    nonisolated static let fontKey = "subtitles.font"
    nonisolated static let textColorKey = "subtitles.textColor"
    nonisolated static let backgroundColorKey = "subtitles.backgroundColor"
    nonisolated static let backgroundOpacityKey = "subtitles.backgroundOpacity"

    static let defaultFont: SubtitleFontStyle = .system
    static let defaultTextColor: SubtitleTextColor = .parchment
    static let defaultBackgroundColor: SubtitleBackgroundColor = .ink
    static let defaultBackgroundOpacity: Double = 1
    /// Opacity moves in tenths, which is what the tvOS − / + buttons step by.
    static let backgroundOpacityStep: Double = 0.1

    var font: SubtitleFontStyle {
        didSet { defaults.set(font.rawValue, forKey: Self.fontKey) }
    }

    var textColor: SubtitleTextColor {
        didSet { defaults.set(textColor.rawValue, forKey: Self.textColorKey) }
    }

    var backgroundColor: SubtitleBackgroundColor {
        didSet { defaults.set(backgroundColor.rawValue, forKey: Self.backgroundColorKey) }
    }

    /// 0 removes the box entirely; the outline still keeps text legible.
    /// Set through `setBackgroundOpacity(_:)`, which keeps it in range.
    private(set) var backgroundOpacity: Double

    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults? = nil) {
        let defaults = defaults ?? AppIdentifiers.defaults
        self.defaults = defaults
        font = defaults.string(forKey: Self.fontKey)
            .flatMap(SubtitleFontStyle.init(rawValue:)) ?? Self.defaultFont
        textColor = defaults.string(forKey: Self.textColorKey)
            .flatMap(SubtitleTextColor.init(rawValue:)) ?? Self.defaultTextColor
        backgroundColor = defaults.string(forKey: Self.backgroundColorKey)
            .flatMap(SubtitleBackgroundColor.init(rawValue:)) ?? Self.defaultBackgroundColor
        backgroundOpacity = defaults.object(forKey: Self.backgroundOpacityKey) == nil
            ? Self.defaultBackgroundOpacity
            : Self.normalizedOpacity(defaults.double(forKey: Self.backgroundOpacityKey))
    }

    func setBackgroundOpacity(_ opacity: Double) {
        let normalized = Self.normalizedOpacity(opacity)
        backgroundOpacity = normalized
        defaults.set(normalized, forKey: Self.backgroundOpacityKey)
    }

    var isDefault: Bool {
        font == Self.defaultFont
            && textColor == Self.defaultTextColor
            && backgroundColor == Self.defaultBackgroundColor
            && backgroundOpacity == Self.defaultBackgroundOpacity
    }

    func reset() {
        font = Self.defaultFont
        textColor = Self.defaultTextColor
        backgroundColor = Self.defaultBackgroundColor
        setBackgroundOpacity(Self.defaultBackgroundOpacity)
    }

    /// Clamps to 0…1 and snaps to whole percent so stored values stay tidy.
    static func normalizedOpacity(_ opacity: Double) -> Double {
        guard opacity.isFinite else { return defaultBackgroundOpacity }
        return (min(max(opacity, 0), 1) * 100).rounded() / 100
    }
}

// MARK: - Display

extension SubtitleFontStyle {
    var displayName: String {
        switch self {
        case .system: String(localized: "System")
        case .rounded: String(localized: "Rounded")
        case .serif: String(localized: "Serif")
        case .monospaced: String(localized: "Monospaced")
        }
    }
}

extension SubtitleTextColor {
    var displayName: String {
        switch self {
        case .parchment: String(localized: "Parchment")
        case .white: String(localized: "White")
        case .yellow: String(localized: "Yellow")
        case .cyan: String(localized: "Cyan")
        case .green: String(localized: "Green")
        case .black: String(localized: "Black")
        }
    }

    var color: Color {
        switch self {
        case .parchment: Theme.textPrimary
        case .white: .white
        case .yellow: Color(red: 1, green: 0.88, blue: 0.2)
        case .cyan: Color(red: 0.35, green: 0.9, blue: 1)
        case .green: Color(red: 0.45, green: 0.95, blue: 0.45)
        case .black: .black
        }
    }

    /// The stroke drawn around each glyph: dark around light text and light
    /// around dark text, so the text reads even with no box behind it.
    var outlineColor: Color {
        self == .black ? .white : Theme.background
    }
}

extension SubtitleBackgroundColor {
    var displayName: String {
        switch self {
        case .ink: String(localized: "Ink")
        case .black: String(localized: "Black")
        case .charcoal: String(localized: "Charcoal")
        case .navy: String(localized: "Navy")
        case .white: String(localized: "White")
        }
    }

    var color: Color {
        switch self {
        case .ink: Theme.background
        case .black: .black
        case .charcoal: Color(white: 0.22)
        case .navy: Color(red: 0.06, green: 0.1, blue: 0.24)
        case .white: .white
        }
    }
}
