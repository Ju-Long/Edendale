//
//  EpisodeProgressionTests.swift
//  EdendaleTests
//
//  Regression tests for BUG-01: automatic episode progression after a
//  natural playback end, including cross-season ordering, season-0
//  specials, duplicate-encode handling, and edge cases.
//

import Testing
import Foundation
@testable import Edendale

struct EpisodeProgressionTests {

    // MARK: - Helpers

    private func makeShow(name: String = "Test Show") -> TVShow {
        TVShow(name: name)
    }

    private func makeEpisode(
        season: Int,
        episode: Int,
        in show: TVShow,
        title: String? = nil
    ) -> Episode {
        let ep = Episode(
            localTitle: title ?? "\(show.name) S\(season)E\(episode)",
            filePath: "/videos/\(show.name)/S\(season)E\(episode).mkv",
            seasonNumber: season,
            episodeNumber: episode
        )
        ep.show = show
        show.episodes.append(ep)
        return ep
    }

    // MARK: - Next episode within a season

    @Test func nextEpisodeWithinSameSeason() {
        let show = makeShow()
        let ep1 = makeEpisode(season: 1, episode: 1, in: show)
        let ep2 = makeEpisode(season: 1, episode: 2, in: show)
        _ = makeEpisode(season: 1, episode: 3, in: show)

        let next = PlayerLogic.nextEpisode(after: ep1, in: show)
        #expect(next?.id == ep2.id)
    }

    // MARK: - Cross-season progression

    @Test func nextEpisodeCrossesSeasonBoundary() {
        let show = makeShow()
        let s1e3 = makeEpisode(season: 1, episode: 3, in: show)
        let s2e1 = makeEpisode(season: 2, episode: 1, in: show)
        _ = makeEpisode(season: 1, episode: 1, in: show)

        let next = PlayerLogic.nextEpisode(after: s1e3, in: show)
        #expect(next?.id == s2e1.id)
    }

    // MARK: - Last episode returns nil

    @Test func lastEpisodeReturnsNil() {
        let show = makeShow()
        _ = makeEpisode(season: 1, episode: 1, in: show)
        let last = makeEpisode(season: 1, episode: 2, in: show)

        let next = PlayerLogic.nextEpisode(after: last, in: show)
        #expect(next == nil)
    }

    // MARK: - Single episode

    @Test func singleEpisodeReturnsNil() {
        let show = makeShow()
        let only = makeEpisode(season: 1, episode: 1, in: show)

        #expect(PlayerLogic.nextEpisode(after: only, in: show) == nil)
    }

    // MARK: - Ordering ignores insertion order

    @Test func orderingBySeasonAndEpisodeNotInsertion() {
        let show = makeShow()
        let s2e1 = makeEpisode(season: 2, episode: 1, in: show)
        let s1e1 = makeEpisode(season: 1, episode: 1, in: show)
        let s1e2 = makeEpisode(season: 1, episode: 2, in: show)

        #expect(PlayerLogic.nextEpisode(after: s1e1, in: show)?.id == s1e2.id)
        #expect(PlayerLogic.nextEpisode(after: s1e2, in: show)?.id == s2e1.id)
        #expect(PlayerLogic.nextEpisode(after: s2e1, in: show) == nil)
    }

    // MARK: - Episode not in show

    @Test func unknownEpisodeReturnsNil() {
        let show = makeShow()
        _ = makeEpisode(season: 1, episode: 1, in: show)

        let orphan = Episode(
            localTitle: "Orphan",
            filePath: "/orphan.mkv",
            seasonNumber: 1,
            episodeNumber: 99
        )

        #expect(PlayerLogic.nextEpisode(after: orphan, in: show) == nil)
    }

    // MARK: - Multi-season with gaps

    @Test func progressionSkipsGapsInSeasonNumbers() {
        let show = makeShow()
        let s1e1 = makeEpisode(season: 1, episode: 1, in: show)
        let s3e1 = makeEpisode(season: 3, episode: 1, in: show)

        #expect(PlayerLogic.nextEpisode(after: s1e1, in: show)?.id == s3e1.id)
    }

    // MARK: - Season-0 specials

    @Test func mainSeasonNeverRegressesToSeasonZero() {
        let show = makeShow()
        let s1e2 = makeEpisode(season: 1, episode: 2, in: show)
        _ = makeEpisode(season: 0, episode: 1, in: show, title: "Behind the Scenes")
        _ = makeEpisode(season: 0, episode: 2, in: show, title: "Bloopers")

        // Season 0 is numerically below season 1 so tuple ordering
        // naturally excludes it when advancing from a main season.
        #expect(PlayerLogic.nextEpisode(after: s1e2, in: show) == nil)
    }

