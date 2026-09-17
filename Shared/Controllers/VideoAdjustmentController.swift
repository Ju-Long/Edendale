import Foundation
import Observation

nonisolated enum VideoAdjustment: String, CaseIterable, Identifiable, Sendable {
    case brightness, contrast, gamma, saturation, hue

    var id: String { rawValue }

    var title: String {
        switch self {
        case .brightness: String(localized: "Brightness")
        case .contrast: String(localized: "Contrast")
        case .gamma: String(localized: "Gamma")
        case .saturation: String(localized: "Saturation")
        case .hue: String(localized: "Hue")
        }
    }

    var range: ClosedRange<Float> {
        switch self {
        case .brightness, .contrast: 0...2
        case .gamma: 0.25...3
        case .saturation: 0...3
        case .hue: 0...360
        }
    }

    var neutral: Float { self == .hue ? 0 : 1 }
    var step: Float { self == .hue ? 5 : 0.05 }

    func normalized(_ value: Float) -> Float {
        guard value.isFinite else { return neutral }
        let bounded = min(max(value, range.lowerBound), range.upperBound)
        return min(max((bounded / step).rounded() * step, range.lowerBound), range.upperBound)
    }

    func label(for value: Float) -> String {
        if self == .hue { return "\(Int(value.rounded()))°" }
        return value.formatted(.number.precision(.fractionLength(2)))
    }
}

/// Device-local picture preferences. Neutral values bypass filtering.
nonisolated struct VideoAdjustmentValues: Codable, Equatable, Sendable {
    var brightness: Float = 1
    var contrast: Float = 1
    var gamma: Float = 1
    var saturation: Float = 1
    var hue: Float = 0

    subscript(_ adjustment: VideoAdjustment) -> Float {
        get {
            switch adjustment {
            case .brightness: brightness
            case .contrast: contrast
            case .gamma: gamma
            case .saturation: saturation
            case .hue: hue
            }
        }
        set {
            let value = adjustment.normalized(newValue)
            switch adjustment {
            case .brightness: brightness = value
            case .contrast: contrast = value
            case .gamma: gamma = value
            case .saturation: saturation = value
            case .hue: hue = value
            }
        }
    }

    var isNeutral: Bool { self == Self() }
}

@MainActor
@Observable
final class VideoAdjustmentController {
    private static let defaultsKey = "video.adjustments"
    private(set) var values: VideoAdjustmentValues
    private(set) var isShowingOriginal = false
    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private weak var engine: PlaybackEngine?

    init(defaults: UserDefaults = AppIdentifiers.defaults) {
        self.defaults = defaults
        var restored = defaults.data(forKey: Self.defaultsKey)
            .flatMap { try? JSONDecoder().decode(VideoAdjustmentValues.self, from: $0) }
            ?? VideoAdjustmentValues()
        for adjustment in VideoAdjustment.allCases {
            restored[adjustment] = restored[adjustment]
        }
        values = restored
    }

    var effectiveValues: VideoAdjustmentValues {
        isShowingOriginal ? VideoAdjustmentValues() : values
    }

    func set(_ adjustment: VideoAdjustment, to value: Float) {
        values[adjustment] = value
        isShowingOriginal = false
        persistAndApply()
    }

    func showOriginal(_ show: Bool) {
        isShowingOriginal = show
        apply()
    }

    func reset() {
        values = VideoAdjustmentValues()
        isShowingOriginal = false
        persistAndApply()
    }

    func apply(to engine: PlaybackEngine) {
        if self.engine !== engine { detach() }
        self.engine = engine
        apply()
    }

    func detach() {
        if let pipeline = engine?.enhancementPipeline {
            pipeline.adjustments = VideoAdjustmentValues()
        }
        engine = nil
        isShowingOriginal = false
    }

    private func persistAndApply() {
        if let data = try? JSONEncoder().encode(values) {
            defaults.set(data, forKey: Self.defaultsKey)
        }
        apply()
    }

    private func apply() {
        guard let pipeline = engine?.enhancementPipeline else { return }
        let current = effectiveValues
        pipeline.adjustments = current
    }
}
