//
//  FormatRouterTests.swift
//  EdendaleTests
//
//  Unit tests for Section A: DecoderProtocol and FormatRouter.
//

import AVFoundation
import CoreMedia
import CoreVideo
import Foundation
import Testing
@testable import Edendale

@MainActor
struct FormatRouterTests {

    // MARK: - Extension Container Routing

    @Test func mkvFileRoutesToFFmpeg() async {
        let url = URL(fileURLWithPath: "/tmp/sample_movie.mkv")
        let kind = await FormatRouter.route(url)
        #expect(kind == .ffmpeg)
    }

    @Test func aviFileRoutesToFFmpeg() async {
        let url = URL(fileURLWithPath: "/tmp/sample_movie.avi")
        let kind = await FormatRouter.route(url)
        #expect(kind == .ffmpeg)
    }

    @Test func flvFileRoutesToFFmpeg() async {
        let url = URL(fileURLWithPath: "/tmp/sample_stream.flv")
        let kind = await FormatRouter.route(url)
        #expect(kind == .ffmpeg)
    }

    @Test func tsFileRoutesToFFmpeg() async {
        let url = URL(fileURLWithPath: "/tmp/broadcast.ts")
        let kind = await FormatRouter.route(url)
        #expect(kind == .ffmpeg)
    }

    @Test func webmFileRoutesToFFmpeg() async {
        let url = URL(fileURLWithPath: "/tmp/video.webm")
        let kind = await FormatRouter.route(url)
        #expect(kind == .ffmpeg)
    }

    // MARK: - SMB URL Routing

    @Test func smbURLRoutesToFFmpeg() async throws {
        let url = try #require(URL(string: "smb://nas.local/Media/movie.mp4"))
        let kind = await FormatRouter.route(url)
        #expect(kind == .ffmpeg)
    }

    @Test func smbWithCredentialsRoutesToFFmpeg() async throws {
        let url = try #require(URL(string: "smb://user:secret@192.168.1.100:445/Share/clip.mov"))
        let kind = await FormatRouter.route(url)
        #expect(kind == .ffmpeg)
    }

    // MARK: - MP4 / MOV Probing

    @Test func corruptOrEmptyMP4RoutesToFFmpeg() async throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let emptyMP4 = tempDir.appendingPathComponent("empty.mp4")
        try Data().write(to: emptyMP4)

