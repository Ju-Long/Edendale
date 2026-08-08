//
//  WatchlistView.swift
//  Edendale
//
//  Movies and shows saved for later, grouped into adaptive poster grids.
//

import SwiftUI

struct WatchlistView: View {
    @Environment(WatchlistStore.self) private var watchlistStore
    @Environment(YoungAudienceFilter.self) private var youngAudienceFilter
    #if !os(macOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    private var movies: [WatchlistItem] {
        visibleItems.filter { $0.mediaType == .movie }
    }

    private var shows: [WatchlistItem] {
        visibleItems.filter { $0.mediaType == .tv }
    }

    private var watchlistItems: [WatchlistItem] {
        watchlistStore.items.filter(\.isInWatchlist)
    }

    private var visibleItems: [WatchlistItem] {
        watchlistItems.filter { youngAudienceFilter.allows($0.ref) }
    }

    private var audienceVerificationKey: YoungAudienceVerificationKey {
        YoungAudienceVerificationKey(
            isEnabled: youngAudienceFilter.isEnabled,
            contextIdentifier: youngAudienceFilter.contextIdentifier,
            refs: watchlistItems.map(\.ref)
        )
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 40) {
                    if movies.isEmpty && shows.isEmpty && !watchlistItems.isEmpty {
                        audienceFilterState
                    }
                    moviesSection
                    showsSection
                }
                .padding(.vertical, 24)
                .padding(.bottom, 40)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.background)
            #if !os(tvOS)
            .navigationTitle("Watchlist")
            #endif
            .navigationDestination(for: MediaRef.self) { ref in
                MediaDetailView(source: .tmdb(ref))
            }
            .settingsToolbar()
            .task(id: audienceVerificationKey) {
                await youngAudienceFilter.verify(watchlistItems.map(\.ref))
            }
        }
    }

    @ViewBuilder
    private var audienceFilterState: some View {
        if youngAudienceFilter.isVerifying(watchlistItems.map(\.ref)) {
            ProgressView()
                .frame(maxWidth: .infinity)
                .padding(.vertical, 40)
                .accessibilityLabel("Verifying audience ratings")
        } else if youngAudienceFilter.isEnabled {
            Text("No PG or PG-13 titles are currently in your watchlist.")
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.textSecondary)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, edgeMargin)
                .padding(.vertical, 40)
        }
    }

    @ViewBuilder
    private var moviesSection: some View {
        if !movies.isEmpty {
            VStack(alignment: .leading, spacing: 18) {
                SectionHeader(title: String(localized: "Movies"))
                mediaGrid(movies, placeholderIcon: "film")
            }
            .padding(.horizontal, edgeMargin)
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Movies")
        }
    }

    @ViewBuilder
    private var showsSection: some View {
        if !shows.isEmpty {
            VStack(alignment: .leading, spacing: 18) {
                SectionHeader(title: String(localized: "TV Shows"))
                mediaGrid(shows, placeholderIcon: "tv")
            }
            .padding(.horizontal, edgeMargin)
            .accessibilityElement(children: .contain)
            .accessibilityLabel("TV Shows")
        }
    }

    private func mediaGrid(
        _ items: [WatchlistItem],
        placeholderIcon: String
    ) -> some View {
        LazyVGrid(columns: gridColumns, alignment: .center, spacing: gridSpacing) {
            ForEach(items) { item in
                NavigationLink(value: item.ref) {
                    PosterCard(
                        title: item.title,
                        subtitle: item.detailedDateText,
                        posterURL: item.posterURL,
                        placeholderIcon: placeholderIcon,
                        width: posterWidth
                    )
                }
                .watchlistPosterButtonStyle()
                .accessibilityHint("Opens the archive record.")
                .contextMenu {
                    Button(role: .destructive) {
                        watchlistStore.remove(item)
                    } label: {
                        Label("Remove from Watchlist", image: .trashCan)
                    }
                }
            }
        }
    }

    // MARK: - Layout metrics

    private var edgeMargin: CGFloat {
        #if os(macOS) || os(tvOS)
        48
        #else
        horizontalSizeClass == .regular ? 48 : 20
        #endif
    }

    private var posterWidth: CGFloat {
        #if os(macOS)
        180
        #elseif os(tvOS)
        240
        #else
        horizontalSizeClass == .regular ? 180 : 140
        #endif
    }

    private var gridColumns: [GridItem] {
        [GridItem(.adaptive(minimum: posterWidth, maximum: posterWidth), spacing: gridSpacing)]
    }

    private var gridSpacing: CGFloat {
        #if os(tvOS)
        24
        #else
        20
        #endif
    }
}

private extension View {
    @ViewBuilder
    func watchlistPosterButtonStyle() -> some View {
        #if os(tvOS)
        self.buttonStyle(CardFocusButtonStyle())
        #else
        self.buttonStyle(.plain)
        #endif
    }
}
