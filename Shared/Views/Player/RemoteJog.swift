//
//  RemoteJog.swift
//  Edendale
//
//  Recognizes a circular "jog" on the Siri Remote's touch surface, like
//  winding a disc: a quick run of turns around the ring takes over the
//  timeline, and from then on every turn moves the playhead, clockwise
//  forward and counter-clockwise back, further per turn the faster it spins.
//
//  Pure and platform-free so the unit tests can feed it synthetic touches;
//  `TVRemoteInput` drives it from the Game Controller dpad.
//

import Foundation

nonisolated struct RemoteJogDetector {
    /// What one touch sample meant.
    enum Event: Equatable {
        /// Nothing jog-related; the touch is free for holds and swipes.
        case none
        /// A circle has started but isn't a jog yet. Swipes the focus engine
        /// reports now come from the circle, not from a deliberate skip.
        case rotating
        /// The circle just became a jog, turning clockwise (forward) or not.
        case engaged(clockwise: Bool)
        /// An engaged jog turned by `radians` (clockwise positive) at a
        /// smoothed `turnsPerSecond`.
        case turned(radians: Double, turnsPerSecond: Double)
    }

    // MARK: Tuning

    /// Distance from the surface's center (0…1) a touch must reach to count
    /// as on the ring. The center is where taps and holds start.
    static let ringRadius = 0.45
    /// Steps larger than this between two samples are a thumb lifted and set
    /// down elsewhere, not a turn.
    static let maxStep = Double.pi / 2
    /// Lift allowed between strokes of one circle, so short flicks in quick
    /// succession still add up.
    static let strokeGap: TimeInterval = 0.35
    /// Turning that engages a jog: three quarters of a circle in one
    /// direction, inside `engageWindow`.
    static let engageArc = 1.5 * Double.pi
    static let engageWindow: TimeInterval = 1.5
    /// Turning that marks a circle as started (before it engages).
    static let rotatingArc = Double.pi / 4
    /// Share of the turning that must go one way. Back-and-forth wiggles
    /// never engage; a jog itself may reverse freely once engaged.
    static let consistency = 0.8
    /// How much the thumb's direction of travel must bend, as a share of the
    /// angle it sweeps around the center. Following the ring bends it by the
    /// full sweep; a straight swipe across the top of the surface sweeps up
    /// to ~127° without bending at all.
    static let bendRatio = 0.5
    /// Travel that measures one direction of motion for the bend.
    static let headingSegment = 0.05
    /// A jog stays engaged this long after its last turn, so the thumb can
    /// lift and come back for another wind without starting over.
    static let idleTimeout: TimeInterval = 1.5
    /// Smoothing time constant for the reported speed.
    static let speedSmoothing: TimeInterval = 0.08
    /// Turns smaller than this are jitter from a resting thumb.
    static let jitter = 0.01

    // MARK: State

    private(set) var isEngaged = false
    /// Smoothed angular speed, radians per second.
    private(set) var angularSpeed = 0.0
    /// Time of the last real turn, for the owner's idle timer.
    private(set) var lastTurnTime: TimeInterval?

    private var lastAngle: Double?
    private var lastSampleTime: TimeInterval?
    private var lastRingTime: TimeInterval?
    private var headingAnchor: (x: Double, y: Double)?
    private var lastHeading: Double?
    private var unsettledTurn = 0.0
    /// Signed sweep around the center and bend of travel (both clockwise
    /// positive) gathered before engaging.
    private var history: [(time: TimeInterval, sweep: Double, bend: Double)] = []

    init() {}

    /// Feeds one absolute touch position: `x` right and `y` up, each −1…1,
    /// as the Game Controller dpad reports it with absolute values on.
    mutating func process(x: Double, y: Double, at time: TimeInterval) -> Event {
        guard (x * x + y * y).squareRoot() >= Self.ringRadius else {
            // Off the ring (lifted or in the middle): the stroke ends here,
            // but the circle can go on with the next one.
            lastAngle = nil
            headingAnchor = nil
            lastHeading = nil
            return .none
        }

        if !isEngaged, let lastRingTime, time - lastRingTime > Self.strokeGap {
            history.removeAll()
        }
        lastRingTime = time

        let bend = measureBend(x: x, y: y)
        let angle = atan2(y, x)
        defer {
            lastAngle = angle
            lastSampleTime = time
        }
        guard let lastAngle, let lastSampleTime else { return currentState }

        // atan2 grows counter-clockwise; flip it so clockwise is positive.
        let step = -Self.wrapped(angle - lastAngle)
        guard abs(step) <= Self.maxStep else { return currentState }

        let dt = max(time - lastSampleTime, 0.001)
        let blend = 1 - exp(-dt / Self.speedSmoothing)
        angularSpeed += blend * (abs(step) / dt - angularSpeed)
        // Small steps count once they add up, so a slow turn keeps the jog
        // alive while a resting thumb's back-and-forth jitter cancels out.
        unsettledTurn += step
        if abs(unsettledTurn) >= Self.jitter {
            lastTurnTime = time
            unsettledTurn = 0
        }

        if isEngaged {
            return .turned(radians: step, turnsPerSecond: turnsPerSecond)
        }

        history.append((time, step, bend))
        history.removeAll { time - $0.time > Self.engageWindow }
        let sweep = history.reduce(0) { $0 + $1.sweep }
        let total = history.reduce(0) { $0 + abs($1.sweep) }
        let bending = history.reduce(0) { $0 + $1.bend }
        guard total > 0,
              abs(sweep) >= Self.consistency * total,
              bending * sweep > 0,
              abs(bending) >= Self.bendRatio * abs(sweep)
        else { return .none }

        if abs(sweep) >= Self.engageArc {
            isEngaged = true
            history.removeAll()
            return .engaged(clockwise: sweep > 0)
        }
        return abs(sweep) >= Self.rotatingArc ? .rotating : .none
    }

    /// Whether an engaged jog has gone quiet long enough to end.
    func isIdle(at time: TimeInterval) -> Bool {
        guard isEngaged, let lastTurnTime else { return false }
        return time - lastTurnTime >= Self.idleTimeout
    }

    /// Forgets the gesture: the jog ended, or something else took the remote.
    mutating func reset() {
        self = RemoteJogDetector()
    }

    var turnsPerSecond: Double { angularSpeed / (2 * .pi) }

    /// The event a sample carries when it can't measure a turn yet.
    private var currentState: Event {
        isEngaged ? .turned(radians: 0, turnsPerSecond: turnsPerSecond) : .none
    }

    /// How far the direction of travel turned (clockwise positive) since the
    /// last measured segment; zero until the thumb has moved far enough to
    /// have a direction.
    private mutating func measureBend(x: Double, y: Double) -> Double {
        guard let anchor = headingAnchor else {
            headingAnchor = (x, y)
            return 0
        }
        let dx = x - anchor.x
        let dy = y - anchor.y
        guard (dx * dx + dy * dy).squareRoot() >= Self.headingSegment else { return 0 }
        headingAnchor = (x, y)
        let heading = atan2(dy, dx)
        defer { lastHeading = heading }
        guard let lastHeading else { return 0 }
        let bend = -Self.wrapped(heading - lastHeading)
        return abs(bend) <= Self.maxStep ? bend : 0
    }

    /// An angle difference folded into −π…π.
    private static func wrapped(_ radians: Double) -> Double {
        var value = radians.truncatingRemainder(dividingBy: 2 * .pi)
        if value > .pi { value -= 2 * .pi }
        if value < -.pi { value += 2 * .pi }
        return value
    }
}

