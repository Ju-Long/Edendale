//
//  PlayerLogicTests.swift
//  EdendaleTests
//
//  Unit tests for the player's pure playback rules: speed stepping, time
//  formatting, auto-skip windows, natural-end detection, hold-drag scrub
//  math, and sibling-file discovery.
//

import Testing
import Foundation
import CoreGraphics
@testable import Edendale

struct PlayerLogicTests {

    // MARK: - Normalized levels

    @Test func normalizedLevelsStepAndClamp() {
        #expect(PlayerLogic.adjustedLevel(0.5, by: 0.05) == 0.55)
        #expect(PlayerLogic.adjustedLevel(0.5, by: -0.05) == 0.45)
        #expect(PlayerLogic.adjustedLevel(0.98, by: 0.05) == 1)
        #expect(PlayerLogic.adjustedLevel(0.02, by: -0.05) == 0)
    }

    // MARK: - Speed

    @Test func rateStepsInFiveHundredthsIncrements() {
        #expect(PlayerLogic.incrementedRate(1.0) == 1.05)
        #expect(PlayerLogic.decrementedRate(1.0) == 0.95)
        // Repeated stepping stays on the 0.05 grid (no float drift).
        var rate: Float = 1.0
        for _ in 0..<7 { rate = PlayerLogic.incrementedRate(rate) }
        #expect(abs(rate - 1.35) < 0.0001)
    }

    @Test func rateClampsToSupportedRange() {
        #expect(PlayerLogic.decrementedRate(PlayerLogic.minRate) == PlayerLogic.minRate)
        #expect(PlayerLogic.incrementedRate(PlayerLogic.maxRate) == PlayerLogic.maxRate)
        #expect(PlayerLogic.normalizedRate(17) == PlayerLogic.maxRate)
        #expect(PlayerLogic.normalizedRate(-2) == PlayerLogic.minRate)
    }

    @Test func rateSnapsOntoGrid() {
        #expect(PlayerLogic.normalizedRate(1.02) == 1.0)
        #expect(PlayerLogic.normalizedRate(1.13) == 1.15)
        #expect(PlayerLogic.rateLabel(1.5) == "1.50×")
    }

    // MARK: - tvOS press-and-hold speed

    @Test func holdRateArmsFromTheRestingSide() {
        // A fresh press must clear the higher arm threshold before it counts.
        #expect(PlayerLogic.holdRate(x: 0.4, y: 0, active: false) == nil)
        #expect(PlayerLogic.holdRate(x: 0.6, y: 0, active: false) == PlayerLogic.holdFastRate)
        #expect(PlayerLogic.holdRate(x: -0.6, y: 0, active: false) == PlayerLogic.holdSlowRate)
    }

    @Test func holdRateHoldsThroughDriftOnceActive() {
        // Hysteresis: an engaged hold survives down to the release threshold…
        #expect(PlayerLogic.holdRate(x: 0.4, y: 0, active: true) == PlayerLogic.holdFastRate)
        // …then lets go once the thumb slides back toward center.
        #expect(PlayerLogic.holdRate(x: 0.2, y: 0, active: true) == nil)
    }

    @Test func holdRateIgnoresVerticalDominantTouches() {
        // An up/down move (vertical-dominant) is never a speed hold.
        #expect(PlayerLogic.holdRate(x: 0.6, y: 0.7, active: false) == nil)
        #expect(PlayerLogic.holdRate(x: 0.6, y: 0.7, active: true) == nil)
    }

    // MARK: - Timestamps

    @Test func timestampsFormatAcrossHourBoundary() {
        #expect(PlayerLogic.timestamp(0) == "0:00")
        #expect(PlayerLogic.timestamp(59.9) == "0:59")
        #expect(PlayerLogic.timestamp(65) == "1:05")
        #expect(PlayerLogic.timestamp(3599) == "59:59")
        #expect(PlayerLogic.timestamp(3600) == "1:00:00")
        #expect(PlayerLogic.timestamp(5025) == "1:23:45")
        #expect(PlayerLogic.timestamp(-4) == "0:00")
        #expect(PlayerLogic.timestamp(Double.nan) == "0:00")
    }

    // MARK: - Auto-skip windows

    @Test func recapSkipOnlyAppliesToLongEnoughMedia() {
        #expect(PlayerLogic.recapSkipTarget(duration: .seconds(2700)) == .seconds(90))
        #expect(PlayerLogic.recapSkipTarget(duration: .seconds(300)) == nil)
        #expect(PlayerLogic.recapSkipTarget(duration: nil) == nil)
    }

    @Test func creditsWindowStartsBeforeTheEnd() {
        #expect(PlayerLogic.creditsStart(duration: .seconds(3600)) == .seconds(3420))
        #expect(PlayerLogic.creditsStart(duration: .seconds(300)) == nil)
        #expect(PlayerLogic.creditsStart(duration: nil) == nil)
    }

    // MARK: - Natural end

    @Test func naturalEndFiresNearNinetyFivePercent() {
        let duration = Duration.seconds(1200)
        // A mid-file stop is a user stop, not a completion.
        #expect(!PlayerLogic.isNaturalEnd(time: .seconds(600), duration: duration))   // 50%
        // Crossing ~95% counts as watched through.
        #expect(PlayerLogic.isNaturalEnd(time: .seconds(1152), duration: duration))   // 96%
        #expect(!PlayerLogic.isNaturalEnd(time: .zero, duration: duration))
        #expect(!PlayerLogic.isNaturalEnd(time: .seconds(1152), duration: nil))
    }

