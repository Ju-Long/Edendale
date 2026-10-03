//
//  PlayerGuideIllustrations.swift
//  Edendale
//
//  The player guide's looping demonstrations: a miniature player with a
//  finger, a pointer, or a Siri Remote acting on it, and legends for the
//  keyboard and the toolbar. They are drawn rather than recorded, so they
//  show the viewer's own skip lengths and speeds, use the archive palette,
//  and need no per-language copies. Each scene is a pure function of its
//  loop phase (0..<1); under Reduce Motion it holds one telling frame.
//

import SwiftUI

// MARK: - Illustration

struct PlayerGuideIllustration: View {
    let topic: PlayerGuideTopic
    let platform: PlayerGuidePlatform
    let controls: PlayerControlPreferences

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Each page starts its loop from the top.
    @State private var start = Date()

    var body: some View {
        switch topic {
        case .keyboard:
            GuideShortcutLegend(
                shortcuts: PlayerGuide.shortcuts(for: platform, controls: controls),
                start: start
            )
        case .tools:
            GuideToolLegend(tools: PlayerGuide.tools(for: platform), start: start)
        default:
            let script = GuideScript(topic: topic, platform: platform, controls: controls)
            TimelineView(.animation(paused: reduceMotion)) { context in
                let phase = reduceMotion
                    ? script.stillPhase
                    : script.phase(elapsed: context.date.timeIntervalSince(start))
                GuideScene(frame: script.frame(at: phase), platform: platform)
            }
            // The page's title and message say everything the scene shows.
            .accessibilityHidden(true)
        }
    }
}

// MARK: - Frames

/// Everything a scene draws at one moment.
struct GuideFrame {
    /// Opacity of the full player chrome.
    var controls: Double = 0
    var isPlaying = true
    /// Timeline position, 0...1.
    var progress: Double = 0.32
    /// Opacity of a bare timeline shown without the rest of the chrome
    /// (a hold-drag scrub, or the tvOS remote-seek overlay).
    var timeline: Double = 0
    /// Picture brightness, drawn as the scenery's opacity over black.
    var brightness: Double = 1
    /// How far the cloud has drifted through its loop, 0..<1; follows the
    /// playback speed.
    var drift: Double = 0
    var zones: GuideZones = .none
    var hud: GuideHUD?
    var hudOpacity: Double = 0
    /// Side panel slide-in, 0 (off screen) ... 1.
    var panel: Double = 0
    /// A finger, or the Mac pointer, on the picture.
    var touch: GuideTouch?
    /// A thumb on the Siri Remote and the button it presses.
    var remote = GuideRemote()
}

/// Dividers for the picture's tap or hold regions.
enum GuideZones {
    case none, halves, thirds
}

enum GuideHUD: Equatable {
    case seek(forward: Bool, seconds: Int)
    case level(caption: String, value: Double)
    case speed(String)
    case scrub(target: String, offset: String)
}

struct GuideTouch {
    /// In unit coordinates of the picture.
    var point: CGPoint
    var opacity: Double
    /// Progress of a tap's ripple, nil between taps.
    var ripple: Double?
    /// Progress of the press-and-hold ring.
    var hold: Double = 0
    var pressed = false
    /// Where a drag began, drawn as a trail to `point`.
    var trailStart: CGPoint?
}

struct GuideRemote {
    enum Key { case clickpad, back, playPause }

    /// In unit coordinates of the clickpad, -1...1 from its center.
    var thumb: CGPoint?
    var thumbOpacity: Double = 0
    var ripple: Double?
    var hold: Double = 0
    var trailStart: CGPoint?
    var pressed: Key?
}

// MARK: - Curves

private enum Curve {
    /// 0 before `start`, 1 after `end`, eased in between.
    static func ramp(_ phase: Double, _ start: Double, _ end: Double) -> Double {
        guard end > start else { return phase >= end ? 1 : 0 }
        let x = min(max((phase - start) / (end - start), 0), 1)
        return x * x * (3 - 2 * x)
    }

    /// Fades in at `start` and out by `end`.
    static func window(_ phase: Double, _ start: Double, _ end: Double, fade: Double = 0.03) -> Double {
        min(ramp(phase, start, start + fade), 1 - ramp(phase, end - fade, end))
    }

    /// A tap ripple's progress while it plays, nil otherwise.
    static func ripple(_ phase: Double, at start: Double, length: Double = 0.1) -> Double? {
        guard phase >= start, phase < start + length else { return nil }
        return (phase - start) / length
    }

    static func lerp(_ a: Double, _ b: Double, _ x: Double) -> Double {
        a + (b - a) * x
    }

    /// Media time elapsed by `phase` when playback ran at `rate` inside
    /// each segment and at 1× elsewhere.
    static func elapsed(_ phase: Double, segments: [(start: Double, end: Double, rate: Double)]) -> Double {
        segments.reduce(phase) { total, segment in
            let overlap = max(0, min(phase, segment.end) - segment.start)
            return total + overlap * (segment.rate - 1)
        }
    }
}

// MARK: - Scripts

/// The timing of each topic's loop.
struct GuideScript {
    let topic: PlayerGuideTopic
    let platform: PlayerGuidePlatform
    let backSeconds: Int
    let forwardSeconds: Int
    let slowRate: Float
    let fastRate: Float

    init(topic: PlayerGuideTopic, platform: PlayerGuidePlatform, controls: PlayerControlPreferences) {
        self.topic = topic
        self.platform = platform
        backSeconds = controls.skipBackwardInterval.seconds
        forwardSeconds = controls.skipForwardInterval.seconds
        slowRate = controls.holdRate(for: .left)
        fastRate = controls.holdRate(for: .right)
    }

