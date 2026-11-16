//
//  PlaybackSources.swift
//  Edendale
//
//  The same movie or show can be imported from several sources — a local
//  folder and an SMB share, say, or one share linked through two endpoints
//  (its LAN host name and its Tailscale address). Each file is its own
//  library record, tied to the others by its TMDB id (or, until it has
//  one, by its parsed title). The library shows one card per title; the
//  detail page plays the preferred copy and offers the rest in a "Play
//  From" menu. These helpers decide the grouping, the order, the default,
//  and how a copy is described.
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

    // MARK: - Grouping copies

    /// What ties copies of one movie together: its TMDB match, or before it
    /// has one, its parsed title and year — the same file reached through
    /// two endpoints parses the same.
    static func copyKey(_ movie: Movie) -> String {
        if let tmdbId = movie.tmdbId { return "tmdb-\(tmdbId)" }
        let year = movie.releaseYear.map(String.init) ?? ""
        return "local-\(normalized(movie.localTitle))-\(year)"
    }

    /// What ties copies of one show together; see `copyKey(_:)` for movies.
    static func copyKey(_ show: TVShow) -> String {
        if let tmdbId = show.tmdbId { return "tmdb-\(tmdbId)" }
        return "local-\(normalized(show.name))"
    }

    /// The first item of each key, in the given order: one card per title.
    static func distinct<Item>(_ items: [Item], key: (Item) -> String) -> [Item] {
        var seen: Set<String> = []
        return items.filter { seen.insert(key($0)).inserted }
    }

    private static func normalized(_ title: String) -> String {
        title.precomposedStringWithCanonicalMapping.lowercased()
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Order and default

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

    /// The copy Play starts: the reachable copy played most recently (the
    /// source the viewer chose last), else the first one whose source isn't
    /// known to be offline, or the first of all when every one is.
    static func preferred<Item>(
        _ copies: [Item],
        isOffline: (Item) -> Bool,
        lastPlayed: (Item) -> Date? = { _ in nil }
    ) -> Item? {
        let reachable = copies.filter { !isOffline($0) }
        return mostRecentlyPlayed(reachable, lastPlayed: lastPlayed) ?? reachable.first ?? copies.first
    }

    /// The copy played most recently; `nil` when none has been played.
    static func mostRecentlyPlayed<Item>(_ copies: [Item], lastPlayed: (Item) -> Date?) -> Item? {
        var best: (item: Item, date: Date)?
        for copy in copies {
            guard let date = lastPlayed(copy) else { continue }
            if best == nil || date > best!.date { best = (copy, date) }
        }
        return best?.item
    }

    /// `copies` with the one played most recently moved to the front; the
    /// rest keep their order.
    static func lastPlayedFirst<Item>(_ copies: [Item], lastPlayed: (Item) -> Date?) -> [Item] {
        var best: (index: Int, date: Date)?
        for (index, copy) in copies.enumerated() {
            guard let date = lastPlayed(copy) else { continue }
            if best == nil || date > best!.date { best = (index, date) }
        }
        guard let best, best.index > 0 else { return copies }
        var reordered = copies
        reordered.insert(reordered.remove(at: best.index), at: 0)
        return reordered
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

    // MARK: - Describing a copy

    /// The file name of a stored path or credential-free URL, decoded.
    static func fileName(of path: String) -> String {
        if let url = URL(string: path), url.scheme != nil, !url.lastPathComponent.isEmpty {
            return url.lastPathComponent
        }
        return (path as NSString).lastPathComponent
    }

    /// Where a copy is reached from: the server's host (with a port only
    /// when one was given), e.g. `longs-mac-studio.local` or `100.64.0.7`,
    /// or for accounts and buckets the account, never the account-key hash
    /// their URLs use as a host. `nil` for local files.
    static func endpoint(folder: VideoFolder?, filePath: String) -> String? {
        let url = URL(string: filePath).flatMap { $0.scheme == nil ? nil : $0 } ?? folder?.remoteURL
        guard let url, let kind = MediaSourceKind(url: url), kind.isRemote else { return nil }
        switch kind {
        case .googleDrive, .oneDrive, .dropbox, .s3:
            return folder?.username.flatMap { $0.isEmpty ? nil : $0 }
        default:
            guard let host = url.host(percentEncoded: false), !host.isEmpty else { return nil }
            // A bare IPv6 address reads as one only in brackets.
            let shownHost = host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host
            return url.port.map { "\(shownHost):\($0)" } ?? shownHost
        }
    }

    /// The first line of a menu item: the source's name and its endpoint,
    /// so one share linked through two endpoints reads as two choices even
    /// where a menu shows no second line (Apple TV).
    static func title(folder: VideoFolder?, filePath: String) -> String {
        let name = folder?.name ?? fileName(of: filePath)
        guard let endpoint = endpoint(folder: folder, filePath: filePath) else { return name }
        return "\(name) (\(endpoint))"
    }

    /// The second line of a menu item: the source's kind and the file name,
    /// which usually tells copies apart (2160p, a remux, a release group).
    static func detail(folder: VideoFolder?, filePath: String) -> String {
        let kind = folder?.sourceKind.displayName ?? MediaSourceKind.local.displayName
        return "\(kind) · \(fileName(of: filePath))"
    }
}
