//
//  YoungAudienceFilterTests.swift
//  EdendaleTests
//

import Foundation
import Testing
@testable import Edendale

@MainActor
struct YoungAudienceFilterTests {
    @Test func policyAcceptsOnlyRequestedCertificationsAndTVEquivalents() {
        for certification in ["PG", "pg", "PG-13", "PG 13", "PG13"] {
            #expect(YoungAudienceCertificationPolicy.allows(certification, for: .movie))
        }

        for certification in ["G", "R", "NC-17", "M18", "", "Not Rated"] {
            #expect(!YoungAudienceCertificationPolicy.allows(certification, for: .movie))
        }

        #expect(YoungAudienceCertificationPolicy.allows("TV-PG", for: .tv))
        #expect(YoungAudienceCertificationPolicy.allows("TV-14", for: .tv))
        #expect(!YoungAudienceCertificationPolicy.allows("TV-G", for: .tv))
        #expect(!YoungAudienceCertificationPolicy.allows("TV-MA", for: .tv))
        #expect(!YoungAudienceCertificationPolicy.allows("TV-14", for: .movie))
    }

    @Test func movieCertificationUsesRequestedRegionAndTheatricalPrecedence() throws {
        let response = try Self.decoder.decode(
            TMDBMovieReleaseDatesResponse.self,
            from: Data(
                """
                {"results": [
                  {"iso_3166_1": "SG", "release_dates": [
                    {"certification": "PG", "release_date": "2026-02-01", "type": 6},
                    {"certification": "PG13", "release_date": "2026-01-01", "type": 3}
                  ]},
                  {"iso_3166_1": "US", "release_dates": [
                    {"certification": "R", "release_date": "2026-01-02", "type": 3}
                  ]},
                  {"iso_3166_1": "GB", "release_dates": [
                    {"certification": "PG", "release_date": "2026-01-01", "type": 3},
                    {"certification": "15", "release_date": "2026-02-01", "type": 3}
                  ]}
                ]}
                """.utf8
            )
        )

        #expect(response.certification(in: "sg") == "PG13")
        #expect(response.certification(in: "US") == "R")
        #expect(response.certification(in: "GB") == nil)
        #expect(response.certification(in: "CA") == nil)
    }

    @Test func televisionCertificationUsesOnlyTheRequestedRegion() throws {
        let response = try Self.decoder.decode(
            TMDBTVContentRatingsResponse.self,
            from: Data(
                """
                {"results": [
                  {"iso_3166_1": "SG", "rating": "PG13"},
                  {"iso_3166_1": "US", "rating": "TV-14"}
                ]}
                """.utf8
            )
        )

        #expect(response.certification(in: "SG") == "PG13")
        #expect(response.certification(in: "us") == "TV-14")
        #expect(response.certification(in: "CA") == nil)
    }

    @Test func preferenceDefaultsOffAndPersists() {
        let fixture = DefaultsFixture()
        defer { fixture.cleanup() }
        let provider = StubCertificationProvider()

        let initial = YoungAudienceFilter(defaults: fixture.defaults, provider: provider)
        #expect(!initial.isEnabled)

        initial.isEnabled = true
        let restored = YoungAudienceFilter(defaults: fixture.defaults, provider: provider)
        #expect(restored.isEnabled)

        restored.isEnabled = false
        #expect(!fixture.defaults.bool(forKey: YoungAudienceFilter.defaultsKey))
    }

    @Test func disabledFilterReturnsEveryItemWithoutRatingRequests() async {
        let fixture = DefaultsFixture()
        defer { fixture.cleanup() }
        let provider = StubCertificationProvider()
        let filter = YoungAudienceFilter(defaults: fixture.defaults, provider: provider)
        let items = [Self.item(3), Self.item(1), Self.item(2, type: .tv)]

        await filter.verify(items.map(\.ref))

        #expect(filter.visible(items) == items)
        let requests = await provider.requestCount
        #expect(requests == 0)
    }

    @Test func enabledFilterFailsClosedAndPreservesAllowedOrder() async {
        let fixture = DefaultsFixture(enabled: true)
        defer { fixture.cleanup() }

        let pg = MediaRef(id: 10, mediaType: .movie)
        let restricted = MediaRef(id: 20, mediaType: .movie)
        let tv14 = MediaRef(id: 30, mediaType: .tv)
        let unrated = MediaRef(id: 40, mediaType: .tv)
        let provider = StubCertificationProvider(responses: [
            pg: [.found("PG")],
            restricted: [.found("R")],
            tv14: [.found("TV-14")],
            unrated: [.unrated]
        ])
        let filter = YoungAudienceFilter(defaults: fixture.defaults, provider: provider)
        let items = [
            Self.item(tv14), Self.item(restricted), Self.item(pg), Self.item(unrated)
        ]

        #expect(filter.visible(items).isEmpty)
        await filter.verify(items.map(\.ref) + [pg])
        #expect(filter.visible(items).map(\.ref) == [tv14, pg])

        await filter.verify(items.map(\.ref))
        let requests = await provider.requestCount
        #expect(requests == 4)
    }

    @Test func unavailableCertificationCanRetryButAlwaysFailsClosed() async {
        let fixture = DefaultsFixture(enabled: true)
        defer { fixture.cleanup() }

        let ref = MediaRef(id: 50, mediaType: .movie)
        let provider = StubCertificationProvider(responses: [
            ref: [.unavailable, .found("PG-13")]
        ])
        let filter = YoungAudienceFilter(defaults: fixture.defaults, provider: provider)

        await filter.verify([ref])
        #expect(!filter.allows(ref))
        await filter.verify([ref])
        #expect(filter.allows(ref))
    }

    private static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        return decoder
    }()

    private static func item(_ id: Int, type: TMDBMediaType = .movie) -> TMDBMediaItem {
        item(MediaRef(id: id, mediaType: type))
    }

    private static func item(_ ref: MediaRef) -> TMDBMediaItem {
        TMDBMediaItem(
            id: ref.id,
            mediaType: ref.mediaType,
            title: "Title \(ref.id)",
            overview: nil,
            posterPath: nil,
            backdropPath: nil,
            voteAverage: nil,
            releaseDate: nil
        )
    }
}

private final class DefaultsFixture {
    let suiteName = "YoungAudienceFilterTests.\(UUID().uuidString)"
    let defaults: UserDefaults

    init(enabled: Bool = false) {
        defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        if enabled {
            defaults.set(true, forKey: YoungAudienceFilter.defaultsKey)
        }
    }

    func cleanup() {
        defaults.removePersistentDomain(forName: suiteName)
    }
}

private actor StubCertificationProvider: ContentCertificationProviding {
    nonisolated let contextIdentifier = "test"
    private var responses: [MediaRef: [ContentCertificationLookup]]
    private(set) var requestCount = 0

    init(responses: [MediaRef: [ContentCertificationLookup]] = [:]) {
        self.responses = responses
    }

    func certification(for ref: MediaRef) async -> ContentCertificationLookup {
        requestCount += 1
        guard var values = responses[ref], !values.isEmpty else { return .unrated }
        let value = values.removeFirst()
        responses[ref] = values
        return value
    }
}
