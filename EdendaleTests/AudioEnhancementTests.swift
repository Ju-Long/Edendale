//
//  AudioEnhancementTests.swift
//  EdendaleTests
//
//  Unit tests for audio enhancement profiles (ENH-04) and audio
//  booster defaults (ENH-05).
//

import Foundation
import Testing
import SwiftVLC
@testable import Edendale

@MainActor
struct AudioEnhancementProfileTests {

    // MARK: - Profile defaults

    @Test func defaultProfileIsMovies() {
        let defaults = UserDefaults(suiteName: "test.audio.\(UUID().uuidString)")!
        let controller = AudioEnhancementController(defaults: defaults)
        #expect(controller.selectedProfile == .movies)
    }

    @Test func allProfilesHaveCorrectBandCount() {
        for profile in AudioEnhancementProfile.allCases {
            #expect(
                profile.bands.count == AudioEnhancementProfile.bandCount,
                "\(profile.displayName) has \(profile.bands.count) bands, expected \(AudioEnhancementProfile.bandCount)"
            )
        }
    }

    @Test func flatProfileHasZeroBandsAndPreamp() {
        let flat = AudioEnhancementProfile.flat
        #expect(flat.preamp == 0)
        #expect(flat.bands.allSatisfy { $0 == 0 })
    }

    @Test func profileBandsAreWithinAmplificationRange() {
        let range = AudioEnhancementProfile.amplificationRange
        for profile in AudioEnhancementProfile.allCases {
            #expect(range.contains(profile.preamp), "\(profile.displayName) preamp \(profile.preamp) out of range")
            for (i, band) in profile.bands.enumerated() {
                #expect(range.contains(band), "\(profile.displayName) band \(i) value \(band) out of range")
            }
        }
    }

    @Test func bandFrequencyLabelsMatchBandCount() {
        #expect(AudioEnhancementProfile.bandFrequencyLabels.count == AudioEnhancementProfile.bandCount)
    }

    // MARK: - Clamping

    @Test func clampAmplificationClampsToRange() {
        #expect(AudioEnhancementProfile.clampAmplification(-25) == -20)
        #expect(AudioEnhancementProfile.clampAmplification(25) == 20)
        #expect(AudioEnhancementProfile.clampAmplification(0) == 0)
        #expect(AudioEnhancementProfile.clampAmplification(-20) == -20)
        #expect(AudioEnhancementProfile.clampAmplification(20) == 20)
    }

    @Test func clampPreampClampsToRange() {
        #expect(AudioEnhancementProfile.clampPreamp(-25) == -20)
        #expect(AudioEnhancementProfile.clampPreamp(25) == 20)
        #expect(AudioEnhancementProfile.clampPreamp(5) == 5)
    }

    // MARK: - Persistence

    @Test func selectedProfilePersistsToDefaults() {
        let defaults = UserDefaults(suiteName: "test.audio.\(UUID().uuidString)")!
        let controller = AudioEnhancementController(defaults: defaults)
        controller.selectProfile(.dialogue)
        #expect(defaults.string(forKey: "audio.enhancementProfile") == "dialogue")
    }

    @Test func persistedProfileIsRestoredOnInit() {
        let suiteName = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.set("nightMode", forKey: "audio.enhancementProfile")

        let controller = AudioEnhancementController(defaults: defaults)
        #expect(controller.selectedProfile == .nightMode)
    }

    @Test func invalidPersistedProfileFallsBackToMovies() {
        let defaults = UserDefaults(suiteName: "test.audio.\(UUID().uuidString)")!
        defaults.set("nonexistent", forKey: "audio.enhancementProfile")

        let controller = AudioEnhancementController(defaults: defaults)
        #expect(controller.selectedProfile == .movies)
    }

    @Test func userAdjustmentsPersistAndRestore() {
        let suiteName = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!

        let controller = AudioEnhancementController(defaults: defaults)
        controller.setUserPreampAdjustment(5)
        controller.setUserBandAdjustment(3, at: 0)
        controller.setUserBandAdjustment(-2, at: 9)

        let restored = AudioEnhancementController(defaults: defaults)
        #expect(restored.userPreampAdjustment == 5)
        #expect(restored.userBandAdjustments[0] == 3)
        #expect(restored.userBandAdjustments[9] == -2)
    }

    @Test func changingProfileResetsUserAdjustments() {
        let defaults = UserDefaults(suiteName: "test.audio.\(UUID().uuidString)")!
        let controller = AudioEnhancementController(defaults: defaults)
        controller.setUserPreampAdjustment(5)
        controller.setUserBandAdjustment(3, at: 0)

        controller.selectProfile(.music)
        #expect(controller.userPreampAdjustment == 0)
        #expect(controller.userBandAdjustments.allSatisfy { $0 == 0 })
    }

    // MARK: - Effective computation

    @Test func effectiveValuesAddProfileAndUserAdjustment() {
        let defaults = UserDefaults(suiteName: "test.audio.\(UUID().uuidString)")!
        let controller = AudioEnhancementController(defaults: defaults)
        controller.selectProfile(.movies)

        controller.setUserPreampAdjustment(2)
        #expect(controller.effectivePreamp == AudioEnhancementProfile.movies.preamp + 2)

        controller.setUserBandAdjustment(5, at: 0)
        let expectedBand0 = AudioEnhancementProfile.movies.bands[0] + 5
        #expect(controller.effectiveBands[0] == AudioEnhancementProfile.clampAmplification(expectedBand0))
    }

    @Test func effectiveValuesClampAtBoundaries() {
        let defaults = UserDefaults(suiteName: "test.audio.\(UUID().uuidString)")!
        let controller = AudioEnhancementController(defaults: defaults)
        controller.selectProfile(.movies)

        controller.setUserPreampAdjustment(20)
        #expect(controller.effectivePreamp == AudioEnhancementProfile.movies.preamp + 20)

        controller.setUserBandAdjustment(20, at: 0)
        #expect(controller.effectiveBands[0] == 20)
    }

    @Test func isFlatDetectsNoEnhancement() {
        let defaults = UserDefaults(suiteName: "test.audio.\(UUID().uuidString)")!
        let controller = AudioEnhancementController(defaults: defaults)

        controller.selectProfile(.flat)
        #expect(controller.isEffectivelyFlat)

        controller.setUserBandAdjustment(1, at: 0)
        #expect(!controller.isEffectivelyFlat)

        controller.resetUserAdjustments()
        #expect(controller.isEffectivelyFlat)
    }

    @Test func hasUserAdjustmentsTracksNonZero() {
        let defaults = UserDefaults(suiteName: "test.audio.\(UUID().uuidString)")!
        let controller = AudioEnhancementController(defaults: defaults)

        #expect(!controller.hasUserAdjustments)

        controller.setUserPreampAdjustment(1)
        #expect(controller.hasUserAdjustments)

        controller.resetUserAdjustments()
        #expect(!controller.hasUserAdjustments)

        controller.setUserBandAdjustment(-1, at: 5)
        #expect(controller.hasUserAdjustments)
    }

    // MARK: - Out-of-bounds band index

    @Test func settingBandAtInvalidIndexIsNoOp() {
        let defaults = UserDefaults(suiteName: "test.audio.\(UUID().uuidString)")!
        let controller = AudioEnhancementController(defaults: defaults)
        let before = controller.userBandAdjustments
        controller.setUserBandAdjustment(5, at: -1)
        controller.setUserBandAdjustment(5, at: AudioEnhancementProfile.bandCount)
        #expect(controller.userBandAdjustments == before)
    }

    // MARK: - Reset

    @Test func resetUserAdjustmentsClearsAllAndPersists() {
        let defaults = UserDefaults(suiteName: "test.audio.\(UUID().uuidString)")!
        let controller = AudioEnhancementController(defaults: defaults)
        controller.setUserPreampAdjustment(10)
        controller.setUserBandAdjustment(7, at: 3)

        controller.resetUserAdjustments()
        #expect(controller.userPreampAdjustment == 0)
        #expect(controller.userBandAdjustments.allSatisfy { $0 == 0 })

        let restored = AudioEnhancementController(defaults: defaults)
        #expect(restored.userPreampAdjustment == 0)
        #expect(restored.userBandAdjustments.allSatisfy { $0 == 0 })
    }

    // MARK: - Stored bands count mismatch

    @Test func storedBandsWithWrongCountAreIgnored() {
        let defaults = UserDefaults(suiteName: "test.audio.\(UUID().uuidString)")!
        defaults.set([Float](repeating: 5, count: 5), forKey: "audio.enhancementBands")

        let controller = AudioEnhancementController(defaults: defaults)
        #expect(controller.userBandAdjustments.count == AudioEnhancementProfile.bandCount)
        #expect(controller.userBandAdjustments.allSatisfy { $0 == 0 })
    }

    // MARK: - Profile identities

    @Test func allProfilesHaveUniqueRawValues() {
        let rawValues = AudioEnhancementProfile.allCases.map(\.rawValue)
        #expect(Set(rawValues).count == rawValues.count)
    }

    @Test func allProfilesHaveNonEmptyDisplayNames() {
        for profile in AudioEnhancementProfile.allCases {
            #expect(!profile.displayName.isEmpty, "\(profile.rawValue) has empty display name")
        }
    }
}

