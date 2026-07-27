//
//  MoviesShowsModel.swift
//  Edendale
//
//  State for the Movies & Shows tab. Owned by RootView so shelves stay
//  cached for the session — switching tabs never refetches.
//

import Foundation
import SwiftUI

@Observable
final class MoviesShowsModel {

    enum Phase {
        case idle
        case loading
        case loaded
        case missingKey
        case failed(String)
    }

    enum Collection: Hashable {
        case all
        case movies
        case shows
        case genre(TMDBGenre)

        var title: String {
            switch self {
            case .all: String(localized: "All Archives")
            case .movies: String(localized: "Feature Films")
            case .shows: String(localized: "Series")
            case .genre(let g): g.name
            }
        }
    }

    /// One scene of the rotating top hero: either the most recent Continue
    /// Watching item or a trending spotlight.
    struct Hero {
        let detail: MediaDetail
        let progress: WatchProgress?

        var isContinueWatching: Bool { progress != nil }

        /// "01:14:32 LEFT" — only when runtime and position are known.
        var remainingText: String? {
            guard let progress, let runtime = detail.runtimeMinutes, runtime > 0 else { return nil }
            let remaining = Int(max(0, Double(runtime) * 60 * (1 - progress.position)))
            let h = remaining / 3600, m = (remaining % 3600) / 60, s = remaining % 60
            let timestamp = String(format: "%02d:%02d:%02d", h, m, s)
            return String(localized: "\(timestamp) left")
        }
    }

    private let tmdb = TMDBService.shared

    var phase: Phase = .idle
    /// Scenes the hero rotates through; the view owns the rotation index.
    var heroScenes: [Hero] = []
    var trending: [TMDBMediaItem] = []
    var popularMovies: [TMDBMediaItem] = []
    var popularShows: [TMDBMediaItem] = []
    var topRated: [TMDBMediaItem] = []
    var genres: [TMDBGenre] = []
    var recommendedMovies: [TMDBMediaItem] = []
    var recommendedShows: [TMDBMediaItem] = []

    var selectedCollection: Collection = .all
    var collectionItems: [TMDBMediaItem] = []
    var isLoadingCollection = false

    /// Chips shown in the Curated Collections section.
    var collections: [Collection] {
        [.all, .movies, .shows] + genres.prefix(6).map { .genre($0) }
    }

    func load(watchStore: WatchProgressStore) async {
        guard tmdb.isConfigured else {
            phase = .missingKey
            return
        }
        if case .loaded = phase { return }
        phase = .loading

        do {
            async let trending = tmdb.trending()
            async let popularMovies = tmdb.popular(.movie)
            async let popularShows = tmdb.popular(.tv)
            async let topRated = tmdb.topRated(.movie)
            async let genres = tmdb.movieGenres()

            self.trending = try await trending
            self.popularMovies = try await popularMovies
            self.popularShows = try await popularShows
            self.topRated = try await topRated
            self.genres = try await genres
            
            if TMDBUserSession.current != nil {
                let account = TMDBAccountClient()
                async let recM = account.recommendations(.movie)
                async let recS = account.recommendations(.tv)
                self.recommendedMovies = (try? await recM) ?? []
                self.recommendedShows = (try? await recS) ?? []
            } else {
                self.recommendedMovies = []
                self.recommendedShows = []
            }
            
            phase = .loaded
        } catch {
            phase = .failed(error.localizedDescription)
            return
        }

        await loadHeroScenes(watchStore: watchStore)
        await loadCollection(selectedCollection)
    }

    func retry(watchStore: WatchProgressStore) async {
        phase = .idle
        await load(watchStore: watchStore)
    }

    func loadCollection(_ collection: Collection) async {
        selectedCollection = collection
        isLoadingCollection = true
        defer { isLoadingCollection = false }

        collectionItems = (try? await fetchCollection(collection)) ?? []
    }

    // MARK: - Private

    private func fetchCollection(_ collection: Collection) async throws -> [TMDBMediaItem] {
        switch collection {
        case .all: try await tmdb.trending(window: "week")
        case .movies: try await tmdb.discover(.movie)
        case .shows: try await tmdb.discover(.tv)
        case .genre(let g): try await tmdb.discover(.movie, genreId: g.id)
        }
    }

    private func loadHeroScenes(watchStore: WatchProgressStore) async {
        var scenes: [Hero] = []

        // Continue Watching leads the rotation when available.
        for progress in watchStore.inProgress.prefix(3) {
            guard let ref = heroRef(for: progress),
                  let detail = try? await tmdb.mediaDetail(ref)
            else { continue }
            scenes.append(Hero(detail: detail, progress: progress))
            break
        }

        // Then spotlight details for the recommended or trending titles, fetched
        // concurrently but kept in order — the hero pager lets the
        // user swipe through all of them.
        let continueRef = scenes.first?.detail.ref
        
        let hasRecommendations = !recommendedMovies.isEmpty || !recommendedShows.isEmpty
        let spotlightItems: [TMDBMediaItem]
        if TMDBUserSession.current != nil && hasRecommendations {
            // Alternate movies and shows for variety
            let maxCount = max(recommendedMovies.count, recommendedShows.count)
            var mixed: [TMDBMediaItem] = []
            for i in 0..<maxCount {
                if i < recommendedMovies.count { mixed.append(recommendedMovies[i]) }
                if i < recommendedShows.count { mixed.append(recommendedShows[i]) }
            }
            spotlightItems = Array(mixed.prefix(20))
        } else {
            spotlightItems = trending
        }
        
        let spotlights = spotlightItems.map(\.ref).filter { $0 != continueRef }
        let details = await withTaskGroup(of: (Int, MediaDetail?).self) { group in
            for (index, ref) in spotlights.enumerated() {
                group.addTask { [tmdb] in (index, try? await tmdb.mediaDetail(ref)) }
            }
            var fetched: [(Int, MediaDetail?)] = []
            for await result in group { fetched.append(result) }
            return fetched.sorted { $0.0 < $1.0 }.compactMap(\.1)
        }
        scenes += details.map { Hero(detail: $0, progress: nil) }

        heroScenes = scenes
    }

    // MARK: - Trailers

    private var trailerCache: [MediaRef: TMDBVideo?] = [:]

    /// Best YouTube trailer for a scene, cached per title for the session.
    /// Transient network failures are not cached so a later tap can retry.
    func trailer(for ref: MediaRef) async -> TMDBVideo? {
        if let cached = trailerCache[ref] { return cached }
        do {
            let best = try await tmdb.bestTrailer(ref)
            trailerCache[ref] = best
            return best
        } catch {
            return nil
        }
    }

    private func heroRef(for progress: WatchProgress) -> MediaRef? {
        switch progress.mediaType {
        case .movie:
            MediaRef(id: progress.tmdbId, mediaType: .movie)
        case .episode:
            progress.showTmdbId.map { MediaRef(id: $0, mediaType: .tv) }
        }
    }
}
