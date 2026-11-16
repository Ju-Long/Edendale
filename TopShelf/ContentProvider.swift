//
//  ContentProvider.swift
//  TopShelfExtension
//
//  The Top Shelf shown while Edendale sits in the Home Screen's top row and
//  is focused. Continue Watching comes first, then Trending; each item's
//  context title names its section, so the full-screen carousel reads as
//  two sections. Items without 16:9 artwork fall back to poster rows, and an
//  empty snapshot leaves the app's static Top Shelf image in place.
//
//  The extension makes no network requests of its own: the Home Screen
//  fetches the TMDB image URLs, and buttons open edendale:// links in the app.
//

import Foundation
import TVServices

final class ContentProvider: TVTopShelfContentProvider {
    override func loadTopShelfContent(
        completionHandler: @escaping ((any TVTopShelfContent)?) -> Void
    ) {
        let snapshot = TopShelfSnapshotStore.load()
        completionHandler(snapshot.flatMap(TopShelfContentBuilder.content(for:)))
    }
}

enum TopShelfContentBuilder {
    enum Section {
        case continueWatching
        case trending

        var title: String {
            switch self {
            case .continueWatching: String(localized: "Continue Watching")
            case .trending: String(localized: "Trending")
            }
        }
    }

    struct Entry {
        let section: Section
        let item: TopShelfMediaItem
    }

    static func content(for snapshot: TopShelfSnapshot) -> (any TVTopShelfContent)? {
        let entries = entries(in: snapshot)
        let carouselItems = entries.compactMap(carouselItem)
        if !carouselItems.isEmpty {
            return TVTopShelfCarouselContent(style: .details, items: carouselItems)
        }
        let sections = sectionedCollections(for: entries)
        return sections.isEmpty ? nil : TVTopShelfSectionedContent(sections: sections)
    }

    /// Continue Watching first, then Trending without the titles already
    /// shown above (item identifiers must be unique within the content).
    static func entries(in snapshot: TopShelfSnapshot) -> [Entry] {
        var seen = Set<String>()
        var entries: [Entry] = []
        for item in snapshot.continueWatching where seen.insert(item.id).inserted {
            entries.append(Entry(section: .continueWatching, item: item))
        }
        for item in snapshot.trending where seen.insert(item.id).inserted {
            entries.append(Entry(section: .trending, item: item))
        }
        return entries
    }

    // MARK: - Carousel

    static func carouselItem(for entry: Entry) -> TVTopShelfCarouselItem? {
        let item = entry.item
        guard let backdrop = TopShelfArtwork.tmdbURL(item.backdropURL) else { return nil }

        let shelfItem = TVTopShelfCarouselItem(identifier: item.id)
        shelfItem.title = item.title
        shelfItem.contextTitle = entry.section.title
        shelfItem.summary = summary(for: entry)
        shelfItem.genre = kindLabel(for: item.id)
        shelfItem.creationDate = item.releaseDate.flatMap(releaseDate(from:))
        if let duration = item.duration, duration > 0 {
            shelfItem.duration = duration
        }
        // 1x is a 1920×1080 screen and 2x is 4K; TMDB has no 1920 rendition.
        shelfItem.setImageURL(backdrop, for: .screenScale1x)
        shelfItem.setImageURL(
            TopShelfArtwork.tmdbURL(item.backdropURL, size: "original"),
            for: .screenScale2x
        )
        applyActions(for: entry, to: shelfItem)
        return shelfItem
    }

    /// Continue Watching leads with where the user is (episode code or
    /// percentage), then the plot.
    static func summary(for entry: Entry) -> String? {
        let overview = entry.item.overview
        guard entry.section == .continueWatching, let subtitle = entry.item.subtitle else {
            return overview
        }
        guard let overview else { return subtitle }
        return "\(subtitle)\n\(overview)"
    }

    static func kindLabel(for id: String) -> String? {
        if id.hasPrefix("tmdb:movie:") { return String(localized: "Movie") }
        if id.hasPrefix("tmdb:tv:") || id.hasPrefix("tmdb:episode:") {
            return String(localized: "Series")
        }
        return nil
    }

    /// Accepts "yyyy-MM-dd" or "yyyy"; noon local time keeps the year stable
    /// in every time zone.
    static func releaseDate(from text: String) -> Date? {
        let parts = text.split(separator: "-").compactMap { Int($0) }
        guard let year = parts.first, (1800...3000).contains(year) else { return nil }
        var components = DateComponents()
        components.year = year
        components.month = parts.count > 1 ? parts[1] : 1
        components.day = parts.count > 2 ? parts[2] : 1
        components.hour = 12
        return Calendar(identifier: .gregorian).date(from: components)
    }

    // MARK: - Sectioned fallback

    static func sectionedCollections(
        for entries: [Entry]
    ) -> [TVTopShelfItemCollection<TVTopShelfSectionedItem>] {
        [Section.continueWatching, .trending].compactMap { section in
            let items = entries
                .filter { $0.section == section }
                .compactMap(sectionedItem)
            guard !items.isEmpty else { return nil }
            let collection = TVTopShelfItemCollection(items: items)
            collection.title = section.title
            return collection
        }
    }

    static func sectionedItem(for entry: Entry) -> TVTopShelfSectionedItem? {
        let item = entry.item
        guard let poster = TopShelfArtwork.tmdbURL(item.posterURL, size: "w500") else { return nil }

        let shelfItem = TVTopShelfSectionedItem(identifier: item.id)
        shelfItem.title = item.title
        shelfItem.imageShape = .poster
        // Posters are 404×608 pt (1x) and 808×1216 px (2x).
        shelfItem.setImageURL(poster, for: .screenScale1x)
        shelfItem.setImageURL(
            TopShelfArtwork.tmdbURL(item.posterURL, size: "w780"),
            for: .screenScale2x
        )
        if let progress = item.progress {
            shelfItem.playbackProgress = min(max(progress, 0), 1)
        }
        applyActions(for: entry, to: shelfItem)
        return shelfItem
    }

    // MARK: - Actions

    /// Continue Watching: Play resumes, the second button opens the library
    /// page. Trending titles aren't in the library, so there is nothing to
    /// play (trailers need an explicit choice inside the app): the only
    /// button opens the detail page.
    static func applyActions(for entry: Entry, to shelfItem: TVTopShelfItem) {
        switch entry.section {
        case .continueWatching:
            shelfItem.playAction = TVTopShelfAction(url: entry.item.deepLink)
            shelfItem.displayAction = entry.item.detailLink.map { TVTopShelfAction(url: $0) }
        case .trending:
            shelfItem.displayAction = TVTopShelfAction(url: entry.item.deepLink)
        }
    }
}

enum TopShelfArtwork {
    /// Accepts only TMDB's HTTPS image host and optionally swaps the size
    /// segment of its "/t/p/<size>/<file>" path.
    static func tmdbURL(_ url: URL?, size: String? = nil) -> URL? {
        guard let url, url.scheme == "https", url.host == "image.tmdb.org" else { return nil }
        guard let size else { return url }
        var parts = url.pathComponents
        guard parts.count == 5, parts[1] == "t", parts[2] == "p" else { return url }
        parts[3] = size
        var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        components?.path = "/" + parts.dropFirst().joined(separator: "/")
        return components?.url ?? url
    }
}
