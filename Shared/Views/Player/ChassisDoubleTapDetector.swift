//
//  ChassisDoubleTapDetector.swift
//  Edendale
//
//  Easter egg: two quick taps on the MacBook chassis toggle play/pause.
//  Uses the built-in accelerometer to sense physical impact on the
//  aluminum body.
//

// Parked: CMMotionManager has never been available on macOS, so this needs a
// different accelerometer source before it can run on a Mac. It compiles on
// iOS only to keep type-checking and is not wired into the player.
#if os(iOS)
import CoreMotion
import Foundation

final class ChassisDoubleTapDetector {
    private let motionManager = CMMotionManager()
    private var lastTapTime: Date?
    private var inCooldown = false

    /// Total acceleration above which a sample counts as a tap.
    /// Gravity alone reads ~1.0 g; a deliberate knock on the chassis
    /// spikes well past 2 g.
    private let tapThreshold: Double = 2.0
    /// Dead zone after a detected tap so the mechanical reverberation
    /// from a single knock isn't counted twice.
    private let cooldownInterval: TimeInterval = 0.15
    /// Maximum gap between the first and second tap.
    private let doubleTapWindow: TimeInterval = 0.45

    var onDoubleTap: (() -> Void)?

    func start() {
        guard motionManager.isAccelerometerAvailable else { return }
        motionManager.accelerometerUpdateInterval = 1.0 / 50.0
        motionManager.startAccelerometerUpdates(to: .main) { [weak self] data, _ in
            guard let self, let data else { return }
            self.detect(data.acceleration)
        }
    }

    func stop() {
        motionManager.stopAccelerometerUpdates()
        lastTapTime = nil
        inCooldown = false
    }

    private func detect(_ a: CMAcceleration) {
        let magnitude = (a.x * a.x + a.y * a.y + a.z * a.z).squareRoot()
        guard magnitude > tapThreshold, !inCooldown else { return }

        inCooldown = true
        DispatchQueue.main.asyncAfter(deadline: .now() + cooldownInterval) { [weak self] in
            self?.inCooldown = false
        }

        let now = Date()
        if let last = lastTapTime, now.timeIntervalSince(last) < doubleTapWindow {
            onDoubleTap?()
            lastTapTime = nil
        } else {
            lastTapTime = now
        }
    }
}
#endif