    /// Seconds per loop.
    var duration: Double {
        switch topic {
        case .showControls: 4
        case .skip: platform == .tv ? 5 : 6
        case .levels: platform == .vision ? 3 : 5
        case .holdSpeed: 5.6
        case .scrub: 5
        case .remoteButtons: 6
        case .keyboard, .tools: 1
        }
    }

    /// The single frame shown under Reduce Motion: the gesture caught at
    /// its result.
    var stillPhase: Double {
        switch topic {
        case .showControls: 0.45
        case .skip: platform == .tv ? 0.3 : 0.24
        case .levels: platform == .vision ? 0.6 : 0.32
        case .holdSpeed: 0.3
        case .scrub: 0.6
        case .remoteButtons: 0.56
        case .keyboard, .tools: 0
        }
    }

    func phase(elapsed: TimeInterval) -> Double {
        (elapsed / duration).truncatingRemainder(dividingBy: 1)
    }

    func frame(at phase: Double) -> GuideFrame {
        switch topic {
        case .showControls: showControls(phase)
        case .skip: platform == .tv ? remoteSkip(phase) : skip(phase)
        case .levels: platform == .vision ? volumeOnly(phase) : levels(phase)
        case .holdSpeed: holdSpeed(phase)
        case .scrub: scrub(phase)
        case .remoteButtons: remoteButtons(phase)
        case .keyboard, .tools: GuideFrame()
        }
    }

    /// A film of this length makes the timestamps look like a feature.
    static let filmSeconds: Double = 6000

    private func timestamp(_ position: Double) -> String {
        PlayerLogic.timestamp(position * Self.filmSeconds)
    }

    // MARK: Show controls

    private func showControls(_ p: Double) -> GuideFrame {
        var frame = GuideFrame()
        frame.progress = 0.32 + 0.03 * p
        frame.drift = p
        frame.controls = min(Curve.ramp(p, 0.22, 0.3), 1 - Curve.ramp(p, 0.7, 0.78))

        let firstTap = Curve.ripple(p, at: 0.16, length: 0.12)
        let secondTap = Curve.ripple(p, at: 0.64, length: 0.12)
        if platform == .tv {
            // Click the clickpad to reveal, Back to hide.
            frame.remote.thumb = .zero
            frame.remote.thumbOpacity = Curve.window(p, 0.08, 0.3)
            frame.remote.ripple = firstTap
            if (0.16..<0.22).contains(p) { frame.remote.pressed = .clickpad }
            if (0.64..<0.7).contains(p) { frame.remote.pressed = .back }
        } else {
            frame.touch = GuideTouch(
                point: CGPoint(x: 0.62, y: 0.6),
                opacity: Curve.window(p, 0.08, 0.84),
                ripple: firstTap ?? secondTap,
                pressed: firstTap.map { $0 < 0.5 } ?? secondTap.map { $0 < 0.5 } ?? false
            )
        }
        return frame
    }

    // MARK: Skip

    private func skip(_ p: Double) -> GuideFrame {
        var frame = GuideFrame()
        frame.zones = .thirds
        frame.drift = p
        let jump = 0.06
        frame.progress = 0.4
            + jump * Curve.ramp(p, 0.17, 0.2)
            - jump * Curve.ramp(p, 0.5, 0.53)
            + 0.03 * min(p, 0.82)
        frame.isPlaying = p < 0.82

        // Right side twice, left side twice, then the middle twice.
        let taps: [(point: CGPoint, start: Double, end: Double)] = [
            (CGPoint(x: 0.84, y: 0.52), 0.03, 0.3),
            (CGPoint(x: 0.16, y: 0.52), 0.36, 0.63),
            (CGPoint(x: 0.5, y: 0.74), 0.69, 0.95),
        ]
        for tap in taps where p >= tap.start && p < tap.end {
            let ripple = Curve.ripple(p, at: tap.start + 0.05, length: 0.07)
                ?? Curve.ripple(p, at: tap.start + 0.12, length: 0.07)
            frame.touch = GuideTouch(
                point: tap.point,
                opacity: Curve.window(p, tap.start, tap.end),
                ripple: ripple,
                pressed: ripple.map { $0 < 0.5 } ?? false
            )
        }

        if p < 0.45 {
            frame.hud = .seek(forward: true, seconds: forwardSeconds)
            frame.hudOpacity = Curve.window(p, 0.17, 0.38)
        } else if p < 0.8 {
            frame.hud = .seek(forward: false, seconds: backSeconds)
            frame.hudOpacity = Curve.window(p, 0.5, 0.71)
        }
        // A middle double-tap pauses, and the chrome shows it.
        frame.controls = Curve.window(p, 0.82, 0.99)
        return frame
    }

    private func remoteSkip(_ p: Double) -> GuideFrame {
        var frame = GuideFrame()
        frame.drift = p
        let base = 0.4
        let jump = 0.05
        frame.progress = base
            + jump * Curve.ramp(p, 0.18, 0.22)
            - jump * Curve.ramp(p, 0.62, 0.66)

        if p < 0.48 {
            let swipe = Curve.ramp(p, 0.1, 0.2)
            frame.remote.thumb = CGPoint(x: Curve.lerp(-0.55, 0.55, swipe), y: 0)
            frame.remote.trailStart = CGPoint(x: -0.55, y: 0)
            frame.remote.thumbOpacity = Curve.window(p, 0.06, 0.25)
            frame.timeline = Curve.window(p, 0.18, 0.46)
            frame.hud = .scrub(
                target: PlayerLogic.timestamp(base * Self.filmSeconds + Double(forwardSeconds)),
                offset: "+" + PlayerLogic.timestamp(Double(forwardSeconds))
            )
            frame.hudOpacity = frame.timeline
        } else {
            let swipe = Curve.ramp(p, 0.54, 0.64)
            frame.remote.thumb = CGPoint(x: Curve.lerp(0.55, -0.55, swipe), y: 0)
            frame.remote.trailStart = CGPoint(x: 0.55, y: 0)
            frame.remote.thumbOpacity = Curve.window(p, 0.5, 0.69)
            frame.timeline = Curve.window(p, 0.62, 0.92)
            frame.hud = .scrub(
                target: PlayerLogic.timestamp(base * Self.filmSeconds - Double(backSeconds)),
                offset: "−" + PlayerLogic.timestamp(Double(backSeconds))
            )
            frame.hudOpacity = frame.timeline
        }
        return frame
    }

