//
//  HeroPageIndicator.swift
//  Edendale
//
//  Hero-page controls shared by all platforms: page dots (clickable where a
//  pointer/touch exists; display-only on tvOS, where the Siri remote swipes
//  the pager directly) and the current page count.
//

import SwiftUI
import Kingfisher

struct HeroPageIndicatorItem: Identifiable {
    let id: Int
    let title: String
    let posterURL: URL?
    let placeholderIcon: String
}

struct HeroPageIndicator: View {
    let items: [HeroPageIndicatorItem]
    let selection: Int
    let onSelect: (Int) -> Void

    var body: some View {
        HStack(spacing: 6) {
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                // tvOS shows plain dots: focusable buttons here would add
                // focus stops between the hero button and the shelves.
                #if os(tvOS)
                    dot(isSelected: selection == index)
                #else
                    Button {
                        onSelect(index)
                    } label: {
                        dot(isSelected: selection == index)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(item.title)
                #endif
            }
        }
    }

    private func dot(isSelected: Bool) -> some View {
        Capsule()
            .fill(isSelected ? Color.accent : .outline)
            .frame(width: isSelected ? 16 : 8, height: 8)
    }
}
