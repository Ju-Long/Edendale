//
//  AppRouteTests.swift
//  EdendaleTests
//

import Foundation
import Testing
@testable import Edendale

@MainActor
struct AppRouteTests {
    @Test func everyRouteRoundTripsThroughItsURL() throws {
        let id = UUID(uuidString: "8BA77310-E230-46D0-BA65-12E31D5EA25B")!
        let routes: [AppRoute] = [
            .search("Blade Runner & more"),
            .media(MediaRef(id: 78, mediaType: .movie)),
            .media(MediaRef(id: 1399, mediaType: .tv)),
            .localMovie(id),
            .localShow(id),
            .playMovie(tmdbId: 78),
            .playEpisode(tmdbId: 63056),
            .playLocalMovie(id),
            .playLocalEpisode(id)
        ]

        for route in routes {
            let url = try #require(route.url)
            #expect(AppRoute(url: url) == route)
        }
    }

    @Test func searchURLsPreserveUnicodeAndPunctuation() throws {
        let route = AppRoute.search("Amélie: 2001 / restored")
        let url = try #require(route.url)

        #expect(AppRoute(url: url) == route)
    }

    @Test func rejectsUnknownOrIncompleteLinks() {
        #expect(AppRoute(url: URL(string: "https://example.com/movie/78")!) == nil)
        #expect(AppRoute(url: URL(string: "edendale://search")!) == nil)
        #expect(AppRoute(url: URL(string: "edendale://play/movie/not-an-id")!) == nil)
        #expect(AppRoute(url: URL(string: "edendale://library/movie/not-a-uuid")!) == nil)
    }
}
