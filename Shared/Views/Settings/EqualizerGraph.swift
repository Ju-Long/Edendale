//
//  EqualizerGraph.swift
//  Edendale
//
//  The ten equalizer bands drawn as one curve the user shapes directly, for
//  Settings → Audio on iPhone, iPad, Mac, and Vision Pro. The center line is
//  0 dB; a profile sets where each band's point sits, and dragging a point
//  up or down moves that band in 1 dB steps. Each point is a ring that shows
//  it can be moved; it fills and grows while held, with its level above it.
//  Only the points take touches, so a swipe elsewhere still scrolls the page.
//  Apple TV keeps remote-operated steppers instead.
//

import SwiftUI

/// "+5 dB", "0 dB", "−3 dB": how Settings writes an equalizer level.
enum DecibelLabel {
    static func text(_ value: Float) -> String {
        let rounded = Int(value.rounded())
        if rounded > 0 {
            return "+\(rounded) dB"
        } else if rounded < 0 {
            return "\(rounded) dB"
        }
        return "0 dB"
    }
}

#if !os(tvOS)
struct EqualizerGraph: View {
    /// Each band's level in dB.
    let levels: [Float]
    let frequencyLabels: [String]
    let range: ClosedRange<Float>
    let onChange: (_ band: Int, _ level: Float) -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The point being dragged and its level when it was grabbed. The drag
    /// moves it from there, so grabbing a point off-center doesn't make it
    /// jump. Cleared when the touch ends or the system cancels it.
    @GestureState private var grab: Grab?
    @State private var hoveredBand: Int?

    private struct Grab: Equatable {
        let band: Int
        let startLevel: Float
    }

    private static let coordinateSpace = "EqualizerGraph"

