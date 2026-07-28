//
//  LandscapeCard.swift
//  Edendale
//
//  16:9 landscape artwork card for episode shelves and Continue Watching:
//  still/backdrop image with caption metadata, watch-progress hairline,
//  and the same hover scale + Projection Gold glow as PosterCard.
//
//  tvOS mirrors PosterCard: the caption is scrimmed into the bottom of the
//  artwork so the focus effect can't hide it, and focus is handled by the
//  enclosing CardFocusButtonStyle rather than pointer hover.
//

import SwiftUI
import Kingfisher

struct LandscapeCard: View {
    let title: String
    var subtitle: String?
    var imageURL: URL?
    /// Asset name of the placeholder symbol shown while there is no artwork.
    var placeholderIcon: String = "film"
    var width: CGFloat = 280
    /// Watch progress in [0, 1]; draws a gold hairline along the bottom edge.
    var progress: Double?
    /// Marks fully watched items with a gold check in the artwork corner.
    var isWatched: Bool = false

    @State private var isHovering = false

    private var height: CGFloat { width * 9 / 16 }

    var body: some View {
        #if os(tvOS)
        artwork
            .frame(width: width)
        #else
        VStack(alignment: .leading, spacing: 8) {
            artwork
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
        .frame(width: width, alignment: .leading)
        .scaleEffect(isHovering ? 1.05 : 1)
        .shadow(color: isHovering ? Theme.goldGlow : .clear, radius: 16)
        .animation(.easeOut(duration: 0.18), value: isHovering)
        .onHover { isHovering = $0 }
        #endif
    }

    private var artwork: some View {
        ZStack {
            Theme.surface
            Image(placeholderIcon)
                .font(.system(size: width * 0.16))
                .foregroundStyle(Theme.surfaceHigh)
            if let imageURL {
                KFImage(imageURL)
                    .resizable()
                    .fade(duration: 0.25)
                    .aspectRatio(contentMode: .fill)
            }
        }
        .frame(width: width, height: height)
        #if os(tvOS)
        .overlay(alignment: .bottom) { captionOverlay }
        #endif
        .overlay(alignment: .topTrailing) {
            if isWatched {
                Image(.check)
                    .font(.system(size: 12))
                    .foregroundStyle(Theme.gold)
                    .padding(8)
                    .background(Circle().fill(Theme.surface))
                    .padding(10)
            }
        }
        .overlay(alignment: .bottomLeading) {
            if let progress, progress > 0 {
                Rectangle()
                    .fill(Theme.gold)
                    .frame(width: width * min(max(progress, 0), 1), height: 3)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.soft))
        .overlay {
            RoundedRectangle(cornerRadius: Theme.Radius.soft)
                .strokeBorder(isHovering ? Theme.outlineBright : Theme.hairline, lineWidth: 1)
        }
    }

    #if os(tvOS)
    /// Title + subtitle scrimmed into the bottom of the artwork so the tvOS
    /// focus effect never covers them (same treatment as PosterCard).
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
