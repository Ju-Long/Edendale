//
//  LibraryMetadataAndSourcesTests.swift
//  EdendaleTests
//
//  TMDB enrichment that finishes what an earlier sweep left (new episodes
//  of a matched show, lookups made offline, titles with no match retried
//  after a while), and titles held by several sources or reached through
//  several endpoints: one card each, their endpoints in Play From, and the
//  source played last kept for Play, Continue Watching, and the next
//  episode. No request reaches TMDB: the lookups are answered here.
//

import Foundation
import SwiftData
import Testing
@testable import Edendale

@MainActor
final class FakeMetadata {
    var movies: [String: TMDBMovieResult] = [:]
    var shows: [String: TMDBTVResult] = [:]
    /// "showId-season-episode" → detail; anything else answers 404.
    var episodes: [String: TMDBEpisodeDetail] = [:]
    /// Thrown by every lookup while set, as an unreachable TMDB would.
    var failure: (any Error)?
    private(set) var requests: [String] = []

    var lookup: MetadataLookup {
        MetadataLookup(
            searchMovie: { title, _ in
                self.requests.append("movie:\(title)")
                if let failure = self.failure { throw failure }
                return self.movies[title]
            },
            searchTV: { name in
                self.requests.append("tv:\(name)")
                if let failure = self.failure { throw failure }
                return self.shows[name]
            },
            fetchEpisode: { showId, season, episode in
                let key = "\(showId)-\(season)-\(episode)"
                self.requests.append("episode:\(key)")
                if let failure = self.failure { throw failure }
                guard let detail = self.episodes[key] else { throw TMDBError.badStatus(404) }
                return detail
            }
        )
    }

    func resetRequests() { requests = [] }

    static func movie(_ id: Int, _ title: String) -> TMDBMovieResult {
        TMDBMovieResult(id: id, title: title, releaseDate: "1995-12-15", overview: "", posterPath: nil, backdropPath: nil, voteAverage: nil)
    }

    static func show(_ id: Int, _ name: String) -> TMDBTVResult {
        TMDBTVResult(id: id, name: name, firstAirDate: "2022-02-18", overview: "", posterPath: nil, backdropPath: nil)
    }

    static func episode(_ id: Int, season: Int, number: Int) -> TMDBEpisodeDetail {
        TMDBEpisodeDetail(id: id, name: "Episode \(number)", overview: nil, stillPath: nil, episodeNumber: number, seasonNumber: season, airDate: nil, voteAverage: nil, runtime: nil)
    }
}

@MainActor
private final class SaveCounter {
    var count = 0
}

@MainActor
struct LibraryEnrichmentTests {

    private let container: ModelContainer
    private let fake = FakeMetadata()
    private let library: LibraryController

    init() throws {
        let schema = Schema([VideoFolder.self, Movie.self, TVShow.self, Episode.self])
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
        container = try ModelContainer(for: schema, configurations: [configuration])
        library = LibraryController(modelContext: container.mainContext, metadata: fake.lookup)
    }

    private var context: ModelContext { container.mainContext }

    @discardableResult
    private func addMovie(_ title: String, to folder: VideoFolder) -> Movie {
        let movie = Movie(localTitle: title, filePath: "\(folder.folderPath)/\(title).mkv")
        folder.movies.append(movie)
        return movie
    }

    @discardableResult
    private func addEpisode(_ season: Int, _ number: Int, to show: TVShow) -> Episode {
        let episode = Episode(
            localTitle: "\(show.name) S\(season)E\(number)",
            filePath: "/\(show.name)/S\(season)E\(number).mkv",
            seasonNumber: season,
            episodeNumber: number
        )
        show.episodes.append(episode)
        return episode
    }

    private func folder(_ name: String = "Films") -> VideoFolder {
        let folder = VideoFolder(name: name, folderPath: "/\(name)")
        context.insert(folder)
        return folder
    }

    private func sweep(retryingUnmatched: Bool = false) async {
        await library.enrichPendingMetadata(retryingUnmatched: retryingUnmatched)?.value
    }