    var body: some View {
        GeometryReader { proxy in
            let plot = EqualizerPlot(size: proxy.size, count: levels.count, range: range)

            ZStack(alignment: .topLeading) {
                grid(plot)

                EqualizerCurve(
                    levels: EqualizerLevels(levels),
                    range: range
                )
                .stroke(
                    Theme.gold,
                    style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round)
                )
                .accessibilityHidden(true)

                ForEach(levels.indices, id: \.self) { band in
                    handle(band, plot: plot)
                }

                if let grab, levels.indices.contains(grab.band) {
                    levelBadge(for: grab.band, plot: plot)
                }
            }
            .coordinateSpace(.named(Self.coordinateSpace))
        }
        .frame(height: EqualizerPlot.height)
        .padding(.vertical, 4)
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Equalizer"))
        .modifier(EqualizerHaptics(heldBand: grab?.band, heldLevel: heldLevel))
    }

    /// The held point's level, which ticks a haptic at each 1 dB step.
    private var heldLevel: Float? {
        guard let grab, levels.indices.contains(grab.band) else { return nil }
        return levels[grab.band]
    }

    private var highlightAnimation: Animation? {
        reduceMotion ? nil : .snappy(duration: 0.18)
    }

    // MARK: - Grid

    private func grid(_ plot: EqualizerPlot) -> some View {
        ZStack(alignment: .topLeading) {
            // One faint column per band, so each point reads as sitting on
            // its own frequency.
            Path { path in
                for band in levels.indices {
                    path.move(to: CGPoint(x: plot.x(band), y: plot.rect.minY))
                    path.addLine(to: CGPoint(x: plot.x(band), y: plot.rect.maxY))
                }
            }
            .stroke(Theme.hairline, lineWidth: 1)

            ForEach(plot.gridLevels, id: \.self) { level in
                let y = plot.y(level)
                Path { path in
                    path.move(to: CGPoint(x: plot.rect.minX, y: y))
                    path.addLine(to: CGPoint(x: plot.rect.maxX, y: y))
                }
                .stroke(
                    level == 0 ? Theme.outlineBright : Theme.outline,
                    style: StrokeStyle(
                        lineWidth: 1,
                        dash: level == 0 || abs(level) == range.upperBound ? [] : [2, 4]
                    )
                )

                Text(verbatim: Self.axisLabel(level))
                    .font(Typography.text(10, weight: level == 0 ? .bold : .medium))
                    .monospacedDigit()
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize()
                    .position(x: EqualizerPlot.gutter / 2 - 2, y: y)
            }

            ForEach(levels.indices, id: \.self) { band in
                Text(verbatim: frequencyLabels.indices.contains(band) ? frequencyLabels[band] : "")
                    .font(Typography.text(11, weight: .medium))
                    .foregroundStyle(grab?.band == band ? Theme.gold : Theme.textSecondary)
                    .fixedSize()
                    .position(x: plot.x(band), y: plot.labelY)
            }

            Text(verbatim: "Hz")
                .font(Typography.text(10, weight: .medium))
                .foregroundStyle(Theme.textSecondary)
                .fixedSize()
                .position(x: EqualizerPlot.gutter / 2 - 2, y: plot.labelY)
        }
        .accessibilityHidden(true)
    }

    private static func axisLabel(_ level: Float) -> String {
        let value = Int(level)
        if value > 0 { return "+\(value)" }
        // A true minus sign, the width of the plus.
        if value < 0 { return "\u{2212}\(-value)" }
        return "0"
    }

    // MARK: - Points

    private func handle(_ band: Int, plot: EqualizerPlot) -> some View {
        let isHeld = grab?.band == band
        let isHovered = hoveredBand == band && grab == nil
        let diameter: CGFloat = isHeld ? 22 : (isHovered ? 17 : 14)

        return ZStack {
            Circle()
                .fill(Theme.goldGlow)
                .frame(width: 42, height: 42)
                .scaleEffect(isHeld ? 1 : 0.4)
                .opacity(isHeld ? 1 : 0)

            Circle()
                .fill(isHeld ? Theme.gold : Theme.surfaceHigh)
                .overlay {
                    Circle().strokeBorder(Theme.gold, lineWidth: 2)
                }
                .frame(width: diameter, height: diameter)
        }
        .animation(highlightAnimation, value: isHeld)
        .animation(highlightAnimation, value: isHovered)
        // A touch target as tall as a fingertip and as wide as the band's
        // column, so neighbouring points never steal each other's drags.
        .frame(width: max(plot.columnWidth, 24), height: 44)
        .contentShape(Rectangle())
        #if os(visionOS)
        .contentShape(.hoverEffect, Circle().inset(by: 6))
        .hoverEffect()
        #endif
        .onHover { inside in
            if inside {
                hoveredBand = band
            } else if hoveredBand == band {
                hoveredBand = nil
            }
        }
        .gesture(drag(band, plot: plot))
        .position(x: plot.x(band), y: plot.y(levels[band]))
        .accessibilityElement()
        .accessibilityLabel(Text(verbatim: bandName(band)))
        .accessibilityValue(Text(verbatim: DecibelLabel.text(levels[band])))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: step(band, by: 1)
            case .decrement: step(band, by: -1)
            @unknown default: break
            }
        }
    }

    private func drag(_ band: Int, plot: EqualizerPlot) -> some Gesture {
        // Starts on touch-down, so a point lights up as soon as it is held.
        DragGesture(minimumDistance: 0, coordinateSpace: .named(Self.coordinateSpace))
            .updating($grab) { value, grab, _ in
                if grab == nil {
                    grab = Grab(band: band, startLevel: levels[band])
                }
                guard let start = grab?.startLevel else { return }
                let moved = Float(-value.translation.height / plot.pointsPerDecibel)
                let level = clamp((start + moved).rounded())
                if level != levels[band] {
                    onChange(band, level)
                }
            }
    }

    private func step(_ band: Int, by delta: Float) {
        let level = clamp(levels[band] + delta)
        if level != levels[band] {
            onChange(band, level)
        }
    }

    private func clamp(_ level: Float) -> Float {
        min(max(level, range.lowerBound), range.upperBound)
    }

    private func bandName(_ band: Int) -> String {
        (frequencyLabels.indices.contains(band) ? frequencyLabels[band] : "") + " Hz"
    }

    /// The held point's level, above it — or below it when the point is
    /// near the top — and kept inside the graph at the edges.
    private func levelBadge(for band: Int, plot: EqualizerPlot) -> some View {
        let pointY = plot.y(levels[band])
        let above = pointY - 32
        let y = above < 12 ? pointY + 32 : above
        let x = min(max(plot.x(band), plot.rect.minX + 26), plot.rect.maxX - 26)

        return Text(verbatim: DecibelLabel.text(levels[band]))
            .font(Typography.text(12, weight: .bold))
            .monospacedDigit()
            .foregroundStyle(Theme.onGold)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Theme.gold, in: Capsule())
            .fixedSize()
            .position(x: x, y: y)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

/// A tap when a point is grabbed and a tick at each 1 dB step. Vision Pro
/// has no haptics to play them.
private struct EqualizerHaptics: ViewModifier {
    let heldBand: Int?
    let heldLevel: Float?

    func body(content: Content) -> some View {
        #if os(visionOS)
        content
        #else
        content
            .sensoryFeedback(.impact(weight: .light), trigger: heldBand) { _, new in
                new != nil
            }
            .sensoryFeedback(.selection, trigger: heldLevel) { old, new in
                old != nil && new != nil
            }
        #endif
    }
}

// MARK: - Geometry

