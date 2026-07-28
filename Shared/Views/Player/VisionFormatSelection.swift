//
//  VisionFormatSelection.swift
//  Edendale
//
//  Platform-neutral format choices for visionOS playback. Media-framework
//  types are deliberately kept out so preset and override behavior can be
//  verified by the regular macOS unit-test target.
//

import Foundation

nonisolated enum VisionFormatPreset: String, CaseIterable, Codable, Hashable, Sendable {
    case automatic
    case twoDimensional
    case sideBySide
    case topBottom
    case vr180
    case threeD360

    var title: String {
        switch self {
        case .automatic: String(localized: "Automatic")
        case .twoDimensional: "2D"
        case .sideBySide: String(localized: "Side-by-Side")
        case .topBottom: String(localized: "Top-Bottom")
        case .vr180: "VR180"
        case .threeD360: String(localized: "3D 360")
        }
    }

    /// The preset's starting layout. Automatic leaves the source untouched
    /// until inspection chooses a path or the user supplies an override.
    var defaultLayout: VisionFormatLayout? {
        switch self {
        case .automatic:
            nil
        case .twoDimensional:
            VisionFormatLayout(
                packing: .none,
                projection: .rectilinear,
                eyeOrder: .leftFirst
            )
        case .sideBySide:
            VisionFormatLayout(
                packing: .sideBySide,
                projection: .rectilinear,
                eyeOrder: .leftFirst
            )
        case .topBottom:
            VisionFormatLayout(
                packing: .overUnder,
                projection: .rectilinear,
                eyeOrder: .leftFirst
            )
        case .vr180:
            VisionFormatLayout(
                packing: .sideBySide,
                projection: .halfEquirectangular,
                eyeOrder: .leftFirst
            )
        case .threeD360:
            VisionFormatLayout(
                packing: .sideBySide,
                projection: .equirectangular,
                eyeOrder: .leftFirst
            )
        }
    }
}

nonisolated enum VisionFramePacking: String, CaseIterable, Codable, Hashable, Sendable {
    case none
    case sideBySide
    case overUnder
}

nonisolated enum VisionVideoProjection: String, CaseIterable, Codable, Hashable, Sendable {
    case rectilinear
    case halfEquirectangular
    case equirectangular
}

nonisolated enum VisionEyeOrder: String, CaseIterable, Codable, Hashable, Sendable {
    case leftFirst
    case reversed
}

nonisolated struct VisionFormatLayout: Codable, Equatable, Hashable, Sendable {
    let packing: VisionFramePacking
    let projection: VisionVideoProjection
    let eyeOrder: VisionEyeOrder
}

nonisolated struct VisionFormatSelection: Codable, Equatable, Hashable, Sendable {
    var preset: VisionFormatPreset
    var packingOverride: VisionFramePacking?
    var projectionOverride: VisionVideoProjection?
    var eyeOrderOverride: VisionEyeOrder?

    init(
        preset: VisionFormatPreset = .automatic,
        packing: VisionFramePacking? = nil,
        projection: VisionVideoProjection? = nil,
        eyeOrder: VisionEyeOrder? = nil
    ) {
        self.preset = preset
        self.packingOverride = packing
        self.projectionOverride = projection
        self.eyeOrderOverride = eyeOrder
    }

    /// True only while inspection remains fully in control of the format.
    var usesAutomaticDetection: Bool {
        preset == .automatic
            && packingOverride == nil
            && projectionOverride == nil
            && eyeOrderOverride == nil
    }

    /// The fully specified layout to hand to a renderer. A fully automatic
    /// selection has no forced layout. If an advanced override is applied to
    /// Automatic, unspecified axes start from neutral 2D defaults.
    var resolvedLayout: VisionFormatLayout? {
        let base: VisionFormatLayout
        if let defaultLayout = preset.defaultLayout {
            base = defaultLayout
        } else {
            guard !usesAutomaticDetection,
                  let neutralLayout = VisionFormatPreset.twoDimensional.defaultLayout
            else { return nil }
            base = neutralLayout
        }

        return VisionFormatLayout(
            packing: packingOverride ?? base.packing,
            projection: projectionOverride ?? base.projection,
            eyeOrder: eyeOrderOverride ?? base.eyeOrder
        )
    }
}
