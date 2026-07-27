//
//  CardFocusButtonStyle.swift
//  Edendale
//
//  tvOS reserved-bounds focus treatment for shelf posters and collection
//  cards: the layout slot is already the focused size, the card rests
//  scaled down inside it, and focus expands it to fill the slot. Because
//  the growth never crosses the card's own bounds, no ancestor (scroll
//  views, transition sources) can clip it — unlike the system `.card`
//  style, which lifts past the frame and needs headroom everywhere.
//

import SwiftUI

#if os(tvOS)
struct CardFocusButtonStyle: ButtonStyle {
    @Environment(\.isFocused) private var isFocused

    /// Fraction of the slot the card occupies at rest.
    var restingScale: CGFloat = 0.9

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(scale(configuration))
            .shadow(color: isFocused ? Theme.goldGlow : .clear, radius: 16)
            .animation(.easeOut(duration: 0.18), value: isFocused)
            .animation(.easeOut(duration: 0.1), value: configuration.isPressed)
    }

    private func scale(_ configuration: Configuration) -> CGFloat {
        guard isFocused else { return restingScale }
        return configuration.isPressed ? 0.96 : 1
    }
}
#endif
