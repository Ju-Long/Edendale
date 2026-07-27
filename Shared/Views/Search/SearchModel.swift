//
//  SearchModel.swift
//  Edendale
//
//  Search state: debounced TMDB text search plus the release-heatmap date
//  filter. The heatmap samples each year's most popular releases (a few
//  discover pages) into per-day counts; a committed day range swaps the
//  results over to discover-by-release-date.
//

import Foundation
import SwiftUI
import Observation

@Observable
@MainActor
final class SearchModel {
    var searchText = "" {
        didSet {
            // Typing a query means the user wants a text search, not the
            // filmography they arrived with — drop the active person.
            if !suppressPersonClear { activePerson = nil }
            scheduleSearch(debounce: true)
        }
    }

    var results: [TMDBMediaItem] = []
    /// People matching the query. Rendered above the titles under a
    /// `actors:`-style prefix, below them otherwise.
    private(set) var people: [TMDBPersonItem] = []
    var isSearching = false

    /// Trending grid shown while nothing is being searched for. Fetched once
    /// per session — switching tabs never refetches.
    private(set) var trending: [TMDBMediaItem] = []
    private(set) var isLoadingTrending = false

    /// The scope the current query's keyword prefix asks for.
    var query: SearchQuery { SearchQuery(parsing: searchText) }
    var scope: SearchScope { query.scope }

    /// When set, the results list is a person's filmography rather than a
    /// text/date search (see `showFilmography`).
    private(set) var activePerson: PersonRef?

    /// Guards `activePerson` from being cleared while `showFilmography`
    /// empties the text field.
    private var suppressPersonClear = false

    // MARK: Release-date selection

    /// Committed day range (start-of-day bounds) the results are filtered to.
    private(set) var selectedRange: ClosedRange<Date>?
    /// First tapped square of an in-progress selection; the next tap
    /// (or Done) completes it.
    private(set) var pendingAnchor: Date?

    /// True when either a text query or a committed date range is active —
    /// an empty result list then means "no matches", not "not searched yet".
    var hasActiveQuery: Bool {
        activePerson != nil
            || !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || selectedRange != nil
    }

    /// True when the idle trending grid should take the screen: nothing typed,
    /// no person, no committed date range.
    var isIdle: Bool { !hasActiveQuery }

    /// Strips a keyword prefix from the field, keeping whatever was typed
    /// after it — what the scope chip's ✕ does.
    func clearScope() {
        guard query.isScoped else { return }
        searchText = query.term
    }

    /// Days in the committed selection, inclusive.
    var selectedDayCount: Int? {
        guard let range = selectedRange else { return nil }
        let days = calendar.dateComponents([.day], from: range.lowerBound, to: range.upperBound).day ?? 0
        return days + 1
    }

    /// Chip and heatmap-footer copy — "12 – 18 Mar 2026 · 7 days".
    var selectionSummary: String? {
        guard let range = selectedRange, let days = selectedDayCount else { return nil }
        let end = range.upperBound.formatted(.dateTime.day().month(.abbreviated).year())
        guard days > 1 else { return String(localized: "\(end) · 1 day") }

        let start: String
        if calendar.isDate(range.lowerBound, equalTo: range.upperBound, toGranularity: .year) {
            start = calendar.isDate(range.lowerBound, equalTo: range.upperBound, toGranularity: .month)
                ? range.lowerBound.formatted(.dateTime.day())
                : range.lowerBound.formatted(.dateTime.day().month(.abbreviated))
        } else {
            start = range.lowerBound.formatted(.dateTime.day().month(.abbreviated).year())
        }
        return String(localized: "\(start) – \(end) · \(days) days")
    }

    // MARK: Heatmap data

    /// Year the heatmap grid currently displays.
    private(set) var displayedYear: Int

    /// Releases per day ("yyyy-MM-dd") for the displayed year.
    var releaseCounts: [String: Int] { heatmapsByYear[displayedYear] ?? [:] }

    var isLoadingHeatmap: Bool { loadingYears.contains(displayedYear) }

    var canShowPreviousYear: Bool { displayedYear > Self.earliestYear }
    /// False on the newest allowed year — the forward chevron hides then.
    var canShowNextYear: Bool { displayedYear < currentYear }

    private var heatmapsByYear: [Int: [String: Int]] = [:]
    private var loadingYears: Set<Int> = []

