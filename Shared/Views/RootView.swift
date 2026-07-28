//
//  RootView.swift
//  Edendale
//
//  Adaptive shell: sidebar on iPadOS/macOS (Settings pinned at the
//  bottom), tab bar on iPhone (Settings in each page's toolbar),
//  ornament tabs on visionOS (Settings as its own tab). Search uses
//  the OS 26 search tab role.
//

import SwiftUI
import SwiftData

enum RootTab: Hashable {
    case movies, downloaded, settings, search
}

struct RootView: View {
    @Environment(TMDBAccountStore.self) private var tmdbAccount
    @Environment(UserMediaStore.self) private var userMediaStore
    @Environment(WatchProgressStore.self) private var watchStore
    @Environment(PlayerSession.self) private var playerSession
    @Environment(AppRouter.self) private var appRouter

    @Query private var libraryMovies: [Movie]
    @Query private var libraryShows: [TVShow]
    @Query private var libraryEpisodes: [Episode]

    /// Session cache for the Movies & Shows tab — owned here so tab
    /// switches never refetch.
    @State private var moviesModel = MoviesShowsModel()
    /// Routes cast taps in a detail page over to the Search tab.
    @State private var searchCoordinator = SearchCoordinator()
    @State private var selectedTab: RootTab = .movies
    @State private var showSettings = false
    @State private var externalDetail: RoutedMediaDetail?

    var body: some View {
        TabView(selection: $selectedTab) {
            Tab("Movies & Shows", image: "clapperboard", value: RootTab.movies) {
                MoviesShowsView()
            }

            Tab("Downloaded", image: "folder-closed", value: RootTab.downloaded) {
                DownloadedView()
            }

            #if os(visionOS) || os(tvOS)
            Tab("Settings", image: "gear-complex", value: RootTab.settings) {
                SettingsView()
            }
            #endif

            Tab("Search", image: "magnifying-glass-play", value: RootTab.search, role: .search) {
                SearchView()
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        #if os(iOS) || os(macOS)
        .tabViewSidebarBottomBar {
            Button {
                showSettings = true
            } label: {
                Label("Settings", image: "gear-complex")
            }
            .archiveButtonStyle(.ghost)
        }
        #endif
        .sheet(isPresented: $showSettings) {
            SettingsView()
        }
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
        .task(id: appRouter.request?.id) {
            guard let request = appRouter.request else { return }
            await handle(request.route)
            appRouter.consume(request.id)
        }
        .task(id: widgetSnapshotRevision) {
            WidgetSnapshotStore.publish(
                trending: moviesModel.trending,
                movies: libraryMovies,
                shows: libraryShows,
                episodes: libraryEpisodes,
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
                await userMediaStore.syncFromTMDB()
            }
        }
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
                await playerSession.play(episode: episode)
            } else {
                selectedTab = .search
                searchCoordinator.requestSearch(String(localized: "Episode \(tmdbId)"))
            }

        case .playLocalMovie(let id):
            guard let movie = libraryMovies.first(where: { $0.id == id }) else { return }
            await playerSession.play(movie: movie)

        case .playLocalEpisode(let id):
            guard let episode = libraryEpisodes.first(where: { $0.id == id }) else { return }
            await playerSession.play(episode: episode)
        }
    }

    /// A compact change token prevents unnecessary WidgetKit reloads while
    /// still reacting to shelf, library, metadata, and watch-progress changes.
    private var widgetSnapshotRevision: Int {
        var hasher = Hasher()
        for item in moviesModel.trending {
            hasher.combine(item.id)
            hasher.combine(item.mediaType)
            hasher.combine(item.title)
            hasher.combine(item.posterPath)
        }
        for movie in libraryMovies {
            hasher.combine(movie.id)
            hasher.combine(movie.tmdbId)
            hasher.combine(movie.displayTitle)
            hasher.combine(movie.posterPath)
        }
        for show in libraryShows {
            hasher.combine(show.id)
            hasher.combine(show.tmdbId)
            hasher.combine(show.displayName)
            hasher.combine(show.posterPath)
            hasher.combine(show.episodes.count)
        }
        for episode in libraryEpisodes {
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
