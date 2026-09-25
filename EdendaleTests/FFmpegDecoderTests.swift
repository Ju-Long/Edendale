import AVFoundation
import AVKit
import CoreMedia
import Foundation
import Testing
@testable import Edendale

private final class FFmpegTestResources: NSObject { }

@MainActor
@Suite(.serialized)
struct FFmpegDecoderTests {
    @Test func selectingEmbeddedSubtitlesKeepsFramesMovingAndRendersCues() async throws {
        let engine = PlaybackEngine()
        engine.isMuted = true
        defer { engine.close() }
        try await engine.open(url: fixture("decoder-subtitles"))
        engine.subtitleEngine.setCanvasSize(CGSize(width: 320, height: 180))
        let track = try #require(engine.subtitleTracks.first)
        engine.play()
        try await wait { engine.currentTime.playbackSeconds > 0.3 }
        let before = engine.videoPresentationTime.seconds
        engine.selectedSubtitleTrack = track
        try await wait {
            engine.videoPresentationTime.seconds > before + 0.3 &&
            engine.subtitleEngine.renderSubtitleTexture(at: engine.videoPresentationTime) != nil
        }
        #expect(engine.isPlaying)
        #expect(engine.state == .playing)
        // Reselecting the same row must not clear the already selected renderer.
        engine.selectedSubtitleTrack = track
        #expect(engine.subtitleEngine.renderSubtitleTexture(at: engine.videoPresentationTime) != nil)
        engine.selectedSubtitleTrack = nil
        #expect(engine.subtitleEngine.activeFormat == nil)
        let time = engine.currentTime.playbackSeconds
        try await wait { engine.currentTime.playbackSeconds > time + 0.2 }
        #expect(engine.subtitleEngine.activeFormat == nil)
    }

    @Test func pausedSubtitleSelectionRapidSwitchAndSeekPreserveState() async throws {
        let engine = PlaybackEngine()
        engine.isMuted = true
        defer { engine.close() }
        try await engine.open(url: fixture("decoder-subtitles"))
        engine.subtitleEngine.setCanvasSize(CGSize(width: 320, height: 180))
        let decoder = try #require(engine.decoder as? FFmpegDecoder)
        engine.pause()
        try await decoder.seek(to: CMTime(seconds: 0.5, preferredTimescale: 600))
        let first = try #require(engine.subtitleTracks.first)
        let last = try #require(engine.subtitleTracks.last)
        engine.selectedSubtitleTrack = first
        engine.selectedSubtitleTrack = last
        try await wait { engine.subtitleEngine.renderSubtitleTexture(at: CMTime(seconds: 0.5, preferredTimescale: 600)) != nil }
        #expect(engine.selectedSubtitleTrack?.id == last.id)
        #expect(!engine.isPlaying)
        #expect(abs(engine.currentTime.playbackSeconds - 0.5) < 0.03)
        try await decoder.seek(to: CMTime(seconds: 2.5, preferredTimescale: 600))
        try await wait { engine.subtitleEngine.renderSubtitleTexture(at: CMTime(seconds: 2.5, preferredTimescale: 600)) != nil }
        #expect(!engine.isPlaying)
        #expect(engine.subtitleEngine.renderSubtitleTexture(at: CMTime(seconds: 0.5, preferredTimescale: 600)) == nil)
        engine.selectedSubtitleTrack = first
        engine.selectedSubtitleTrack = nil
        try await Task.sleep(for: .milliseconds(150))
        #expect(engine.selectedSubtitleTrack == nil)
        #expect(engine.subtitleEngine.activeFormat == nil)
    }

    @Test func subtitleDemuxingReturnsTextInsteadOfScanningToEOF() throws {
        let reader = EDFFmpegReader(hardwareDecoding: false)
        defer { reader.close() }
        try reader.open(url: fixture("decoder-subtitles"))
        let tracks = try #require(reader.mediaInfo["subtitle"] as? [[String: Any]])
        #expect(tracks.count == 2)
        for track in tracks {
            let configuration = try reader.selectSubtitleTrack(try #require(track["index"] as? Int))
            #expect(configuration["format"] as? String == "ass")
            try reader.seek(seconds: 0)
            var cue: EDFFmpegFrame?
            for _ in 0..<100 {
                cue = try reader.readBatch().first(where: { $0.subtitle != nil })
                if cue != nil { break }
            }
            let first = try #require(cue)
            #expect(!reader.atEnd)
            #expect(first.presentationTime < 0.3)
            #expect(first.duration > 1)
            #expect((first.subtitle?["texts"] as? [String])?.first?.contains("first") == true ||
                    (first.subtitle?["texts"] as? [String])?.first?.contains("First") == true)
        }
    }

