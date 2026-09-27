import AVFoundation
import CoreMedia
import Foundation

/// Serializes all FFmpeg access away from the main actor. Only interrupt may run
/// concurrently with a read, allowing close/seek to cancel blocked network I/O.
private nonisolated final class FFmpegWorker: @unchecked Sendable {
    private let queue = DispatchQueue(label: "Edendale.FFmpeg", qos: .userInitiated)
    private let reader: EDFFmpegReader

    init(hardwareDecoding: Bool) {
        reader = EDFFmpegReader(hardwareDecoding: hardwareDecoding)
    }

    func open(_ url: URL) async throws -> MediaInfo {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    try self.reader.open(url: url)
                    let values = self.reader.mediaInfo
                    let videos = (values["video"] as? [[String: Any]] ?? []).map { track in
                        VideoTrackInfo(index: track["index"] as? Int ?? 0,
                            codec: track["codec"] as? String ?? "unknown",
                            size: CGSize(width: track["width"] as? Double ?? 0, height: track["height"] as? Double ?? 0),
                            bitDepth: track["bitDepth"] as? Int ?? 8,
                            isHardwareDecodable: track["hardware"] as? Bool ?? false)
                    }
                    let audio = (values["audio"] as? [[String: Any]] ?? []).map { track in
                        AudioTrackInfo(index: track["index"] as? Int ?? 0,
                            codec: track["codec"] as? String ?? "unknown",
                            channelCount: track["channels"] as? Int ?? 0,
                            sampleRate: track["sampleRate"] as? Int ?? 0,
                            language: track["language"] as? String, title: track["title"] as? String)
                    }
                    let subs = (values["subtitle"] as? [[String: Any]] ?? []).map { track in
                        SubtitleTrackInfo(
                            index: track["index"] as? Int ?? 0,
                            codec: track["codec"] as? String ?? "unknown",
                            language: track["language"] as? String,
                            title: track["title"] as? String,
                            isImageBased: track["isImageBased"] as? Bool ?? false)
                    }
                    let seconds = values["duration"] as? Double ?? 0
                    continuation.resume(returning: MediaInfo(
                        duration: seconds > 0 ? CMTime(seconds: seconds, preferredTimescale: 600) : .indefinite,
                        videoTracks: videos, audioTracks: audio, subtitleTracks: subs,
                        naturalSize: CGSize(width: values["width"] as? Double ?? 0, height: values["height"] as? Double ?? 0),
                        frameRate: values["frameRate"] as? Float ?? 0, isHDR: values["hdr"] as? Bool ?? false))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    struct Batch: @unchecked Sendable {
        let frames: [EDFFmpegFrame]
        let atEnd: Bool
        let blockedOnVideo: Bool
    }

    func read(decodingVideo: Bool) async throws -> Batch {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    let frames = try self.reader.readBatch(decodingVideo: decodingVideo)
                    continuation.resume(returning: Batch(frames: frames, atEnd: self.reader.atEnd,
                        blockedOnVideo: self.reader.blockedOnVideo))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    func seek(seconds: Double, audioTrack: Int?, subtitleTrack: Int?,
              resetVideoDecoder: Bool) async throws -> [String: Any] {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    if resetVideoDecoder { _ = self.reader.recreateVideoDecoder() }
                    if let audioTrack { try self.reader.selectAudioTrack(audioTrack) }
                    let configuration = try self.reader.selectSubtitleTrack(subtitleTrack ?? -1)
                    try self.reader.seek(seconds: seconds)
                    continuation.resume(returning: configuration)
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    func setVideoDecodingEnabled(_ enabled: Bool) {
        queue.async {
            self.reader.videoDecodingEnabled = enabled
            if enabled {
                _ = self.reader.recreateVideoDecoder()
            }
        }
    }

    func interrupt() { reader.interrupt() }

    func close() {
        reader.interrupt()
        queue.async { self.reader.close() }
    }
}

/// Demuxes and decodes using FFmpeg, renders PCM with AVFoundation, and presents
/// video to Metal against the same audio-backed media clock. Read-ahead is bounded
/// so a long movie is streamed, never decoded or converted in its entirety.
@MainActor
public final class FFmpegDecoder: MediaDecoder {
    public private(set) var state: DecoderState = .idle {
        didSet { if state != oldValue { onStateChanged?(state) } }
    }
    public var currentTime: CMTime {
        if usingWallClock {
            guard clockRunning else {
                return CMTime(seconds: wallClockOffset, preferredTimescale: 600)
            }
            let elapsed = CACurrentMediaTime() - wallClockStart
            return CMTime(seconds: wallClockOffset + elapsed * Double(playbackRate), preferredTimescale: 600)
        }
        return synchronizer?.currentTime() ?? .zero
    }
    public private(set) var mediaInfo: MediaInfo?
    public var onStateChanged: (@MainActor (DecoderState) -> Void)?
    public var onTimeChanged: (@MainActor (CMTime) -> Void)?
    public var onVideoFrame: (@MainActor (DecodedVideoFrame) -> Void)?
    var onDiscontinuity: (() -> Void)?
    var onSubtitleConfiguration: ((SubtitleTrackFormat?, Data) -> Void)?
    var onSubtitleEvent: ((DecodedSubtitleEvent) -> Void)?
    var onImageSubtitleCue: ((ImageSubtitleCue) -> Void)?
    private var selectedSubtitleIndex: Int?
    private var subtitleSelectionGeneration = 0
    public var volume: Float = 1 { didSet { audioRenderer?.volume = min(max(volume, 0), 1) } }
    public var isMuted = false { didSet { audioRenderer?.isMuted = isMuted } }
    var audioProcessor: AudioEQProcessor?

    private let hardwareDecoding: Bool
    private var worker: FFmpegWorker?
    private var synchronizer: AVSampleBufferRenderSynchronizer?
    private var audioRenderer: AVSampleBufferAudioRenderer?
    private var displayLink: DisplayLinkDriver?
    private var pump: Task<Void, Never>?
    private var generation = 0
    private var videos: [DecodedVideoFrame] = []
    /// Decoded audio the renderer has no room for yet. The demuxer can deliver
    /// audio a second or more ahead of the video it is interleaved with, so the
    /// pump parks it here and keeps decoding instead of waiting for the renderer.
    private var pendingAudio: [CMSampleBuffer] = []
    /// The reader holds undecoded video it cannot read past until video decodes.
    private var readerBlockedOnVideo = false
    private var bufferedUntil = 0.0
    /// End of the decoded audio, including `pendingAudio`.
    private var audioBufferedUntil = 0.0
    private var eof = false
    private var wantsToPlay = false
    private var clockRunning = false
    private var previewPending = true
    private var playbackRate: Float = 1

    // Wall-clock fallback for video-only files where the synchronizer has no renderer
    private var usingWallClock = false
    private var wallClockStart: CFTimeInterval = 0
    private var wallClockOffset: Double = 0

    /// Decoded audio never runs further ahead of the clock than this (about
    /// 0.4 MB/s of 48 kHz stereo PCM); reading pauses there instead.
    private static let maxAudioAhead = 4.0

    public init(hardwareDecoding: Bool = true) {
        self.hardwareDecoding = hardwareDecoding
    }

    deinit {
        pump?.cancel()
        worker?.close()
        displayLink?.stop()
    }

    public func open(url: URL) async throws -> MediaInfo {
        debugPrint("[FFmpegDecoder.open] called — url=\(url.lastPathComponent), hwDecoding=\(hardwareDecoding)")
        close()
        let request = generation
        state = .opening
        let worker = FFmpegWorker(hardwareDecoding: hardwareDecoding)
        if !isVideoDecodingEnabled {
            worker.setVideoDecodingEnabled(false)
        }
        self.worker = worker
        do {
            debugPrint("[FFmpegDecoder.open] calling worker.open...")
            let info = try await withTaskCancellationHandler {
                try await worker.open(url)
            } onCancel: {
                worker.interrupt()
            }
            debugPrint("[FFmpegDecoder.open] ✅ worker.open succeeded — duration=\(info.duration.seconds)s, video=\(info.videoTracks.count), audio=\(info.audioTracks.count)")
            try Task.checkCancellation()
            guard generation == request else { throw CancellationError() }
            let sync = AVSampleBufferRenderSynchronizer()
            sync.delaysRateChangeUntilHasSufficientMediaData = false
            sync.setRate(0, time: .zero)
            if !info.audioTracks.isEmpty {
                let renderer = AVSampleBufferAudioRenderer()
                renderer.volume = volume
                renderer.isMuted = isMuted
                sync.addRenderer(renderer)
                audioRenderer = renderer
                usingWallClock = false
                if let firstAudio = info.audioTracks.first {
                    audioProcessor?.configure(
                        sampleRate: Double(firstAudio.sampleRate),
                        channelCount: firstAudio.channelCount
                    )
                }
                debugPrint("[FFmpegDecoder.open] audio renderer attached, wallClock=false")
            } else {
                usingWallClock = true
                debugPrint("[FFmpegDecoder.open] no audio tracks, wallClock=true")
            }
            synchronizer = sync
            mediaInfo = info
            displayLink = DisplayLinkDriver { [weak self] in
                Task { @MainActor [weak self] in self?.tick() }
            }
            state = .ready
            debugPrint("[FFmpegDecoder.open] ✅ state=ready, starting pump")
            startPump(worker, generation: request)
            return info
        } catch {
            debugPrint("[FFmpegDecoder.open] ❌ ERROR: \(error)")
            worker.close()
            if generation == request { state = .error(error) }
            throw error
        }
    }

    public func play() {
        debugPrint("[FFmpegDecoder.play] called — worker=\(worker == nil ? "nil" : "exists"), mediaInfo=\(mediaInfo == nil ? "nil" : "exists"), state=\(state)")
        guard worker != nil, mediaInfo != nil else {
            debugPrint("[FFmpegDecoder.play] ❌ guard failed — no worker or mediaInfo")
            return
        }
        if case .error = state {
            debugPrint("[FFmpegDecoder.play] ❌ in error state, not playing")
            return
        }
        if state == .ended {
            Task { [weak self] in
                guard let self else { return }
                do { try await self.seek(to: .zero); self.play() } catch { }
            }
            return
        }
        wantsToPlay = true
        if state != .seeking { state = .playing }
        displayLink?.start()
        startClockIfReady()
        debugPrint("[FFmpegDecoder.play] ✅ wantsToPlay=true, clockRunning=\(clockRunning)")
    }

    public func pause() {
        wantsToPlay = false
        if usingWallClock {
            if clockRunning {
                wallClockOffset = currentTime.seconds
            }
        } else {
            synchronizer?.rate = 0
        }
        clockRunning = false
        displayLink?.stop()
        if mediaInfo != nil && state != .seeking { state = .paused }
        onTimeChanged?(currentTime)
    }

    public func seek(to time: CMTime) async throws {
        guard time.isValid, time.seconds.isFinite else { return }
        try await reposition(seconds: time.seconds, audioTrack: nil)
    }

    /// Refills video at the current position with a new decoder. iOS
    /// invalidates hardware decoder sessions while the app is in the
    /// background, so the old decoder fails the first frames after it returns.
    public func reloadVideo() {
        guard mediaInfo?.videoTracks.isEmpty == false else { return }
        let time = currentTime.seconds
        Task { [weak self] in
            try? await self?.reposition(seconds: time, audioTrack: nil, resetVideoDecoder: true)
        }
    }

    private func reposition(seconds: Double, audioTrack: Int?, resetVideoDecoder: Bool = false) async throws {
        guard let worker, let synchronizer else { return }
        generation += 1
        let request = generation
        pump?.cancel()
        worker.interrupt()
        if !usingWallClock {
            synchronizer.rate = 0
        }
        clockRunning = false
        state = .seeking
        videos.removeAll()
        pendingAudio.removeAll()
        readerBlockedOnVideo = false
        audioRenderer?.flush()
        onDiscontinuity?()
        var target = max(seconds, 0)
        if let duration = mediaInfo?.duration.seconds, duration.isFinite { target = min(target, duration) }
        do {
            let configuration = try await worker.seek(seconds: target, audioTrack: audioTrack,
                subtitleTrack: selectedSubtitleIndex, resetVideoDecoder: resetVideoDecoder)
            guard generation == request else { throw CancellationError() }
            let format = (configuration["format"] as? String).flatMap(SubtitleTrackFormat.init(rawValue:))
            onSubtitleConfiguration?(format, configuration["header"] as? Data ?? Data())
            synchronizer.setRate(0, time: CMTime(seconds: target, preferredTimescale: 60000))
            if usingWallClock {
                wallClockOffset = target
            }
            bufferedUntil = target
            audioBufferedUntil = target
            eof = false
            previewPending = true
            onTimeChanged?(currentTime)
            state = wantsToPlay ? .playing : .paused
            if wantsToPlay { displayLink?.start() }
            startPump(worker, generation: request)
        } catch {
            if generation == request { state = .error(error) }
            throw error
        }
    }

    public func setRate(_ rate: Float) {
        guard rate.isFinite, rate > 0 else { return }
        playbackRate = min(max(rate, 0.25), 4)
        if clockRunning {
            if usingWallClock {
                wallClockOffset = currentTime.seconds
                wallClockStart = CACurrentMediaTime()
            } else {
                synchronizer?.rate = playbackRate
            }
        }
    }

    public func selectAudioTrack(_ index: Int) {
        guard mediaInfo?.audioTracks.contains(where: { $0.index == index }) == true else { return }
        let time = currentTime.seconds
        Task { [weak self] in try? await self?.reposition(seconds: time, audioTrack: index) }
    }

    public func selectSubtitleTrack(_ index: Int?) {
        guard index == nil || mediaInfo?.subtitleTracks.contains(where: { $0.index == index }) == true else { return }
        guard selectedSubtitleIndex != index else { return }
        selectedSubtitleIndex = index
        subtitleSelectionGeneration += 1
        let selection = subtitleSelectionGeneration
        Task { [weak self] in
            guard let self, self.subtitleSelectionGeneration == selection else { return }
            // A bounded seek refills packets at the current position. Never scan
            // to EOF or leave the pump cancelled while loading a whole track.
            try? await self.reposition(seconds: self.currentTime.seconds, audioTrack: nil)
        }
    }

    public private(set) var isVideoDecodingEnabled: Bool = true

    public func setVideoDecodingEnabled(_ enabled: Bool) {
        guard isVideoDecodingEnabled != enabled else { return }
        isVideoDecodingEnabled = enabled
        debugPrint("[FFmpegDecoder] setVideoDecodingEnabled → \(enabled)")
        worker?.setVideoDecodingEnabled(enabled)
        if !enabled {
            videos.removeAll()
            // The reader drops held video once decoding is off.
            readerBlockedOnVideo = false
        } else {
            previewPending = true
            if wantsToPlay {
                displayLink?.start()
            }
        }
    }

    public func close() {
        subtitleSelectionGeneration += 1
        selectedSubtitleIndex = nil
        generation += 1
        pump?.cancel()
        pump = nil
        worker?.close()
        worker = nil
        displayLink?.stop()
        displayLink = nil
        if !usingWallClock {
            synchronizer?.rate = 0
        }
        audioRenderer?.flush()
        audioRenderer = nil
        synchronizer = nil
        videos.removeAll()
        pendingAudio.removeAll()
        readerBlockedOnVideo = false
        mediaInfo = nil
        bufferedUntil = 0
        audioBufferedUntil = 0
        eof = false
        wantsToPlay = false
        clockRunning = false
        previewPending = true
        playbackRate = 1
        usingWallClock = false
        wallClockStart = 0
        wallClockOffset = 0
        state = .idle
    }

    private func startPump(_ worker: FFmpegWorker, generation request: Int) {
        pump = Task { [weak self] in
            do {
                while !Task.isCancelled {
                    self?.feedAudioRenderer()
                    guard let shouldRead = self?.shouldRead(generation: request) else { return }
                    if !shouldRead {
                        // Reading may pause before a video frame decodes; the
                        // clock then starts without one (see startClockIfReady).
                        self?.startClockIfReady()
                        try await Task.sleep(for: .milliseconds(20))
                        continue
                    }
                    // Video decodes only when the queue needs frames. A read for
                    // audio alone leaves the video packets it passes undecoded.
                    guard let decodeVideo = self?.videoNeedsData else { return }
                    let batch = try await worker.read(decodingVideo: decodeVideo)
                    guard !Task.isCancelled, self?.generation == request else { return }
                    self?.readerBlockedOnVideo = batch.blockedOnVideo
                    for frame in batch.frames {
                        guard self?.generation == request else { return }
                        self?.accept(frame)
                    }
                    if batch.atEnd { self?.eof = true }
                    self?.startClockIfReady()
                    if batch.atEnd {
                        self?.tick()
                        return
                    }
                }
            } catch {
                guard !Task.isCancelled, let self, self.generation == request else { return }
                if !self.usingWallClock {
                    self.synchronizer?.rate = 0
                }
                self.clockRunning = false
                self.displayLink?.stop()
                self.state = .error(error)
            }
        }
    }

    private func shouldRead(generation request: Int) -> Bool? {
        guard generation == request else { return nil }
        guard !eof else { return false }
        if readerBlockedOnVideo && !videoNeedsData { return false }
        let audioAhead = audioBufferedUntil - currentTime.seconds
        if !usingWallClock && audioAhead >= Self.maxAudioAhead { return false }
        // Audio asks for more only while the renderer has room; video reads on
        // past it, parking the audio decoded on the way in `pendingAudio`.
        let audioNeedsData = !usingWallClock && pendingAudio.isEmpty && audioAhead < 1.0
        return audioNeedsData || videoNeedsData
    }

    private var videoNeedsData: Bool { isVideoDecodingEnabled && videos.count < 12 }

    private func accept(_ frame: EDFFmpegFrame) {
        if let subtitle = frame.subtitle {
            guard let index = subtitle["trackIndex"] as? Int, index == selectedSubtitleIndex else { return }
            let start = CMTime(seconds: frame.presentationTime, preferredTimescale: 60000)
            let end = CMTime(seconds: frame.presentationTime + frame.duration, preferredTimescale: 60000)
            for text in subtitle["texts"] as? [String] ?? [] {
                onSubtitleEvent?(DecodedSubtitleEvent(text: text, start: start, end: end))
            }
            let rects = (subtitle["rects"] as? [[String: Any]] ?? []).compactMap { rect -> ImageSubtitleRect? in
                guard let x = rect["x"] as? Int, let y = rect["y"] as? Int,
                      let width = rect["width"] as? Int, let height = rect["height"] as? Int,
                      let data = rect["data"] as? Data else { return nil }
                return ImageSubtitleRect(x: x, y: y, width: width, height: height, data: data)
            }
            if !rects.isEmpty {
                let size = CGSize(width: subtitle["width"] as? Int ?? 0, height: subtitle["height"] as? Int ?? 0)
                onImageSubtitleCue?(ImageSubtitleCue(start: start, end: end, rects: rects,
                    canvasSize: size.width > 0 && size.height > 0 ? size : mediaInfo?.naturalSize ?? .zero,
                    trackIndex: index))
            }
            return
        }
        bufferedUntil = max(bufferedUntil, frame.presentationTime + frame.duration)
        if let sample = frame.audioSampleBuffer {
            audioBufferedUntil = max(audioBufferedUntil, frame.presentationTime + frame.duration)
            pendingAudio.append(sample)
            feedAudioRenderer()
        }
        if let pixel = frame.pixelBuffer {
            let video = DecodedVideoFrame(pixelBuffer: pixel,
                presentationTime: CMTime(seconds: frame.presentationTime, preferredTimescale: 60000),
                duration: CMTime(seconds: frame.duration, preferredTimescale: 60000))
            if previewPending {
                onVideoFrame?(video)
                previewPending = false
            } else {
                videos.append(video)
            }
        }
    }

    /// Hands pending audio to the renderer while it has room. The pump and the
    /// display link poll this, because during playback the renderer's
    /// requestMediaDataWhenReady callback fires only after it has run dry.
    private func feedAudioRenderer() {
        guard let renderer = audioRenderer else {
            pendingAudio.removeAll()
            return
        }
        var fed = 0
        while fed < pendingAudio.count && renderer.isReadyForMoreMediaData {
            // Equalize at hand-off, so settings changes are heard no later than before.
            audioProcessor?.processSampleBuffer(pendingAudio[fed])
            renderer.enqueue(pendingAudio[fed])
            fed += 1
        }
        pendingAudio.removeFirst(fed)
    }

    private func startClockIfReady() {
        guard wantsToPlay, state != .seeking, !clockRunning,
              bufferedUntil > currentTime.seconds else { return }
        // Start picture and sound together: wait for the first video frame at
        // this position, unless reading has stopped before it could decode.
        if previewPending && isVideoDecodingEnabled && !eof && mediaInfo?.videoTracks.isEmpty == false
            && (usingWallClock || audioBufferedUntil - currentTime.seconds < Self.maxAudioAhead) {
            return
        }
        if usingWallClock {
            wallClockStart = CACurrentMediaTime()
        } else {
            synchronizer?.rate = playbackRate
        }
        clockRunning = true
    }

    private func tick() {
        feedAudioRenderer()
        let time = currentTime
        var latest: DecodedVideoFrame?
        while let first = videos.first, first.presentationTime <= time {
            latest = videos.removeFirst()
        }
        if let latest { onVideoFrame?(latest) }
        onTimeChanged?(time)
        if eof && wantsToPlay && videos.isEmpty && time.seconds >= bufferedUntil - 0.005 {
            if usingWallClock {
                wallClockOffset = bufferedUntil
            } else {
                synchronizer?.setRate(0, time: CMTime(seconds: bufferedUntil, preferredTimescale: 60000))
            }
            clockRunning = false
            wantsToPlay = false
            displayLink?.stop()
            onTimeChanged?(currentTime)
            state = .ended
        }
    }
}
