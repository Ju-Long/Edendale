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

    @Test func seekingDecodesTheSameFramesAsPlayingThrough() throws {
        // A seek decodes from the previous keyframe but skips the frames
        // before the target that nothing references. Every frame from the
        // target on must match an uninterrupted decode exactly.
        let target = 1.5
        func frames(seeking: Bool) throws -> [(time: Double, pixels: Data)] {
            let reader = EDFFmpegReader(hardwareDecoding: false)
            defer { reader.close() }
            try reader.open(url: fixture())
            if seeking { try reader.seek(seconds: target) }
            var frames: [(time: Double, pixels: Data)] = []
            while !reader.atEnd {
                for frame in try reader.readBatch() {
                    guard let pixel = frame.pixelBuffer, frame.presentationTime >= target - 0.00001 else { continue }
                    frames.append((frame.presentationTime, pixelData(pixel)))
                }
            }
            return frames
        }
        let continuous = try frames(seeking: false)
        let seeked = try frames(seeking: true)
        #expect(continuous.count == 18)
        #expect(seeked.map(\.time) == continuous.map(\.time))
        #expect(seeked.map(\.pixels) == continuous.map(\.pixels))
    }

    @Test func seekKeepsThePictureUpUntilTheTargetFrameArrives() async throws {
        let engine = PlaybackEngine()
        engine.isMuted = true
        defer { engine.close() }
        try await engine.open(url: fixture())
        let decoder = try #require(engine.decoder as? FFmpegDecoder)
        engine.play()
        try await wait { !engine.ringBuffer.isEmpty && engine.currentTime.playbackSeconds > 0.3 }
        // The renderer draws black whenever the buffer has no frame for it.
        var shownDuringSeek: DecodedVideoFrame?
        let discontinuity = decoder.onDiscontinuity
        decoder.onDiscontinuity = {
            discontinuity?()
            shownDuringSeek = engine.ringBuffer.latestFrame()
        }
        engine.seek(to: .seconds(2))
        try await wait { engine.videoPresentationTime.seconds >= 2 }
        let held = try #require(shownDuringSeek)
        #expect(held.presentationTime.seconds < 1)
        #expect((engine.ringBuffer.latestFrame()?.presentationTime.seconds ?? 0) >= 2)
    }

    @Test func heldVideoKeepsAudioFlowingWithoutLosingFrames() throws {
        // Reads for audio alone leave video packets waiting undecoded while
        // the audio behind them is read; then every frame decodes, in order.
        func videoTimes(_ reader: EDFFmpegReader) throws -> [Double] {
            var times: [Double] = []
            while !reader.atEnd {
                times += try reader.readBatch(decodingVideo: true).filter { $0.pixelBuffer != nil }.map(\.presentationTime)
            }
            return times
        }
        let plain = EDFFmpegReader(hardwareDecoding: false)
        defer { plain.close() }
        try plain.open(url: fixture())
        let expected = try videoTimes(plain)
        #expect(expected.count == 36)

        let reader = EDFFmpegReader(hardwareDecoding: false)
        defer { reader.close() }
        try reader.open(url: fixture())
        var audio = 0
        for _ in 0..<1000 where !reader.blockedOnVideo {
            let batch = try reader.readBatch(decodingVideo: false)
            #expect(batch.allSatisfy { $0.pixelBuffer == nil })
            audio += batch.filter { $0.audioSampleBuffer != nil }.count
        }
        // The demuxer has ended, but the held video still has to decode.
        #expect(reader.blockedOnVideo)
        #expect(audio > 100)
        #expect(!reader.atEnd)
        #expect(try reader.readBatch(decodingVideo: false).isEmpty)
        #expect(try videoTimes(reader) == expected)

        // A seek discards held packets.
        try reader.seek(seconds: 0)
        for _ in 0..<20 { _ = try reader.readBatch(decodingVideo: false) }
        try reader.seek(seconds: 1.5)
        #expect(try videoTimes(reader) == expected.filter { $0 >= 1.5 - 0.00001 })
    }

    @Test func videoBehindItsAudioInTheFileStillReachesTheRenderer() async throws {
        let url = try await audioLeadingClip()
        defer { try? FileManager.default.removeItem(at: url) }
        // AVAssetWriter's interleave, read by FFmpeg's mov demuxer, puts each
        // video frame about a second behind the audio it plays with. The pump
        // used to wait on the full audio renderer with that video unread.
        let reader = EDFFmpegReader(hardwareDecoding: false)
        defer { reader.close() }
        try reader.open(url: url)
        var audioRead = 0.0
        var firstVideo: EDFFmpegFrame?
        for _ in 0..<500 where firstVideo == nil {
            let batch = try reader.readBatch()
            audioRead = max(audioRead, batch.filter { $0.audioSampleBuffer != nil }.map(\.presentationTime).max() ?? 0)
            firstVideo = batch.first { $0.pixelBuffer != nil }
        }
        try #require(audioRead - (firstVideo?.presentationTime ?? .infinity) > 0.5)

        let engine = PlaybackEngine()
        engine.isMuted = true
        defer { engine.close() }
        try await engine.open(url: url)
        let decoder = try #require(engine.decoder as? FFmpegDecoder)
        let frames = 24..<84 // 1 s to 3.5 s
        var shown = Set<Int>()
        var lateness: [Double] = []
        let present = decoder.onVideoFrame
        decoder.onVideoFrame = { frame in
            let index = Int((frame.presentationTime.seconds * 24).rounded())
            if frames.contains(index) {
                shown.insert(index)
                lateness.append(decoder.currentTime.seconds - frame.presentationTime.seconds)
            }
            present?(frame)
        }
        engine.play()
        try await wait { engine.currentTime.playbackSeconds >= 3.5 }
        // Before, a third to two thirds of these frames reached the renderer,
        // on average 0.1–0.2 s late.
        #expect(shown.count >= frames.count * 9 / 10)
        #expect(lateness.reduce(0, +) / Double(max(lateness.count, 1)) < 0.1)
    }

    @Test func seeksShowTheTargetFrameBeforeTheClockMovesOn() async throws {
        let url = try await audioLeadingClip()
        defer { try? FileManager.default.removeItem(at: url) }
        let engine = PlaybackEngine()
        engine.isMuted = true
        defer { engine.close() }
        try await engine.open(url: url)
        let decoder = try #require(engine.decoder as? FFmpegDecoder)
        var target = 0.0
        var clockAtTargetFrame: Double?
        let present = decoder.onVideoFrame
        decoder.onVideoFrame = { frame in
            if clockAtTargetFrame == nil, frame.presentationTime.seconds >= target - 0.001 {
                clockAtTargetFrame = decoder.currentTime.seconds
            }
            present?(frame)
        }
        // Paused, the audio renderer fills while the clock stands still. The
        // target frame, a second further into the file, still has to decode.
        target = 4.3
        try await decoder.seek(to: CMTime(seconds: target, preferredTimescale: 600))
        try await wait { clockAtTargetFrame != nil }

        // Playing, the clock waits for the target frame (mid-GOP, so it takes
        // a while), and picture and sound resume together.
        engine.play()
        target = 6.3
        clockAtTargetFrame = nil
        try await decoder.seek(to: CMTime(seconds: target, preferredTimescale: 600))
        try await wait { clockAtTargetFrame != nil }
        #expect((clockAtTargetFrame ?? .infinity) < target + 0.001)
        try await wait { engine.currentTime.playbackSeconds > target + 0.2 }
    }

    /// Eight seconds of 640×360 24 fps H.264 (B-frames, one 10 s GOP unless
    /// `keyFrameInterval` frames are shorter) and AAC from AVAssetWriter, named
    /// .mkv so FormatRouter picks FFmpeg.
    private func audioLeadingClip(keyFrameInterval: Int = 240) async throws -> URL {
        let directory = FileManager.default.temporaryDirectory
        let mp4 = directory.appendingPathComponent("audio-leading-\(UUID().uuidString).mp4")
        defer { try? FileManager.default.removeItem(at: mp4) }
        let writer = try AVAssetWriter(outputURL: mp4, fileType: .mp4)
        let video = AVAssetWriterInput(mediaType: .video, outputSettings: [
            AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 640, AVVideoHeightKey: 360,
            AVVideoCompressionPropertiesKey: [AVVideoAllowFrameReorderingKey: true,
                                              AVVideoMaxKeyFrameIntervalKey: keyFrameInterval],
        ])
        let pixels = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: video, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: 640, kCVPixelBufferHeightKey as String: 360,
        ])
        let audio = AVAssetWriterInput(mediaType: .audio, outputSettings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 48_000, AVNumberOfChannelsKey: 2,
        ])
        writer.add(video)
        writer.add(audio)
        try #require(writer.startWriting())
        writer.startSession(atSourceTime: .zero)
        var description = AudioStreamBasicDescription(mSampleRate: 48_000, mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsFloat | kAudioFormatFlagIsPacked, mBytesPerPacket: 8,
            mFramesPerPacket: 1, mBytesPerFrame: 8, mChannelsPerFrame: 2, mBitsPerChannel: 32, mReserved: 0)
        var format: CMAudioFormatDescription?
        CMAudioFormatDescriptionCreate(allocator: nil, asbd: &description, layoutSize: 0, layout: nil,
            magicCookieSize: 0, magicCookie: nil, extensions: nil, formatDescriptionOut: &format)
        let pcm = try #require(format)

        final class Progress: @unchecked Sendable { var frame = 0, packet = 0 }
        let progress = Progress()
        let finished = DispatchGroup()
        finished.enter()
        video.requestMediaDataWhenReady(on: DispatchQueue(label: "clip.video")) {
            while video.isReadyForMoreMediaData {
                guard progress.frame < 8 * 24, let pool = pixels.pixelBufferPool else {
                    video.markAsFinished()
                    finished.leave()
                    return
                }
                var buffer: CVPixelBuffer?
                CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
                guard let buffer else { continue }
                CVPixelBufferLockBaseAddress(buffer, [])
                let base = CVPixelBufferGetBaseAddress(buffer)!
                for row in 0..<360 {
                    var color = 0xFF20_4000 | UInt32((row + progress.frame * 4) % 256)
                    memset_pattern4(base + row * CVPixelBufferGetBytesPerRow(buffer), &color, 640 * 4)
                }
                CVPixelBufferUnlockBaseAddress(buffer, [])
                pixels.append(buffer, withPresentationTime: CMTime(value: CMTimeValue(progress.frame), timescale: 24))
                progress.frame += 1
            }
        }
        finished.enter()
        audio.requestMediaDataWhenReady(on: DispatchQueue(label: "clip.audio")) {
            while audio.isReadyForMoreMediaData {
                guard progress.packet < 8 * 48_000 / 1024 else {
                    audio.markAsFinished()
                    finished.leave()
                    return
                }
                var block: CMBlockBuffer?
                CMBlockBufferCreateWithMemoryBlock(allocator: nil, memoryBlock: nil, blockLength: 8192,
                    blockAllocator: nil, customBlockSource: nil, offsetToData: 0, dataLength: 8192,
                    flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block)
                CMBlockBufferFillDataBytes(with: 0, blockBuffer: block!, offsetIntoDestination: 0, dataLength: 8192)
                var sample: CMSampleBuffer?
                CMAudioSampleBufferCreateReadyWithPacketDescriptions(allocator: nil, dataBuffer: block!,
                    formatDescription: pcm, sampleCount: 1024,
                    presentationTimeStamp: CMTime(value: CMTimeValue(progress.packet * 1024), timescale: 48_000),
                    packetDescriptions: nil, sampleBufferOut: &sample)
                audio.append(sample!)
                progress.packet += 1
            }
        }
        await withCheckedContinuation { continuation in
            finished.notify(queue: .global()) { continuation.resume() }
        }
        await writer.finishWriting()
        #expect(writer.status == .completed)
        let clip = directory.appendingPathComponent("audio-leading-\(UUID().uuidString).mkv")
        try FileManager.default.moveItem(at: mp4, to: clip)
        return clip
    }

    private func pixelData(_ pixel: CVPixelBuffer) -> Data {
        // Software-decoded frames are 32BGRA; compare only the visible bytes.
        #expect(CVPixelBufferGetPixelFormatType(pixel) == kCVPixelFormatType_32BGRA)
        CVPixelBufferLockBaseAddress(pixel, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixel, .readOnly) }
        guard let base = CVPixelBufferGetBaseAddress(pixel) else { return Data() }
        let rowBytes = CVPixelBufferGetWidth(pixel) * 4
        var data = Data()
        for row in 0..<CVPixelBufferGetHeight(pixel) {
            data.append(base.advanced(by: row * CVPixelBufferGetBytesPerRow(pixel))
                .assumingMemoryBound(to: UInt8.self), count: rowBytes)
        }
        return data
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

    @Test(arguments: [false, true])
    func replacedVideoDecoderResumesAtTheNextKeyframe(hardware: Bool) async throws {
        // A decoder replaced mid-GOP, as after iOS drops a hardware session,
        // has no reference frames. It must wait for the next keyframe (one a
        // second here), then decode what an uninterrupted pass does, still on
        // the same decoder type.
        let url = try await audioLeadingClip(keyFrameInterval: 24)
        defer { try? FileManager.default.removeItem(at: url) }
        func frames(replacingAfter replaceTime: Double?) throws -> [(time: Double, pixels: Data, format: OSType)] {
            let reader = EDFFmpegReader(hardwareDecoding: hardware)
            defer { reader.close() }
            try reader.open(url: url)
            var frames: [(time: Double, pixels: Data, format: OSType)] = []
            var replaced = replaceTime == nil
            while !reader.atEnd {
                let batch = try reader.readBatch()
                if replaced {
                    for frame in batch {
                        guard let pixel = frame.pixelBuffer else { continue }
                        frames.append((frame.presentationTime, planeData(pixel), CVPixelBufferGetPixelFormatType(pixel)))
                    }
                } else if let replaceTime,
                          batch.contains(where: { $0.pixelBuffer != nil && $0.presentationTime >= replaceTime }) {
                    #expect(reader.recreateVideoDecoder())
                    replaced = true
                }
            }
            return frames
        }
        let continuous = try frames(replacingAfter: nil)
        let replaced = try frames(replacingAfter: 1.4)
        let first = try #require(replaced.first)
        #expect(first.time > 1.4 && first.time < 2.001)
        let resumed = continuous.filter { $0.time > first.time - 0.00001 }
        #expect(replaced.map(\.time) == resumed.map(\.time))
        #expect(replaced.map(\.format) == resumed.map(\.format))
        #expect(replaced.map(\.pixels) == resumed.map(\.pixels))
    }

    @Test func reloadingVideoShowsThePausedFrameAgain() async throws {
        // Back from the background, the engine reloads video: a new decoder
        // shows the paused picture again, and playback goes on from there.
        let url = try await audioLeadingClip(keyFrameInterval: 24)
        defer { try? FileManager.default.removeItem(at: url) }
        let decoder = FFmpegDecoder()
        decoder.isMuted = true
        defer { decoder.close() }
        var times: [Double] = []
        decoder.onVideoFrame = { times.append($0.presentationTime.seconds) }
        _ = try await decoder.open(url: url)
        decoder.play()
        try await wait { decoder.currentTime.seconds > 1.3 }
        decoder.pause()
        let paused = decoder.currentTime.seconds
        times.removeAll()
        decoder.reloadVideo()
        try await wait { !times.isEmpty }
        #expect(decoder.state == .paused)
        let shown = try #require(times.first)
        #expect(shown > paused - 0.0001 && shown < paused + 1.0 / 24 + 0.0001)
        decoder.play()
        try await wait { decoder.currentTime.seconds > paused + 0.5 && times.count > 5 }
    }

    /// The visible bytes of every plane, for 32BGRA and 8-bit bi-planar frames.
    private func planeData(_ pixel: CVPixelBuffer) -> Data {
        CVPixelBufferLockBaseAddress(pixel, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixel, .readOnly) }
        let planar = CVPixelBufferIsPlanar(pixel)
        var data = Data()
        for plane in 0..<(planar ? CVPixelBufferGetPlaneCount(pixel) : 1) {
            guard let base = planar ? CVPixelBufferGetBaseAddressOfPlane(pixel, plane)
                                    : CVPixelBufferGetBaseAddress(pixel) else { continue }
            let width = planar ? CVPixelBufferGetWidthOfPlane(pixel, plane) : CVPixelBufferGetWidth(pixel)
            let height = planar ? CVPixelBufferGetHeightOfPlane(pixel, plane) : CVPixelBufferGetHeight(pixel)
            let stride = planar ? CVPixelBufferGetBytesPerRowOfPlane(pixel, plane) : CVPixelBufferGetBytesPerRow(pixel)
            // Luma is one byte per sample; chroma interleaves Cb and Cr.
            let bytesPerSample = planar ? (plane == 0 ? 1 : 2) : 4
            for row in 0..<height {
                data.append(base.advanced(by: row * stride).assumingMemoryBound(to: UInt8.self),
                            count: width * bytesPerSample)
            }
        }
        return data
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

    // MARK: - Surround audio

    /// A source layout, as a WAVE channel mask (which orders channels the way
    /// FFmpeg does), and the amplitude each Core Audio speaker should receive
    /// when source channel i carries a 440 Hz tone at 0.1 + 0.05 × i.
    nonisolated struct SurroundCase: Sendable, CustomTestStringConvertible {
        let testDescription: String
        let mask: UInt32
        let tag: AudioChannelLayoutTag
        let amplitudes: [AudioChannelLabel: Float]
    }

    nonisolated static let surroundCases: [SurroundCase] = [
        SurroundCase(testDescription: "mono", mask: 0x4, tag: kAudioChannelLayoutTag_Stereo,
            amplitudes: [kAudioChannelLabel_Left: 0.1 * Float(0.5).squareRoot(), kAudioChannelLabel_Right: 0.1 * Float(0.5).squareRoot()]),
        SurroundCase(testDescription: "stereo", mask: 0x3, tag: kAudioChannelLayoutTag_Stereo,
            amplitudes: [kAudioChannelLabel_Left: 0.1, kAudioChannelLabel_Right: 0.15]),
        SurroundCase(testDescription: "quad", mask: 0x33, tag: kAudioChannelLayoutTag_MPEG_5_1_A,
            amplitudes: [kAudioChannelLabel_Left: 0.1, kAudioChannelLabel_Right: 0.15, kAudioChannelLabel_Center: 0,
                         kAudioChannelLabel_LFEScreen: 0, kAudioChannelLabel_LeftSurround: 0.2,
                         kAudioChannelLabel_RightSurround: 0.25]),
        SurroundCase(testDescription: "5.1(side)", mask: 0x60F, tag: kAudioChannelLayoutTag_MPEG_5_1_A,
            amplitudes: [kAudioChannelLabel_Left: 0.1, kAudioChannelLabel_Right: 0.15, kAudioChannelLabel_Center: 0.2,
                         kAudioChannelLabel_LFEScreen: 0.25, kAudioChannelLabel_LeftSurround: 0.3,
                         kAudioChannelLabel_RightSurround: 0.35]),
        SurroundCase(testDescription: "5.1(back)", mask: 0x3F, tag: kAudioChannelLayoutTag_MPEG_5_1_A,
            amplitudes: [kAudioChannelLabel_Left: 0.1, kAudioChannelLabel_Right: 0.15, kAudioChannelLabel_Center: 0.2,
                         kAudioChannelLabel_LFEScreen: 0.25, kAudioChannelLabel_LeftSurround: 0.3,
                         kAudioChannelLabel_RightSurround: 0.35]),
        // The back center splits between the rear pair.
        SurroundCase(testDescription: "6.1", mask: 0x70F, tag: kAudioChannelLayoutTag_WAVE_7_1,
            amplitudes: [kAudioChannelLabel_Left: 0.1, kAudioChannelLabel_Right: 0.15, kAudioChannelLabel_Center: 0.2,
                         kAudioChannelLabel_LFEScreen: 0.25, kAudioChannelLabel_RearSurroundLeft: 0.3 * Float(0.5).squareRoot(),
                         kAudioChannelLabel_RearSurroundRight: 0.3 * Float(0.5).squareRoot(),
                         kAudioChannelLabel_LeftSurround: 0.35, kAudioChannelLabel_RightSurround: 0.4]),
        SurroundCase(testDescription: "7.1", mask: 0x63F, tag: kAudioChannelLayoutTag_WAVE_7_1,
            amplitudes: [kAudioChannelLabel_Left: 0.1, kAudioChannelLabel_Right: 0.15, kAudioChannelLabel_Center: 0.2,
                         kAudioChannelLabel_LFEScreen: 0.25, kAudioChannelLabel_RearSurroundLeft: 0.3,
                         kAudioChannelLabel_RearSurroundRight: 0.35, kAudioChannelLabel_LeftSurround: 0.4,
                         kAudioChannelLabel_RightSurround: 0.45]),
    ]

    @Test(arguments: FFmpegDecoderTests.surroundCases)
    func decodedAudioKeepsEveryChannelWhereItBelongs(_ surround: SurroundCase) throws {
        // The renderer places each channel by the layout the buffer names, so
        // every source channel must arrive under its own speaker's label.
        let url = try surroundWAV(mask: surround.mask)
        defer { try? FileManager.default.removeItem(at: url) }
        let reader = EDFFmpegReader(hardwareDecoding: false)
        defer { reader.close() }
        try reader.open(url: url)
        var buffers: [CMSampleBuffer] = []
        while !reader.atEnd { buffers += try reader.readBatch().compactMap(\.audioSampleBuffer) }
        let format = try #require(buffers.first.flatMap(CMSampleBufferGetFormatDescription))
        var layoutSize = 0
        let layout = try #require(CMAudioFormatDescriptionGetChannelLayout(format, sizeOut: &layoutSize))
        #expect(layout.pointee.mChannelLayoutTag == surround.tag)
        let speakers = try channelLabels(of: surround.tag)
        let samples = try interleavedSamples(buffers)
        #expect(samples.count == 24_000 * speakers.count)
        for (channel, speaker) in speakers.enumerated() {
            let expected = try #require(surround.amplitudes[speaker], "No level for speaker \(speaker)")
            let energy = stride(from: channel, to: samples.count, by: speakers.count).reduce(0.0) {
                $0 + Double(samples[$1] * samples[$1])
            }
            // A sine's amplitude is √2 × its RMS; 0.5 s holds whole cycles.
            let amplitude = Float((2 * energy / Double(samples.count / speakers.count)).squareRoot())
            #expect(abs(amplitude - expected) < 0.002, "Speaker \(speaker) played \(amplitude), expected \(expected)")
        }
    }

    @Test func eac3DecodesToSurroundWhenItDoesNotPassThrough() throws {
        let reader = EDFFmpegReader(hardwareDecoding: false)
        defer { reader.close() }
        try reader.open(url: fixture("decoder-eac3"))
        var buffers: [CMSampleBuffer] = []
        while !reader.atEnd { buffers += try reader.readBatch().compactMap(\.audioSampleBuffer) }
        let format = try #require(buffers.first.flatMap(CMSampleBufferGetFormatDescription))
        #expect(CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee.mFormatID == kAudioFormatLinearPCM)
        let speakers = try channelLabels(of: kAudioChannelLayoutTag_MPEG_5_1_A)
        let samples = try interleavedSamples(buffers)
        // The fixture plays 300, 400, 500, 60, 700, and 800 Hz on FL FR FC LFE SL SR.
        let tones: [AudioChannelLabel: Double] = [
            kAudioChannelLabel_Left: 300, kAudioChannelLabel_Right: 400, kAudioChannelLabel_Center: 500,
            kAudioChannelLabel_LFEScreen: 60, kAudioChannelLabel_LeftSurround: 700, kAudioChannelLabel_RightSurround: 800,
        ]
        for (channel, speaker) in speakers.enumerated() {
            let channelSamples = stride(from: channel, to: samples.count, by: 6).map { samples[$0] }
            // Half cycles, counted with hysteresis so coding noise near zero
            // doesn't add crossings. The tones peak at 0.125.
            var crossings = 0
            var positive: Bool?
            for sample in channelSamples where abs(sample) > 0.03 {
                if let positive, positive != (sample > 0) { crossings += 1 }
                positive = sample > 0
            }
            let frequency = Double(crossings) / 2 / (Double(channelSamples.count) / 48_000)
            let expected = try #require(tones[speaker])
            #expect(abs(frequency - expected) < expected * 0.05, "Speaker \(speaker) played \(frequency) Hz")
        }
    }

    nonisolated static let systemDecodesEAC3: Bool = {
        var size: UInt32 = 0
        guard AudioFormatGetPropertyInfo(kAudioFormatProperty_DecodeFormatIDs, 0, nil, &size) == noErr else { return false }
        var formats = [AudioFormatID](repeating: 0, count: Int(size) / MemoryLayout<AudioFormatID>.size)
        guard AudioFormatGetProperty(kAudioFormatProperty_DecodeFormatIDs, 0, nil, &size, &formats) == noErr else { return false }
        return formats.contains(kAudioFormatEnhancedAC3)
    }()

    @Test(.enabled(if: FFmpegDecoderTests.systemDecodesEAC3))
    func eac3ReachesTheRendererUndecodedWithTheSystemParsersConfiguration() throws {
        let reader = EDFFmpegReader(hardwareDecoding: false)
        reader.passesThroughEAC3 = true
        defer { reader.close() }
        try reader.open(url: fixture("decoder-eac3"))
        var units: [CMSampleBuffer] = []
        while !reader.atEnd { units += try reader.readBatch().compactMap(\.audioSampleBuffer) }
        #expect(units.count > 55)
        for (index, unit) in units.enumerated() {
            let format = try #require(CMSampleBufferGetFormatDescription(unit))
            let asbd = try #require(CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee)
            #expect(asbd.mFormatID == kAudioFormatEnhancedAC3)
            #expect(asbd.mFramesPerPacket == 1536 && asbd.mChannelsPerFrame == 6 && asbd.mSampleRate == 48_000)
            #expect(CMSampleBufferGetNumSamples(unit) == 1)
            // Each unit starts exactly where the one before it ends.
            #expect(CMSampleBufferGetPresentationTimeStamp(unit) == CMTime(value: CMTimeValue(index * 1536), timescale: 48_000))
        }
        // The dec3 box names the substreams and, when present, object audio.
        let format = try #require(units.first.flatMap(CMSampleBufferGetFormatDescription))
        var cookieSize = 0
        let cookie = try #require(CMAudioFormatDescriptionGetMagicCookie(format, sizeOut: &cookieSize))
        #expect(Data(bytes: cookie, count: cookieSize).range(of: Data("dec3".utf8)) != nil)
    }

    @Test(.enabled(if: FFmpegDecoderTests.systemDecodesEAC3), arguments: [(1.01, 1.024), (1.03, 1.056)])
    func passthroughResumesAtTheFirstWholeUnitAfterASeek(target: Double, resumesAt: Double) throws {
        // These seeks land at a unit 6–14 ms from the target. Its own time
        // stands; a unit that starts before the target is left out.
        let reader = EDFFmpegReader(hardwareDecoding: false)
        reader.passesThroughEAC3 = true
        defer { reader.close() }
        try reader.open(url: fixture("decoder-eac3"))
        _ = try reader.readBatch()
        try reader.seek(seconds: target)
        var units: [CMSampleBuffer] = []
        while !reader.atEnd { units += try reader.readBatch().compactMap(\.audioSampleBuffer) }
        let first = try #require(units.first)
        #expect(abs(CMSampleBufferGetPresentationTimeStamp(first).seconds - resumesAt) < 0.000_01)
    }

    @Test(arguments: [1.01, 1.03])
    func decodedAudioAfterASeekMatchesPlayingThrough(target: Double) throws {
        // The first frame after these seeks starts 6–14 ms from the target.
        // Audio must carry the samples of the times it is stamped with.
        func samples(seekingTo target: Double?) throws -> (start: Double, values: [Float]) {
            let reader = EDFFmpegReader(hardwareDecoding: false)
            defer { reader.close() }
            try reader.open(url: fixture("decoder-eac3"))
            if let target {
                _ = try reader.readBatch()
                try reader.seek(seconds: target)
            }
            var buffers: [CMSampleBuffer] = []
            while !reader.atEnd { buffers += try reader.readBatch().compactMap(\.audioSampleBuffer) }
            let start = try #require(buffers.first).presentationTimeStamp.seconds
            return (start, try interleavedSamples(buffers))
        }
        let continuous = try samples(seekingTo: nil)
        let seeked = try samples(seekingTo: target)
        #expect(seeked.start >= target - 0.000_01 && seeked.start < target + 0.032)
        // From 1.06 s on, past the decoder's warm-up after the seek. FFmpeg
        // dithers empty mantissas from a sequence that a seek doesn't reset,
        // so samples differ by up to about 1e-4; one sample of misplacement
        // would differ by about 1e-2.
        let compareFrom = 1.06, frames = 9_600
        func slice(_ audio: (start: Double, values: [Float])) -> ArraySlice<Float> {
            let offset = Int(((compareFrom - audio.start) * 48_000).rounded()) * 6
            return audio.values[offset..<(offset + frames * 6)]
        }
        #expect(zip(slice(seeked), slice(continuous)).allSatisfy { abs($0 - $1) < 0.001 })
    }

    @Test(.enabled(if: FFmpegDecoderTests.systemDecodesEAC3))
    func switchingTracksDecodesOtherAudioAndPassesEAC3ThroughAgain() throws {
        let reader = EDFFmpegReader(hardwareDecoding: false)
        reader.passesThroughEAC3 = true
        defer { reader.close() }
        try reader.open(url: fixture("decoder-eac3"))
        func formatOfNextAudio() throws -> AudioStreamBasicDescription? {
            for _ in 0..<100 {
                if let audio = try reader.readBatch().compactMap(\.audioSampleBuffer).first {
                    return CMSampleBufferGetFormatDescription(audio)
                        .flatMap(CMAudioFormatDescriptionGetStreamBasicDescription)?.pointee
                }
            }
            return nil
        }
        #expect(try formatOfNextAudio()?.mFormatID == kAudioFormatEnhancedAC3)
        try reader.selectAudioTrack(1)
        try reader.seek(seconds: 0.5)
        let ac3 = try #require(try formatOfNextAudio())
        #expect(ac3.mFormatID == kAudioFormatLinearPCM && ac3.mChannelsPerFrame == 2)
        try reader.selectAudioTrack(0)
        try reader.seek(seconds: 0.5)
        #expect(try formatOfNextAudio()?.mFormatID == kAudioFormatEnhancedAC3)
    }

    @Test(.enabled(if: FFmpegDecoderTests.systemDecodesEAC3))
    func passedThroughAudioPlaysOnTheRendererWithoutTheEqualizer() async throws {
        let decoder = FFmpegDecoder()
        decoder.isMuted = true
        let equalizer = AudioEQProcessor()
        equalizer.update(preamp: -4, bands: [6, 3, 0, 0, 0, 2, 3, 0, 0, 0])
        decoder.audioProcessor = equalizer
        defer { decoder.close() }
        _ = try await decoder.open(url: fixture("decoder-eac3"))
        decoder.play()
        try await wait { decoder.currentTime.seconds > 0.5 }
        let renderer = try #require(Mirror(reflecting: decoder).descendant("audioRenderer") as? AVSampleBufferAudioRenderer)
        #expect(renderer.status == .rendering)
        #expect(renderer.error == nil)
        // The equalizer never sees encoded audio.
        #expect(Mirror(reflecting: decoder).descendant("equalizedFormat") as? (sampleRate: Double, channels: Int) == nil)
        try await wait { decoder.state == .ended }
    }

    @Test func outputChangesRefillAudioOnceFromThePlayhead() async throws {
        let decoder = FFmpegDecoder()
        decoder.isMuted = true
        defer { decoder.close() }
        var discontinuities = 0
        decoder.onDiscontinuity = { discontinuities += 1 }
        _ = try await decoder.open(url: fixture())
        decoder.play()
        try await wait { decoder.currentTime.seconds > 0.5 }
        let renderer = try #require(Mirror(reflecting: decoder).descendant("audioRenderer") as? AVSampleBufferAudioRenderer)
        let playhead = decoder.currentTime.seconds
        // A route change can flush the renderer and change its output format at once.
        NotificationCenter.default.post(name: .AVSampleBufferAudioRendererWasFlushedAutomatically, object: renderer)
        NotificationCenter.default.post(name: .AVSampleBufferAudioRendererOutputConfigurationDidChange, object: renderer)
        try await wait { discontinuities == 1 && decoder.state == .playing }
        #expect(decoder.currentTime.seconds >= playhead - 0.05)
        try await wait { decoder.currentTime.seconds > playhead + 0.5 }
        #expect(discontinuities == 1)
    }

    @Test func equalizerFollowsTheDecodedChannels() async throws {
        let decoder = FFmpegDecoder(passesThroughEAC3: false)
        decoder.isMuted = true
        let equalizer = AudioEQProcessor()
        equalizer.update(preamp: -4, bands: [6, 3, 0, 0, 0, 2, 3, 0, 0, 0])
        decoder.audioProcessor = equalizer
        defer { decoder.close() }
        _ = try await decoder.open(url: fixture("decoder-eac3"))
        decoder.play()
        try await wait { decoder.currentTime.seconds > 0.3 }
        let format = Mirror(reflecting: decoder).descendant("equalizedFormat") as? (sampleRate: Double, channels: Int)
        #expect(format?.sampleRate == 48_000)
        #expect(format?.channels == 6)
    }

    /// Half a second of 48 kHz 16-bit WAVE_FORMAT_EXTENSIBLE audio whose
    /// channel i carries 440 Hz at amplitude 0.1 + 0.05 × i.
    private func surroundWAV(mask: UInt32) throws -> URL {
        let channels = mask.nonzeroBitCount, frames = 24_000
        var data = Data()
        func append<T: FixedWidthInteger>(_ value: T) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        data.append(contentsOf: Array("RIFF".utf8))
        append(UInt32(4 + 8 + 40 + 8 + frames * channels * 2))
        data.append(contentsOf: Array("WAVEfmt ".utf8))
        append(UInt32(40))
        append(UInt16(0xFFFE))
        append(UInt16(channels))
        append(UInt32(48_000))
        append(UInt32(48_000 * channels * 2))
        append(UInt16(channels * 2))
        append(UInt16(16))
        append(UInt16(22))
        append(UInt16(16))
        append(mask)
        // KSDATAFORMAT_SUBTYPE_PCM
        data.append(contentsOf: [0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00,
                                 0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71])
        data.append(contentsOf: Array("data".utf8))
        append(UInt32(frames * channels * 2))
        for frame in 0..<frames {
            let phase = sin(2 * Double.pi * 440 * Double(frame) / 48_000)
            for channel in 0..<channels {
                append(Int16((0.1 + 0.05 * Double(channel)) * phase * 32_767))
            }
        }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("surround-\(UUID().uuidString).wav")
        try data.write(to: url)
        return url
    }

    /// The speaker of each channel, in order, for a Core Audio layout tag.
    private func channelLabels(of tag: AudioChannelLayoutTag) throws -> [AudioChannelLabel] {
        var tag = tag
        var size: UInt32 = 0
        let tagSize = UInt32(MemoryLayout<AudioChannelLayoutTag>.size)
        try #require(AudioFormatGetPropertyInfo(kAudioFormatProperty_ChannelLayoutForTag, tagSize, &tag, &size) == noErr)
        let layout = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: MemoryLayout<AudioChannelLayout>.alignment)
        defer { layout.deallocate() }
        try #require(AudioFormatGetProperty(kAudioFormatProperty_ChannelLayoutForTag, tagSize, &tag, &size, layout) == noErr)
        let count = Int(layout.assumingMemoryBound(to: AudioChannelLayout.self).pointee.mNumberChannelDescriptions)
        let offset = try #require(MemoryLayout<AudioChannelLayout>.offset(of: \.mChannelDescriptions))
        let descriptions = (layout + offset).assumingMemoryBound(to: AudioChannelDescription.self)
        return (0..<count).map { descriptions[$0].mChannelLabel }
    }

    /// The interleaved float samples of consecutive PCM buffers.
    private func interleavedSamples(_ buffers: [CMSampleBuffer]) throws -> [Float] {
        var samples: [Float] = []
        for buffer in buffers {
            let block = try #require(CMSampleBufferGetDataBuffer(buffer))
            var values = [Float](repeating: 0, count: CMBlockBufferGetDataLength(block) / 4)
            let result = values.withUnsafeMutableBytes {
                CMBlockBufferCopyDataBytes(block, atOffset: 0, dataLength: $0.count, destination: $0.baseAddress!)
            }
            try #require(result == kCMBlockBufferNoErr)
            samples += values
        }
        return samples
    }
}
