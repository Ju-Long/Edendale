//
//  PlayerIdlePointerTests.swift
//  EdendaleTests
//
//  The macOS player's resting pointer: it hides after the delay over the
//  video, comes back on the next move, and stays through a held button.
//

#if os(macOS)
import CoreGraphics
import Testing
@testable import Edendale

@MainActor
struct PlayerIdlePointerTests {

    private static let delay: Duration = .milliseconds(50)
    private static let wait: Duration = .milliseconds(200)

    @Test func hidesAfterFiveSecondsByDefault() {
        #expect(PlayerIdlePointer.defaultDelay == .seconds(5))
    }

    @Test func hidesOnceThePointerRests() async throws {
        let pointer = PlayerIdlePointer(delay: Self.delay, isButtonHeld: { false })
        pointer.pointerMoved(to: CGPoint(x: 10, y: 10))
        #expect(!pointer.isHidden)
        try await Task.sleep(for: Self.wait)
        #expect(pointer.isHidden)
    }

    @Test func aMoveBringsItBackAndRestartsTheCountdown() async throws {
        let pointer = PlayerIdlePointer(delay: Self.delay, isButtonHeld: { false })
        pointer.pointerMoved(to: CGPoint(x: 10, y: 10))
        try await Task.sleep(for: Self.wait)
        pointer.pointerMoved(to: CGPoint(x: 11, y: 10))
        #expect(!pointer.isHidden)
        try await Task.sleep(for: Self.wait)
        #expect(pointer.isHidden)
    }

    @Test func aReportAtTheSamePointLeavesItHidden() async throws {
        let pointer = PlayerIdlePointer(delay: Self.delay, isButtonHeld: { false })
        pointer.pointerMoved(to: CGPoint(x: 10, y: 10))
        try await Task.sleep(for: Self.wait)
        pointer.pointerMoved(to: CGPoint(x: 10, y: 10))
        #expect(pointer.isHidden)
    }

    @Test func staysWhileAButtonIsHeld() async throws {
        var held = true
        let pointer = PlayerIdlePointer(delay: Self.delay, isButtonHeld: { held })
        pointer.pointerMoved(to: CGPoint(x: 10, y: 10))
        try await Task.sleep(for: Self.wait)
        #expect(!pointer.isHidden)
        held = false
        try await Task.sleep(for: Self.wait)
        #expect(pointer.isHidden)
    }

    @Test func leavingTheVideoShowsItAndStopsTheCountdown() async throws {
        let pointer = PlayerIdlePointer(delay: Self.delay, isButtonHeld: { false })
        pointer.pointerMoved(to: CGPoint(x: 10, y: 10))
        try await Task.sleep(for: Self.wait)
        pointer.pointerLeft()
        #expect(!pointer.isHidden)
        try await Task.sleep(for: Self.wait)
        #expect(!pointer.isHidden)
    }

    @Test func restartingShowsItWhileOverTheVideo() async throws {
        let pointer = PlayerIdlePointer(delay: Self.delay, isButtonHeld: { false })
        pointer.pointerMoved(to: CGPoint(x: 10, y: 10))
        try await Task.sleep(for: Self.wait)
        pointer.restartCountdown()
        #expect(!pointer.isHidden)
        try await Task.sleep(for: Self.wait)
        #expect(pointer.isHidden)
    }
}
#endif
