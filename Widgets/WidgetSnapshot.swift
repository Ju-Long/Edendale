//
//  WidgetSnapshot.swift
//  WidgetsExtension
//
//  Read-only bridge to the compact snapshot published by the main app. The
//  extension never opens Edendale's SwiftData or CoreData stores directly.
//

import Foundation
import WidgetKit

struct EdendaleWidgetSnapshot: Codable, Sendable {
    let updatedAt: Date
    let trending: [EdendaleWidgetItem]
    let continueWatching: [EdendaleWidgetItem]
}

struct EdendaleWidgetItem: Codable, Identifiable, Sendable {
    let id: String
    let title: String
    let subtitle: String?
    let posterURL: URL?
    let progress: Double?
    let deepLink: URL
}

enum EdendaleWidgetSnapshotStore {
    static let appGroup = "group.com.BaBaSaMa.Edendale"
    static let snapshotKey = "edendale.widget.snapshot.v1"

    static func load() -> EdendaleWidgetSnapshot? {
        guard let defaults = UserDefaults(suiteName: appGroup) else { return nil }

        let data: Data?
        if let storedData = defaults.data(forKey: snapshotKey) {
            data = storedData
        } else if let storedString = defaults.string(forKey: snapshotKey) {
            data = storedString.data(using: .utf8)
        } else {
            data = nil
        }

        guard let data else { return nil }

        let decoder = JSONDecoder()
        if let snapshot = try? decoder.decode(EdendaleWidgetSnapshot.self, from: data) {
            return snapshot
        }

        // Accept ISO-8601 dates as a compatibility fallback if the publisher
        // elects to use a human-readable JSON date strategy later.
        let isoDecoder = JSONDecoder()
        isoDecoder.dateDecodingStrategy = .iso8601
        return try? isoDecoder.decode(EdendaleWidgetSnapshot.self, from: data)
    }
}

enum EdendaleWidgetContent: Sendable, Equatable {
    case trending
    case continueWatching

    var title: String {
        switch self {
        case .trending: String(localized: "Trending")
        case .continueWatching: String(localized: "Continue Watching")
        }
    }

    var emptyTitle: String {
        switch self {
        case .trending: String(localized: "No trends yet")
        case .continueWatching: String(localized: "All caught up")
        }
    }

    var emptyMessage: String {
        switch self {
        case .trending: String(localized: "Open Edendale to refresh this week's picks.")
        case .continueWatching: String(localized: "Start a video and it will appear here.")
        }
    }

    var refreshInterval: TimeInterval {
        switch self {
        case .trending: 3 * 60 * 60
        case .continueWatching: 60 * 60
        }
    }

    func items(in snapshot: EdendaleWidgetSnapshot) -> [EdendaleWidgetItem] {
        switch self {
        case .trending: snapshot.trending
        case .continueWatching: snapshot.continueWatching
        }
    }

    func maximumItemCount(for family: WidgetFamily) -> Int {
        switch family {
        case .systemSmall: 1
        case .systemMedium: 2
        case .systemLarge: 4
        default: 1
        }
    }
}

struct EdendaleWidgetEntry: TimelineEntry {
    let date: Date
    let snapshot: EdendaleWidgetSnapshot?
    let posterData: [String: Data]
    let isPlaceholder: Bool

    static func placeholder(at date: Date = .now) -> EdendaleWidgetEntry {
        EdendaleWidgetEntry(
            date: date,
            snapshot: EdendaleWidgetSnapshot(
                updatedAt: date,
                trending: placeholderItems,
                continueWatching: placeholderItems
            ),
            posterData: [:],
            isPlaceholder: true
        )
    }

    private static let placeholderItems: [EdendaleWidgetItem] = (1...4).map { index in
        EdendaleWidgetItem(
            id: "placeholder-\(index)",
            title: String(localized: "Featured title"),
            subtitle: String(localized: "New in Edendale"),
            posterURL: nil,
            progress: 0.42,
            deepLink: URL(string: "edendale://placeholder/\(index)")!
        )
    }
}

struct EdendaleWidgetProvider: TimelineProvider {
    typealias Entry = EdendaleWidgetEntry

    let content: EdendaleWidgetContent

    func placeholder(in context: Context) -> Entry {
        .placeholder()
    }

    func getSnapshot(in context: Context, completion: @escaping (Entry) -> Void) {
        if context.isPreview {
            completion(.placeholder())
            return
        }

        Task {
            completion(await makeEntry(for: context.family))
        }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<Entry>) -> Void) {
        Task {
            let now = Date()
            let entry = await makeEntry(for: context.family, date: now)
            let nextRefresh = now.addingTimeInterval(content.refreshInterval)
            completion(Timeline(entries: [entry], policy: .after(nextRefresh)))
        }
    }

    private func makeEntry(
        for family: WidgetFamily,
        date: Date = .now
    ) async -> EdendaleWidgetEntry {
        guard let snapshot = EdendaleWidgetSnapshotStore.load() else {
            return EdendaleWidgetEntry(
                date: date,
                snapshot: nil,
                posterData: [:],
                isPlaceholder: false
            )
        }

        let count = content.maximumItemCount(for: family)
        let visibleItems = Array(content.items(in: snapshot).prefix(count))
        let posters = await EdendaleWidgetPosterLoader.loadPosters(for: visibleItems)
        return EdendaleWidgetEntry(
            date: date,
            snapshot: snapshot,
            posterData: posters,
            isPlaceholder: false
        )
    }
}

private enum EdendaleWidgetPosterLoader {
    /// Keeps each timeline request small enough for the widget extension's
    /// execution budget. Missing artwork simply renders the catalog placeholder.
    private static let maximumImageBytes = 2_000_000

    static func loadPosters(for items: [EdendaleWidgetItem]) async -> [String: Data] {
        await withTaskGroup(of: (String, Data?).self) { group in
            for item in items {
                guard let url = item.posterURL,
                      url.scheme == "https",
                      url.host == "image.tmdb.org"
                else { continue }
                group.addTask {
                    do {
                        let (data, response) = try await URLSession.shared.data(from: url)
                        if let http = response as? HTTPURLResponse,
                           !(200..<300).contains(http.statusCode) {
                            return (item.id, nil)
                        }
                        guard !data.isEmpty, data.count <= maximumImageBytes else {
                            return (item.id, nil)
                        }
                        return (item.id, data)
                    } catch {
                        return (item.id, nil)
                    }
                }
            }

            var result: [String: Data] = [:]
            for await (id, data) in group {
                if let data { result[id] = data }
            }
            return result
        }
    }
}
