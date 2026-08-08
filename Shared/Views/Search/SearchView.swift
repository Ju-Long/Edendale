//
//  SearchView.swift
//  Edendale
//
//  Search tab with TMDB network search and date range filtering.
//

import SwiftUI
import SwiftData

struct SearchView: View {
    @Environment(WatchProgressStore.self) private var watchStore
    @Environment(SearchCoordinator.self) private var coordinator
    @Environment(YoungAudienceFilter.self) private var youngAudienceFilter
    #if !os(macOS)
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #endif

    /// Local library, matched entirely on device — "From Your Library"
    /// results appear before the first TMDB response comes back.
    @Query private var libraryMovies: [Movie]
    @Query private var libraryShows: [TVShow]

    @State private var model = SearchModel()
    @State private var path = NavigationPath()
    @State private var showingDateFilter = false

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                #if os(tvOS)
                tvFilterBar
                #endif
                
                VStack(alignment: .leading, spacing: 28) {
                    if model.isIdle {
                        idleContent
                    } else {
                        searchContent
                    }
                }
                .padding(.top, localMatches.isEmpty ? 0 : 8)
            }
            .scrollDisabled(isScrollEmpty)
            #if os(tvOS)
            // Group the grid so Up from the top row reaches the filter bar
            // and Down returns into the results (matching MediaDetailView).
            .focusSection()
            #endif
            .background(Theme.background)
            #if !os(tvOS)
            .navigationTitle("Search")
            .safeAreaInset(edge: .top, spacing: 0) {
                filterBar
            }
            #endif
            .searchable(text: $model.searchText, prompt: "Movies, shows, people")
            .task { await model.loadTrendingIfNeeded() }
            .task(id: audienceVerificationKey) {
                await youngAudienceFilter.verify(audienceRefs)
            }
            .navigationDestination(for: MediaRef.self) { ref in
                MediaDetailView(source: .tmdb(ref))
            }
            .navigationDestination(for: PersonRef.self) { PersonDetailView(person: $0) }
            .navigationDestination(for: Movie.self) { MediaDetailView(source: .localMovie($0)) }
            .navigationDestination(for: TVShow.self) { MediaDetailView(source: .localShow($0)) }
            // Fires on first appearance and whenever a new cast tap arrives:
            // pop to the search root and load that person's filmography.
            .task(id: coordinator.pendingPerson) {
                guard let person = coordinator.pendingPerson else { return }
                path = NavigationPath()
                model.showFilmography(for: person)
                coordinator.pendingPerson = nil
            }
            .task(id: coordinator.pendingSearch?.id) {
                guard let request = coordinator.pendingSearch else { return }
                path = NavigationPath()
                model.searchText = request.query
                coordinator.consumeSearch(request.id)
            }
            .settingsToolbar()
            .sheet(isPresented: $showingDateFilter) {
                ReleaseHeatmapView(
                    year: model.displayedYear,
                    counts: model.releaseCounts,
                    selection: model.selectedRange,
                    pendingAnchor: model.pendingAnchor,
                    isLoading: model.isLoadingHeatmap,
                    canGoBack: model.canShowPreviousYear,
                    canGoForward: model.canShowNextYear,
                    summary: model.selectionSummary,
                    onPreviousYear: { model.showPreviousYear() },
                    onNextYear: { model.showNextYear() },
                    onSelectDay: { model.tapDay($0) },
                    onClear: { model.clearSelection() },
                    onDone: { 
                        model.finishSelecting()
                        showingDateFilter = false
                    }
                )
                .padding()
                .onAppear {
                    model.prepareHeatmap()
                    Task { await model.loadHeatmapIfNeeded() }
                }
                .onChange(of: model.displayedYear) { _, _ in
                    Task { await model.loadHeatmapIfNeeded() }
                }
                .presentationDetents([.medium])
            }
        }
    }

    // MARK: - Content

    /// Nothing searched for yet: browse today's trending titles rather than
    /// staring at a placeholder.
    @ViewBuilder
    private var idleContent: some View {
        if !visibleTrending.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                mediaGrid(visibleTrending)
            }
        } else if model.isLoadingTrending || youngAudienceFilter.isVerifying(trendingRefs) {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.vertical, 60)
                .accessibilityLabel("Loading trending titles")
        } else {
            initialState
        }
    }

    /// Results for a query, a committed date range, or a person filter.
    @ViewBuilder
    private var searchContent: some View {
        if !localMatches.isEmpty {
            libraryResults
        }

        if model.query.isAwaitingTerm && model.selectedRange == nil && model.activePerson == nil {
            scopePrompt
        } else if model.isSearching && visibleResults.isEmpty && model.people.isEmpty {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityLabel("Searching")
        } else if youngAudienceFilter.isVerifying(resultAndLocalRefs)
                    && visibleResults.isEmpty && localMatches.isEmpty {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityLabel("Verifying audience ratings")
        } else if visibleResults.isEmpty && model.people.isEmpty {
            if localMatches.isEmpty { emptyState }
        } else if model.scope == .people {
            // A people prefix reorders — people lead, titles still follow.
            peopleResults
            titleResults(header: String(localized: "Also in Titles"))
        } else {
            titleResults(header: localMatches.isEmpty ? nil : String(localized: "From TMDB"))
            peopleResults
        }
    }

    @ViewBuilder
    private func titleResults(header: String?) -> some View {
        if !visibleResults.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                if let header {
                    SectionHeader(title: header)
                        .padding(.horizontal, edgeMargin)
                }
                resultsList
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(header ?? String(localized: "Results"))
        }
    }

    @ViewBuilder
    private var peopleResults: some View {
        if !model.people.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                SectionHeader(title: String(localized: "People"))
                    .padding(.horizontal, edgeMargin)

                LazyVGrid(columns: peopleColumns, alignment: .center, spacing: gridSpacing) {
                    ForEach(model.people) { person in
                        NavigationLink(value: person.ref) {
                            PersonCard(
                                name: person.name,
                                subtitle: person.knownForText,
                                profileURL: person.profileURL,
                                width: personWidth
                            )
                        }
                        .cardLinkStyle()
                        .accessibilityHint("Opens this person's page.")
                    }
                }
                .padding(.horizontal, edgeMargin)
                .padding(.vertical, 14)
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel("People")
        }
    }

    /// "actors:" typed with nothing after it — say what the scope will do
    /// instead of reporting "no results" for an empty term.
    private var scopePrompt: some View {
        VStack(spacing: 12) {
            Image(model.scope == .people ? .circleUserFill : .magnifyingGlassPlay)
                .font(.system(size: 44))
                .foregroundStyle(Theme.surfaceHigh)
                .accessibilityHidden(true)
            Text(scopePromptTitle)
                .font(Typography.headlineMD)
                .foregroundStyle(Theme.textPrimary)
            Text(scopePromptMessage)
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.vertical, 60)
        .accessibilityElement(children: .combine)
    }

    private var scopePromptTitle: String {
        switch model.scope {
        case .people: String(localized: "Search People")
        case .movies: String(localized: "Search Films")
        case .shows: String(localized: "Search Series")
        case .all: String(localized: "Search the Archive")
        }
    }

    private var scopePromptMessage: String {
        switch model.scope {
        case .people: String(localized: "Type a name to find actors and actresses.")
        case .movies: String(localized: "Type a title to search films only.")
        case .shows: String(localized: "Type a title to search series only.")
        case .all: String(localized: "Find movies and shows by title or release date.")
        }
    }

    /// Shared grid used by the idle trending shelf and the TMDB results.
    private func mediaGrid(_ items: [TMDBMediaItem]) -> some View {
        LazyVGrid(columns: gridColumns, alignment: .center, spacing: gridSpacing) {
            ForEach(items, id: \.ref) { item in
                NavigationLink(value: item.ref) {
                    PosterCard(
                        title: item.title,
                        subtitle: item.detailedDateText,
                        posterURL: item.posterURL,
                        placeholderIcon: item.mediaType == .tv ? "tv" : "film",
                        width: posterWidth,
                        isWatched: item.mediaType == .movie
                        ? watchStore.isWatched(item.id, mediaType: .movie)
                        : false
                    )
                }
                .cardLinkStyle()
                .accessibilityHint("Opens the archive record.")
            }
        }
        .padding(.horizontal, edgeMargin)
        .padding(.vertical, 14)
    }

    /// Only lock scrolling when the screen genuinely has nothing in it.
    private var isScrollEmpty: Bool {
        visibleResults.isEmpty
            && model.people.isEmpty
            && visibleTrending.isEmpty
            && localMatches.isEmpty
    }

    /// Filter bar pinned above the results. tvOS gets a focus-aware,
    /// 10-foot-legible bar of solid buttons (no white-on-gold, no dead
    /// `onTapGesture` targets); the compact platforms keep the inline
    /// pill chips.
    @ViewBuilder
    private var filterBar: some View {
        if model.query.isScoped {
            scopeChip
                .padding(.horizontal)
                .padding(.vertical, 8)
        } else if let person = model.activePerson {
            personChip(person)
                .padding(.horizontal)
                .padding(.vertical, 8)
        } else if model.selectedRange != nil {
            filterChip
                .padding(.horizontal)
                .padding(.vertical, 8)
        } else {
            Button(action: { showingDateFilter = true }) {
                HStack {
                    Image(systemName: "calendar")
                    Text("Filter by Date")
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Theme.surfaceLow)
                .clipShape(Capsule())
                .overlay(Capsule().stroke(Theme.hairline, lineWidth: 1))
                .foregroundStyle(Theme.textSecondary)
            }
            .buttonStyle(.plain)
            .padding(.horizontal)
            .padding(.vertical, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    #if os(tvOS)
    /// tvOS filter bar: real focusable buttons in a `focusSection`, styled
    /// with `archiveButtonStyle` so the active state reads as a solid amber
    /// fill (dark `OnGold`/`Background` label) instead of low-contrast white
    /// text over gold. Opaque background keeps focused posters below from
    /// bleeding through the reserved inset.
    private var tvFilterBar: some View {
        HStack(spacing: 20) {
            if model.query.isScoped {
                Button(action: { model.clearScope() }) {
                    Label {
                        Text(model.scope.label)
                    } icon: {
                        Image(.xmark)
                    }
                }
                .archiveButtonStyle(.primary)
            } else if let person = model.activePerson {
                Button(action: { model.clearPerson() }) {
                    Label {
                        Text("Starring \(person.name)")
                    } icon: {
                        Image(.xmark)
                    }
                }
                .archiveButtonStyle(.primary)
            } else if model.selectedRange != nil, let summary = model.selectionSummary {
                Button(action: { showingDateFilter = true }) {
                    Label(summary, image: .calendarDays)
                }
                .archiveButtonStyle(.primary)

                Button(action: { model.clearSelection() }) {
                    Label {
                        Text("Clear")
                    } icon: {
                        Image(.xmark)
                    }
                }
                .archiveButtonStyle(.secondary)
            } else {
                Button(action: { showingDateFilter = true }) {
                    Label("Filter by Date", image: .calendarDays)
                }
                .archiveButtonStyle(.secondary)
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, edgeMargin)
        .padding(.top, 20)
        .padding(.bottom, 12)
        .background(Theme.background)
        .focusSection()
    }
    #endif

    private var filterChip: some View {
        HStack {
            if let summary = model.selectionSummary {
                Text(summary)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textPrimary)
            }

            Button(action: { model.clearSelection() }) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(Theme.textSecondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Theme.gold)
        .clipShape(Capsule())
        .overlay(Capsule().stroke(Theme.gold.opacity(0.3), lineWidth: 1))
        .frame(maxWidth: .infinity, alignment: .leading)
        .onTapGesture {
            showingDateFilter = true
        }
        // The chip's main action is a bare `onTapGesture`, which assistive
        // technology cannot see at all. One element carries both the summary
        // and the two things the chip can do.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Date filter")
        .accessibilityValue(model.selectionSummary ?? "")
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Opens the date filter.")
        .accessibilityAction { showingDateFilter = true }
        .accessibilityAction(named: Text("Clear")) { model.clearSelection() }
    }

    /// Chip shown while the results are a cast member's filmography — mirrors
    /// `filterChip`, tapping the ✕ returns to an empty search. The name itself
    /// pushes that person's page, so the filter is not a dead end.
    private func personChip(_ person: PersonRef) -> some View {
        HStack {
            NavigationLink(value: person) {
                Text("Starring \(person.name)")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textPrimary)
            }
            .buttonStyle(.plain)

            Button(action: { model.clearPerson() }) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(Theme.textSecondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Theme.gold)
        .clipShape(Capsule())
        .overlay(Capsule().stroke(Theme.gold.opacity(0.3), lineWidth: 1))
        .frame(maxWidth: .infinity, alignment: .leading)
        // Name link and ✕ button read as one filter, with the ✕ as a named
        // action rather than an unlabeled glyph.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Starring \(person.name)")
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Opens this person's page.")
        .accessibilityAction { path.append(person) }
        .accessibilityAction(named: Text("Clear")) { model.clearPerson() }
    }

    /// Chip shown while a keyword prefix is scoping the query. The ✕ strips
    /// the prefix from the field and keeps whatever was typed after it.
    private var scopeChip: some View {
        HStack {
            Text(model.scope.label)
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textPrimary)

            Button(action: { model.clearScope() }) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundStyle(Theme.textSecondary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Theme.gold)
        .clipShape(Capsule())
        .overlay(Capsule().stroke(Theme.gold.opacity(0.3), lineWidth: 1))
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(model.scope.label)
        .accessibilityAction(named: Text("Clear")) { model.clearScope() }
    }

    // MARK: - Local library

    /// A local hit: either an imported movie or an imported show. Only the
    /// text query filters these — the release-date heatmap and the person
    /// filmography are TMDB-side concepts with nothing to match on disk.
    private enum LocalMatch: Hashable {
        case movie(Movie)
        case show(TVShow)
    }

    private var localMatches: [LocalMatch] {
        guard model.activePerson == nil else { return [] }
        let needle = model.searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard needle.count > 1 else { return [] }

        let movies = libraryMovies
            .filter {
                $0.displayTitle.localizedCaseInsensitiveContains(needle)
                    && isVisibleToSelectedAudience($0)
            }
            .sorted { $0.displayTitle.localizedCompare($1.displayTitle) == .orderedAscending }
            .map(LocalMatch.movie)
        let shows = libraryShows
            .filter {
                $0.displayName.localizedCaseInsensitiveContains(needle)
                    && isVisibleToSelectedAudience($0)
            }
            .sorted { $0.displayName.localizedCompare($1.displayName) == .orderedAscending }
            .map(LocalMatch.show)
        return movies + shows
    }

    private var libraryResults: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionHeader(title: String(localized: "From Your Library"))
                .padding(.horizontal, edgeMargin)

            LazyVGrid(columns: gridColumns, alignment: .center, spacing: gridSpacing) {
                ForEach(localMatches, id: \.self) { match in
                    switch match {
                    case .movie(let movie): localCard(movie)
                    case .show(let show): localCard(show)
                    }
                }
            }
            .padding(.horizontal, edgeMargin)
            .padding(.vertical, 14)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("From Your Library")
    }

    private func localCard(_ movie: Movie) -> some View {
        NavigationLink(value: movie) {
            PosterCard(
                title: movie.displayTitle,
                subtitle: movie.releaseYear.map(String.init),
                posterURL: movie.posterURL,
                placeholderIcon: "film",
                width: posterWidth,
                isWatched: movie.tmdbId.map { watchStore.isWatched($0, mediaType: .movie) } ?? false
            )
        }
        .cardLinkStyle()
        .accessibilityHint("Opens the archive record.")
    }

    private func localCard(_ show: TVShow) -> some View {
        NavigationLink(value: show) {
            PosterCard(
                title: show.displayName,
                subtitle: show.firstAirDate.flatMap { String($0.prefix(4)) },
                posterURL: show.posterURL,
                placeholderIcon: "tv",
                width: posterWidth
            )
        }
        .cardLinkStyle()
        .accessibilityHint("Opens the archive record.")
    }

    private var resultsList: some View { mediaGrid(visibleResults) }

    private var visibleResults: [TMDBMediaItem] {
        youngAudienceFilter.visible(model.results)
    }

    private var visibleTrending: [TMDBMediaItem] {
        youngAudienceFilter.visible(model.trending)
    }

    private var trendingRefs: [MediaRef] {
        model.trending.map(\.ref)
    }

    private var localCandidateRefs: [MediaRef] {
        guard model.activePerson == nil else { return [] }
        let needle = model.searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard needle.count > 1 else { return [] }

        let movieRefs = libraryMovies.compactMap { movie -> MediaRef? in
            guard movie.displayTitle.localizedCaseInsensitiveContains(needle),
                  let id = movie.tmdbId else { return nil }
            return MediaRef(id: id, mediaType: .movie)
        }
        let showRefs = libraryShows.compactMap { show -> MediaRef? in
            guard show.displayName.localizedCaseInsensitiveContains(needle),
                  let id = show.tmdbId else { return nil }
            return MediaRef(id: id, mediaType: .tv)
        }
        return movieRefs + showRefs
    }

    private var resultAndLocalRefs: [MediaRef] {
        model.results.map(\.ref) + localCandidateRefs
    }

    private var audienceRefs: [MediaRef] {
        trendingRefs + resultAndLocalRefs
    }

    private var audienceVerificationKey: YoungAudienceVerificationKey {
        YoungAudienceVerificationKey(
            isEnabled: youngAudienceFilter.isEnabled,
            contextIdentifier: youngAudienceFilter.contextIdentifier,
            refs: audienceRefs
        )
    }

    private func isVisibleToSelectedAudience(_ movie: Movie) -> Bool {
        guard youngAudienceFilter.isEnabled else { return true }
        guard let id = movie.tmdbId else { return false }
        return youngAudienceFilter.allows(MediaRef(id: id, mediaType: .movie))
    }

    private func isVisibleToSelectedAudience(_ show: TVShow) -> Bool {
        guard youngAudienceFilter.isEnabled else { return true }
        guard let id = show.tmdbId else { return false }
        return youngAudienceFilter.allows(MediaRef(id: id, mediaType: .tv))
    }

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

    /// People cards are square-portrait, so they pack tighter than posters.
    private var personWidth: CGFloat { posterWidth * 0.72 }

    private var peopleColumns: [GridItem] {
        [GridItem(.adaptive(minimum: personWidth, maximum: personWidth), spacing: gridSpacing)]
    }

    private var gridSpacing: CGFloat {
        #if os(tvOS)
        24
        #else
        20
        #endif
    }
    
    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 44))
                .foregroundStyle(Theme.surfaceHigh)
                .accessibilityHidden(true)
            Text("No Results Found")
                .font(Typography.headlineMD)
                .foregroundStyle(Theme.textPrimary)
            Text("Try adjusting your search or date filter.")
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }

    private var initialState: some View {
        VStack(spacing: 12) {
            Image(.magnifyingGlassPlay)
                .font(.system(size: 44))
                .foregroundStyle(Theme.surfaceHigh)
                .accessibilityHidden(true)
            Text("Search the Archive")
                .font(Typography.headlineMD)
                .foregroundStyle(Theme.textPrimary)
            Text("Find movies and shows by title or release date.")
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .combine)
    }
}

/// Poster/card links share one style: tvOS uses the reserved-bounds focus
/// enlargement, every other platform draws the card unadorned.
private extension View {
    @ViewBuilder
    func cardLinkStyle() -> some View {
        #if os(tvOS)
        buttonStyle(CardFocusButtonStyle())
        #else
        buttonStyle(.plain)
        #endif
    }
}