    @Test func specialAdvancesToNextStored() {
        let show = makeShow()
        let special = makeEpisode(season: 0, episode: 1, in: show, title: "Special")
        let s1e1 = makeEpisode(season: 1, episode: 1, in: show)

        // Specials advance into season 1 by ascending tuple order.
        #expect(PlayerLogic.nextEpisode(after: special, in: show)?.id == s1e1.id)
    }

    @Test func specialsAdvanceAmongThemselves() {
        let show = makeShow()
        let s0e1 = makeEpisode(season: 0, episode: 1, in: show, title: "OVA 1")
        let s0e2 = makeEpisode(season: 0, episode: 2, in: show, title: "OVA 2")
        _ = makeEpisode(season: 1, episode: 1, in: show)

        #expect(PlayerLogic.nextEpisode(after: s0e1, in: show)?.id == s0e2.id)
    }

    @Test func mainSeasonSkipsOverSeasonZero() {
        let show = makeShow()
        let s1e1 = makeEpisode(season: 1, episode: 1, in: show)
        _ = makeEpisode(season: 0, episode: 1, in: show, title: "OVA")
        let s1e2 = makeEpisode(season: 1, episode: 2, in: show)

        #expect(PlayerLogic.nextEpisode(after: s1e1, in: show)?.id == s1e2.id)
    }

    // MARK: - Duplicate encodes of the same episode

    @Test func duplicateEncodeDoesNotRepeatSameEpisode() {
        let show = makeShow()
        _ = makeEpisode(season: 1, episode: 1, in: show, title: "S1E1 720p")
        let dup = makeEpisode(season: 1, episode: 1, in: show, title: "S1E1 1080p")
        let s1e2 = makeEpisode(season: 1, episode: 2, in: show)

        let next = PlayerLogic.nextEpisode(after: dup, in: show)
        #expect(next?.id == s1e2.id)
    }

    @Test func allDuplicatesOfLastEpisodeReturnNil() {
        let show = makeShow()
        _ = makeEpisode(season: 2, episode: 5, in: show, title: "Finale 720p")
        let dup = makeEpisode(season: 2, episode: 5, in: show, title: "Finale 1080p")
        _ = makeEpisode(season: 1, episode: 1, in: show)

        #expect(PlayerLogic.nextEpisode(after: dup, in: show) == nil)
    }

    // MARK: - Does not go backwards

    @Test func neverAdvancesBackwards() {
        let show = makeShow()
        _ = makeEpisode(season: 1, episode: 1, in: show)
        let s2e3 = makeEpisode(season: 2, episode: 3, in: show)
        _ = makeEpisode(season: 2, episode: 1, in: show)

        let next = PlayerLogic.nextEpisode(after: s2e3, in: show)
        #expect(next == nil)
    }

    // MARK: - Large library with specials and duplicates

    @Test func complexLibraryProgressesCorrectly() {
        let show = makeShow(name: "Anime")
        _ = makeEpisode(season: 0, episode: 1, in: show, title: "OVA 1")
        let s1e1 = makeEpisode(season: 1, episode: 1, in: show)
        _ = makeEpisode(season: 1, episode: 1, in: show, title: "S1E1 alt")
        let s1e2 = makeEpisode(season: 1, episode: 2, in: show)
        _ = makeEpisode(season: 0, episode: 2, in: show, title: "OVA 2")
        let s1e3 = makeEpisode(season: 1, episode: 3, in: show)
        let s2e1 = makeEpisode(season: 2, episode: 1, in: show)
        _ = makeEpisode(season: 0, episode: 3, in: show, title: "Recap")

        #expect(PlayerLogic.nextEpisode(after: s1e1, in: show)?.id == s1e2.id)
        #expect(PlayerLogic.nextEpisode(after: s1e2, in: show)?.id == s1e3.id)
        #expect(PlayerLogic.nextEpisode(after: s1e3, in: show)?.id == s2e1.id)
        #expect(PlayerLogic.nextEpisode(after: s2e1, in: show) == nil)
    }

    // MARK: - Natural end detection with progression context

