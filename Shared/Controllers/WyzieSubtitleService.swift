//
//  WyzieSubtitleService.swift
//  Edendale
//
//  Online subtitle search through sub.wyzie.io. A synchronized user key takes
//  precedence over the optional build-time key, searches happen only after an
//  explicit user action, and validated downloads are stored only in Caches.
//

import Foundation

struct WyzieSubtitle: Identifiable, Sendable, Decodable, Hashable {
    let id: String
    let url: String
    let format: String
    let encoding: String
    let isHearingImpaired: Bool
    let flagUrl: String
    let media: String
    let display: String
    let language: String
    let source: [String]?
    let release: String?
    let releases: [String]?
    let fileName: String?
    let downloadCount: Int?
    let origin: String?
    let matchedRelease: String?
    let matchedFilter: String?

    init(
        id: String,
        url: String,
        format: String,
        encoding: String,
        isHearingImpaired: Bool,
        flagUrl: String,
        media: String,
        display: String,
        language: String,
        source: [String]? = nil,
        release: String? = nil,
        releases: [String]? = nil,
        fileName: String? = nil,
        downloadCount: Int? = nil,
        origin: String? = nil,
        matchedRelease: String? = nil,
        matchedFilter: String? = nil
    ) {
        self.id = id
        self.url = url
        self.format = format
        self.encoding = encoding
        self.isHearingImpaired = isHearingImpaired
        self.flagUrl = flagUrl
        self.media = media
        self.display = display
        self.language = language
        self.source = source
        self.release = release
        self.releases = releases
        self.fileName = fileName
        self.downloadCount = downloadCount
        self.origin = origin
        self.matchedRelease = matchedRelease
        self.matchedFilter = matchedFilter
    }

    private enum CodingKeys: String, CodingKey {
        case id, url, format, encoding, isHearingImpaired, flagUrl, media
        case display, language, source, release, releases, fileName
        case downloadCount, origin, matchedRelease, matchedFilter
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        url = try container.decode(String.self, forKey: .url)
        format = try container.decode(String.self, forKey: .format)
        encoding = try container.decode(String.self, forKey: .encoding)
        isHearingImpaired = try container.decode(Bool.self, forKey: .isHearingImpaired)
        flagUrl = try container.decode(String.self, forKey: .flagUrl)
        media = try container.decode(String.self, forKey: .media)
        display = try container.decode(String.self, forKey: .display)
        language = try container.decode(String.self, forKey: .language)
        source = try container.decodeIfPresent(StringOrArray.self, forKey: .source)?.values
        release = try container.decodeIfPresent(String.self, forKey: .release)
        releases = try container.decodeIfPresent([String].self, forKey: .releases)
        fileName = try container.decodeIfPresent(String.self, forKey: .fileName)
        downloadCount = try container.decodeIfPresent(Int.self, forKey: .downloadCount)
        origin = try container.decodeIfPresent(String.self, forKey: .origin)
        matchedRelease = try container.decodeIfPresent(String.self, forKey: .matchedRelease)
        matchedFilter = try container.decodeIfPresent(String.self, forKey: .matchedFilter)
    }
}

private struct StringOrArray: Decodable {
    let values: [String]

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let value = try? container.decode(String.self) {
            values = [value]
        } else {
            values = try container.decode([String].self)
        }
    }
}

enum WyzieError: Error, LocalizedError {
    case missingKey
    case badStatus(code: Int, message: String?)
    case emptyFile
    case fileTooLarge
    case badURL

    var errorDescription: String? {
        switch self {
        case .missingKey:
            String(localized: "A Wyzie API key is required.")
        case .badStatus(let code, let message):
            if let message, !message.isEmpty {
                String(localized: "Wyzie request failed (HTTP \(code)): \(message)")
            } else {
                String(localized: "Wyzie request failed (HTTP \(code)).")
            }
        case .emptyFile:
            String(localized: "The downloaded subtitle file was empty.")
        case .fileTooLarge:
            String(localized: "The downloaded subtitle file was too large.")
        case .badURL:
            String(localized: "The subtitle URL was invalid.")
        }
    }
}

struct WyzieSubtitleQuery: Equatable, Sendable {
    let id: String
    let season: Int?
    let episode: Int?
    var language: String?
    var format: String?
    var hearingImpaired: Bool?
}

