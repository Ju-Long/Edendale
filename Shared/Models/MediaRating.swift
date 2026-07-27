//
//  MediaRating.swift
//  Edendale
//

import Foundation

struct MediaRating: Hashable, Sendable {
    let source: String
    let value: String
    let suffix: String?
    
    init(source: String, value: String, suffix: String? = nil) {
        self.source = source
        self.value = value
        self.suffix = suffix
    }
}

protocol MediaRatingsProvider: Sendable {
    /// Provide ratings for the given media reference
    func ratings(for ref: MediaRef) async throws -> [MediaRating]
}
