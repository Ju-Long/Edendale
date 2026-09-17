//
//  AVFoundationDecoderTests.swift
//  EdendaleTests
//
//  Unit tests for AVFoundationDecoder verifying lifecycle, playback, seek,
//  frame delivery, and metadata extraction.
//

import AVFoundation
import CoreMedia
import CoreVideo
import Foundation
import Testing
@testable import Edendale

@MainActor
struct AVFoundationDecoderTests {

    /// Helper to generate a minimal valid MP4 file with H.264 video.
    private static func createTestVideo(
        durationSeconds: Double = 1.0,
        size: CGSize = CGSize(width: 320, height: 240),
        frameRate: Int32 = 30
    ) async throws -> URL {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("EdendaleDecoderTests_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        let outputURL = tempDir.appendingPathComponent("test_sample.mp4")

        let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
        let videoSettings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(size.width),
            AVVideoHeightKey: Int(size.height)
        ]
        let writerInput = AVAssetWriterInput(mediaType: .video, outputSettings: videoSettings)
        writerInput.expectsMediaDataInRealTime = false

        let attributes: [String: Any] = [
            kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
            kCVPixelBufferWidthKey as String: Int(size.width),
            kCVPixelBufferHeightKey as String: Int(size.height)
        ]
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(
            assetWriterInput: writerInput,
            sourcePixelBufferAttributes: attributes
        )

        guard writer.canAdd(writerInput) else {
            throw AVFoundationDecoderError.trackLoadingFailed("Cannot add video input to asset writer")
        }
        writer.add(writerInput)

        writer.startWriting()
        writer.startSession(atSourceTime: .zero)

        let totalFrames = Int(Double(frameRate) * durationSeconds)
        var bufferPool: CVPixelBufferPool?
        CVPixelBufferPoolCreate(nil, nil, attributes as CFDictionary, &bufferPool)

        for i in 0..<totalFrames {
            while !writerInput.isReadyForMoreMediaData {
                try await Task.sleep(nanoseconds: 10_000_000)
            }
            var pixelBuffer: CVPixelBuffer?
            if let pool = bufferPool {
                CVPixelBufferPoolCreatePixelBuffer(nil, pool, &pixelBuffer)
            }
            if pixelBuffer == nil {
                CVPixelBufferCreate(
                    nil,
                    Int(size.width),
                    Int(size.height),
                    kCVPixelFormatType_32BGRA,
                    nil,
                    &pixelBuffer
                )
            }
            if let pixelBuffer {
                let presentationTime = CMTime(value: CMTimeValue(i), timescale: frameRate)
                adaptor.append(pixelBuffer, withPresentationTime: presentationTime)
            }
        }

        writerInput.markAsFinished()
        await writer.finishWriting()
        return outputURL
    }

    @Test func initialDecoderState() {
        let decoder = AVFoundationDecoder()
        #expect(decoder.state == .idle)
        #expect(decoder.currentTime == .zero)
        #expect(decoder.mediaInfo == nil)
    }

    @Test func openValidMP4PopulatesMediaInfo() async throws {
        let fileURL = try await Self.createTestVideo(durationSeconds: 1.0, size: CGSize(width: 320, height: 240))
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let decoder = AVFoundationDecoder(pixelFormatPreference: .automatic)
        let info = try await decoder.open(url: fileURL)

        #expect(decoder.state == .ready)
        #expect(info.videoTracks.count == 1)
        #expect(info.videoTracks[0].codec == "h264")
        #expect(info.videoTracks[0].size.width == 320)
        #expect(info.videoTracks[0].size.height == 240)
        #expect(info.videoTracks[0].isHardwareDecodable == true)
        #expect(info.duration.seconds > 0.8)
        #expect(info.frameRate == 30.0)
    }

    @Test func transportControlsPlayAndPause() async throws {
        let fileURL = try await Self.createTestVideo(durationSeconds: 1.0)
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let decoder = AVFoundationDecoder()
        _ = try await decoder.open(url: fileURL)

        decoder.play()
        #expect(decoder.state == .playing)

        decoder.pause()
        #expect(decoder.state == .paused)

        decoder.setRate(1.5)
        #expect(decoder.state == .paused)

        decoder.play()
        #expect(decoder.state == .playing)
    }

    @Test func seekUpdatesCurrentTime() async throws {
        let fileURL = try await Self.createTestVideo(durationSeconds: 2.0)
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let decoder = AVFoundationDecoder()
        _ = try await decoder.open(url: fileURL)

        let targetTime = CMTime(seconds: 1.0, preferredTimescale: 600)
        try await decoder.seek(to: targetTime)

        #expect(decoder.currentTime.seconds >= 0.8)
    }

    @Test func closeResetsDecoderState() async throws {
        let fileURL = try await Self.createTestVideo(durationSeconds: 1.0)
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let decoder = AVFoundationDecoder()
        _ = try await decoder.open(url: fileURL)
        #expect(decoder.state == .ready)

        decoder.close()
        #expect(decoder.state == .idle)
        #expect(decoder.mediaInfo == nil)
    }

    @Test func openInvalidURLFails() async {
        let decoder = AVFoundationDecoder()
        let badURL = URL(fileURLWithPath: "/tmp/non_existent_file_\(UUID().uuidString).mp4")

        await #expect(throws: Error.self) {
            try await decoder.open(url: badURL)
        }
        #expect(decoder.state != .ready)
    }

    @Test func frameDeliveryDeliversPixelBuffers() async throws {
        let fileURL = try await Self.createTestVideo(durationSeconds: 1.5, size: CGSize(width: 320, height: 240))
        defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }

        let decoder = AVFoundationDecoder()
        _ = try await decoder.open(url: fileURL)

        var receivedFrames: [DecodedVideoFrame] = []
        decoder.onVideoFrame = { frame in
            receivedFrames.append(frame)
        }

        decoder.play()

        var waited = 0
        while receivedFrames.isEmpty && waited < 40 {
            try await Task.sleep(nanoseconds: 50_000_000)
            waited += 1
        }

        decoder.pause()

        #expect(!receivedFrames.isEmpty)
        if let firstFrame = receivedFrames.first {
            let width = CVPixelBufferGetWidth(firstFrame.pixelBuffer)
            let height = CVPixelBufferGetHeight(firstFrame.pixelBuffer)
            #expect(width == 320)
            #expect(height == 240)
            #expect(firstFrame.presentationTime.isValid)
        }
    }
}
