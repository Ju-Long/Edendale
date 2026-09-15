//
//  PlayerPlaylistPanel.swift
//  Edendale
//
//  The list sidebar: the show's episodes when metadata is present,
//  otherwise the video files sitting in the same folder as the playing file.
//

import SwiftUI
import Kingfisher

struct PlayerPlaylistPanel: View {
    @Environment(PlayerSession.self) private var session
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let chrome: PlayerChromeModel
    let item: PlaybackItem

    @FocusState private var focusedRow: RowID?

    /// Scroll and focus target identity for playlist rows.
    private enum RowID: Hashable {
        case episode(UUID)
        case file(URL)
    }

    private var playlistShow: TVShow? {
        guard let episode = item.episode, let show = episode.show,
              show.episodes.contains(where: { $0.id == episode.id })
        else { return nil }
        return show
    }

    private var currentRowID: RowID? {
        if playlistShow != nil, let episodeID = item.episode?.id {
            return .episode(episodeID)
        }
        if let url = item.url {
            return .file(url)
        }
        return nil
    }

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    header

                    if let show = playlistShow {
                        episodeList(show)
                    } else {
                        fileList
                    }
                }
                .padding(24)
            }
            .scrollIndicators(.hidden)
            .onAppear {
                if let id = currentRowID {
                    proxy.scrollTo(id, anchor: .center)
                    #if os(tvOS)
                    focusedRow = id
                    #endif
                }
            }
            .onChange(of: item.id) {
                guard let id = currentRowID else { return }
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.3)) {
                    proxy.scrollTo(id, anchor: .center)
                }
                #if os(tvOS)
                focusedRow = id
                #endif
            }
        }
        #if os(tvOS)
        .defaultFocus($focusedRow, currentRowID)
        #endif
    }

    private var header: some View {
        HStack {
            Text(
                playlistShow != nil
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
                        let rowID = RowID.episode(episode.id)
                        row(
                            title: episode.displayTitle,
                            detail: episode.episodeCode,
                            playtime: episode.formattedDuration,
                            artworkURL: episode.stillURL ?? show.backdropURL,
                            showsArtwork: true,
                            isCurrent: episode.id == item.episode?.id,
                            isFocused: focusedRow == rowID
                        ) {
                            Task { await session.play(episode: episode) }
                        }
                        .id(rowID)
                        .focused($focusedRow, equals: rowID)
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
                    let rowID = RowID.file(fileURL)
                    let isCurrentFile = fileURL == url
                    row(
                        title: isCurrentFile ? (item.movie?.displayTitle ?? fileURL.lastPathComponent) : fileURL.lastPathComponent,
                        detail: nil,
                        playtime: isCurrentFile ? item.movie?.formattedDuration : nil,
                        artworkURL: isCurrentFile ? item.movie?.backdropURL : nil,
                        showsArtwork: isCurrentFile && item.movie != nil,
                        isCurrent: isCurrentFile,
                        isFocused: focusedRow == rowID
                    ) {
                        session.play(siblingURL: fileURL)
                    }
                    .id(rowID)
                    .focused($focusedRow, equals: rowID)
                }
            }
        }
    }

    // MARK: - Row

    private static let artworkWidth: CGFloat = 80
    private static let artworkHeight: CGFloat = 45 // 16:9

    private func row(
        title: String,
        detail: String?,
        playtime: String? = nil,
        artworkURL: URL? = nil,
        showsArtwork: Bool = false,
        isCurrent: Bool,
        isFocused: Bool,
        action: @escaping () -> Void
    ) -> some View {
        let isHighlighted = isCurrent || isFocused
        return Button {
            guard !isCurrent else { return }
            action()
        } label: {
            HStack(spacing: 10) {
                if showsArtwork {
                    artwork(url: artworkURL)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(isHighlighted ? Typography.titleLG : Typography.bodyLG)
                        .foregroundStyle(
                            isHighlighted ? Theme.playlistActiveText : Theme.textPrimary
                        )
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    if let detail {
                        Text(detail)
                            .font(Typography.bodySM)
                            .foregroundStyle(
                                isHighlighted ? Theme.playlistActiveText : Theme.textSecondary
                            )
                    }
                    if let playtime {
                        Text(playtime)
                            .font(Typography.bodySM)
                            .foregroundStyle(
                                isHighlighted
                                    ? Theme.playlistActiveText
                                    : Theme.textSecondary
                            )
                    }
                }
                Spacer()
                if isCurrent {
                    Image(.play)
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(Theme.playlistActiveText)
                        .accessibilityHidden(true)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(
                isHighlighted
                    ? Theme.playlistActiveBackground
                    : .clear,
                in: RoundedRectangle(cornerRadius: Theme.Radius.soft)
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .focusEffectDisabled()
        .scaleEffect(isFocused && !reduceMotion ? 1.03 : 1)
        .animation(reduceMotion ? nil : .easeOut(duration: 0.12), value: isFocused)
        .accessibilityLabel(title)
        .accessibilityValue(accessibilityRowValue(detail: detail, playtime: playtime))
        .accessibilityAddTraits(isCurrent ? [.isButton, .isSelected] : .isButton)
    }

    private func artwork(url: URL?) -> some View {
        ZStack {
            Theme.surfaceHigh
            Image(.clapperboard)
                .foregroundStyle(Theme.textSecondary)
            KFImage(url)
                .resizable()
                .fade(duration: reduceMotion ? 0 : 0.2)
                .aspectRatio(contentMode: .fill)
        }
        .frame(width: Self.artworkWidth, height: Self.artworkHeight)
        .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.soft))
        .overlay(
            RoundedRectangle(cornerRadius: Theme.Radius.soft)
                .strokeBorder(
                    Theme.hairline,
                    lineWidth: 1
                )
        )
        .accessibilityHidden(true)
    }

    private func accessibilityRowValue(detail: String?, playtime: String?) -> String {
        [detail, playtime].compactMap { $0 }.joined(separator: ", ")
    }
}