    // MARK: Brightness and volume

    private func levels(_ p: Double) -> GuideFrame {
        var frame = GuideFrame()
        frame.zones = .halves
        frame.progress = 0.32 + 0.03 * p
        frame.drift = p

        let brighten = Curve.ramp(p, 0.58, 0.86)
        let restore = Curve.ramp(p, 0.94, 1)
        frame.brightness = Curve.lerp(Curve.lerp(0.55, 1, brighten), 0.55, restore)

        if p < 0.5 {
            let swipe = Curve.ramp(p, 0.1, 0.38)
            let start = CGPoint(x: 0.78, y: 0.72)
            frame.touch = GuideTouch(
                point: CGPoint(x: start.x, y: Curve.lerp(start.y, 0.3, swipe)),
                opacity: Curve.window(p, 0.05, 0.44),
                pressed: true,
                trailStart: start
            )
            frame.hud = .level(caption: String(localized: "Volume"), value: Curve.lerp(0.3, 0.85, swipe))
            frame.hudOpacity = Curve.window(p, 0.1, 0.46)
        } else {
            let start = CGPoint(x: 0.22, y: 0.72)
            frame.touch = GuideTouch(
                point: CGPoint(x: start.x, y: Curve.lerp(start.y, 0.3, brighten)),
                opacity: Curve.window(p, 0.53, 0.92),
                pressed: true,
                trailStart: start
            )
            frame.hud = .level(caption: String(localized: "Brightness"), value: Curve.lerp(0.45, 1, brighten))
            frame.hudOpacity = Curve.window(p, 0.58, 0.94)
        }
        return frame
    }

    /// visionOS has no app brightness, so its levels page is volume alone.
    private func volumeOnly(_ p: Double) -> GuideFrame {
        var frame = GuideFrame()
        frame.zones = .halves
        frame.progress = 0.32 + 0.03 * p
        frame.drift = p
        let swipe = Curve.ramp(p, 0.18, 0.66)
        let start = CGPoint(x: 0.78, y: 0.72)
        frame.touch = GuideTouch(
            point: CGPoint(x: start.x, y: Curve.lerp(start.y, 0.3, swipe)),
            opacity: Curve.window(p, 0.08, 0.8, fade: 0.05),
            pressed: true,
            trailStart: start
        )
        frame.hud = .level(caption: String(localized: "Volume"), value: Curve.lerp(0.3, 0.85, swipe))
        frame.hudOpacity = Curve.window(p, 0.18, 0.86, fade: 0.05)
        return frame
    }

    // MARK: Hold speed

    private func holdSpeed(_ p: Double) -> GuideFrame {
        var frame = GuideFrame()
        frame.zones = platform == .tv ? .none : .halves

        let segments = [
            (start: 0.15, end: 0.4, rate: Double(fastRate)),
            (start: 0.63, end: 0.9, rate: Double(slowRate)),
        ]
        let elapsed = Curve.elapsed(p, segments: segments)
        let loop = Curve.elapsed(1, segments: segments)
        frame.drift = elapsed / loop
        frame.progress = 0.3 + 0.05 * elapsed / loop

        let right = p < 0.48
        let (start, end, engaged) = right ? (0.04, 0.42, 0.15) : (0.52, 0.92, 0.63)
        let ring = p < end - 0.03 ? Curve.ramp(p, start + 0.03, engaged) : 0
        let opacity = Curve.window(p, start, end)

        if platform == .tv {
            frame.remote.thumb = CGPoint(x: right ? 0.78 : -0.78, y: 0)
            frame.remote.thumbOpacity = opacity
            frame.remote.hold = ring
        } else {
            frame.touch = GuideTouch(
                point: CGPoint(x: right ? 0.76 : 0.24, y: 0.54),
                opacity: opacity,
                hold: ring,
                pressed: p >= start + 0.03 && p < end - 0.03
            )
        }
        frame.hud = .speed(PlayerLogic.rateLabel(right ? fastRate : slowRate))
        frame.hudOpacity = Curve.window(p, engaged, end)
        return frame
    }

    // MARK: Scrub

    private func scrub(_ p: Double) -> GuideFrame {
        var frame = GuideFrame()
        frame.drift = p
        let base = 0.32
        let start = CGPoint(x: 0.4, y: 0.56)
        let slide = Curve.ramp(p, 0.24, 0.58)
        let x = Curve.lerp(start.x, 0.72, slide)
        let target = base + (x - start.x) * 0.9
        frame.progress = target
        frame.isPlaying = p < 0.08 || p > 0.76

        frame.touch = GuideTouch(
            point: CGPoint(x: x, y: start.y),
            opacity: Curve.window(p, 0.05, 0.76),
            hold: p < 0.73 ? Curve.ramp(p, 0.08, 0.18) : 0,
            pressed: p >= 0.08 && p < 0.73,
            trailStart: slide > 0 ? start : nil
        )
        frame.timeline = Curve.window(p, 0.18, 0.9)
        let offset = (target - base) * Self.filmSeconds
        frame.hud = .scrub(target: timestamp(target), offset: "+" + PlayerLogic.timestamp(offset))
        frame.hudOpacity = Curve.window(p, 0.18, 0.78)
        return frame
    }

