//
//  MediaParser.swift
//  Edendale
//
//  Classifies a video filename as either a movie or a TV episode by
//  matching common naming conventions before any network lookup.
//

import Foundation

struct MediaParser {

    // MARK: - Public API

    enum ParsedMedia {
        case movie(title: String, year: Int?)
        case episode(showName: String, season: Int, episode: Int)
    }

    nonisolated static func parse(fileURL: URL) -> ParsedMedia {
        let base = fileURL.deletingPathExtension().lastPathComponent

        if let tv = extractTVInfo(from: base) {
            return .episode(showName: tv.showName, season: tv.season, episode: tv.episode)
        }

        let (title, year) = extractMovieTitleAndYear(from: base)
        return .movie(title: title, year: year)
    }

    // MARK: - Private

    nonisolated private static func extractTVInfo(
        from name: String
    ) -> (showName: String, season: Int, episode: Int)? {
        // Matches: ShowName.S01E02 or ShowName.s01e02
        let sxxExxPattern = #"^(.+?)[. _\-][Ss](\d{1,2})[Ee](\d{1,2})"#
        // Matches: ShowName.1x02
        let nxnnPattern   = #"^(.+?)[. _\-](\d{1,2})x(\d{2})(?:[. _\-]|$)"#

        for pattern in [sxxExxPattern, nxnnPattern] {
            guard
                let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive),
                let match = regex.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)),
                match.numberOfRanges >= 4,
                let showRange    = Range(match.range(at: 1), in: name),
                let seasonRange  = Range(match.range(at: 2), in: name),
                let episodeRange = Range(match.range(at: 3), in: name)
            else { continue }

            return (
                showName: cleanTitle(String(name[showRange])),
                season:   Int(name[seasonRange])  ?? 1,
                episode:  Int(name[episodeRange]) ?? 1
            )
        }
        return nil
    }

    nonisolated private static func extractMovieTitleAndYear(from name: String) -> (String, Int?) {
        // Year wrapped in optional parens/brackets, e.g. "Movie.Title.2023" or "Movie Title (2023)"
        let yearPattern = #"[. _\[(]((19|20)\d{2})(?:[. _\])]|$)"#

        if
            let regex = try? NSRegularExpression(pattern: yearPattern),
            let match = regex.firstMatch(in: name, range: NSRange(name.startIndex..., in: name)),
            let yearRange  = Range(match.range(at: 1), in: name),
            let matchRange = Range(match.range, in: name)
        {
            let year      = Int(name[yearRange])
            let titlePart = String(name[name.startIndex..<matchRange.lowerBound])
            return (cleanTitle(titlePart), year)
        }

        return (cleanTitle(name), nil)
    }

    nonisolated private static func cleanTitle(_ raw: String) -> String {
        var s = raw
            .replacingOccurrences(of: ".", with: " ")
            .replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")
        // Collapse runs of whitespace
        while s.contains("  ") { s = s.replacingOccurrences(of: "  ", with: " ") }
        return s.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
