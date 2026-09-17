//
//  FFmpegDecoder.swift
//  Edendale
//
//  Stub implementation of `MediaDecoder` for the FFmpeg pipeline.
//  The real implementation requires FFmpeg xcframeworks to be built
//  (Section B). This stub allows the protocol wiring and format router
//  integration to compile and run; opening any media through it throws
//  an error directing the user to the AVFoundation path.
//

import AVFoundation
import CoreMedia
import Foundation

enum FFmpegDecoderError: LocalizedError {
    case notBuilt

    var errorDescription: String? {
        "FFmpeg decoder is not yet available. FFmpeg xcframeworks need to be built before this format can be played."
    }
}

@MainActor
public final class FFmpegDecoder: MediaDecoder {

    public private(set) var state: DecoderState = .idle
    public var currentTime: CMTime { .zero }
    public var mediaInfo: MediaInfo? { nil }

    public var onStateChanged: (@MainActor (DecoderState) -> Void)?
    public var onTimeChanged: (@MainActor (CMTime) -> Void)?

    public init() {}

    public func open(url: URL) async throws -> MediaInfo {
        state = .error(FFmpegDecoderError.notBuilt)
        onStateChanged?(state)
        throw FFmpegDecoderError.notBuilt
    }

    public func play() {}
    public func pause() {}

    public func seek(to time: CMTime) async throws {
        throw FFmpegDecoderError.notBuilt
    }

    public func setRate(_ rate: Float) {}
    public func selectAudioTrack(_ index: Int) {}
    public func selectSubtitleTrack(_ index: Int?) {}

    public func close() {
        state = .idle
        onStateChanged?(state)
    }
}
