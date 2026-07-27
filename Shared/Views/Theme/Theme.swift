//
//  Theme.swift
//  Edendale
//
//  Semantic accessors for the Edendale Archive palette (DESIGN.md).
//  Every value is an asset-catalog color set — no literals anywhere else.
//

import SwiftUI

enum Theme {
    /// Obsidian archive floor — the app-wide background.
    static let background = Color("Background")
    /// Dim surface, one step above the floor.
    static let surfaceLow = Color("SurfaceLow")
    /// Cards and containers.
    static let surface = Color("Surface")
    /// Elevated containers (chips, thumbnails).
    static let surfaceHigh = Color("SurfaceHigh")

    /// App accent (AccentColor asset) — hero-spotlight glow on shelf cards.
    static let accent = Color("AccentColor")

    /// Projection Gold — primary accent for text, borders and glows.
    static let gold = Color("Gold")
    /// Deep amber for solid primary buttons.
    static let goldDeep = Color("GoldDeep")
    /// Text placed on solid gold.
    static let onGold = Color("OnGold")
    /// Soft amber outer glow for interactive elements (alpha baked into the asset).
    static let goldGlow = Color("GoldGlow")

    /// Release-density heatmap ramp, dim → bright:
    /// `surface` (none) → `heatLow` → `heatMid` → `goldDeep` → `gold`.
    static let heatLow = Color("HeatLow")
    static let heatMid = Color("HeatMid")

    /// Main text — off-white, never pure white.
    static let textPrimary = Color("TextPrimary")
    /// Muted parchment text for metadata and body copy.
    static let textSecondary = Color("TextSecondary")

    /// Hairline rules and resting borders.
    static let outline = Color("Outline")
    /// Focused/hover borders.
    static let outlineBright = Color("OutlineBright")
    /// 1px glass-layer edge (white at low alpha, baked into the asset).
    static let hairline = Color("HairlineBorder")

    /// High-contrast quiet zone required around generated QR codes.
    static let qrCodeBackground = Color("QRCodeBackground")

    /// Corner radii — "Precise and Architectural" (DESIGN.md).
    enum Radius {
        static let soft: CGFloat = 4
        static let card: CGFloat = 8
        static let glass: CGFloat = 12
    }
}
