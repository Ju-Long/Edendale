import Foundation

/// Only public metadata identifiers leave the device. Episode IDs are always
/// the parent show's TMDB ID plus TMDB season/episode numbering.
nonisolated struct IntroDBMedia: Hashable, Sendable {
    let tmdbID: Int
    let season: Int?
    let episode: Int?

    init?(tmdbID: Int, season: Int? = nil, episode: Int? = nil) {
        guard (1...10_000_000).contains(tmdbID) else { return nil }
        switch (season, episode) {
        case (nil, nil): break
        case (.some(let season), .some(let episode)) where season > 0 && episode > 0: break
        default: return nil
        }
        self.tmdbID = tmdbID
        self.season = season
        self.episode = episode
    }

    var type: String { season == nil ? "movie" : "tv" }
}

nonisolated struct IntroDBRequest: Hashable, Sendable {
    let media: IntroDBMedia
    let durationMS: Int

    init?(media: IntroDBMedia, duration: TimeInterval) {
        // The provider documents timestamps up to six hours. Do not send
        // unknown, unbounded, or unsupported runtimes as an apparent match.
        guard duration.isFinite, duration > 0, duration <= 21_600 else { return nil }
        let milliseconds = Int((duration * 1_000).rounded())
        guard milliseconds > 0 else { return nil }
        self.media = media
        self.durationMS = milliseconds
    }

    var duration: TimeInterval { Double(durationMS) / 1_000 }

    var urlRequest: URLRequest {
        var components = URLComponents(string: "https://api.theintrodb.org/v3/media")!
        var items = [URLQueryItem(name: "tmdb_id", value: String(media.tmdbID))]
        if let season = media.season, let episode = media.episode {
            items += [
                URLQueryItem(name: "season", value: String(season)),
                URLQueryItem(name: "episode", value: String(episode))
            ]
        }
        items.append(URLQueryItem(name: "duration_ms", value: String(durationMS)))
        components.queryItems = items
        var request = URLRequest(url: components.url!, cachePolicy: .reloadIgnoringLocalCacheData)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 8
        return request
    }
}

nonisolated struct PlaybackSegment: Hashable, Identifiable, Sendable {
    enum Kind: String, Sendable {
        case intro, recap, credits

        var title: String {
            switch self {
            case .intro: String(localized: "Skip Intro")
            case .recap: String(localized: "Skip Recap")
            case .credits: String(localized: "Skip Credits")
            }
        }
    }

    let kind: Kind
    let start: TimeInterval
    let end: TimeInterval
    let reachesEnd: Bool
    var id: Self { self }

    func contains(_ time: TimeInterval) -> Bool {
        time.isFinite && time >= start && time < end
    }
}

nonisolated enum IntroDBError: Error {
    case invalidResponse
    case badStatus(Int)
    case rateLimited
}

/// Public, anonymous, device-to-provider reads. No shared application key,
/// cookies, disk cache, library scan, or contribution endpoint is used.
actor IntroDBService {
    static let shared = IntroDBService()
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, URLResponse)

    private let transport: Transport
    private var retryAfter: Date?

    init(transport: Transport? = nil) {
        if let transport {
            self.transport = transport
        } else {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.urlCache = nil
            configuration.httpCookieStorage = nil
            configuration.httpShouldSetCookies = false
            configuration.timeoutIntervalForResource = 12
            let session = URLSession(configuration: configuration)
            self.transport = { try await session.data(for: $0) }
        }
    }

    func segments(for request: IntroDBRequest) async throws -> [PlaybackSegment] {
        if let retryAfter, retryAfter > Date() { throw IntroDBError.rateLimited }
        let (data, response) = try await transport(request.urlRequest)
        try Task.checkCancellation()
        guard let response = response as? HTTPURLResponse else { throw IntroDBError.invalidResponse }
        if response.statusCode == 429 {
            retryAfter = Date().addingTimeInterval(Self.cooldown(response))
            throw IntroDBError.rateLimited
        }
        if response.statusCode == 404 { return [] }
        guard response.statusCode == 200 else { throw IntroDBError.badStatus(response.statusCode) }
        return try Self.decode(data, for: request)
    }

    private static func cooldown(_ response: HTTPURLResponse) -> TimeInterval {
        // Reset headers are seconds remaining, not Unix timestamps. A daily
        // limit must not be retried on the much shorter burst-limit schedule.
        let delays = ["Retry-After", "X-RateLimit-Reset", "X-UsageLimit-Reset"]
            .compactMap { response.value(forHTTPHeaderField: $0).flatMap(Double.init) }
            .filter { $0.isFinite && $0 > 0 }
        return max(60, delays.max() ?? 60)
    }

    static func decode(_ data: Data, for request: IntroDBRequest) throws -> [PlaybackSegment] {
        let response = try JSONDecoder().decode(Response.self, from: data)
        guard response.tmdbID == request.media.tmdbID,
              response.type == request.media.type,
              response.season == request.media.season,
              response.episode == request.media.episode
        else { throw IntroDBError.invalidResponse }

        let groups: [(PlaybackSegment.Kind, [Timestamp])] = [
            (.intro, response.intro ?? []), (.recap, response.recap ?? []),
            (.credits, response.credits ?? [])
        ]
        let segments = groups.flatMap { kind, timestamps in
            timestamps.compactMap { timestamp -> PlaybackSegment? in
                // Both-null and zero-length entries represent "no segment".
                // A missing credits start must never become a full-file skip.
                guard timestamp.start != nil || timestamp.end != nil else { return nil }
                if kind == .credits {
                    guard let start = timestamp.start, start > 0 else { return nil }
                } else if timestamp.end == nil {
                    return nil
                }
                let start = Double(timestamp.start ?? 0) / 1_000
                let end = timestamp.end.map { Double($0) / 1_000 } ?? request.duration
                guard start >= 0, end > start, end <= request.duration else { return nil }
                return PlaybackSegment(
                    kind: kind, start: start, end: end,
                    reachesEnd: kind == .credits && end == request.duration
                )
            }
        }
        // Keep each range separate: gaps can contain mid/post-credits scenes.
        // Reject conflicting overlaps rather than choosing which content to cut.
        let unique = Array(Set(segments))
        return unique.filter { segment in
            !unique.contains { other in
                other != segment && max(segment.start, other.start) < min(segment.end, other.end)
            }
        }.sorted { $0.start < $1.start }
    }

    private struct Timestamp: Decodable {
        let start: Int?
        let end: Int?
        enum CodingKeys: String, CodingKey {
            case start = "start_ms", end = "end_ms"
        }
    }

    private struct Response: Decodable {
        let tmdbID: Int
        let type: String
        let season: Int?
        let episode: Int?
        let intro: [Timestamp]?
        let recap: [Timestamp]?
        let credits: [Timestamp]?
        enum CodingKeys: String, CodingKey {
            case tmdbID = "tmdb_id"
            case type, season, episode, intro, recap, credits
        }
    }
}
