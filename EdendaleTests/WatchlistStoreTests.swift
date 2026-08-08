//
//  WatchlistStoreTests.swift
//  EdendaleTests
//

import Foundation
import SwiftData
import Testing
@testable import Edendale

@MainActor
struct WatchlistStoreTests {
    @Test func signedOutChangesPersistLocallyWithoutRemoteRequests() throws {
        let harness = try makeHarness(isAuthenticated: false)
        let ref = MediaRef(id: 603, mediaType: .movie)
        let metadata = WatchlistMetadata(
            title: "The Matrix",
            overview: "A hacker learns the truth.",
            posterPath: "/matrix-poster.jpg",
            backdropPath: "/matrix-backdrop.jpg",
            voteAverage: 8.2,
            releaseDate: "1999-03-30"
        )

        harness.store.setWatchlist(true, for: ref, metadata: metadata)

        var persisted = try persistedItems(in: harness.container)
        var item = try #require(persisted.first)
        #expect(persisted.count == 1)
        #expect(item.ref == ref)
        #expect(item.isInWatchlist)
        #expect(item.pendingAction == .add)
        #expect(item.title == "The Matrix")
        #expect(harness.store.isInWatchlist(ref))

        harness.store.setWatchlist(false, for: ref)

        persisted = try persistedItems(in: harness.container)
        item = try #require(persisted.first)
        #expect(persisted.count == 1)
        #expect(item.isInWatchlist == false)
        #expect(item.pendingAction == .remove)
        #expect(harness.store.isInWatchlist(ref) == false)
        #expect(harness.remote.mutations.isEmpty)
        #expect(harness.remote.listRequests.isEmpty)
    }

    @Test func movieAndTVWithTheSameIDRemainDistinctAndKeepMetadata() throws {
        let harness = try makeHarness(isAuthenticated: false)
        let movie = MediaRef(id: 42, mediaType: .movie)
        let show = MediaRef(id: 42, mediaType: .tv)

        harness.store.setWatchlist(
            true,
            for: movie,
            metadata: WatchlistMetadata(
                title: "Movie 42",
                overview: "Movie overview",
                posterPath: "/movie-poster.jpg",
                backdropPath: "/movie-backdrop.jpg",
                voteAverage: 7.4,
                releaseDate: "2024-01-02"
            )
        )
        harness.store.setWatchlist(
            true,
            for: show,
            metadata: WatchlistMetadata(
                title: "Show 42",
                overview: "Show overview",
                posterPath: "/show-poster.jpg",
                backdropPath: "/show-backdrop.jpg",
                voteAverage: 8.6,
                releaseDate: "2025-03-04"
            )
        )

        let items = try persistedItems(in: harness.container)
        let byKey = Dictionary(uniqueKeysWithValues: items.map { ($0.storageKey, $0) })
        let savedMovie = try #require(byKey["movie_42"])
        let savedShow = try #require(byKey["tv_42"])

        #expect(items.count == 2)
        #expect(savedMovie.ref == movie)
        #expect(savedMovie.title == "Movie 42")
        #expect(savedMovie.overview == "Movie overview")
        #expect(savedMovie.posterPath == "/movie-poster.jpg")
        #expect(savedMovie.backdropPath == "/movie-backdrop.jpg")
        #expect(savedMovie.voteAverage == 7.4)
        #expect(savedMovie.releaseDate == "2024-01-02")
        #expect(savedShow.ref == show)
        #expect(savedShow.title == "Show 42")
        #expect(savedShow.overview == "Show overview")
        #expect(savedShow.posterPath == "/show-poster.jpg")
        #expect(savedShow.backdropPath == "/show-backdrop.jpg")
        #expect(savedShow.voteAverage == 8.6)
        #expect(savedShow.releaseDate == "2025-03-04")
        #expect(harness.store.refs == Set([movie, show]))
    }

