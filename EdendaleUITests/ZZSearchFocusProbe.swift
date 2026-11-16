// TEMPORARY probe for the iPhone Duo search focus bug. Delete after use.

import XCTest

final class ZZSearchFocusProbe: XCTestCase {
    private var dir: String {
        ProcessInfo.processInfo.environment["SNAPSHOT_DIR"] ?? NSTemporaryDirectory()
    }

    @MainActor
    private func shot(_ name: String) {
        let data = XCUIScreen.main.screenshot().pngRepresentation
        try? data.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
    }

    @MainActor
    private func log(_ text: String) {
        let url = URL(fileURLWithPath: dir).appendingPathComponent("search-probe.log")
        let line = (text + "\n").data(using: .utf8)!
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile(); handle.write(line); try? handle.close()
        } else {
            try? line.write(to: url)
        }
    }

    @MainActor
    private func state(_ app: XCUIApplication, _ label: String) {
        let field = app.searchFields.firstMatch
        let focus = field.exists ? ((field.value(forKey: "hasKeyboardFocus") as? Bool) ?? false) : false
        log("[\(label)] window=\(app.windows.firstMatch.frame.size) keyboard=\(app.keyboards.firstMatch.exists) field.exists=\(field.exists) field.hasFocus=\(focus)")
    }

    @MainActor
    private func focusSearch(_ app: XCUIApplication, _ tag: String) {
        app.buttons["Search"].firstMatch.tap()
        sleep(2)
        let field = app.searchFields.firstMatch
        if field.exists, !app.keyboards.firstMatch.exists { field.tap(); sleep(1) }
        state(app, "\(tag) focused")
        dumpButtons(app, tag)
        shot("\(tag)-a-focused")
    }

    @MainActor
    private func tapClose(_ app: XCUIApplication, _ tag: String) {
        let field = app.searchFields.firstMatch
        let candidates = app.buttons.allElementsBoundByIndex.filter {
            $0.exists && $0.frame.minY < 120 && field.exists && $0.frame.minX > field.frame.maxX - 2
        }
        log("[\(tag)] buttons right of field: \(candidates.map { "'\($0.label)'/\($0.identifier)@\($0.frame)" })")
        if let close = candidates.first {
            close.tap()
            sleep(2)
            state(app, "\(tag) after X")
            dumpButtons(app, "\(tag) after X")
            shot("\(tag)-d-after-x")
        }
    }

    @MainActor
    private func refocus(_ app: XCUIApplication, _ tag: String) {
        let field = app.searchFields.firstMatch
        if field.exists && field.isHittable {
            field.tap()
        } else {
            app.buttons["Search"].firstMatch.tap()
            sleep(1)
            if app.searchFields.firstMatch.exists, !app.keyboards.firstMatch.exists {
                app.searchFields.firstMatch.tap()
            }
        }
        sleep(1)
        state(app, "\(tag) refocused")
    }

    @MainActor
    private func pass(_ app: XCUIApplication, _ tag: String) {
        log("--- \(tag) window=\(app.windows.firstMatch.frame.size)")
        let scroll = app.scrollViews.firstMatch
        log("scroll frame=\(scroll.frame)")

        refocus(app, tag)
        log("\(tag) swipe-start \(Date().timeIntervalSince1970)")
        scroll.swipeUp(velocity: .slow)
        sleep(2)
        state(app, "\(tag) after scroll")

        refocus(app, tag)
        log("\(tag) margin-tap \(Date().timeIntervalSince1970)")
        scroll.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.6)).tap()
        sleep(2)
        state(app, "\(tag) after margin tap")

        refocus(app, tag)
        log("\(tag) x-tap \(Date().timeIntervalSince1970)")
        tapClose(app, tag)
        sleep(1)
    }

    @MainActor
    private func value(_ app: XCUIApplication, _ tag: String) {
        log("\(tag) value='\(app.searchFields.firstMatch.value as? String ?? "?")' t=\(Date().timeIntervalSince1970)")
    }

    @MainActor
    private func fresh(_ tag: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.terminate()
        app.launch()
        sleep(3)
        log("--- \(tag) t=\(Date().timeIntervalSince1970)")
        app.buttons["Search"].firstMatch.tap()
        sleep(2)
        state(app, "\(tag) tab selected")
        app.searchFields.firstMatch.tap()
        sleep(2)
        state(app, "\(tag) field tapped")
        return app
    }

    @MainActor
    func testSearchFocus() throws {
        continueAfterFailure = true

        var app = fresh("T1 empty X")
        tapClose(app, "T1")
        sleep(2)
        state(app, "T1 settled"); value(app, "T1")

        app = fresh("T2 search key")
        app.typeText("dune\n")
        sleep(3)
        state(app, "T2 settled"); value(app, "T2")

        app = fresh("T3 content tap")
        app.typeText("dune")
        sleep(3)
        app.scrollViews.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.6)).tap()
        sleep(2)
        state(app, "T3 settled"); value(app, "T3")
        tapClose(app, "T3x")
        sleep(2)
        state(app, "T3x settled"); value(app, "T3x")

        app = fresh("T4 query X")
        app.typeText("dune")
        sleep(3)
        tapClose(app, "T4")
        sleep(2)
        state(app, "T4 settled"); value(app, "T4")
        dumpButtons(app, "T4 settled")
        dumpButtons(app, "T4 before reopen")
        app.searchFields.firstMatch.tap()
        sleep(2)
        state(app, "T4 reopened"); value(app, "T4 reopened")
        tapClose(app, "T4b")
        sleep(2)
        state(app, "T4b settled"); value(app, "T4b")
    }

    @MainActor
    private func dumpButtons(_ app: XCUIApplication, _ label: String) {
        for button in app.buttons.allElementsBoundByIndex where button.frame.minY < 140 && button.exists {
            log("  [\(label)] top button label='\(button.label)' id='\(button.identifier)' frame=\(button.frame)")
        }
    }

    /// Phone keyboard with the field in the top bar, like the Duo's inner screen.
    @MainActor
    func testPhoneLandscapeSearchFocus() throws {
        continueAfterFailure = true
        let app = XCUIApplication()
        app.launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(3)
        app.buttons["Search"].firstMatch.tap()
        sleep(2)
        state(app, "L tab")
        shot("p01-tab")
        let field = app.searchFields.firstMatch
        if field.exists, !app.keyboards.firstMatch.exists { field.tap(); sleep(1) }
        state(app, "L field")
        dumpButtons(app, "focused")
        shot("p02-focused")

        // Empty area: the large "Search" title row, right of the title.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.55, dy: 0.2)).tap()
        sleep(1)
        state(app, "L tap empty")
        shot("p03-tap-empty")

        app.swipeUp()
        sleep(1)
        state(app, "L swipe")
        shot("p04-swipe")

        // The X beside the field.
        for button in app.buttons.allElementsBoundByIndex where button.exists && button.frame.minY < 140 {
            if ["Close", "Cancel", "xmark", "Dismiss"].contains(button.label) || button.identifier.lowercased().contains("close") {
                log("tapping \(button.label) / \(button.identifier)")
                button.tap()
                sleep(2)
                state(app, "L after \(button.label)")
                shot("p05-after-x")
                dumpButtons(app, "after x")
                break
            }
        }
        XCUIDevice.shared.orientation = .portrait
        sleep(1)
    }

    @MainActor
    func testReopen() throws {
        continueAfterFailure = true
        let app = fresh("R")
        app.typeText("dune")
        sleep(3)
        tapClose(app, "R")
        sleep(2)
        state(app, "R minimized"); value(app, "R")
        for i in 1...2 {
            log("R\(i) reopen t=\(Date().timeIntervalSince1970)")
            app.searchFields.firstMatch.tap()
            sleep(1)
            state(app, "R\(i) +1s"); value(app, "R\(i) +1s")
            sleep(3)
            state(app, "R\(i) +4s"); value(app, "R\(i) +4s")
            if app.keyboards.firstMatch.exists { tapClose(app, "R\(i)x"); sleep(2) }
        }
    }

    /// iPhone / iPad: reach the field however the layout offers it.
    @MainActor
    func testGeneric() throws {
        continueAfterFailure = true
        let app = XCUIApplication()
        app.terminate()
        app.launch()
        sleep(3)
        app.buttons["Search"].firstMatch.tap()
        sleep(2)
        func openField() {
            if !app.searchFields.firstMatch.exists {
                let magnifier = app.navigationBars.buttons["Search"].firstMatch
                if magnifier.exists { magnifier.tap() } else { app.buttons["Search"].firstMatch.tap() }
                sleep(2)
            }
            if !app.keyboards.firstMatch.exists, app.searchFields.firstMatch.exists {
                app.searchFields.firstMatch.tap()
                sleep(1)
            }
        }
        openField()
        state(app, "G open")
        app.typeText("dune\n")
        sleep(3)
        state(app, "G after Search key"); value(app, "G after Search key")
        openField()
        state(app, "G reopened"); value(app, "G reopened")
        app.scrollViews.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.6)).tap()
        sleep(2)
        state(app, "G after margin tap"); value(app, "G after margin tap")
        openField()
        for button in app.buttons.allElementsBoundByIndex where button.exists {
            if ["Cancel", "close", "Close"].contains(button.label) {
                log("G tapping \(button.label) @\(button.frame)")
                button.tap()
                sleep(2)
                break
            }
        }
        state(app, "G after close"); value(app, "G after close")
        openField()
        sleep(3)
        state(app, "G reopened after close +3s"); value(app, "G reopened after close +3s")
    }
}
