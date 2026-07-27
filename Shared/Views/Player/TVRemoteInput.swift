//
//  TVRemoteInput.swift
//  Edendale
//
//  Reads the Siri Remote's touch surface through the Game Controller
//  framework so a resting press-and-hold can drive playback speed — the
//  tvOS counterpart to the iOS hold-speed gesture. Quick swipes still fall
//  through to the focus engine's move commands (the ±10 s seeks in the
//  reveal catcher and timeline); only a thumb held to one side past a short
//  dwell engages slow (left) or fast (right) playback, reverting the instant
//  it lifts.
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
    private static let dwell: Duration = .milliseconds(450)

    private var dwellTask: Task<Void, Never>?
    /// Rate of the hold currently in effect, nil while idle.
    private var engagedRate: Float?
    /// Most recent touch position, retained so the dwell timer can confirm
    /// the thumb is still down when the surface reports no further changes.
    private var latestX: Float = 0
    private var latestY: Float = 0

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
        guard chrome.activePanel == nil else {
            release()
            return
        }

        if engagedRate != nil {
            if let rate = PlayerLogic.holdRate(x: x, y: y, active: true) {
                if rate != engagedRate {
                    engagedRate = rate
                    chrome.beginHoldRate(rate)
                }
            } else {
                release()
            }
            return
        }

        if PlayerLogic.holdRate(x: x, y: y, active: false) != nil {
            armDwell()
        } else {
            dwellTask?.cancel()
            dwellTask = nil
        }
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
        guard engagedRate == nil, chrome.activePanel == nil,
              let rate = PlayerLogic.holdRate(x: latestX, y: latestY, active: false)
        else { return }
        engagedRate = rate
        chrome.beginHoldRate(rate)
    }

    private func release() {
        dwellTask?.cancel()
        dwellTask = nil
        guard engagedRate != nil else { return }
        engagedRate = nil
        chrome.endHoldRate()
    }
}
#endif
