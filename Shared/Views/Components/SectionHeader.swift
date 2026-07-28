//
//  SectionHeader.swift
//  Edendale
//
//  Bebas section heading with a trailing hairline rule: "YOUR ARCHIVE ———".
//  Given a SectionScrubber, the rule becomes a slider mirroring an attached
//  horizontal scroll view, with a draggable capsule as the thumb.
//

import SwiftUI

/// Live link between a SectionHeader's rule and a horizontal scroll view.
struct SectionScrubber {
    /// Scroll position, 0…1.
    var progress: Double
    /// Viewport width / content width; sizes the capsule thumb.
    var visibleFraction: Double
    /// Receives a new 0…1 position while the thumb is dragged.
    var onScrub: (Double) -> Void

    /// The scrubber only appears when the content actually overflows.
    var isScrollable: Bool { visibleFraction < 0.999 }
}

struct SectionHeader: View {
    let title: String
    /// When set (and the content overflows), the rule becomes a scrubber.
    var scrubber: SectionScrubber?

    var body: some View {
        HStack(spacing: 16) {
            Text(title)
                .font(Typography.headlineMD)
                .textCase(.uppercase)
                .kerning(2)
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
                .fixedSize()
            if let scrubber, scrubber.isScrollable {
                ScrubberRule(scrubber: scrubber)
            } else {
                Rectangle()
                    .fill(Theme.outline)
                    .frame(height: 1)
            }
        }
        .accessibilityAddTraits(.isHeader)
    }
}

/// The hairline rule as a slider track, with a gold capsule thumb whose
/// width mirrors how much of the shelf is visible.
private struct ScrubberRule: View {
    let scrubber: SectionScrubber

    private let thumbHeight: CGFloat = 5
    private let minThumbWidth: CGFloat = 28

    var body: some View {
        GeometryReader { proxy in
            let trackWidth = proxy.size.width
            let thumbWidth = max(trackWidth * scrubber.visibleFraction, minThumbWidth)
            let travel = max(trackWidth - thumbWidth, 0)

            ZStack(alignment: .leading) {
                Rectangle()
                    .fill(Theme.outline)
                    .frame(height: 1)
                Capsule()
                    .fill(Theme.gold)
                    .frame(width: thumbWidth, height: thumbHeight)
                    .offset(x: travel * min(max(scrubber.progress, 0), 1))
            }
            .frame(maxHeight: .infinity)
            // tvOS has no touch surface: the rule stays a read-only progress
            // indicator there, driven by the focused shelf's scroll position.
            #if !os(tvOS)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard travel > 0 else { return }
                        let fraction = (value.location.x - thumbWidth / 2) / travel
                        scrubber.onScrub(min(max(fraction, 0), 1))
                    }
            )
            #endif
        }
        .frame(height: 16) // generous hit area around the 5pt thumb
    }
}
