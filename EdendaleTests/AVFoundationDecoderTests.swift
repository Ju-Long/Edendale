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

private final class AVFoundationTestResources: NSObject { }

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

    /// About two seconds of audio in an MP4: the FFmpeg fixture's E-AC-3,
    /// copied unit for unit, or AAC encoded from silence.
    private static func createTestAudio(eac3: Bool) async throws -> URL {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("EdendaleDecoderTests_\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        let outputURL = tempDir.appendingPathComponent("test_audio.mp4")
        var samples: [CMSampleBuffer] = []
        let input: AVAssetWriterInput
        if eac3 {
            let bundle = Bundle(for: AVFoundationTestResources.self)
            let fixture = try #require(bundle.url(forResource: "decoder-eac3", withExtension: "mkv")
                ?? bundle.url(forResource: "decoder-eac3", withExtension: "mkv", subdirectory: "Fixtures"))
            let reader = EDFFmpegReader(hardwareDecoding: false)
            reader.passesThroughEAC3 = true
            defer { reader.close() }
            try reader.open(url: fixture)
            while !reader.atEnd { samples += try reader.readBatch().compactMap(\.audioSampleBuffer) }
            let format = try #require(samples.first.flatMap(CMSampleBufferGetFormatDescription))
            input = AVAssetWriterInput(mediaType: .audio, outputSettings: nil, sourceFormatHint: format)
        } else {
            var description = AudioStreamBasicDescription(mSampleRate: 48_000, mFormatID: kAudioFormatLinearPCM,
                mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked, mBytesPerPacket: 8,
                mFramesPerPacket: 1, mBytesPerFrame: 8, mChannelsPerFrame: 2, mBitsPerChannel: 32, mReserved: 0)
            var format: CMAudioFormatDescription?
            CMAudioFormatDescriptionCreate(allocator: nil, asbd: &description, layoutSize: 0, layout: nil,
                magicCookieSize: 0, magicCookie: nil, extensions: nil, formatDescriptionOut: &format)
            let pcm = try #require(format)
            for packet in 0..<94 {
                var block: CMBlockBuffer?
                CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: 8192,
                    blockAllocator: nil, customBlockSource: nil, offsetToData: 0, dataLength: 8192,
                    flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block)
                CMBlockBufferFillDataBytes(with: 0, blockBuffer: block!, offsetIntoDestination: 0, dataLength: 8192)
                var sample: CMSampleBuffer?
                CMAudioSampleBufferCreateReadyWithPacketDescriptions(allocator: nil, dataBuffer: block!,
                    formatDescription: pcm, sampleCount: 1024,
                    presentationTimeStamp: CMTime(value: CMTimeValue(packet * 1024), timescale: 48_000),
                    packetDescriptions: nil, sampleBufferOut: &sample)
                samples.append(try #require(sample))
            }
            input = AVAssetWriterInput(mediaType: .audio, outputSettings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 48_000, AVNumberOfChannelsKey: 2,
            ])
        }
        input.expectsMediaDataInRealTime = false
        let writer = try AVAssetWriter(outputURL: outputURL, fileType: .mp4)
        writer.add(input)
        try #require(writer.startWriting())
        writer.startSession(atSourceTime: .zero)
        for sample in samples {
            while !input.isReadyForMoreMediaData {
                try await Task.sleep(nanoseconds: 5_000_000)
            }
            input.append(sample)
        }
        input.markAsFinished()
        await writer.finishWriting()
        try #require(writer.status == .completed, "\(String(describing: writer.error))")
        return outputURL
    }

    @Test func initialDecoderState() {
        let decoder = AVFoundationDecoder()
        #expect(decoder.state == .idle)
        #expect(decoder.currentTime == .zero)
        #expect(decoder.mediaInfo == nil)
    }

    @Test(.enabled(if: FFmpegDecoderTests.systemDecodesEAC3))
    func eac3PlaysWithoutTheEqualizerTapThatOtherAudioGets() async throws {
        // The tap needs decoded audio, which would cost E-AC-3 its object
        // audio; the system decoder plays it untouched instead.
        for (eac3, tapped) in [(true, false), (false, true)] {
            let fileURL = try await Self.createTestAudio(eac3: eac3)
            defer { try? FileManager.default.removeItem(at: fileURL.deletingLastPathComponent()) }
            let decoder = AVFoundationDecoder()
            defer { decoder.close() }
            let info = try await decoder.open(url: fileURL)
            #expect(info.audioTracks.first?.codec == (eac3 ? "eac3" : "aac"))
            decoder.audioProcessor = AudioEQProcessor()
            decoder.installAudioTap()
            #expect((decoder.avPlayer?.currentItem?.audioMix != nil) == tapped)
        }
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
