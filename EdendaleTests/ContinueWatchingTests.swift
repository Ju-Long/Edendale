//
//  ContinueWatchingTests.swift
//  EdendaleTests
//
//  Regression tests for BUG-02: Continue Watching surfaces next-up
//  episodes after a completed one without writing progress for the
//  unwatched successor. Tests cover deduplication, show membership,
//  cross-season advancement, and edge cases.
//

import Testing
import Foundation
@testable import Edendale

struct ContinueWatchingTests {

    // MARK: - Helpers

    private func makeShow(name: String = "Test Show", tmdbId: Int? = nil) -> TVShow {
        let show = TVShow(name: name)
        show.tmdbId = tmdbId
        return show
    }

    private func makeEpisode(
        season: Int,
        episode: Int,
        in show: TVShow,
        tmdbId: Int? = nil,
        title: String? = nil
    ) -> Episode {
        let ep = Episode(
            localTitle: title ?? "\(show.name) S\(season)E\(episode)",
            filePath: "/videos/\(show.name)/S\(season)E\(episode).mkv",
            seasonNumber: season,
            episodeNumber: episode
        )
        ep.tmdbId = tmdbId
        ep.show = show
        show.episodes.append(ep)
        return ep
    }

    private func completedProgress(
        tmdbId: Int,
        showTmdbId: Int,
        season: Int,
        episode: Int,
        at date: Date = Date()
    ) -> WatchProgress {
        WatchProgress(
            tmdbId: tmdbId,
            mediaType: .episode,
            position: 1.0,
            isCompleted: true,
            showTmdbId: showTmdbId,
            seasonNumber: season,
            episodeNumber: episode,
            lastWatchedAt: date
        )
    }

    private func inProgressProgress(
        tmdbId: Int,
        showTmdbId: Int,
        season: Int,
        episode: Int
    ) -> WatchProgress {
        WatchProgress(
            tmdbId: tmdbId,
            mediaType: .episode,
            position: 0.4,
            isCompleted: false,
            showTmdbId: showTmdbId,
            seasonNumber: season,
            episodeNumber: episode
        )
    }

    // MARK: - Next-up resolution

    @Test func completedEpisodeSurfacesNextUp() {
        let show = makeShow(name: "Anime", tmdbId: 1)
        _ = makeEpisode(season: 1, episode: 1, in: show, tmdbId: 101)
        let ep2 = makeEpisode(season: 1, episode: 2, in: show, tmdbId: 102)

        let progress = [completedProgress(tmdbId: 101, showTmdbId: 1, season: 1, episode: 1)]
        let result = PlayerLogic.nextUpEpisodes(
            allProgress: progress,
            inProgressShowTmdbIds: [],
            shows: [show]
        )

        #expect(result.count == 1)
        #expect(result.first?.episode.id == ep2.id)
    }

    @Test func crossSeasonNextUp() {
        let show = makeShow(name: "Drama", tmdbId: 2)
        _ = makeEpisode(season: 1, episode: 3, in: show, tmdbId: 201)
        let s2e1 = makeEpisode(season: 2, episode: 1, in: show, tmdbId: 202)

        let progress = [completedProgress(tmdbId: 201, showTmdbId: 2, season: 1, episode: 3)]
        let result = PlayerLogic.nextUpEpisodes(
            allProgress: progress,
            inProgressShowTmdbIds: [],
            shows: [show]
        )

        #expect(result.count == 1)
        #expect(result.first?.episode.id == s2e1.id)
    }

    @Test func lastEpisodeCompletedNoNextUp() {
        let show = makeShow(name: "Short", tmdbId: 3)
        _ = makeEpisode(season: 1, episode: 1, in: show, tmdbId: 301)

        let progress = [completedProgress(tmdbId: 301, showTmdbId: 3, season: 1, episode: 1)]
        let result = PlayerLogic.nextUpEpisodes(
            allProgress: progress,
            inProgressShowTmdbIds: [],
            shows: [show]
        )

        #expect(result.isEmpty)
    }

