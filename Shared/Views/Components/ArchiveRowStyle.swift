//
//  ArchiveRowStyle.swift
//  Edendale
//
//  Button chrome for full-width list rows (e.g. episode rows). Unlike
//  ArchiveButtonStyle it leaves the label's fonts and colors untouched
//  and highlights with a quiet surface fill + gold border, so the row's
//  gold accents and secondary text stay legible — the button style's
//  solid-gold focus flood hid gold icons, and `.plain` gave no
//  highlight at all.
//
//  Apply via `.archiveRowStyle()`, never `.buttonStyle` directly:
//  `@Environment(\.isFocused)` never updates inside a ButtonStyle on
//  tvOS, so the modifier below tracks focus with `@FocusState` outside
//  the button and hands it to the style.
//

import SwiftUI

extension View {
    /// Row-shaped highlight (hover, press, tvOS/keyboard focus) that
    /// keeps the row content's own colors.
    func archiveRowStyle() -> some View {
        modifier(ArchiveRowFocusModifier())
    }
}

private struct ArchiveRowFocusModifier: ViewModifier {
    @FocusState private var isFocused: Bool

    func body(content: Content) -> some View {
        content
            .focused($isFocused)
            .buttonStyle(ArchiveRowStyle(isFocused: isFocused))
    }
}

struct ArchiveRowStyle: ButtonStyle {
    /// Supplied by `archiveRowStyle()`; stands in for hover on tvOS
    /// and keyboard navigation, which never deliver `.onHover`.
    var isFocused: Bool = false

    func makeBody(configuration: Configuration) -> some View {
        StyledBody(isFocused: isFocused, configuration: configuration)
    }

    private struct StyledBody: View {
        let isFocused: Bool
        let configuration: Configuration
        @State private var isHovering = false

        private var isHighlighted: Bool {
            isFocused || isHovering || configuration.isPressed
        }

        var body: some View {
            configuration.label
                .background(
                    isHighlighted ? (isFocused ? Theme.surfaceHigh : Theme.surface) : .clear,
                    in: RoundedRectangle(cornerRadius: Theme.Radius.soft)
                )
                .overlay {
                    RoundedRectangle(cornerRadius: Theme.Radius.soft)
                        .strokeBorder(isFocused ? Theme.gold : .clear, lineWidth: 1)
                }
                .shadow(color: isFocused ? Theme.goldGlow : .clear, radius: 14)
                .scaleEffect(configuration.isPressed ? 0.99 : 1)
                .animation(.easeOut(duration: 0.15), value: isHighlighted)
                #if !os(tvOS)
                .onHover { isHovering = $0 }
                #endif
                .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.soft))
        }
    }
}
