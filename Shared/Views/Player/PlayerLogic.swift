//
//  PlayerLogic.swift
//  Edendale
//
//  Pure, platform-free playback rules — speed stepping, time formatting,
//  auto-skip windows, sibling-file discovery. Kept side-effect free so the
//  unit tests can exercise them directly.
//

import Foundation
import CoreGraphics

enum PlayerLogic {

    // MARK: - Normalized levels

    /// Keyboard-driven volume and brightness adjustments use five-percent
    /// steps and stay inside the UI's normalized 0...1 range.
    static let levelStep: Float = 0.05

    static func adjustedLevel(_ level: Float, by delta: Float) -> Float {
        let hundredths = (Double(level + delta) * 100).rounded()
        return min(max(Float(hundredths / 100), 0), 1)
    }

    // MARK: - Playback speed

    /// Speed adjustments move in 0.05× increments within a sane VLC range.
    static let rateStep: Float = 0.05
    static let minRate: Float = 0.25
    static let maxRate: Float = 3.0

    /// Snaps a rate onto the 0.05 grid and clamps it to the supported range.
    /// Snapping happens in integer hundredths so repeated stepping never
    /// accumulates float drift (1.05, 1.10, … stay exact).
    static func normalizedRate(_ rate: Float) -> Float {
        let hundredths = (Double(rate) * 100).rounded()
        let snapped = Float((hundredths / 5).rounded() * 5 / 100)
        return min(max(snapped, minRate), maxRate)
    }

    static func incrementedRate(_ rate: Float) -> Float {
        normalizedRate(rate + rateStep)
    }

    static func decrementedRate(_ rate: Float) -> Float {
        normalizedRate(rate - rateStep)
    }

    // MARK: - tvOS press-and-hold speed

    /// Playback rates the tvOS touch-surface hold engages: resting a thumb
    /// on the left half slows to `holdSlowRate`, the right half speeds to
    /// `holdFastRate`. The rate reverts the moment the thumb lifts.
    static let holdSlowRate: Float = 0.5
    static let holdFastRate: Float = 2.0

    /// Horizontal touch magnitude (0…1 from the remote's center) that arms a
    /// hold, and the lower magnitude at which an armed hold lets go. The gap
    /// is hysteresis, so a thumb hovering near the edge doesn't chatter the
    /// speed on and off.
    static let holdArmMagnitude: Float = 0.55
    static let holdReleaseMagnitude: Float = 0.30

    /// The hold-speed rate a touch at (`x`, `y`) implies, or nil when the
    /// touch isn't a clear horizontal press — too centered, or vertical
    /// enough to be an up/down move instead. `active` is true once a hold is
    /// engaged, widening the tolerance so small drift doesn't drop it; a
    /// fresh press must clear the higher arm threshold. `x` runs −1 (left)
    /// … +1 (right).
    static func holdRate(x: Float, y: Float, active: Bool) -> Float? {
        let threshold = active ? holdReleaseMagnitude : holdArmMagnitude
        guard abs(x) >= threshold, abs(x) > abs(y) else { return nil }
        return x >= 0 ? holdFastRate : holdSlowRate
    }

    /// Display string for a rate, e.g. "1.05×".
    static func rateLabel(_ rate: Float) -> String {
        String(format: "%.2f×", rate)
    }

    // MARK: - Time formatting

    /// "1:23:45" above an hour, "23:45" below.
    static func timestamp(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let total = Int(seconds.rounded(.down))
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        return h > 0
            ? String(format: "%d:%02d:%02d", h, m, s)
            : String(format: "%d:%02d", m, s)
    }

    static func timestamp(_ duration: Duration) -> String {
        timestamp(duration.playbackSeconds)
    }

    // MARK: - Upcoming episode preview

    /// Short clips do not show an upcoming-episode card.
    static let minimumSkippableDuration: Duration = .seconds(600)
    /// How close to the end the "Up Next" preview appears for TV episodes.
    static let upcomingPreviewThreshold: Duration = .seconds(30)

    // MARK: - End-of-media

    /// Whether a stop at `time` (of `duration`) counts as reaching the end,
    /// rather than a user-initiated stop mid-file. Keep the existing 95%
    /// completion policy and two-second tolerance for short clips. A manual
    /// terminal-credits skip saves completion explicitly in PlayerSession.
    static func isNaturalEnd(time: Duration, duration: Duration?) -> Bool {
        guard let duration, duration > .zero else { return false }
        return time >= duration * 0.95 || time >= duration - .seconds(2)
    }

    // MARK: - Timeline scrubbing

    /// Seconds of media that one full-width hold-drag sweeps across.
    static let scrubWindowSeconds: Double = 300

    /// Target position (0...1) for a hold-drag of `translation` points across
    /// a surface `width` points wide, starting from `basePosition`.
    static func scrubTarget(
        basePosition: Double,
        translation: Double,
        width: Double,
        durationSeconds: Double
    ) -> Double {
        guard width > 0, durationSeconds > 0 else { return basePosition }
        let offsetSeconds = (translation / width) * scrubWindowSeconds
        let target = basePosition + offsetSeconds / durationSeconds
        return min(max(target, 0), 1)
    }

