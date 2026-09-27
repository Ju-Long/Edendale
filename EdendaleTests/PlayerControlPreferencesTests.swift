//
//  PlayerControlPreferencesTests.swift
//  EdendaleTests
//
//  Unit tests for the App Controls preferences — skip lengths and
//  press-and-hold speeds, their persistence and bounds — and for the
//  player actions that read them.
//

import Foundation
import SwiftData
import Testing
@testable import Edendale

@MainActor
struct PlayerControlPreferencesTests {

    // MARK: - Preferences

    @Test func defaultsSkipTenSecondsAndHoldAtHalfAndDoubleSpeed() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        let controls = PlayerControlPreferences(defaults: store.defaults)

        #expect(controls.skipBackwardInterval == .ten)
        #expect(controls.skipForwardInterval == .ten)
        #expect(controls.holdRate(for: .left) == 0.5)
        #expect(controls.holdRate(for: .right) == 2.0)
    }

    @Test func choicesPersistForTheNextLaunch() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        let controls = PlayerControlPreferences(defaults: store.defaults)
        controls.skipBackwardInterval = .thirty
        controls.skipForwardInterval = .fifteen
        controls.setHoldRate(0.75, for: .left)
        controls.setHoldRate(2.5, for: .right)

        let relaunched = PlayerControlPreferences(defaults: store.defaults)
        #expect(relaunched.skipBackwardInterval == .thirty)
        #expect(relaunched.skipForwardInterval == .fifteen)
        #expect(relaunched.holdRate(for: .left) == 0.75)
        #expect(relaunched.holdRate(for: .right) == 2.5)
    }

    @Test func skipOffsetsAreSignedByDirection() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        let controls = PlayerControlPreferences(defaults: store.defaults)
        controls.skipBackwardInterval = .fifteen
        controls.skipForwardInterval = .thirty

        #expect(controls.skipOffset(for: .backward) == -15)
        #expect(controls.skipOffset(for: .forward) == 30)
    }

    @Test func holdRatesSnapToQuarterStepsWithinTheSpeedRange() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        let controls = PlayerControlPreferences(defaults: store.defaults)

        controls.setHoldRate(0.6, for: .left)
        #expect(controls.holdRate(for: .left) == 0.5)
        controls.setHoldRate(1.4, for: .right)
        #expect(controls.holdRate(for: .right) == 1.5)
        controls.setHoldRate(9, for: .right)
        #expect(controls.holdRate(for: .right) == PlayerLogic.maxRate)
        controls.setHoldRate(0.05, for: .left)
        #expect(controls.holdRate(for: .left) == PlayerLogic.minRate)
        controls.setHoldRate(.nan, for: .left)
        #expect(controls.holdRate(for: .left) == PlayerLogic.minRate)
    }

    @Test func unrecognizedStoredValuesFallBack() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        store.defaults.set(12, forKey: PlayerControlPreferences.skipForwardKey)
        store.defaults.set(Float(7.3), forKey: PlayerControlPreferences.holdRightRateKey)

        let controls = PlayerControlPreferences(defaults: store.defaults)
        #expect(controls.skipForwardInterval == PlayerControlPreferences.defaultSkipInterval)
        #expect(controls.holdRate(for: .right) == PlayerLogic.maxRate)
    }

    @Test func skipLengthChangesReachTheSystemTransport() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        let controls = PlayerControlPreferences(defaults: store.defaults)
        var changes = 0
        controls.onSkipIntervalsChange = { changes += 1 }

        controls.skipBackwardInterval = .fifteen
        controls.skipForwardInterval = .thirty
        controls.setHoldRate(1.0, for: .left)

        #expect(changes == 2)
    }

    // MARK: - Player actions

    @Test func skipsAndHoldsUseTheChosenValues() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        let schema = Schema([VideoFolder.self, Movie.self, TVShow.self, Episode.self])
        let container = try ModelContainer(
            for: schema,
            configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)]
        )
        let watchStore = WatchProgressStore()
        let session = PlayerSession(
            library: LibraryController(modelContext: container.mainContext),
            watchStore: watchStore,
            segmentSkipping: PlayerSegmentController(defaults: store.defaults, lookup: { _ in [] }),
            defaults: store.defaults
        )
        let chrome = PlayerChromeModel(session: session, watchStore: watchStore, defaults: store.defaults)

        session.controls.skipBackwardInterval = .fifteen
        session.controls.skipForwardInterval = .thirty
        chrome.skip(.backward)
        #expect(chrome.hud == .seek(by: -15))
        chrome.skip(.forward)
        #expect(chrome.hud == .seek(by: 30))

        session.controls.setHoldRate(0.75, for: .left)
        chrome.beginHold(on: .left)
        #expect(chrome.holdRate == 0.75)
        #expect(chrome.hud == .speed(0.75))
        chrome.endHoldRate()
        #expect(chrome.holdRate == nil)

        chrome.beginHold(on: .right)
        #expect(chrome.holdRate == PlayerControlPreferences.defaultHoldRightRate)
        chrome.endHoldRate()
    }
}

/// A private preferences suite per test, so nothing leaks between runs.
private struct TestDefaults {
    let name = "PlayerControlPreferencesTests-\(UUID().uuidString)"
    let defaults: UserDefaults

    init() throws {
        defaults = try #require(UserDefaults(suiteName: name))
    }

    func remove() {
        defaults.removePersistentDomain(forName: name)
    }
}