    @Test func matchesMoviesShowsAndTheirEpisodes() async throws {
        let films = folder()
        let movie = addMovie("Heat", to: films)
        let show = TVShow(name: "Severance")
        films.tvShows.append(show)
        let pilot = addEpisode(1, 1, to: show)
        fake.movies["Heat"] = FakeMetadata.movie(949, "Heat")
        fake.shows["Severance"] = FakeMetadata.show(95396, "Severance")
        fake.episodes["95396-1-1"] = FakeMetadata.episode(500, season: 1, number: 1)
        #expect(library.pendingMetadataCount() == 2) // the episode waits for its show

        await sweep()

        #expect(movie.tmdbId == 949)
        #expect(show.tmdbId == 95396)
        #expect(pilot.tmdbId == 500)
        #expect(pilot.episodeTitle == "Episode 1")
        #expect(library.pendingMetadataCount() == 0)
        #expect(!library.isEnriching)
    }

    @Test func episodesAddedToAMatchedShowAreMatched() async throws {
        let films = folder()
        let show = TVShow(name: "Severance")
        show.tmdbId = 95396
        films.tvShows.append(show)
        let matched = addEpisode(1, 1, to: show)
        matched.tmdbId = 500
        // A rescan found the next file after the show was matched.
        let added = addEpisode(1, 2, to: show)
        fake.episodes["95396-1-2"] = FakeMetadata.episode(501, season: 1, number: 2)
        #expect(library.pendingMetadataCount() == 1)

        await sweep()

        #expect(added.tmdbId == 501)
        #expect(fake.requests == ["episode:95396-1-2"])
    }

    @Test func unreachableTMDBLeavesItemsForTheNextSweep() async throws {
        let films = folder()
        let heat = addMovie("Heat", to: films)
        let ronin = addMovie("Ronin", to: films)
        fake.failure = URLError(.notConnectedToInternet)

        await sweep()

        // One failed request ends the pass; nothing is marked unmatched.
        #expect(fake.requests.count == 1)
        #expect(heat.tmdbId == nil && heat.metadataCheckedAt == nil)
        #expect(ronin.tmdbId == nil && ronin.metadataCheckedAt == nil)
        #expect(library.pendingMetadataCount() == 2)

        fake.failure = nil
        fake.movies["Heat"] = FakeMetadata.movie(949, "Heat")
        fake.movies["Ronin"] = FakeMetadata.movie(8195, "Ronin")
        await sweep()
        #expect(heat.tmdbId == 949)
        #expect(ronin.tmdbId == 8195)
    }

    @Test func unmatchedTitlesWaitBeforeBeingAskedAgain() async throws {
        let films = folder()
        let home = addMovie("Birthday 2019", to: films)
        let show = TVShow(name: "Severance")
        show.tmdbId = 95396
        films.tvShows.append(show)
        // Numbering TMDB doesn't have: a 404 is an answer, not a failure.
        let special = addEpisode(7, 40, to: show)

        await sweep()
        #expect(home.metadataCheckedAt != nil)
        #expect(special.metadataCheckedAt != nil)
        #expect(library.pendingMetadataCount() == 0)

        // The automatic sweep (every library visit) doesn't ask again…
        fake.resetRequests()
        await sweep()
        #expect(fake.requests.isEmpty)

        // …until the interval passes, or a rescan forces it.
        let later = Date().addingTimeInterval(LibraryController.unmatchedRetryInterval + 1)
        #expect(library.pendingMetadataCount(now: later) == 2)
        fake.movies["Birthday 2019"] = FakeMetadata.movie(1, "Birthday")
        await sweep(retryingUnmatched: true)
        #expect(fake.requests.count == 2)
        #expect(home.tmdbId == 1)
        #expect(home.metadataCheckedAt == nil)
    }