nonisolated enum RemoteJog {
    /// Media seconds one full turn covers at `turnsPerSecond`. Slow turns
    /// creep (a few seconds a turn) and fast spins fly (minutes a turn): the
    /// speed is raised to a power, so a spin twice as fast covers about
    /// three times as much per turn. Capped at a quarter of the video so a
    /// short clip never jumps end to end in one turn.
    static func secondsPerTurn(turnsPerSecond: Double, durationSeconds: Double) -> Double {
        let ceiling = max(60, durationSeconds / 4)
        let speed = max(turnsPerSecond, 0) / referenceTurnsPerSecond
        return min(max(referenceSecondsPerTurn * pow(speed, exponent), minimumSecondsPerTurn), ceiling)
    }

    /// The media offset, in seconds, a turn of `radians` covers.
    static func seconds(
        forRadians radians: Double,
        turnsPerSecond: Double,
        durationSeconds: Double
    ) -> Double {
        radians / (2 * .pi)
            * secondsPerTurn(turnsPerSecond: turnsPerSecond, durationSeconds: durationSeconds)
    }

    /// Half a turn per second covers ten seconds a turn.
    static let referenceTurnsPerSecond = 0.5
    static let referenceSecondsPerTurn = 10.0
    static let minimumSecondsPerTurn = 4.0
    static let exponent = 1.6

    /// 1…3 speed marks for the timeline's jog capsule.
    static func speedTier(turnsPerSecond: Double) -> Int {
        switch turnsPerSecond {
        case ..<0.9: 1
        case ..<1.8: 2
        default: 3
        }
    }
}
