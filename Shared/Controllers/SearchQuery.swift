//
//  SearchQuery.swift
//  Edendale
//
//  Keyword prefixes that scope a search ("actors:", "movies:", "shows:").
//  Native Swift search-prefix grammar. The independent Android Kotlin and
//  Windows C# implementations must stay aligned, so the keyword table below
//  is kept in the same order.
//

import Foundation

/// What a search field value is scoped to. `.all` is the unprefixed default:
/// titles first, people alongside.
enum SearchScope: Hashable, Sendable {
    case all
    case people
    case movies
    case shows

    /// Chip copy for the active scope.
    var label: String {
        switch self {
        case .all: String(localized: "All")
        case .people: String(localized: "People")
        case .movies: String(localized: "Films")
        case .shows: String(localized: "Series")
        }
    }
}

/// A raw search field value split into the scope its keyword prefix asks for
/// and the `term` TMDB should actually see.
///
/// The grammar is a keyword, a colon, and the rest of the line — case
/// insensitive, singular or plural, whitespace after the colon optional:
///
///     actors: tom hanks   ->  SearchQuery(.people, "tom hanks")
///     Movies:alien        ->  SearchQuery(.movies, "alien")
///     Alien: Romulus      ->  SearchQuery(.all, "Alien: Romulus")
///
/// An unrecognised `word:` is deliberately left alone: titles legitimately
/// contain colons, and silently eating "Alien:" would make them unsearchable.
struct SearchQuery: Hashable, Sendable {
    let scope: SearchScope
    let term: String

    var isScoped: Bool { scope != .all }

    /// True when a prefix was typed but nothing has been searched for yet.
    var isAwaitingTerm: Bool { isScoped && term.isEmpty }

    /// Recognised prefixes, without the colon.
    private static let keywords: [String: SearchScope] = [
        "actor": .people,
        "actors": .people,
        "actress": .people,
        "actresses": .people,
        "person": .people,
        "people": .people,
        "cast": .people,
        "movie": .movies,
        "movies": .movies,
        "film": .movies,
        "films": .movies,
        "show": .shows,
        "shows": .shows,
        "tv": .shows,
        "series": .shows
    ]

    init(scope: SearchScope, term: String) {
        self.scope = scope
        self.term = term
    }

    init(parsing raw: String) {
        guard let colon = raw.firstIndex(of: ":") else {
            self.init(scope: .all, term: raw.trimmed)
            return
        }

        let keyword = raw[raw.startIndex..<colon].trimmed.lowercased()
        guard let scope = Self.keywords[keyword] else {
            self.init(scope: .all, term: raw.trimmed)
            return
        }
        self.init(scope: scope, term: String(raw[raw.index(after: colon)...]).trimmed)
    }
}

private extension StringProtocol {
    var trimmed: String {
        trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
