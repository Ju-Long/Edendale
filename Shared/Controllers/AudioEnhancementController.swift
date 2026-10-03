//
//  AudioEnhancementController.swift
//  Edendale
//
//  Persisted audio enhancement profiles backed by a 10-band parametric
//  equalizer. The selected profile, per-band user adjustments, and
//  booster state are stored in UserDefaults and applied to the active
//  playback engine on each media start and whenever the user changes
//  settings mid-playback.
//
//  Profile preamps are negative, offsetting each curve's peak band
//  boost to leave more headroom with the booster off.
//  The booster adds a fixed preamp gain through the same equalizer,
//  changing effective output immediately without touching volume.
//
//  visionOS native AVKit playback does not route through the custom
//  pipeline, so equalizer profiles have no effect when the system
//  player is active.
//

import Foundation
import Observation

// MARK: - Profile definition

enum AudioEnhancementProfile: String, CaseIterable, Identifiable, Sendable {
    case flat
    case movies
    case music
    case dialogue
    case nightMode

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .flat: String(localized: "Flat")
        case .movies: String(localized: "Movies")
        case .music: String(localized: "Music")
        case .dialogue: String(localized: "Dialogue")
        case .nightMode: String(localized: "Night Mode")
        }
    }

    /// Preamp set to the negative of the peak positive band boost so
    /// the presets leave more headroom with the booster off. This is
    /// equalization, not a limiter. The booster adds fixed gain on top.
    var preamp: Float {
        switch self {
        case .flat:      0
        case .movies:   -8
        case .music:    -4
        case .dialogue: -6
        case .nightMode: -5
        }
    }

    /// 10-band amplification values (dB) for center frequencies:
    /// 60, 170, 310, 600, 1k, 3k, 6k, 12k, 14k, 16k Hz.
    var bands: [Float] {
        switch self {
        case .flat:
            [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
        case .movies:
            [8, 5, 3, 0, 0, 2, 3, 2, 1, 0]
        case .music:
            [4, 2, 0, -1, -1, 2, 3, 3, 2, 1]
        case .dialogue:
            [-3, -1, 0, 5, 6, 5, 3, 1, 0, -1]
        case .nightMode:
            [-5, -2, 1, 4, 5, 5, 3, 1, 0, -1]
        }
    }

    var peakBandBoost: Float {
        bands.max() ?? 0
    }

    static let bandCount = 10
    static let amplificationRange: ClosedRange<Float> = -20...20
    static let preampRange: ClosedRange<Float> = -20...20

    static func clampAmplification(_ value: Float) -> Float {
        guard value.isFinite else { return 0 }
        return min(max(value, amplificationRange.lowerBound), amplificationRange.upperBound)
    }

    static func clampPreamp(_ value: Float) -> Float {
        guard value.isFinite else { return 0 }
        return min(max(value, preampRange.lowerBound), preampRange.upperBound)
    }

    static let bandFrequencyLabels = [
        "60", "170", "310", "600", "1k", "3k", "6k", "12k", "14k", "16k"
    ]
}

// MARK: - Controller

@MainActor
@Observable
final class AudioEnhancementController {

    private enum DefaultsKey {
        static let profile = "audio.enhancementProfile"
        static let userPreamp = "audio.enhancementPreamp"
        static let userBands = "audio.enhancementBands"
        static let booster = "audio.boosterEnabled"
    }

    private(set) var selectedProfile: AudioEnhancementProfile {
        didSet {
            defaults.set(selectedProfile.rawValue, forKey: DefaultsKey.profile)
            _userPreampAdjustment = 0
            _userBandAdjustments = Array(repeating: 0, count: AudioEnhancementProfile.bandCount)
            persistUserAdjustments()
            pushToProcessor()
        }
    }

    private(set) var boosterEnabled: Bool {
        didSet {
            defaults.set(boosterEnabled, forKey: DefaultsKey.booster)
            pushToProcessor()
        }
    }

    static let boosterGain: Float = 10

