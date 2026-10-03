//
//  PlayerGuide.swift
//  Edendale
//
//  What the player guide teaches: one page per way of controlling playback,
//  chosen for the platform's input (touch, pointer and keyboard, or the
//  Siri Remote) and worded with the viewer's own skip lengths and hold
//  speeds from Settings ▸ App Controls. The guide opens over the video on
//  a viewer's first playback on the device, and again on request from the
//  Adjustments panel or Settings (see PlayerGuideView).
//

import Foundation
import SwiftUI

/// What a guide page demonstrates. Each has its own looping illustration.
nonisolated enum PlayerGuideTopic: String, CaseIterable, Identifiable, Sendable {
    /// Tap or click the video, or click the remote, to show the controls.
    case showControls
    /// Double-tap a side of the video, or swipe the remote, to skip.
    case skip
    /// Swipe up or down: brightness on the left, volume on the right.
    case levels
    /// Press and hold a side of the video for that side's speed.
    case holdSpeed
    /// Press and hold, then slide sideways through the timeline.
    case scrub
    /// The player's key commands.
    case keyboard
    /// Play/Pause and Back on the Siri Remote.
    case remoteButtons
    /// The chips along the top of the player.
    case tools

    var id: String { rawValue }
}

/// The input a platform's player is driven with.
nonisolated enum PlayerGuidePlatform: Sendable {
    /// iPhone and iPad.
    case touch
    /// Apple Vision Pro: look and tap, pinch and hold.
    case vision
    /// Pointer and keyboard.
    case mac
    /// Siri Remote.
    case tv

    static var current: PlayerGuidePlatform {
        #if os(macOS)
        .mac
        #elseif os(tvOS)
        .tv
        #elseif os(visionOS)
        .vision
        #else
        .touch
        #endif
    }
}

struct PlayerGuidePage: Identifiable, Equatable {
    let topic: PlayerGuideTopic
    let title: String
    let message: String
    /// Where to change what the page describes, when it is adjustable.
    var footnote: String?

    var id: PlayerGuideTopic { topic }
}

/// One key command on the keyboard page.
struct PlayerGuideShortcut: Identifiable, Equatable {
    /// Keycaps pressed together, as printed on Apple keyboards.
    let keys: [String]
    let action: String

    var id: String { keys.joined(separator: "+") }
}

/// One toolbar chip on the tools page.
struct PlayerGuideTool: Identifiable, Equatable {
    enum Glyph: Equatable {
        case asset(ImageResource)
        /// The system's own route-picker glyph, which the audio chip shows.
        case system(String)
    }

    let glyph: Glyph
    let name: String
    let detail: String

    var id: String { name }
}

enum PlayerGuide {
    /// Raise when the guide gains something worth showing again to viewers
    /// who already closed it.
    static let version = 1
    /// The newest guide version the viewer has closed on this device.
    nonisolated static let seenVersionKey = "player.guideSeenVersion"

    /// Whether the guide still has to open by itself on this device.
    static func isUnseen(in defaults: UserDefaults) -> Bool {
        defaults.integer(forKey: seenVersionKey) < version
    }

    static func markSeen(in defaults: UserDefaults) {
        defaults.set(version, forKey: seenVersionKey)
    }

    // MARK: - Pages

    /// The topics a platform's guide covers, in reading order. Touch
    /// platforms add the keyboard page only while a keyboard is attached.
    static func topics(for platform: PlayerGuidePlatform, hasKeyboard: Bool) -> [PlayerGuideTopic] {
        switch platform {
        case .touch, .vision:
            [.showControls, .skip, .levels, .holdSpeed, .scrub]
                + (hasKeyboard ? [.keyboard] : [])
                + [.tools]
        case .mac:
            [.showControls, .holdSpeed, .keyboard, .tools]
        case .tv:
            [.showControls, .skip, .holdSpeed, .remoteButtons, .tools]
        }
    }

    static func pages(
        for platform: PlayerGuidePlatform,
        controls: PlayerControlPreferences,
        hasKeyboard: Bool
    ) -> [PlayerGuidePage] {
        topics(for: platform, hasKeyboard: hasKeyboard).map {
            page($0, platform: platform, controls: controls)
        }
    }

