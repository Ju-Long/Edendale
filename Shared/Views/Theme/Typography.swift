//
//  Typography.swift
//  Edendale
//
//  DESIGN.md text styles: Bebas Neue for theatrical display type,
//  Inter for functional UI. Fonts ship in Shared/Fonts and are
//  registered at launch by FontRegistrar; every accessor falls back
//  to a condensed system face when the files are missing.
//

import SwiftUI
import CoreText

#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

// MARK: - Runtime font registration

enum FontRegistrar {
    private static let fontExtensions = ["ttf", "otf"]

    /// Registers every bundled font with the process. Safe to call more than once.
    static func registerAll() {
        for ext in fontExtensions {
            let urls = (Bundle.main.urls(forResourcesWithExtension: ext, subdirectory: nil) ?? [])
                + (Bundle.main.urls(forResourcesWithExtension: ext, subdirectory: "Fonts") ?? [])
            for url in urls {
                CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
            }
        }
    }

    static func isAvailable(_ name: String) -> Bool {
        #if canImport(UIKit)
        UIFont(name: name, size: 12) != nil
        #else
        NSFont(name: name, size: 12) != nil
        #endif
    }
}

// MARK: - Text styles

enum Typography {
    static let bebasName = "BebasNeue-Regular"
    static let interName = "Inter"

    private static let bebasAvailable = FontRegistrar.isAvailable(bebasName)
    private static let interAvailable = FontRegistrar.isAvailable(interName)

    /// Theatrical display face (Bebas Neue).
    static func display(_ size: CGFloat) -> Font {
        bebasAvailable
            ? .custom(bebasName, size: size)
            : .system(size: size, weight: .heavy).width(.condensed)
    }

    /// Functional UI face (Inter).
    static func text(_ size: CGFloat, weight: Font.Weight = .regular) -> Font {
        interAvailable
            ? .custom(interName, size: size).weight(weight)
            : .system(size: size, weight: weight)
    }

    // DESIGN.md scale
    static var displayXL: Font { display(96) }
    static var headlineLG: Font { display(64) }
    static var headlineMD: Font { display(32) }
    static var titleLG: Font { text(20, weight: .semibold) }
    static var bodyLG: Font { text(16) }
    static var bodySM: Font { text(14) }
    static var labelCaps: Font { text(12, weight: .bold) }
}

// MARK: - Label-caps helper

/// Uppercased, tracked 12pt bold label — the design system's `label-caps` style.
struct LabelCaps: ViewModifier {
    var color: Color = Theme.textSecondary

    func body(content: Content) -> some View {
        content
            .font(Typography.labelCaps)
            .textCase(.uppercase)
            .kerning(1.2)
            .foregroundStyle(color)
    }
}
