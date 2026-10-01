//
//  PlaybackSourcesTests.swift
//  EdendaleTests
//
//  The detail page's Play From menu: copies of one title across sources,
//  their order, the copy Play picks when a source is unreachable, a show's
//  episodes merged across its copies, and how each copy is described.
//

import Foundation
import SwiftData
import Testing
@testable import Edendale

@MainActor
struct PlaybackSourcesTests {

    /// A context must not outlive its container, so tests hold this.
    private let container: ModelContainer

    init() throws {
        let schema = Schema([VideoFolder.self, Movie.self, TVShow.self, Episode.self])
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, cloudKitDatabase: .none)
        container = try ModelContainer(for: schema, configurations: [configuration])
    }

    private func makeContext() throws -> ModelContext {
        container.mainContext
    }

    private func folder(_ name: String, _ kind: MediaSourceKind, in context: ModelContext) -> VideoFolder {
        let folder = VideoFolder(name: name, folderPath: "/\(name)", sourceKind: kind)
        context.insert(folder)
        return folder
    }

    private func movie(_ path: String, in folder: VideoFolder, context: ModelContext) -> Movie {
        let movie = Movie(localTitle: "Heat", filePath: path)
        movie.tmdbId = 949
        context.insert(movie)
        movie.folder = folder
        return movie
    }

    @Test func ordersThePagesCopyFirstThenLocalThenByName() throws {
        let context = try makeContext()
        let page = movie("smb://nas/films/Heat.2160p.mkv", in: folder("NAS", .smb, in: context), context: context)
        let zeta = movie("sftp://zeta/Heat.mkv", in: folder("Zeta", .sftp, in: context), context: context)
        let alpha = movie("nfs://alpha/Heat.mkv", in: folder("Alpha", .nfs, in: context), context: context)
        let local = movie("/Movies/Heat.mkv", in: folder("Movies", .local, in: context), context: context)

        let ordered = PlaybackSources.order(primary: page, others: [zeta, alpha, local], folder: \.folder)
        #expect(ordered.map(\.filePath) == [page, local, alpha, zeta].map(\.filePath))
    }

    @Test func playSkipsUnavailableSources() {
        #expect(PlaybackSources.preferred(["nas", "laptop"]) { $0 == "nas" } == "laptop")
        #expect(PlaybackSources.preferred(["nas", "laptop"]) { _ in false } == "nas")
        // Every source unreachable: still try the page's own.
        #expect(PlaybackSources.preferred(["nas", "laptop"]) { _ in true } == "nas")
        #expect(PlaybackSources.preferred([String]()) { _ in false } == nil)
    }

    @Test func mergesAShowsEpisodesAcrossItsCopies() throws {
        let context = try makeContext()
        func show(_ folder: VideoFolder, episodes: [(Int, Int)]) -> TVShow {
            let show = TVShow(name: "Severance")
            show.tmdbId = 95396
            context.insert(show)
            show.folder = folder
            for (season, number) in episodes {
                let episode = Episode(
                    localTitle: "S\(season)E\(number)",
                    filePath: "\(folder.folderPath)/S\(season)E\(number).mkv",
                    seasonNumber: season,
                    episodeNumber: number
                )
                context.insert(episode)
                episode.show = show
            }
            return show
        }
        let nas = show(folder("NAS", .smb, in: context), episodes: [(1, 1), (1, 2)])
        let laptop = show(folder("Laptop", .local, in: context), episodes: [(1, 2), (2, 1), (1, 3)])

        let slots = PlaybackSources.episodeSlots(primary: nas, others: [laptop])
        #expect(slots.map(\.id) == ["1-1", "1-2", "1-3", "2-1"])
        // Episodes only the other copy has are on the page too.
        #expect(slots[2].copies.map(\.filePath) == ["/Laptop/S1E3.mkv"])
        // A shared episode lists the page's copy first.
        #expect(slots[1].copies.map(\.filePath) == ["/NAS/S1E2.mkv", "/Laptop/S1E2.mkv"])
        #expect(slots[1].primary.filePath == "/NAS/S1E2.mkv")
    }

    @Test func describesACopyByKindAndFileName() throws {
        let context = try makeContext()
        let nas = folder("NAS", .smb, in: context)
        #expect(PlaybackSources.detail(folder: nas, filePath: "smb://nas/films/Heat%20(1995)%202160p.mkv")
            == "SMB · Heat (1995) 2160p.mkv")
        #expect(PlaybackSources.fileName(of: "/Users/me/Movies/Heat (1995).mkv") == "Heat (1995).mkv")
        #expect(PlaybackSources.detail(folder: nil, filePath: "/Movies/Heat.mkv")
            == "\(MediaSourceKind.local.displayName) · Heat.mkv")
    }
}
