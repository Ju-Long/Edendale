//
//  TMDBRatingsProvider.swift
//  Edendale
//

import Foundation

struct TMDBRatingsProvider: MediaRatingsProvider {
    func ratings(for ref: MediaRef) async throws -> [MediaRating] {
        let detail = try await TMDBService.shared.mediaDetail(ref)
        var results = [MediaRating]()
        
        if let score = detail.score {
            results.append(MediaRating(source: String(localized: "TMDB Score"), value: score.formatted(.number.precision(.fractionLength(1))), suffix: "/10"))
        }
        if let votes = detail.voteCount {
            results.append(MediaRating(source: String(localized: "Votes"), value: votes.formatted(), suffix: nil))
        }
        
        return results
    }
}