    #if os(iOS) || os(macOS)
    @Test func pipTransportUsesOneRequestAndMatchesThePlaybackClock() async throws {
        let engine = PlaybackEngine()
        engine.isMuted = true
        defer { engine.close() }
        try await engine.open(url: fixture())
        let source = engine.pipSource
        let controller = try #require(source.pipController)
        engine.play()
        try await wait { engine.currentTime.playbackSeconds > 0.1 }
        #expect(!source.pictureInPictureControllerIsPlaybackPaused(controller))
        let timebase = try #require(source.displayLayer.controlTimebase)
        #expect(CMTimebaseGetRate(timebase) == 1)
        source.pictureInPictureController(controller, setPlaying: false)
        try await wait { !engine.isPlaying }
        #expect(source.pictureInPictureControllerIsPlaybackPaused(controller))
        #expect(CMTimebaseGetRate(timebase) == 0)
        source.pictureInPictureController(controller, setPlaying: true)
        try await wait { engine.isPlaying }
        #expect(!source.pictureInPictureControllerIsPlaybackPaused(controller))
        engine.setRate(1.5)
        #expect(CMTimebaseGetRate(timebase) == 1.5)
        #expect(abs(CMTimebaseGetTime(timebase).seconds - engine.currentTime.playbackSeconds) < 0.1)
    }

    @Test func reopeningMediaKeepsTheLayersPictureInPictureController() async throws {
        let engine = PlaybackEngine()
        engine.isMuted = true
        defer { engine.close() }
        // AVKit keeps an unretained pointer from the display layer to its first
        // controller, so replacing the controller made later calls read freed memory.
        let controller = try #require(engine.pipSource.pipController)
        try await engine.open(url: fixture())
        try await engine.open(url: fixture("decoder-mpeg4-dts"))
        engine.close()
        #expect(engine.pipSource.pipController === controller)
    }
    #endif

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

    @Test func openMalformedSMBURLFailsWithInvalidURLError() throws {
        let reader = EDFFmpegReader(hardwareDecoding: false)
        defer { reader.close() }
        let urlNoPath = try #require(URL(string: "smb://127.0.0.1/share"))
        do {
            try reader.open(url: urlNoPath)
            Issue.record("Expected open to fail on incomplete SMB URL")
        } catch {
            let nsError = error as NSError
            #expect(nsError.domain == "Edendale.FFmpeg")
            #expect(nsError.localizedDescription.contains("Invalid SMB URL"))
        }

        let urlNoHost = try #require(URL(string: "smb:///share/test.mkv"))
        do {
            try reader.open(url: urlNoHost)
            Issue.record("Expected open to fail on missing host")
        } catch {
            let nsError = error as NSError
            #expect(nsError.domain == "Edendale.FFmpeg")
            #expect(nsError.localizedDescription.contains("missing host"))
        }
    }

    @Test func openUnreachableSMBURLAttemptsSMBConnectionWithoutProtocolNotFoundError() throws {
        let reader = EDFFmpegReader(hardwareDecoding: false)
        defer { reader.close() }
        let smbURL = try #require(URL(string: "smb://user:pass@127.0.0.1:1/share/movie.mkv"))
        do {
            try reader.open(url: smbURL)
            Issue.record("Expected open to fail connecting to port 1")
        } catch {
            let nsError = error as NSError
            #expect(nsError.domain == "Edendale.FFmpeg")
            #expect(nsError.code != -1330794744)
            #expect(!nsError.localizedDescription.contains("Protocol not found"))
            #expect(nsError.localizedDescription.contains("SMB connect failed"))
        }
    }

    @Test func videoDecodingDisabledSkipsVideoFramesWhileContinuingAudio() throws {
        let reader = EDFFmpegReader(hardwareDecoding: false)
        defer { reader.close() }
        try reader.open(url: fixture())

        reader.videoDecodingEnabled = false
        var videoCount = 0
        var audioCount = 0

        for _ in 0..<10 {
            if reader.atEnd { break }
            let batch = try reader.readBatch()
            videoCount += batch.filter { $0.pixelBuffer != nil }.count
            audioCount += batch.filter { $0.audioSampleBuffer != nil }.count
        }

        #expect(videoCount == 0, "Video frames should be skipped when videoDecodingEnabled is false")
        #expect(audioCount > 0, "Audio frames should continue to decode when videoDecodingEnabled is false")

        try reader.seek(seconds: 0)
        reader.videoDecodingEnabled = true
        #expect(reader.recreateVideoDecoder(), "recreateVideoDecoder should succeed")

        var resumedVideoCount = 0
        for _ in 0..<100 {
            if reader.atEnd { break }
            let batch = try reader.readBatch()
            resumedVideoCount += batch.filter { $0.pixelBuffer != nil }.count
        }
        #expect(resumedVideoCount > 0, "Video frames should resume decoding when videoDecodingEnabled is true")
    }

    @Test func decoderSetVideoDecodingEnabledControlsVideoPumping() async throws {
        let decoder = FFmpegDecoder(hardwareDecoding: false)
        defer { decoder.close() }
        _ = try await decoder.open(url: fixture())
        decoder.play()

        decoder.setVideoDecodingEnabled(false)
        #expect(!decoder.isVideoDecodingEnabled)

        try await Task.sleep(for: .milliseconds(150))
        decoder.setVideoDecodingEnabled(true)
        #expect(decoder.isVideoDecodingEnabled)
    }
}
