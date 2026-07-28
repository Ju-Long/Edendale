//
//  TMDBSeasonBrowser.swift
//  Edendale
//
//  Season/episode browser for shows that are not (or not yet) in the local
//  library. The detail view shows this whenever a TMDB show has no imported
//  match; imported shows keep their own playable episode shelves.
//
//  Seasons come free with the show's detail response; each season's episode
//  list is fetched on demand and cached, so opening a show never pays for
//  seasons nobody looks at.
//

import SwiftUI

struct TMDBSeasonBrowser: View {
    let showId: Int
    let seasons: [TMDBSeasonSummary]

    @Environment(WatchProgressStore.self) private var watchStore
    #if !os(macOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    @State private var selectedSeason: Int?
    /// Episodes already fetched, keyed by season number.
    @State private var episodesBySeason: [Int: [TMDBEpisodeDetail]] = [:]
    @State private var loadingSeason: Int?
    @State private var errorMessage: String?

    var body: some View {
        if !seasons.isEmpty {
            VStack(alignment: .leading, spacing: 18) {
                SectionHeader(title: String(localized: "Episodes"))

                seasonPicker

                if let season = selectedSeason {
                    seasonBody(season)
                }
            }
            .task(id: selectedSeason) { await loadSelectedSeason() }
            .onAppear {
                if selectedSeason == nil { selectedSeason = seasons.first?.seasonNumber }
            }
        }
    }

    // MARK: - Season picker

    private var seasonPicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(seasons) { season in
                    FilterChip(
                        title: seasonTitle(season),
                        isSelected: season.seasonNumber == selectedSeason
                    ) {
                        selectedSeason = season.seasonNumber
                    }
                }
            }
            .padding(.vertical, chipRowPadding)
        }
        .scrollClipDisabled()
    }

    /// "Season 2" for numbered seasons, TMDB's own name for Specials.
    private func seasonTitle(_ season: TMDBSeasonSummary) -> String {
        season.seasonNumber == 0 ? season.name : String(localized: "Season \(season.seasonNumber)")
    }

    // MARK: - Episodes

    @ViewBuilder
    private func seasonBody(_ season: Int) -> some View {
        if let episodes = episodesBySeason[season] {
            if episodes.isEmpty {
                message(String(localized: "TMDB lists no episodes for this season yet."))
            } else {
                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: episodeSpacing) {
                        ForEach(episodes) { episode in
                            episodeCard(episode)
                        }
                    }
                    .padding(.vertical, 14)
                }
                .scrollClipDisabled()
            }
        } else if loadingSeason == season {
            ProgressView()
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 24)
        } else if let errorMessage {
            message(errorMessage)
        }
    }

    /// TMDB episodes are not playable — there is no local file behind them —
    /// so the card is a watch-state toggle rather than a play button.
    private func episodeCard(_ episode: TMDBEpisodeDetail) -> some View {
        Button {
            toggleWatched(episode)
        } label: {
            LandscapeCard(
                title: episode.name,
                subtitle: subtitle(for: episode),
                imageURL: episode.stillURL,
                placeholderIcon: "tv",
                width: episodeCardWidth,
                isWatched: watchStore.isWatched(episode.id, mediaType: .episode)
            )
        }
        #if os(tvOS)
        .buttonStyle(CardFocusButtonStyle())
        #else
        .buttonStyle(.plain)
        #endif
        .contextMenu {
            Button {
                toggleWatched(episode)
            } label: {
                Label(
                    watchStore.isWatched(episode.id, mediaType: .episode)
                        ? String(localized: "Mark Unwatched")
                        : String(localized: "Mark Watched"),
                    image: "check"
                )
            }
        }
    }

    private func subtitle(for episode: TMDBEpisodeDetail) -> String {
        guard let runtime = episode.runtime, runtime > 0 else { return episode.episodeCode }
        return "\(episode.episodeCode) · \(String(localized: "\(runtime) min"))"
    }

    private func toggleWatched(_ episode: TMDBEpisodeDetail) {
        if watchStore.isWatched(episode.id, mediaType: .episode) {
            watchStore.remove(tmdbId: episode.id, mediaType: .episode)
        } else {
            watchStore.markCompleted(tmdbId: episode.id, mediaType: .episode)
        }
    }

    private func message(_ text: String) -> some View {
        Text(text)
            .font(Typography.bodySM)
            .foregroundStyle(Theme.textSecondary)
            .padding(.vertical, 12)
    }

    // MARK: - Data

    private func loadSelectedSeason() async {
        guard let season = selectedSeason,
              episodesBySeason[season] == nil,
              TMDBService.shared.isConfigured
        else { return }

        loadingSeason = season
        errorMessage = nil
        do {
            let detail = try await TMDBService.shared.tvSeason(showId: showId, seasonNumber: season)
            episodesBySeason[season] = detail.episodes.sorted { $0.episodeNumber < $1.episodeNumber }
        } catch {
            errorMessage = String(localized: "This season could not be loaded.")
        }
        if loadingSeason == season { loadingSeason = nil }
    }

    // MARK: - Layout metrics

    private var isWide: Bool {
        #if os(macOS)
        true
        #else
        horizontalSizeClass == .regular
        #endif
    }

    private var episodeCardWidth: CGFloat {
        #if os(tvOS)
        420
        #else
        isWide ? 300 : 250
        #endif
    }

    private var episodeSpacing: CGFloat {
        #if os(tvOS)
        8
        #else
        20
        #endif
    }

    private var chipRowPadding: CGFloat {
        #if os(tvOS)
        16
        #else
        4
        #endif
    }
}