    @Test func classifiesLookupFailures() {
        #expect(LibraryController.haltsEnrichment(URLError(.timedOut)))
        #expect(LibraryController.haltsEnrichment(TMDBError.missingCredentials))
        #expect(LibraryController.haltsEnrichment(TMDBError.badStatus(429)))
        #expect(LibraryController.haltsEnrichment(TMDBError.badStatus(503)))
        #expect(LibraryController.haltsEnrichment(TMDBError.badStatus(401)))
        #expect(!LibraryController.haltsEnrichment(TMDBError.badStatus(404)))
        #expect(!LibraryController.haltsEnrichment(URLError(.badURL)))
        #expect(!LibraryController.haltsEnrichment(DecodingError.dataCorrupted(.init(codingPath: [], debugDescription: ""))))
    }

    @Test func requestsDuringASweepQueueOneMorePass() async throws {
        let films = folder()
        let heat = addMovie("Heat", to: films)
        fake.movies["Heat"] = FakeMetadata.movie(949, "Heat")
        let first = library.enrichPendingMetadata()
        let ronin = addMovie("Ronin", to: films)
        fake.movies["Ronin"] = FakeMetadata.movie(8195, "Ronin")
        let second = library.enrichPendingMetadata()
        await second?.value
        await first?.value

        #expect(heat.tmdbId == 949)
        #expect(ronin.tmdbId == 8195)
        // Each title was asked about once.
        #expect(fake.requests.sorted() == ["movie:Heat", "movie:Ronin"])
    }

    /// Each save refreshes every page showing the library, so a sweep
    /// writes its matches in batches: here the movies and shows together,
    /// then the episodes found under the shows' matches.
    @Test func writesMatchesInBatchesWithOneSaveEach() async throws {
        let films = folder()
        for title in ["Heat", "Ronin", "Collateral"] {
            addMovie(title, to: films)
            fake.movies[title] = FakeMetadata.movie(title.count, title)
        }
        let show = TVShow(name: "Severance")
        films.tvShows.append(show)
        let pilot = addEpisode(1, 1, to: show)
        let second = addEpisode(1, 2, to: show)
        fake.shows["Severance"] = FakeMetadata.show(95396, "Severance")
        fake.episodes["95396-1-1"] = FakeMetadata.episode(500, season: 1, number: 1)
        fake.episodes["95396-1-2"] = FakeMetadata.episode(501, season: 1, number: 2)
        try context.save()

        let saves = SaveCounter()
        let observer = NotificationCenter.default.addObserver(
            forName: ModelContext.didSave, object: context, queue: nil
        ) { _ in MainActor.assumeIsolated { saves.count += 1 } }
        defer { NotificationCenter.default.removeObserver(observer) }

        await sweep()

        #expect(pilot.tmdbId == 500 && second.tmdbId == 501)
        #expect(library.pendingMetadataCount() == 0)
        #expect(saves.count == 2)
    }

