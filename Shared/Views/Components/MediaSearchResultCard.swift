//
//  MediaSearchResultCard.swift
//  Edendale
//
//  Compact search result with enough context to make overview matches clear.
//

import SwiftUI
import Kingfisher

struct MediaSearchResultCard: View {
    let title: String
    let overview: String?
    let dateText: String?
    let mediaType: TMDBMediaType
    let posterURL: URL?
    var isDownloaded = false

    @State private var isHovering = false

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            poster

            VStack(alignment: .leading, spacing: 8) {
                if isDownloaded {
                    Text("Downloaded")
                        .labelCaps(Theme.gold)
                }

                Text(title)
                    .font(Typography.titleLG)
                    .foregroundStyle(Theme.textPrimary)
                    .lineLimit(2)

                if let dateText {
                    Text(dateText)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                }

                if let overview, !overview.isEmpty {
                    Text(overview)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                        .lineLimit(overviewLineLimit)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(12)
        .frame(maxWidth: .infinity, minHeight: posterHeight + 24, alignment: .topLeading)
        .background(Theme.surfaceLow)
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card))
        .overlay {
            RoundedRectangle(cornerRadius: Theme.Radius.card)
                .strokeBorder(isHovering ? Theme.outlineBright : Theme.hairline, lineWidth: 1)
        }
        .shadow(color: isHovering ? Theme.goldGlow : .clear, radius: 16)
        .contentShape(RoundedRectangle(cornerRadius: Theme.Radius.card))
        #if !os(tvOS)
        .onHover { isHovering = $0 }
        #endif
        // Poster, badge, title, date, and overview are one result.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(accessibilityValue)
    }

    /// Everything under the title, in the order it is drawn, with the media
    /// type spelled out — the poster placeholder is the only other thing
    /// that distinguishes a show from a film.
    private var accessibilityValue: String {
        var parts: [String] = []
        parts.append(
            mediaType == .tv ? String(localized: "TV show") : String(localized: "Movie")
        )
        if isDownloaded { parts.append(String(localized: "Downloaded")) }
        if let dateText { parts.append(dateText) }
        if let overview, !overview.isEmpty { parts.append(overview) }
        return parts.joined(separator: ", ")
    }

    private var poster: some View {
        ZStack {
            Theme.surface
            Image(mediaType == .tv ? "tv" : "film")
                .font(.system(size: posterWidth * 0.28))
                .foregroundStyle(Theme.surfaceHigh)
            if let posterURL {
                KFImage(posterURL)
                    .resizable()
                    .fade(duration: 0.25)
                    .aspectRatio(contentMode: .fill)
            }
        }
        .frame(width: posterWidth, height: posterHeight)
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.soft))
        .overlay {
            RoundedRectangle(cornerRadius: Theme.Radius.soft)
                .strokeBorder(Theme.hairline, lineWidth: 1)
        }
    }

    private var posterWidth: CGFloat {
        #if os(tvOS)
        140
        #else
        92
        #endif
    }

    private var posterHeight: CGFloat { posterWidth * 1.5 }

    private var overviewLineLimit: Int {
        #if os(tvOS)
        5
        #else
        4
        #endif
    }
}