    // MARK: Remote buttons

    private func remoteButtons(_ p: Double) -> GuideFrame {
        var frame = GuideFrame()
        frame.progress = 0.32 + 0.03 * p
        if p < 0.48 {
            // Play/Pause pauses, then resumes.
            frame.controls = Curve.window(p, 0.04, 0.46)
            frame.isPlaying = !(0.15..<0.33).contains(p)
            if (0.12..<0.18).contains(p) || (0.3..<0.36).contains(p) {
                frame.remote.pressed = .playPause
            }
        } else {
            // Back closes the open panel, then the controls.
            frame.controls = Curve.window(p, 0.5, 0.84)
            frame.panel = min(Curve.ramp(p, 0.5, 0.54), 1 - Curve.ramp(p, 0.64, 0.7))
            if (0.62..<0.68).contains(p) || (0.76..<0.82).contains(p) {
                frame.remote.pressed = .back
            }
        }
        frame.drift = p
        return frame
    }
}

// MARK: - Scene

/// One frame: the miniature player, with the Siri Remote beside it on tvOS.
struct GuideScene: View {
    let frame: GuideFrame
    let platform: PlayerGuidePlatform

    /// The remote's width and the gap before it, relative to the picture's
    /// height.
    private static let remoteWidth: CGFloat = 0.3
    private static let remoteGap: CGFloat = 0.1
    private static let pictureAspect: CGFloat = 16 / 9

    private var aspect: CGFloat {
        platform == .tv
            ? Self.pictureAspect + Self.remoteWidth + Self.remoteGap
            : Self.pictureAspect
    }

    var body: some View {
        GeometryReader { geo in
            let height = geo.size.height
            HStack(spacing: height * Self.remoteGap) {
                GuidePicture(frame: frame, platform: platform)
                    .frame(width: height * Self.pictureAspect, height: height)
                if platform == .tv {
                    GuideRemoteView(remote: frame.remote)
                        .frame(width: height * Self.remoteWidth, height: height)
                }
            }
        }
        .aspectRatio(aspect, contentMode: .fit)
    }
}

/// The miniature player: scenery, chrome, feedback, and the finger or pointer.
private struct GuidePicture: View {
    let frame: GuideFrame
    let platform: PlayerGuidePlatform

    var body: some View {
        GeometryReader { geo in
            let size = geo.size
            let u = size.width / 100
            ZStack {
                GuideScenery(brightness: frame.brightness, drift: frame.drift, unit: u, size: size)
                GuideZoneLines(zones: frame.zones, unit: u, size: size)

                if frame.timeline > 0 {
                    GuideTimeline(progress: frame.progress, unit: u, size: size, showsScrim: true)
                        .opacity(frame.timeline * (1 - frame.controls))
                }
                if frame.controls > 0 {
                    GuideChrome(frame: frame, platform: platform, unit: u, size: size)
                        .opacity(frame.controls)
                }
                if frame.panel > 0 {
                    GuidePanel(unit: u, size: size)
                        .offset(x: (1 - frame.panel) * 32 * u)
                }
                if let hud = frame.hud, frame.hudOpacity > 0 {
                    GuideHUDPill(hud: hud, unit: u)
                        .position(x: size.width / 2, y: 13 * u)
                        .opacity(frame.hudOpacity)
                }
                if let touch = frame.touch, touch.opacity > 0 {
                    GuideTouchMark(touch: touch, pointer: platform == .mac, unit: u, size: size)
                }
            }
            .frame(width: size.width, height: size.height)
        }
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card))
        .overlay {
            RoundedRectangle(cornerRadius: Theme.Radius.card)
                .strokeBorder(Theme.hairline, lineWidth: 1)
        }
    }
}

// MARK: - Scenery

/// A quiet night landscape standing in for the video.
private struct GuideScenery: View {
    let brightness: Double
    let drift: Double
    let unit: CGFloat
    let size: CGSize

    var body: some View {
        ZStack {
            Theme.background
            ZStack {
                LinearGradient(
                    colors: [Theme.surfaceLow, Theme.background],
                    startPoint: .top, endPoint: .bottom
                )
                Circle()
                    .fill(Theme.outline)
                    .frame(width: 9 * unit, height: 9 * unit)
                    .position(x: 74 * unit, y: size.height * 0.3)
                // Leaves on the right and comes back on the left once per loop.
                GuideCloud()
                    .fill(Theme.surfaceHigh)
                    .frame(width: 16 * unit, height: 6 * unit)
                    .position(x: (drift * 132 - 16) * unit, y: size.height * 0.4)
                GuideHill(crest: 0.58, peak: 0.3)
                    .fill(Theme.surface)
                GuideHill(crest: 0.72, peak: 0.7)
                    .fill(Theme.surfaceHigh)
            }
            .opacity(brightness)
        }
    }
}

private struct GuideCloud: Shape {
    func path(in rect: CGRect) -> Path {
        let w = rect.width
        let h = rect.height
        var path = Path()
        path.addRoundedRect(
            in: CGRect(x: rect.minX, y: rect.minY + h * 0.5, width: w, height: h * 0.5),
            cornerSize: CGSize(width: h * 0.25, height: h * 0.25)
        )
        path.addEllipse(in: CGRect(x: rect.minX + w * 0.14, y: rect.minY + h * 0.25, width: w * 0.36, height: h * 0.7))
        path.addEllipse(in: CGRect(x: rect.minX + w * 0.38, y: rect.minY, width: w * 0.4, height: h * 0.95))
        return path
    }
}

