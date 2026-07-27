//
//  PosterCard.swift
//  Edendale
//
//  The primary interactive unit: 2:3 poster with soft 4pt corners,
//  caption metadata, and a hover scale + Projection Gold glow.
//
//  tvOS differs: the caption lives *inside* the poster as a scrimmed
//  bottom overlay (so the focus effect can't hide it), and focus is
//  handled by the enclosing `.card` button style in `MediaShelf` rather
//  than the pointer-hover treatment used elsewhere.
//

import SwiftUI
import Kingfisher

struct PosterCard: View {
    let title: String
    var subtitle: String?
    var posterURL: URL?
    /// Asset name of the placeholder symbol shown while there is no artwork.
    var placeholderIcon: String = "film"
    var width: CGFloat = 160
    /// Watch progress in [0, 1]; draws a gold hairline along the poster's bottom edge.
    var progress: Double?
    /// Whether this item has been fully watched; draws a checkmark indicator.
    var isWatched: Bool = false
    /// Accent outer glow marking the card the hero is currently spotlighting.
    var isSpotlit: Bool = false

    @State private var isHovering = false

    var body: some View {
        #if os(tvOS)
        poster
            .frame(width: width)
        #else
        VStack(alignment: .leading, spacing: 8) {
            poster
            Text(title)
                .font(Typography.text(15, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
            if let subtitle {
                Text(subtitle)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(1)
            }
        }
        .frame(width: width)
        .scaleEffect(isHovering ? 1.05 : 1)
        .shadow(color: glowColor, radius: 16)
        .animation(.easeOut(duration: 0.18), value: isHovering)
        .animation(.easeOut(duration: 0.35), value: isSpotlit)
        .onHover { isHovering = $0 }
        #endif
    }

    private var glowColor: Color {
        if isSpotlit { return Theme.accent }
        return isHovering ? Theme.goldGlow : .clear
    }

    private var poster: some View {
        ZStack {
            Theme.surface
            Image(placeholderIcon)
                .font(.system(size: width * 0.28))
                .foregroundStyle(Theme.surfaceHigh)
            if let posterURL {
                KFImage(posterURL)
                    .resizable()
                    .fade(duration: 0.25)
                    .aspectRatio(contentMode: .fill)
            }
        }
        .frame(width: width, height: width * 1.5)
        #if os(tvOS)
        .overlay(alignment: .bottom) { captionOverlay }
        #endif
        .overlay(alignment: .bottomLeading) {
            if let progress, progress > 0 {
                Rectangle()
                    .fill(Theme.gold)
                    .frame(width: width * min(max(progress, 0), 1), height: 3)
            }
        }
        .overlay(alignment: .topLeading) {
            if isWatched {
                Image("check")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(Theme.gold)
                    .padding(4)
                    .background(Circle().fill(Theme.surfaceHigh))
                    .padding(6)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.soft))
        .overlay {
            RoundedRectangle(cornerRadius: Theme.Radius.soft)
                .strokeBorder(isHovering ? Theme.outlineBright : Theme.hairline, lineWidth: 1)
        }
    }

    #if os(tvOS)
    /// Title + date scrimmed into the bottom of the poster so the tvOS focus
    /// effect never covers them (the old external caption sat below the card
    /// and was hidden by the focus highlight).
    private var captionOverlay: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(Typography.text(18, weight: .semibold))
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
            if let subtitle {
                Text(subtitle)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.top, 28)
        .padding(.bottom, 12)
        .background(
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0),
                    .init(color: Theme.background, location: 1)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        )
    }
    #endif
}
