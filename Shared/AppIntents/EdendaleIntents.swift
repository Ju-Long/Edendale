//
//  EdendaleIntents.swift
//  Edendale
//
//  Siri and Shortcuts surface for opening known media, searching the app,
//  and playing local files. Entity resolution reads the privacy-safe app-
//  group snapshot rather than opening either persistence store.
//

import AppIntents
import Foundation

struct MediaEntity: AppEntity, Sendable {
    static var typeDisplayRepresentation = TypeDisplayRepresentation(name: "Movie or Show")
    static var defaultQuery = MediaEntityQuery()

    let id: String
    let title: String
    let subtitle: String?
    let deepLink: URL

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(
            title: "\(title)",
            subtitle: subtitle.map { "\($0)" }
        )
    }

    init(item: WidgetMediaItem) {
        id = item.id
        title = item.title
        subtitle = item.subtitle
        deepLink = item.deepLink
    }

    init(id: String, title: String, subtitle: String?, deepLink: URL) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.deepLink = deepLink
    }
}

struct MediaEntityQuery: EntityStringQuery {
    func entities(for identifiers: [MediaEntity.ID]) async throws -> [MediaEntity] {
        let current = Dictionary(uniqueKeysWithValues: Self.entities.map { ($0.id, $0) })
        return identifiers.compactMap { identifier in
            current[identifier] ?? Self.stableTMDBEntity(for: identifier)
        }
    }

    func suggestedEntities() async throws -> [MediaEntity] {
        Array(Self.entities.prefix(40))
    }

    func entities(matching string: String) async throws -> [MediaEntity] {
        let query = string.normalizedForMediaLookup
        guard !query.isEmpty else { return try await suggestedEntities() }
        return Self.entities
            .compactMap { entity -> (MediaEntity, Int)? in
                let title = entity.title.normalizedForMediaLookup
                let score: Int
                if title == query {
                    score = 0
                } else if title.hasPrefix(query) {
                    score = 1
                } else if title.contains(query) {
                    score = 2
                } else {
                    return nil
                }
                return (entity, score)
            }
            .sorted {
                if $0.1 != $1.1 { return $0.1 < $1.1 }
                return $0.0.title.localizedStandardCompare($1.0.title) == .orderedAscending
            }
            .prefix(20)
            .map(\.0)
    }

    private static var entities: [MediaEntity] {
        let snapshot = WidgetSnapshotStore.load()
        let source = (snapshot?.catalog ?? []) + (snapshot?.trending ?? [])
        var seen = Set<String>()
        return source
            .filter { seen.insert($0.id).inserted }
            .map(MediaEntity.init(item:))
    }

    /// Saved Shortcuts keep only an entity identifier. Rebuild stable TMDB
    /// entities even after they rotate out of the current Trending snapshot.
    private static func stableTMDBEntity(for identifier: String) -> MediaEntity? {
        let parts = identifier.split(separator: ":", maxSplits: 2).map(String.init)
        guard parts.count == 3,
              parts[0] == "tmdb",
              let mediaType = TMDBMediaType(rawValue: parts[1]),
              let tmdbID = Int(parts[2]),
              let deepLink = AppRoute.media(
                MediaRef(id: tmdbID, mediaType: mediaType)
              ).url
        else { return nil }

        return MediaEntity(
            id: identifier,
            title: mediaType == .movie
                ? String(localized: "Movie \(tmdbID)")
                : String(localized: "Show \(tmdbID)"),
            subtitle: String(localized: "Saved Edendale item"),
            deepLink: deepLink
        )
    }
}

struct OpenMediaIntent: OpenIntent {
    static var title: LocalizedStringResource = "Open Movie or Show"
    static var description = IntentDescription(
        "Opens a movie or show from your Edendale library or current browse catalog."
    )
    static var openAppWhenRun = true
    @available(macOS 26.0, iOS 26.0, watchOS 26.0, tvOS 26.0, visionOS 26.0, *)
    static var supportedModes: IntentModes { .foreground(.immediate) }

    @Parameter(title: "Movie or Show")
    var target: MediaEntity

    init() {}

    init(target: MediaEntity) {
        self.target = target
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        guard let route = AppRoute(url: target.deepLink) else {
            throw EdendaleIntentError.invalidMedia
        }
        if target.id.hasPrefix("local:"),
           WidgetSnapshotStore.load()?.catalog.contains(where: { $0.id == target.id }) != true {
            throw EdendaleIntentError.invalidMedia
        }
        AppRouter.shared.open(route)
        return .result()
    }
}

struct SearchEdendaleIntent: ShowInAppSearchResultsIntent {
    static var title: LocalizedStringResource = "Search Edendale"
    static var description = IntentDescription("Searches Edendale for movies and shows.")
    static var searchScopes: [StringSearchScope] = [.general]
    static var openAppWhenRun = true
    @available(macOS 26.0, iOS 26.0, watchOS 26.0, tvOS 26.0, visionOS 26.0, *)
    static var supportedModes: IntentModes { .foreground(.immediate) }

    @Parameter(title: "Search")
    var criteria: StringSearchCriteria

    init() {
        criteria = StringSearchCriteria(term: "")
    }

    @MainActor
    func perform() async throws -> some IntentResult {
        let query = criteria.term.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { throw EdendaleIntentError.emptySearch }
        AppRouter.shared.open(.search(query))
        return .result()
    }
}

