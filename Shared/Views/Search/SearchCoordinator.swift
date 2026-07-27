//
//  SearchCoordinator.swift
//  Edendale
//
//  Cross-tab bridge for "show this actor's films": the detail view sets a
//  `pendingPerson`, `RootView` switches to the Search tab, and `SearchView`
//  loads that person's filmography and clears the request.
//

import Foundation
import Observation

/// A TMDB person (actor/actress) whose filmography the Search tab surfaces.
struct PersonRef: Hashable, Sendable {
    let id: Int
    let name: String
}

@Observable
@MainActor
final class SearchCoordinator {
    /// Set to route the Search tab to a person's filmography; `SearchView`
    /// consumes it (back to `nil`) once the search kicks off.
    var pendingPerson: PersonRef?

    /// Search requested outside the Search tab (Siri, a widget/deep link, or
    /// another app surface). An identity token lets the same text be requested
    /// twice in succession and still trigger a fresh navigation event.
    private(set) var pendingSearch: SearchRequest?

    func requestSearch(_ query: String) {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        pendingSearch = SearchRequest(query: query)
    }

    func consumeSearch(_ requestID: UUID) {
        guard pendingSearch?.id == requestID else { return }
        pendingSearch = nil
    }
}

struct SearchRequest: Identifiable, Equatable, Sendable {
    let id = UUID()
    let query: String
}
