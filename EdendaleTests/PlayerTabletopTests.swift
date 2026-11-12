//
//  PlayerTabletopTests.swift
//  EdendaleTests
//
//  The iPhone Duo's tabletop player: when a half-open hinge splits the
//  window into picture and controls, where the fold falls, and how the
//  controls behave below it.
//

import CoreGraphics
import Foundation
import SwiftData
import Testing
@testable import Edendale

@MainActor
struct PlayerTabletopTests {

    /// The Duo's inner screen in portrait, in points.
    nonisolated private static let inner = CGSize(width: 669, height: 951)

    private func fold(
        _ hinge: PlayerHingeStatus,
        window: CGSize = inner,
        screen: CGSize? = nil
    ) -> PlayerFoldState {
        PlayerFoldState(hinge: hinge, windowSize: window, screenSize: screen ?? window)
    }

    // MARK: - When to split

    @Test func halfOpenInPortraitPutsTheFoldAcrossTheMiddle() throws {
        let layout = try #require(PlayerTabletopLayout.resolve(fold(.partiallyOpen)))
        #expect(layout.foldY == 476)
        #expect(layout.height == 951)
        #expect(layout.deckHeight == 475)
    }

    @Test(arguments: [PlayerHingeStatus.unknown, .closed, .fullyOpen])
    func onlyAHalfOpenHingeSplits(_ hinge: PlayerHingeStatus) {
        #expect(PlayerTabletopLayout.resolve(fold(hinge)) == nil)
    }

    @Test func landscapeKeepsTheFullScreenLayout() {
        // Held like a book, the fold runs down the middle of the picture.
        let landscape = CGSize(width: 951, height: 669)
        #expect(PlayerTabletopLayout.resolve(fold(.partiallyOpen, window: landscape)) == nil)
    }

    @Test func aWindowSharingTheScreenKeepsTheFullScreenLayout() {
        let half = CGSize(width: 334, height: 951)
        #expect(PlayerTabletopLayout.resolve(fold(.partiallyOpen, window: half, screen: Self.inner)) == nil)
    }

    @Test func halvesTooShortForTheControlsKeepTheFullScreenLayout() {
        let short = CGSize(width: 320, height: 600)
        #expect(PlayerTabletopLayout.resolve(fold(.partiallyOpen, window: short)) == nil)
    }

    @Test func aWindowWithinAPointOfTheScreenStillSplits() {
        let screen = CGSize(width: 669.5, height: 951.5)
        #expect(PlayerTabletopLayout.resolve(fold(.partiallyOpen, screen: screen)) != nil)
    }

    // MARK: - Controls below the fold

    @Test func aTapOnThePictureKeepsTheTabletopControls() throws {
        let harness = try ChromeHarness()
        defer { harness.remove() }
        let chrome = harness.chrome

        chrome.toggleControls()
        #expect(!chrome.controlsVisible)

        chrome.isTabletop = true
        #expect(chrome.controlsVisible)
        chrome.toggleControls()
        #expect(chrome.controlsVisible)
    }

    @Test func aTapOnThePictureClosesAPanelBelowTheFold() throws {
        let harness = try ChromeHarness()
        defer { harness.remove() }
        let chrome = harness.chrome

        chrome.isTabletop = true
        chrome.openPanel(.settings)
        #expect(chrome.activePanel == .settings)
        // The panel takes the controls' place below the fold, so the Up
        // Next card and skip prompts stay on the picture.
        #expect(!chrome.panelCoversVideo)

        chrome.toggleControls()
        #expect(chrome.activePanel == nil)
        #expect(chrome.controlsVisible)
    }

    @Test func leavingTabletopRestoresTapToHide() throws {
        let harness = try ChromeHarness()
        defer { harness.remove() }
        let chrome = harness.chrome

        chrome.isTabletop = true
        chrome.isTabletop = false
        #expect(chrome.controlsVisible)
        chrome.toggleControls()
        #expect(!chrome.controlsVisible)
    }
}

/// A chrome model over an in-memory library and a private preferences
/// suite, so nothing leaks between runs.
@MainActor
private struct ChromeHarness {
    let suiteName = "PlayerTabletopTests-\(UUID().uuidString)"
    let container: ModelContainer
    let chrome: PlayerChromeModel
    private let session: PlayerSession

    init() throws {
        let defaults = try #require(UserDefaults(suiteName: suiteName))
        let schema = Schema([VideoFolder.self, Movie.self, TVShow.self, Episode.self])
        container = try ModelContainer(
            for: schema,
            configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)]
        )
        let watchStore = WatchProgressStore()
        session = PlayerSession(
            library: LibraryController(modelContext: container.mainContext),
            watchStore: watchStore,
            segmentSkipping: PlayerSegmentController(defaults: defaults, lookup: { _ in [] }),
            defaults: defaults
        )
        chrome = PlayerChromeModel(session: session, watchStore: watchStore, defaults: defaults)
    }

    func remove() {
        UserDefaults(suiteName: suiteName)?.removePersistentDomain(forName: suiteName)
    }
}
