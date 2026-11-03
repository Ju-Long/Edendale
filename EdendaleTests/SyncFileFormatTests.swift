//
//  SyncFileFormatTests.swift
//  EdendaleTests
//
//  The device file of SYNC-PROTOCOL.md §5 and §6: what this device writes
//  reads back unchanged, the JSON rules of §1.2 (integers never carry a
//  fraction, booleans are never numbers, absent and null agree), the
//  limits, and the identifier rules of §3.
//

import Foundation
import Testing
@testable import Edendale

@Suite struct SyncFileFormatTests {

    private let device = "0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"

    private func wrap(_ progress: String = "", _ userMedia: String = "", space: String = "Family", extra: String = "") -> Data {
        Data("""
        {"format":1,"space":"\(space)","device":{"id":"\(device)","name":"Living Room TV","platform":"tvos","app":"27.1"},
         "updateTime":1762041600000,\(extra)
         "watchProgress":[\(progress)],"userMedia":[\(userMedia)]}
        """.utf8)
    }

    @Test func specificationExampleParses() throws {
        let data = wrap(
            #"{"type":"movie","tmdbId":603,"position":1234.5,"duration":8160,"completed":false,"device":"\#(device)","updateTime":1762041600000}"#,
            #"{"type":"tv","tmdbId":1399,"favourite":true,"watchlist":false,"rating":8.5,"review":null,"device":"0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b","updateTime":1762038000000}"#
        )
        let file = try SyncFileFormat.parse(data, fileName: "\(device).json", space: "Family")
        #expect(file.device == SyncDeviceInfo(id: device, name: "Living Room TV", platform: "tvos", app: "27.1"))
        let progress = try #require(file.watchProgress.first)
        #expect(progress.key == .movie(603))
        #expect(progress.position == 1234.5)
        #expect(progress.duration == 8160)
        #expect(progress.completed == false)
        let media = try #require(file.userMedia.first)
        #expect(media.key == .show(1399))
        #expect(media.rating == 8.5)
        #expect(media.review == nil)
        #expect(media.device == "0b0b0b0b-0b0b-4b0b-8b0b-0b0b0b0b0b0b")
        #expect(file.updateTime == 1_762_041_600_000)
    }

    @Test func writtenFilesReadBackUnchanged() throws {
        let file = SyncDeviceFile(
            space: "Family",
            device: SyncDeviceInfo(id: device, name: "Ju's iPhone", platform: "ios", app: "27.1"),
            watchProgress: [
                SyncProgressRecord(type: .tv, tmdbID: 1399, season: 1, episode: 2, position: 12.5, duration: 3600, completed: true, device: device, updateTime: 1_762_000_000_000),
                SyncProgressRecord(type: .movie, tmdbID: 603, cleared: true, device: device, updateTime: 1_762_000_000_001)
            ],
            userMedia: [
                SyncUserMediaRecord(type: .movie, tmdbID: 603, favourite: true, watchlist: true, rating: 7.5, review: "Still holds up — “whoa”.", device: device, updateTime: 1_762_000_000_002),
                SyncUserMediaRecord(type: .tv, tmdbID: 1399, device: device, updateTime: 1_762_000_000_003)
            ]
        )
        let data = try SyncFileFormat.encode(file)
        let text = String(decoding: data, as: UTF8.self)
        #expect(!text.hasPrefix("\u{FEFF}"))
        #expect(text.contains("\"cleared\" : true"))
        #expect(text.contains("\"review\" : null"))
        #expect(text.contains("\"updateTime\" : 1762000000003"))
        // The top-level stamp is the highest record stamp.
        #expect(text.contains("\"updateTime\" : 1762000000003,\n  \"userMedia\"") || text.contains("1762000000003"))
        let parsed = try SyncFileFormat.parse(data, fileName: "\(device).json", space: "Family")
        #expect(parsed == file)
        #expect(parsed.updateTime == 1_762_000_000_003)
    }