private struct GuideHill: Shape {
    /// Height of the ridge as a fraction of the picture, from the top.
    let crest: CGFloat
    /// Where along the width the ridge peaks.
    let peak: CGFloat

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let top = rect.height * crest
        path.move(to: CGPoint(x: 0, y: rect.maxY))
        path.addLine(to: CGPoint(x: 0, y: top + rect.height * 0.08))
        path.addQuadCurve(
            to: CGPoint(x: rect.width * peak, y: top),
            control: CGPoint(x: rect.width * peak * 0.5, y: top)
        )
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX, y: top + rect.height * 0.1),
            control: CGPoint(x: rect.width * (peak + (1 - peak) * 0.5), y: top)
        )
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}

private struct GuideZoneLines: View {
    let zones: GuideZones
    let unit: CGFloat
    let size: CGSize

    private var dividers: [CGFloat] {
        switch zones {
        case .none: []
        case .halves: [0.5]
        case .thirds: [1 / 3, 2 / 3]
        }
    }

    var body: some View {
        Path { path in
            for fraction in dividers {
                path.move(to: CGPoint(x: size.width * fraction, y: 12 * unit))
                path.addLine(to: CGPoint(x: size.width * fraction, y: size.height - 12 * unit))
            }
        }
        .stroke(Theme.outlineBright, style: StrokeStyle(lineWidth: max(1, 0.3 * unit), dash: [1.2 * unit, 1.2 * unit]))
    }
}

// MARK: - Chrome

/// The controls overlay in miniature: close and tools above, play/pause in
/// the middle, timeline below.
private struct GuideChrome: View {
    let frame: GuideFrame
    let platform: PlayerGuidePlatform
    let unit: CGFloat
    let size: CGSize

    private var toolIcons: [ImageResource] {
        switch platform {
        case .touch: [.pictureInPicture, .mobileRotateUnlock, .listTree, .sidebarRight]
        case .mac: [.pictureInPicture, .listTree, .sidebarRight]
        case .vision, .tv: [.listTree, .sidebarRight]
        }
    }

    var body: some View {
        let u = unit
        let chip = 4.6 * u
        ZStack {
            VStack(spacing: 0) {
                LinearGradient(colors: [Theme.background, .clear], startPoint: .top, endPoint: .bottom)
                    .frame(height: size.height * 0.3)
                Spacer(minLength: 0)
            }

            Capsule()
                .fill(Theme.surfaceHigh)
                .frame(width: 7 * u, height: chip)
                .overlay {
                    Image(.xmark)
                        .font(.system(size: 2 * u, weight: .bold))
                        .foregroundStyle(Theme.textPrimary)
                }
                .position(x: 6.5 * u, y: 5.5 * u)

            Capsule()
                .fill(Theme.outlineBright)
                .frame(width: 18 * u, height: 1.4 * u)
                .position(x: size.width / 2, y: 9.5 * u)
            Capsule()
                .fill(Theme.outline)
                .frame(width: 12 * u, height: 1.1 * u)
                .position(x: size.width / 2, y: 12 * u)

            // The audio chip sits first among the tools; it draws the
            // system's own glyph, so a plain disc stands in for it here.
            ForEach(Array(([nil] + toolIcons.map(Optional.some)).enumerated()), id: \.offset) { index, icon in
                let count = toolIcons.count + 1
                Circle()
                    .fill(Theme.surfaceHigh)
                    .frame(width: chip, height: chip)
                    .overlay {
                        if let icon {
                            Image(icon)
                                .font(.system(size: 2 * u, weight: .bold))
                                .foregroundStyle(Theme.textPrimary)
                        } else {
                            Image(systemName: "airplayaudio")
                                .font(.system(size: 2 * u, weight: .bold))
                                .foregroundStyle(Theme.textPrimary)
                        }
                    }
                    .position(
                        x: size.width - 3 * u - chip / 2 - CGFloat(count - 1 - index) * (chip + 1.2 * u),
                        y: 5.5 * u
                    )
            }

            Circle()
                .fill(Theme.surfaceHigh)
                .frame(width: 11 * u, height: 11 * u)
                .overlay {
                    Image(frame.isPlaying ? .pause : .play)
                        .font(.system(size: 4 * u, weight: .bold))
                        .foregroundStyle(Theme.textPrimary)
                }
                .overlay {
                    Circle().strokeBorder(Theme.hairline, lineWidth: 1)
                }
                .position(x: size.width / 2, y: size.height / 2)

            GuideTimeline(progress: frame.progress, unit: u, size: size, showsScrim: true)
        }
    }
}

private struct GuideTimeline: View {
    let progress: Double
    let unit: CGFloat
    let size: CGSize
    let showsScrim: Bool

