//
//  LibrarySectionsTests.swift
//  EdendaleTests
//
//  The Watchlist and Downloaded sections the macOS sidebar opens as pages
//  of their own, the Continue Watching list they share with the Downloaded
//  page, the sidebar's fallback when a section empties, the heading
//  scrubber's scroll geometry, and side-panel docking.
//

import Foundation
import SwiftData
import SwiftUI
import Testing
@testable import Edendale

@MainActor
struct LibrarySectionsTests {

    // MARK: - Sections

    @Test func downloadedSectionsListOnlyWhatHasContent() {
        let movie = Movie(localTitle: "Heat", filePath: "/videos/Heat.mkv")
        let show = TVShow(name: "Severance")

        #expect(DownloadedSection.available(hasResumeItems: false, movies: [], shows: []).isEmpty)
        #expect(
            DownloadedSection.available(hasResumeItems: true, movies: [movie], shows: [show])
                == [.continueWatching, .movies, .shows]
        )
        #expect(DownloadedSection.available(hasResumeItems: false, movies: [], shows: [show]) == [.shows])
    }

    @Test func watchlistSectionsFollowMediaTypes() {
        let movie = WatchlistItem(ref: MediaRef(id: 27205, mediaType: .movie))
        let show = WatchlistItem(ref: MediaRef(id: 1396, mediaType: .tv))

        #expect(WatchlistSection.available(in: []).isEmpty)
        #expect(WatchlistSection.available(in: [movie]) == [.movies])
        #expect(WatchlistSection.available(in: [show, movie]) == [.movies, .shows])
    }

    // MARK: - Continue Watching

    @Test func continueWatchingLimitCapsOnlyTheShelf() {
        let store = WatchProgressStore()
        let base = Int.random(in: 10_000_000...90_000_000)
        let movies = (0..<3).map { index in
            let movie = Movie(localTitle: "Movie \(index)", filePath: "/videos/\(base)-\(index).mkv")
            movie.tmdbId = base + index
            return movie
        }
        for index in movies.indices {
            store.update(WatchProgress(
                tmdbId: base + index,
                mediaType: .movie,
                position: 0.5,
                lastWatchedAt: Date(timeIntervalSinceNow: -Double(index) * 60)
            ))
        }
        defer {
            for index in movies.indices { store.remove(tmdbId: base + index, mediaType: .movie) }
        }

        let all = ResumeItem.items(watchStore: store, movies: movies, shows: [], limit: nil)
        #expect(all.map(\.title) == ["Movie 0", "Movie 1", "Movie 2"])
        #expect(ResumeItem.items(watchStore: store, movies: movies, shows: [], limit: 2).count == 2)
        // Titles the audience filter hides are never offered.
        #expect(ResumeItem.items(watchStore: store, movies: [], shows: [], limit: nil).isEmpty)
    }

    @Test func continueWatchingSuggestsTheEpisodeAfterTheLastCompletedOne() throws {
        let store = WatchProgressStore()
        let showID = Int.random(in: 10_000_000...90_000_000)
        let show = TVShow(name: "Next Up")
        show.tmdbId = showID
        let episodes = (1...2).map { number in
            let episode = Episode(
                localTitle: "Episode \(number)",
                filePath: "/videos/\(showID)/S01E0\(number).mkv",
                seasonNumber: 1,
                episodeNumber: number
            )
            episode.tmdbId = showID * 10 + number
            episode.show = show
            show.episodes.append(episode)
            return episode
        }
        store.update(WatchProgress(
            tmdbId: showID * 10 + 1,
            mediaType: .episode,
            position: 1,
            isCompleted: true,
            showTmdbId: showID,
            seasonNumber: 1,
            episodeNumber: 1
        ))
        defer { store.remove(tmdbId: showID * 10 + 1, mediaType: .episode) }

        let items = ResumeItem.items(watchStore: store, movies: [], shows: [show], limit: nil)
        let item = try #require(items.first)
        #expect(items.count == 1)
        #expect(item.isNextUp)
        guard case .episode(let episode) = item.payload else {
            Issue.record("Expected the next episode")
            return
        }
        #expect(episode.id == episodes[1].id)
    }

    // MARK: - Heading scrubber

    @Test func shelfMetricsMirrorScrollGeometry() {
        let metrics = ShelfScrollMetrics(ScrollGeometry(
            contentOffset: CGPoint(x: 250, y: 0),
            contentSize: CGSize(width: 2000, height: 200),
            contentInsets: EdgeInsets(),
            containerSize: CGSize(width: 1000, height: 200)
        ))
        #expect(metrics.range == 1000)
        #expect(metrics.progress == 0.25)
        #expect(metrics.visibleFraction == 0.5)

        var position = ScrollPosition()
        let scrubber = metrics.scrubber(scrolling: Binding(get: { position }, set: { position = $0 }))
        #expect(scrubber.isScrollable)
        scrubber.onScrub(0.5)
        #expect(position.x == 500)
    }

    @Test func fittingShelfHasNothingToScrub() {
        let metrics = ShelfScrollMetrics(ScrollGeometry(
            contentOffset: .zero,
            contentSize: CGSize(width: 800, height: 200),
            contentInsets: EdgeInsets(),
            containerSize: CGSize(width: 1000, height: 200)
        ))
        #expect(metrics.progress == 0)
        #expect(!metrics.scrubber(scrolling: .constant(ScrollPosition())).isScrollable)
    }

    // MARK: - Side panels

    @Test func sidePanelsCoverTheVideoExceptWhenDockedOnMac() throws {
        let schema = Schema([VideoFolder.self, Movie.self, TVShow.self, Episode.self])
        let container = try ModelContainer(
            for: schema,
            configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)]
        )
        let name = "LibrarySectionsTests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: name))
        defer { defaults.removePersistentDomain(forName: name) }
        let watchStore = WatchProgressStore()
        let session = PlayerSession(
            library: LibraryController(modelContext: container.mainContext),
            watchStore: watchStore,
            segmentSkipping: PlayerSegmentController(defaults: defaults, lookup: { _ in [] }),
            defaults: defaults
        )
        let chrome = PlayerChromeModel(session: session, watchStore: watchStore, defaults: defaults)

        #expect(!chrome.panelCoversVideo)
        chrome.openPanel(.playlist)
        #if os(macOS)
        #expect(!chrome.panelCoversVideo)
        #else
        #expect(chrome.panelCoversVideo)
        #endif
        chrome.closePanel()
        #expect(!chrome.panelCoversVideo)
    }

    // MARK: - macOS sidebar

    #if os(macOS)
    @Test func sidebarRowsMapToRootTabs() {
        for tab in [RootTab.movies, .watchlist, .downloaded, .search, .settings] {
            #expect(SidebarItem(tab).tab == tab)
        }
        // A route that picks a tab opens its whole page.
        #expect(SidebarItem(.downloaded) == .downloaded(nil))
        #expect(SidebarItem.downloaded(.movies).tab == .downloaded)
        #expect(SidebarItem.watchlist(.shows).tab == .watchlist)
    }

    @Test func emptiedSectionRowFallsBackToItsPage() {
        let continueWatching = SidebarItem.downloaded(.continueWatching)
        #expect(
            continueWatching.resolved(watchlistSections: [], downloadedSections: [.movies])
                == .downloaded(nil)
        )
        #expect(
            continueWatching.resolved(watchlistSections: [], downloadedSections: [.continueWatching])
                == continueWatching
        )
        #expect(
            SidebarItem.watchlist(.shows).resolved(watchlistSections: [.movies], downloadedSections: [])
                == .watchlist(nil)
        )
        #expect(SidebarItem.search.resolved(watchlistSections: [], downloadedSections: []) == .search)
        #expect(
            SidebarItem.downloaded(nil).resolved(watchlistSections: [], downloadedSections: [])
                == .downloaded(nil)
        )
    }
    #endif
}