// MARK: - Live equalizer and booster integration

@MainActor
struct AudioBoosterTests {
    @Test func boosterPersistsAndRestoresWithoutChangingAdjustments() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let controller = AudioEnhancementController(defaults: defaults)
        #expect(!controller.boosterEnabled)
        controller.setUserPreampAdjustment(3)
        controller.setUserBandAdjustment(-2, at: 2)
        let unboosted = controller.effectivePreamp
        let bands = controller.effectiveBands
        controller.setBooster(true)
        #expect(controller.effectivePreamp == unboosted + AudioEnhancementController.boosterGain)
        controller.setBooster(true)
        #expect(controller.effectivePreamp == unboosted + AudioEnhancementController.boosterGain)
        #expect(controller.effectiveBands == bands)
        let restored = AudioEnhancementController(defaults: defaults)
        #expect(restored.boosterEnabled)
        #expect(restored.effectivePreamp == controller.effectivePreamp)
        restored.setBooster(false)
        #expect(restored.effectivePreamp == unboosted)
        #expect(restored.effectiveBands == bands)
    }

    @Test func togglingBoosterRestoresPreampAfterClamping() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let controller = AudioEnhancementController(defaults: defaults)
        controller.selectProfile(.flat)
        controller.setUserPreampAdjustment(17)
        controller.setBooster(true)
        #expect(controller.effectivePreamp == 20)
        controller.setBooster(false)
        #expect(controller.effectivePreamp == 17)
        #expect(controller.userPreampAdjustment == 17)
    }

    @Test func corruptNonFiniteAdjustmentsAreSanitized() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(Float.nan, forKey: "audio.enhancementPreamp")
        defaults.set([Float](repeating: .infinity, count: 10), forKey: "audio.enhancementBands")
        let controller = AudioEnhancementController(defaults: defaults)
        #expect(controller.userPreampAdjustment == 0)
        #expect(controller.userBandAdjustments.allSatisfy { $0 == 0 })
        controller.setUserPreampAdjustment(-.infinity)
        controller.setUserBandAdjustment(.nan, at: 0)
        #expect(controller.effectivePreamp.isFinite)
        #expect(controller.effectiveBands.allSatisfy { $0.isFinite })
    }

    @Test func sameProfileKeepsAdjustmentsAndPresetsProvideHeadroom() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let controller = AudioEnhancementController(defaults: defaults)
        controller.setUserPreampAdjustment(2)
        controller.selectProfile(.movies)
        #expect(controller.userPreampAdjustment == 2)
        for profile in AudioEnhancementProfile.allCases {
            #expect(profile.preamp + profile.peakBandBoost <= 0)
        }
    }

    @Test func liveEqualizerTracksControlsAndPlayerReplacement() throws {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let controller = AudioEnhancementController(defaults: defaults)
        let instance = try VLCInstance(arguments: ["--ignore-config", "--no-video", "--no-audio", "--no-stats"])
        let player = Player(instance: instance)
        controller.apply(to: player)
        #expect(player.equalizer?.preamp == controller.effectivePreamp)
        #expect(player.equalizer?.bands == controller.effectiveBands)
        let originalVolume = player.volume
        controller.setBooster(true)
        #expect(player.equalizer?.preamp == controller.effectivePreamp)
        #expect(player.volume == originalVolume)
        controller.setUserBandAdjustment(4, at: 5)
        #expect(player.equalizer?.bands[5] == controller.effectiveBands[5])
        controller.setBooster(false)
        #expect(player.equalizer?.preamp == controller.effectivePreamp)
        controller.selectProfile(.flat)
        #expect(player.equalizer == nil)
        controller.setBooster(true)
        #expect(player.equalizer?.preamp == AudioEnhancementController.boosterGain)
        let nextPlayer = Player(instance: instance)
        controller.apply(to: nextPlayer)
        #expect(player.equalizer == nil)
        #expect(nextPlayer.equalizer?.preamp == AudioEnhancementController.boosterGain)
        controller.detach()
        #expect(nextPlayer.equalizer == nil)
        controller.setBooster(false)
        #expect(nextPlayer.equalizer == nil)
    }
}
