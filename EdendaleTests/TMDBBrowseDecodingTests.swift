//
//  TMDBBrowseDecodingTests.swift
//  EdendaleTests
//
//  Fixture-based decoding tests for the TMDB browse DTOs.
//

import Testing
import Foundation
@testable import Edendale

struct TMDBBrowseDecodingTests {

    @Test func mediaParserAcceptsABareTerminalYear() {
        let parsed = MediaParser.parse(
            fileURL: URL(fileURLWithPath: "/Movies/Movie.Title.2024.mkv")
        )
        guard case .movie(let title, let year) = parsed else {
            Issue.record("Expected a movie filename")
            return
        }
        #expect(title == "Movie Title")
        #expect(year == 2024)
    }

    /// Mirrors TMDBService's decoder configuration.
    private static let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.keyDecodingStrategy = .convertFromSnakeCase
        return d
    }()

    // MARK: - Search query prefixes

    // Android and Windows carry equivalent native tests; keep all three in step.

    @Test func everyPeopleAliasScopesToPeople() {
        for alias in ["actor", "actors", "actress", "actresses", "person", "people", "cast"] {
            let query = SearchQuery(parsing: "\(alias): tom hanks")
            #expect(query.scope == .people, "alias \(alias):")
            #expect(query.term == "tom hanks", "alias \(alias):")
        }
    }

    @Test func everyMovieAliasScopesToMovies() {
        for alias in ["movie", "movies", "film", "films"] {
            let query = SearchQuery(parsing: "\(alias): alien")
            #expect(query.scope == .movies, "alias \(alias):")
            #expect(query.term == "alien", "alias \(alias):")
        }
    }

    @Test func everyShowAliasScopesToShows() {
        for alias in ["show", "shows", "tv", "series"] {
            let query = SearchQuery(parsing: "\(alias): severance")
            #expect(query.scope == .shows, "alias \(alias):")
            #expect(query.term == "severance", "alias \(alias):")
        }
    }

    @Test func prefixMatchingIgnoresCaseAndSurroundingWhitespace() {
        #expect(SearchQuery(parsing: "Actors: tom hanks").scope == .people)
        #expect(SearchQuery(parsing: "ACTORS:tom hanks").term == "tom hanks")
        let spaced = SearchQuery(parsing: "  Movies :   alien  ")
        #expect(spaced.scope == .movies)
        #expect(spaced.term == "alien")
    }

    /// Titles legitimately contain colons — eating them would make the title
    /// unsearchable.
    @Test func unrecognisedPrefixStaysLiteralText() {
        let query = SearchQuery(parsing: "Alien: Romulus")
        #expect(query.scope == .all)
        #expect(query.term == "Alien: Romulus")
        #expect(query.isScoped == false)
    }

    @Test func onlyALeadingKeywordCounts() {
        let query = SearchQuery(parsing: "Star Trek: movies of the week")
        #expect(query.scope == .all)
        #expect(query.term == "Star Trek: movies of the week")
    }

    @Test func noColonIsAlwaysAnUnscopedSearch() {
        let query = SearchQuery(parsing: "  blade runner  ")
        #expect(query.scope == .all)
        #expect(query.term == "blade runner")
    }

    @Test func prefixWithNoTermIsScopedButAwaitingInput() {
        let query = SearchQuery(parsing: "actors:")
        #expect(query.scope == .people)
        #expect(query.term.isEmpty)
        #expect(query.isAwaitingTerm)
    }

    @Test func emptyInputIsAnUnscopedEmptyTerm() {
        let query = SearchQuery(parsing: "")
        #expect(query.scope == .all)
        #expect(query.term.isEmpty)
        #expect(query.isAwaitingTerm == false)
    }

    @Test func onlyTheFirstColonSplitsTheQuery() {
        let query = SearchQuery(parsing: "movies: Alien: Romulus")
        #expect(query.scope == .movies)
        #expect(query.term == "Alien: Romulus")
    }

    // MARK: - People

    @Test func decodesPeopleSearchResultsWithKnownForTitles() throws {
        let json = """
        {"results": [
            {"id": 31, "name": "Tom Hanks", "profile_path": "/profile.jpg",
             "known_for": [{"title": "Big"}, {"name": "Band of Brothers"},
                           {"title": "Forrest Gump"}, {"title": "Cast Away"}]},
            {"id": 88, "name": "No Photo Person"}
        ]}
        """
        let page = try Self.decoder.decode(
            TMDBPagedResponse<TMDBPersonItemRaw>.self, from: Data(json.utf8)
        )
        let people = page.results.map(\.item)

        #expect(people.count == 2)
        #expect(people[0].name == "Tom Hanks")
        // Capped at three, and movie/show titles come from different keys.
        #expect(people[0].knownFor == ["Big", "Band of Brothers", "Forrest Gump"])
        #expect(people[0].knownForText == "Big · Band of Brothers · Forrest Gump")
        #expect(people[0].profileURL?.absoluteString
            == "https://image.tmdb.org/t/p/w185/profile.jpg")
        #expect(people[1].profileURL == nil)
        #expect(people[1].knownForText == nil)
    }

    @Test func decodesPersonDetailWithBiographyAndVitals() throws {
        let json = """
        {"id": 31, "name": "Tom Hanks", "biography": "An American actor.",
         "profile_path": "/profile.jpg", "birthday": "1956-07-09",
         "place_of_birth": "Concord, California", "known_for_department": "Acting"}
        """
        let detail = try Self.decoder
            .decode(TMDBPersonDetailRaw.self, from: Data(json.utf8)).detail

        #expect(detail.name == "Tom Hanks")
        #expect(detail.biography == "An American actor.")
        #expect(detail.knownForDepartment == "Acting")
        #expect(detail.vitals == "1956 · Concord, California")
        #expect(detail.profileURL?.absoluteString
            == "https://image.tmdb.org/t/p/h632/profile.jpg")
    }

    @Test func personDetailVitalsSpanBirthAndDeathYears() throws {
        let json = """
        {"id": 2, "name": "Gene Wilder", "birthday": "1933-06-11",
         "deathday": "2016-08-29", "place_of_birth": "Milwaukee, Wisconsin"}
        """
        let detail = try Self.decoder
            .decode(TMDBPersonDetailRaw.self, from: Data(json.utf8)).detail

        #expect(detail.vitals == "1933 – 2016 · Milwaukee, Wisconsin")
    }

    /// TMDB returns "" rather than omitting unknown person fields.
    @Test func blankPersonFieldsAreDroppedRatherThanRenderedEmpty() throws {
        let json = """
        {"id": 31, "name": "Tom Hanks", "biography": "", "place_of_birth": ""}
        """
        let detail = try Self.decoder
            .decode(TMDBPersonDetailRaw.self, from: Data(json.utf8)).detail

        #expect(detail.biography == nil)
        #expect(detail.placeOfBirth == nil)
        #expect(detail.vitals == nil)
    }

    @Test func decodesMixedTrendingAndFiltersPeople() throws {
        let json = """
        {"results": [
            {"id": 603, "media_type": "movie", "title": "The Matrix",
             "overview": "A hacker learns the truth.", "poster_path": "/p.jpg",
             "backdrop_path": "/b.jpg", "vote_average": 8.2, "release_date": "1999-03-30"},
            {"id": 1396, "media_type": "tv", "name": "Breaking Bad",
             "first_air_date": "2008-01-20"},
            {"id": 500, "media_type": "person", "name": "Some Actor"}
        ]}
        """
        let page = try Self.decoder.decode(
            TMDBPagedResponse<TMDBMediaItemRaw>.self, from: Data(json.utf8)
        )
        let items = page.results.compactMap { $0.item(defaultType: .movie) }

        #expect(items.count == 2)
        #expect(items[0].title == "The Matrix")
        #expect(items[0].mediaType == .movie)
        #expect(items[0].year == 1999)
        #expect(items[0].posterURL?.absoluteString == "https://image.tmdb.org/t/p/w342/p.jpg")
        #expect(items[1].title == "Breaking Bad")
        #expect(items[1].mediaType == .tv)
        #expect(items[1].year == 2008)
    }

    @Test func listItemsWithoutMediaTypeUseTheDefault() throws {
        let json = """
        {"results": [{"id": 27205, "title": "Inception", "release_date": "2010-07-15"}]}
        """
        let page = try Self.decoder.decode(
            TMDBPagedResponse<TMDBMediaItemRaw>.self, from: Data(json.utf8)
        )
        let items = page.results.compactMap { $0.item(defaultType: .tv) }

        #expect(items.first?.mediaType == .tv)
    }

    @Test func formatsShelfDatesForTheRequestedLocale() throws {
        func item(date: String?) -> TMDBMediaItem {
            TMDBMediaItem(
                id: 1, mediaType: .movie, title: "T", overview: nil,
                posterPath: nil, backdropPath: nil, voteAverage: nil,
                releaseDate: date
            )
        }

        let locale = Locale(identifier: "en_US")
        #expect(item(date: "1999-03-30").detailedDateText(locale: locale) == "Mar 30, 1999")
        #expect(item(date: "2008-01-01").detailedDateText(locale: locale) == "Jan 1, 2008")
        #expect(item(date: "2010-07-22").detailedDateText(locale: locale) == "Jul 22, 2010")
        #expect(item(date: "2011-11-13").detailedDateText(locale: locale) == "Nov 13, 2011")
        #expect(item(date: "2012-05-03").detailedDateText(locale: locale) == "May 3, 2012")
        // Bare year falls back when TMDB has no full date.
        #expect(item(date: "1968").detailedDateText == "1968")
        #expect(item(date: nil).detailedDateText == nil)
    }

    @Test func picksOfficialYouTubeTrailerFirst() throws {
        let json = """
        {"results": [
            {"id": "a", "key": "clipKey", "site": "YouTube", "type": "Clip", "official": true},
            {"id": "b", "key": "fanKey", "site": "YouTube", "type": "Trailer", "official": false},
            {"id": "c", "key": "vimeoKey", "site": "Vimeo", "type": "Trailer", "official": true},
            {"id": "d", "key": "officialKey", "site": "YouTube", "type": "Trailer", "official": true}
        ]}
        """
        let videos = try Self.decoder.decode(
            TMDBPagedResponse<TMDBVideo>.self, from: Data(json.utf8)
        ).results

        #expect(TMDBVideo.bestTrailer(in: videos)?.key == "officialKey")
        // Without an official trailer, any YouTube trailer wins over a teaser.
        let unofficial = videos.filter { $0.official != true }
        #expect(TMDBVideo.bestTrailer(in: unofficial)?.key == "fanKey")
        // Teaser is the last resort; non-YouTube sources never play.
        let teaserOnly = [
            TMDBVideo(id: "e", key: "teaserKey", name: nil, site: "YouTube", type: "Teaser", official: nil),
            TMDBVideo(id: "f", key: "vimeoKey", name: nil, site: "Vimeo", type: "Trailer", official: true)
        ]
        #expect(TMDBVideo.bestTrailer(in: teaserOnly)?.key == "teaserKey")
    }

    @Test func mapsMovieDetailIntoMediaDetail() throws {
        let json = """
        {"id": 62, "title": "2001: A Space Odyssey",
         "tagline": "An epic drama of adventure and exploration",
         "overview": "Humanity finds a mysterious object buried beneath the lunar surface.",
         "release_date": "1968-04-02", "runtime": 149,
         "genres": [{"id": 878, "name": "Science Fiction"}],
         "vote_average": 8.3, "vote_count": 11500,
         "credits": {
            "cast": [{"id": 1, "name": "Keir Dullea", "character": "Dr. Dave Bowman", "profile_path": null}],
            "crew": [{"id": 2, "name": "Stanley Kubrick", "job": "Director"}]
         }}
        """
        let movie = try Self.decoder.decode(TMDBMovieDetail.self, from: Data(json.utf8))
        let detail = MediaDetail(movie: movie)

        #expect(detail.ref == MediaRef(id: 62, mediaType: .movie))
        #expect(detail.year == 1968)
        #expect(detail.runtimeMinutes == 149)
        #expect(detail.genres == ["Science Fiction"])
        #expect(detail.attribution == "Directed by Stanley Kubrick")
        #expect(detail.cast.first?.name == "Keir Dullea")
    }

    @Test func mapsTVDetailIntoMediaDetail() throws {
        let json = """
        {"id": 1396, "name": "Breaking Bad", "tagline": "",
         "overview": "A chemistry teacher turns to crime.",
         "first_air_date": "2008-01-20", "episode_run_time": [47],
         "number_of_seasons": 5, "number_of_episodes": 62,
         "genres": [{"id": 18, "name": "Drama"}],
         "vote_average": 8.9, "vote_count": 12000,
         "created_by": [{"id": 3, "name": "Vince Gilligan"}]}
        """
        let tv = try Self.decoder.decode(TMDBTVDetail.self, from: Data(json.utf8))
        let detail = MediaDetail(tv: tv)

        #expect(detail.ref == MediaRef(id: 1396, mediaType: .tv))
        #expect(detail.year == 2008)
        #expect(detail.runtimeMinutes == 47)
        #expect(detail.seasonCount == 5)
        #expect(detail.episodeCount == 62)
        #expect(detail.attribution == "Created by Vince Gilligan")
        // Empty taglines collapse to nil so the UI skips the quote block.
        #expect(detail.tagline == nil)
    }

    @Test func ordersSeasonsAndDropsEmptyOnes() throws {
        let json = """
        {"id": 1396, "name": "Breaking Bad",
         "number_of_seasons": 2, "number_of_episodes": 20,
         "seasons": [
            {"id": 30, "name": "Specials", "season_number": 0, "episode_count": 3,
             "air_date": "2009-02-17", "overview": "", "poster_path": null},
            {"id": 32, "name": "Season 2", "season_number": 2, "episode_count": 13,
             "air_date": "2009-03-08", "overview": "", "poster_path": "/b.jpg"},
            {"id": 31, "name": "Season 1", "season_number": 1, "episode_count": 7,
             "air_date": "2008-01-20", "overview": "", "poster_path": "/a.jpg"},
            {"id": 33, "name": "Season 3", "season_number": 3, "episode_count": 0,
             "air_date": null, "overview": "", "poster_path": null}
         ]}
        """
        let tv = try Self.decoder.decode(TMDBTVDetail.self, from: Data(json.utf8))
        let detail = MediaDetail(tv: tv)

        // Announced-but-empty seasons are dropped; Specials sort last.
        #expect(detail.seasons.map(\.seasonNumber) == [1, 2, 0])
        #expect(detail.seasons.first?.year == 2008)
    }

    @Test func moviesCarryNoSeasons() throws {
        let json = """
        {"id": 62, "title": "2001: A Space Odyssey", "vote_average": 8.3}
        """
        let movie = try Self.decoder.decode(TMDBMovieDetail.self, from: Data(json.utf8))
        #expect(MediaDetail(movie: movie).seasons.isEmpty)
    }

    @Test func decodesSeasonEpisodeList() throws {
        let json = """
        {"id": 3572, "name": "Season 1", "season_number": 1, "overview": "",
         "episodes": [
            {"id": 62085, "name": "Pilot", "overview": "Walter White gets a diagnosis.",
             "still_path": "/still.jpg", "episode_number": 1, "season_number": 1,
             "air_date": "2008-01-20", "vote_average": 8.2, "runtime": 58},
            {"id": 62086, "name": "Cat's in the Bag...", "overview": null,
             "still_path": null, "episode_number": 2, "season_number": 1,
             "air_date": "2008-01-27", "vote_average": 8.1, "runtime": null}
         ]}
        """
        let season = try Self.decoder.decode(TMDBSeasonDetail.self, from: Data(json.utf8))

        #expect(season.episodes.count == 2)
        #expect(season.episodes.first?.episodeCode == "S01E01")
        #expect(season.episodes.first?.runtime == 58)
        // A missing runtime must not break the caption.
        #expect(season.episodes.last?.runtime == nil)
    }
}
