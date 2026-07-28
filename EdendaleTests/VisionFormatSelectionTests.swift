//
//  VisionFormatSelectionTests.swift
//  EdendaleTests
//

import Testing
@testable import Edendale

struct VisionFormatSelectionTests {
    @Test func presetTitlesMatchFormatSelectorChoices() {
        #expect(VisionFormatPreset.allCases.map(\.title) == [
            "Automatic",
            "2D",
            "Side-by-Side",
            "Top-Bottom",
            "VR180",
            "3D 360"
        ])
    }

    @Test func automaticPresetLeavesLayoutUnforced() {
        let selection = VisionFormatSelection()

        #expect(selection.preset == .automatic)
        #expect(selection.usesAutomaticDetection)
        #expect(selection.resolvedLayout == nil)
    }

    @Test func twoDimensionalPresetUsesNeutralRectilinearLayout() {
        let selection = VisionFormatSelection(preset: .twoDimensional)

        #expect(selection.resolvedLayout == VisionFormatLayout(
            packing: .none,
            projection: .rectilinear,
            eyeOrder: .leftFirst
        ))
    }

    @Test func sideBySidePresetUsesRectilinearHorizontalPacking() {
        let selection = VisionFormatSelection(preset: .sideBySide)

        #expect(selection.resolvedLayout == VisionFormatLayout(
            packing: .sideBySide,
            projection: .rectilinear,
            eyeOrder: .leftFirst
        ))
    }

    @Test func topBottomPresetUsesRectilinearOverUnderPacking() {
        let selection = VisionFormatSelection(preset: .topBottom)

        #expect(selection.resolvedLayout == VisionFormatLayout(
            packing: .overUnder,
            projection: .rectilinear,
            eyeOrder: .leftFirst
        ))
    }

    @Test func vr180PresetUsesHalfEquirectangularStereoLayout() {
        let selection = VisionFormatSelection(preset: .vr180)

        #expect(selection.resolvedLayout == VisionFormatLayout(
            packing: .sideBySide,
            projection: .halfEquirectangular,
            eyeOrder: .leftFirst
        ))
    }

    @Test func threeD360PresetUsesEquirectangularStereoLayout() {
        let selection = VisionFormatSelection(preset: .threeD360)

        #expect(selection.resolvedLayout == VisionFormatLayout(
            packing: .sideBySide,
            projection: .equirectangular,
            eyeOrder: .leftFirst
        ))
    }

    @Test func packingProjectionAndEyeOrderOverrideIndependently() {
        let selection = VisionFormatSelection(
            preset: .vr180,
            packing: .overUnder,
            projection: .equirectangular,
            eyeOrder: .reversed
        )

        #expect(selection.resolvedLayout == VisionFormatLayout(
            packing: .overUnder,
            projection: .equirectangular,
            eyeOrder: .reversed
        ))
    }

    @Test func aSingleOverridePreservesOtherPresetDefaults() {
        let selection = VisionFormatSelection(
            preset: .threeD360,
            packing: .overUnder
        )

        #expect(selection.resolvedLayout == VisionFormatLayout(
            packing: .overUnder,
            projection: .equirectangular,
            eyeOrder: .leftFirst
        ))
    }

    @Test func automaticWithOverrideBecomesAnExplicitLayout() {
        let selection = VisionFormatSelection(
            preset: .automatic,
            projection: .halfEquirectangular,
            eyeOrder: .reversed
        )

        #expect(!selection.usesAutomaticDetection)
        #expect(selection.resolvedLayout == VisionFormatLayout(
            packing: .none,
            projection: .halfEquirectangular,
            eyeOrder: .reversed
        ))
    }
}
