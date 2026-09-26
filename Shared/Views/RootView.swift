//
//  RootView.swift
//  Edendale
//
//  Adaptive shell: sidebar on iPadOS (Settings pinned at the bottom),
//  tab bar on iPhone (Settings in each page's toolbar) — both present
//  Settings as a sheet. The tvOS sidebar and the visionOS ornament give
//  Settings its own tab. Search uses the OS 26 search tab role.
//
//  macOS uses a split view instead (see RootSidebar): its sidebar also
//  lists the Watchlist and Downloaded sections, ⌘B hides it, and Settings
//  sits below Search.
//

import SwiftUI
import SwiftData

enum RootTab: Hashable {
    case movies, watchlist, downloaded, settings, search
}

struct RootView: View {
    @Environment(TMDBAccountStore.self) private var tmdbAccount
    @Environment(WatchlistStore.self) private var watchlistStore
    @Environment(UserMediaStore.self) private var userMediaStore
    @Environment(WatchProgressStore.self) private var watchStore
    @Environment(PlayerSession.self) private var playerSession
    @Environment(AppRouter.self) private var appRouter
    @Environment(YoungAudienceFilter.self) private var youngAudienceFilter
    @Environment(\.scenePhase) private var scenePhase

    @Query private var libraryMovies: [Movie]
    @Query private var libraryShows: [TVShow]
    @Query private var libraryEpisodes: [Episode]

    /// Session cache for the Movies & Shows tab — owned here so tab
    /// switches never refetch.
    @State private var moviesModel = MoviesShowsModel()
    /// Routes cast taps in a detail page over to the Search tab.
    @State private var searchCoordinator = SearchCoordinator()
    #if os(macOS)
    @State private var sidebarItem: SidebarItem = .movies
    @State private var sidebarVisibility: NavigationSplitViewVisibility = .all
    /// The split view shows one page at a time, so the search session lives
    /// here to survive visits to other pages (a tab view keeps each tab).
    @State private var searchModel = SearchModel()
    #else
    @State private var selectedTab: RootTab = .movies
    #endif
    #if os(iOS)
    @State private var showSettings = false
    #endif
    @State private var externalDetail: RoutedMediaDetail?

    #if os(macOS)
    /// The sidebar row's root tab; routes that pick a tab open its whole page.
    private var selectedTab: RootTab {
        get { sidebarItem.tab }
        nonmutating set { sidebarItem = SidebarItem(newValue) }
    }
    #endif

    var body: some View {
        navigation
        .environment(moviesModel)
        .environment(searchCoordinator)
        .sheet(item: $externalDetail) { destination in
            NavigationStack {
                MediaDetailView(source: destination.source)
            }
        }
        // A cast tap anywhere sets `pendingPerson`; jump to the Search tab so
        // it can load that person's filmography.
        .onChange(of: searchCoordinator.pendingPerson) { _, person in
            if person != nil { selectedTab = .search }
        }
        .onChange(of: searchCoordinator.pendingSearch) { _, request in
            if request != nil { selectedTab = .search }
        }
        .onChange(of: hasWatchlistItems) { _, hasWatchlistItems in
            if !hasWatchlistItems && selectedTab == .watchlist {
                selectedTab = .movies
            }
        }
        .task(id: appRouter.request?.id) {
            guard let request = appRouter.request else { return }
            await handle(request.route)
            appRouter.consume(request.id)
        }
        .task(id: audienceVerificationKey) {
            await youngAudienceFilter.verify(audienceRefs)
        }
        .task(id: widgetSnapshotRevision) {
            WidgetSnapshotStore.publish(
                trending: youngAudienceFilter.visible(moviesModel.trending),
                movies: visibleLibraryMovies,
                shows: visibleLibraryShows,
                episodes: visibleLibraryEpisodes,
                progress: watchStore.inProgress
            )
        }
        .tint(Theme.gold)
        // The archive is dark by design — a lit theater breaks the vault.
        .preferredColorScheme(.dark)
        .background(Theme.background)
        // Merge the connected TMDB account's lists on launch and again
        // whenever a sign-in completes (or arrives via iCloud Keychain).
        .task(id: tmdbAccount.isSignedIn) {
            if tmdbAccount.isSignedIn {
                await syncAccountStateFromTMDB()
            }
        }
        .onChange(of: scenePhase) { _, phase in
            guard phase == .active, tmdbAccount.isSignedIn else { return }
            Task { await syncAccountStateFromTMDB() }
        }
    }

    // MARK: - Navigation

