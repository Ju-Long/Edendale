//
//  ArchiveButtonStyle.swift
//  Edendale
//
//  DESIGN.md buttons: primary (solid deep gold, sharp corners),
//  secondary (1px gold border), ghost (label-caps text only).
//  Hover casts the soft "Projection Glow" instead of a drop shadow.
//
//  Apply via `.archiveButtonStyle(_:)`, never `.buttonStyle` directly:
//  `@Environment(\.isFocused)` never updates inside a ButtonStyle on
//  tvOS, so the modifier below tracks focus with `@FocusState` outside
//  the button and hands it to the style.
//

import SwiftUI

extension View {
    /// Archive button chrome with a focus highlight that works on tvOS.
    /// `active` is for toggle buttons (e.g. Favorite): true renders the
    /// label gold, false parchment. Set colors here, not on the label —
    /// a foreground on the label would override the focused state's.
    func archiveButtonStyle(_ kind: ArchiveButtonStyle.Kind, active: Bool? = nil) -> some View {
        modifier(ArchiveButtonFocusModifier(kind: kind, isActive: active))
    }
}

private struct ArchiveButtonFocusModifier: ViewModifier {
    let kind: ArchiveButtonStyle.Kind
    let isActive: Bool?
    @FocusState private var isFocused: Bool

    func body(content: Content) -> some View {
        content
            .focused($isFocused)
            .buttonStyle(ArchiveButtonStyle(kind: kind, isActive: isActive, isFocused: isFocused))
    }
}

struct ArchiveButtonStyle: ButtonStyle {
    enum Kind { case primary, secondary, ghost }
    var kind: Kind = .primary
    /// Toggle state for stateful buttons; nil for stateless ones.
    var isActive: Bool? = nil
    /// Supplied by `archiveButtonStyle(_:)`; stands in for hover on
    /// tvOS and keyboard navigation, which never deliver `.onHover`.
    var isFocused: Bool = false

    func makeBody(configuration: Configuration) -> some View {
        StyledBody(kind: kind, isActive: isActive, isFocused: isFocused, configuration: configuration)
    }

    private struct StyledBody: View {
        let kind: Kind
        let isActive: Bool?
        let isFocused: Bool
        let configuration: Configuration
        @State private var isHovering = false

        var body: some View {
            configuration.label
                .font(font)
                .textCase(.uppercase)
                .kerning(1.2)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .foregroundStyle(foreground)
                .padding(.horizontal, horizontalPadding)
                .padding(.vertical, verticalPadding)
                .background(background, in: RoundedRectangle(cornerRadius: Theme.Radius.soft))
                .overlay {
                    if kind == .secondary {
                        RoundedRectangle(cornerRadius: Theme.Radius.soft)
                            .strokeBorder(isFocused ? Theme.gold : Theme.goldDeep, lineWidth: 1)
                    }
                }
                .shadow(color: showsGlow ? Theme.goldGlow : .clear, radius: 14)
                .scaleEffect(scale)
                .animation(.easeOut(duration: 0.15), value: configuration.isPressed)
                .animation(.easeOut(duration: 0.15), value: isFocused)
                #if !os(tvOS)
                .onHover { isHovering = $0 }
                #endif
                .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.soft))
        }

        /// Focus always glows (alongside the solid gold fill below);
        /// pointer hover keeps the quieter ghost style glow-free.
        private var showsGlow: Bool {
            if isFocused { return true }
            return isHovering && kind != .ghost
        }

        private var scale: CGFloat {
            if configuration.isPressed { return 0.97 }
            #if os(tvOS)
            return isFocused ? 1.06 : 1
            #else
            return 1
            #endif
        }

        /// Focused buttons of every kind flood solid gold — on the TV's
        /// 10-foot UI the glow alone is too quiet to find the cursor.
        private var foreground: Color {
            if isFocused { return Theme.onGold }
            if let isActive { return isActive ? Theme.gold : Theme.textPrimary }
            switch kind {
            case .primary: return Theme.background
            case .secondary, .ghost: return Theme.gold
            }
        }

        private var background: Color {
            if isFocused { return Theme.gold }
            switch kind {
            case .primary:
                return Theme.goldDeep
            case .secondary, .ghost:
                #if os(tvOS)
                // Opaque backing so gold caps stay legible where the
                // action row overlays a bright hero backdrop.
                return Theme.surfaceLow
                #else
                return .clear
                #endif
            }
        }

        // tvOS reads from across the room: larger type, roomier targets.

        private var font: Font {
            #if os(tvOS)
            Typography.text(24, weight: .bold)
            #else
            Typography.labelCaps
            #endif
        }

        private var horizontalPadding: CGFloat {
            #if os(tvOS)
            kind == .ghost ? 20 : 32
            #else
            kind == .ghost ? 6 : 22
            #endif
        }

        private var verticalPadding: CGFloat {
            #if os(tvOS)
            kind == .ghost ? 12 : 16
            #else
            kind == .ghost ? 6 : 14
            #endif
        }
    }
}
