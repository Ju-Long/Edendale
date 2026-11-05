//
//  VisionTheaterController.swift
//  Edendale
//
//  State of the visionOS immersive theater: which room is open, and the
//  feed that carries the picture onto its screen.
//

#if os(visionOS)

import SwiftUI
import UIKit

/// The theater is opened only by the viewer, from the player's Theater menu.
/// It shows the picture the 2D player is drawing, so it lives exactly as
/// long as that player; the player window stays open as its remote.
@MainActor
@Observable
final class VisionTheaterController {
    /// The `ImmersiveSpace` scene's identifier.
    static let spaceID = "theater"

    /// The room the space shows, or is opening with. Choosing another one
    /// while it is open swaps the room in place.
    private(set) var environment: TheaterEnvironment?

    /// The immersive space is on screen.
    private(set) var isImmersed = false

    /// An open request is in flight, so a second tap can't race it.
    private(set) var isOpening = false

    @ObservationIgnored let screenFeed = VisionTheaterScreenFeed()

    /// The player window's size before the theater shrank it.
    @ObservationIgnored private var windowSizeBeforeTheater: CGSize?

    /// The player window becomes a remote this size while the theater is
    /// open, so it no longer covers the screen.
    static let remoteWindowSize = CGSize(width: 960, height: 540)

    func choose(_ environment: TheaterEnvironment, open: OpenImmersiveSpaceAction) async {
        if isImmersed {
            self.environment = environment
            return
        }
        guard !isOpening else { return }
        isOpening = true
        defer { isOpening = false }
        self.environment = environment
        switch await open(id: Self.spaceID) {
        case .opened:
            break
        case .userCancelled, .error:
            if !isImmersed { self.environment = nil }
        @unknown default:
            if !isImmersed { self.environment = nil }
        }
    }

    func leave(dismiss: DismissImmersiveSpaceAction) async {
        guard isImmersed else {
            environment = nil
            return
        }
        await dismiss()
        // The space's own view may outlive the dismissal, so don't wait for
        // it to report going away.
        spaceDidDisappear()
    }

    func spaceDidAppear() {
        guard !isImmersed else { return }
        isImmersed = true
        screenFeed.isActive = true
        shrinkPlayerWindow()
    }

    /// Also runs when visionOS closes the space itself, for example when the
    /// viewer presses the Digital Crown. Safe to call more than once.
    func spaceDidDisappear() {
        guard isImmersed || environment != nil else { return }
        isImmersed = false
        environment = nil
        screenFeed.reset()
        restorePlayerWindow()
    }

    // MARK: - Player window

    private var playerWindowScene: UIWindowScene? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.session.role == .windowApplication && $0.activationState == .foregroundActive }
    }

    private func shrinkPlayerWindow() {
        guard let scene = playerWindowScene else { return }
        let current = scene.coordinateSpace.bounds.size
        guard current.width > Self.remoteWindowSize.width
                || current.height > Self.remoteWindowSize.height
        else { return }
        windowSizeBeforeTheater = current
        scene.requestGeometryUpdate(.Vision(size: Self.remoteWindowSize))
    }

    private func restorePlayerWindow() {
        guard let size = windowSizeBeforeTheater else { return }
        windowSizeBeforeTheater = nil
        playerWindowScene?.requestGeometryUpdate(.Vision(size: size))
    }
}

#endif