    var body: some View {
        let u = unit
        let inset = 13 * u
        let track = size.width - inset * 2
        let y = size.height - 5 * u
        let fill = track * min(max(progress, 0), 1)
        ZStack {
            if showsScrim {
                VStack(spacing: 0) {
                    Spacer(minLength: 0)
                    LinearGradient(colors: [.clear, Theme.background], startPoint: .top, endPoint: .bottom)
                        .frame(height: size.height * 0.3)
                }
            }
            Text(PlayerLogic.timestamp(progress * GuideScript.filmSeconds))
                .font(Typography.text(1.9 * u, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(Theme.textPrimary)
                .fixedSize()
                .position(x: inset / 2, y: y)
            Text(PlayerLogic.timestamp(GuideScript.filmSeconds))
                .font(Typography.text(1.9 * u, weight: .medium))
                .monospacedDigit()
                .foregroundStyle(Theme.textSecondary)
                .fixedSize()
                .position(x: size.width - inset / 2, y: y)
            Capsule()
                .fill(Theme.surfaceHigh)
                .frame(width: track, height: 0.8 * u)
                .position(x: size.width / 2, y: y)
            Capsule()
                .fill(Theme.gold)
                .frame(width: max(fill, 0.8 * u), height: 0.8 * u)
                .position(x: inset + max(fill, 0.8 * u) / 2, y: y)
            Circle()
                .fill(Theme.gold)
                .frame(width: 2.2 * u, height: 2.2 * u)
                .position(x: inset + fill, y: y)
        }
    }
}

/// A side panel's silhouette sliding in from the trailing edge.
private struct GuidePanel: View {
    let unit: CGFloat
    let size: CGSize

    var body: some View {
        let u = unit
        let width = 32 * u
        VStack(alignment: .leading, spacing: 2 * u) {
            Capsule()
                .fill(Theme.outlineBright)
                .frame(width: 14 * u, height: 1.8 * u)
                .padding(.bottom, u)
            ForEach(0..<5, id: \.self) { row in
                Capsule()
                    .fill(row == 1 ? Theme.gold : Theme.surfaceHigh)
                    .frame(width: (row == 1 ? 18 : 24) * u, height: 2.2 * u)
            }
            Spacer(minLength: 0)
        }
        .padding(3 * u)
        .frame(width: width, height: size.height, alignment: .topLeading)
        .background(Theme.surfaceLow)
        .overlay(alignment: .leading) {
            Rectangle().fill(Theme.hairline).frame(width: 1)
        }
        .position(x: size.width - width / 2, y: size.height / 2)
    }
}

// MARK: - Feedback

/// PlayerHUDView's pill in miniature.
private struct GuideHUDPill: View {
    let hud: GuideHUD
    let unit: CGFloat

    var body: some View {
        let u = unit
        HStack(spacing: 1.6 * u) {
            switch hud {
            case .seek(let forward, let seconds):
                Image(forward ? .forward : .backward)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 2.2 * u, height: 2.2 * u)
                    .foregroundStyle(Theme.gold)
                value(String(localized: "\(seconds)s"))
            case .level(let caption, let level):
                self.caption(caption)
                ZStack(alignment: .leading) {
                    Capsule().fill(Theme.surface)
                    Capsule()
                        .fill(Theme.gold)
                        .frame(width: max(0.6 * u, 16 * u * min(max(level, 0), 1)))
                }
                .frame(width: 16 * u, height: 0.7 * u)
            case .speed(let rate):
                caption(String(localized: "Speed"))
                value(rate)
            case .scrub(let target, let offset):
                value(target)
                caption(offset)
            }
        }
        .padding(.horizontal, 2.4 * u)
        .padding(.vertical, 1.3 * u)
        .background(Theme.surfaceHigh, in: Capsule())
        .overlay {
            Capsule().strokeBorder(Theme.hairline, lineWidth: 1)
        }
        .fixedSize()
    }

    private func caption(_ text: String) -> some View {
        Text(text)
            .font(Typography.text(1.7 * unit, weight: .bold))
            .textCase(.uppercase)
            .kerning(0.15 * unit)
            .foregroundStyle(Theme.textSecondary)
    }

    private func value(_ text: String) -> some View {
        Text(text)
            .font(Typography.text(3 * unit, weight: .semibold))
            .monospacedDigit()
            .foregroundStyle(Theme.textPrimary)
    }
}

// MARK: - Finger and pointer

private struct GuideTouchMark: View {
    let touch: GuideTouch
    /// Draws the Mac's arrow pointer instead of a fingertip.
    let pointer: Bool
    let unit: CGFloat
    let size: CGSize

    var body: some View {
        let u = unit
        let point = CGPoint(x: touch.point.x * size.width, y: touch.point.y * size.height)
        ZStack {
            if let start = touch.trailStart {
                Path { path in
                    path.move(to: CGPoint(x: start.x * size.width, y: start.y * size.height))
                    path.addLine(to: point)
                }
                .stroke(Theme.goldGlow, style: StrokeStyle(lineWidth: 6 * u, lineCap: .round))
            }
            GuideContactRings(ripple: touch.ripple, hold: touch.hold, diameter: 6 * u, unit: u)
                .position(point)
            if pointer {
                GuidePointerArrow()
                    .fill(Theme.textPrimary)
                    .overlay {
                        GuidePointerArrow().stroke(Theme.background, lineWidth: max(1, 0.3 * u))
                    }
                    .frame(width: 4.4 * u, height: 6.6 * u)
                    .scaleEffect(touch.pressed ? 0.88 : 1, anchor: .topLeading)
                    // The arrow's tip, its top-left corner, rests on the point.
                    .position(x: point.x + 2.2 * u, y: point.y + 3.3 * u)
            } else {
                Circle()
                    .fill(Theme.textPrimary)
                    .frame(width: 6 * u, height: 6 * u)
                    .overlay {
                        Circle().strokeBorder(Theme.background, lineWidth: max(1, 0.3 * u))
                    }
                    .scaleEffect(touch.pressed ? 0.86 : 1)
                    .position(point)
            }
        }
        .opacity(touch.opacity)
    }
}

/// A tap's expanding ripple and a hold's filling ring around a contact.
private struct GuideContactRings: View {
    let ripple: Double?
    let hold: Double
    let diameter: CGFloat
    let unit: CGFloat