    /// Normalized destination for a relative seek. Used by tvOS while
    /// several remote presses accumulate into one preview before commit.
    static func seekPosition(
        fromSeconds currentSeconds: Double,
        bySeconds offsetSeconds: Double,
        durationSeconds: Double
    ) -> Double {
        guard durationSeconds > 0 else { return 0 }
        let targetSeconds = min(max(currentSeconds + offsetSeconds, 0), durationSeconds)
        return targetSeconds / durationSeconds
    }

    // MARK: - Aspect fill

    /// Uniform scale that makes a video of natural size `video` cover a
    /// `container` of a different shape, given a surface that renders the
    /// video aspect-fit (letterboxed) inside the container. Scaling that
    /// fitted surface by this factor and cropping the overflow yields an
    /// aspect-fill presentation. Returns 1 when either size is degenerate,
    /// so callers can multiply unconditionally. Assumes square pixels — the
    /// coded track dimensions stand in for the display aspect ratio.
    static func aspectFillScale(container: CGSize, video: CGSize) -> CGFloat {
        guard container.width > 0, container.height > 0,
              video.width > 0, video.height > 0 else { return 1 }
        let containerAspect = container.width / container.height
        let videoAspect = video.width / video.height
        return max(videoAspect / containerAspect, containerAspect / videoAspect)
    }

    // MARK: - Episode progression

    /// The next locally stored episode strictly after `current` in
    /// season/episode order, including cross-season advancement.
    /// Skips duplicate encodes of the current episode. Returns `nil`
    /// when `current` is the last available episode or does not
    /// belong to `show`. Season-0 specials advance among themselves
    /// and into season 1; main seasons never go backwards to season 0
    /// because tuple ordering naturally excludes lower-numbered seasons.
    static func nextEpisode(after current: Episode, in show: TVShow) -> Episode? {
        guard show.episodes.contains(where: { $0.id == current.id }) else { return nil }
        return show.episodes
            .filter {
                ($0.seasonNumber, $0.episodeNumber)
                    > (current.seasonNumber, current.episodeNumber)
            }
            .min {
                ($0.seasonNumber, $0.episodeNumber)
                    < ($1.seasonNumber, $1.episodeNumber)
            }
    }

    /// Identifies the furthest completed episode per show from a
    /// collection of watch progress entries. Used by Continue Watching
    /// to resolve next-up candidates without writing progress for
    /// unwatched episodes.
    static func highestCompletedPerShow(
        _ entries: some Sequence<WatchProgress>
    ) -> [Int: (season: Int, episode: Int, lastWatchedAt: Date)] {
        var result: [Int: (season: Int, episode: Int, lastWatchedAt: Date)] = [:]
        for p in entries {
            guard p.mediaType == .episode,
                  p.isCompleted,
                  let showId = p.showTmdbId,
                  let season = p.seasonNumber,
                  let episode = p.episodeNumber
            else { continue }
            if let existing = result[showId] {
                if (season, episode) > (existing.season, existing.episode) {
                    result[showId] = (season, episode, p.lastWatchedAt)
                }
            } else {
                result[showId] = (season, episode, p.lastWatchedAt)
            }
        }
        return result
    }

    /// Resolves next-up episodes for Continue Watching. For each show
    /// that has a completed episode and no episode already in progress,
    /// returns the next stored episode after the furthest completed one.
    static func nextUpEpisodes(
        allProgress: some Sequence<WatchProgress>,
        inProgressShowTmdbIds: Set<Int>,
        shows: [TVShow]
    ) -> [(episode: Episode, lastWatchedAt: Date)] {
        let highest = highestCompletedPerShow(allProgress)
        var candidates: [Int: Episode] = [:]
        // A completed episode may have been removed after viewing. Its
        // portable season/episode coordinates still identify the successor.
        // Merge duplicate local show records into one next-up card.
        for show in shows {
            guard let showID = show.tmdbId,
                  !inProgressShowTmdbIds.contains(showID),
                  let completed = highest[showID] else { continue }
            for episode in show.episodes {
                let order = (episode.seasonNumber, episode.episodeNumber)
                guard order > (completed.season, completed.episode) else { continue }
                if let existing = candidates[showID],
                   order >= (existing.seasonNumber, existing.episodeNumber) { continue }
                candidates[showID] = episode
            }
        }
        return candidates.compactMap { showID, episode in
            highest[showID].map { (episode: episode, lastWatchedAt: $0.lastWatchedAt) }
        }
    }

    // MARK: - Folder siblings

    /// Video files sitting in the same folder as `url`, sorted by name —
    /// the file-list fallback when an item has no episode metadata. Returns
    /// at least `url` itself when the folder can't be listed (no access).
    static func siblingVideoFiles(of url: URL) -> [URL] {
        let folder = url.deletingLastPathComponent()
        let contents = (try? FileManager.default.contentsOfDirectory(
            at: folder,
            includingPropertiesForKeys: nil,
            options: [.skipsHiddenFiles]
        )) ?? []

        let videos = contents
            .filter { LibraryController.supportedExtensions.contains($0.pathExtension.lowercased()) }
            .sorted {
                $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedAscending
            }
        return videos.isEmpty ? [url] : videos
    }
}

extension Duration {
    /// Duration as fractional seconds, for progress math and formatting.
    nonisolated var playbackSeconds: Double {
        Double(components.seconds) + Double(components.attoseconds) / 1e18
    }
}