/// Where the graph puts each band and level. Shared by the view and the
/// curve so the points sit exactly on the line.
private struct EqualizerPlot {
    static let height: CGFloat = 212
    /// Room on the leading edge for the dB scale.
    static let gutter: CGFloat = 30
    /// Room above +20 dB and below −20 dB for a whole point.
    static let verticalInset: CGFloat = 12
    static let labelHeight: CGFloat = 18

    let rect: CGRect
    let count: Int
    let range: ClosedRange<Float>

    init(size: CGSize, count: Int, range: ClosedRange<Float>) {
        rect = CGRect(
            x: Self.gutter,
            y: Self.verticalInset,
            width: max(size.width - Self.gutter, 1),
            height: max(size.height - Self.labelHeight - Self.verticalInset * 2, 1)
        )
        self.count = max(count, 1)
        self.range = range
    }

    var columnWidth: CGFloat { rect.width / CGFloat(count) }

    var pointsPerDecibel: CGFloat {
        rect.height / CGFloat(range.upperBound - range.lowerBound)
    }

    var labelY: CGFloat { rect.maxY + Self.verticalInset + Self.labelHeight / 2 - 2 }

    /// The scale's ruled levels: both limits, halfway to each, and 0 dB.
    var gridLevels: [Float] {
        let top = range.upperBound
        let bottom = range.lowerBound
        return [top, top / 2, 0, bottom / 2, bottom]
    }

    func x(_ band: Int) -> CGFloat {
        rect.minX + (CGFloat(band) + 0.5) * columnWidth
    }

    func y(_ level: Float) -> CGFloat {
        let span = CGFloat(range.upperBound - range.lowerBound)
        let fraction = CGFloat(level - range.lowerBound) / span
        return rect.maxY - fraction * rect.height
    }
}

/// The curve through every band's point, run flat out to both edges.
/// Animatable, so choosing a profile sweeps the line into its new shape.
private struct EqualizerCurve: Shape {
    var levels: EqualizerLevels
    let range: ClosedRange<Float>

    var animatableData: EqualizerLevels {
        get { levels }
        set { levels = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let values = levels.values
        guard !values.isEmpty else { return Path() }
        let plot = EqualizerPlot(size: rect.size, count: values.count, range: range)
        let points = values.indices.map { band in
            CGPoint(x: plot.x(band), y: plot.y(Float(values[band])))
        }

        var path = Path()
        path.move(to: CGPoint(x: plot.rect.minX, y: points[0].y))
        path.addLine(to: points[0])
        // Catmull–Rom through the points, with the handles kept inside the
        // plot so the line never overshoots past ±20 dB.
        for index in 0..<(points.count - 1) {
            let p0 = points[max(index - 1, 0)]
            let p1 = points[index]
            let p2 = points[index + 1]
            let p3 = points[min(index + 2, points.count - 1)]
            let control1 = CGPoint(
                x: p1.x + (p2.x - p0.x) / 6,
                y: clampY(p1.y + (p2.y - p0.y) / 6, plot)
            )
            let control2 = CGPoint(
                x: p2.x - (p3.x - p1.x) / 6,
                y: clampY(p2.y - (p3.y - p1.y) / 6, plot)
            )
            path.addCurve(to: p2, control1: control1, control2: control2)
        }
        path.addLine(to: CGPoint(x: plot.rect.maxX, y: points[points.count - 1].y))
        return path
    }

    private func clampY(_ y: CGFloat, _ plot: EqualizerPlot) -> CGFloat {
        min(max(y, plot.rect.minY), plot.rect.maxY)
    }
}

/// The band levels as one animatable vector.
private struct EqualizerLevels: VectorArithmetic {
    var values: [Double]

    init(_ levels: [Float]) {
        values = levels.map(Double.init)
    }

    private init(values: [Double]) {
        self.values = values
    }

    static var zero: EqualizerLevels { EqualizerLevels(values: []) }

    static func + (lhs: EqualizerLevels, rhs: EqualizerLevels) -> EqualizerLevels {
        combine(lhs, rhs, +)
    }

    static func - (lhs: EqualizerLevels, rhs: EqualizerLevels) -> EqualizerLevels {
        combine(lhs, rhs, -)
    }

    mutating func scale(by rhs: Double) {
        values = values.map { $0 * rhs }
    }

    var magnitudeSquared: Double {
        values.reduce(0) { $0 + $1 * $1 }
    }

    /// `zero` is empty, so a missing value counts as 0.
    private static func combine(
        _ lhs: EqualizerLevels,
        _ rhs: EqualizerLevels,
        _ operation: (Double, Double) -> Double
    ) -> EqualizerLevels {
        let count = max(lhs.values.count, rhs.values.count)
        return EqualizerLevels(values: (0..<count).map { index in
            operation(
                index < lhs.values.count ? lhs.values[index] : 0,
                index < rhs.values.count ? rhs.values[index] : 0
            )
        })
    }
}
#endif