    /// TMDB's earliest films are 1870s shorts.
    private static let earliestYear = 1874
    /// Discover pages sampled per year for the density grid (20 titles each).
    private static let heatmapPageLimit = 10
    /// Discover pages fetched for a committed range's result list.
    private static let resultPageLimit = 3

    private let tmdb = TMDBService.shared
    private var searchTask: Task<Void, Never>?

    private var calendar: Calendar { HeatmapCalendar.current }
    private var currentYear: Int { calendar.component(.year, from: Date()) }

    init() {
        displayedYear = Calendar(identifier: .gregorian).component(.year, from: Date())
    }

    // MARK: - Heatmap navigation

    /// Called when the filter panel opens: land on the committed selection's
    /// year so the highlighted squares are in view.
    func prepareHeatmap() {
        if let range = selectedRange {
            displayedYear = min(calendar.component(.year, from: range.lowerBound), currentYear)
        }
    }

    func showPreviousYear() {
        guard canShowPreviousYear else { return }
        displayedYear -= 1
    }

    func showNextYear() {
        guard canShowNextYear else { return }
        displayedYear += 1
    }

    /// Builds the displayed year's per-day release counts from its most
    /// popular titles, once per year per session.
    func loadHeatmapIfNeeded() async {
        let year = displayedYear
        guard heatmapsByYear[year] == nil, !loadingYears.contains(year), tmdb.isConfigured else { return }
        loadingYears.insert(year)
        defer { loadingYears.remove(year) }

        let from = "\(year)-01-01"
        let to = "\(year)-12-31"
        do {
            let first = try await tmdb.discoverMoviesReleased(from: from, to: to, page: 1)
            var items = first.items
            let pageCount = min(first.totalPages, Self.heatmapPageLimit)
            if pageCount > 1 {
                items += try await withThrowingTaskGroup(of: [TMDBMediaItem].self) { group in
                    for page in 2...pageCount {
                        group.addTask { [tmdb] in
                            try await tmdb.discoverMoviesReleased(from: from, to: to, page: page).items
                        }
                    }
                    return try await group.reduce(into: []) { $0 += $1 }
                }
            }

            var counts: [String: Int] = [:]
            for item in items {
                if let date = item.releaseDate {
                    counts[date, default: 0] += 1
                }
            }
            heatmapsByYear[year] = counts
        } catch {
            print("Heatmap load failed for \(year): \(error)")
        }
    }

    // MARK: - Day selection

    /// One tap on a heatmap square. The first tap clears any completed
    /// selection and anchors a new one; tapping the anchor again commits a
    /// single day; tapping any other square commits the span between the two
    /// (either direction).
    func tapDay(_ day: Date) {
        let day = calendar.startOfDay(for: day)
        guard let anchor = pendingAnchor else {
            selectedRange = nil
            pendingAnchor = day
            return
        }
        commit(range: min(anchor, day)...max(anchor, day))
    }

    /// Done with an unfinished anchor commits it as a single-day selection.
    func finishSelecting() {
        guard let anchor = pendingAnchor else { return }
        commit(range: anchor...anchor)
    }

    func clearSelection() {
        pendingAnchor = nil
        guard selectedRange != nil else { return }
        selectedRange = nil
        scheduleSearch()
    }

    // MARK: - Person filmography

    /// Switch the results over to a person's filmography, clearing any text
    /// query or date filter that was in effect.
    func showFilmography(for person: PersonRef) {
        pendingAnchor = nil
        selectedRange = nil
        activePerson = person
        if searchText.isEmpty {
            scheduleSearch()
        } else {
            // Emptying the field would otherwise clear `activePerson` via the
            // `searchText` observer; suppress that for this one assignment.
            suppressPersonClear = true
            searchText = ""
            suppressPersonClear = false
        }
    }

    func clearPerson() {
        guard activePerson != nil else { return }
        activePerson = nil
        scheduleSearch()
    }

    private func commit(range: ClosedRange<Date>) {
        pendingAnchor = nil
        selectedRange = range
        scheduleSearch()
    }

    // MARK: - Search

    private func scheduleSearch(debounce: Bool = false) {
        searchTask?.cancel()
        searchTask = Task {
            if debounce {
                // Wait for the user to stop typing.
                try? await Task.sleep(nanoseconds: 300_000_000)
                guard !Task.isCancelled else { return }
            }
            await performSearch()
        }
    }

