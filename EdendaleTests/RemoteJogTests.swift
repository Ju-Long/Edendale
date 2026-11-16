//
//  RemoteJogTests.swift
//  EdendaleTests
//
//  The Siri Remote jog gesture fed with synthetic touch paths: circles and
//  quick flicks engage it, straight swipes and wiggles never do, and the
//  turn-to-time mapping speeds up with the spin.
//

import Testing
import Foundation
@testable import Edendale

struct RemoteJogTests {

    private struct Touch {
        let x: Double
        let y: Double
        let t: TimeInterval
    }

    /// Samples along a circle from `start` (radians, counter-clockwise from
    /// the right) sweeping `sweep` radians clockwise (negative: counter-
    /// clockwise), at the dpad's ~100 Hz.
    private func arc(
        from start: Double,
        sweep: Double,
        radius: Double = 0.85,
        duration: TimeInterval,
        at startTime: TimeInterval = 0
    ) -> [Touch] {
        let count = max(Int(duration * 100), 2)
        return (0...count).map { i in
            let fraction = Double(i) / Double(count)
            let angle = start - sweep * fraction
            return Touch(
                x: radius * cos(angle),
                y: radius * sin(angle),
                t: startTime + duration * fraction
            )
        }
    }

    private func line(
        from: (Double, Double),
        to: (Double, Double),
        duration: TimeInterval,
        at startTime: TimeInterval = 0
    ) -> [Touch] {
        let count = max(Int(duration * 100), 2)
        return (0...count).map { i in
            let f = Double(i) / Double(count)
            return Touch(
                x: from.0 + (to.0 - from.0) * f,
                y: from.1 + (to.1 - from.1) * f,
                t: startTime + duration * f
            )
        }
    }

    private func lift(at time: TimeInterval) -> [Touch] {
        [Touch(x: 0, y: 0, t: time)]
    }

    private func run(_ touches: [Touch], into detector: inout RemoteJogDetector) -> [RemoteJogDetector.Event] {
        touches.map { detector.process(x: $0.x, y: $0.y, at: $0.t) }
    }

    private func run(_ touches: [Touch]) -> [RemoteJogDetector.Event] {
        var detector = RemoteJogDetector()
        return run(touches, into: &detector)
    }

    private func engagements(_ events: [RemoteJogDetector.Event]) -> [Bool] {
        events.compactMap {
            if case .engaged(let clockwise) = $0 { clockwise } else { nil }
        }
    }

    // MARK: - Engaging

    @Test func quickClockwiseCircleEngagesForward() {
        let events = run(arc(from: .pi / 2, sweep: 1.8 * .pi, duration: 0.6))
        #expect(engagements(events) == [true])

        let rotating = events.firstIndex(of: .rotating)
        let engaged = events.firstIndex(of: .engaged(clockwise: true))
        #expect(rotating != nil && engaged != nil && rotating! < engaged!)

        // Every sample after engaging is a forward turn.
        for event in events[(engaged! + 1)...] {
            guard case .turned(let radians, _) = event else {
                Issue.record("expected a turn, got \(event)")
                return
            }
            #expect(radians > 0)
        }
    }

    @Test func quickCounterClockwiseCircleEngagesBackward() {
        let events = run(arc(from: 0, sweep: -1.8 * .pi, duration: 0.6))
        #expect(engagements(events) == [false])
    }

    @Test func slowCircleNeverEngages() {
        // A full turn over three seconds sweeps only 180° inside any 1.5 s.
        let events = run(arc(from: .pi / 2, sweep: 2 * .pi, duration: 3))
        #expect(engagements(events).isEmpty)
    }

    @Test func flicksInQuickSuccessionAddUp() {
        // Winding a disc: three quick strokes from twelve o'clock to about
        // four, lifting between them.
        var touches: [Touch] = []
        var time: TimeInterval = 0
        for _ in 0..<3 {
            touches += arc(from: .pi / 2, sweep: 0.62 * .pi, duration: 0.2, at: time)
            time += 0.2
            touches += lift(at: time + 0.01)
            time += 0.15
        }
        let events = run(touches)
        #expect(engagements(events) == [true])
    }

    @Test func flicksFarApartDoNotAddUp() {
        var touches = arc(from: .pi / 2, sweep: 1.1 * .pi, duration: 0.3)
        touches += lift(at: 0.31)
        touches += arc(from: .pi / 2, sweep: 1.1 * .pi, duration: 0.3, at: 0.9)
        #expect(engagements(run(touches)).isEmpty)
    }

    // MARK: - Not a jog