        let kind = await FormatRouter.route(emptyMP4)
        #expect(kind == .ffmpeg)
    }

    @Test func validH264MP4RoutesToAVFoundation() async throws {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let mp4URL = tempDir.appendingPathComponent("sample_h264.mp4")
        try await createTestMP4(at: mp4URL)

        let kind = await FormatRouter.route(mp4URL)
        #expect(kind == .avFoundation)
    }

    // MARK: - Codec Classification

    @Test func supportedVideoCodecsIdentifiedCorrectly() {
        #expect(FormatRouter.isSupportedAVFoundationVideoCodec(kCMVideoCodecType_H264))
        #expect(FormatRouter.isSupportedAVFoundationVideoCodec(kCMVideoCodecType_HEVC))
        #expect(FormatRouter.isSupportedAVFoundationVideoCodec(kCMVideoCodecType_AV1))
        #expect(FormatRouter.isSupportedAVFoundationVideoCodec(kCMVideoCodecType_AppleProRes422))
        #expect(FormatRouter.isSupportedAVFoundationVideoCodec(kCMVideoCodecType_AppleProRes4444))

        // VP8 & VP9 are not supported by AVFoundation
        let vp9Code: FourCharCode = fourCharCode("vp09")
        let vp8Code: FourCharCode = fourCharCode("vp08")
        #expect(!FormatRouter.isSupportedAVFoundationVideoCodec(vp9Code))
        #expect(!FormatRouter.isSupportedAVFoundationVideoCodec(vp8Code))
        #expect(FormatRouter.isUnsupportedVideoCodec(vp9Code))
        #expect(FormatRouter.isUnsupportedVideoCodec(vp8Code))
    }

    @Test func dtsAndUnsupportedAudioCodecsIdentifiedCorrectly() {
        let dtsCode: FourCharCode = fourCharCode("dts ")
        let dtshCode: FourCharCode = fourCharCode("dtsh")
        let dtseCode: FourCharCode = fourCharCode("dtse")
        let trhdCode: FourCharCode = fourCharCode("trhd")
        let aacCode: FourCharCode = fourCharCode("aac ")

        #expect(FormatRouter.isDTSAudio(dtsCode))
        #expect(FormatRouter.isDTSAudio(dtshCode))
        #expect(FormatRouter.isDTSAudio(dtseCode))
        #expect(!FormatRouter.isDTSAudio(aacCode))

        #expect(FormatRouter.isUnsupportedAudioCodec(dtsCode))
        #expect(FormatRouter.isUnsupportedAudioCodec(trhdCode))
        #expect(!FormatRouter.isUnsupportedAudioCodec(aacCode))
    }

    // MARK: - Protocol & Metadata Types

    @Test func mediaInfoAndTrackInfoConstructible() {
        let video = VideoTrackInfo(
            index: 0,
            codec: "hevc",
            size: CGSize(width: 3840, height: 2160),
            bitDepth: 10,
            isHardwareDecodable: true
        )
        let audio = AudioTrackInfo(
            index: 1,
            codec: "aac",
            channelCount: 6,
            sampleRate: 48000,
            language: "eng",
            title: "Surround 5.1"
        )
        let subtitle = SubtitleTrackInfo(
            index: 2,
            codec: "ass",
            language: "jpn",
            title: "Dialogue",
            isImageBased: false
        )

        let info = MediaInfo(
            duration: CMTime(seconds: 120, preferredTimescale: 600),
            videoTracks: [video],
            audioTracks: [audio],
            subtitleTracks: [subtitle],
            naturalSize: CGSize(width: 3840, height: 2160),
            frameRate: 24.0,
            isHDR: true
        )

        #expect(info.videoTracks.count == 1)
        #expect(info.videoTracks[0].id == 0)
        #expect(info.videoTracks[0].codec == "hevc")
        #expect(info.audioTracks[0].channelCount == 6)
        #expect(info.subtitleTracks[0].isImageBased == false)
        #expect(info.isHDR == true)
    }

    @Test func decoderStateTransitionsAndEquality() {
        #expect(DecoderState.idle == DecoderState.idle)
        #expect(DecoderState.playing == DecoderState.playing)
        #expect(DecoderState.paused == DecoderState.paused)
        #expect(DecoderState.seeking == DecoderState.seeking)
        #expect(DecoderState.ended == DecoderState.ended)
        #expect(DecoderState.idle != DecoderState.playing)

        let err1 = DecoderError.unsupportedFormat("corrupt header")
        let err2 = DecoderError.unsupportedFormat("corrupt header")
        let err3 = DecoderError.assetNotPlayable

        #expect(DecoderState.error(err1) == DecoderState.error(err2))
        #expect(DecoderState.error(err1) != DecoderState.error(err3))
    }

    @Test func mockDecoderConformsToMediaDecoder() async throws {
        let mock = MockDecoder()
        #expect(mock.state == .idle)
        #expect(mock.currentTime == .zero)

        var stateUpdates: [DecoderState] = []
        var timeUpdates: [CMTime] = []
        mock.onStateChanged = { stateUpdates.append($0) }
        mock.onTimeChanged = { timeUpdates.append($0) }

        let testURL = URL(fileURLWithPath: "/tmp/mock.mp4")
        let info = try await mock.open(url: testURL)
        #expect(info.videoTracks.count == 1)
        #expect(mock.state == .ready)

        mock.play()
        #expect(mock.state == .playing)

        mock.pause()
        #expect(mock.state == .paused)

        try await mock.seek(to: CMTime(seconds: 10, preferredTimescale: 600))
        #expect(mock.currentTime.seconds == 10)

        mock.setRate(1.5)
        #expect(mock.rate == 1.5)

        mock.selectAudioTrack(0)
        #expect(mock.selectedAudioIndex == 0)

        mock.selectSubtitleTrack(nil)
        #expect(mock.selectedSubtitleIndex == nil)

        mock.close()
        #expect(mock.state == .idle)
        #expect(stateUpdates.count > 0)
    }

    // MARK: - Test Helpers

    private func fourCharCode(_ str: String) -> FourCharCode {
        str.utf8.reduce(0) { ($0 << 8) | UInt32($1) }
    }

    private func createTestMP4(at url: URL) async throws {
        let writer = try AVAssetWriter(url: url, fileType: .mp4)

        let videoSettings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: 160,
            AVVideoHeightKey: 120
        ]

        let writerInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
        writerInput.expectsMediaDataInRealTime = false

        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: writerInput,
            sourcePixelBufferAttributes: [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey as String: 160,
                kCVPixelBufferHeightKey as String: 120
            ]
        )

        guard writer.canAdd(writerInput) else {
            throw DecoderError.playbackFailed("Cannot add video input to asset writer")
        }
        writer.add(writerInput)

        writer.startWriting()
        writer.startSession(atSourceTime: .zero)

        var pixelBuffer: CVPixelBuffer?
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            160,
            120,
            kCVPixelFormatType_32BGRA,
            nil,
            &pixelBuffer
        )
        guard status == kCVReturnSuccess, let buffer = pixelBuffer else {
            throw DecoderError.playbackFailed("Failed to allocate pixel buffer")
        }

        while !writerInput.isReadyForMoreMediaData {
            try await Task.sleep(nanoseconds: 10_000_000)
        }

        adaptor.append(buffer, withPresentationTime: .zero)
        writerInput.markAsFinished()
        await writer.finishWriting()
    }
}

