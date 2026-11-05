//
//  AudioEnhancementTests.swift
//  EdendaleTests
//
//  Unit tests for audio enhancement profiles (ENH-04) and audio
//  booster defaults (ENH-05).
//

import CoreMedia
import Foundation
import Testing
@testable import Edendale

@MainActor
struct AudioEnhancementProfileTests {

    // MARK: - Profile defaults

    @Test func defaultProfileIsMovies() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
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
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let controller = AudioEnhancementController(defaults: defaults)
        controller.selectProfile(.dialogue)
        #expect(defaults.string(forKey: "audio.enhancementProfile") == "dialogue")
    }

    @Test func persistedProfileIsRestoredOnInit() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("nightMode", forKey: "audio.enhancementProfile")

        let controller = AudioEnhancementController(defaults: defaults)
        #expect(controller.selectedProfile == .nightMode)
    }

    @Test func invalidPersistedProfileFallsBackToMovies() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set("nonexistent", forKey: "audio.enhancementProfile")

        let controller = AudioEnhancementController(defaults: defaults)
        #expect(controller.selectedProfile == .movies)
    }

    @Test func userAdjustmentsPersistAndRestore() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

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
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let controller = AudioEnhancementController(defaults: defaults)
        controller.setUserPreampAdjustment(5)
        controller.setUserBandAdjustment(3, at: 0)

        controller.selectProfile(.music)
        #expect(controller.userPreampAdjustment == 0)
        #expect(controller.userBandAdjustments.allSatisfy { $0 == 0 })
    }

    // MARK: - Effective computation

    @Test func effectiveValuesAddProfileAndUserAdjustment() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let controller = AudioEnhancementController(defaults: defaults)
        controller.selectProfile(.movies)

        controller.setUserPreampAdjustment(2)
        #expect(controller.effectivePreamp == AudioEnhancementProfile.movies.preamp + 2)

        controller.setUserBandAdjustment(5, at: 0)
        let expectedBand0 = AudioEnhancementProfile.movies.bands[0] + 5
        #expect(controller.effectiveBands[0] == AudioEnhancementProfile.clampAmplification(expectedBand0))
    }

    @Test func effectiveValuesClampAtBoundaries() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let controller = AudioEnhancementController(defaults: defaults)
        controller.selectProfile(.movies)

        controller.setUserPreampAdjustment(20)
        #expect(controller.effectivePreamp == AudioEnhancementProfile.movies.preamp + 20)

        controller.setUserBandAdjustment(20, at: 0)
        #expect(controller.effectiveBands[0] == 20)
    }

    @Test func isFlatDetectsNoEnhancement() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let controller = AudioEnhancementController(defaults: defaults)

        controller.selectProfile(.flat)
        #expect(controller.isEffectivelyFlat)

        controller.setUserBandAdjustment(1, at: 0)
        #expect(!controller.isEffectivelyFlat)

        controller.resetUserAdjustments()
        #expect(controller.isEffectivelyFlat)
    }

    @Test func hasUserAdjustmentsTracksNonZero() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
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
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let controller = AudioEnhancementController(defaults: defaults)
        let before = controller.userBandAdjustments
        controller.setUserBandAdjustment(5, at: -1)
        controller.setUserBandAdjustment(5, at: AudioEnhancementProfile.bandCount)
        #expect(controller.userBandAdjustments == before)
    }

    // MARK: - Reset

    @Test func resetUserAdjustmentsClearsAllAndPersists() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
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
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
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

    @Test func processorReceivesEffectiveValues() {
        let suite = "test.audio.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let controller = AudioEnhancementController(defaults: defaults)
        controller.selectProfile(.movies)
        #expect(!controller.processor.isFlat)

        controller.selectProfile(.flat)
        #expect(controller.processor.isFlat)

        controller.setBooster(true)
        #expect(!controller.processor.isFlat)

        controller.setBooster(false)
        #expect(controller.processor.isFlat)

        controller.setUserBandAdjustment(4, at: 5)
        #expect(!controller.processor.isFlat)

        controller.resetUserAdjustments()
        #expect(controller.processor.isFlat)
    }
}

