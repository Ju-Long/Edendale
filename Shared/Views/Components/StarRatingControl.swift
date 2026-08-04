//
//  StarRatingControl.swift
//  Edendale
//
//  Five-star rating row on TMDB's 0–10 scale. Selecting a star cycles it:
//  first select fills it, the second drops it to a half star, the third
//  removes it (the rating falls back to the full stars before it), and the
//  cycle repeats.
//

import SwiftUI

struct StarRatingControl: View {
    /// Current rating on TMDB's 0–10 scale; nil when unrated.
    let rating: Double?
    /// Receives the new rating; nil clears it.
    let setRating: (Double?) -> Void

    var body: some View {
        let current = rating ?? 0

        HStack(spacing: 0) {
            ForEach(1...5, id: \.self) { index in
                let full = Double(index) * 2
                let half = full - 1

                Button {
                    setRating(nextRating(forStar: index, current: current))
                } label: {
                    if current >= full {
                        Image(.starFill)
                    } else if current >= half {
                        Image(.starHalfFill)
                    } else {
                        Image(.star)
                    }
                }
                .archiveButtonStyle(.ghost, active: current >= half)
            }
        }
        .background {
            RoundedRectangle(cornerRadius: Theme.Radius.soft)
                .strokeBorder(Theme.goldDeep, lineWidth: 1)
            #if os(tvOS)
            RoundedRectangle(cornerRadius: Theme.Radius.soft)
                .fill(Theme.surfaceLow)
            #endif
        }
        // Five identical glyph buttons whose only difference is fill state
        // make a poor set of stops. The row is one adjustable control
        // instead: swipe up/down moves the rating a half star at a time.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Your rating")
        .accessibilityValue(valueText)
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment:
                setRating(min(current + 1, 10))
            case .decrement:
                let next = current - 1
                setRating(next > 0 ? next : nil)
            @unknown default:
                break
            }
        }
    }

    /// Spoken as stars rather than TMDB's 0–10 scale, matching what the row
    /// draws.
    private var valueText: String {
        guard let rating, rating > 0 else { return String(localized: "Not rated") }
        let stars = (rating / 2).formatted(.number.precision(.fractionLength(0...1)))
        return String(localized: "\(stars) of 5 stars")
    }

    /// The cycle for one star: full → half → removed → full → …
    /// Selecting a star in any other state always fills it first.
    private func nextRating(forStar index: Int, current: Double) -> Double? {
        let full = Double(index) * 2
        let half = full - 1

        if current == full { return half }
        if current == half {
            // Removing this star keeps the full stars before it; clearing
            // the first star clears the rating entirely.
            let remaining = Double(index - 1) * 2
            return remaining > 0 ? remaining : nil
        }
        return full
    }
}
