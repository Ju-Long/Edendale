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
    private let lock = NSLock()

    public init(capacity: Int = 3) {
        self.capacity = max(capacity, 1)
    }

    /// Pushes a decoded video frame into the ring buffer.
    /// If capacity is reached, the oldest frame is discarded.
    public func push(_ frame: DecodedVideoFrame) {
        lock.lock()
        defer { lock.unlock() }

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

    /// Returns the earliest frame with presentation timestamp strictly after `time`.
    public func latestFrame(after time: CMTime) -> DecodedVideoFrame? {
        lock.lock()
        defer { lock.unlock() }

        return frames.first(where: { $0.presentationTime > time })
    }

    /// Returns the latest frame whose presentation timestamp is at or before `time`.
    /// Old frames earlier than this display candidate are pruned.
    public func latestFrame(atOrBefore time: CMTime) -> DecodedVideoFrame? {
        lock.lock()
        defer { lock.unlock() }

        guard let targetIndex = frames.lastIndex(where: { $0.presentationTime <= time }) else {
            return frames.first
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
        return frames.last
    }

    /// Clears all frames from the buffer (e.g. on seek, track switch, or media change).
    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        frames.removeAll(keepingCapacity: true)
    }

    /// Current number of frames in the buffer.
    public var count: Int {
        lock.lock()
        defer { lock.unlock() }
        return frames.count
    }

    /// Whether the buffer is currently empty.
    public var isEmpty: Bool {
        lock.lock()
        defer { lock.unlock() }
        return frames.isEmpty
    }
}