    @Test func naturalEndDetectedNearMediaEnd() {
        let duration = Duration.seconds(2400)
        #expect(PlayerLogic.isNaturalEnd(time: .seconds(2350), duration: duration))
        #expect(!PlayerLogic.isNaturalEnd(time: .seconds(1200), duration: duration))
    }

    @Test func creditsSkipPositionIsNotNaturalEndForShortEpisodes() {
        let shortEpisode = Duration.seconds(1320)
        if let creditsStart = PlayerLogic.creditsStart(duration: shortEpisode) {
            let isEnd = PlayerLogic.isNaturalEnd(time: creditsStart, duration: shortEpisode)
            #expect(!isEnd)
        }
    }

    @Test func creditsStartIsNotNaturalEnd() {
        let duration = Duration.seconds(2400)
        guard let creditsStart = PlayerLogic.creditsStart(duration: duration) else {
            Issue.record("Expected credits window for 40-minute media")
            return
        }
        // Credits start (2220s = 92.5%) falls below the 95% natural-end
        // threshold. Skip Credits writes completion explicitly via
        // saveCompletionProgress rather than relying on isNaturalEnd.
        #expect(!PlayerLogic.isNaturalEnd(time: creditsStart, duration: duration))
    }

    @Test func explicitCompletionPositionIsNaturalEnd() {
        // A position of 1.0 (100%), as written by saveCompletionProgress,
        // always satisfies the natural-end check.
        let duration = Duration.seconds(2400)
        #expect(PlayerLogic.isNaturalEnd(time: duration, duration: duration))
    }

    // MARK: - Show membership

    @Test func episodeFromDifferentShowReturnsNil() {
        let showA = makeShow(name: "Show A")
        _ = makeEpisode(season: 1, episode: 1, in: showA)
        _ = makeEpisode(season: 1, episode: 2, in: showA)

        let showB = makeShow(name: "Show B")
        let foreign = makeEpisode(season: 1, episode: 1, in: showB)

        #expect(PlayerLogic.nextEpisode(after: foreign, in: showA) == nil)
    }

    // MARK: - highestCompletedPerShow

    @Test func highestCompletedPerShowSelectsFurthest() {
        let entries = [
            WatchProgress(tmdbId: 101, mediaType: .episode, position: 1, isCompleted: true,
                          showTmdbId: 1, seasonNumber: 1, episodeNumber: 1),
            WatchProgress(tmdbId: 103, mediaType: .episode, position: 1, isCompleted: true,
                          showTmdbId: 1, seasonNumber: 1, episodeNumber: 3),
            WatchProgress(tmdbId: 102, mediaType: .episode, position: 1, isCompleted: true,
                          showTmdbId: 1, seasonNumber: 1, episodeNumber: 2),
        ]
        let result = PlayerLogic.highestCompletedPerShow(entries)
        #expect(result[1]?.season == 1)
        #expect(result[1]?.episode == 3)
    }

    @Test func highestCompletedPerShowIgnoresInProgress() {
        let entries: [WatchProgress] = [
            WatchProgress(tmdbId: 101, mediaType: .episode, position: 1, isCompleted: true,
                          showTmdbId: 1, seasonNumber: 1, episodeNumber: 1),
            WatchProgress(tmdbId: 102, mediaType: .episode, position: 0.4, isCompleted: false,
                          showTmdbId: 1, seasonNumber: 1, episodeNumber: 2),
        ]
        let result = PlayerLogic.highestCompletedPerShow(entries)
        #expect(result[1]?.episode == 1)
    }

    @Test func highestCompletedPerShowGroupsByShow() {
        let entries = [
            WatchProgress(tmdbId: 101, mediaType: .episode, position: 1, isCompleted: true,
                          showTmdbId: 1, seasonNumber: 1, episodeNumber: 3),
            WatchProgress(tmdbId: 201, mediaType: .episode, position: 1, isCompleted: true,
                          showTmdbId: 2, seasonNumber: 1, episodeNumber: 1),
        ]
        let result = PlayerLogic.highestCompletedPerShow(entries)
        #expect(result.count == 2)
        #expect(result[1]?.episode == 3)
        #expect(result[2]?.episode == 1)
    }

    @Test func highestCompletedPerShowIgnoresMovies() {
        let entries: [WatchProgress] = [
            WatchProgress(tmdbId: 999, mediaType: .movie, position: 1, isCompleted: true),
        ]
        #expect(PlayerLogic.highestCompletedPerShow(entries).isEmpty)
    }
}
