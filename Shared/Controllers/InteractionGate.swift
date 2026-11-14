//
//  InteractionGate.swift
//  Edendale
//
//  When background library work may spend main-thread time: not while the
//  viewer scrolls a page or watches a video, and not until things have
//  stayed still for a moment. Metadata matching (LibraryController) keeps
//  making its lookups off the main thread meanwhile, and writes what it
//  found to the library — a save, then a refresh of every page showing it
//  — only once this gate has settled.
//

import SwiftUI

@MainActor
final class InteractionGate {

    enum Activity: Hashable {
        /// One scroll view moving: dragged, flicked, or still decelerating.
        case scrolling(UUID)
        /// The player is up, or opening.
        case playback
    }

    /// How long nothing may happen before held work goes ahead.
    let settleDelay: Duration

    private var activities: Set<Activity> = []
    private var lastActivity: ContinuousClock.Instant?

    init(settleDelay: Duration = .seconds(1.5)) {
        self.settleDelay = settleDelay
    }

    /// Starts or ends an ongoing activity. Its end starts the settle delay.
    func set(_ activity: Activity, active: Bool) {
        if active {
            activities.insert(activity)
        } else if activities.remove(activity) != nil {
            lastActivity = .now
        }
    }

    /// A moment of activity, such as asking to play: held work waits out
    /// the settle delay from now.
    func touch() {
        lastActivity = .now
    }

    /// Nothing is going on, and nothing has for `settleDelay`. Work that
    /// may run while a video plays (keeping widgets current) passes
    /// `includingPlayback: false` and waits only for scrolling.
    func isSettled(includingPlayback: Bool = true) -> Bool {
        let ongoing = includingPlayback ? activities : activities.subtracting([.playback])
        guard ongoing.isEmpty else { return false }
        guard let lastActivity else { return true }
        return ContinuousClock.now - lastActivity >= settleDelay
    }

    /// Returns once the gate has settled, or the task is cancelled.
    func waitUntilSettled(includingPlayback: Bool = true) async {
        while !isSettled(includingPlayback: includingPlayback), !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(250))
        }
    }
}

extension EnvironmentValues {
    /// The app's gate; `nil` (previews, tests) reports nothing.
    @Entry var interactionGate: InteractionGate? = nil
}

extension View {
    /// Tells the interaction gate while this scroll view moves, so library
    /// work held for it doesn't land mid-scroll.
    func holdsBackgroundWorkWhileScrolling() -> some View {
        modifier(ScrollActivityReporter())
    }
}

private struct ScrollActivityReporter: ViewModifier {
    @Environment(\.interactionGate) private var gate
    @State private var id = UUID()

    func body(content: Content) -> some View {
        content
            .onScrollPhaseChange { _, phase in
                gate?.set(.scrolling(id), active: phase.isScrolling)
            }
            .onDisappear {
                gate?.set(.scrolling(id), active: false)
            }
    }
}