    @Test func naturalEndTreatsCreditsSkipAsFinished() {
        // Skip-credits ends playback `creditsLength` before the tail — far
        // more than 2 s. For a feature-length runtime that cutoff still sits
        // past 95%, so the stop completes instead of lingering in Continue
        // Watching (the bug when only the 2-second arm existed).
        let feature = Duration.seconds(7200)
        let creditsStart = PlayerLogic.creditsStart(duration: feature)!
        #expect(PlayerLogic.isNaturalEnd(time: creditsStart, duration: feature))
    }

    @Test func naturalEndKeepsTwoSecondArmForShortClips() {
        // Below `minimumSkippableDuration` the credits window never applies,
        // so completion rides the 2-second arm. Here the arms diverge: at 30 s
        // the 2 s cutoff is 28 s while 95% is 28.5 s. A stop within 2 s of the
        // end still counts even though it falls short of 95%.
        let clip = Duration.seconds(30)
        #expect(PlayerLogic.isNaturalEnd(time: .seconds(28.2), duration: clip))  // within 2 s, below 95%
        #expect(!PlayerLogic.isNaturalEnd(time: .seconds(27), duration: clip))   // short of both arms
    }

    // MARK: - Aspect fill

    @Test func aspectFillScaleCoversMismatchedShapes() {
        // 2.39:1 film in a 16:9 window scales up to erase the letterbox.
        let wideInScreen = PlayerLogic.aspectFillScale(
            container: CGSize(width: 1920, height: 1080),
            video: CGSize(width: 1920, height: 803)
        )
        #expect(abs(wideInScreen - (1920.0 / 803.0) / (1920.0 / 1080.0)) < 0.0001)
        #expect(wideInScreen > 1)

        // 4:3 content in a 16:9 window scales up to erase the pillarbox.
        let tallInScreen = PlayerLogic.aspectFillScale(
            container: CGSize(width: 1920, height: 1080),
            video: CGSize(width: 1440, height: 1080)
        )
        #expect(abs(tallInScreen - (16.0 / 9.0) / (4.0 / 3.0)) < 0.0001)
        #expect(tallInScreen > 1)
    }

    @Test func aspectFillScaleIsOneWhenShapesMatchOrAreUnknown() {
        // Same aspect ratio needs no crop.
        #expect(PlayerLogic.aspectFillScale(
            container: CGSize(width: 1280, height: 720),
            video: CGSize(width: 1920, height: 1080)
        ) == 1)
        // Degenerate sizes fall back to fit (scale 1) rather than diverging.
        #expect(PlayerLogic.aspectFillScale(
            container: CGSize(width: 1920, height: 1080),
            video: .zero
        ) == 1)
        #expect(PlayerLogic.aspectFillScale(
            container: .zero,
            video: CGSize(width: 1920, height: 1080)
        ) == 1)
    }

    // MARK: - Hold-drag scrubbing

    @Test func scrubTargetSweepsTheConfiguredWindow() {
        // Full-width drag on a 600 s file moves 300 s → +0.5 of position.
        let forward = PlayerLogic.scrubTarget(
            basePosition: 0.2, translation: 400, width: 400, durationSeconds: 600
        )
        #expect(abs(forward - 0.7) < 0.0001)

        let backward = PlayerLogic.scrubTarget(
            basePosition: 0.2, translation: -400, width: 400, durationSeconds: 600
        )
        #expect(backward == 0)  // clamped at the start

        // Degenerate inputs leave the position untouched.
        #expect(PlayerLogic.scrubTarget(
            basePosition: 0.4, translation: 100, width: 0, durationSeconds: 600
        ) == 0.4)
    }

    @Test func relativeSeekPositionClampsToTimeline() {
        #expect(PlayerLogic.seekPosition(
            fromSeconds: 30, bySeconds: 10, durationSeconds: 100
        ) == 0.4)
        #expect(PlayerLogic.seekPosition(
            fromSeconds: 5, bySeconds: -10, durationSeconds: 100
        ) == 0)
        #expect(PlayerLogic.seekPosition(
            fromSeconds: 95, bySeconds: 10, durationSeconds: 100
        ) == 1)
        #expect(PlayerLogic.seekPosition(
            fromSeconds: 30, bySeconds: 10, durationSeconds: 0
        ) == 0)
    }

    // MARK: - Folder siblings

    @Test func siblingListingFiltersAndSortsVideoFiles() throws {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("PlayerLogicTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        for name in ["b.mkv", "a.mp4", "notes.txt", "c.srt", "Episode 10.mkv", "Episode 2.mkv"] {
            FileManager.default.createFile(
                atPath: dir.appendingPathComponent(name).path, contents: Data()
            )
        }

        let siblings = PlayerLogic.siblingVideoFiles(of: dir.appendingPathComponent("b.mkv"))
        let names = siblings.map(\.lastPathComponent)

        // Videos only, numerically aware sort, non-video files excluded.
        #expect(names == ["a.mp4", "b.mkv", "Episode 2.mkv", "Episode 10.mkv"])
    }

    @Test func siblingListingFallsBackToTheFileItself() {
        let missing = URL(fileURLWithPath: "/definitely/not/here/movie.mkv")
        #expect(PlayerLogic.siblingVideoFiles(of: missing) == [missing])
    }
}
