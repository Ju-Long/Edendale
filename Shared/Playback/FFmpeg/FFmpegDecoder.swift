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
                    let seconds = values["duration"] as? Double ?? 0
                    continuation.resume(returning: MediaInfo(
                        duration: seconds > 0 ? CMTime(seconds: seconds, preferredTimescale: 600) : .indefinite,
                        videoTracks: videos, audioTracks: audio, subtitleTracks: [],
                        naturalSize: CGSize(width: values["width"] as? Double ?? 0, height: values["height"] as? Double ?? 0),
                        frameRate: values["frameRate"] as? Float ?? 0, isHDR: values["hdr"] as? Bool ?? false))
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    struct Batch: @unchecked Sendable {
        let frames: [EDFFmpegFrame]
        let atEnd: Bool
    }

    func read() async throws -> Batch {
        try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do { continuation.resume(returning: Batch(frames: try self.reader.readBatch(), atEnd: self.reader.atEnd)) }
                catch { continuation.resume(throwing: error) }
            }
        }
    }

    func seek(seconds: Double, audioTrack: Int?) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            queue.async {
                do {
                    if let audioTrack { try self.reader.selectAudioTrack(audioTrack) }
                    try self.reader.seek(seconds: seconds)
                    continuation.resume()
                } catch { continuation.resume(throwing: error) }
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
    public var currentTime: CMTime { synchronizer?.currentTime() ?? .zero }
    public private(set) var mediaInfo: MediaInfo?
    public var onStateChanged: (@MainActor (DecoderState) -> Void)?
    public var onTimeChanged: (@MainActor (CMTime) -> Void)?
    public var onVideoFrame: (@MainActor (DecodedVideoFrame) -> Void)?
    var onDiscontinuity: (() -> Void)?
    public var volume: Float = 1 { didSet { audioRenderer?.volume = min(max(volume, 0), 1) } }
    public var isMuted = false { didSet { audioRenderer?.isMuted = isMuted } }

    private let hardwareDecoding: Bool
    private var worker: FFmpegWorker?
    private var synchronizer: AVSampleBufferRenderSynchronizer?
    private var audioRenderer: AVSampleBufferAudioRenderer?
    private var displayLink: DisplayLinkDriver?
    private var pump: Task<Void, Never>?
    private var generation = 0
    private var videos: [DecodedVideoFrame] = []
    private var bufferedUntil = 0.0
    private var eof = false
    private var wantsToPlay = false
    private var clockRunning = false
    private var previewPending = true
    private var playbackRate: Float = 1

    public init(hardwareDecoding: Bool = true) {
        self.hardwareDecoding = hardwareDecoding
    }

    deinit {
        pump?.cancel()
        worker?.close()
        displayLink?.stop()
    }

    public func open(url: URL) async throws -> MediaInfo {
        close()
        let request = generation
        state = .opening
        let worker = FFmpegWorker(hardwareDecoding: hardwareDecoding)
        self.worker = worker
        do {
            let info = try await withTaskCancellationHandler {
                try await worker.open(url)
            } onCancel: {
                worker.interrupt()
            }
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
            }
            synchronizer = sync
            mediaInfo = info
            displayLink = DisplayLinkDriver { [weak self] in
                Task { @MainActor [weak self] in self?.tick() }
            }
            state = .ready
            startPump(worker, generation: request)
            return info
        } catch {
            worker.close()
            if generation == request { state = .error(error) }
            throw error
        }
    }

    public func play() {
        guard worker != nil, mediaInfo != nil else { return }
        if case .error = state { return }
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
    }

    public func pause() {
        wantsToPlay = false
        synchronizer?.rate = 0
        clockRunning = false
        displayLink?.stop()
        if mediaInfo != nil && state != .seeking { state = .paused }
        onTimeChanged?(currentTime)
    }

    public func seek(to time: CMTime) async throws {
        guard time.isValid, time.seconds.isFinite else { return }
        try await reposition(seconds: time.seconds, audioTrack: nil)
    }

    private func reposition(seconds: Double, audioTrack: Int?) async throws {
        guard let worker, let synchronizer else { return }
        generation += 1
        let request = generation
        pump?.cancel()
        worker.interrupt()
        synchronizer.rate = 0
        clockRunning = false
        state = .seeking
        videos.removeAll()
        audioRenderer?.flush()
        onDiscontinuity?()
        var target = max(seconds, 0)
        if let duration = mediaInfo?.duration.seconds, duration.isFinite { target = min(target, duration) }
        do {
            try await worker.seek(seconds: target, audioTrack: audioTrack)
            guard generation == request else { throw CancellationError() }
            synchronizer.setRate(0, time: CMTime(seconds: target, preferredTimescale: 600))
            bufferedUntil = target
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
        if clockRunning { synchronizer?.rate = playbackRate }
    }

    public func selectAudioTrack(_ index: Int) {
        guard mediaInfo?.audioTracks.contains(where: { $0.index == index }) == true else { return }
        let time = currentTime.seconds
        Task { [weak self] in try? await self?.reposition(seconds: time, audioTrack: index) }
    }

    // Embedded subtitle decoding/rendering is independent of the A/V pipeline.
    // Until connected, no nonfunctional embedded subtitle tracks are advertised.
    public func selectSubtitleTrack(_ index: Int?) { }

    public func close() {
        generation += 1
        pump?.cancel()
        pump = nil
        worker?.close()
        worker = nil
        displayLink?.stop()
        displayLink = nil
        synchronizer?.rate = 0
        audioRenderer?.flush()
        audioRenderer = nil
        synchronizer = nil
        videos.removeAll()
        mediaInfo = nil
        bufferedUntil = 0
        eof = false
        wantsToPlay = false
        clockRunning = false
        previewPending = true
        state = .idle
    }

    private func startPump(_ worker: FFmpegWorker, generation request: Int) {
        pump = Task { [weak self] in
            do {
                while !Task.isCancelled {
                    // Do not retain the decoder across the suspension points.
                    guard let shouldRead = self?.shouldRead(generation: request) else { return }
                    if !shouldRead {
                        try await Task.sleep(for: .milliseconds(20))
                        continue
                    }
                    let batch = try await worker.read()
                    guard !Task.isCancelled, self?.generation == request else { return }
                    for frame in batch.frames {
                        while frame.audioSampleBuffer != nil && self?.audioRenderer?.isReadyForMoreMediaData == false {
                            try await Task.sleep(for: .milliseconds(10))
                            guard self?.generation == request else { return }
                        }
                        guard !Task.isCancelled, self?.generation == request else { return }
                        self?.accept(frame)
                    }
                    self?.startClockIfReady()
                    if batch.atEnd {
                        self?.eof = true
                        self?.tick()
                        return
                    }
                }
            } catch {
                guard !Task.isCancelled, let self, self.generation == request else { return }
                self.synchronizer?.rate = 0
                self.clockRunning = false
                self.displayLink?.stop()
                self.state = .error(error)
            }
        }
    }

    private func shouldRead(generation request: Int) -> Bool? {
        guard generation == request else { return nil }
        return !eof && videos.count < 12 && bufferedUntil < currentTime.seconds + 0.5
    }

    private func accept(_ frame: EDFFmpegFrame) {
        bufferedUntil = max(bufferedUntil, frame.presentationTime + frame.duration)
        if let sample = frame.audioSampleBuffer { audioRenderer?.enqueue(sample) }
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

    private func startClockIfReady() {
        guard wantsToPlay, state != .seeking, !clockRunning,
              bufferedUntil > currentTime.seconds else { return }
        synchronizer?.rate = playbackRate
        clockRunning = true
    }

    private func tick() {
        let time = currentTime
        var latest: DecodedVideoFrame?
        while let first = videos.first, first.presentationTime <= time {
            latest = videos.removeFirst()
        }
        if let latest { onVideoFrame?(latest) }
        onTimeChanged?(time)
        if eof && wantsToPlay && videos.isEmpty && time.seconds >= bufferedUntil - 0.005 {
            synchronizer?.setRate(0, time: CMTime(seconds: bufferedUntil, preferredTimescale: 60000))
            clockRunning = false
            wantsToPlay = false
            displayLink?.stop()
            onTimeChanged?(currentTime)
            state = .ended
        }
    }
}
