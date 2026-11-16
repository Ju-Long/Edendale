//
//  VisionTheaterController.swift
//  Edendale
//
//  State of the visionOS immersive theater: which room is open, and the
//  feed that carries the picture onto its screen.
//

#if os(visionOS)

import SwiftUI

/// The theater is opened only by the viewer, from the player's Theater menu.
/// It shows the picture the player is drawing, so it lives exactly as long
/// as that player. While it is open the player window is closed: the
/// theater draws the picture itself and its own panel carries the controls,
/// and leaving brings the window back.
@MainActor
@Observable
final class VisionTheaterController {
    /// The `ImmersiveSpace` scene's identifier.
    static let spaceID = "theater"

    /// The main window's scene identifier, which reopens it after the
    /// theater closed it.
    static let playerWindowID = "main"

    /// The room the space shows, or is opening with. Choosing another one
    /// while it is open swaps the room in place.
    private(set) var environment: TheaterEnvironment?

    /// The immersive space is on screen.
    private(set) var isImmersed = false

    /// An open request is in flight, so a second tap can't race it.
    private(set) var isOpening = false

    /// The player window has closed for the theater, so the theater draws
    /// the picture itself.
    private(set) var isPlayerWindowClosed = false

    /// When the player window last asked to close for the theater. A
    /// window that goes away soon after closed for the theater; much later,
    /// it was the viewer closing the player.
    @ObservationIgnored private var windowCloseRequested: ContinuousClock.Instant?
    private static let windowCloseTimeout: Duration = .seconds(3)

    /// Player screens on screen, one in each open player window.
    @ObservationIgnored private var playerScreens = 0

    /// Opens the main window again; set by the theater's space.
    @ObservationIgnored var reopenPlayerWindow: (() -> Void)?

    @ObservationIgnored let screenFeed = VisionTheaterScreenFeed()

    /// The seat picked in each room from the seat panel's map; a room
    /// without one seats the viewer in its best seat. Like the lights, it
    /// stays on this device and comes back the next time the room opens.
    private(set) var seats: [TheaterEnvironment: TheaterSeatMap.SeatID]

    /// How bright every room is drawn.
    private(set) var lights: TheaterLights

    /// Counts the viewer's presses of Recenter; the space turns the theater
    /// to where they look on each one.
    private(set) var recenterRequests = 0

    @ObservationIgnored private let defaults: UserDefaults

    nonisolated static let lightsKey = "theater.lights"

    nonisolated static func seatKey(for environment: TheaterEnvironment) -> String {
        "theater.seat.\(environment.rawValue)"
    }

    init(defaults: UserDefaults? = nil) {
        let defaults = defaults ?? AppIdentifiers.defaults
        self.defaults = defaults
        var seats: [TheaterEnvironment: TheaterSeatMap.SeatID] = [:]
        for environment in TheaterEnvironment.allCases {
            if let stored = defaults.string(forKey: Self.seatKey(for: environment)),
               let seat = TheaterSeatMap.SeatID(storageValue: stored) {
                seats[environment] = seat
            }
        }
        self.seats = seats
        lights = defaults.string(forKey: Self.lightsKey).flatMap(TheaterLights.init(rawValue:)) ?? .normal
    }

    /// Nil for the room's best seat.
    func seat(in environment: TheaterEnvironment) -> TheaterSeatMap.SeatID? {
        seats[environment]
    }

    /// The seat in the open room, or the room opening.
    var currentSeat: TheaterSeatMap.SeatID? {
        environment.flatMap(seat(in:))
    }

    /// Moves the viewer to `seat` in the open room; nil is its best seat.
    func choose(seat: TheaterSeatMap.SeatID?) {
        guard let environment else { return }
        seats[environment] = seat
        if let seat {
            defaults.set(seat.storageValue, forKey: Self.seatKey(for: environment))
        } else {
            defaults.removeObject(forKey: Self.seatKey(for: environment))
        }
    }

    func choose(_ lights: TheaterLights) {
        self.lights = lights
        defaults.set(lights.rawValue, forKey: Self.lightsKey)
    }

    func recenter() {
        guard isImmersed else { return }
        recenterRequests += 1
    }

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
    }

    /// Also runs when visionOS closes the space itself, for example when the
    /// viewer presses the Digital Crown. Safe to call more than once.
    func spaceDidDisappear() {
        guard isImmersed || environment != nil else { return }
        isImmersed = false
        environment = nil
        screenFeed.reset()
        // A window still closing comes back once it has closed.
        if isPlayerWindowClosed {
            isPlayerWindowClosed = false
            reopenPlayerWindowIfNone()
        }
    }

    // MARK: - Player window

    /// The player window should close: the theater is open, and has
    /// finished opening. A window closed while the open request was still
    /// in flight often came straight back.
    var wantsPlayerWindowClosed: Bool { isImmersed && !isOpening }

    /// The player window is about to close itself for the theater.
    func playerWindowWillClose() {
        guard isImmersed else { return }
        windowCloseRequested = .now
    }

    /// Reported by the player as it comes on screen.
    func playerScreenDidAppear() {
        playerScreens += 1
    }

    /// Reported by the player as it goes off screen. True when the theater
    /// closed its window, so playback carries on in the theater rather than
    /// ending with the window.
    @discardableResult
    func playerScreenDidDisappear() -> Bool {
        playerScreens = max(playerScreens - 1, 0)
        let requested = windowCloseRequested
        windowCloseRequested = nil
        guard let requested, requested.duration(to: .now) < Self.windowCloseTimeout else { return false }
        if isImmersed {
            isPlayerWindowClosed = true
        } else {
            // The theater closed before the window had.
            reopenPlayerWindowIfNone()
        }
        return true
    }

    /// Visible player windows are left as they are rather than doubled.
    private func reopenPlayerWindowIfNone() {
        guard playerScreens == 0 else { return }
        reopenPlayerWindow?()
    }
}

#endif
