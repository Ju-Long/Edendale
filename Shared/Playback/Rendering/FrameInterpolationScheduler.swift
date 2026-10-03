//
//  FrameInterpolationScheduler.swift
//  Edendale
//
//  Decides what the motion-smoothing draw loop shows on each display refresh.
//

import CoreMedia

/// Per-refresh decisions for motion smoothing, keyed on frame timestamps.
///
/// Decoded frames reach the renderer when they are due, with no lookahead, so
/// the frame between N-1 and N can only be built once N has arrived. It has to
/// reach the screen before N, so N is held back by one refresh (about 21 ms
/// for 24 fps content).
struct FrameInterpolationScheduler {
    enum Action: Equatable {
        /// Nothing new has been decoded; leave the current image on screen.
        case keepCurrent
        /// Show the real frame held back behind its synthetic frame.
        case showHeldFrame
        /// A new frame arrived. `synthesize` is true when it directly follows
        /// the previous frame, so the frame between them can be shown first.
        case showNewFrame(synthesize: Bool)
    }

    private var lastFrameTime: CMTime = .invalid
    private var isHoldingFrame = false

    /// - Parameters:
    ///   - latestFrameTime: Presentation time of the newest decoded frame.
    ///   - frameDuration: Expected spacing between source frames, in seconds.
    mutating func nextAction(for latestFrameTime: CMTime, frameDuration: Double) -> Action {
        if isHoldingFrame {
            isHoldingFrame = false
            return .showHeldFrame
        }
        guard latestFrameTime.isNumeric, latestFrameTime != lastFrameTime else {
            return .keepCurrent
        }

        // Only direct neighbours can be blended: a seek, a dropped frame or a
        // stall leaves nothing meaningful between the two frames.
        let gap = lastFrameTime.isNumeric ? (latestFrameTime - lastFrameTime).seconds : 0
        lastFrameTime = latestFrameTime
        return .showNewFrame(synthesize: gap > 0 && gap < frameDuration * 1.5)
    }

    /// Call after presenting a synthetic frame in front of the new frame, so
    /// the next refresh shows the held real frame.
    mutating func didHoldFrame() {
        isHoldingFrame = true
    }

    /// Forget history, e.g. when pausing, emptying the frame buffer or turning
    /// smoothing off.
    mutating func reset() {
        lastFrameTime = .invalid
        isHoldingFrame = false
    }
}
