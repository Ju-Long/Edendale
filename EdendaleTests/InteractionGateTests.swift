//
//  InteractionGateTests.swift
//  EdendaleTests
//
//  The gate background library work waits on: held while something goes
//  on (a scroll, the player), settled once nothing has for its delay, and
//  told by the player when the viewer asks to play and while it is up.
//

import Foundation
import SwiftData
import Testing
@testable import Edendale

@MainActor
struct InteractionGateTests {

    private static let settle: Duration = .milliseconds(80)

    private func waitOutSettleDelay() async throws {
        try await Task.sleep(for: Self.settle * 2)
    }

    @Test func settledUntilSomethingHappens() {
        let gate = InteractionGate(settleDelay: Self.settle)
        #expect(gate.isSettled())
    }

    @Test func holdsWhileActiveAndSettlesAfterTheDelay() async throws {
        let gate = InteractionGate(settleDelay: Self.settle)
        let scroll = InteractionGate.Activity.scrolling(UUID())
        gate.set(scroll, active: true)
        gate.set(.playback, active: true)
        try await waitOutSettleDelay()
        #expect(!gate.isSettled())

        // One activity ending isn't enough while another goes on.
        gate.set(scroll, active: false)
        try await waitOutSettleDelay()
        #expect(!gate.isSettled())

        gate.set(.playback, active: false)
        #expect(!gate.isSettled())
        try await waitOutSettleDelay()
        #expect(gate.isSettled())
    }

    @Test func workThatMayRunDuringPlaybackWaitsOnlyForScrolling() async throws {
        let gate = InteractionGate(settleDelay: Self.settle)
        let scroll = InteractionGate.Activity.scrolling(UUID())
        gate.set(.playback, active: true)
        gate.set(scroll, active: true)
        #expect(!gate.isSettled(includingPlayback: false))
        gate.set(scroll, active: false)
        try await waitOutSettleDelay()
        #expect(gate.isSettled(includingPlayback: false))
        #expect(!gate.isSettled())
    }

    @Test func endingAnActivityThatNeverStartedChangesNothing() {
        let gate = InteractionGate(settleDelay: .seconds(60))
        gate.set(.scrolling(UUID()), active: false)
        #expect(gate.isSettled())
    }

    @Test func aMomentOfActivityPostponesHeldWork() async throws {
        let gate = InteractionGate(settleDelay: Self.settle)
        gate.touch()
        #expect(!gate.isSettled())
        await gate.waitUntilSettled()
        #expect(gate.isSettled())
    }

    @Test func thePlayerHoldsTheGateFromAskingToPlayUntilItCloses() async throws {
        let schema = Schema([VideoFolder.self, Movie.self, TVShow.self, Episode.self])
        let container = try ModelContainer(
            for: schema,
            configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)]
        )
        let name = "InteractionGateTests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let gate = InteractionGate(settleDelay: Self.settle)
        let session = PlayerSession(
            library: LibraryController(modelContext: container.mainContext),
            watchStore: WatchProgressStore(),
            segmentSkipping: PlayerSegmentController(defaults: defaults, lookup: { _ in [] }),
            defaults: defaults,
            interactionGate: gate
        )

        // A file Edendale can't play still presents the player, with its error.
        await session.play(fileURL: URL(fileURLWithPath: "/tmp/InteractionGateTests.txt"))
        #expect(session.isPresented)
        try await waitOutSettleDelay()
        #expect(!gate.isSettled())

        session.end()
        #expect(!gate.isSettled())
        try await waitOutSettleDelay()
        #expect(gate.isSettled())
    }
}
