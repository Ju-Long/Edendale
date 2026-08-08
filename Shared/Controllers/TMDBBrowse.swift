//
//  TMDBBrowse.swift
//  Edendale
//
//  Browse/discover endpoints and DTOs for the Movies & Shows tab.
//  Enrichment lookups stay in TMDBService.swift — this file is UI-facing
//  and never runs on the import fast path.
//

import Foundation

// MARK: - Shared types

nonisolated enum TMDBMediaType: String, Codable, Hashable, Sendable {
    case movie
    case tv
}

/// Lightweight handle for navigation: enough to fetch a full detail record.
nonisolated struct MediaRef: Hashable, Sendable {
    let id: Int
    let mediaType: TMDBMediaType
}

/// TMDB image CDN URL builder.
enum TMDBImage {
    enum Size: String {
        case poster = "w342"
        case posterLarge = "w500"
        case profile = "w185"
        /// The person page's hero portrait.
        case profileLarge = "h632"
        case still = "w300"
        case backdrop = "w780"
        case original
    }

    static func url(_ path: String?, size: Size) -> URL? {
        path.flatMap { URL(string: "https://image.tmdb.org/t/p/\(size.rawValue)\($0)") }
    }
}

// MARK: - List item

/// Unified movie/show row for shelves and grids.
struct TMDBMediaItem: Identifiable, Hashable, Sendable {
    let id: Int
    let mediaType: TMDBMediaType
    let title: String
    let overview: String?
    let posterPath: String?
    let backdropPath: String?
    let voteAverage: Double?
    /// Raw "yyyy-MM-dd" release date (movies) or first-air date (shows).
    let releaseDate: String?

    var year: Int? { releaseDate.flatMap { Int($0.prefix(4)) } }

    /// Shelf caption in the user's locale using the release date from TMDB.
    /// Falls back to the bare year when TMDB has no full date.
    var detailedDateText: String? {
        detailedDateText(locale: .autoupdatingCurrent)
    }

    func detailedDateText(locale: Locale) -> String? {
        guard let releaseDate,
              let date = Self.releaseDateParser.date(from: releaseDate)
        else { return year.map(String.init) }
        return date.formatted(
            .dateTime.year().month(.abbreviated).day().locale(locale)
        )
    }

    private static let releaseDateParser: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    var ref: MediaRef { MediaRef(id: id, mediaType: mediaType) }
    var posterURL: URL? { TMDBImage.url(posterPath, size: .poster) }
    var backdropURL: URL? { TMDBImage.url(backdropPath, size: .backdrop) }
}

/// Raw decode shape: movie lists use `title`/`release_date`, TV lists use
/// `name`/`first_air_date`, and /trending/all mixes both plus people.
struct TMDBMediaItemRaw: Decodable {
    let id: Int
    let mediaType: String?
    let title: String?
    let name: String?
    let overview: String?
    let posterPath: String?
    let backdropPath: String?
    let voteAverage: Double?
    let releaseDate: String?
    let firstAirDate: String?

    /// Returns nil for unsupported media types (people in trending results).
    func item(defaultType: TMDBMediaType) -> TMDBMediaItem? {
        let type: TMDBMediaType
        if let mediaType {
            guard let known = TMDBMediaType(rawValue: mediaType) else { return nil }
            type = known
        } else {
            type = defaultType
        }
        return TMDBMediaItem(
            id: id,
            mediaType: type,
            title: title ?? name ?? String(localized: "Untitled"),
            overview: overview,
            posterPath: posterPath,
            backdropPath: backdropPath,
            voteAverage: voteAverage,
            releaseDate: releaseDate ?? firstAirDate
        )
    }
}

// MARK: - Genres

struct TMDBGenre: Decodable, Identifiable, Hashable, Sendable {
    let id: Int
    let name: String
}

private struct TMDBGenreList: Decodable {
    let genres: [TMDBGenre]
}

// MARK: - Videos

/// One entry from /{movie|tv}/{id}/videos — trailers, teasers, clips.
struct TMDBVideo: Decodable, Identifiable, Hashable, Sendable {
    let id: String
    /// YouTube video key — playback goes through the embed player.
    let key: String
    let name: String?
    let site: String?
    let type: String?
    let official: Bool?