    /// While the viewer scrolls or watches, the lookups carry on and what
    /// they find waits; it is written once things settle.
    @Test func holdsMatchesWhileTheViewerIsBusy() async throws {
        let gate = InteractionGate(settleDelay: .milliseconds(50))
        let library = LibraryController(modelContext: context, metadata: fake.lookup, interactionGate: gate)
        let films = folder()
        let heat = addMovie("Heat", to: films)
        let ronin = addMovie("Ronin", to: films)
        fake.movies["Heat"] = FakeMetadata.movie(949, "Heat")
        fake.movies["Ronin"] = FakeMetadata.movie(8195, "Ronin")
        gate.set(.playback, active: true)

        let task = library.enrichPendingMetadata()
        for _ in 0..<200 where fake.requests.count < 2 {
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(fake.requests.count == 2)
        try await Task.sleep(for: .milliseconds(300))
        #expect(heat.tmdbId == nil && ronin.tmdbId == nil)
        #expect(library.isEnriching)

        gate.set(.playback, active: false)
        await task?.value
        #expect(heat.tmdbId == 949)
        #expect(ronin.tmdbId == 8195)
        #expect(!library.isEnriching)
    }

    @Test func playingACopyRemembersItsSource() async throws {
        let lan = VideoFolder(name: "Media", folderPath: "sftp://longs-mac-studio.local/Media", sourceKind: .sftp)
        let tailscale = VideoFolder(name: "Media", folderPath: "sftp://100.64.0.7/Media", sourceKind: .sftp)
        context.insert(lan)
        context.insert(tailscale)
        let lanShow = TVShow(name: "Severance"), tailShow = TVShow(name: "Severance")
        lan.tvShows.append(lanShow)
        tailscale.tvShows.append(tailShow)
        let episode = Episode(localTitle: "S1E1", filePath: "sftp://100.64.0.7/Media/S1E1.mkv", seasonNumber: 1, episodeNumber: 1)
        tailShow.episodes.append(episode)
        let movie = Movie(localTitle: "Heat", filePath: "sftp://100.64.0.7/Media/Heat.mkv")
        tailscale.movies.append(movie)

        _ = await library.preparePlayback(episode: episode)
        _ = await library.preparePlayback(movie: movie)

        #expect(tailShow.lastPlayedAt != nil)
        #expect(lanShow.lastPlayedAt == nil)
        #expect(movie.lastPlayedAt != nil)
        #expect(library.otherCopies(of: tailShow).map(\.id) == [lanShow.id])
    }
}

@MainActor
struct MultipleSourcesTests {

    private let container: ModelContainer

    init() throws {
        let schema = Schema([VideoFolder.self, Movie.self, TVShow.self, Episode.self])
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
        container = try ModelContainer(for: schema, configurations: [configuration])
    }

    private var context: ModelContext { container.mainContext }

    private func smb(_ host: String) -> VideoFolder {
        let folder = VideoFolder(name: "Media", folderPath: "smb://\(host)/Media", sourceKind: .smb)
        context.insert(folder)
        return folder
    }

    private func show(in folder: VideoFolder, tmdbId: Int? = 95396, episodeBase: Int = 1000, episodes: [(Int, Int)]) -> TVShow {
        let show = TVShow(name: "Severance")
        show.tmdbId = tmdbId
        folder.tvShows.append(show)
        for (season, number) in episodes {
            let episode = Episode(
                localTitle: "S\(season)E\(number)",
                filePath: "\(folder.folderPath)/S\(season)E\(number).mkv",
                seasonNumber: season,
                episodeNumber: number
            )
            episode.tmdbId = episodeBase + season * 100 + number
            show.episodes.append(episode)
        }
        return show
    }

    @Test func oneShareThroughTwoEndpointsIsOneTitle() throws {
        let lan = smb("longs-mac-studio.local"), tailscale = smb("100.64.0.7")
        let lanMovie = Movie(localTitle: "Heat", filePath: "smb://longs-mac-studio.local/Media/Heat.mkv")
        let tailMovie = Movie(localTitle: "Heat", filePath: "smb://100.64.0.7/Media/Heat.mkv")
        lan.movies.append(lanMovie)
        tailscale.movies.append(tailMovie)

        // Grouped before TMDB answers (same parsed title), and after.
        #expect(PlaybackSources.distinct([lanMovie, tailMovie], key: { PlaybackSources.copyKey($0) }).count == 1)
        lanMovie.tmdbId = 949
        tailMovie.tmdbId = 949
        #expect(PlaybackSources.distinct([lanMovie, tailMovie], key: { PlaybackSources.copyKey($0) }).map(\.id) == [lanMovie.id])
        let other = Movie(localTitle: "Ronin", filePath: "smb://100.64.0.7/Media/Ronin.mkv")
        #expect(PlaybackSources.distinct([lanMovie, other, tailMovie], key: { PlaybackSources.copyKey($0) }).count == 2)

        let lanShow = show(in: lan, tmdbId: nil, episodes: [(1, 1)])
        let tailShow = show(in: tailscale, tmdbId: nil, episodes: [(1, 1)])
        #expect(PlaybackSources.copyKey(lanShow) == PlaybackSources.copyKey(tailShow))
    }