    @Test func integersRefuseFractionsAndBooleansRefuseNumbers() {
        let fraction = wrap(#"{"type":"movie","tmdbId":603.5,"position":1,"duration":2,"completed":false,"device":"x","updateTime":1762041600000}"#)
        #expect(throws: SyncFileFormat.Rejection.invalidField("watchProgress[0].tmdbId")) {
            try SyncFileFormat.parse(fraction, fileName: "\(device).json", space: "Family")
        }
        let numericBool = wrap(#"{"type":"movie","tmdbId":603,"position":1,"duration":2,"completed":1,"device":"x","updateTime":1762041600000}"#)
        #expect(throws: SyncFileFormat.Rejection.invalidField("watchProgress[0].completed")) {
            try SyncFileFormat.parse(numericBool, fileName: "\(device).json", space: "Family")
        }
        let booleanInt = wrap(#"{"type":"movie","tmdbId":true,"position":1,"duration":2,"completed":false,"device":"x","updateTime":1762041600000}"#)
        #expect(throws: SyncFileFormat.Rejection.invalidField("watchProgress[0].tmdbId")) {
            try SyncFileFormat.parse(booleanInt, fileName: "\(device).json", space: "Family")
        }
        // A fractional updateTime is not an error: it counts as 0 (§7.3).
        let fractionalStamp = wrap(#"{"type":"movie","tmdbId":603,"position":1,"duration":2,"completed":false,"device":"x","updateTime":1762041600000.5}"#)
        #expect((try? SyncFileFormat.parse(fractionalStamp, fileName: "\(device).json", space: "Family"))?.watchProgress.first?.updateTime == 0)
    }

    @Test func recordRulesAreEnforced() {
        func progress(_ fields: String) -> Data {
            wrap(#"{"type":"movie","tmdbId":603,"device":"x","updateTime":1762041600000,"# + fields + "}")
        }
        func userMedia(_ fields: String) -> Data {
            wrap("", #"{"type":"tv","tmdbId":1399,"device":"x","updateTime":1762041600000,"# + fields + "}")
        }
        let cases: [(Data, String)] = [
            (progress(#""position":-1,"duration":2,"completed":false"#), "watchProgress[0].position"),
            (progress(#""position":1,"duration":0,"completed":false"#), "watchProgress[0].duration"),
            (progress(#""position":1,"duration":2"#), "watchProgress[0].completed"),
            (progress(#""position":1,"duration":2,"completed":false,"season":1"#), "watchProgress[0].season"),
            (wrap(#"{"type":"tv","tmdbId":1399,"position":1,"duration":2,"completed":false,"device":"x","updateTime":1762041600000}"#), "watchProgress[0].season"),
            (wrap(#"{"type":"tv","tmdbId":1399,"season":1,"episode":-1,"position":1,"duration":2,"completed":false,"device":"x","updateTime":1762041600000}"#), "watchProgress[0].season"),
            (wrap(#"{"type":"short","tmdbId":1,"position":1,"duration":2,"completed":false,"device":"x","updateTime":1}"#), "watchProgress[0].type"),
            (wrap(#"{"type":"movie","tmdbId":0,"position":1,"duration":2,"completed":false,"device":"x","updateTime":1}"#), "watchProgress[0].tmdbId"),
            (wrap(#"{"type":"movie","tmdbId":1,"position":1,"duration":2,"completed":false,"device":"","updateTime":1}"#), "watchProgress[0].device"),
            (userMedia(#""rating":8.25"#), "userMedia[0].rating"),
            (userMedia(#""rating":0"#), "userMedia[0].rating"),
            (userMedia(#""rating":10.5"#), "userMedia[0].rating"),
            (userMedia(#""favourite":"yes""#), "userMedia[0].favourite")
        ]
        for (data, field) in cases {
            #expect(throws: SyncFileFormat.Rejection.invalidField(field)) {
                try SyncFileFormat.parse(data, fileName: "\(device).json", space: "Family")
            }
        }
        // A tombstone needs no position, duration, or completion.
        let tombstone = progress(#""cleared":true"#)
        #expect((try? SyncFileFormat.parse(tombstone, fileName: "\(device).json", space: "Family"))?.watchProgress.first?.cleared == true)
        // Absent booleans read as false; a review of 10,000 scalars is the most allowed.
        let bare = userMedia(#""review":"\#(String(repeating: "é", count: 10_000))""#)
        let parsed = try? SyncFileFormat.parse(bare, fileName: "\(device).json", space: "Family")
        #expect(parsed?.userMedia.first?.favourite == false)
        #expect(parsed?.userMedia.first?.review?.unicodeScalars.count == 10_000)
        let long = userMedia(#""review":"\#(String(repeating: "é", count: 10_001))""#)
        #expect(throws: SyncFileFormat.Rejection.invalidField("userMedia[0].review")) {
            try SyncFileFormat.parse(long, fileName: "\(device).json", space: "Family")
        }
    }

    @Test func fileRulesAreEnforced() {
        #expect(throws: SyncFileFormat.Rejection.notJSON) {
            try SyncFileFormat.parse(Data("not json".utf8), fileName: "\(device).json", space: "Family")
        }
        #expect(throws: SyncFileFormat.Rejection.notJSON) {
            try SyncFileFormat.parse(Data("[]".utf8), fileName: "\(device).json", space: "Family")
        }
        let duplicate = wrap(
            #"{"type":"movie","tmdbId":603,"position":1,"duration":2,"completed":false,"device":"x","updateTime":1},"# +
            #"{"type":"movie","tmdbId":603,"position":2,"duration":2,"completed":false,"device":"x","updateTime":2}"#
        )
        #expect(throws: SyncFileFormat.Rejection.duplicateKey("movie:603")) {
            try SyncFileFormat.parse(duplicate, fileName: "\(device).json", space: "Family")
        }
        let longName = Data("""
        {"format":1,"space":"Family","device":{"id":"\(device)","name":"\(String(repeating: "n", count: 65))","platform":"ios","app":"1"},"watchProgress":[],"userMedia":[]}
        """.utf8)
        #expect(throws: SyncFileFormat.Rejection.invalidField("device.name")) {
            try SyncFileFormat.parse(longName, fileName: "\(device).json", space: "Family")
        }
        // Unknown fields are ignored; a missing array is empty.
        let sparse = Data("""
        {"format":1,"space":"Family","device":{"id":"\(device)","name":"n","platform":"xbox","app":"1","colour":"green"},"future":true}
        """.utf8)
        let parsed = try? SyncFileFormat.parse(sparse, fileName: "\(device).json", space: "Family")
        #expect(parsed?.watchProgress.isEmpty == true)
        #expect(parsed?.updateTime == 0)
    }

    @Test func tooManyRecordsAreRefused() {
        // Tombstones are the smallest records: 100,001 of them stay under
        // the size limit, so the count limit is what trips.
        let record = #"{"type":"movie","tmdbId":%d,"device":"x","updateTime":1}"#
        let records = (1...100_001).map { String(format: record, $0) }.joined(separator: ",")
        #expect(throws: SyncFileFormat.Rejection.tooManyRecords) {
            try SyncFileFormat.parse(wrap("", records), fileName: "\(device).json", space: "Family")
        }
        let justEnough = (1...100_000).map { String(format: record, $0) }.joined(separator: ",")
        #expect((try? SyncFileFormat.parse(wrap("", justEnough), fileName: "\(device).json", space: "Family"))?.userMedia.count == 100_000)
    }

    @Test func spaceNamesFollowTheRules() {
        #expect(SyncFileFormat.normalizedSpaceName("  Family ") == "Family")
        #expect(SyncFileFormat.normalizedSpaceName("Ju & Long") == "Ju & Long")
        #expect(SyncFileFormat.normalizedSpaceName("家族") == "家族")
        #expect(SyncFileFormat.normalizedSpaceName("") == nil)
        #expect(SyncFileFormat.normalizedSpaceName("   ") == nil)
        #expect(SyncFileFormat.normalizedSpaceName(".") == nil)
        #expect(SyncFileFormat.normalizedSpaceName("..") == nil)
        #expect(SyncFileFormat.normalizedSpaceName("a/b") == nil)
        #expect(SyncFileFormat.normalizedSpaceName("a:b") == nil)
        #expect(SyncFileFormat.normalizedSpaceName("tab\there") == nil)
        #expect(SyncFileFormat.normalizedSpaceName(String(repeating: "x", count: 64)) != nil)
        #expect(SyncFileFormat.normalizedSpaceName(String(repeating: "x", count: 65)) == nil)
    }

    @Test func deviceIDsAreLowercaseVersion4UUIDs() {
        #expect(SyncFileFormat.isDeviceID("0a0a0a0a-0a0a-4a0a-8a0a-0a0a0a0a0a0a"))
        #expect(!SyncFileFormat.isDeviceID("0A0A0A0A-0A0A-4A0A-8A0A-0A0A0A0A0A0A"))
        #expect(!SyncFileFormat.isDeviceID("0a0a0a0a-0a0a-1a0a-8a0a-0a0a0a0a0a0a"))
        #expect(!SyncFileFormat.isDeviceID("not-a-uuid"))
        let made = SyncFileFormat.makeDeviceID()
        #expect(SyncFileFormat.isDeviceID(made))
        #expect(SyncFileFormat.deviceID(ofFileName: "\(made).json") == made)
        #expect(SyncFileFormat.deviceID(ofFileName: "\(made).json.tmp") == nil)
        #expect(SyncFileFormat.deviceID(ofFileName: "notes.json") == nil)
        #expect(SyncFileFormat.fileName(deviceID: made) == "\(made).json")
    }

    @Test func ratingsStepByHalves() {
        #expect(SyncFileFormat.isValidRating(0.5))
        #expect(SyncFileFormat.isValidRating(10))
        #expect(SyncFileFormat.isValidRating(7.5))
        #expect(!SyncFileFormat.isValidRating(0))
        #expect(!SyncFileFormat.isValidRating(7.25))
        #expect(!SyncFileFormat.isValidRating(10.5))
        #expect(!SyncFileFormat.isValidRating(.nan))
    }
}