    /// Best hero trailer: official YouTube trailers first, then any YouTube
    /// trailer, then a teaser as a last resort.
    static func bestTrailer(in videos: [TMDBVideo]) -> TMDBVideo? {
        let youtube = videos.filter { $0.site == "YouTube" }
        let trailers = youtube.filter { $0.type == "Trailer" }
        return trailers.first { $0.official == true }
            ?? trailers.first
            ?? youtube.first { $0.type == "Teaser" }
    }
}

// MARK: - Credits

struct TMDBCredits: Decodable, Sendable {
    let cast: [TMDBCastMember]?
    let crew: [TMDBCrewMember]?
}

struct TMDBCastMember: Decodable, Identifiable, Hashable, Sendable {
    let id: Int
    let name: String
    let character: String?
    let profilePath: String?

    var profileURL: URL? { TMDBImage.url(profilePath, size: .profile) }
}

struct TMDBCrewMember: Decodable, Sendable {
    let id: Int
    let name: String
    let job: String?
}

/// /person/{id}/combined_credits — a person's cast appearances across movies
/// and shows (each raw item carries its own `media_type`).
private struct TMDBPersonCredits: Decodable {
    let cast: [TMDBMediaItemRaw]
}

// MARK: - People

/// One result from /search/person: portrait plus best-known titles.
struct TMDBPersonItem: Identifiable, Hashable, Sendable {
    let id: Int
    let name: String
    let profilePath: String?
    /// Up to three titles TMDB considers this person best known for.
    let knownFor: [String]

    var profileURL: URL? { TMDBImage.url(profilePath, size: .profile) }

    var ref: PersonRef { PersonRef(id: id, name: name) }

    /// Caption under the portrait — "Big · Band of Brothers".
    var knownForText: String? {
        knownFor.isEmpty ? nil : knownFor.joined(separator: " · ")
    }
}

// Not private: decoding is covered by TMDBBrowseDecodingTests.
struct TMDBPersonItemRaw: Decodable {
    let id: Int
    let name: String
    let profilePath: String?
    let knownFor: [TMDBKnownForRaw]?

    /// `known_for` entries are movies or shows, so the title lives under
    /// either key.
    struct TMDBKnownForRaw: Decodable {
        let title: String?
        let name: String?
    }

    var item: TMDBPersonItem {
        TMDBPersonItem(
            id: id,
            name: name,
            profilePath: profilePath,
            knownFor: (knownFor ?? []).prefix(3).compactMap { $0.title ?? $0.name }
        )
    }
}

/// /person/{id} — the biography header of a person page. The filmography
/// stays a separate request so the two can be fetched concurrently.
struct PersonDetail: Identifiable, Hashable, Sendable {
    let id: Int
    let name: String
    let biography: String?
    let profilePath: String?
    let birthday: String?
    let deathday: String?
    let placeOfBirth: String?
    let knownForDepartment: String?

    var profileURL: URL? { TMDBImage.url(profilePath, size: .profileLarge) }

    var ref: PersonRef { PersonRef(id: id, name: name) }

    /// "1956 – 2016 · Concord, California" — the vitals line under the name.
    /// Nil when TMDB knows neither a date nor a place.
    var vitals: String? {
        let born = birthday.flatMap { $0.count >= 4 ? String($0.prefix(4)) : nil }
        let died = deathday.flatMap { $0.count >= 4 ? String($0.prefix(4)) : nil }
        let lifespan: String? = switch (born, died) {
        case let (born?, died?): "\(born) – \(died)"
        case let (born?, nil): born
        case let (nil, died?): "– \(died)"
        case (nil, nil): nil
        }
        let parts = [lifespan, placeOfBirth].compactMap { $0 }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }
}

// Not private: decoding is covered by TMDBBrowseDecodingTests.
struct TMDBPersonDetailRaw: Decodable {
    let id: Int
    let name: String
    let biography: String?
    let profilePath: String?
    let birthday: String?
    let deathday: String?
    let placeOfBirth: String?
    let knownForDepartment: String?

    var detail: PersonDetail {
        PersonDetail(
            id: id,
            name: name,
            biography: biography?.nonBlank,
            profilePath: profilePath,
            birthday: birthday?.nonBlank,
            deathday: deathday?.nonBlank,
            placeOfBirth: placeOfBirth?.nonBlank,
            knownForDepartment: knownForDepartment?.nonBlank
        )
    }
}

