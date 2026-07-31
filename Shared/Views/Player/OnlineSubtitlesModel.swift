//
//  OnlineSubtitlesModel.swift
//  Edendale
//
//  State for explicit Wyzie searches and per-result downloads. Although VLC
//  accepts remote track URLs, a validated local cache file is deterministic
//  inside the sandbox, survives a flaky CDN, and is checked before VLC parses
//  it.
//

import Foundation
import SwiftVLC

@MainActor
@Observable
final class OnlineSubtitlesModel {
    enum Phase: Equatable {
        case idle
        case searching
        case results
        case failed(String)
    }

    private(set) var phase: Phase = .idle
    private(set) var results: [WyzieSubtitle] = []

    var language: String = AppIdentifiers.defaults.string(
        forKey: DefaultsKey.language
    ) ?? "en" {
        didSet {
            AppIdentifiers.defaults.set(language, forKey: DefaultsKey.language)
        }
    }

    var hearingImpaired: Bool = AppIdentifiers.defaults.object(
        forKey: DefaultsKey.hearingImpaired
    ) as? Bool ?? false {
        didSet {
            AppIdentifiers.defaults.set(
                hearingImpaired,
                forKey: DefaultsKey.hearingImpaired
            )
        }
    }

    private(set) var downloadingID: String?
    private(set) var downloadedIDs: Set<String> = []

    private var searchTask: Task<Void, Never>?

    static let availableLanguages: [(code: String, label: String)] = [
        ("", String(localized: "All languages")),
        ("en", String(localized: "English")),
        ("es", String(localized: "Spanish")),
        ("fr", String(localized: "French")),
        ("de", String(localized: "German")),
        ("it", String(localized: "Italian")),
        ("pt", String(localized: "Portuguese")),
        ("nl", String(localized: "Dutch")),
        ("pl", String(localized: "Polish")),
        ("ru", String(localized: "Russian")),
        ("tr", String(localized: "Turkish")),
        ("ar", String(localized: "Arabic")),
        ("hi", String(localized: "Hindi")),
        ("id", String(localized: "Indonesian")),
        ("ja", String(localized: "Japanese")),
        ("ko", String(localized: "Korean")),
        ("zh", String(localized: "Chinese")),
        ("sv", String(localized: "Swedish")),
        ("da", String(localized: "Danish")),
        ("no", String(localized: "Norwegian")),
        ("fi", String(localized: "Finnish")),
        ("cs", String(localized: "Czech")),
        ("el", String(localized: "Greek")),
        ("he", String(localized: "Hebrew")),
        ("th", String(localized: "Thai")),
        ("vi", String(localized: "Vietnamese")),
        ("ro", String(localized: "Romanian")),
        ("hu", String(localized: "Hungarian")),
        ("uk", String(localized: "Ukrainian"))
    ]

    func search(
        lookup: (id: String, season: Int?, episode: Int?),
        key: String
    ) {
        searchTask?.cancel()
        phase = .searching
        results = []

        let query = WyzieSubtitleQuery(
            id: lookup.id,
            season: lookup.season,
            episode: lookup.episode,
            language: language.isEmpty ? nil : language,
            format: nil,
            hearingImpaired: hearingImpaired
        )
        searchTask = Task { [weak self] in
            guard let self else { return }
            do {
                let subtitles = try await WyzieSubtitleService.shared.search(query, key: key)
                guard !Task.isCancelled else { return }
                results = subtitles
                phase = .results
            } catch {
                guard !Task.isCancelled else { return }
                phase = .failed(error.localizedDescription)
            }
        }
    }

    func download(_ subtitle: WyzieSubtitle, into player: Player) async {
        guard downloadingID == nil, !downloadedIDs.contains(subtitle.id) else { return }
        downloadingID = subtitle.id
        if !results.isEmpty {
            phase = .results
        }
        defer { downloadingID = nil }

        do {
            let localURL = try await WyzieSubtitleService.shared.download(subtitle)
            try player.addExternalTrack(from: localURL, type: .subtitle, select: true)
            downloadedIDs.insert(subtitle.id)
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    func reset() {
        searchTask?.cancel()
        searchTask = nil
        results = []
        phase = .idle
        downloadingID = nil
        downloadedIDs = []
    }

    private enum DefaultsKey {
        static let language = "subtitles.wyzieLanguage"
        static let hearingImpaired = "subtitles.wyzieHearingImpaired"
    }
}
