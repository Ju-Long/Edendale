//
//  ScreenBrightness.swift
//  Edendale
//
//  The screen brightness the player's brightness controls change: a swipe
//  on the left of the video on iPhone and iPad, and ⌘↑ / ⌘↓ on iPad and Mac.
//  iOS sets the device screen's own brightness. macOS has no public API for
//  a display's brightness, so it dims the display showing the player
//  through its gamma table: 100% is the brightness the Mac is set to, and
//  lower levels darken the whole screen down to a floor that stays readable.
//
//  Like Android's window brightness, the change belongs to the player: the
//  viewer's own brightness comes back when the player closes and whenever
//  Edendale stops being the active app (Apple's Technical Q&A QA1751), and
//  the player's level returns with the app unless the viewer changed the
//  brightness elsewhere in the meantime.
//

#if os(iOS) || os(macOS)
#if os(macOS)
import AppKit
import CoreGraphics
#else
import UIKit
#endif

@MainActor
enum ScreenBrightness {
    /// The level the player's brightness controls start from, 0...1.
    static var level: Double {
        #if os(macOS)
        appliedLevel ?? 1
        #else
        Double(activeScreen?.brightness ?? 0.5)
        #endif
    }

    /// Changes the screen's brightness for the player. Returns false when
    /// this screen's brightness can't be changed.
    @discardableResult
    static func set(_ level: Double) -> Bool {
        let level = min(max(level, 0), 1)
        observeActivation()
        #if os(macOS)
        guard let display = playerDisplay else { return false }
        // The player moved to another display: give the old one back.
        for (other, table) in originalTables where other != display {
            table.apply(to: other, scale: 1)
            originalTables[other] = nil
        }
        if originalTables[display] == nil {
            guard let table = GammaTable(display: display) else { return false }
            originalTables[display] = table
        }
        guard originalTables[display]?.apply(to: display, scale: dimmingScale(for: level)) == true else {
            originalTables[display] = nil
            return false
        }
        appliedLevel = level
        isSuspended = false
        return true
        #else
        guard let screen = activeScreen else { return false }
        if original == nil || adjustedScreen !== screen {
            restore()
            original = screen.brightness
            adjustedScreen = screen
        }
        screen.brightness = CGFloat(level)
        appliedLevel = CGFloat(level)
        isSuspended = false
        return true
        #endif
    }

    /// Puts back the brightness the viewer had before the player changed it.
    static func restore() {
        #if os(macOS)
        for (display, table) in originalTables {
            table.apply(to: display, scale: 1)
        }
        originalTables = [:]
        #else
        if let original, let adjustedScreen, !isSuspended {
            adjustedScreen.brightness = original
        }
        original = nil
        adjustedScreen = nil
        #endif
        appliedLevel = nil
        isSuspended = false
    }

    // MARK: - Leaving and returning to the app

    /// The player's level is set aside while the app is inactive.
    private static var isSuspended = false
    private static var activationObservers: [NSObjectProtocol] = []

    private static func observeActivation() {
        guard activationObservers.isEmpty else { return }
        let center = NotificationCenter.default
        #if os(macOS)
        let resign = NSApplication.didResignActiveNotification
        let become = NSApplication.didBecomeActiveNotification
        #else
        let resign = UIApplication.willResignActiveNotification
        let become = UIApplication.didBecomeActiveNotification
        #endif
        activationObservers = [
            center.addObserver(forName: resign, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { ScreenBrightness.suspend() }
            },
            center.addObserver(forName: become, object: nil, queue: .main) { _ in
                MainActor.assumeIsolated { ScreenBrightness.resume() }
            },
        ]
    }

    private static func suspend() {
        guard appliedLevel != nil, !isSuspended else { return }
        #if os(macOS)
        for (display, table) in originalTables {
            table.apply(to: display, scale: 1)
        }
        #else
        if let original, let adjustedScreen {
            adjustedScreen.brightness = original
        }
        #endif
        isSuspended = true
    }

    private static func resume() {
        guard isSuspended, let appliedLevel else { return }
        isSuspended = false
        #if os(macOS)
        for (display, table) in originalTables {
            table.apply(to: display, scale: dimmingScale(for: appliedLevel))
        }
        #else
        guard let original, let adjustedScreen else { return }
        // A brightness the viewer chose in Control Center or Settings wins.
        guard abs(adjustedScreen.brightness - original) < 0.01 else {
            self.original = nil
            self.adjustedScreen = nil
            self.appliedLevel = nil
            return
        }
        adjustedScreen.brightness = appliedLevel
        #endif
    }

    // MARK: - iOS

    #if os(iOS)
    private static var appliedLevel: CGFloat?
    private static var original: CGFloat?
    private static weak var adjustedScreen: UIScreen?

    /// The screen of the scene the viewer is using.
    private static var activeScreen: UIScreen? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let active = scenes.filter { $0.activationState == .foregroundActive }
        let scene = active.first { $0.windows.contains(where: \.isKeyWindow) } ?? active.first ?? scenes.first
        return scene?.screen
    }
    #endif

    // MARK: - macOS

    #if os(macOS)
    private static var appliedLevel: Double?
    /// Each dimmed display's own table, put back when the player is done.
    private static var originalTables: [CGDirectDisplayID: GammaTable] = [:]

    /// The display that shows the player window.
    private static var playerDisplay: CGDirectDisplayID? {
        let screen = NSApp.keyWindow?.screen ?? NSApp.mainWindow?.screen ?? NSScreen.main
        let number = screen?.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber
        return number.map { CGDirectDisplayID($0.uint32Value) }
    }

    /// How far the gamma table is scaled for a level: 0% keeps 15% of the
    /// light, so the screen never goes black.
    static func dimmingScale(for level: Double) -> Float {
        let floor = 0.15
        return Float(floor + (1 - floor) * min(max(level, 0), 1))
    }

    /// A display's gamma ramps as they were before the player dimmed it.
    private struct GammaTable {
        let red: [CGGammaValue]
        let green: [CGGammaValue]
        let blue: [CGGammaValue]

        init?(display: CGDirectDisplayID) {
            let capacity = CGDisplayGammaTableCapacity(display)
            guard capacity > 0 else { return nil }
            var red = [CGGammaValue](repeating: 0, count: Int(capacity))
            var green = red
            var blue = red
            var count: UInt32 = 0
            guard CGGetDisplayTransferByTable(display, capacity, &red, &green, &blue, &count) == .success,
                  count > 0 else { return nil }
            self.red = Array(red.prefix(Int(count)))
            self.green = Array(green.prefix(Int(count)))
            self.blue = Array(blue.prefix(Int(count)))
        }

        @discardableResult
        func apply(to display: CGDirectDisplayID, scale: Float) -> Bool {
            let result = CGSetDisplayTransferByTable(
                display,
                UInt32(red.count),
                red.map { $0 * scale },
                green.map { $0 * scale },
                blue.map { $0 * scale }
            )
            if result != .success {
                debugPrint("[ScreenBrightness] gamma table refused for display \(display): \(result.rawValue)")
            }
            return result == .success
        }
    }
    #endif
}
#endif