private extension String {
    /// TMDB returns "" rather than omitting unknown person fields.
    var nonBlank: String? {
        trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : self
    }
}

struct TMDBCreator: Decodable, Sendable {
    let id: Int
    let name: String
}

// MARK: - Seasons

/// One season as listed on a show's detail response. Enough to drive the
/// season picker without a second request per season.
struct TMDBSeasonSummary: Decodable, Identifiable, Hashable, Sendable {
    let id: Int
    let name: String
    let seasonNumber: Int
    let episodeCount: Int?
    let airDate: String?
    let overview: String?
    let posterPath: String?

    var year: Int? { airDate.flatMap { Int($0.prefix(4)) } }
    var posterURL: URL? { TMDBImage.url(posterPath, size: .poster) }
}

/// /tv/{id}/season/{n} — the season's episode list, fetched on demand when
/// the user picks a season in the detail view's episode browser.
struct TMDBSeasonDetail: Decodable, Sendable {
    let id: Int
    let name: String
    let seasonNumber: Int
    let overview: String?
    let episodes: [TMDBEpisodeDetail]
}

// MARK: - Details

struct TMDBMovieDetail: Decodable, Sendable {
    let id: Int
    let title: String
    let tagline: String?
    let overview: String?
    let posterPath: String?
    let backdropPath: String?
    let releaseDate: String?
    let runtime: Int?
    let genres: [TMDBGenre]?
    let voteAverage: Double?
    let voteCount: Int?
    let credits: TMDBCredits?
}

struct TMDBTVDetail: Decodable, Sendable {
    let id: Int
    let name: String
    let tagline: String?
    let overview: String?
    let posterPath: String?
    let backdropPath: String?
    let firstAirDate: String?
    let episodeRunTime: [Int]?
    let numberOfSeasons: Int?
    let numberOfEpisodes: Int?
    let genres: [TMDBGenre]?
    let voteAverage: Double?
    let voteCount: Int?
    let credits: TMDBCredits?
    let createdBy: [TMDBCreator]?
    let seasons: [TMDBSeasonSummary]?
}

/// Platform-neutral detail record the detail view renders, built from either
/// a movie or a TV detail response.
struct MediaDetail: Sendable {
    let ref: MediaRef
    let title: String
    let tagline: String?
    let overview: String?
    let posterPath: String?
    let backdropPath: String?
    /// Raw movie release date or TV first-air date from TMDB.
    let releaseDate: String?
    let year: Int?
    let runtimeMinutes: Int?
    let genres: [String]
    /// "Directed by X" for films, "Created by X" for shows.
    let attribution: String?
    let score: Double?
    let voteCount: Int?
    let cast: [TMDBCastMember]
    let seasonCount: Int?
    let episodeCount: Int?
    /// Seasons a show actually published, ordered on screen: numbered seasons
    /// ascending, Specials (season 0) last. Empty for movies.
    let seasons: [TMDBSeasonSummary]

    var backdropURL: URL? { TMDBImage.url(backdropPath, size: .original) }

    init(movie m: TMDBMovieDetail) {
        ref = MediaRef(id: m.id, mediaType: .movie)
        title = m.title
        tagline = m.tagline?.isEmpty == false ? m.tagline : nil
        overview = m.overview
        posterPath = m.posterPath
        backdropPath = m.backdropPath
        releaseDate = m.releaseDate
        year = m.releaseDate.flatMap { Int($0.prefix(4)) }
        runtimeMinutes = m.runtime
        genres = (m.genres ?? []).map(\.name)
        attribution = m.credits?.crew?.first { $0.job == "Director" }.map {
            String(localized: "Directed by \($0.name)")
        }
        score = m.voteAverage
        voteCount = m.voteCount
        cast = Array((m.credits?.cast ?? []).prefix(10))
        seasonCount = nil
        episodeCount = nil
        seasons = []
    }

