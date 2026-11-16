//
//  TVRemoteInput.swift
//  Edendale
//
//  Reads the Siri Remote's touch surface through the Game Controller
//  framework so a resting press-and-hold can drive playback speed — the
//  tvOS counterpart to the iOS hold-speed gesture. Quick swipes still fall
//  through to the focus engine's move commands (the skips in the reveal
//  catcher and timeline); only a thumb held to one side past a short dwell
//  engages that side's hold speed (0.5× left and 2× right unless changed in
//  Settings), reverting the instant it lifts.
//
//  The same touches feed `RemoteJogDetector`: a quick wind around the ring
//  becomes a jog that scrubs the timeline, and holds stay off for the rest
//  of that touch.
//

#if os(tvOS)
import Foundation
import GameController

@MainActor
final class TVRemoteInput {
    private unowned let chrome: PlayerChromeModel

    private var observers: [NSObjectProtocol] = []
    private var monitoredPads: [ObjectIdentifier: GCMicroGamepad] = [:]

    /// Dwell before a sustained touch becomes a hold. Long enough that a
    /// flick across the edge — which also fires a seek move command — lifts
    /// before it ever counts as a hold.
    private static let dwell: Duration = .milliseconds(300)

    private var dwellTask: Task<Void, Never>?
    /// Side of the hold currently in effect, nil while idle.
    private var engagedSide: HoldSide?
    /// Most recent touch position, retained so the dwell timer can confirm
    /// the thumb is still down when the surface reports no further changes.
    private var latestX: Float = 0
    private var latestY: Float = 0

    /// Circles around the ring: a quick wind takes over the timeline (see
    /// `RemoteJogDetector`), and holds stay off until the thumb lifts.
    private var jogDetector = RemoteJogDetector()
    private var jogIdleTask: Task<Void, Never>?
    private var holdBlockedUntilLift = false

    init(chrome: PlayerChromeModel) {
        self.chrome = chrome
        for controller in GCController.controllers() { monitor(controller) }

        let center = NotificationCenter.default
        observers.append(center.addObserver(
            forName: .GCControllerDidConnect, object: nil, queue: .main
        ) { [weak self] note in
            guard let controller = note.object as? GCController else { return }
            MainActor.assumeIsolated { self?.monitor(controller) }
        })
        observers.append(center.addObserver(
            forName: .GCControllerDidDisconnect, object: nil, queue: .main
        ) { [weak self] note in
            guard let controller = note.object as? GCController else { return }
            MainActor.assumeIsolated { self?.stopMonitoring(controller) }
        })
    }

    /// Detaches every handler and observer and drops any active hold. Call
    /// before the owner releases this — `deinit` on a `@MainActor` type can't
    /// touch actor state, so teardown is explicit.
    func invalidate() {
        release()
        jogIdleTask?.cancel()
        jogIdleTask = nil
        jogDetector.reset()
        for (_, pad) in monitoredPads { pad.dpad.valueChangedHandler = nil }
        monitoredPads.removeAll()
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers.removeAll()
    }

    private func monitor(_ controller: GCController) {
        guard let pad = controller.microGamepad else { return }
        controller.handlerQueue = .main
        // Absolute values report where the thumb is resting, not how far it
        // has moved — that's what "which side is being held" needs.
        pad.reportsAbsoluteDpadValues = true
        monitoredPads[ObjectIdentifier(controller)] = pad
        pad.dpad.valueChangedHandler = { [weak self] _, x, y in
            MainActor.assumeIsolated { self?.touchChanged(x: x, y: y) }
        }
    }

    private func stopMonitoring(_ controller: GCController) {
        let id = ObjectIdentifier(controller)
        monitoredPads[id]?.dpad.valueChangedHandler = nil
        monitoredPads[id] = nil
    }

    private func touchChanged(x: Float, y: Float) {
        latestX = x
        latestY = y

        // A hold only makes sense over the bare video — never fight a side
        // panel's list scrolling or hijack the remote inside a menu.
        guard chrome.activePanel == nil, !chrome.guideVisible else {
            release()
            endJog()
            return
        }

        if trackJog(x: x, y: y) { return }

        if engagedSide != nil {
            if let side = PlayerLogic.holdSide(x: x, y: y, active: true) {
                if side != engagedSide {
                    engagedSide = side
                    chrome.beginHold(on: side)
                }
            } else {
                release()
            }
            return
        }

        if PlayerLogic.holdSide(x: x, y: y, active: false) != nil {
            armDwell()
        } else {
            dwellTask?.cancel()
            dwellTask = nil
        }
    }

    /// Runs the touch through the jog detector. Returns true when the touch
    /// belongs to a circle, so the hold logic must leave it alone.
    private func trackJog(x: Float, y: Float) -> Bool {
        // Menu or a click ended the jog on the chrome's side.
        if jogDetector.isEngaged, chrome.jog == nil { jogDetector.reset() }

        let event = jogDetector.process(
            x: Double(x), y: Double(y),
            at: ProcessInfo.processInfo.systemUptime
        )
        switch event {
        case .none:
            break
        case .rotating:
            chrome.noteRemoteCircle()
        case .engaged(let clockwise):
            chrome.noteRemoteCircle()
            if chrome.beginJog(forward: clockwise) {
                watchJogIdle()
            } else {
                jogDetector.reset()
            }
        case .turned(let radians, let turnsPerSecond):
            chrome.noteRemoteCircle()
            chrome.jog(byRadians: radians, turnsPerSecond: turnsPerSecond)
        }

        let lifted = abs(x) < 0.05 && abs(y) < 0.05
        if event != .none || jogDetector.isEngaged {
            holdBlockedUntilLift = true
            release()
        } else if lifted {
            holdBlockedUntilLift = false
        }
        return holdBlockedUntilLift
    }

    /// Ends the jog once the ring has been still for the detector's timeout.
    private func watchJogIdle() {
        jogIdleTask?.cancel()
        jogIdleTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self, !Task.isCancelled else { return }
                guard self.jogDetector.isEngaged, self.chrome.jog != nil else {
                    self.jogDetector.reset()
                    return
                }
                if self.jogDetector.isIdle(at: ProcessInfo.processInfo.systemUptime) {
                    self.endJog()
                    return
                }
            }
        }
    }

    private func endJog() {
        jogIdleTask?.cancel()
        jogIdleTask = nil
        jogDetector.reset()
        chrome.endJog()
    }

    private func armDwell() {
        guard dwellTask == nil else { return }
        dwellTask = Task { [weak self] in
            try? await Task.sleep(for: Self.dwell)
            guard let self, !Task.isCancelled else { return }
            self.dwellTask = nil
            self.engageIfStillHeld()
        }
    }

    private func engageIfStillHeld() {
        guard engagedSide == nil, chrome.activePanel == nil,
              let side = PlayerLogic.holdSide(x: latestX, y: latestY, active: false)
        else { return }
        engagedSide = side
        chrome.beginHold(on: side)
    }

    private func release() {
        dwellTask?.cancel()
        dwellTask = nil
        guard engagedSide != nil else { return }
        engagedSide = nil
        chrome.endHoldRate()
    }
}
#endif
