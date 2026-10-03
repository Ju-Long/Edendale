//
//  PlayerGuideTests.swift
//  EdendaleTests
//
//  Unit tests for the player guide: which pages each platform's input
//  gets, that the pages quote the viewer's App Controls choices, that the
//  key and toolbar legends match the player, and that the guide opens by
//  itself only until the viewer closes it once.
//

import Foundation
import SwiftData
import Testing
@testable import Edendale

@MainActor
struct PlayerGuideTests {

    // MARK: - Pages

    @Test func eachPlatformTeachesItsOwnInput() {
        #expect(PlayerGuide.topics(for: .touch, hasKeyboard: false)
            == [.showControls, .skip, .levels, .holdSpeed, .scrub, .tools])
        #expect(PlayerGuide.topics(for: .vision, hasKeyboard: false)
            == [.showControls, .skip, .levels, .holdSpeed, .scrub, .tools])
        #expect(PlayerGuide.topics(for: .mac, hasKeyboard: false)
            == [.showControls, .holdSpeed, .keyboard, .tools])
        #expect(PlayerGuide.topics(for: .tv, hasKeyboard: false)
            == [.showControls, .skip, .holdSpeed, .remoteButtons, .tools])
    }

    @Test func touchPlatformsAddKeysOnlyWithAKeyboard() {
        #expect(PlayerGuide.topics(for: .touch, hasKeyboard: true).contains(.keyboard))
        #expect(PlayerGuide.topics(for: .vision, hasKeyboard: true).contains(.keyboard))
        #expect(!PlayerGuide.topics(for: .tv, hasKeyboard: true).contains(.keyboard))
        #expect(PlayerGuide.topics(for: .mac, hasKeyboard: true) == PlayerGuide.topics(for: .mac, hasKeyboard: false))
    }

    @Test func pagesQuoteTheChosenSkipLengthsAndSpeeds() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        let controls = PlayerControlPreferences(defaults: store.defaults)
        controls.skipBackwardInterval = .fifteen
        controls.skipForwardInterval = .thirty
        controls.setHoldRate(0.75, for: .left)
        controls.setHoldRate(2.5, for: .right)

        for platform in [PlayerGuidePlatform.touch, .vision, .tv] {
            let skip = PlayerGuide.page(.skip, platform: platform, controls: controls)
            #expect(skip.message.contains("30"))
            #expect(skip.message.contains("15"))
            #expect(skip.footnote != nil)
        }
        for platform in [PlayerGuidePlatform.touch, .vision, .mac, .tv] {
            let hold = PlayerGuide.page(.holdSpeed, platform: platform, controls: controls)
            #expect(hold.message.contains(PlayerLogic.rateLabel(2.5)))
            #expect(hold.message.contains(PlayerLogic.rateLabel(0.75)))
        }
    }

    @Test func everyPageHasWords() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        let controls = PlayerControlPreferences(defaults: store.defaults)
        for platform in [PlayerGuidePlatform.touch, .vision, .mac, .tv] {
            let pages = PlayerGuide.pages(for: platform, controls: controls, hasKeyboard: true)
            #expect(!pages.isEmpty)
            #expect(Set(pages.map(\.id)).count == pages.count)
            for page in pages {
                #expect(!page.title.isEmpty)
                #expect(!page.message.isEmpty)
            }
        }
    }

    // MARK: - Legends

    @Test func keyboardLegendMatchesThePlayersKeys() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        let controls = PlayerControlPreferences(defaults: store.defaults)
        controls.skipBackwardInterval = .thirty

        let mac = PlayerGuide.shortcuts(for: .mac, controls: controls)
        let pad = PlayerGuide.shortcuts(for: .touch, controls: controls)
        // Only the Mac player window toggles full screen with F.
        #expect(mac.contains { $0.keys == ["F"] })
        #expect(!pad.contains { $0.keys == ["F"] })
        for keys in [["←"], ["→"], ["↑", "↓"], ["⌘", "↑", "↓"], ["M"], ["S"], ["esc"]] {
            #expect(mac.contains { $0.keys == keys })
            #expect(pad.contains { $0.keys == keys })
        }
        let back = try #require(mac.first { $0.keys == ["←"] })
        #expect(back.action.contains("30"))
        #expect(Set(mac.map(\.id)).count == mac.count)
    }

    @Test func toolLegendMatchesTheToolbar() {
        func names(_ platform: PlayerGuidePlatform) -> [String] {
            PlayerGuide.tools(for: platform).map(\.name)
        }
        let pip = String(localized: "Picture in Picture")
        let rotation = String(localized: "Rotation Lock")

        #expect(names(.touch).contains(pip))
        #expect(names(.touch).contains(rotation))
        #expect(names(.mac).contains(pip))
        #expect(!names(.mac).contains(rotation))
        #expect(!names(.tv).contains(pip))
        #expect(!names(.vision).contains(pip))
        for platform in [PlayerGuidePlatform.touch, .vision, .mac, .tv] {
            #expect(names(platform).suffix(2) == [String(localized: "Playlist"), String(localized: "Adjustments")])
        }
    }

    // MARK: - First run

    @Test func guideOpensByItselfUntilClosedOnce() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        let fixture = try SessionFixture(defaults: store.defaults)
        let (session, watchStore) = (fixture.session, fixture.watchStore)

        let first = PlayerChromeModel(session: session, watchStore: watchStore, defaults: store.defaults)
        first.presentGuideIfFirstRun()
        #expect(first.guideVisible)
        #expect(first.guideIsFirstRun)
        first.dismissGuide()
        #expect(!first.guideVisible)
        #expect(!PlayerGuide.isUnseen(in: store.defaults))

        let next = PlayerChromeModel(session: session, watchStore: watchStore, defaults: store.defaults)
        next.presentGuideIfFirstRun()
        #expect(!next.guideVisible)

        // It still opens on request.
        next.presentGuide()
        #expect(next.guideVisible)
        #expect(!next.guideIsFirstRun)
    }

    @Test func aNewerGuideOpensAgain() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        store.defaults.set(PlayerGuide.version - 1, forKey: PlayerGuide.seenVersionKey)
        #expect(PlayerGuide.isUnseen(in: store.defaults))
        PlayerGuide.markSeen(in: store.defaults)
        #expect(!PlayerGuide.isUnseen(in: store.defaults))
    }

    @Test func openingTheGuideClosesPanelsAndHeldSpeed() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        let fixture = try SessionFixture(defaults: store.defaults)
        let chrome = PlayerChromeModel(
            session: fixture.session, watchStore: fixture.watchStore, defaults: store.defaults
        )
        chrome.openPanel(.settings)
        chrome.beginHold(on: .right)

        chrome.presentGuide()
        #expect(chrome.activePanel == nil)
        #expect(chrome.holdRate == nil)
        #expect(chrome.hud == nil)
        chrome.dismissGuide()
        #expect(chrome.controlsVisible)
    }

    // MARK: - Illustrations

    @Test func illustrationsLoopWithinTheirPicture() throws {
        let store = try TestDefaults()
        defer { store.remove() }
        let controls = PlayerControlPreferences(defaults: store.defaults)
        for platform in [PlayerGuidePlatform.touch, .vision, .mac, .tv] {
            for topic in PlayerGuide.topics(for: platform, hasKeyboard: false)
            where topic != .tools && topic != .keyboard {
                let script = GuideScript(topic: topic, platform: platform, controls: controls)
                #expect(script.duration > 0)
                #expect(abs(script.phase(elapsed: script.duration * 2.5) - 0.5) < 1e-9)
                for step in 0..<100 {
                    let frame = script.frame(at: Double(step) / 100)
                    #expect((0...1).contains(frame.controls))
                    #expect((0...1).contains(frame.hudOpacity))
                    #expect((0...1).contains(frame.progress))
                    if let touch = frame.touch {
                        #expect((0...1).contains(touch.point.x) && (0...1).contains(touch.point.y))
                    }
                }
                // The Reduce Motion still shows the gesture's result.
                let still = script.frame(at: script.stillPhase)
                #expect(still.touch != nil || still.remote.thumb != nil || still.remote.pressed != nil
                    || still.controls > 0)
            }
        }
    }
}

/// A player session over an in-memory library, kept alive for one test.
@MainActor
private struct SessionFixture {
    let container: ModelContainer
    let watchStore = WatchProgressStore()
    let session: PlayerSession

    init(defaults: UserDefaults) throws {
        let schema = Schema([VideoFolder.self, Movie.self, TVShow.self, Episode.self])
        container = try ModelContainer(
            for: schema,
            configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)]
        )
        session = PlayerSession(
            library: LibraryController(modelContext: container.mainContext),
            watchStore: watchStore,
            segmentSkipping: PlayerSegmentController(defaults: defaults, lookup: { _ in [] }),
            defaults: defaults
        )
    }
}

/// A private preferences suite per test, so nothing leaks between runs.
private struct TestDefaults {
    let name = "PlayerGuideTests-\(UUID().uuidString)"
    let defaults: UserDefaults

    init() throws {
        defaults = try #require(UserDefaults(suiteName: name))
    }

    func remove() {
        defaults.removePersistentDomain(forName: name)
    }
}
