//
//  PersonCard.swift
//  Edendale
//
//  One person portrait — used by the search People grid and by the detail
//  view's Principal Cast row. Square artwork with soft corners so it sits in
//  the same rhythm as `PosterCard`, and a `circle-user-fill` placeholder for
//  the many TMDB people who have no photograph.
//

import SwiftUI
import Kingfisher

struct PersonCard: View {
    let name: String
    /// Character name (cast row) or best-known titles (search results).
    var subtitle: String?
    var profileURL: URL?
    var width: CGFloat = 90

    @State private var isHovering = false

    var body: some View {
        VStack(spacing: 8) {
            portrait
            Text(name)
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textPrimary)
                .multilineTextAlignment(.center)
                .lineLimit(2)
            if let subtitle {
                Text(subtitle)
                    .font(Typography.text(12))
                    .foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
            }
        }
        .frame(width: width)
        #if !os(tvOS)
        .scaleEffect(isHovering ? 1.05 : 1)
        .shadow(color: isHovering ? Theme.goldGlow : .clear, radius: 16)
        .animation(.easeOut(duration: 0.18), value: isHovering)
        .onHover { isHovering = $0 }
        #endif
        // Portrait, name, and role are one person, not three stops.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(name)
        .accessibilityValue(subtitle ?? "")
    }

    private var portrait: some View {
        ZStack {
            Theme.surface
            Image(.circleUserFill)
                .font(.system(size: portraitSize * 0.42))
                .foregroundStyle(Theme.surfaceHigh)
            if let profileURL {
                KFImage(profileURL)
                    .resizable()
                    .fade(duration: 0.25)
                    .aspectRatio(contentMode: .fill)
            }
        }
        .frame(width: portraitSize, height: portraitSize)
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card))
        .overlay {
            RoundedRectangle(cornerRadius: Theme.Radius.card)
                .strokeBorder(isHovering ? Theme.outlineBright : Theme.hairline, lineWidth: 1)
        }
    }

    /// The artwork is square and slightly inset from the caption width, so a
    /// two-line name never widens the card.
    private var portraitSize: CGFloat { width * 0.94 }
}