    static func page(
        _ topic: PlayerGuideTopic,
        platform: PlayerGuidePlatform,
        controls: PlayerControlPreferences
    ) -> PlayerGuidePage {
        let back = controls.skipBackwardInterval.seconds
        let forward = controls.skipForwardInterval.seconds
        let slow = PlayerLogic.rateLabel(controls.holdRate(for: .left))
        let fast = PlayerLogic.rateLabel(controls.holdRate(for: .right))
        let appControls = String(localized: "Change skip lengths and hold speeds in Settings ▸ App Controls.")

        switch (topic, platform) {
        case (.showControls, .mac):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Click to Show Controls"),
                message: String(localized: "Click the video once to show or hide the controls. They fade away on their own after five seconds of playback.")
            )
        case (.showControls, .tv):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Show the Controls"),
                message: String(localized: "Click the clickpad or swipe up to show the controls. Press Back to hide them again.")
            )
        case (.showControls, _):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Tap to Show Controls"),
                message: String(localized: "Tap the video once to show or hide the controls. They fade away on their own after five seconds of playback.")
            )

        case (.skip, .tv):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Swipe to Skip"),
                message: String(localized: "While the controls are hidden, swipe right on the clickpad to jump forward \(forward) seconds, or left to jump back \(back) seconds. Swipe down to see the timeline."),
                footnote: appControls
            )
        case (.skip, .vision):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Tap Twice to Skip"),
                message: String(localized: "Tap the right side twice to jump forward \(forward) seconds, or the left side twice to jump back \(back) seconds. Tap the middle twice to play or pause."),
                footnote: appControls
            )
        case (.skip, _):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Double-Tap to Skip"),
                message: String(localized: "Double-tap the right side to jump forward \(forward) seconds, or the left side to jump back \(back) seconds. Double-tap the middle to play or pause."),
                footnote: appControls
            )

        case (.levels, .vision):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Drag for Volume"),
                message: String(localized: "Pinch and drag up or down on the right side of the video to change the volume.")
            )
        case (.levels, _):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Swipe for Brightness and Volume"),
                message: String(localized: "Swipe up or down on the left side to change the screen brightness, or on the right side to change the volume.")
            )

        case (.holdSpeed, .mac):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Click and Hold for Speed"),
                message: String(localized: "Click and hold the right half of the video to play at \(fast), or the left half to play at \(slow). Playback returns to its usual speed when you release the button."),
                footnote: appControls
            )
        case (.holdSpeed, .tv):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Rest Your Thumb for Speed"),
                message: String(localized: "Rest your thumb on the right edge of the clickpad to play at \(fast), or on the left edge to play at \(slow). Playback returns to its usual speed when you lift it."),
                footnote: appControls
            )
        case (.holdSpeed, .vision):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Hold to Change Speed"),
                message: String(localized: "Pinch and hold the right side to play at \(fast), or the left side to play at \(slow). Playback returns to its usual speed when you let go."),
                footnote: appControls
            )
        case (.holdSpeed, _):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Hold to Change Speed"),
                message: String(localized: "Touch and hold the right side to play at \(fast), or the left side to play at \(slow). Playback returns to its usual speed when you let go."),
                footnote: appControls
            )

        case (.scrub, .vision):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Hold and Slide to Seek"),
                message: String(localized: "Pinch and hold the video, then drag sideways to move through the timeline. Let go to jump to that moment.")
            )
        case (.scrub, _):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Hold and Slide to Seek"),
                message: String(localized: "Touch and hold the video, then slide sideways to move through the timeline. Let go to jump to that moment.")
            )

        case (.keyboard, .mac):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Keyboard Shortcuts"),
                message: String(localized: "These keys work whenever the player window is in front."),
                footnote: appControls
            )
        case (.keyboard, _):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Keyboard Shortcuts"),
                message: String(localized: "With a keyboard connected, these keys control playback."),
                footnote: appControls
            )

        case (.remoteButtons, _):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Play/Pause and Back"),
                message: String(localized: "Press Play/Pause to pause or resume at any time. Back steps out one layer at a time: an open panel, then the controls, then the player.")
            )

        case (.tools, _):
            return PlayerGuidePage(
                topic: topic,
                title: String(localized: "Player Tools"),
                message: String(localized: "These buttons sit at the top of the player. Open this guide again from Adjustments.")
            )
        }
    }

    // MARK: - Keyboard

    /// The key commands PlayerScreen handles, plus the skip prompt's S.
    static func shortcuts(
        for platform: PlayerGuidePlatform,
        controls: PlayerControlPreferences
    ) -> [PlayerGuideShortcut] {
        var shortcuts = [
            PlayerGuideShortcut(keys: [String(localized: "Space")], action: String(localized: "Play or pause")),
            PlayerGuideShortcut(
                keys: ["←"],
                action: String(localized: "Back \(controls.skipBackwardInterval.seconds) seconds")
            ),
            PlayerGuideShortcut(
                keys: ["→"],
                action: String(localized: "Forward \(controls.skipForwardInterval.seconds) seconds")
            ),
            PlayerGuideShortcut(keys: ["↑", "↓"], action: String(localized: "Volume")),
            PlayerGuideShortcut(keys: ["⌘", "↑", "↓"], action: String(localized: "Brightness")),
            PlayerGuideShortcut(keys: ["M"], action: String(localized: "Mute or unmute")),
        ]
        if platform == .mac {
            shortcuts.append(PlayerGuideShortcut(keys: ["F"], action: String(localized: "Enter or exit full screen")))
        }
        shortcuts.append(PlayerGuideShortcut(keys: ["S"], action: String(localized: "Skip an intro or credits when offered")))
        shortcuts.append(PlayerGuideShortcut(
            keys: ["esc"],
            action: platform == .mac
                ? String(localized: "Close a panel, then the player")
                : String(localized: "Close the player")
        ))
        return shortcuts
    }

    // MARK: - Tools

    /// The top-bar chips PlayerControlsOverlay shows on this platform.
    static func tools(for platform: PlayerGuidePlatform) -> [PlayerGuideTool] {
        var tools: [PlayerGuideTool] = []
        if platform == .touch || platform == .mac {
            tools.append(PlayerGuideTool(
                glyph: .asset(.pictureInPicture),
                name: String(localized: "Picture in Picture"),
                detail: String(localized: "Keep watching in a floating window")
            ))
        }
        if platform == .touch {
            tools.append(PlayerGuideTool(
                glyph: .asset(.mobileRotateLock),
                name: String(localized: "Rotation Lock"),
                detail: String(localized: "Hold the screen in its current orientation")
            ))
        }
        tools.append(PlayerGuideTool(
            glyph: .system("airplayaudio"),
            name: String(localized: "Audio Output"),
            detail: String(localized: "Play sound through AirPlay or Bluetooth")
        ))
        tools.append(PlayerGuideTool(
            glyph: .asset(.listTree),
            name: String(localized: "Playlist"),
            detail: String(localized: "Jump to another episode or file")
        ))
        tools.append(PlayerGuideTool(
            glyph: .asset(.sidebarRight),
            name: String(localized: "Adjustments"),
            detail: String(localized: "Speed, tracks, subtitles, and picture")
        ))
        return tools
    }
}
