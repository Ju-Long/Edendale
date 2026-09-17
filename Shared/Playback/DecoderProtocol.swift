//
//  DecoderProtocol.swift
//  Edendale
//
//  Unified decoder protocol and playback models for Edendale.
//

import AVFoundation
import CoreMedia
import CoreVideo

public struct MediaInfo: Sendable {
    public let duration: CMTime
    public let videoTracks: [VideoTrackInfo]
    public let audioTracks: [AudioTrackInfo]
    public let subtitleTracks: [SubtitleTrackInfo]
    public let naturalSize: CGSize
    public let frameRate: Float
    public let isHDR: Bool

    public init(
        duration: CMTime,
        videoTracks: [VideoTrackInfo],
        audioTracks: [AudioTrackInfo],
        subtitleTracks: [SubtitleTrackInfo],
        naturalSize: CGSize,
        frameRate: Float,
        isHDR: Bool
    ) {
        self.duration = duration
        self.videoTracks = videoTracks
        self.audioTracks = audioTracks
        self.subtitleTracks = subtitleTracks
        self.naturalSize = naturalSize
        self.frameRate = frameRate
        self.isHDR = isHDR
    }
}

public struct VideoTrackInfo: Sendable, Identifiable {
    public var id: Int { index }
    public let index: Int
    public let codec: String          // "h264", "hevc", "vp9", "av1" …
    public let size: CGSize
    public let bitDepth: Int          // 8, 10, 12
    public let isHardwareDecodable: Bool

    public init(index: Int, codec: String, size: CGSize, bitDepth: Int, isHardwareDecodable: Bool) {
        self.index = index
        self.codec = codec
        self.size = size
        self.bitDepth = bitDepth
        self.isHardwareDecodable = isHardwareDecodable
    }
}

public struct AudioTrackInfo: Sendable, Identifiable {
    public var id: Int { index }
    public let index: Int
    public let codec: String
    public let channelCount: Int
    public let sampleRate: Int
    public let language: String?
    public let title: String?

    public init(index: Int, codec: String, channelCount: Int, sampleRate: Int, language: String?, title: String?) {
        self.index = index
        self.codec = codec
        self.channelCount = channelCount
        self.sampleRate = sampleRate
        self.language = language
        self.title = title
    }
}

public struct SubtitleTrackInfo: Sendable, Identifiable {
    public var id: Int { index }
    public let index: Int
    public let codec: String          // "ass", "srt", "webvtt", "pgs", "dvdsub"
    public let language: String?
    public let title: String?
    public let isImageBased: Bool     // PGS, VobSub vs text-based

    public init(index: Int, codec: String, language: String?, title: String?, isImageBased: Bool) {
        self.index = index
        self.codec = codec
        self.language = language
        self.title = title
        self.isImageBased = isImageBased
    }
}

public struct DecodedVideoFrame: Sendable {
    public let pixelBuffer: CVPixelBuffer
    public let presentationTime: CMTime
    public let duration: CMTime

    public init(pixelBuffer: CVPixelBuffer, presentationTime: CMTime, duration: CMTime) {
        self.pixelBuffer = pixelBuffer
        self.presentationTime = presentationTime
        self.duration = duration
    }
}

public struct DecodedSubtitleEvent: Sendable {
    public let text: String           // raw ASS/SRT markup
    public let start: CMTime
    public let end: CMTime
    public let trackIndex: Int

    public init(text: String, start: CMTime, end: CMTime, trackIndex: Int = 0) {
        self.text = text
        self.start = start
        self.end = end
        self.trackIndex = trackIndex
    }
}

public enum DecoderState: @unchecked Sendable, Equatable {
    case idle
    case opening
    case ready
    case playing
    case paused
    case seeking
    case ended
    case error(any Error)

    public static func == (lhs: DecoderState, rhs: DecoderState) -> Bool {
        switch (lhs, rhs) {
        case (.idle, .idle),
             (.opening, .opening),
             (.ready, .ready),
             (.playing, .playing),
             (.paused, .paused),
             (.seeking, .seeking),
             (.ended, .ended):
            return true
        case (.error(let e1), .error(let e2)):
            return String(describing: e1) == String(describing: e2)
        default:
            return false
        }
    }
}

@MainActor
public protocol MediaDecoder: AnyObject {
    var state: DecoderState { get }
    var currentTime: CMTime { get }
    var mediaInfo: MediaInfo? { get }
    var onStateChanged: (@MainActor (DecoderState) -> Void)? { get set }
    var onTimeChanged: (@MainActor (CMTime) -> Void)? { get set }

    func open(url: URL) async throws -> MediaInfo
    func play()
    func pause()
    func seek(to time: CMTime) async throws
    func setRate(_ rate: Float)
    func selectAudioTrack(_ index: Int)
    func selectSubtitleTrack(_ index: Int?)
    func close()
}
