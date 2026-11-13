//
//  HeroResumeTests.swift
//  EdendaleTests
//
//  The Movies & Shows hero's Resume Playback: it plays what Continue
//  Watching would for that title (the movie, or the show's episode watched
//  last) instead of opening the detail page, and its Continue Watching
//  scene follows the store as progress syncs in. Also the player's own
//  time and battery on an iPhone held sideways.
//

import Foundation
import Testing
@testable import Edendale

@MainActor
struct HeroResumeTests {

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }()

    private func hero(movie id: Int, progress: WatchProgress? = nil) throws -> MoviesShowsModel.Hero {
        let json = #"{"id": \#(id), "title": "Title \#(id)"}"#
        let detail = MediaDetail(movie: try Self.decoder.decode(TMDBMovieDetail.self, from: Data(json.utf8)))
        return MoviesShowsModel.Hero(detail: detail, progress: progress)
    }

    // MARK: - Resume Playback

    @Test func resumePlaysTheShowsEpisodeWatchedLast() throws {
        let store = WatchProgressStore()
        let showID = Int.random(in: 10_000_000...90_000_000)
        let otherShowID = showID + 1
        let show = TVShow(name: "Resume Show")
        show.tmdbId = showID
        let other = TVShow(name: "Other Show")
        other.tmdbId = otherShowID
        let episodes = (1...3).map { number in
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
        let otherEpisode = Episode(localTitle: "Other", filePath: "/videos/\(otherShowID)/S01E01.mkv", seasonNumber: 1, episodeNumber: 1)
        otherEpisode.tmdbId = otherShowID * 10 + 1
        otherEpisode.show = other
        other.episodes.append(otherEpisode)

        // Episode 3 was started first; episode 2 was the one watched last.
        // Another show was watched more recently still.
        let records = [
            WatchProgress(tmdbId: showID * 10 + 3, mediaType: .episode, position: 0.6, showTmdbId: showID,
                          seasonNumber: 1, episodeNumber: 3, lastWatchedAt: Date(timeIntervalSinceNow: -600)),
            WatchProgress(tmdbId: showID * 10 + 2, mediaType: .episode, position: 0.3, showTmdbId: showID,
                          seasonNumber: 1, episodeNumber: 2, lastWatchedAt: Date(timeIntervalSinceNow: -60)),
            WatchProgress(tmdbId: otherShowID * 10 + 1, mediaType: .episode, position: 0.5, showTmdbId: otherShowID,
                          seasonNumber: 1, episodeNumber: 1, lastWatchedAt: Date()),
        ]
        records.forEach(store.update)
        defer { records.forEach { store.remove(tmdbId: $0.tmdbId, mediaType: .episode) } }

        let item = try #require(ResumeItem.latest(
            for: MediaRef(id: showID, mediaType: .tv),
            watchStore: store,
            movies: [],
            shows: [other, show]
        ))
        guard case .episode(let episode) = item.payload else {
            Issue.record("Expected an episode")
            return
        }
        #expect(episode.id == episodes[1].id)
        #expect(!item.isNextUp)
    }

    @Test func resumeMovesOnOnceTheLastEpisodeWatchedIsFinished() throws {
        let store = WatchProgressStore()
        let showID = Int.random(in: 10_000_000...90_000_000)
        let show = TVShow(name: "Finished Show")
        show.tmdbId = showID
        let episodes = (1...2).map { number in
            let episode = Episode(
                localTitle: "Episode \(number)",
                filePath: "/videos/\(showID)/S02E0\(number).mkv",
                seasonNumber: 2,
                episodeNumber: number
            )
            episode.tmdbId = showID * 10 + number
            episode.show = show
            show.episodes.append(episode)
            return episode
        }
        store.update(WatchProgress(tmdbId: showID * 10 + 1, mediaType: .episode, position: 1, isCompleted: true,
                                   showTmdbId: showID, seasonNumber: 2, episodeNumber: 1))
        defer { store.remove(tmdbId: showID * 10 + 1, mediaType: .episode) }

        let item = try #require(ResumeItem.latest(
            for: MediaRef(id: showID, mediaType: .tv), watchStore: store, movies: [], shows: [show]
        ))
        guard case .episode(let episode) = item.payload else {
            Issue.record("Expected the next episode")
            return
        }
        #expect(episode.id == episodes[1].id)
        #expect(item.isNextUp)
    }

    @Test func resumeFindsOnlyTheHerosMovieInTheLibrary() {
        let store = WatchProgressStore()
        let movieID = Int.random(in: 10_000_000...90_000_000)
        let movie = Movie(localTitle: "Resume Movie", filePath: "/videos/\(movieID).mkv")
        movie.tmdbId = movieID
        let other = Movie(localTitle: "Other Movie", filePath: "/videos/\(movieID + 1).mkv")
        other.tmdbId = movieID + 1
        store.update(WatchProgress(tmdbId: movieID, mediaType: .movie, position: 0.4, lastWatchedAt: Date(timeIntervalSinceNow: -60)))
        store.update(WatchProgress(tmdbId: movieID + 1, mediaType: .movie, position: 0.7))
        defer {
            store.remove(tmdbId: movieID, mediaType: .movie)
            store.remove(tmdbId: movieID + 1, mediaType: .movie)
        }

        let ref = MediaRef(id: movieID, mediaType: .movie)
        let item = ResumeItem.latest(for: ref, watchStore: store, movies: [other, movie], shows: [])
        guard case .movie(let resumed) = item?.payload else {
            Issue.record("Expected the movie")
            return
        }
        #expect(resumed.id == movie.id)
        // Without a copy here, the hero falls back to the detail page.
        #expect(ResumeItem.latest(for: ref, watchStore: store, movies: [other], shows: []) == nil)
        // A show with the same ID is a different title.
        #expect(ResumeItem.latest(for: MediaRef(id: movieID, mediaType: .tv), watchStore: store, movies: [movie], shows: []) == nil)
    }

    // MARK: - Continue Watching scene

    @Test func theContinueWatchingSceneIsReplacedAndNotRepeated() throws {
        let spotlightA = try hero(movie: 1)
        let spotlightB = try hero(movie: 2)
        let oldLead = try hero(movie: 3, progress: WatchProgress(tmdbId: 3, mediaType: .movie, position: 0.2))
        let newLead = try hero(movie: 2, progress: WatchProgress(tmdbId: 2, mediaType: .movie, position: 0.5))

        let scenes = MoviesShowsModel.scenes([oldLead, spotlightA, spotlightB], leadingWith: newLead)
        #expect(scenes.map(\.detail.ref.id) == [2, 1])
        #expect(scenes.first?.isContinueWatching == true)
        #expect(scenes.first?.progress?.position == 0.5)

        // Nothing left in progress: only the spotlights stay.
        let none = MoviesShowsModel.scenes([oldLead, spotlightA, spotlightB], leadingWith: nil)
        #expect(none.map(\.detail.ref.id) == [1, 2])
        #expect(none.allSatisfy { !$0.isContinueWatching })
    }

    // MARK: - Player time and battery

    @Test func timeAndBatteryShowOnlyWhereTheSystemDrawsNoStatusBar() {
        // A Face ID iPhone on its side: no status bar and no inset for one.
        #expect(PlayerDeviceStatus.isShown(compactHeight: true, topInset: 0))
        // A Home-button iPhone on its side keeps the system's status bar.
        #expect(!PlayerDeviceStatus.isShown(compactHeight: true, topInset: 20))
        // Portrait, and iPad, have the system's status bar.
        #expect(!PlayerDeviceStatus.isShown(compactHeight: false, topInset: 0))
        #expect(!PlayerDeviceStatus.isShown(compactHeight: false, topInset: 59))
    }

    @Test func batteryGlyphFollowsTheLevel() {
        #expect(PlayerDeviceStatus.batterySymbol(level: -1, isCharging: false) == nil)
        #expect(PlayerDeviceStatus.batterySymbol(level: 0.05, isCharging: false) == "battery.0percent")
        #expect(PlayerDeviceStatus.batterySymbol(level: 0.3, isCharging: false) == "battery.25percent")
        #expect(PlayerDeviceStatus.batterySymbol(level: 0.5, isCharging: false) == "battery.50percent")
        #expect(PlayerDeviceStatus.batterySymbol(level: 0.8, isCharging: false) == "battery.75percent")
        #expect(PlayerDeviceStatus.batterySymbol(level: 1, isCharging: false) == "battery.100percent")
        #expect(PlayerDeviceStatus.batterySymbol(level: 0.4, isCharging: true) == "battery.100percent.bolt")
    }
}