    @Test func playFromNamesEachEndpoint() throws {
        let lan = smb("longs-mac-studio.local"), tailscale = smb("100.64.0.7")
        #expect(PlaybackSources.title(folder: lan, filePath: "smb://longs-mac-studio.local/Media/Heat.mkv")
            == "Media (longs-mac-studio.local)")
        #expect(PlaybackSources.title(folder: tailscale, filePath: "smb://100.64.0.7/Media/Heat.mkv")
            == "Media (100.64.0.7)")
        #expect(PlaybackSources.endpoint(folder: nil, filePath: "sftp://nas:2222/Heat.mkv") == "nas:2222")
        // Local files have no endpoint; accounts show the account, not the key hash.
        let local = VideoFolder(name: "Movies", folderPath: "/Users/me/Movies")
        #expect(PlaybackSources.title(folder: local, filePath: "/Users/me/Movies/Heat.mkv") == "Movies")
        let drive = VideoFolder(name: "Videos you chose", folderPath: "gdrive://k3y/root", sourceKind: .googleDrive, username: "me@example.com")
        #expect(PlaybackSources.endpoint(folder: drive, filePath: "gdrive://k3y/1AbC/Heat.mkv") == "me@example.com")
    }

    @Test func playPrefersTheCopyPlayedLast() {
        let now = Date()
        let copies = ["lan", "tailscale", "laptop"]
        let played = ["tailscale": now, "laptop": now.addingTimeInterval(-60)]
        #expect(PlaybackSources.preferred(copies, isOffline: { _ in false }, lastPlayed: { played[$0] }) == "tailscale")
        // The source played last is offline: the next most recent reachable one.
        #expect(PlaybackSources.preferred(copies, isOffline: { $0 == "tailscale" }, lastPlayed: { played[$0] }) == "laptop")
        // Nothing played yet: the first reachable, as before.
        #expect(PlaybackSources.preferred(copies, isOffline: { _ in false }, lastPlayed: { _ in nil }) == "lan")
        #expect(PlaybackSources.lastPlayedFirst(copies, lastPlayed: { played[$0] }) == ["tailscale", "lan", "laptop"])
    }

    @Test func nextEpisodeStaysOnTheChosenSource() throws {
        let lan = show(in: smb("longs-mac-studio.local"), episodes: [(1, 1), (1, 2), (1, 3)])
        let tailscale = show(in: smb("100.64.0.7"), episodes: [(1, 1), (1, 2), (1, 3)])
        let playing = try #require(tailscale.episodes.first { $0.episodeNumber == 1 })

        let next = PlayerLogic.nextEpisode(after: playing, in: tailscale, otherCopies: [lan])
        #expect(next?.show?.id == tailscale.id)
        #expect(next?.episodeNumber == 2)
    }

    @Test func nextEpisodeComesFromAnotherCopyOnlyWhenTheChosenOneLacksIt() throws {
        let nas = show(in: smb("nas"), episodes: [(1, 1), (1, 2), (1, 4)])
        let laptop = show(in: smb("laptop"), episodes: [(1, 3)])
        let offline = show(in: smb("offline"), episodes: [(1, 3), (1, 5)])
        let second = try #require(nas.episodes.first { $0.episodeNumber == 2 })
        let fourth = try #require(nas.episodes.first { $0.episodeNumber == 4 })

        // E03 is only elsewhere: it isn't skipped, and an offline source loses.
        let third = PlayerLogic.nextEpisode(after: second, in: nas, otherCopies: [offline, laptop]) { $0.id == offline.id }
        #expect(third?.show?.id == laptop.id)
        #expect(third?.episodeNumber == 3)
        // Back on the chosen source as soon as it has the next one.
        #expect(PlayerLogic.nextEpisode(after: third!, in: laptop, otherCopies: [nas, offline])?.show?.id == nas.id)
        // Past its last episode: E05 from the only copy holding it.
        #expect(PlayerLogic.nextEpisode(after: fourth, in: nas, otherCopies: [laptop, offline])?.show?.id == offline.id)
        #expect(PlayerLogic.nextEpisode(after: fourth, in: nas, otherCopies: [laptop]) == nil)
    }

    @Test func continueWatchingResumesFromTheSourcePlayedLast() throws {
        let store = WatchProgressStore()
        let showID = Int.random(in: 10_000_000...90_000_000)
        // The test host's watch store is shared: IDs no other test uses,
        // within the store's 32-bit columns.
        let base = Int.random(in: 1_100_000_000...2_000_000_000)
        let lan = show(in: smb("longs-mac-studio.local"), tmdbId: showID, episodeBase: base, episodes: [(1, 1), (1, 2)])
        let tailscale = show(in: smb("100.64.0.7"), tmdbId: showID, episodeBase: base, episodes: [(1, 1), (1, 2), (1, 3)])
        tailscale.lastPlayedAt = Date()
        let first = try #require(tailscale.episodes.first { $0.episodeNumber == 1 })
        store.update(WatchProgress(
            tmdbId: first.tmdbId!, mediaType: .episode, position: 0.4,
            showTmdbId: showID, seasonNumber: 1, episodeNumber: 1
        ))
        defer { store.remove(tmdbId: first.tmdbId!, mediaType: .episode) }

        var items = ResumeItem.items(watchStore: store, movies: [], shows: [lan, tailscale], limit: nil)
        #expect(items.count == 1)
        guard case .episode(let resumed) = items.first?.payload else {
            Issue.record("Expected an episode")
            return
        }
        #expect(resumed.show?.id == tailscale.id)

        // Offline now: the other endpoint takes over.
        items = ResumeItem.items(watchStore: store, movies: [], shows: [lan, tailscale], limit: nil) {
            $0?.id == tailscale.folder?.id
        }
        guard case .episode(let fallback) = items.first?.payload else {
            Issue.record("Expected an episode")
            return
        }
        #expect(fallback.show?.id == lan.id)
    }

    @Test func clearingProgressRemovesItFromContinueWatching() {
        let store = WatchProgressStore()
        let id = Int.random(in: 10_000_000...90_000_000)
        let movie = Movie(localTitle: "Heat", filePath: "/Movies/Heat.mkv")
        movie.tmdbId = id
        store.update(WatchProgress(tmdbId: id, mediaType: .movie, position: 0.5))
        #expect(ResumeItem.items(watchStore: store, movies: [movie], shows: [], limit: nil).count == 1)

        store.remove(tmdbId: id, mediaType: .movie)
        #expect(store.progress(for: id, mediaType: .movie) == nil)
        #expect(ResumeItem.items(watchStore: store, movies: [movie], shows: [], limit: nil).isEmpty)
    }

    @Test func markingAnEpisodeWatchedKeepsItsShowForNextUpAndSync() throws {
        let store = WatchProgressStore()
        let showID = Int.random(in: 10_000_000...90_000_000)
        let base = Int.random(in: 1_100_000_000...2_000_000_000)
        let show = TVShow(name: "Severance")
        show.tmdbId = showID
        for number in 1...2 {
            let episode = Episode(localTitle: "E\(number)", filePath: "/Severance/E\(number).mkv", seasonNumber: 1, episodeNumber: number)
            episode.tmdbId = base + number
            episode.show = show
            show.episodes.append(episode)
        }

        store.markCompleted(tmdbId: base + 1, mediaType: .episode, showTmdbId: showID, seasonNumber: 1, episodeNumber: 1)
        defer { store.remove(tmdbId: base + 1, mediaType: .episode) }

        // The sync file can name it, and Continue Watching offers the next one.
        #expect(store.progress(for: base + 1, mediaType: .episode)?.syncKey == .episode(show: showID, season: 1, episode: 1))
        let item = try #require(ResumeItem.items(watchStore: store, movies: [], shows: [show], limit: nil).first)
        #expect(item.isNextUp)
        guard case .episode(let next) = item.payload else {
            Issue.record("Expected the next episode")
            return
        }
        #expect(next.episodeNumber == 2)
    }
}
