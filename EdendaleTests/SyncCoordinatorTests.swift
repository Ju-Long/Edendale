//
//  SyncCoordinatorTests.swift
//  EdendaleTests
//
//  Two devices sharing one in-memory location (SYNC-PROTOCOL.md §8):
//  each writes only its own file, reads the other's, converges, keeps
//  tombstones, maps episodes between the file's show/season/episode key
//  and the store's episode ID, and skips files whose stamp has not
//  changed. A failing location leaves the local store untouched.
//

import Foundation
import Testing
@testable import Edendale

@MainActor
@Suite(.serialized)
struct SyncCoordinatorTests {

    @Test func twoDevicesConvergeThroughTheirOwnFiles() async throws {
        let storage = MemorySyncStorage()
        let phone = SyncTestDevice(storage: storage)
        let tv = SyncTestDevice(storage: storage)
        let movie = Int.random(in: 1_000_000...9_000_000)

        phone.watchStore.update(WatchProgress(tmdbId: movie, mediaType: .movie, position: 0.25, watchedSeconds: 8000))
        phone.userMediaStore.setFavorite(true, for: MediaRef(id: movie, mediaType: .movie))
        phone.userMediaStore.setRating(8.5, for: MediaRef(id: movie, mediaType: .movie))
        #expect(await phone.sync())

        // The phone wrote only its own file.
        #expect(await storage.names == [phone.fileName])
        let data = try #require(await storage.contents(phone.fileName))
        let file = try SyncFileFormat.parse(data, fileName: phone.fileName, space: "Family")
        #expect(file.watchProgress.first?.position == 2000)
        #expect(file.watchProgress.first?.duration == 8000)
        #expect(file.userMedia.first?.rating == 8.5)
        #expect(file.device.id == phone.stamper.deviceID)

        #expect(await tv.sync())
        #expect(tv.watchStore.progress(for: movie, mediaType: .movie)?.position == 0.25)
        #expect(tv.watchStore.progress(for: movie, mediaType: .movie)?.deviceID == phone.stamper.deviceID)
        #expect(tv.userMediaStore.rating(for: MediaRef(id: movie, mediaType: .movie)) == 8.5)
        #expect(tv.userMediaStore.isFavorite(MediaRef(id: movie, mediaType: .movie)))
        #expect(await storage.names == [phone.fileName, tv.fileName].sorted())
        #expect(tv.coordinator.status.devices == [SyncStamper.deviceName])

        // The TV watches further; the phone picks it up, and a second read of
        // an unchanged file changes nothing.
        tv.watchStore.update(WatchProgress(tmdbId: movie, mediaType: .movie, position: 0.5, watchedSeconds: 8000))
        #expect(await tv.sync())
        let readsBefore = await storage.reads
        #expect(await phone.sync())
        #expect(phone.watchStore.progress(for: movie, mediaType: .movie)?.position == 0.5)
        #expect(phone.watchStore.progress(for: movie, mediaType: .movie)?.deviceID == tv.stamper.deviceID)
        let readsAfter = await storage.reads
        #expect(readsAfter == readsBefore + 1)
        let writesBefore = await storage.writes
        #expect(await phone.sync())
        #expect(await storage.reads == readsAfter)
        #expect(await storage.writes == writesBefore)
    }

    @Test func removalsTravelAsTombstonesAndWin() async throws {
        let storage = MemorySyncStorage()
        let phone = SyncTestDevice(storage: storage)
        let tv = SyncTestDevice(storage: storage)
        let movie = Int.random(in: 1_000_000...9_000_000)
        let ref = MediaRef(id: movie, mediaType: .movie)

        tv.watchStore.update(WatchProgress(tmdbId: movie, mediaType: .movie, position: 0.5, watchedSeconds: 100))
        tv.userMediaStore.setFavorite(true, for: ref)
        await tv.sync()
        await phone.sync()
        #expect(phone.watchStore.progress(for: movie, mediaType: .movie) != nil)
        #expect(phone.userMediaStore.isFavorite(ref))

        phone.watchStore.remove(tmdbId: movie, mediaType: .movie)
        phone.userMediaStore.setFavorite(false, for: ref)
        await phone.sync()
        let data = try #require(await storage.contents(phone.fileName))
        let file = try SyncFileFormat.parse(data, fileName: phone.fileName, space: "Family")
        #expect(file.watchProgress.first?.cleared == true)
        #expect(file.userMedia.first?.isTombstone == true)

        await tv.sync()
        #expect(tv.watchStore.progress(for: movie, mediaType: .movie) == nil)
        #expect(!tv.userMediaStore.isFavorite(ref))
        // The TV's stale copy never comes back: its stamp is older.
        await tv.sync()
        await phone.sync()
        #expect(phone.watchStore.progress(for: movie, mediaType: .movie) == nil)
    }