    var body: some View {
        ZStack {
            if let ripple {
                Circle()
                    .stroke(Theme.gold, lineWidth: max(1, 0.5 * unit))
                    .frame(width: diameter * (1 + 1.6 * ripple), height: diameter * (1 + 1.6 * ripple))
                    .opacity(1 - ripple)
            }
            if hold > 0 {
                Circle()
                    .trim(from: 0, to: hold)
                    .stroke(Theme.gold, style: StrokeStyle(lineWidth: max(1.5, 0.7 * unit), lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .frame(width: diameter * 1.6, height: diameter * 1.6)
            }
        }
    }
}

private struct GuidePointerArrow: Shape {
    func path(in rect: CGRect) -> Path {
        let w = rect.width
        let h = rect.height
        var path = Path()
        path.move(to: CGPoint(x: 0, y: 0))
        path.addLine(to: CGPoint(x: 0, y: h * 0.86))
        path.addLine(to: CGPoint(x: w * 0.3, y: h * 0.64))
        path.addLine(to: CGPoint(x: w * 0.52, y: h))
        path.addLine(to: CGPoint(x: w * 0.68, y: h * 0.93))
        path.addLine(to: CGPoint(x: w * 0.47, y: h * 0.58))
        path.addLine(to: CGPoint(x: w, y: h * 0.58))
        path.closeSubpath()
        return path.offsetBy(dx: rect.minX, dy: rect.minY)
    }
}

// MARK: - Siri Remote

private struct GuideRemoteView: View {
    let remote: GuideRemote

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width
            let h = geo.size.height
            let pad = w * 0.8
            let padCenter = CGPoint(x: w / 2, y: h * 0.22)
            let button = w * 0.3
            let left = w * 0.27
            let right = w * 0.73

            ZStack {
                RoundedRectangle(cornerRadius: w * 0.22)
                    .fill(Theme.surfaceHigh)
                    .overlay {
                        RoundedRectangle(cornerRadius: w * 0.22).strokeBorder(Theme.hairline, lineWidth: 1)
                    }

                Circle()
                    .fill(Theme.surface)
                    .overlay {
                        Circle().strokeBorder(
                            remote.pressed == .clickpad ? Theme.gold : Theme.outline,
                            lineWidth: max(1, w * 0.05)
                        )
                    }
                    .shadow(color: remote.pressed == .clickpad ? Theme.goldGlow : .clear, radius: w * 0.1)
                    .frame(width: pad, height: pad)
                    .position(padCenter)

                thumb(padCenter: padCenter, padRadius: pad / 2, width: w)

                key(.back, size: button) {
                    Image(.chevronLeft).font(.system(size: button * 0.4, weight: .bold))
                }
                .position(x: left, y: h * 0.5)
                key(nil, size: button) {
                    Image(.tv).font(.system(size: button * 0.36, weight: .bold))
                }
                .position(x: right, y: h * 0.5)
                key(.playPause, size: button) {
                    HStack(spacing: 0) {
                        Image(.play)
                        Image(.pause)
                    }
                    .font(.system(size: button * 0.24, weight: .bold))
                }
                .position(x: left, y: h * 0.66)
                key(nil, size: button) { EmptyView() }
                    .position(x: left, y: h * 0.82)
                Capsule()
                    .fill(Theme.surface)
                    .frame(width: button, height: h * 0.32)
                    .overlay {
                        VStack {
                            Text(verbatim: "+")
                            Spacer(minLength: 0)
                            Text(verbatim: "−")
                        }
                        .font(.system(size: button * 0.4, weight: .bold))
                        .foregroundStyle(Theme.textSecondary)
                        .padding(.vertical, button * 0.2)
                    }
                    .position(x: right, y: h * 0.74)
            }
        }
    }

    @ViewBuilder
    private func thumb(padCenter: CGPoint, padRadius: CGFloat, width: CGFloat) -> some View {
        if let thumb = remote.thumb, remote.thumbOpacity > 0 {
            let reach = padRadius * 0.62
            let point = CGPoint(x: padCenter.x + thumb.x * reach, y: padCenter.y + thumb.y * reach)
            let diameter = width * 0.24
            ZStack {
                if let start = remote.trailStart {
                    Path { path in
                        path.move(to: CGPoint(x: padCenter.x + start.x * reach, y: padCenter.y + start.y * reach))
                        path.addLine(to: point)
                    }
                    .stroke(Theme.goldGlow, style: StrokeStyle(lineWidth: diameter, lineCap: .round))
                }
                GuideContactRings(ripple: remote.ripple, hold: remote.hold, diameter: diameter, unit: width / 30)
                    .position(point)
                Circle()
                    .fill(Theme.textPrimary)
                    .frame(width: diameter, height: diameter)
                    .position(point)
            }
            .opacity(remote.thumbOpacity)
        }
    }

    private func key(
        _ id: GuideRemote.Key?,
        size: CGFloat,
        @ViewBuilder glyph: () -> some View
    ) -> some View {
        let pressed = id != nil && remote.pressed == id
        return Circle()
            .fill(Theme.surface)
            .overlay {
                glyph().foregroundStyle(pressed ? Theme.gold : Theme.textSecondary)
            }
            .overlay {
                Circle().strokeBorder(pressed ? Theme.gold : .clear, lineWidth: max(1, size * 0.08))
            }
            .shadow(color: pressed ? Theme.goldGlow : .clear, radius: size * 0.3)
            .scaleEffect(pressed ? 0.92 : 1)
            .frame(width: size, height: size)
    }
}

// MARK: - Legends

/// The guide's legends step a gold highlight down their rows, like a
/// finger pointing at each in turn; Reduce Motion leaves them unlit.
private enum GuideLegend {
    static let step: TimeInterval = 1.4

    static func active(at date: Date, since start: Date, count: Int, reduceMotion: Bool) -> Int? {
        guard !reduceMotion, count > 0 else { return nil }
        return Int(max(0, date.timeIntervalSince(start)) / step) % count
    }

    static var columns: [GridItem] {
        [GridItem(.adaptive(minimum: GuideMetrics.legendColumn), spacing: GuideMetrics.legendSpacing, alignment: .leading)]
    }
}

/// Sits a legend in the middle of its space, scrolling only when large
/// text or a long translation outgrows it. The inset keeps the highlight's
/// glow clear of the edges.
private struct GuideLegendFrame<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ViewThatFits(in: .vertical) {
            content
                .padding(GuideMetrics.glowInset)
            ScrollView {
                content
                    .padding(GuideMetrics.glowInset)
            }
            .scrollIndicators(.visible)
            .scrollIndicatorsFlash(onAppear: true)
        }
    }
}

