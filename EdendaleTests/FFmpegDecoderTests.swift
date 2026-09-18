import AVFoundation
import CoreMedia
import Foundation
import Testing
@testable import Edendale

private final class FFmpegTestResources: NSObject { }

@MainActor
@Suite(.serialized)
struct FFmpegDecoderTests {
    private func fixture(_ name: String = "decoder-h264-aac") throws -> URL {
        let bundle = Bundle(for: FFmpegTestResources.self)
        return try #require(bundle.url(forResource: name, withExtension: "mkv")
            ?? bundle.url(forResource: name, withExtension: "mkv", subdirectory: "Fixtures"))
    }

    private func wait(until predicate: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(6)
        while !predicate() && Date() < deadline { try await Task.sleep(for: .milliseconds(20)) }
        #expect(predicate())
    }

    @Test func demuxesMKVAndDrainsVideoAndAudiblePCM() throws {
        let reader = EDFFmpegReader(hardwareDecoding: false)
        defer { reader.close() }
        try reader.open(url: fixture())
        #expect(reader.mediaInfo["width"] as? Int == 160)
        #expect(reader.mediaInfo["height"] as? Int == 90)
        let audio = try #require(reader.mediaInfo["audio"] as? [[String: Any]])
        #expect(audio.count == 2)
        #expect(audio.first?["index"] as? Int == 1)
        var frames = 0
        var samples = 0
        var audible = false
        var lastVideoTime = -Double.infinity
        for _ in 0..<1000 {
            if reader.atEnd { break }
            let batch = try reader.readBatch()
            for frame in batch {
                if let pixel = frame.pixelBuffer {
                    frames += 1
                    #expect(CVPixelBufferGetWidth(pixel) == 160)
                    #expect(CVPixelBufferGetHeight(pixel) == 90)
                    #expect(frame.presentationTime >= lastVideoTime)
                    lastVideoTime = frame.presentationTime
                }
                if let audio = frame.audioSampleBuffer, let block = CMSampleBufferGetDataBuffer(audio) {
                    samples += CMSampleBufferGetNumSamples(audio)
                    var values = [Float](repeating: 0, count: CMBlockBufferGetDataLength(block) / 4)
                    let result = values.withUnsafeMutableBytes {
                        CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: $0.count, destination: $0.baseAddress!)
                    }
                    #expect(result == kCMBlockBufferNoErr)
                    audible = audible || values.contains(where: { abs($0) > 0.001 })
                }
            }
        }
        #expect(frames == 36) // Includes delayed B frames drained at EOF.
        #expect(samples >= 140_000)
        #expect(audible)
        #expect(reader.atEnd)
        #expect(try reader.readBatch().isEmpty)
    }

    @Test func decodesDTSAndSoftwareVideo() throws {
        let reader = EDFFmpegReader(hardwareDecoding: false)
        defer { reader.close() }
        try reader.open(url: fixture("decoder-mpeg4-dts"))
        var videos = 0, audio = 0
        for _ in 0..<1000 {
            if reader.atEnd { break }
            let batch = try reader.readBatch()
            videos += batch.filter { $0.pixelBuffer != nil }.count
            audio += batch.filter { $0.audioSampleBuffer != nil }.count
        }
        #expect(videos == 12)
        #expect(audio > 0)
    }

    @Test func seekFlushesOldFramesAndSwitchesAudioStream() throws {
        let reader = EDFFmpegReader(hardwareDecoding: false)
        defer { reader.close() }
        try reader.open(url: fixture())
        _ = try reader.readBatch()
        try reader.selectAudioTrack(2)
        try reader.seek(seconds: 1.5)
        var videos = 0, audio = 0
        for _ in 0..<1000 {
            if reader.atEnd { break }
            let batch = try reader.readBatch()
            for frame in batch {
                #expect(frame.presentationTime >= 1.5 - 0.00001)
                if frame.pixelBuffer != nil { videos += 1 }
                if frame.audioSampleBuffer != nil { audio += 1 }
            }
        }
        #expect(videos > 0 && videos < 36)
        #expect(audio > 0)
        try reader.seek(seconds: 0)
        #expect(!reader.atEnd)
        #expect(try !reader.readBatch().isEmpty)
    }

    @Test func lifecycleDeliversFramesPausesSeeksAndEnds() async throws {
        let decoder = FFmpegDecoder()
        decoder.isMuted = true
        defer { decoder.close() }
        var times: [Double] = []
        decoder.onVideoFrame = { times.append($0.presentationTime.seconds) }
        let info = try await decoder.open(url: fixture())
        #expect(info.videoTracks.first?.codec == "h264")
        #expect(info.naturalSize == CGSize(width: 160, height: 90))
        #expect(info.audioTracks.count == 2)
        #expect(info.audioTracks.first?.index == 1)
        #expect(decoder.state == .ready)
        decoder.play()
        try await wait { times.count >= 3 && decoder.currentTime.seconds > 0.2 }
        decoder.pause()
        let pausedTime = decoder.currentTime.seconds
        try await Task.sleep(for: .milliseconds(150))
        #expect(abs(decoder.currentTime.seconds - pausedTime) < 0.03)
        try await decoder.seek(to: CMTime(seconds: 2, preferredTimescale: 600))
        #expect(decoder.state == .paused)
        times.removeAll()
        try await wait { !times.isEmpty }
        #expect(times.allSatisfy { $0 >= 2 })
        decoder.setRate(2)
        decoder.play()
        try await wait { decoder.state == .ended }
        #expect(decoder.currentTime.seconds >= 2.9)
    }

    @Test func closeCancelsPendingOpenAndSuppressesLateFrames() async throws {
        let decoder = FFmpegDecoder(hardwareDecoding: false)
        let url = try fixture()
        let opening = Task { try await decoder.open(url: url) }
        await Task.yield()
        decoder.close()
        _ = try? await opening.value
        #expect(decoder.state == .idle)
        #expect(decoder.mediaInfo == nil)
        #expect(decoder.currentTime == .zero)
        var frames = 0
        decoder.onVideoFrame = { _ in frames += 1 }
        _ = try await decoder.open(url: url)
        decoder.play()
        try await wait { frames > 0 }
        decoder.close()
        let count = frames
        try await Task.sleep(for: .milliseconds(100))
        #expect(frames == count)
        #expect(decoder.state == .idle)
    }

    @Test func missingFileReportsAnOpenErrorInsteadOfTheStubMessage() async {
        let decoder = FFmpegDecoder()
        defer { decoder.close() }
        do {
            _ = try await decoder.open(url: URL(fileURLWithPath: "/missing-\(UUID()).mkv"))
            Issue.record("Opening a missing file should fail")
        } catch {
            #expect(!error.localizedDescription.contains("not yet available"))
            if case .error = decoder.state { } else { Issue.record("Expected error state") }
        }
    }

    @Test func playbackEngineRoutesFramesAndSelectsTheFirstAudioStream() async throws {
        let engine = PlaybackEngine()
        engine.isMuted = true
        defer { engine.close() }
        try await engine.open(url: fixture())
        #expect(engine.decoder is FFmpegDecoder)
        #expect(engine.selectedAudioTrack?.trackIndex == 1)
        engine.play()
        try await wait { !engine.ringBuffer.isEmpty && engine.currentTime.playbackSeconds > 0.1 }
        #expect(engine.state == .playing)
        engine.pause()
        #expect(engine.state == .paused)
    }
}