    private func performSearch() async {
        let parsed = SearchQuery(parsing: searchText)
        let term = parsed.term

        guard hasActiveQuery else {
            results = []
            people = []
            isSearching = false
            return
        }

        // "actors:" with nothing after it: show that scope's prompt rather
        // than firing a request for an empty term.
        if parsed.isAwaitingTerm && selectedRange == nil && activePerson == nil {
            results = []
            people = []
            isSearching = false
            return
        }

        isSearching = true
        defer { isSearching = false }

        do {
            var items: [TMDBMediaItem] = []
            var found: [TMDBPersonItem] = []

            if let person = activePerson {
                items = try await tmdb.filmography(personId: person.id)
            } else if term.isEmpty, let range = selectedRange {
                items = try await moviesReleased(in: range)
            } else {
                (items, found) = try await scopedSearch(parsed)
                if let bounds = activeDayBounds(for: term) {
                    // Release dates are "yyyy-MM-dd", so day strings compare
                    // lexicographically — no timezone juggling.
                    items = items.filter { item in
                        guard let date = item.releaseDate else { return false }
                        return date >= bounds.from && date <= bounds.to
                    }
                }
            }
            guard !Task.isCancelled else { return }
            results = items
            people = found
        } catch is CancellationError {
        } catch {
            guard !Task.isCancelled else { return }
            print("Search failed: \(error)")
            results = []
            people = []
        }
    }

    /// Dispatches to the endpoints the query's scope asks for. Android and
    /// Windows implement the same behavior, including the rule that an
    /// *additive* lookup failure never blanks the primary result.
    private func scopedSearch(
        _ query: SearchQuery
    ) async throws -> ([TMDBMediaItem], [TMDBPersonItem]) {
        let term = query.term
        switch query.scope {
        case .movies:
            return (try await tmdb.searchMovies(query: term), [])

        case .shows:
            return (try await tmdb.searchShows(query: term), [])

        case .people:
            // People are the point of this scope, so they propagate failures;
            // titles are the additive section.
            async let titles = try? tmdb.searchMulti(query: term)
            let found = try await tmdb.searchPeople(query: term)
            return (await titles ?? [], found)

        case .all:
            async let found = try? tmdb.searchPeople(query: term)
            let titles = try await tmdb.searchMulti(query: term)
            return (titles, await found ?? [])
        }
    }

    // MARK: - Trending

    /// Fills the idle grid, once per session.
    func loadTrendingIfNeeded() async {
        guard trending.isEmpty, !isLoadingTrending, tmdb.isConfigured else { return }
        isLoadingTrending = true
        defer { isLoadingTrending = false }
        do {
            trending = try await tmdb.trending()
        } catch {
            print("Trending load failed: \(error)")
        }
    }

    /// Most popular movies released inside the committed range.
    private func moviesReleased(in range: ClosedRange<Date>) async throws -> [TMDBMediaItem] {
        let from = dayString(range.lowerBound)
        let to = dayString(range.upperBound)
        var items: [TMDBMediaItem] = []
        var page = 1
        var totalPages = 1
        repeat {
            let result = try await tmdb.discoverMoviesReleased(from: from, to: to, page: page)
            items += result.items
            totalPages = result.totalPages
            page += 1
        } while page <= min(totalPages, Self.resultPageLimit)
        return items
    }

    /// Day-string bounds to filter text results by: the committed heatmap
    /// selection wins; otherwise a date parsed from the query itself
    /// (e.g. "July 2024") still narrows the results.
    private func activeDayBounds(for query: String) -> (from: String, to: String)? {
        if let range = selectedRange {
            return (dayString(range.lowerBound), dayString(range.upperBound))
        }
        if let parsed = parseDateFromQuery(query) {
            // The month interval's end is the next month's first instant.
            return (dayString(parsed.start), dayString(parsed.end.addingTimeInterval(-1)))
        }
        return nil
    }

    /// Extract a date range from natural language in the query (e.g. "July 2024").
    private func parseDateFromQuery(_ query: String) -> (start: Date, end: Date)? {
        guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.date.rawValue) else { return nil }
        let matches = detector.matches(in: query, options: [], range: NSRange(location: 0, length: query.utf16.count))

        for match in matches {
            if let date = match.date {
                // Assume if the string contains a 4-digit year, it might be a month/year query
                if let interval = calendar.dateInterval(of: .month, for: date) {
                    // For "July 2024", NSDataDetector usually gives noon on some day in July,
                    // we want the whole month.
                    return (interval.start, interval.end)
                }
            }
        }
        return nil
    }

    /// "yyyy-MM-dd" in the heatmap calendar — TMDB's release-date format.
    private func dayString(_ date: Date) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }
}
