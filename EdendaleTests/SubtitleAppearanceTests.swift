//
//  SubtitleAppearanceTests.swift
//  EdendaleTests
//
//  Unit tests for the Settings ▸ Subtitles appearance — its defaults,
//  persistence, and opacity bounds — and for the renderer honouring the
//  chosen typeface and colours.
//

import CoreText
import Foundation
import SwiftUI
import Testing
@testable import Edendale
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

@MainActor
struct SubtitleAppearanceTests {

    @Test func defaultsKeepTheArchiveLook() throws {
        let store = try SubtitleTestDefaults()
        defer { store.remove() }
        let appearance = SubtitleAppearance(defaults: store.defaults)

        #expect(appearance.font == .system)
        #expect(appearance.textColor == .parchment)
        #expect(appearance.backgroundColor == .ink)
        #expect(appearance.backgroundOpacity == 1)
        #expect(appearance.isDefault)
    }

    @Test func choicesPersistForTheNextLaunch() throws {
        let store = try SubtitleTestDefaults()
        defer { store.remove() }
        let appearance = SubtitleAppearance(defaults: store.defaults)
        appearance.font = .serif
        appearance.textColor = .yellow
        appearance.backgroundColor = .navy
        appearance.setBackgroundOpacity(0.4)

        let relaunched = SubtitleAppearance(defaults: store.defaults)
        #expect(relaunched.font == .serif)
        #expect(relaunched.textColor == .yellow)
        #expect(relaunched.backgroundColor == .navy)
        #expect(relaunched.backgroundOpacity == 0.4)
        #expect(!relaunched.isDefault)
    }

    @Test func opacityClampsToZeroThroughOne() throws {
        let store = try SubtitleTestDefaults()
        defer { store.remove() }
        let appearance = SubtitleAppearance(defaults: store.defaults)

        appearance.setBackgroundOpacity(-0.5)
        #expect(appearance.backgroundOpacity == 0)
        appearance.setBackgroundOpacity(1.7)
        #expect(appearance.backgroundOpacity == 1)
        appearance.setBackgroundOpacity(.nan)
        #expect(appearance.backgroundOpacity == SubtitleAppearance.defaultBackgroundOpacity)
        // Repeated tvOS steps never drift off whole percents.
        appearance.setBackgroundOpacity(0.1 + 0.2)
        #expect(appearance.backgroundOpacity == 0.3)
    }

    @Test func unrecognizedStoredValuesFallBackToDefaults() throws {
        let store = try SubtitleTestDefaults()
        defer { store.remove() }
        store.defaults.set("comic-sans", forKey: SubtitleAppearance.fontKey)
        store.defaults.set("plaid", forKey: SubtitleAppearance.textColorKey)
        store.defaults.set("tartan", forKey: SubtitleAppearance.backgroundColorKey)

        let appearance = SubtitleAppearance(defaults: store.defaults)
        #expect(appearance.isDefault)
    }

    @Test func resetRestoresEveryDefault() throws {
        let store = try SubtitleTestDefaults()
        defer { store.remove() }
        let appearance = SubtitleAppearance(defaults: store.defaults)
        appearance.font = .monospaced
        appearance.textColor = .black
        appearance.backgroundColor = .white
        appearance.setBackgroundOpacity(0)

        appearance.reset()
        #expect(appearance.isDefault)
        #expect(SubtitleAppearance(defaults: store.defaults).isDefault)
    }

    @Test(arguments: SubtitleFontStyle.allCases)
    func rendererUsesTheChosenTypeface(style: SubtitleFontStyle) throws {
        let renderer = TimedTextRenderer(device: nil)
        let text = renderer.buildAttributedString(
            from: "Plain <i>italic</i>",
            fontSize: 24,
            fontStyle: style
        )
        let plain = try #require(text.attribute(.font, at: 0, effectiveRange: nil) as? PlatformFont)
        let italic = try #require(
            text.attribute(.font, at: text.length - 1, effectiveRange: nil) as? PlatformFont
        )

        #expect(plain.pointSize == 24)
        // A family without an italic face (SF Rounded) slants its upright
        // glyphs through the font matrix instead.
        let ctItalic = italic as CTFont
        let slanted = CTFontGetSymbolicTraits(ctItalic).contains(.traitItalic)
            || CTFontGetMatrix(ctItalic).c != 0
        #expect(slanted)
        // The system families' PostScript names carry their design.
        let name = plain.fontName.lowercased()
        switch style {
        case .system: #expect(!name.contains("rounded") && !name.contains("serif") && !name.contains("mono"), "\(name)")
        case .rounded: #expect(name.contains("rounded"), "\(name)")
        case .serif: #expect(name.contains("serif") || name.contains("newyork"), "\(name)")
        case .monospaced: #expect(name.contains("mono"), "\(name)")
        }
    }

    @Test func rendererAppliesTheChosenColours() throws {
        let renderer = TimedTextRenderer(device: nil)
        let text = renderer.buildAttributedString(
            from: "Hello",
            textColor: PlatformColor(SubtitleTextColor.yellow.color),
            outlineColor: PlatformColor(SubtitleTextColor.yellow.outlineColor)
        )
        let fill = try #require(text.attribute(.foregroundColor, at: 0, effectiveRange: nil) as? PlatformColor)
        #expect(fill == PlatformColor(SubtitleTextColor.yellow.color))
        #expect(SubtitleTextColor.black.outlineColor != SubtitleTextColor.white.outlineColor)
    }
}

private struct SubtitleTestDefaults {
    let name = "SubtitleAppearanceTests-\(UUID().uuidString)"
    let defaults: UserDefaults

    init() throws {
        defaults = try #require(UserDefaults(suiteName: name))
    }

    func remove() {
        defaults.removePersistentDomain(forName: name)
    }
}
