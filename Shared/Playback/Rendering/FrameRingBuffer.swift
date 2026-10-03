//
//  FrameRingBuffer.swift
//  Edendale
//
//  Thread-safe triple-buffered ring buffer connecting decoders to the Metal renderer.
//

import CoreMedia
import Foundation

/// Thread-safe frame buffer between decoder and renderer.
/// Triple-buffered by default: decoder writes, renderer reads without lock contention.
public final class FrameRingBuffer: @unchecked Sendable {
    public let capacity: Int
    private var frames: [DecodedVideoFrame] = []
    /// The picture `flush()` keeps on screen until the next frame is pushed.
    private var heldFrame: DecodedVideoFrame?
    private let lock = NSLock()

    public init(capacity: Int = 3) {
        self.capacity = max(capacity, 1)
    }

    /// Pushes a decoded video frame into the ring buffer.
    /// If capacity is reached, the oldest frame is discarded.
    public func push(_ frame: DecodedVideoFrame) {
        lock.lock()
        defer { lock.unlock() }

        heldFrame = nil

        // Insert maintaining PTS order
        if let index = frames.firstIndex(where: { $0.presentationTime > frame.presentationTime }) {
            frames.insert(frame, at: index)
        } else {
            frames.append(frame)
        }

        // Evict oldest frames if exceeding capacity
        while frames.count > capacity {
            frames.removeFirst()
        }
    }

    /// Returns the latest frame whose presentation timestamp is at or before `time`.
    /// Old frames earlier than this display candidate are pruned.
    public func latestFrame(atOrBefore time: CMTime) -> DecodedVideoFrame? {
        lock.lock()
        defer { lock.unlock() }

        guard let targetIndex = frames.lastIndex(where: { $0.presentationTime <= time }) else {
            return frames.first ?? heldFrame
        }

        let frame = frames[targetIndex]
        // Prune stale frames strictly older than targetIndex
        if targetIndex > 0 {
            frames.removeSubrange(0..<targetIndex)
        }

        return frame
    }

    /// Returns the most recently pushed or latest frame in the buffer.
    public func latestFrame() -> DecodedVideoFrame? {
        lock.lock()
        defer { lock.unlock() }
        return frames.last ?? heldFrame
    }

    /// Drops the queued frames at a discontinuity in the same media (seek or
    /// track switch) but keeps the picture on screen until the next push, so
    /// the video holds still instead of going black while the decoder
    /// refills from the new position.
    public func flush() {
        lock.lock()
        defer { lock.unlock() }
        // Readers prune every frame older than the one they return, so the
        // first queued frame is the one on screen.
        heldFrame = frames.first ?? heldFrame
        frames.removeAll(keepingCapacity: true)
    }

    /// Clears all frames from the buffer, including a held picture (e.g. on
    /// media change).
    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        frames.removeAll(keepingCapacity: true)
        heldFrame = nil
    }

    /// Current number of queued frames; a held picture is not counted.
    public var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return frames.count
    }

    /// Whether no frames are queued; a held picture is not counted.
    public var isEmpty: Bool {
        lock.lock()
        defer { lock.unlock() }
        return frames.isEmpty
    }
}