    @ViewBuilder
    private var navigation: some View {
        #if os(macOS)
        let watchlistSections = self.watchlistSections
        let downloadedSections = self.downloadedSections
        NavigationSplitView(columnVisibility: $sidebarVisibility) {
            RootSidebar(
                selection: $sidebarItem,
                showsWatchlist: hasWatchlistItems,
                watchlistSections: watchlistSections,
                downloadedSections: downloadedSections
            )
        } detail: {
            sidebarDestination
        }
        // View ▸ Hide Sidebar (⌘B); see EdendaleCommands.
        .focusedSceneValue(\.sidebarVisibility, $sidebarVisibility)
        // A section row that empties (its last title removed, or hidden by
        // the audience filter) falls back to its whole page.
        .onChange(of: watchlistSections) { _, sections in
            sidebarItem = sidebarItem.resolved(
                watchlistSections: sections,
                downloadedSections: downloadedSections
            )
        }
        .onChange(of: downloadedSections) { _, sections in
            sidebarItem = sidebarItem.resolved(
                watchlistSections: watchlistSections,
                downloadedSections: sections
            )
        }
        #else
        TabView(selection: $selectedTab) {
            Tab("Movies & Shows", image: "clapperboard", value: RootTab.movies) {
                MoviesShowsView()
            }

            if hasWatchlistItems {
                Tab("Watchlist", image: "film-stack", value: RootTab.watchlist) {
                    WatchlistView()
                }
            }

            Tab("Downloaded", image: "folder-closed", value: RootTab.downloaded) {
                DownloadedView()
            }

            #if os(tvOS) || os(visionOS)
            Tab("Settings", image: "gear-complex", value: RootTab.settings) {
                SettingsView()
            }
            #endif

            Tab("Search", image: "magnifying-glass-play", value: RootTab.search, role: .search) {
                SearchView()
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        #if os(iOS)
        .tabViewSidebarBottomBar {
            Button {
                showSettings = true
            } label: {
                Label("Settings", image: "gear-complex")
            }
            .archiveButtonStyle(.ghost)
        }
        .sheet(isPresented: $showSettings) {
            SettingsView()
        }
        #endif
        #endif
    }

    #if os(macOS)
    @ViewBuilder
    private var sidebarDestination: some View {
        switch sidebarItem {
        case .movies:
            MoviesShowsView()
        case .watchlist(let section):
            // A fresh navigation stack per row: choosing a section always
            // lands on its page, never on a detail pushed from another row.
            WatchlistView(section: section)
                .id(sidebarItem)
        case .downloaded(let section):
            DownloadedView(section: section)
                .id(sidebarItem)
        case .search:
            SearchView(model: searchModel)
        case .settings:
            SettingsView()
        }
    }

    private var watchlistSections: [WatchlistSection] {
        WatchlistSection.available(in: watchlistStore.items.filter {
            $0.isInWatchlist && youngAudienceFilter.allows($0.ref)
        })
    }

    private var downloadedSections: [DownloadedSection] {
        // Same show order as the Downloaded page, which decides between
        // duplicate show records.
        let shows = visibleLibraryShows.sorted { $0.dateAdded > $1.dateAdded }
        let resumeItems = ResumeItem.items(
            watchStore: watchStore,
            movies: visibleLibraryMovies,
            shows: shows,
            limit: 1
        )
        return DownloadedSection.available(
            hasResumeItems: !resumeItems.isEmpty,
            movies: visibleLibraryMovies,
            shows: shows
        )
    }
    #endif

    private var hasWatchlistItems: Bool {
        watchlistStore.items.contains {
            $0.isInWatchlist && youngAudienceFilter.allows($0.ref)
        }
    }

    private var visibleLibraryMovies: [Movie] {
        libraryMovies.filter { movie in
            guard youngAudienceFilter.isEnabled else { return true }
            guard let id = movie.tmdbId else { return false }
            return youngAudienceFilter.allows(MediaRef(id: id, mediaType: .movie))
        }
    }

    private var visibleLibraryShows: [TVShow] {
        libraryShows.filter { show in
            guard youngAudienceFilter.isEnabled else { return true }
            guard let id = show.tmdbId else { return false }
            return youngAudienceFilter.allows(MediaRef(id: id, mediaType: .tv))
        }
    }

    private var visibleLibraryEpisodes: [Episode] {
        guard youngAudienceFilter.isEnabled else { return libraryEpisodes }
        let visibleShowIDs = Set(visibleLibraryShows.map(\.id))
        return libraryEpisodes.filter { episode in
            episode.show.map { visibleShowIDs.contains($0.id) } == true
        }
    }

    private var audienceRefs: [MediaRef] {
        moviesModel.trending.map(\.ref)
            + libraryMovies.compactMap { movie in
                movie.tmdbId.map { MediaRef(id: $0, mediaType: .movie) }
            }
            + libraryShows.compactMap { show in
                show.tmdbId.map { MediaRef(id: $0, mediaType: .tv) }
            }
            + watchlistStore.items.filter(\.isInWatchlist).map(\.ref)
    }

    private var audienceVerificationKey: YoungAudienceVerificationKey {
        YoungAudienceVerificationKey(
            isEnabled: youngAudienceFilter.isEnabled,
            contextIdentifier: youngAudienceFilter.contextIdentifier,
            refs: audienceRefs
        )
    }

    private func syncAccountStateFromTMDB() async {
        async let userMediaSync: Void = userMediaStore.syncFromTMDB()
        async let watchlistSync: Void = watchlistStore.syncFromTMDB()
        _ = await (userMediaSync, watchlistSync)
    }

    @MainActor
    private func handle(_ route: AppRoute) async {
        switch route {
        case .search(let query):
            selectedTab = .search
            searchCoordinator.requestSearch(query)

        case .media(let ref):
            selectedTab = .movies
            externalDetail = RoutedMediaDetail(source: .tmdb(ref))

        case .localMovie(let id):
            guard let movie = libraryMovies.first(where: { $0.id == id }) else { return }
            selectedTab = .downloaded
            externalDetail = RoutedMediaDetail(source: .localMovie(movie))

        case .localShow(let id):
            guard let show = libraryShows.first(where: { $0.id == id }) else { return }
            selectedTab = .downloaded
            externalDetail = RoutedMediaDetail(source: .localShow(show))

        case .playMovie(let tmdbId):
            guard await isVisibleToSelectedAudience(
                MediaRef(id: tmdbId, mediaType: .movie)
            ) else { return }
            if let movie = libraryMovies.first(where: { $0.tmdbId == tmdbId }) {
                await playerSession.play(movie: movie)
            } else {
                selectedTab = .movies
                externalDetail = RoutedMediaDetail(
                    source: .tmdb(MediaRef(id: tmdbId, mediaType: .movie))
                )
            }

        case .playEpisode(let tmdbId):
            if let episode = libraryEpisodes.first(where: { $0.tmdbId == tmdbId }) {
                guard await isVisibleToSelectedAudience(episode) else { return }
                await playerSession.play(episode: episode)
            } else {
                selectedTab = .search
                searchCoordinator.requestSearch(String(localized: "Episode \(tmdbId)"))
            }

        case .playLocalMovie(let id):
            guard let movie = libraryMovies.first(where: { $0.id == id }),
                  await isVisibleToSelectedAudience(movie) else { return }
            await playerSession.play(movie: movie)

        case .playLocalEpisode(let id):
            guard let episode = libraryEpisodes.first(where: { $0.id == id }),
                  await isVisibleToSelectedAudience(episode) else { return }
            await playerSession.play(episode: episode)
        }
    }

    private func isVisibleToSelectedAudience(_ ref: MediaRef) async -> Bool {
        guard youngAudienceFilter.isEnabled else { return true }
        await youngAudienceFilter.verify([ref])
        guard !Task.isCancelled else { return false }
        return youngAudienceFilter.allows(ref)
    }

    private func isVisibleToSelectedAudience(_ movie: Movie) async -> Bool {
        guard youngAudienceFilter.isEnabled else { return true }
        guard let id = movie.tmdbId else { return false }
        return await isVisibleToSelectedAudience(MediaRef(id: id, mediaType: .movie))
    }

    private func isVisibleToSelectedAudience(_ show: TVShow) async -> Bool {
        guard youngAudienceFilter.isEnabled else { return true }
        guard let id = show.tmdbId else { return false }
        return await isVisibleToSelectedAudience(MediaRef(id: id, mediaType: .tv))
    }

    private func isVisibleToSelectedAudience(_ episode: Episode) async -> Bool {
        guard youngAudienceFilter.isEnabled else { return true }
        guard let show = episode.show else { return false }
        return await isVisibleToSelectedAudience(show)
    }

    /// A compact change token prevents unnecessary WidgetKit reloads while
    /// still reacting to shelf, library, metadata, and watch-progress changes.
    private var widgetSnapshotRevision: Int {
        var hasher = Hasher()
        hasher.combine(youngAudienceFilter.isEnabled)
        for item in youngAudienceFilter.visible(moviesModel.trending) {
            hasher.combine(item.id)
            hasher.combine(item.mediaType)
            hasher.combine(item.title)
            hasher.combine(item.posterPath)
        }
        for movie in visibleLibraryMovies {
            hasher.combine(movie.id)
            hasher.combine(movie.tmdbId)
            hasher.combine(movie.displayTitle)
            hasher.combine(movie.posterPath)
        }
        for show in visibleLibraryShows {
            hasher.combine(show.id)
            hasher.combine(show.tmdbId)
            hasher.combine(show.displayName)
            hasher.combine(show.posterPath)
            hasher.combine(show.episodes.count)
        }
        for episode in visibleLibraryEpisodes {
            hasher.combine(episode.id)
            hasher.combine(episode.tmdbId)
            hasher.combine(episode.displayTitle)
        }
        for progress in watchStore.inProgress {
            hasher.combine(progress.tmdbId)
            hasher.combine(progress.mediaType)
            hasher.combine(progress.position)
            hasher.combine(progress.lastWatchedAt)
        }
        return hasher.finalize()
    }
}

private struct RoutedMediaDetail: Identifiable {
    let id = UUID()
    let source: MediaDetailSource
}