struct PlayEdendaleIntent: PlayVideoIntent {
    static var title: LocalizedStringResource = "Play Video in Edendale"
    static var description = IntentDescription(
        "Plays a matching local movie or episode, or opens search when no local file matches."
    )
    static var supportedCategories: [VideoCategory] = [.movies, .tv]
    static var openAppWhenRun = true
    @available(macOS 26.0, iOS 26.0, watchOS 26.0, tvOS 26.0, visionOS 26.0, *)
    static var supportedModes: IntentModes { .foreground(.immediate) }

    @Parameter(title: "Movie or Show")
    var term: String

    init() {
        term = ""
    }

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let query = term.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { throw EdendaleIntentError.emptySearch }

        if let decision = Self.bestPlayableDecision(matching: query) {
            AppRouter.shared.open(decision.route)
            switch decision.action {
            case .playback:
                return .result(dialog: "Opening \(decision.title) in Edendale.")
            case .chooseEpisode:
                return .result(
                    dialog: "I opened \(decision.title) so you can choose an episode."
                )
            }
        }

        AppRouter.shared.open(.search(query))
        return .result(dialog: "I couldn't find a local file, so I opened Edendale search.")
    }

    private static func bestPlayableDecision(matching query: String) -> PlayDecision? {
        let normalizedQuery = query.normalizedForMediaLookup
        guard !normalizedQuery.isEmpty else { return nil }

        let catalog = WidgetSnapshotStore.load()?.catalog ?? []
        let bestMatch = catalog
            .compactMap { item -> (item: WidgetMediaItem, score: Int)? in
                guard item.id.hasPrefix("local:"),
                      let score = matchScore(
                        title: item.title.normalizedForMediaLookup,
                        query: normalizedQuery
                      )
                else { return nil }
                return (item, score)
            }
            .min {
                if $0.score != $1.score { return $0.score < $1.score }
                let titleOrder = $0.item.title.localizedStandardCompare($1.item.title)
                if titleOrder != .orderedSame { return titleOrder == .orderedAscending }
                return $0.item.id < $1.item.id
            }?
            .item

        guard let bestMatch else { return nil }
        let parts = bestMatch.id.split(separator: ":", maxSplits: 2).map(String.init)
        guard parts.count == 3, parts[0] == "local" else { return nil }

        switch parts[1] {
        case "movie":
            guard let id = UUID(uuidString: parts[2]) else { return nil }
            return PlayDecision(
                route: .playLocalMovie(id),
                title: bestMatch.title,
                action: .playback
            )

        case "episode":
            guard let id = UUID(uuidString: parts[2]) else { return nil }
            return PlayDecision(
                route: .playLocalEpisode(id),
                title: bestMatch.title,
                action: .playback
            )

        case "show":
            guard let showID = UUID(uuidString: parts[2]) else { return nil }
            if let episode = bestEpisode(for: bestMatch.id, in: catalog),
               let episodeID = episode.id.split(separator: ":", maxSplits: 2).last,
               let id = UUID(uuidString: String(episodeID)) {
                return PlayDecision(
                    route: .playLocalEpisode(id),
                    title: episode.title,
                    action: .playback
                )
            }
            return PlayDecision(
                route: .localShow(showID),
                title: bestMatch.title,
                action: .chooseEpisode
            )

        default:
            return nil
        }
    }

    private static func matchScore(title: String, query: String) -> Int? {
        if title == query { return 0 }
        if title.hasPrefix(query) { return 1 }
        if title.contains(query) { return 2 }
        return nil
    }

    private static func bestEpisode(
        for showID: String,
        in catalog: [WidgetMediaItem]
    ) -> WidgetMediaItem? {
        catalog
            .filter { $0.parentID == showID && $0.id.hasPrefix("local:episode:") }
            .sorted {
                let lhsResumable = ($0.progress ?? 0) > 0
                let rhsResumable = ($1.progress ?? 0) > 0
                if lhsResumable != rhsResumable { return lhsResumable }
                if lhsResumable, $0.progress != $1.progress {
                    return ($0.progress ?? 0) > ($1.progress ?? 0)
                }
                let episodeOrder = ($0.subtitle ?? "").localizedStandardCompare(
                    $1.subtitle ?? ""
                )
                if episodeOrder != .orderedSame {
                    return episodeOrder == .orderedAscending
                }
                return $0.id < $1.id
            }
            .first
    }

    private struct PlayDecision {
        enum Action {
            case playback
            case chooseEpisode
        }

        let route: AppRoute
        let title: String
        let action: Action
    }
}

struct EdendaleShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: OpenMediaIntent(),
            phrases: [
                "Open \(\.$target) in \(.applicationName)",
                "Show \(\.$target) in \(.applicationName)"
            ],
            shortTitle: "Open Media",
            systemImageName: "film"
        )

        AppShortcut(
            intent: SearchEdendaleIntent(),
            phrases: [
                "Search in \(.applicationName)",
                "Find a movie with \(.applicationName)"
            ],
            shortTitle: "Search Edendale",
            systemImageName: "magnifyingglass"
        )

        AppShortcut(
            intent: PlayEdendaleIntent(),
            phrases: [
                "Play a video in \(.applicationName)",
                "Watch something with \(.applicationName)"
            ],
            shortTitle: "Play Video",
            systemImageName: "play.fill"
        )
    }

    static var shortcutTileColor: ShortcutTileColor { .yellow }
}

private enum EdendaleIntentError: LocalizedError {
    case emptySearch
    case invalidMedia

    var errorDescription: String? {
        switch self {
        case .emptySearch: String(localized: "Tell Edendale what movie or show to find.")
        case .invalidMedia: String(localized: "That Edendale media item is no longer available.")
        }
    }
}

private extension String {
    nonisolated var normalizedForMediaLookup: String {
        folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
