//
//  PlaybackSources.swift
//  Edendale
//
//  The same movie or show can be imported from several sources — a local
//  folder and an SMB share, say. Each file is its own library record, tied
//  to the others by its TMDB id. The detail page plays the preferred copy
//  and offers the rest in a "Play From" menu; these helpers decide the
//  order, the default, and how a copy is described.
//

import Foundation

enum PlaybackSources {

    /// One episode of a show across every imported copy of the show.
    struct EpisodeSlot: Identifiable {
        let season: Int
        let number: Int
        /// The page's own copy first; see `order`.
        let copies: [Episode]

        var id: String { "\(season)-\(number)" }
        var primary: Episode { copies[0] }
    }

    /// `primary` first, then copies in local folders, then the rest by
    /// folder name, so the menu reads the same each time.
    static func order<Item>(primary: Item, others: [Item], folder: (Item) -> VideoFolder?) -> [Item] {
        let sorted = others.sorted { lhs, rhs in
            let left = folder(lhs), right = folder(rhs)
            let leftLocal = left?.isRemote == false, rightLocal = right?.isRemote == false
            if leftLocal != rightLocal { return leftLocal }
            return (left?.name ?? "").localizedStandardCompare(right?.name ?? "") == .orderedAscending
        }
        return [primary] + sorted
    }

    /// The copy Play starts: the first one whose source isn't known to be
    /// offline, or the first of all when every one is.
    static func preferred<Item>(_ copies: [Item], isOffline: (Item) -> Bool) -> Item? {
        copies.first { !isOffline($0) } ?? copies.first
    }

    /// Every episode of `primary` and of `others` (other copies of the same
    /// show), one slot per season and episode number, in airing order.
    static func episodeSlots(primary: TVShow, others: [TVShow]) -> [EpisodeSlot] {
        let shows = order(primary: primary, others: others, folder: \.folder)
        var slots: [String: (season: Int, number: Int, copies: [Episode])] = [:]
        for show in shows {
            let episodes = show.episodes.sorted { ($0.seasonNumber, $0.episodeNumber, $0.filePath) < ($1.seasonNumber, $1.episodeNumber, $1.filePath) }
            for episode in episodes {
                let key = "\(episode.seasonNumber)-\(episode.episodeNumber)"
                slots[key, default: (episode.seasonNumber, episode.episodeNumber, [])].copies.append(episode)
            }
        }
        return slots.values
            .map { EpisodeSlot(season: $0.season, number: $0.number, copies: $0.copies) }
            .sorted { ($0.season, $0.number) < ($1.season, $1.number) }
    }

    /// The file name of a stored path or credential-free URL, decoded.
    static func fileName(of path: String) -> String {
        if let url = URL(string: path), url.scheme != nil, !url.lastPathComponent.isEmpty {
            return url.lastPathComponent
        }
        return (path as NSString).lastPathComponent
    }

    /// The second line of a menu item: the source's kind and the file name,
    /// which usually tells copies apart (2160p, a remux, a release group).
    static func detail(folder: VideoFolder?, filePath: String) -> String {
        let kind = folder?.sourceKind.displayName ?? MediaSourceKind.local.displayName
        return "\(kind) · \(fileName(of: filePath))"
    }
}
