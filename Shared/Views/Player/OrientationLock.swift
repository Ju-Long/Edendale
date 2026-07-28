//
//  OrientationLock.swift
//  Edendale
//
//  Screen-rotation lock for the player on iOS/iPadOS. The app delegate
//  (see `EdendaleApp`) reports `mask` from
//  `application(_:supportedInterfaceOrientationsFor:)`; locking narrows the
//  mask to the orientation on screen and asks every window to re-evaluate.
//

#if os(iOS)
import UIKit

@MainActor
enum OrientationLock {

    /// Current allowed orientations. `nil` means "not locked" — the system
    /// default from the Info.plist applies.
    private(set) static var mask: UIInterfaceOrientationMask?

    /// What the app delegate should report right now.
    static var effectiveMask: UIInterfaceOrientationMask {
        mask ?? (UIDevice.current.userInterfaceIdiom == .pad ? .all : .allButUpsideDown)
    }

    /// Locks rotation to whatever orientation is currently on screen.
    static func lockToCurrent() {
        let current = activeWindowScene?.interfaceOrientation ?? .portrait
        mask = orientationMask(for: current)
        refreshWindows()
    }

    static func unlock() {
        mask = nil
        refreshWindows()
    }

    private static func orientationMask(for orientation: UIInterfaceOrientation) -> UIInterfaceOrientationMask {
        switch orientation {
        case .portrait: .portrait
        case .portraitUpsideDown: .portraitUpsideDown
        case .landscapeLeft: .landscapeLeft
        case .landscapeRight: .landscapeRight
        case .unknown: .portrait
        @unknown default: .portrait
        }
    }

    private static var activeWindowScene: UIWindowScene? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
    }

    private static func refreshWindows() {
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            // iPad ignores the app-delegate mask for multitasking-capable
            // apps, so re-evaluating the supported orientations alone does
            // nothing there. Requesting the scene geometry directly is what
            // makes the lock stick on a full-screen iPad; iPhone honors the
            // mask either way. Errors (Split View / Stage Manager, where
            // the system owns rotation) are deliberately ignored.
            if let mask {
                scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask))
            }
            for window in scene.windows {
                window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
            }
        }
    }
}
#endif