    @Test func signedInChangeAttemptsRemoteMutation() async throws {
        let harness = try makeHarness(isAuthenticated: true)
        let ref = MediaRef(id: 27205, mediaType: .movie)

        harness.store.setWatchlist(
            true,
            for: ref,
            metadata: WatchlistMetadata(title: "Inception")
        )

        let completed = await eventually {
            harness.remote.mutations.count == 1 && harness.store.isSyncing == false
        }
        #expect(completed)
        #expect(
            harness.remote.mutations == [
                RemoteMutation(inWatchlist: true, ref: ref)
            ]
        )
        #expect(harness.remote.listRequests.isEmpty)
    }

    @Test func failedPushKeepsTheLocalPendingAction() async throws {
        let ref = MediaRef(id: 278, mediaType: .movie)
        let pending = WatchlistItem(
            ref: ref,
            metadata: WatchlistMetadata(title: "The Shawshank Redemption"),
            pendingAction: .add
        )
        let harness = try makeHarness(
            isAuthenticated: true,
            failMutations: true,
            localItems: [pending]
        )

        await harness.store.syncFromTMDB()

        let item = try #require(try persistedItems(in: harness.container).first)
        #expect(item.ref == ref)
        #expect(item.isInWatchlist)
        #expect(item.pendingAction == .add)
        #expect(harness.store.isInWatchlist(ref))
        #expect(harness.store.lastSyncError != nil)
        #expect(
            harness.remote.mutations == [
                RemoteMutation(inWatchlist: true, ref: ref)
            ]
        )
    }

    @Test func fullPullImportsMovieAndTVMetadataAndRemovesAbsentConfirmedRows() async throws {
        let removedRef = MediaRef(id: 11, mediaType: .movie)
        let confirmedLocal = WatchlistItem(
            ref: removedRef,
            metadata: WatchlistMetadata(title: "Removed Remotely"),
            pendingAction: .none
        )
        let remoteMovie = mediaItem(
            id: 155,
            type: .movie,
            title: "The Dark Knight",
            overview: "Batman faces the Joker.",
            posterPath: "/dark-knight-poster.jpg",
            backdropPath: "/dark-knight-backdrop.jpg",
            voteAverage: 8.5,
            releaseDate: "2008-07-16"
        )
        let remoteShow = mediaItem(
            id: 1396,
            type: .tv,
            title: "Breaking Bad",
            overview: "A chemistry teacher changes course.",
            posterPath: "/breaking-bad-poster.jpg",
            backdropPath: "/breaking-bad-backdrop.jpg",
            voteAverage: 8.9,
            releaseDate: "2008-01-20"
        )
        let harness = try makeHarness(
            isAuthenticated: true,
            movies: [remoteMovie],
            shows: [remoteShow],
            localItems: [confirmedLocal]
        )

        await harness.store.syncFromTMDB()

        let items = try persistedItems(in: harness.container)
        let byKey = Dictionary(uniqueKeysWithValues: items.map { ($0.storageKey, $0) })
        let movie = try #require(byKey[WatchlistItem.key(for: remoteMovie.ref)])
        let show = try #require(byKey[WatchlistItem.key(for: remoteShow.ref)])

        #expect(items.count == 2)
        #expect(byKey[WatchlistItem.key(for: removedRef)] == nil)
        #expect(movie.title == remoteMovie.title)
        #expect(movie.overview == remoteMovie.overview)
        #expect(movie.posterPath == remoteMovie.posterPath)
        #expect(movie.backdropPath == remoteMovie.backdropPath)
        #expect(movie.voteAverage == remoteMovie.voteAverage)
        #expect(movie.releaseDate == remoteMovie.releaseDate)
        #expect(movie.pendingAction == .none)
        #expect(show.title == remoteShow.title)
        #expect(show.overview == remoteShow.overview)
        #expect(show.posterPath == remoteShow.posterPath)
        #expect(show.backdropPath == remoteShow.backdropPath)
        #expect(show.voteAverage == remoteShow.voteAverage)
        #expect(show.releaseDate == remoteShow.releaseDate)
        #expect(show.pendingAction == .none)
        #expect(harness.store.refs == Set([remoteMovie.ref, remoteShow.ref]))
        #expect(Set(harness.remote.listRequests) == Set([.movie, .tv]))
        #expect(harness.remote.mutations.isEmpty)
    }

    @Test func pendingAddSurvivesAStaleRemoteList() async throws {
        let ref = MediaRef(id: 550, mediaType: .movie)
        let pending = WatchlistItem(
            ref: ref,
            metadata: WatchlistMetadata(
                title: "Fight Club",
                posterPath: "/fight-club.jpg"
            ),
            pendingAction: .add
        )
        let harness = try makeHarness(
            isAuthenticated: true,
            localItems: [pending]
        )

        await harness.store.syncFromTMDB()

        let item = try #require(try persistedItems(in: harness.container).first)
        #expect(item.ref == ref)
        #expect(item.title == "Fight Club")
        #expect(item.posterPath == "/fight-club.jpg")
        #expect(item.isInWatchlist)
        #expect(item.pendingAction == .add)
        #expect(harness.store.isInWatchlist(ref))
        #expect(
            harness.remote.mutations == [
                RemoteMutation(inWatchlist: true, ref: ref)
            ]
        )
    }

    @Test func pendingRemovalIsDeletedWhenRemoteConfirmsAbsence() async throws {
        let ref = MediaRef(id: 1399, mediaType: .tv)
        let pending = WatchlistItem(
            ref: ref,
            metadata: WatchlistMetadata(title: "Game of Thrones"),
            isInWatchlist: false,
            pendingAction: .remove
        )
        let harness = try makeHarness(
            isAuthenticated: true,
            localItems: [pending]
        )

        await harness.store.syncFromTMDB()

        #expect(try persistedItems(in: harness.container).isEmpty)
        #expect(harness.store.isInWatchlist(ref) == false)
        #expect(
            harness.remote.mutations == [
                RemoteMutation(inWatchlist: false, ref: ref)
            ]
        )
        #expect(Set(harness.remote.listRequests) == Set([.movie, .tv]))
    }

    private func makeHarness(
        isAuthenticated: Bool,
        movies: [TMDBMediaItem] = [],
        shows: [TMDBMediaItem] = [],
        failMutations: Bool = false,
        localItems: [WatchlistItem] = []
    ) throws -> Harness {
        let schema = Schema([WatchlistItem.self])
        let configuration = ModelConfiguration(
            schema: schema,
            isStoredInMemoryOnly: true,
            cloudKitDatabase: .none
        )
        let container = try ModelContainer(
            for: schema,
            configurations: [configuration]
        )
        let context = container.mainContext
        for item in localItems {
            context.insert(item)
        }
        if !localItems.isEmpty {
            try context.save()
        }

        let remote = WatchlistRemoteStub(
            movies: movies,
            shows: shows,
            failMutations: failMutations
        )
        let store = WatchlistStore(
            modelContext: context,
            account: remote,
            isAuthenticated: { isAuthenticated },
            legacyContext: nil
        )
        return Harness(container: container, remote: remote, store: store)
    }

    private func persistedItems(in container: ModelContainer) throws -> [WatchlistItem] {
        let context = ModelContext(container)
        return try context.fetch(FetchDescriptor<WatchlistItem>())
            .sorted { $0.storageKey < $1.storageKey }
    }

    private func mediaItem(
        id: Int,
        type: TMDBMediaType,
        title: String,
        overview: String? = nil,
        posterPath: String? = nil,
        backdropPath: String? = nil,
        voteAverage: Double? = nil,
        releaseDate: String? = nil
    ) -> TMDBMediaItem {
        TMDBMediaItem(
            id: id,
            mediaType: type,
            title: title,
            overview: overview,
            posterPath: posterPath,
            backdropPath: backdropPath,
            voteAverage: voteAverage,
            releaseDate: releaseDate
        )
    }

    private func eventually(
        _ condition: @escaping @MainActor () -> Bool
    ) async -> Bool {
        for _ in 0..<100 {
            if condition() { return true }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return condition()
    }
}

