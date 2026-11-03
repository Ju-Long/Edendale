//
//  SyncRecords.swift
//  Edendale
//
//  The device file of SYNC-PROTOCOL.md (main, 915b40d): one JSON file per
//  device in `Edendale/<space>/<device-id>.json`, holding that device's
//  whole merged watch state. Only the format lives here: the record shapes
//  (§6), how a file is validated on read (§5), and how this device's file
//  is written. Merging is SyncLedger; reaching a location is SyncStorage.
//
//  Reading goes through JSONSerialization rather than Codable so the
//  protocol's number rules hold exactly: an integer field with a fraction
//  (`603.5`, and `603.0` too) is refused, `1` is never a boolean, and an
//  invalid `updateTime` counts as 0 instead of failing the record (§7.3).
//  Nothing here logs record contents.
//

import Foundation

// MARK: - Keys

nonisolated enum SyncMediaType: String, Sendable, Codable {
    case movie
    case tv
}

/// What identifies a record: `type:tmdbId` for a movie or a show, with
/// `:season:episode` for an episode's progress.
nonisolated struct SyncRecordKey: Hashable, Sendable, Comparable, CustomStringConvertible {
    let type: SyncMediaType
    let tmdbID: Int
    let season: Int?
    let episode: Int?

    init(type: SyncMediaType, tmdbID: Int, season: Int? = nil, episode: Int? = nil) {
        self.type = type
        self.tmdbID = tmdbID
        self.season = season
        self.episode = episode
    }

    static func movie(_ tmdbID: Int) -> SyncRecordKey { SyncRecordKey(type: .movie, tmdbID: tmdbID) }
    static func show(_ tmdbID: Int) -> SyncRecordKey { SyncRecordKey(type: .tv, tmdbID: tmdbID) }
    static func episode(show tmdbID: Int, season: Int, episode: Int) -> SyncRecordKey {
        SyncRecordKey(type: .tv, tmdbID: tmdbID, season: season, episode: episode)
    }

    var description: String {
        var text = "\(type.rawValue):\(tmdbID)"
        if let season, let episode { text += ":\(season):\(episode)" }
        return text
    }

    static func < (lhs: SyncRecordKey, rhs: SyncRecordKey) -> Bool {
        lhs.description < rhs.description
    }
}

// MARK: - Records

/// §6.1. A tombstone has `cleared` set and no position, duration, or
/// completion.
nonisolated struct SyncProgressRecord: Equatable, Sendable, Codable {
    var type: SyncMediaType
    var tmdbID: Int
    var season: Int?
    var episode: Int?
    /// Seconds; absent in a tombstone.
    var position: Double?
    /// Seconds; absent in a tombstone.
    var duration: Double?
    var completed: Bool?
    var cleared: Bool
    var device: String
    var updateTime: Int64

    init(
        type: SyncMediaType,
        tmdbID: Int,
        season: Int? = nil,
        episode: Int? = nil,
        position: Double? = nil,
        duration: Double? = nil,
        completed: Bool? = nil,
        cleared: Bool = false,
        device: String,
        updateTime: Int64
    ) {
        self.type = type
        self.tmdbID = tmdbID
        self.season = season
        self.episode = episode
        self.position = position
        self.duration = duration
        self.completed = completed
        self.cleared = cleared
        self.device = device
        self.updateTime = updateTime
    }

    var key: SyncRecordKey { SyncRecordKey(type: type, tmdbID: tmdbID, season: season, episode: episode) }
    var stamp: SyncStamp { SyncStamp(updateTime: updateTime, device: device) }
}