    @Test func straightSwipesAcrossTheTopAreNotCircles() {
        // Straight swipes stay on the ring yet sweep up to ~120° around the
        // center; they must stay plain swipes, not even "rotating".
        let swipes = [
            line(from: (-0.9, 0.7), to: (0.9, 0.7), duration: 0.2),
            line(from: (-0.85, 0.5), to: (0.85, 0.5), duration: 0.15),
            line(from: (0.85, -0.5), to: (-0.85, -0.5), duration: 0.15),
            line(from: (0.6, 0.9), to: (0.6, -0.9), duration: 0.2),
        ]
        for swipe in swipes {
            #expect(run(swipe).allSatisfy { $0 == .none })
        }
    }

    @Test func wigglingBackAndForthNeverEngages() {
        var touches: [Touch] = []
        var time: TimeInterval = 0
        for i in 0..<8 {
            let forward = i.isMultiple(of: 2)
            touches += arc(
                from: forward ? .pi / 2 : 0,
                sweep: forward ? .pi / 2 : -.pi / 2,
                duration: 0.12,
                at: time
            ).dropFirst()
            time += 0.12
        }
        #expect(engagements(run(touches)).isEmpty)
    }

    @Test func centerTouchesAreIgnored() {
        let events = run(arc(from: 0, sweep: 2 * .pi, radius: 0.3, duration: 0.5))
        #expect(events.allSatisfy { $0 == .none })
    }

    // MARK: - Engaged

    @Test func engagedJogReversesAndReportsSpeed() {
        var detector = RemoteJogDetector()
        _ = run(arc(from: .pi / 2, sweep: 1.6 * .pi, duration: 0.5), into: &detector)
        #expect(detector.isEngaged)

        // Slow counter-clockwise turn: backward, slow.
        let back = run(
            arc(from: -0.3 * .pi, sweep: -0.5 * .pi, duration: 0.5, at: 0.6),
            into: &detector
        )
        let slowTurns = back.compactMap { event -> (Double, Double)? in
            if case .turned(let r, let s) = event { (r, s) } else { nil }
        }
        #expect(slowTurns.dropFirst().allSatisfy { $0.0 < 0 })
        let slowSpeed = slowTurns.last!.1
        #expect(abs(slowSpeed - 0.5) < 0.1)

        // Fast clockwise spin: forward, about four times the speed.
        let fast = run(
            arc(from: -0.8 * .pi, sweep: 1.0 * .pi, duration: 0.25, at: 1.2),
            into: &detector
        )
        guard case .turned(_, let fastSpeed) = fast.last! else {
            Issue.record("expected a turn")
            return
        }
        #expect(fastSpeed > 1.8 && fastSpeed < 2.2)
    }

    @Test func engagedJogIdlesOutAfterTheLastTurn() {
        var detector = RemoteJogDetector()
        let touches = arc(from: .pi / 2, sweep: 1.6 * .pi, duration: 0.5)
        _ = run(touches, into: &detector)
        _ = detector.process(x: 0, y: 0, at: 0.52)

        #expect(!detector.isIdle(at: 1.0))
        #expect(detector.isIdle(at: 0.5 + RemoteJogDetector.idleTimeout))

        // A second wind before the timeout continues without re-engaging.
        let more = run(arc(from: .pi / 2, sweep: 0.4 * .pi, duration: 0.2, at: 1.2), into: &detector)
        #expect(engagements(more).isEmpty)
        #expect(more.contains { if case .turned(let r, _) = $0 { r > 0 } else { false } })
    }

    // MARK: - Turn → time

    @Test func fasterSpinsCoverMoreTimePerTurn() {
        let movie = 2 * 3600.0
        #expect(RemoteJog.secondsPerTurn(turnsPerSecond: 0.5, durationSeconds: movie) == 10)
        let speeds = [0.25, 0.5, 1, 2, 3]
        let perTurn = speeds.map { RemoteJog.secondsPerTurn(turnsPerSecond: $0, durationSeconds: movie) }
        #expect(perTurn == perTurn.sorted())
        #expect(Set(perTurn).count == perTurn.count)
        // A brisk three turns a second covers minutes a turn.
        #expect(perTurn.last! > 120)
    }

    @Test func secondsPerTurnStaysInBounds() {
        #expect(RemoteJog.secondsPerTurn(turnsPerSecond: 0, durationSeconds: 7200) == RemoteJog.minimumSecondsPerTurn)
        // A 20-minute episode never covers more than five minutes a turn.
        #expect(RemoteJog.secondsPerTurn(turnsPerSecond: 20, durationSeconds: 1200) == 300)
        // Very short clips still allow a minute a turn.
        #expect(RemoteJog.secondsPerTurn(turnsPerSecond: 20, durationSeconds: 90) == 60)
    }

    @Test func turnsMapToSignedSeconds() {
        let half = RemoteJog.seconds(forRadians: .pi, turnsPerSecond: 0.5, durationSeconds: 7200)
        let back = RemoteJog.seconds(forRadians: -.pi, turnsPerSecond: 0.5, durationSeconds: 7200)
        #expect(half == 5)
        #expect(back == -5)
    }
}
