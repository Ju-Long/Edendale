//
//  SubtitleModels.swift
//  Edendale
//
//  Data structures representing decoded text and image subtitle cues.
//

import CoreGraphics
import CoreMedia
import Foundation


/// A rectangular bitmap slice belonging to an image-based subtitle (e.g. PGS or VobSub).
public struct ImageSubtitleRect: Sendable, Equatable {
    public let x: Int
    public let y: Int
    public let width: Int
    public let height: Int
    public let data: Data // 32-bit RGBA pixel data

    public init(x: Int, y: Int, width: Int, height: Int, data: Data) {
        self.x = x
        self.y = y
        self.width = width
        self.height = height
        self.data = data
    }
}

/// Decoded image-based subtitle cue containing one or more bitmap rectangles.
public struct ImageSubtitleCue: Sendable, Equatable {
    public let start: CMTime
    public let end: CMTime
    public let rects: [ImageSubtitleRect]
    public let canvasSize: CGSize
    public let trackIndex: Int

    public init(
        start: CMTime,
        end: CMTime,
        rects: [ImageSubtitleRect],
        canvasSize: CGSize,
        trackIndex: Int = 0
    ) {
        self.start = start
        self.end = end
        self.rects = rects
        self.canvasSize = canvasSize
        self.trackIndex = trackIndex
    }

    public func contains(time: CMTime) -> Bool {
        return time >= start && time <= end
    }
}

/// Supported subtitle track formats.
public enum SubtitleTrackFormat: String, Sendable, CaseIterable, Equatable {
    case ass
    case ssa
    case srt
    case webvtt
    case pgs
    case vobsub

    public var isImageBased: Bool {
        switch self {
        case .pgs, .vobsub:
            return true
        case .ass, .ssa, .srt, .webvtt:
            return false
        }
    }
}