/// §6.2. The tombstone is the record with everything false or null; it is
/// kept, never dropped.
nonisolated struct SyncUserMediaRecord: Equatable, Sendable, Codable {
    var type: SyncMediaType
    var tmdbID: Int
    var favourite: Bool
    var watchlist: Bool
    /// 0.5…10 in steps of 0.5.
    var rating: Double?
    /// At most 10,000 Unicode scalars.
    var review: String?
    var device: String
    var updateTime: Int64

    init(
        type: SyncMediaType,
        tmdbID: Int,
        favourite: Bool = false,
        watchlist: Bool = false,
        rating: Double? = nil,
        review: String? = nil,
        device: String,
        updateTime: Int64
    ) {
        self.type = type
        self.tmdbID = tmdbID
        self.favourite = favourite
        self.watchlist = watchlist
        self.rating = rating
        self.review = review
        self.device = device
        self.updateTime = updateTime
    }

    var key: SyncRecordKey { SyncRecordKey(type: type, tmdbID: tmdbID) }
    var stamp: SyncStamp { SyncStamp(updateTime: updateTime, device: device) }
    var isTombstone: Bool { !favourite && !watchlist && rating == nil && review == nil }
}

/// The `updateTime` and writing device of a record, ordered as §7.1 orders
/// records: higher time wins, and on a tie the lexically smaller device ID.
nonisolated struct SyncStamp: Equatable, Sendable {
    var updateTime: Int64
    var device: String

    /// Whether a record with this stamp replaces one with `other`, or any
    /// record at all when `other` is nil.
    func wins(over other: SyncStamp?) -> Bool {
        guard let other else { return true }
        if updateTime != other.updateTime { return updateTime > other.updateTime }
        return device.utf8.lexicographicallyPrecedes(other.device.utf8)
    }
}

/// §5, the `device` object.
nonisolated struct SyncDeviceInfo: Equatable, Sendable {
    var id: String
    var name: String
    var platform: String
    var app: String
}

/// One device file, as read or as about to be written.
nonisolated struct SyncDeviceFile: Equatable, Sendable {
    static let format = 1

    var space: String
    var device: SyncDeviceInfo
    var watchProgress: [SyncProgressRecord]
    var userMedia: [SyncUserMediaRecord]

    /// The highest `updateTime` of any record, or 0 (§5).
    var updateTime: Int64 {
        max(watchProgress.map(\.updateTime).max() ?? 0, userMedia.map(\.updateTime).max() ?? 0)
    }
}

// MARK: - Limits and validation