    @Test func inProgressEpisodeSuppressesNextUp() {
        let show = makeShow(name: "Sitcom", tmdbId: 4)
        _ = makeEpisode(season: 1, episode: 1, in: show, tmdbId: 401)
        _ = makeEpisode(season: 1, episode: 2, in: show, tmdbId: 402)
        _ = makeEpisode(season: 1, episode: 3, in: show, tmdbId: 403)

        let progress: [WatchProgress] = [
            completedProgress(tmdbId: 401, showTmdbId: 4, season: 1, episode: 1),
            inProgressProgress(tmdbId: 402, showTmdbId: 4, season: 1, episode: 2),
        ]
        let result = PlayerLogic.nextUpEpisodes(
            allProgress: progress,
            inProgressShowTmdbIds: [4],
            shows: [show]
        )

        #expect(result.isEmpty)
    }

    @Test func showWithoutTmdbIdExcluded() {
        let show = makeShow(name: "Unknown")
        _ = makeEpisode(season: 1, episode: 1, in: show, tmdbId: 501)
        _ = makeEpisode(season: 1, episode: 2, in: show, tmdbId: 502)

        let progress = [completedProgress(tmdbId: 501, showTmdbId: 5, season: 1, episode: 1)]
        let result = PlayerLogic.nextUpEpisodes(
            allProgress: progress,
            inProgressShowTmdbIds: [],
            shows: [show]
        )

        #expect(result.isEmpty)
    }

    @Test func multipleShowsIndependentNextUp() {
        let showA = makeShow(name: "Show A", tmdbId: 10)
        _ = makeEpisode(season: 1, episode: 1, in: showA, tmdbId: 1001)
        let a2 = makeEpisode(season: 1, episode: 2, in: showA, tmdbId: 1002)

        let showB = makeShow(name: "Show B", tmdbId: 20)
        _ = makeEpisode(season: 1, episode: 1, in: showB, tmdbId: 2001)
        let b2 = makeEpisode(season: 1, episode: 2, in: showB, tmdbId: 2002)

        let progress = [
            completedProgress(tmdbId: 1001, showTmdbId: 10, season: 1, episode: 1),
            completedProgress(tmdbId: 2001, showTmdbId: 20, season: 1, episode: 1),
        ]
        let result = PlayerLogic.nextUpEpisodes(
            allProgress: progress,
            inProgressShowTmdbIds: [],
            shows: [showA, showB]
        )

        #expect(result.count == 2)
        let ids = Set(result.map { $0.episode.id })
        #expect(ids.contains(a2.id))
        #expect(ids.contains(b2.id))
    }

    @Test func highestCompletedDeterminesNextUp() {
        let show = makeShow(name: "Long", tmdbId: 30)
        _ = makeEpisode(season: 1, episode: 1, in: show, tmdbId: 3001)
        _ = makeEpisode(season: 1, episode: 2, in: show, tmdbId: 3002)
        _ = makeEpisode(season: 1, episode: 3, in: show, tmdbId: 3003)
        let ep4 = makeEpisode(season: 1, episode: 4, in: show, tmdbId: 3004)

        let progress = [
            completedProgress(tmdbId: 3001, showTmdbId: 30, season: 1, episode: 1),
            completedProgress(tmdbId: 3002, showTmdbId: 30, season: 1, episode: 2),
            completedProgress(tmdbId: 3003, showTmdbId: 30, season: 1, episode: 3),
        ]
        let result = PlayerLogic.nextUpEpisodes(
            allProgress: progress,
            inProgressShowTmdbIds: [],
            shows: [show]
        )

        #expect(result.count == 1)
        #expect(result.first?.episode.id == ep4.id)
    }

    @Test func allEpisodesCompletedNoNextUp() {
        let show = makeShow(name: "Finished", tmdbId: 40)
        _ = makeEpisode(season: 1, episode: 1, in: show, tmdbId: 4001)
        _ = makeEpisode(season: 1, episode: 2, in: show, tmdbId: 4002)

        let progress = [
            completedProgress(tmdbId: 4001, showTmdbId: 40, season: 1, episode: 1),
            completedProgress(tmdbId: 4002, showTmdbId: 40, season: 1, episode: 2),
        ]
        let result = PlayerLogic.nextUpEpisodes(
            allProgress: progress,
            inProgressShowTmdbIds: [],
            shows: [show]
        )

        #expect(result.isEmpty)
    }