private enum DecoderError: Error, Equatable {
    case playbackFailed(String)
    case unsupportedFormat(String)
    case assetNotPlayable
}

// MARK: - Mock Decoder Implementation

@MainActor
private final class MockDecoder: MediaDecoder {
    var state: DecoderState = .idle {
        didSet { onStateChanged?(state) }
    }
    var currentTime: CMTime = .zero {
        didSet { onTimeChanged?(currentTime) }
    }
    var mediaInfo: MediaInfo?
    var onStateChanged: (@MainActor (DecoderState) -> Void)?
    var onTimeChanged: (@MainActor (CMTime) -> Void)?
    var onVideoFrame: (@MainActor (DecodedVideoFrame) -> Void)?

    var rate: Float = 1.0
    var selectedAudioIndex: Int?
    var selectedSubtitleIndex: Int?

    func open(url: URL) async throws -> MediaInfo {
        state = .opening
        let info = MediaInfo(
            duration: CMTime(seconds: 60, preferredTimescale: 600),
            videoTracks: [
                VideoTrackInfo(index: 0, codec: "h264", size: CGSize(width: 1920, height: 1080), bitDepth: 8, isHardwareDecodable: true)
            ],
            audioTracks: [
                AudioTrackInfo(index: 1, codec: "aac", channelCount: 2, sampleRate: 44100, language: nil, title: nil)
            ],
            subtitleTracks: [],
            naturalSize: CGSize(width: 1920, height: 1080),
            frameRate: 30.0,
            isHDR: false
        )
        self.mediaInfo = info
        state = .ready
        return info
    }

    func play() {
        state = .playing
    }

    func pause() {
        state = .paused
    }

    func seek(to time: CMTime) async throws {
        state = .seeking
        currentTime = time
        state = .paused
    }

    func setRate(_ rate: Float) {
        self.rate = rate
    }

    func selectAudioTrack(_ index: Int) {
        selectedAudioIndex = index
    }

    func selectSubtitleTrack(_ index: Int?) {
        selectedSubtitleIndex = index
    }

    func close() {
        state = .idle
        mediaInfo = nil
        currentTime = .zero
    }
}
