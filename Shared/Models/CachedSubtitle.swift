//
//  CachedSubtitle.swift
//  Edendale
//
//  An online subtitle downloaded for one video and kept on this device, so
//  playing that video again reattaches it without a search or a download.
//  Device-local like the library: never synced. The file itself lives in
//  the subtitle cache folder (see SubtitleCacheStore).
//

import Foundation
import SwiftData

@Model
final class CachedSubtitle {
    /// `videoKey` and `subtitleID` together: one record per subtitle and video.
    @Attribute(.unique) var storageKey: String
    /// The provider's ID for the subtitle file.
    var subtitleID: String
    /// The video it was downloaded for (`SubtitleVideoKey`), which doesn't
    /// depend on the address the video was read from.
    var videoKey: String
    /// The file's name inside the subtitle cache folder. Stored relative to
    /// that folder because the app container's path changes across updates.
    var fileName: String
    /// The name the player's subtitle list shows.
    var displayName: String
    /// The subtitle's language code, as the provider reports it.
    var language: String
    var downloadedAt: Date
    /// When it was downloaded or last reattached to a playback. Launch
    /// deletes subtitles unused for more than a month.
    var lastUsedAt: Date

    init(
        subtitleID: String,
        videoKey: String,
        fileName: String,
        displayName: String,
        language: String,
        date: Date
    ) {
        self.storageKey = Self.makeStorageKey(subtitleID: subtitleID, videoKey: videoKey)
        self.subtitleID = subtitleID
        self.videoKey = videoKey
        self.fileName = fileName
        self.displayName = displayName
        self.language = language
        self.downloadedAt = date
        self.lastUsedAt = date
    }

    static func makeStorageKey(subtitleID: String, videoKey: String) -> String {
        "\(videoKey)#\(subtitleID)"
    }
}

/// Which video a downloaded subtitle belongs to: the TMDB identity it was
/// searched with plus the file's name. The address the file is read from
/// plays no part, so one file reached through several endpoints of the same
/// server (a LAN address and a Tailscale address, a host name and an IP, a
/// different port or share path) shares its subtitles, as does a local copy.
/// Another release of the same title, whose timing may differ, doesn't.
nonisolated enum SubtitleVideoKey {
    static func make(
        lookup: (id: String, season: Int?, episode: Int?),
        fileName: String
    ) -> String? {
        let name = fileName
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .precomposedStringWithCanonicalMapping
            .lowercased()
        guard !lookup.id.isEmpty, !name.isEmpty else { return nil }
        let media: String
        if let season = lookup.season {
            media = "tv:\(lookup.id):s\(season)e\(lookup.episode ?? 0)"
        } else {
            media = "movie:\(lookup.id)"
        }
        return "\(media)/\(name)"
    }
}

extension PlaybackItem {
    /// The `SubtitleVideoKey` of this item; `nil` without a TMDB match,
    /// which online subtitle search needs too.
    var subtitleVideoKey: String? {
        guard let lookup = subtitleLookup, let fileName else { return nil }
        return SubtitleVideoKey.make(lookup: lookup, fileName: fileName)
    }
}
