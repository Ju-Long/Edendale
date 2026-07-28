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
    }

    // MARK: - Toggle

    private var toggle: some View {
        Button {
            withAnimation(.easeInOut(duration: 0.25)) { expanded.toggle() }
        } label: {
            HStack(spacing: 8) {
                Text(expanded ? String(localized: "Show Less") : String(localized: "Read More"))
                    .labelCaps(Theme.gold)
                chevron
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    /// Down and up glyphs stacked and crossfaded by the expanded state:
    /// the down chevron fades out and the up chevron fades in on expand.
    private var chevron: some View {
        ZStack {
            Image(.chevronDown).opacity(expanded ? 0 : 1)
            Image(.chevronUp).opacity(expanded ? 1 : 0)
        }
        .font(.system(size: 11))
        .foregroundStyle(Theme.gold)
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