nonisolated enum SyncFileFormat {
    /// Files past this size are skipped unread (§5).
    static let maxFileBytes = 8 * 1024 * 1024
    static let maxRecords = 100_000
    static let maxReviewScalars = 10_000
    static let maxDeviceNameCharacters = 64
    /// 2000-01-01T00:00:00Z; stamps below it count as 0 (§7.3).
    static let minimumStamp: Int64 = 946_684_800_000
    static let fileExtension = "json"
    static let rootFolderName = "Edendale"

    /// Space names: 1–64 scalars after trimming, no path or shell
    /// characters, not `.` or `..` (§3).
    static func normalizedSpaceName(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        let count = trimmed.unicodeScalars.count
        guard count >= 1, count <= 64, trimmed != ".", trimmed != ".." else { return nil }
        let forbidden: Set<Unicode.Scalar> = ["/", "\\", ":", "*", "?", "\"", "<", ">", "|"]
        for scalar in trimmed.unicodeScalars {
            if forbidden.contains(scalar) || scalar.properties.generalCategory == .control { return nil }
        }
        return trimmed
    }

    /// A lowercase hyphenated version-4 UUID (§3).
    static func isDeviceID(_ string: String) -> Bool {
        guard string.count == 36, let uuid = UUID(uuidString: string),
              uuid.uuidString.lowercased() == string
        else { return false }
        let bytes = uuid.uuid
        return bytes.6 >> 4 == 4 && bytes.8 >> 6 == 0b10
    }

    static func makeDeviceID() -> String {
        UUID().uuidString.lowercased()
    }

    /// `<device-id>.json`.
    static func fileName(deviceID: String) -> String {
        "\(deviceID).\(fileExtension)"
    }

    /// The device ID a space-folder entry names, or nil for anything that
    /// is not `<uuid>.json`.
    static func deviceID(ofFileName name: String) -> String? {
        guard name.hasSuffix("." + fileExtension) else { return nil }
        let id = String(name.dropLast(fileExtension.count + 1))
        return isDeviceID(id) ? id : nil
    }

    /// Why a file was skipped. Never carries record contents.
    enum Rejection: Error, Equatable, Sendable {
        case tooLarge
        case notJSON
        /// `format` above 1: the user updates Edendale to sync with the device.
        case newerFormat(Int)
        case spaceMismatch
        case deviceIDMismatch
        case tooManyRecords
        case duplicateKey(String)
        /// A field broke a rule in §5 or §6; the path names it.
        case invalidField(String)
    }

    // MARK: Reading

    /// Validates one device file (§5, §6). `space` is the folder it was
    /// listed in and `fileName` its name there.
    static func parse(_ data: Data, fileName: String, space: String) throws(Rejection) -> SyncDeviceFile {
        guard data.count <= maxFileBytes else { throw .tooLarge }
        guard let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else { throw .notJSON }
        let file = JSONObject(root, path: "")

        guard let format = try file.integer("format") else { throw .invalidField("format") }
        if format > SyncDeviceFile.format { throw .newerFormat(Int(clamping: format)) }
        guard format == SyncDeviceFile.format else { throw .invalidField("format") }

        guard let fileSpace = try file.string("space") else { throw .invalidField("space") }
        guard fileSpace == space else { throw .spaceMismatch }

        guard let device = try file.object("device") else { throw .invalidField("device") }
        guard let id = try device.string("id") else { throw .invalidField("device.id") }
        guard id == deviceID(ofFileName: fileName) else { throw .deviceIDMismatch }
        guard let name = try device.string("name"), !name.isEmpty, name.count <= maxDeviceNameCharacters else {
            throw .invalidField("device.name")
        }
        guard let platform = try device.string("platform") else { throw .invalidField("device.platform") }
        let app = try device.string("app") ?? ""

        let progressItems = try file.array("watchProgress")
        let userMediaItems = try file.array("userMedia")
        guard progressItems.count + userMediaItems.count <= maxRecords else { throw .tooManyRecords }

        var progress: [SyncProgressRecord] = []
        var progressKeys: Set<SyncRecordKey> = []
        for (index, item) in progressItems.enumerated() {
            let record = try parseProgress(JSONObject(item, path: "watchProgress[\(index)]"))
            guard progressKeys.insert(record.key).inserted else { throw .duplicateKey(record.key.description) }
            progress.append(record)
        }
        var userMedia: [SyncUserMediaRecord] = []
        var userMediaKeys: Set<SyncRecordKey> = []
        for (index, item) in userMediaItems.enumerated() {
            let record = try parseUserMedia(JSONObject(item, path: "userMedia[\(index)]"))
            guard userMediaKeys.insert(record.key).inserted else { throw .duplicateKey(record.key.description) }
            userMedia.append(record)
        }

        return SyncDeviceFile(
            space: fileSpace,
            device: SyncDeviceInfo(id: id, name: name, platform: platform, app: app),
            watchProgress: progress,
            userMedia: userMedia
        )
    }

    private static func parseProgress(_ object: JSONObject) throws(Rejection) -> SyncProgressRecord {
        let (type, tmdbID) = try parseIdentity(object)
        var season: Int?
        var episode: Int?
        switch type {
        case .tv:
            guard let s = try object.integer("season"), let e = try object.integer("episode"), s >= 0, e >= 0,
                  let sInt = Int(exactly: s), let eInt = Int(exactly: e)
            else { throw .invalidField(object.path + ".season") }
            season = sInt
            episode = eInt
        case .movie:
            guard try object.integer("season") == nil, try object.integer("episode") == nil else {
                throw .invalidField(object.path + ".season")
            }
        }
        let cleared = try object.bool("cleared") ?? false
        let device = try parseDevice(object)
        let updateTime = try object.stamp("updateTime")

        if cleared {
            return SyncProgressRecord(
                type: type, tmdbID: tmdbID, season: season, episode: episode,
                cleared: true, device: device, updateTime: updateTime
            )
        }
        guard let position = try object.number("position"), position >= 0 else {
            throw .invalidField(object.path + ".position")
        }
        guard let duration = try object.number("duration"), duration > 0 else {
            throw .invalidField(object.path + ".duration")
        }
        guard let completed = try object.bool("completed") else { throw .invalidField(object.path + ".completed") }
        return SyncProgressRecord(
            type: type, tmdbID: tmdbID, season: season, episode: episode,
            position: position, duration: duration, completed: completed,
            device: device, updateTime: updateTime
        )
    }

    private static func parseUserMedia(_ object: JSONObject) throws(Rejection) -> SyncUserMediaRecord {
        let (type, tmdbID) = try parseIdentity(object)
        let favourite = try object.bool("favourite") ?? false
        let watchlist = try object.bool("watchlist") ?? false
        var rating: Double?
        if let value = try object.number("rating") {
            guard isValidRating(value) else { throw .invalidField(object.path + ".rating") }
            rating = value
        }
        var review: String?
        if let text = try object.string("review") {
            guard text.unicodeScalars.count <= maxReviewScalars else { throw .invalidField(object.path + ".review") }
            review = text
        }
        return SyncUserMediaRecord(
            type: type, tmdbID: tmdbID, favourite: favourite, watchlist: watchlist,
            rating: rating, review: review,
            device: try parseDevice(object), updateTime: try object.stamp("updateTime")
        )
    }

    private static func parseIdentity(_ object: JSONObject) throws(Rejection) -> (SyncMediaType, Int) {
        guard let raw = try object.string("type"), let type = SyncMediaType(rawValue: raw) else {
            throw .invalidField(object.path + ".type")
        }
        guard let id = try object.integer("tmdbId"), id >= 1, let tmdbID = Int(exactly: id) else {
            throw .invalidField(object.path + ".tmdbId")
        }
        return (type, tmdbID)
    }

    private static func parseDevice(_ object: JSONObject) throws(Rejection) -> String {
        guard let device = try object.string("device"), !device.isEmpty else {
            throw .invalidField(object.path + ".device")
        }
        return device
    }

    /// 0.5 to 10.0 in steps of 0.5.
    static func isValidRating(_ value: Double) -> Bool {
        guard value.isFinite, value >= 0.5, value <= 10 else { return false }
        let doubled = value * 2
        return doubled == doubled.rounded()
    }

    // MARK: Writing

    /// The bytes of this device's file: UTF-8 JSON, sorted keys, no
    /// byte-order mark, only the fields of §5 and §6.
    static func encode(_ file: SyncDeviceFile) throws -> Data {
        let root: [String: Any] = [
            "format": SyncDeviceFile.format,
            "space": file.space,
            "device": [
                "id": file.device.id,
                "name": file.device.name,
                "platform": file.device.platform,
                "app": file.device.app
            ] as [String: Any],
            "updateTime": NSNumber(value: file.updateTime),
            "watchProgress": file.watchProgress.map(json(for:)),
            "userMedia": file.userMedia.map(json(for:))
        ]
        return try JSONSerialization.data(
            withJSONObject: root,
            options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        )
    }

    private static func json(for record: SyncProgressRecord) -> [String: Any] {
        var object: [String: Any] = [
            "type": record.type.rawValue,
            "tmdbId": record.tmdbID,
            "device": record.device,
            "updateTime": NSNumber(value: record.updateTime)
        ]
        if let season = record.season, let episode = record.episode {
            object["season"] = season
            object["episode"] = episode
        }
        if record.cleared {
            object["cleared"] = true
        } else {
            object["position"] = record.position ?? 0
            object["duration"] = record.duration ?? 0
            object["completed"] = record.completed ?? false
        }
        return object
    }

    private static func json(for record: SyncUserMediaRecord) -> [String: Any] {
        [
            "type": record.type.rawValue,
            "tmdbId": record.tmdbID,
            "favourite": record.favourite,
            "watchlist": record.watchlist,
            "rating": record.rating.map { $0 as Any } ?? NSNull(),
            "review": record.review.map { $0 as Any } ?? NSNull(),
            "device": record.device,
            "updateTime": NSNumber(value: record.updateTime)
        ]
    }
}

