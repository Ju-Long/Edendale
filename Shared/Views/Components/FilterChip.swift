//
//  FilterChip.swift
//  Edendale
//
//  Curated-collection filter chip: gold outline when selected,
//  quiet outline otherwise. On tvOS the default focus effect (a solid
//  white capsule that bleeds over neighbours) is disabled in favour of a
//  gold-fill focused state driven by `\.isFocused`.
//

import SwiftUI

struct FilterChip: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ChipLabel(title: title, isSelected: isSelected)
        }
        // tvOS: even `.plain` paints the system's white focus platter behind
        // the chip, so a bare custom style renders the label alone and leaves
        // the focused look entirely to `ChipLabel`.
        #if os(tvOS)
        .buttonStyle(ChipButtonStyle())
        .focusEffectDisabled()
        #else
        .buttonStyle(.plain)
        #endif
    }
}

#if os(tvOS)
/// Chrome-free button style: no platter, no lift — the button's label draws
/// its own focused state via `\.isFocused`. Shared by any tvOS button that
/// must escape the system focus platter (filter chips, episode rows).
struct ChipButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
    }
}
#endif

/// The chip's visual body. Kept separate from `FilterChip` so it can read
/// `\.isFocused` — which only reflects the enclosing button's focus from
/// inside the button's label.
private struct ChipLabel: View {
    let title: String
    let isSelected: Bool
    #if os(tvOS)
    @Environment(\.isFocused) private var isFocused
    #endif

    var body: some View {
        Text(title)
            .font(Typography.text(13, weight: .semibold))
            .foregroundStyle(foreground)
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .background(background, in: Capsule())
            .overlay {
                Capsule().strokeBorder(border, lineWidth: 1)
            }
            .contentShape(Capsule())
        #if os(tvOS)
            .scaleEffect(isFocused ? 1.12 : 1)
            .animation(.easeOut(duration: 0.15), value: isFocused)
        #endif
    }

    private var foreground: Color {
        #if os(tvOS)
        if isFocused { return Theme.onGold }
        #endif
        return isSelected ? Theme.gold : Theme.textSecondary
    }

    private var background: Color {
        #if os(tvOS)
        if isFocused { return Theme.gold }
        #endif
        return isSelected ? Theme.surface : .clear
    }

    private var border: Color {
        #if os(tvOS)
        if isFocused { return Theme.gold }
        #endif
        return isSelected ? Theme.gold : Theme.outline
    }
}