@MainActor
private struct Harness {
    let container: ModelContainer
    let remote: WatchlistRemoteStub
    let store: WatchlistStore
}

private struct RemoteMutation: Equatable, Sendable {
    let inWatchlist: Bool
    let ref: MediaRef
}

/// Main-actor isolation makes the async-let movie/TV reads race-free under
/// Swift 6 while matching the app target's default actor isolation.
@MainActor
private final class WatchlistRemoteStub: WatchlistRemoteClient {
    private let lists: [TMDBMediaType: [TMDBMediaItem]]
    private let failMutations: Bool

    private(set) var mutations: [RemoteMutation] = []
    private(set) var listRequests: [TMDBMediaType] = []

    init(
        movies: [TMDBMediaItem],
        shows: [TMDBMediaItem],
        failMutations: Bool
    ) {
        lists = [.movie: movies, .tv: shows]
        self.failMutations = failMutations
    }

    func watchlistItems(_ type: TMDBMediaType) async throws -> [TMDBMediaItem] {
        listRequests.append(type)
        return lists[type, default: []]
    }

    func setWatchlist(_ inWatchlist: Bool, for ref: MediaRef) async throws {
        mutations.append(RemoteMutation(inWatchlist: inWatchlist, ref: ref))
        if failMutations {
            throw StubError.mutationRejected
        }
    }

    private enum StubError: Error {
        case mutationRejected
    }
}