    init(tv t: TMDBTVDetail) {
        ref = MediaRef(id: t.id, mediaType: .tv)
        title = t.name
        tagline = t.tagline?.isEmpty == false ? t.tagline : nil
        overview = t.overview
        posterPath = t.posterPath
        backdropPath = t.backdropPath
        releaseDate = t.firstAirDate
        year = t.firstAirDate.flatMap { Int($0.prefix(4)) }
        runtimeMinutes = t.episodeRunTime?.first
        genres = (t.genres ?? []).map(\.name)
        attribution = t.createdBy?.first.map { String(localized: "Created by \($0.name)") }
        score = t.voteAverage
        voteCount = t.voteCount
        cast = Array((t.credits?.cast ?? []).prefix(10))
        seasonCount = t.numberOfSeasons
        episodeCount = t.numberOfEpisodes
        // TMDB lists Specials as season 0 and can carry announced-but-empty
        // seasons; drop the empty ones and sort Specials to the end.
        seasons = (t.seasons ?? [])
            .filter { ($0.episodeCount ?? 0) > 0 }
            .sorted { lhs, rhs in
                if (lhs.seasonNumber == 0) != (rhs.seasonNumber == 0) {
                    return rhs.seasonNumber == 0
                }
                return lhs.seasonNumber < rhs.seasonNumber
            }
    }
}

// MARK: - Endpoints

extension TMDBService {

    /// Searches movies and TV shows by title. TMDB's mixed search also
    /// returns people, which `item(defaultType:)` deliberately filters out.
    func searchMulti(query text: String) async throws -> [TMDBMediaItem] {
        let query = [
            URLQueryItem(name: "query", value: text),
            URLQueryItem(name: "include_adult", value: "false")
        ]
        return try await fetch(
            TMDBPagedResponse<TMDBMediaItemRaw>.self,
            path: "/search/multi",
            query: query
        ).results.compactMap { $0.item(defaultType: .movie) }
    }

    /// Movies only — the `movies:`/`films:` search scope.
    func searchMovies(query text: String) async throws -> [TMDBMediaItem] {
        let query = [
            URLQueryItem(name: "query", value: text),
            URLQueryItem(name: "include_adult", value: "false")
        ]
        return try await fetch(
            TMDBPagedResponse<TMDBMediaItemRaw>.self,
            path: "/search/movie",
            query: query
        ).results.compactMap { $0.item(defaultType: .movie) }
    }

    /// Shows only — the `shows:`/`series:`/`tv:` search scope.
    func searchShows(query text: String) async throws -> [TMDBMediaItem] {
        let query = [
            URLQueryItem(name: "query", value: text),
            URLQueryItem(name: "include_adult", value: "false")
        ]
        return try await fetch(
            TMDBPagedResponse<TMDBMediaItemRaw>.self,
            path: "/search/tv",
            query: query
        ).results.compactMap { $0.item(defaultType: .tv) }
    }

    /// Actors/actresses matching the query, in TMDB's relevance order.
    func searchPeople(query text: String) async throws -> [TMDBPersonItem] {
        let query = [
            URLQueryItem(name: "query", value: text),
            URLQueryItem(name: "include_adult", value: "false")
        ]
        return try await fetch(
            TMDBPagedResponse<TMDBPersonItemRaw>.self,
            path: "/search/person",
            query: query
        ).results.map(\.item)
    }

    /// A person's biography record — the person page header.
    func personDetail(personId: Int) async throws -> PersonDetail {
        try await fetch(TMDBPersonDetailRaw.self, path: "/person/\(personId)").detail
    }

    /// Trending movies and shows (people are filtered out).
    func trending(window: String = "day") async throws -> [TMDBMediaItem] {
        try await fetch(TMDBPagedResponse<TMDBMediaItemRaw>.self, path: "/trending/all/\(window)")
            .results.compactMap { $0.item(defaultType: .movie) }
    }

    func popular(_ type: TMDBMediaType) async throws -> [TMDBMediaItem] {
        try await fetch(TMDBPagedResponse<TMDBMediaItemRaw>.self, path: "/\(type.rawValue)/popular")
            .results.compactMap { $0.item(defaultType: type) }
    }

    func topRated(_ type: TMDBMediaType) async throws -> [TMDBMediaItem] {
        try await fetch(TMDBPagedResponse<TMDBMediaItemRaw>.self, path: "/\(type.rawValue)/top_rated")
            .results.compactMap { $0.item(defaultType: type) }
    }