struct WyzieSubtitleService {
    static let shared = WyzieSubtitleService()

    private static let baseURL = "https://sub.wyzie.io"
    private static let maximumDownloadSize = 5 * 1024 * 1024

    private init() {}

    func search(_ query: WyzieSubtitleQuery, key: String) async throws -> [WyzieSubtitle] {
        let key = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !key.isEmpty else { throw WyzieError.missingKey }

        var components = URLComponents(string: "\(Self.baseURL)/search")
        components?.queryItems = Self.queryItems(for: query, key: key)
        guard let url = components?.url else { throw WyzieError.badURL }

        let (data, response) = try await URLSession.shared.data(from: url)
        let statusCode = (response as? HTTPURLResponse)?.statusCode ?? 200
        return try Self.decodeSearchResponse(data, statusCode: statusCode)
    }

    func download(_ subtitle: WyzieSubtitle) async throws -> URL {
        guard let remoteURL = URL(string: subtitle.url),
              ["http", "https"].contains(remoteURL.scheme?.lowercased() ?? "")
        else {
            throw WyzieError.badURL
        }
        let (data, response) = try await URLSession.shared.data(from: remoteURL)

        if let http = response as? HTTPURLResponse,
           !(200..<300).contains(http.statusCode) {
            throw Self.statusError(data: data, statusCode: http.statusCode)
        }
        guard !data.isEmpty else { throw WyzieError.emptyFile }
        guard data.count <= Self.maximumDownloadSize else { throw WyzieError.fileTooLarge }

        guard let caches = FileManager.default.urls(
            for: .cachesDirectory,
            in: .userDomainMask
        ).first else {
            throw WyzieError.badURL
        }
        let directory = caches.appendingPathComponent("Subtitles", isDirectory: true)
        try FileManager.default.createDirectory(
            at: directory,
            withIntermediateDirectories: true
        )
        let localURL = directory.appendingPathComponent(Self.cacheFileName(for: subtitle))
        try data.write(to: localURL, options: .atomic)
        return localURL
    }

    static func queryItems(for query: WyzieSubtitleQuery, key: String) -> [URLQueryItem] {
        var items = [URLQueryItem(name: "id", value: query.id)]
        if let season = query.season {
            items.append(URLQueryItem(name: "season", value: String(season)))
            if let episode = query.episode {
                items.append(URLQueryItem(name: "episode", value: String(episode)))
            }
        }
        if let language = query.language {
            items.append(URLQueryItem(name: "language", value: language))
        }
        if let format = query.format {
            items.append(URLQueryItem(name: "format", value: format))
        }
        if let hearingImpaired = query.hearingImpaired {
            items.append(URLQueryItem(name: "hi", value: String(hearingImpaired)))
        }
        items.append(URLQueryItem(name: "key", value: key))
        return items
    }

    static func cacheFileName(for subtitle: WyzieSubtitle) -> String {
        let id = sanitized(subtitle.id)
        let language = sanitized(subtitle.language)
        let sanitizedFormat = sanitized(subtitle.format)
        let format = sanitizedFormat.isEmpty ? "srt" : sanitizedFormat
        return "wyzie-\(id)-\(language).\(format)"
    }

    static func decodeSearchResponse(
        _ data: Data,
        statusCode: Int
    ) throws -> [WyzieSubtitle] {
        guard (200..<300).contains(statusCode) else {
            throw statusError(data: data, statusCode: statusCode)
        }
        return try JSONDecoder().decode([WyzieSubtitle].self, from: data)
    }

    private static func statusError(data: Data, statusCode: Int) -> WyzieError {
        struct ErrorEnvelope: Decodable {
            let code: Int
            let message: String
            let details: String?
            let notice: String?
        }

        let message = try? JSONDecoder().decode(ErrorEnvelope.self, from: data).message
        return .badStatus(code: statusCode, message: message)
    }

    private static func sanitized(_ value: String) -> String {
        value.lowercased().unicodeScalars
            .filter {
                CharacterSet.alphanumerics.contains($0)
                    || $0 == "-"
                    || $0 == "_"
            }
            .map(String.init)
            .joined()
    }
}
