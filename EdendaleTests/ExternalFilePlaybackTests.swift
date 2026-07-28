//
//  ExternalFilePlaybackTests.swift
//  EdendaleTests
//

import Foundation
import SwiftData
import Testing
@testable import Edendale

@MainActor
struct ExternalFilePlaybackTests {
    @Test func supportedFileCreatesTransientPlaybackItem() async throws {
        let library = try makeLibrary()
        let fileURL = try makeTemporaryFile(named: "Direct Open.MKV")
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let item = await library.preparePlayback(fileURL: fileURL)

        #expect(item.url == fileURL)
        #expect(item.errorMessage == nil)
        #expect(item.movie == nil)
        #expect(item.episode == nil)
        #expect(item.displayTitle == "Direct Open.MKV")
    }

    @Test func unsupportedFileReturnsVisibleFailure() async throws {
        let library = try makeLibrary()
        let fileURL = try makeTemporaryFile(named: "notes.txt")
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let item = await library.preparePlayback(fileURL: fileURL)

        #expect(item.url == nil)
        #expect(item.errorMessage?.contains("isn't supported") == true)
    }

    @Test func nonFileURLReturnsVisibleFailure() async throws {
        let library = try makeLibrary()

        let item = await library.preparePlayback(
            fileURL: URL(string: "https://example.com/video.mp4")!
        )

        #expect(item.url == nil)
        #expect(item.errorMessage == "Edendale can only open local video files.")
    }

    private func makeLibrary() throws -> LibraryController {
        let schema = Schema([
            VideoFolder.self,
            Movie.self,
            TVShow.self,
            Episode.self
        ])
        let configuration = ModelConfiguration(
            schema: schema,
            isStoredInMemoryOnly: true,
            cloudKitDatabase: .none
        )
        let container = try ModelContainer(for: schema, configurations: [configuration])
        return LibraryController(modelContext: container.mainContext)
    }

    private func makeTemporaryFile(named name: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("ExternalFilePlaybackTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        let fileURL = directory.appendingPathComponent(name)
        try Data().write(to: fileURL)
        return fileURL
    }
}
