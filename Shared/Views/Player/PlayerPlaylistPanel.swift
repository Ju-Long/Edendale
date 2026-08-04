//
//  PlayerPlaylistPanel.swift
//  Edendale
//
//  The list sidebar: the show's episodes when metadata is present,
//  otherwise the video files sitting in the same folder as the playing file.
//

import SwiftUI

struct PlayerPlaylistPanel: View {
    @Environment(PlayerSession.self) private var session

    let chrome: PlayerChromeModel
    let item: PlaybackItem

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                header

                if let show = item.episode?.show, !show.episodes.isEmpty {
                    episodeList(show)
                } else {
                    fileList
                }
            }
            .padding(24)
        }
        .scrollIndicators(.hidden)
    }

    private var header: some View {
        HStack {
            Text(
                item.episode?.show != nil
                    ? String(localized: "Episodes")
                    : String(localized: "In This Folder")
            )
                .font(Typography.headlineMD)
                .textCase(.uppercase)
                .foregroundStyle(Theme.textPrimary)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            PlayerIconChip(
                icon: .sidebarRight,
                label: String(localized: "Close Playlist")
            ) {
                chrome.closePanel()
            }
        }
    }

    // MARK: - Episodes (metadata present)

    private func episodeList(_ show: TVShow) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            ForEach(show.availableSeasons, id: \.self) { season in
                VStack(alignment: .leading, spacing: 6) {
                    Text("Season \(season)")
                        .labelCaps()
                        .accessibilityAddTraits(.isHeader)

                    ForEach(show.episodes(for: season)) { episode in
                        row(
                            title: episode.displayTitle,
                            detail: episode.episodeCode,
                            isCurrent: episode.id == item.episode?.id
                        ) {
                            Task { await session.play(episode: episode) }
                        }
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityLabel("Season \(season)")
            }
        }
    }

    // MARK: - Folder files (no metadata)

    private var fileList: some View {
        VStack(alignment: .leading, spacing: 6) {
            if let url = item.url {
                ForEach(PlayerLogic.siblingVideoFiles(of: url), id: \.self) { fileURL in
                    row(
                        title: fileURL.lastPathComponent,
                        detail: nil,
                        isCurrent: fileURL == url
                    ) {
                        session.play(siblingURL: fileURL)
                    }
                }
            }
        }
    }

    // MARK: - Row

    private func row(
        title: String,
        detail: String?,
        isCurrent: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button {
            guard !isCurrent else { return }
            action()
        } label: {
            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(Typography.bodyLG)
                        .foregroundStyle(isCurrent ? Theme.gold : Theme.textPrimary)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    if let detail {
                        Text(detail)
                            .font(Typography.bodySM)
                            .foregroundStyle(Theme.textSecondary)
                    }
                }
                Spacer()
                if isCurrent {
                    // "Now playing" is announced as a selected trait below.
                    Image(.play)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Theme.gold)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(isCurrent ? Theme.surface : .clear, in: RoundedRectangle(cornerRadius: Theme.Radius.soft))
            .contentShape(Rectangle())
        }
        .playerChipStyle()
        // Title, episode code, and the playing marker are one row.
        .accessibilityLabel(title)
        .accessibilityValue(detail ?? "")
        .accessibilityAddTraits(isCurrent ? [.isButton, .isSelected] : .isButton)
    }
}