    /// Discover by optional genre, sorted by popularity.
    func discover(_ type: TMDBMediaType, genreId: Int? = nil) async throws -> [TMDBMediaItem] {
        var query = [
            URLQueryItem(name: "sort_by", value: "popularity.desc"),
            URLQueryItem(name: "include_adult", value: "false")
        ]
        if let genreId {
            query.append(URLQueryItem(name: "with_genres", value: "\(genreId)"))
        }
        return try await fetch(TMDBPagedResponse<TMDBMediaItemRaw>.self, path: "/discover/\(type.rawValue)", query: query)
            .results.compactMap { $0.item(defaultType: type) }
    }

    /// One page of movies whose primary release date falls inside
    /// [`from`, `to`] (both "yyyy-MM-dd", inclusive), most popular first.
    /// Feeds the search tab's release heatmap and its date-filtered results.
    func discoverMoviesReleased(
        from: String,
        to: String,
        page: Int = 1
    ) async throws -> (items: [TMDBMediaItem], totalPages: Int) {
        let query = [
            URLQueryItem(name: "primary_release_date.gte", value: from),
            URLQueryItem(name: "primary_release_date.lte", value: to),
            URLQueryItem(name: "sort_by", value: "popularity.desc"),
            URLQueryItem(name: "include_adult", value: "false"),
            URLQueryItem(name: "page", value: "\(page)")
        ]
        let response = try await fetch(
            TMDBPagedResponse<TMDBMediaItemRaw>.self,
            path: "/discover/movie",
            query: query
        )
        return (
            response.results.compactMap { $0.item(defaultType: .movie) },
            response.totalPages ?? 1
        )
    }

    /// Every movie and show a person is credited on as cast, most recent
    /// first. Powers the Search tab's "Starring …" filmography view.
    func filmography(personId: Int) async throws -> [TMDBMediaItem] {
        let credits = try await fetch(
            TMDBPersonCredits.self,
            path: "/person/\(personId)/combined_credits"
        )
        var seen = Set<MediaRef>()
        return credits.cast
            .compactMap { $0.item(defaultType: .movie) }
            // A person can be credited more than once on the same title.
            .filter { seen.insert($0.ref).inserted }
            .sorted { ($0.releaseDate ?? "") > ($1.releaseDate ?? "") }
    }

    /// Videos attached to a title (trailers, teasers, clips).
    func videos(_ ref: MediaRef) async throws -> [TMDBVideo] {
        try await fetch(
            TMDBPagedResponse<TMDBVideo>.self,
            path: "/\(ref.mediaType.rawValue)/\(ref.id)/videos"
        ).results
    }

    /// The trailer the hero's Watch Trailer button should play, if any.
    func bestTrailer(_ ref: MediaRef) async throws -> TMDBVideo? {
        TMDBVideo.bestTrailer(in: try await videos(ref))
    }

    /// Movie genres (drives the curated-collection chips).
    func movieGenres() async throws -> [TMDBGenre] {
        try await fetch(TMDBGenreList.self, path: "/genre/movie/list").genres
    }

    func movieDetail(id: Int) async throws -> TMDBMovieDetail {
        let query = [URLQueryItem(name: "append_to_response", value: "credits")]
        return try await fetch(TMDBMovieDetail.self, path: "/movie/\(id)", query: query)
    }

    func tvDetail(id: Int) async throws -> TMDBTVDetail {
        let query = [URLQueryItem(name: "append_to_response", value: "credits")]
        return try await fetch(TMDBTVDetail.self, path: "/tv/\(id)", query: query)
    }

    /// One season's episode list. Fetched lazily by the detail view's episode
    /// browser so opening a show never pays for seasons nobody looks at.
    func tvSeason(showId: Int, seasonNumber: Int) async throws -> TMDBSeasonDetail {
        try await fetch(TMDBSeasonDetail.self, path: "/tv/\(showId)/season/\(seasonNumber)")
    }

    /// Unified detail fetch for the media detail view.
    func mediaDetail(_ ref: MediaRef) async throws -> MediaDetail {
        switch ref.mediaType {
        case .movie: MediaDetail(movie: try await movieDetail(id: ref.id))
        case .tv: MediaDetail(tv: try await tvDetail(id: ref.id))
        }
    }
}
