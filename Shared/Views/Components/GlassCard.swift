//
//  GlassCard.swift
//  Edendale
//
//  Glass layer per DESIGN.md: deep backdrop blur with a 1px hairline edge.
//  Hosts scorecards, the overview record, and other level-2 surfaces.
//

import SwiftUI

struct GlassCard<Content: View>: View {
    var padding: CGFloat = 24
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassBackground(in: RoundedRectangle(cornerRadius: Theme.Radius.card))
            .overlay {
                RoundedRectangle(cornerRadius: Theme.Radius.card)
                    .strokeBorder(Theme.hairline, lineWidth: 1)
            }
    }
}