// MARK: - Typed JSON access

/// One parsed JSON object with the protocol's type rules: absent and
/// `null` are the same, integers never carry a fraction, and booleans are
/// never numbers.
private nonisolated struct JSONObject {
    let values: [String: Any]
    let path: String

    init(_ values: [String: Any], path: String) {
        self.values = values
        self.path = path
    }

    init(_ value: Any, path: String) throws(SyncFileFormat.Rejection) {
        guard let values = value as? [String: Any] else { throw .invalidField(path) }
        self.values = values
        self.path = path
    }

    private func field(_ name: String) -> String {
        path.isEmpty ? name : path + "." + name
    }

    private func present(_ name: String) -> Any? {
        guard let value = values[name], !(value is NSNull) else { return nil }
        return value
    }

    func string(_ name: String) throws(SyncFileFormat.Rejection) -> String? {
        guard let value = present(name) else { return nil }
        guard let string = value as? String else { throw .invalidField(field(name)) }
        return string
    }

    func object(_ name: String) throws(SyncFileFormat.Rejection) -> JSONObject? {
        guard let value = present(name) else { return nil }
        return try JSONObject(value, path: field(name))
    }

    func array(_ name: String) throws(SyncFileFormat.Rejection) -> [Any] {
        guard let value = present(name) else { return [] }
        guard let array = value as? [Any] else { throw .invalidField(field(name)) }
        return array
    }

    func bool(_ name: String) throws(SyncFileFormat.Rejection) -> Bool? {
        guard let value = present(name) else { return nil }
        guard let number = value as? NSNumber, Self.isBoolean(number) else { throw .invalidField(field(name)) }
        return number.boolValue
    }

    /// An integer, refusing booleans and numbers written with a fraction
    /// or exponent.
    func integer(_ name: String) throws(SyncFileFormat.Rejection) -> Int64? {
        guard let value = present(name) else { return nil }
        guard let number = value as? NSNumber, let integer = Self.integer(number) else {
            throw .invalidField(field(name))
        }
        return integer
    }

    /// A finite decimal number (integers included), never a boolean.
    func number(_ name: String) throws(SyncFileFormat.Rejection) -> Double? {
        guard let value = present(name) else { return nil }
        guard let number = value as? NSNumber, !Self.isBoolean(number), number.doubleValue.isFinite else {
            throw .invalidField(field(name))
        }
        return number.doubleValue
    }

    /// §7.3: anything but a valid millisecond stamp counts as 0.
    func stamp(_ name: String) throws(SyncFileFormat.Rejection) -> Int64 {
        guard let value = present(name), let number = value as? NSNumber, let integer = Self.integer(number),
              integer >= SyncFileFormat.minimumStamp
        else { return 0 }
        return integer
    }

    private static func isBoolean(_ number: NSNumber) -> Bool {
        CFGetTypeID(number) == CFBooleanGetTypeID()
    }

    private static func integer(_ number: NSNumber) -> Int64? {
        guard !isBoolean(number) else { return nil }
        switch String(cString: number.objCType) {
        case "c", "s", "i", "l", "q", "C", "S", "I", "L", "Q":
            // JSONSerialization keeps integers that fit in 64 bits as such.
            let value = number.int64Value
            return NSNumber(value: value) == number ? value : nil
        default:
            return nil
        }
    }
}
