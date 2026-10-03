//
//  PlayerUpNextView.swift
//  Edendale
//
//  "Up Next" card that appears near the end of a TV episode, previewing
//  the next episode with its still image, code, and title.
//

import SwiftUI
import Kingfisher

struct PlayerUpNextView: View {
    let episode: Episode
    let onPlay: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @FocusState private var isFocused: Bool
    @State private var isHovered = false

    private var imageURL: URL? {
        episode.stillURL ?? episode.show?.backdropURL
    }

    var body: some View {
        Button(action: onPlay) {
            HStack(spacing: 10) {
                artwork
                details
            }
            .padding(10)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: Theme.Radius.card))
            .overlay {
                RoundedRectangle(cornerRadius: Theme.Radius.card)
                    .strokeBorder(isFocused || isHovered ? Theme.gold : Theme.outline, lineWidth: 1)
            }
        }
        .buttonStyle(.plain)
        .focused($isFocused)
        .focusEffectDisabled()
        #if !os(tvOS)
        .onHover { isHovered = $0 }
        #endif
        .frame(maxWidth: 280)
        .transition(reduceMotion ? .identity : .asymmetric(
            insertion: .move(edge: .trailing).combined(with: .opacity),
            removal: .opacity
        ))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Up Next: \(episode.episodeCode) \(episode.displayTitle)"))
        .accessibilityHint("Play the next episode now")
    }

    private var artwork: some View {
        ZStack {
            Theme.surfaceHigh
            Image(.clapperboard)
                .foregroundStyle(Theme.textSecondary)
                .font(.system(size: 14))
            KFImage(imageURL)
                .resizable()
                .fade(duration: reduceMotion ? 0 : 0.2)
                .aspectRatio(contentMode: .fill)
        }
        .frame(width: 80, height: 45)
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.soft))
        .overlay(
            RoundedRectangle(cornerRadius: Theme.Radius.soft)
                .strokeBorder(Theme.hairline, lineWidth: 1)
        )
        .accessibilityHidden(true)
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("UP NEXT")
                .font(Typography.labelCaps)
                .foregroundStyle(Theme.gold)
            Text(episode.episodeCode)
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
            Text(episode.displayTitle)
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
        }
    }
}
