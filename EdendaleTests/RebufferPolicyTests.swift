//
//  RebufferPolicyTests.swift
//  EdendaleTests
//
//  When streamed playback stops to refill and when it carries on.
//

import Foundation
import Testing
@testable import Edendale

@Suite struct RebufferPolicyTests {

    /// A file of 8 MB per second.
    private let policy = RebufferPolicy(length: 800_000_000, duration: 100)

    private func source(_ bytes: Int64, complete: Bool = false) -> RebufferPolicy.Source {
        RebufferPolicy.Source(bytes: bytes, complete: complete)
    }

    @Test func theByteRateComesFromSizeAndDuration() {
        #expect(policy.byteRate == 8_000_000)
        #expect(RebufferPolicy(length: -1, duration: 100).byteRate == nil)
        #expect(RebufferPolicy(length: 1000, duration: .nan).byteRate == nil)
        #expect(RebufferPolicy(length: 1000, duration: 0).byteRate == nil)
        #expect(RebufferPolicy().byteRate == nil)
    }

    @Test func stopsOnlyWhenThePlayerAndTheSourceHaveBothRunOut() {
        // No frame waits, the sound is nearly gone, and the source is empty.
        #expect(policy.shouldPause(source: source(0), queuedVideoFrames: 0, audioAhead: 0.2))
        // The source still holds a second of the file: frames are coming.
        #expect(!policy.shouldPause(source: source(8_000_000), queuedVideoFrames: 0, audioAhead: 0.2))
        // Frames still wait to be shown.
        #expect(!policy.shouldPause(source: source(0), queuedVideoFrames: 3, audioAhead: 0.2))
        // The read-ahead is full or at the end of the file: nothing is late.
        #expect(!policy.shouldPause(source: source(0, complete: true), queuedVideoFrames: 0, audioAhead: 0))
    }

    @Test func aSparsePictureWithSoundWellAheadKeepsPlaying() {
        // Cover art, or a long still: the empty queue is not a stall.
        #expect(!policy.shouldPause(source: source(0), queuedVideoFrames: 0, audioAhead: 0.9))
    }

    @Test func soundAloneStopsJustBeforeItRunsOut() {
        #expect(!policy.shouldPause(source: source(0), queuedVideoFrames: nil, audioAhead: 0.3))
        #expect(policy.shouldPause(source: source(0), queuedVideoFrames: nil, audioAhead: 0.04))
        #expect(policy.shouldPause(source: source(0), queuedVideoFrames: nil, audioAhead: -1))
    }

    @Test func aPictureAloneStopsWhenNoFrameWaits() {
        #expect(policy.shouldPause(source: source(0), queuedVideoFrames: 0, audioAhead: nil))
        #expect(!policy.shouldPause(source: source(0), queuedVideoFrames: 1, audioAhead: nil))
        #expect(!policy.shouldPause(source: source(0), queuedVideoFrames: nil, audioAhead: nil))
    }

    @Test func resumesOnceTheSourceHoldsEnoughOrCanHoldNoMore() {
        // Eight seconds of the file by default.
        #expect(!policy.canResume(source(63_000_000)))
        #expect(policy.canResume(source(64_000_000)))
        // A read-ahead that is full, at the end of the file, or has given
        // up never grows: waiting longer would wait for ever.
        #expect(policy.canResume(source(0, complete: true)))
    }

    @Test func usesByteLimitsWithoutAByteRate() {
        let policy = RebufferPolicy()
        #expect(policy.shouldPause(source: source(100_000), queuedVideoFrames: 0, audioAhead: 0))
        #expect(!policy.shouldPause(source: source(300_000), queuedVideoFrames: 0, audioAhead: 0))
        #expect(!policy.canResume(source(4 << 20)))
        #expect(policy.canResume(source(8 << 20)))
    }
}