    @Test func deletedCompletedEpisodeStillFindsStoredSuccessor() {
        let show = makeShow(name: "Pruned", tmdbId: 50)
        let successor = makeEpisode(season: 1, episode: 2, in: show, tmdbId: 5002)

        // Progress references episode 1 which is no longer in the library.
        let progress = [completedProgress(tmdbId: 5001, showTmdbId: 50, season: 1, episode: 1)]
        let result = PlayerLogic.nextUpEpisodes(
            allProgress: progress,
            inProgressShowTmdbIds: [],
            shows: [show]
        )

        #expect(result.count == 1)
        #expect(result.first?.episode.id == successor.id)
    }

    @Test func nextUpPreservesLastWatchedDate() {
        let show = makeShow(name: "Dated", tmdbId: 60)
        _ = makeEpisode(season: 1, episode: 1, in: show, tmdbId: 6001)
        _ = makeEpisode(season: 1, episode: 2, in: show, tmdbId: 6002)

        let watchDate = Date(timeIntervalSince1970: 1_700_000_000)
        let progress = [completedProgress(
            tmdbId: 6001, showTmdbId: 60, season: 1, episode: 1, at: watchDate
        )]
        let result = PlayerLogic.nextUpEpisodes(
            allProgress: progress,
            inProgressShowTmdbIds: [],
            shows: [show]
        )

        #expect(result.first?.lastWatchedAt == watchDate)
    }

    @Test func movieProgressDoesNotProduceNextUp() {
        let show = makeShow(name: "Mixed", tmdbId: 70)
        _ = makeEpisode(season: 1, episode: 1, in: show, tmdbId: 7001)

        let movieProgress = WatchProgress(
            tmdbId: 999, mediaType: .movie, position: 1, isCompleted: true
        )
        let result = PlayerLogic.nextUpEpisodes(
            allProgress: [movieProgress],
            inProgressShowTmdbIds: [],
            shows: [show]
        )

        #expect(result.isEmpty)
    }

    @Test func specialCompletedAdvancesToSeason1() {
        let show = makeShow(name: "Special", tmdbId: 80)
        _ = makeEpisode(season: 0, episode: 1, in: show, tmdbId: 8001, title: "OVA")
        let s1e1 = makeEpisode(season: 1, episode: 1, in: show, tmdbId: 8002)

        let progress = [completedProgress(tmdbId: 8001, showTmdbId: 80, season: 0, episode: 1)]
        let result = PlayerLogic.nextUpEpisodes(
            allProgress: progress,
            inProgressShowTmdbIds: [],
            shows: [show]
        )

        #expect(result.count == 1)
        #expect(result.first?.episode.id == s1e1.id)
    }
    @Test func duplicateShowRecordsProduceOneEarliestSuccessor() {
        let early = makeShow(name: "Folder B", tmdbId: 90)
        let next = makeEpisode(season: 2, episode: 1, in: early, tmdbId: 9002)
        let later = makeShow(name: "Folder A", tmdbId: 90)
        _ = makeEpisode(season: 2, episode: 3, in: later, tmdbId: 9003)
        let progress = [completedProgress(tmdbId: 9001, showTmdbId: 90, season: 1, episode: 10)]
        let result = PlayerLogic.nextUpEpisodes(allProgress: progress, inProgressShowTmdbIds: [], shows: [later, early])
        #expect(result.count == 1)
        #expect(result.first?.episode.id == next.id)
    }

    @Test func progressForShowsOutsideVisibleLibraryProducesNoCard() {
        let visible = makeShow(name: "Visible", tmdbId: 91)
        _ = makeEpisode(season: 1, episode: 2, in: visible)
        let progress = [completedProgress(tmdbId: 9201, showTmdbId: 92, season: 1, episode: 1)]
        #expect(PlayerLogic.nextUpEpisodes(allProgress: progress, inProgressShowTmdbIds: [], shows: [visible]).isEmpty)
    }

}