/// The FFmpeg path equalizes interleaved surround PCM in place.
struct AudioEQProcessorTests {
    @Test func interleavedChannelsMatchEachChannelFilteredAlone() throws {
        let frames = 1536
        let left = (0..<frames).map { Float(sin(Double($0) * 0.031)) * 0.5 }
        let right = (0..<frames).map { Float(sin(Double($0) * 0.27)) * 0.25 }
        let bands: [Float] = [6, 4, 0, -3, 0, 2, 5, -6, 3, 1]

        let stereo = AudioEQProcessor()
        stereo.configure(sampleRate: 48_000, channelCount: 2)
        stereo.update(preamp: -4, bands: bands)
        let interleaved = zip(left, right).flatMap { [$0, $1] }
        // Two buffers in a row, so the filters' state carries across them.
        let first = try Self.process(interleaved, channels: 2, with: stereo)
        let second = try Self.process(interleaved, channels: 2, with: stereo)

        for (channel, signal) in [left, right].enumerated() {
            let mono = AudioEQProcessor()
            mono.configure(sampleRate: 48_000, channelCount: 1)
            mono.update(preamp: -4, bands: bands)
            let expected = try Self.process(signal, channels: 1, with: mono)
                + Self.process(signal, channels: 1, with: mono)
            let actual = (first + second).enumerated()
                .filter { $0.offset % 2 == channel }
                .map(\.element)
            #expect(actual.count == expected.count)
            #expect(zip(actual, expected).allSatisfy { abs($0 - $1) < 1e-6 })
            #expect(actual != signal + signal)
        }
    }

    @Test func flatProcessorLeavesSamplesUntouched() throws {
        let processor = AudioEQProcessor()
        processor.configure(sampleRate: 48_000, channelCount: 6)
        processor.update(preamp: 0, bands: Array(repeating: 0, count: AudioEQProcessor.bandCount))
        let samples = (0..<(6 * 512)).map { Float($0 % 97) / 97 }
        #expect(try Self.process(samples, channels: 6, with: processor) == samples)
    }

    private static func process(_ samples: [Float], channels: Int, with processor: AudioEQProcessor) throws -> [Float] {
        var description = AudioStreamBasicDescription(
            mSampleRate: 48_000,
            mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked,
            mBytesPerPacket: UInt32(4 * channels),
            mFramesPerPacket: 1,
            mBytesPerFrame: UInt32(4 * channels),
            mChannelsPerFrame: UInt32(channels),
            mBitsPerChannel: 32,
            mReserved: 0
        )
        var format: CMAudioFormatDescription?
        try #require(CMAudioFormatDescriptionCreate(
            allocator: kCFAllocatorDefault, asbd: &description, layoutSize: 0, layout: nil,
            magicCookieSize: 0, magicCookie: nil, extensions: nil, formatDescriptionOut: &format
        ) == noErr)
        let length = samples.count * MemoryLayout<Float>.size
        var block: CMBlockBuffer?
        try #require(CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: length,
            blockAllocator: kCFAllocatorDefault, customBlockSource: nil, offsetToData: 0,
            dataLength: length, flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block
        ) == noErr)
        let buffer = try #require(block)
        try samples.withUnsafeBytes { bytes in
            try #require(CMBlockBufferReplaceDataBytes(
                with: bytes.baseAddress!, blockBuffer: buffer, offsetIntoDestination: 0, dataLength: length
            ) == noErr)
        }
        var sample: CMSampleBuffer?
        try #require(CMAudioSampleBufferCreateReadyWithPacketDescriptions(
            allocator: kCFAllocatorDefault, dataBuffer: buffer, formatDescription: try #require(format),
            sampleCount: samples.count / channels, presentationTimeStamp: .zero,
            packetDescriptions: nil, sampleBufferOut: &sample
        ) == noErr)
        processor.processSampleBuffer(try #require(sample))

        var output = [Float](repeating: 0, count: samples.count)
        try output.withUnsafeMutableBytes { bytes in
            try #require(CMBlockBufferCopyDataBytes(
                buffer, atOffset: 0, dataLength: length, destination: bytes.baseAddress!
            ) == noErr)
        }
        return output
    }
}
