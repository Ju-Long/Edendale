//
//  RatingsEnvironment.swift
//  Edendale
//

import SwiftUI

private struct RatingProvidersKey: EnvironmentKey {
    static let defaultValue: [any MediaRatingsProvider] = []
}

extension EnvironmentValues {
    var ratingProviders: [any MediaRatingsProvider] {
        get { self[RatingProvidersKey.self] }
        set { self[RatingProvidersKey.self] = newValue }
    }
}
