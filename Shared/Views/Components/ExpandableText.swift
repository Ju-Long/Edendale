//
//  ExpandableText.swift
//  Edendale
//
//  Body copy that collapses to a fixed number of lines and reveals the
//  rest on demand. The toggle crossfades between a down chevron (expand)
//  and an up chevron (collapse); the affordance only appears when the
//  text is long enough to clip.
//

import SwiftUI

struct ExpandableText: View {
    let text: String
    var font: Font = Typography.bodyLG
    var color: Color = Theme.textSecondary
    var lineSpacing: CGFloat = 6
    var collapsedLineLimit: Int = 4

    @State private var expanded = false
    @State private var isTruncated = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(text)
                .font(font)
                .foregroundStyle(color)
                .lineSpacing(lineSpacing)
                .lineLimit(expanded ? nil : collapsedLineLimit)
                .fixedSize(horizontal: false, vertical: true)
                .background { truncationProbe }

            if isTruncated {
                toggle
            }
        }
        // The copy and its reveal toggle are one passage: the full text is
        // always read (clipping is a visual limit, not an editorial one) and
        // expanding is offered as an action rather than a separate stop.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(text)
        .accessibilityActions {
            if isTruncated {
                Button(expanded ? "Show Less" : "Read More") {
                    withAnimation(.easeInOut(duration: 0.25)) { expanded.toggle() }
                }
            }
        }
    }

    // MARK: - Toggle

    /// `.plain` left tvOS to paint its own white focus platter behind the
    /// gold caps; the ghost archive style highlights the toggle itself and
    /// keeps the label legible (colors come from the style, never the label).
    private var toggle: some View {
        Button {
            withAnimation(.easeInOut(duration: 0.25)) { expanded.toggle() }
        } label: {
            HStack(spacing: 8) {
                Text(expanded ? String(localized: "Show Less") : String(localized: "Read More"))
                chevron
            }
            .contentShape(Rectangle())
        }
        .archiveButtonStyle(.ghost)
    }

    /// Down and up glyphs stacked and crossfaded by the expanded state:
    /// the down chevron fades out and the up chevron fades in on expand.
    /// Font and color are inherited from the button style so the glyph
    /// tracks the label — including when focus flips both to `OnGold`.
    private var chevron: some View {
        ZStack {
            Image(.chevronDown).opacity(expanded ? 0 : 1)
            Image(.chevronUp).opacity(expanded ? 1 : 0)
        }
    }

    // MARK: - Truncation detection

    /// Sits behind the visible copy and, while collapsed, checks whether the
    /// full text overflows the collapsed limit — driving whether the toggle
    /// is offered. Skipped while expanded so the affordance stays put.
    @ViewBuilder
    private var truncationProbe: some View {
        if !expanded {
            ViewThatFits(in: .vertical) {
                Text(text)
                    .font(font)
                    .lineSpacing(lineSpacing)
                    .fixedSize(horizontal: false, vertical: true)
                    .hidden()
                    .onAppear { isTruncated = false }
                Color.clear
                    .onAppear { isTruncated = true }
            }
        }
    }
}