    @Test func episodesAreKeyedByShowAndResolvedToTheLocalID() async throws {
        let storage = MemorySyncStorage()
        let phone = SyncTestDevice(storage: storage)
        let show = Int.random(in: 1_000_000...9_000_000)
        let episodeID = show + 1
        phone.watchStore.update(WatchProgress(
            tmdbId: episodeID, mediaType: .episode, position: 0.4, watchedSeconds: 2400,
            showTmdbId: show, seasonNumber: 1, episodeNumber: 2
        ))
        await phone.sync()
        let data = try #require(await storage.contents(phone.fileName))
        let file = try SyncFileFormat.parse(data, fileName: phone.fileName, space: "Family")
        #expect(file.watchProgress.first?.key == .episode(show: show, season: 1, episode: 2))

        // A device that cannot resolve the episode keeps the record pending,
        // still writes it out, and applies it once it can.
        let tv = SyncTestDevice(storage: storage)
        await tv.sync()
        #expect(tv.watchStore.progress(for: episodeID, mediaType: .episode) == nil)
        let tvData = try #require(await storage.contents(tv.fileName))
        let tvFile = try SyncFileFormat.parse(tvData, fileName: tv.fileName, space: "Family")
        #expect(tvFile.watchProgress.first?.key == .episode(show: show, season: 1, episode: 2))
        #expect(tvFile.watchProgress.first?.device == phone.stamper.deviceID)

        // Once the TV holds its own row for that episode (it played it), the
        // pending record resolves through the store's show/season/episode.
        tv.watchStore.update(WatchProgress(
            tmdbId: episodeID, mediaType: .episode, position: 0.1, watchedSeconds: 2400,
            showTmdbId: show, seasonNumber: 1, episodeNumber: 2, lastWatchedAt: Date(timeIntervalSince1970: 1_700_000_000)
        ))
        // The TV's own write is newer, so it wins; the phone then adopts it.
        await tv.sync()
        await phone.sync()
        #expect(phone.watchStore.progress(for: episodeID, mediaType: .episode)?.position == 0.1)
    }

    @Test func aFileFromAnotherPlatformIsMergedAndRewrittenVerbatim() async throws {
        let storage = MemorySyncStorage()
        let device = "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b"
        let movie = Int.random(in: 1_000_000...9_000_000)
        let planted = """
        {"format":1,"space":"Family","device":{"id":"\(device)","name":"Pixel 9","platform":"android","app":"27.1"},
         "updateTime":1762041600000,
         "watchProgress":[{"type":"movie","tmdbId":\(movie),"position":600,"duration":6000,"completed":false,"device":"\(device)","updateTime":1762041600000}],
         "userMedia":[{"type":"movie","tmdbId":\(movie),"favourite":true,"watchlist":true,"rating":null,"review":"Good.","device":"\(device)","updateTime":1762041500000}]}
        """
        await storage.plant("\(device).json", Data(planted.utf8))
        let mac = SyncTestDevice(storage: storage)
        var mirrored: [(MediaRef, Bool)] = []
        mac.userMediaStore.onWatchlistSynced = { mirrored.append(($0, $1)) }
        #expect(await mac.sync())
        let ref = MediaRef(id: movie, mediaType: .movie)
        #expect(mac.watchStore.progress(for: movie, mediaType: .movie)?.position == 0.1)
        #expect(mac.userMediaStore.isFavorite(ref))
        #expect(mac.userMediaStore.state(for: ref).isInWatchlist)
        #expect(mac.userMediaStore.state(for: ref).review == "Good.")
        #expect(mirrored.map(\.1) == [true])
        #expect(mac.coordinator.status.devices == ["Pixel 9"])
        // The record keeps the Android device's stamp when written back out.
        let data = try #require(await storage.contents(mac.fileName))
        let file = try SyncFileFormat.parse(data, fileName: mac.fileName, space: "Family")
        #expect(file.watchProgress.first?.device == device)
        #expect(file.watchProgress.first?.updateTime == 1_762_041_600_000)
        #expect(file.userMedia.first?.review == "Good.")
        // And this device's next local write orders after it.
        #expect(mac.stamper.highWater >= 1_762_041_600_000)
        mac.userMediaStore.setRating(9, for: ref)
        #expect(mac.userMediaStore.state(for: ref).updateTime > 1_762_041_600_000)
    }