private struct GuideShortcutLegend: View {
    let shortcuts: [PlayerGuideShortcut]
    let start: Date
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GuideLegendFrame {
            TimelineView(.periodic(from: start, by: GuideLegend.step)) { context in
                let active = GuideLegend.active(
                    at: context.date, since: start, count: shortcuts.count, reduceMotion: reduceMotion
                )
                LazyVGrid(columns: GuideLegend.columns, alignment: .leading, spacing: GuideMetrics.legendRowSpacing) {
                    ForEach(Array(shortcuts.enumerated()), id: \.element.id) { index, shortcut in
                        row(shortcut, isActive: index == active)
                    }
                }
                .animation(.easeOut(duration: 0.2), value: active)
            }
        }
    }

    private func row(_ shortcut: PlayerGuideShortcut, isActive: Bool) -> some View {
        HStack(spacing: GuideMetrics.legendSpacing / 2) {
            HStack(spacing: 4) {
                ForEach(shortcut.keys, id: \.self) { key in
                    GuideKeycap(label: key, isActive: isActive)
                }
            }
            .frame(minWidth: GuideMetrics.keycapColumn, alignment: .leading)
            Text(shortcut.action)
                .font(GuideMetrics.legendFont)
                .foregroundStyle(isActive ? Theme.textPrimary : Theme.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        // Keys and what they do are one entry.
        .accessibilityElement(children: .combine)
    }
}

private struct GuideKeycap: View {
    let label: String
    let isActive: Bool

    var body: some View {
        Text(verbatim: label)
            .font(GuideMetrics.keycapFont)
            .foregroundStyle(isActive ? Theme.gold : Theme.textPrimary)
            .lineLimit(1)
            .padding(.horizontal, label.count > 1 ? GuideMetrics.keycap * 0.3 : 0)
            .frame(minWidth: GuideMetrics.keycap, minHeight: GuideMetrics.keycap)
            .background(Theme.surfaceHigh, in: RoundedRectangle(cornerRadius: Theme.Radius.soft))
            .overlay {
                RoundedRectangle(cornerRadius: Theme.Radius.soft)
                    .strokeBorder(isActive ? Theme.gold : Theme.outline, lineWidth: 1)
            }
            .shadow(color: isActive ? Theme.goldGlow : .clear, radius: 6)
            .offset(y: isActive ? 1 : 0)
    }
}

private struct GuideToolLegend: View {
    let tools: [PlayerGuideTool]
    let start: Date
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        GuideLegendFrame {
            TimelineView(.periodic(from: start, by: GuideLegend.step)) { context in
                let active = GuideLegend.active(
                    at: context.date, since: start, count: tools.count, reduceMotion: reduceMotion
                )
                LazyVGrid(columns: GuideLegend.columns, alignment: .leading, spacing: GuideMetrics.legendRowSpacing) {
                    ForEach(Array(tools.enumerated()), id: \.element.id) { index, tool in
                        row(tool, isActive: index == active)
                    }
                }
                .animation(.easeOut(duration: 0.2), value: active)
            }
        }
    }

    private func row(_ tool: PlayerGuideTool, isActive: Bool) -> some View {
        HStack(spacing: GuideMetrics.legendSpacing / 2) {
            glyph(tool.glyph)
                .font(.system(size: GuideMetrics.chipGlyph, weight: .bold))
                .foregroundStyle(isActive ? Theme.gold : Theme.textPrimary)
                .frame(width: GuideMetrics.chip, height: GuideMetrics.chip)
                .background(Theme.surfaceHigh, in: Circle())
                .overlay {
                    Circle().strokeBorder(isActive ? Theme.gold : Theme.hairline, lineWidth: isActive ? 1.5 : 1)
                }
                .shadow(color: isActive ? Theme.goldGlow : .clear, radius: 8)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(tool.name)
                    .font(GuideMetrics.legendTitleFont)
                    .foregroundStyle(Theme.textPrimary)
                Text(tool.detail)
                    .font(GuideMetrics.legendFont)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func glyph(_ glyph: PlayerGuideTool.Glyph) -> some View {
        switch glyph {
        case .asset(let resource): Image(resource)
        case .system(let name): Image(systemName: name)
        }
    }
}

// MARK: - Metrics

/// Sizes for the guide, read from across the room on tvOS.
enum GuideMetrics {
    #if os(tvOS)
    static let titleFont = Typography.display(64)
    static let bodyFont = Typography.text(30)
    static let noteFont = Typography.text(24)
    static let legendTitleFont = Typography.text(28, weight: .semibold)
    static let legendFont = Typography.text(24)
    static let keycapFont = Typography.text(24, weight: .semibold)
    static let keycap: CGFloat = 52
    static let keycapColumn: CGFloat = 200
    static let chip: CGFloat = 72
    static let chipGlyph: CGFloat = 28
    static let legendColumn: CGFloat = 520
    static let legendSpacing: CGFloat = 40
    static let legendRowSpacing: CGFloat = 28
    static let glowInset: CGFloat = 16
    #else
    static let titleFont = Typography.headlineMD
    static let bodyFont = Typography.bodyLG
    static let noteFont = Typography.bodySM
    static let legendTitleFont = Typography.text(16, weight: .semibold)
    static let legendFont = Typography.bodySM
    static let keycapFont = Typography.text(13, weight: .semibold)
    static let keycap: CGFloat = 28
    static let keycapColumn: CGFloat = 96
    static let chip: CGFloat = 40
    static let chipGlyph: CGFloat = 18
    static let legendColumn: CGFloat = 250
    static let legendSpacing: CGFloat = 24
    static let legendRowSpacing: CGFloat = 14
    static let glowInset: CGFloat = 8
    #endif
}