    private var _userPreampAdjustment: Float = 0
    var userPreampAdjustment: Float { _userPreampAdjustment }

    private var _userBandAdjustments: [Float] = Array(
        repeating: 0,
        count: AudioEnhancementProfile.bandCount
    )
    var userBandAdjustments: [Float] { _userBandAdjustments }

    @ObservationIgnored
    private let defaults: UserDefaults

    @ObservationIgnored
    let processor = AudioEQProcessor()

    @ObservationIgnored
    private weak var engine: PlaybackEngine?

    init(defaults: UserDefaults = AppIdentifiers.defaults) {
        self.defaults = defaults

        let stored = defaults.string(forKey: DefaultsKey.profile) ?? ""
        selectedProfile = AudioEnhancementProfile(rawValue: stored) ?? .movies
        boosterEnabled = defaults.bool(forKey: DefaultsKey.booster)

        if let storedPreamp = defaults.object(forKey: DefaultsKey.userPreamp) as? Float {
            _userPreampAdjustment = AudioEnhancementProfile.clampPreamp(storedPreamp)
        }
        if let storedBands = defaults.array(forKey: DefaultsKey.userBands) as? [Float],
           storedBands.count == AudioEnhancementProfile.bandCount {
            _userBandAdjustments = storedBands.map {
                AudioEnhancementProfile.clampAmplification($0)
            }
        }

        pushToProcessor()
    }

    // MARK: - Selection

    func selectProfile(_ profile: AudioEnhancementProfile) {
        guard profile != selectedProfile else { return }
        selectedProfile = profile
    }

    func setBooster(_ enabled: Bool) {
        boosterEnabled = enabled
    }

    // MARK: - Computed configuration

    var effectivePreamp: Float {
        var value = selectedProfile.preamp + _userPreampAdjustment
        if boosterEnabled {
            value += Self.boosterGain
        }
        return AudioEnhancementProfile.clampPreamp(value)
    }

    var effectiveBands: [Float] {
        zip(selectedProfile.bands, _userBandAdjustments).map { base, adj in
            AudioEnhancementProfile.clampAmplification(base + adj)
        }
    }

    /// True when the equalizer would have no audible effect.
    var isEffectivelyFlat: Bool {
        effectivePreamp == 0 && effectiveBands.allSatisfy { $0 == 0 }
    }

    // MARK: - User adjustments

    func setUserPreampAdjustment(_ value: Float) {
        _userPreampAdjustment = AudioEnhancementProfile.clampPreamp(value)
        persistUserAdjustments()
        pushToProcessor()
    }

    func setUserBandAdjustment(_ value: Float, at index: Int) {
        guard index >= 0, index < AudioEnhancementProfile.bandCount else { return }
        _userBandAdjustments[index] = AudioEnhancementProfile.clampAmplification(value)
        persistUserAdjustments()
        pushToProcessor()
    }

    func resetUserAdjustments() {
        _userPreampAdjustment = 0
        _userBandAdjustments = Array(repeating: 0, count: AudioEnhancementProfile.bandCount)
        persistUserAdjustments()
        pushToProcessor()
    }

    var hasUserAdjustments: Bool {
        _userPreampAdjustment != 0
            || _userBandAdjustments.contains(where: { $0 != 0 })
    }

    // MARK: - Engine integration

    func apply(to engine: PlaybackEngine) {
        if self.engine !== engine { detach() }
        self.engine = engine
        engine.installAudioProcessor(isEffectivelyFlat ? nil : processor)
    }

    func detach() {
        engine?.installAudioProcessor(nil)
        engine = nil
    }

    // MARK: - Private

    private func persistUserAdjustments() {
        defaults.set(_userPreampAdjustment, forKey: DefaultsKey.userPreamp)
        defaults.set(_userBandAdjustments, forKey: DefaultsKey.userBands)
    }

    private func pushToProcessor() {
        processor.update(preamp: effectivePreamp, bands: effectiveBands)
        if let engine {
            engine.installAudioProcessor(isEffectivelyFlat ? nil : processor)
        }
    }
}