    @Test func skippedFilesAndFailuresLeaveTheStoreAlone() async throws {
        let storage = MemorySyncStorage()
        let device = "0c0c0c0c-0c0c-4c0c-8c0c-0c0c0c0c0c0c"
        await storage.plant("\(device).json", Data(#"{"format":2,"space":"Family","device":{"id":"\#(device)","name":"Future","platform":"xbox","app":"28"},"watchProgress":[],"userMedia":[]}"#.utf8))
        await storage.plant("notes.json", Data("{}".utf8))
        let mac = SyncTestDevice(storage: storage)
        let movie = Int.random(in: 1_000_000...9_000_000)
        mac.watchStore.update(WatchProgress(tmdbId: movie, mediaType: .movie, position: 0.3, watchedSeconds: 10))
        #expect(await mac.sync())
        #expect(mac.coordinator.status.warnings == ["Update Edendale to sync with another device."])
        #expect(mac.watchStore.progress(for: movie, mediaType: .movie)?.position == 0.3)

        await storage.fail(with: SyncStorageError.failed("reach the server"))
        #expect(await mac.sync() == false)
        #expect(mac.coordinator.status.lastError != nil)
        #expect(mac.watchStore.progress(for: movie, mediaType: .movie)?.position == 0.3)
        await storage.fail(with: nil)
        #expect(await mac.sync())
        #expect(mac.coordinator.status.lastError == nil)
    }

    @Test func switchingLocationKeepsTheStoreAndOffersToDeleteTheOldFile() async throws {
        let storage = MemorySyncStorage()
        let mac = SyncTestDevice(storage: storage)
        let movie = Int.random(in: 1_000_000...9_000_000)
        mac.watchStore.update(WatchProgress(tmdbId: movie, mediaType: .movie, position: 0.3, watchedSeconds: 10))
        await mac.sync()
        #expect(await storage.names == [mac.fileName])

        mac.coordinator.setLocation(SyncLocation(provider: .folder, space: "Other", folderPath: "/memory"), startSync: false)
        #expect(mac.coordinator.previousLocation?.space == "Family")
        #expect(mac.watchStore.progress(for: movie, mediaType: .movie)?.position == 0.3)
        try await mac.coordinator.deleteFileFromPreviousLocation()
        #expect(mac.coordinator.previousLocation == nil)
        #expect(await storage.names.isEmpty)

        mac.coordinator.setLocation(nil, startSync: false)
        #expect(!mac.coordinator.isOn)
        #expect(mac.coordinator.previousLocation?.space == "Other")
    }

    @Test func localWritesAreStampedMonotonically() async throws {
        let storage = MemorySyncStorage()
        let mac = SyncTestDevice(storage: storage)
        let movie = Int.random(in: 1_000_000...9_000_000)
        mac.stamper.observe(4_000_000_000_000)
        mac.watchStore.update(WatchProgress(tmdbId: movie, mediaType: .movie, position: 0.3, watchedSeconds: 10))
        #expect(mac.watchStore.progress(for: movie, mediaType: .movie)?.updateTime == 4_000_000_000_001)
        #expect(mac.stamper.highWater == 4_000_000_000_001)
        #expect(mac.watchStore.progress(for: movie, mediaType: .movie)?.deviceID == mac.stamper.deviceID)
        #expect(SyncFileFormat.isDeviceID(mac.stamper.deviceID))
    }
}
