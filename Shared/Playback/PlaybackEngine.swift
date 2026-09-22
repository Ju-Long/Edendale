//
//  PlaybackEngine.swift
//  Edendale
//
//  Observable playback bridge wrapping `MediaDecoder` for the player UI layer.
//  Views bind to this instead of the decoder directly, providing transport
//  state, track information, volume/mute, and a `FrameRingBuffer` for the
//  Metal rendering surface.
//

import AVFoundation
import CoreMedia
import Foundation
import Observation

#if canImport(AppKit)
import AppKit
#elseif canImport(UIKit)
import UIKit
#endif

// MARK: - Playback state

/// Transport state exposed to views.
enum PlaybackState: Equatable {
    case idle
    case stopped
    case opening
    case buffering
    case playing
    case paused
    case error
}

// MARK: - Track type

/// Unified track descriptor for video, audio, and subtitle tracks.
struct PlaybackTrack: Identifiable, Equatable {
    let id: String
    let name: String
    let language: String?
    var isSelected: Bool
    let width: Int?
    let height: Int?
    let channels: Int?
    /// The decoder-level index for selection calls.
    let trackIndex: Int

    enum TrackType: Equatable { case video, audio, subtitle }
    let type: TrackType
}

// MARK: - Engine

@MainActor
@Observable
final class PlaybackEngine {

    // MARK: - Observable transport state

    private(set) var state: PlaybackState = .idle

    /// The playback clock in `Duration`.
    private(set) var currentTime: Duration = .zero
    /// Total duration of the loaded media.
    private(set) var duration: Duration? {
        didSet {
            #if os(iOS) || os(macOS)
            if duration != oldValue { pipSource.invalidatePlaybackState() }
            #endif
        }
    }
    /// Whether the decoder is actively outputting.
    private(set) var isPlaying: Bool = false {
        didSet {
            #if os(iOS) || os(macOS)
            if isPlaying != oldValue { pipSource.invalidatePlaybackState() }
            #endif
        }
    }
    private(set) var playbackRate: Float = 1
    /// Whether the loaded media supports seeking.
    private(set) var isSeekable: Bool = true
    private(set) var videoPresentationTime: CMTime = .invalid

    /// The source content's native frame rate (e.g. 24, 30, 60).
    var sourceFrameRate: Float { decoder?.mediaInfo?.frameRate ?? 0 }

    /// Normalised position (0 ... 1).  Settable — the setter seeks.
    var position: Double {
        get {
            guard let d = duration, d > .zero else { return 0 }
            return min(max(currentTime.playbackSeconds / d.playbackSeconds, 0), 1)
        }
        set {
            guard let d = duration, d > .zero else { return }
            let clamped = min(max(newValue, 0), 1)
            currentTime = .seconds(d.playbackSeconds * clamped)
            onTimeChanged?(currentTime)
            let target = CMTime(
                seconds: d.playbackSeconds * clamped,
                preferredTimescale: 600
            )
            Task { [weak self] in try? await self?.decoder?.seek(to: target) }
        }
    }

    // MARK: - Audio

    var isMuted: Bool = false {
        didSet { applyAudioSettings() }
    }
    var volume: Float = 1.0 {
        didSet {
            #if !os(macOS)
            guard !isUpdatingFromSystemVolume else { return }
            systemVolume.setLevel(volume)
            #endif
            applyAudioSettings()
        }
    }

    #if !os(macOS)
    let systemVolume = SystemVolumeController()
    private var isUpdatingFromSystemVolume = false
    #endif

    // MARK: - Track arrays

    private(set) var videoTracks: [PlaybackTrack] = []
    private(set) var audioTracks: [PlaybackTrack] = []
    private(set) var subtitleTracks: [PlaybackTrack] = []

    /// Settable audio track selection.
    var selectedAudioTrack: PlaybackTrack? {
        get { audioTracks.first(where: \.isSelected) }
        set {
            for i in audioTracks.indices { audioTracks[i].isSelected = false }
            guard let track = newValue else { return }
            if let idx = audioTracks.firstIndex(where: { $0.id == track.id }) {
                audioTracks[idx].isSelected = true
            }
            decoder?.selectAudioTrack(track.trackIndex)
        }
    }

    /// Settable subtitle track selection — nil turns subtitles off.
    var selectedSubtitleTrack: PlaybackTrack? {
        get { subtitleTracks.first(where: \.isSelected) }
        set {
            guard newValue?.id != selectedSubtitleTrack?.id else { return }
            for i in subtitleTracks.indices { subtitleTracks[i].isSelected = false }
            guard let track = newValue else {
                decoder?.selectSubtitleTrack(nil)
                subtitleEngine.reset()
                subtitleEngine.selectFormat(nil)
                return
            }
            if let idx = subtitleTracks.firstIndex(where: { $0.id == track.id }) {
                subtitleTracks[idx].isSelected = true
            }
            if track.id.hasPrefix("ext-") {
                decoder?.selectSubtitleTrack(nil)
                activateExternalSubtitle(id: track.id)
            } else if decoder is FFmpegDecoder {
                subtitleEngine.reset()
                subtitleEngine.selectFormat(nil)
                decoder?.selectSubtitleTrack(track.trackIndex)
            } else {
                subtitleEngine.reset()
                subtitleEngine.selectFormat(.srt)
                decoder?.selectSubtitleTrack(track.trackIndex)
            }
        }
    }

    // MARK: - Pipeline components

    private(set) var decoder: (any MediaDecoder)?
    private var openGeneration = 0
    let ringBuffer = FrameRingBuffer()
    let enhancementPipeline: EnhancementPipeline?
    let frameInterpolator: FrameInterpolator?
    let subtitleEngine = SubtitleEngine()
    private var externalSubtitleData: [String: (format: SubtitleTrackFormat, content: String)] = [:]

    #if os(iOS) || os(macOS)
    let pipSource = SampleBufferPiPSource()
    #endif

    // MARK: - Audio EQ

    private var audioProcessor: AudioEQProcessor?

    func installAudioProcessor(_ processor: AudioEQProcessor?) {
        audioProcessor = processor
        if let avDecoder = decoder as? AVFoundationDecoder {
            let changed = avDecoder.audioProcessor !== processor
            avDecoder.audioProcessor = processor
            if changed { avDecoder.installAudioTap() }
        } else if let ffmpeg = decoder as? FFmpegDecoder {
            ffmpeg.audioProcessor = processor
        }
    }

    // MARK: - Session callbacks

    /// Fired when the decoder reaches the end of the media naturally.
    var onEnded: (() -> Void)?
    /// Fired on every periodic time tick from the decoder.
    var onTimeChanged: ((Duration) -> Void)?
    var onSystemVolumeChanged: ((Float) -> Void)?
    var onPictureInPictureStarted: (() -> Void)?
    var onPictureInPictureStopped: (() -> Void)?

    // MARK: - App Lifecycle & PiP Coordination

    private var isAppBackgrounded = false
    private nonisolated(unsafe) var lifecycleObservers: [NSObjectProtocol] = []

    // MARK: - Init

    init() {
        let pipeline = EnhancementPipeline()
        self.enhancementPipeline = pipeline
        if let device = pipeline?.device {
            self.frameInterpolator = FrameInterpolator(
                device: device,
                library: MetalShaderSource.library(for: device)
            )
        } else {
            self.frameInterpolator = nil
        }
        #if !os(macOS)
        volume = systemVolume.level
        #endif
        #if os(iOS) || os(macOS)
        pipSource.attach(to: self)
        #endif
        setupLifecycleObservers()
        #if !os(macOS)
        systemVolume.onLevelChanged = { [weak self] newLevel in
            guard let self else { return }
            self.isUpdatingFromSystemVolume = true
            self.volume = newLevel
            self.isUpdatingFromSystemVolume = false
            self.onSystemVolumeChanged?(newLevel)
        }
        #endif
    }

    deinit {
        for obs in lifecycleObservers {
            NotificationCenter.default.removeObserver(obs)
        }
        lifecycleObservers.removeAll()
    }

    private func setupLifecycleObservers() {
        #if os(macOS)
        let hideObs = NotificationCenter.default.addObserver(
            forName: NSApplication.didHideNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleAppBackgroundChanged(isBackgrounded: true)
        }
        let unhideObs = NotificationCenter.default.addObserver(
            forName: NSApplication.didUnhideNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleAppBackgroundChanged(isBackgrounded: false)
        }
        lifecycleObservers.append(contentsOf: [hideObs, unhideObs])
        #elseif os(iOS) || os(tvOS)
        let bgObs = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleAppBackgroundChanged(isBackgrounded: true)
        }
        let fgObs = NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleAppBackgroundChanged(isBackgrounded: false)
        }
        lifecycleObservers.append(contentsOf: [bgObs, fgObs])
        #endif

        #if os(iOS) || os(macOS)
        pipSource.onWillStart = { [weak self] in
            self?.updateVideoDecodingState()
        }
        pipSource.onDidStart = { [weak self] in
            self?.updateVideoDecodingState()
            self?.onPictureInPictureStarted?()
        }
        pipSource.onDidStop = { [weak self] in
            self?.updateVideoDecodingState()
            self?.onPictureInPictureStopped?()
        }
        #endif
    }

    private func handleAppBackgroundChanged(isBackgrounded: Bool) {
        let wasBackgrounded = self.isAppBackgrounded
        self.isAppBackgrounded = isBackgrounded
        updateVideoDecodingState()

        #if os(iOS) || os(tvOS)
        if wasBackgrounded && !isBackgrounded {
            #if os(iOS)
            let pipActive = pipSource.isActive
            #else
            let pipActive = false
            #endif
            if !pipActive, decoder is FFmpegDecoder {
                let seconds = currentTime.playbackSeconds
                Task { [weak self] in
                    guard let self else { return }
                    try? await self.decoder?.seek(
                        to: CMTime(seconds: seconds, preferredTimescale: 600)
                    )
                }
            }
        }
        #endif
    }

    private func updateVideoDecodingState() {
        #if os(iOS)
        let pipMayAutoStart = pipSource.automaticallyStartsFromInline
            && pipSource.isPossible
        let shouldDecodeVideo = !isAppBackgrounded
            || pipSource.isActive
            || pipMayAutoStart
        debugPrint("[PlaybackEngine] updateVideoDecodingState — bg=\(isAppBackgrounded), pipActive=\(pipSource.isActive), autoStart=\(pipSource.automaticallyStartsFromInline), pipPossible=\(pipSource.isPossible), pipMayAuto=\(pipMayAutoStart) → shouldDecode=\(shouldDecodeVideo)")
        #elseif os(macOS)
        let shouldDecodeVideo = !isAppBackgrounded || pipSource.isActive
        #elseif os(tvOS)
        let shouldDecodeVideo = !isAppBackgrounded
        #else
        let shouldDecodeVideo = true
        #endif
        decoder?.setVideoDecodingEnabled(shouldDecodeVideo)
    }

    // MARK: - Lifecycle

    /// Routes the URL through `FormatRouter`, creates the appropriate decoder,
    /// opens the media, and populates track information.
    func open(url: URL) async throws {
        debugPrint("[PlaybackEngine.open] called — url=\(url.lastPathComponent)")
        close()
        let request = openGeneration
        state = .opening

        let kind = await FormatRouter.route(url)
        debugPrint("[PlaybackEngine.open] FormatRouter → \(kind)")
        try Task.checkCancellation()
        guard openGeneration == request else { throw CancellationError() }
        let newDecoder: any MediaDecoder
        switch kind {
        case .avFoundation:
            debugPrint("[PlaybackEngine.open] creating AVFoundationDecoder")
            newDecoder = AVFoundationDecoder()
        case .ffmpeg:
            debugPrint("[PlaybackEngine.open] creating FFmpegDecoder")
            newDecoder = FFmpegDecoder()
        }

        decoder = newDecoder
        wireCallbacks(newDecoder)
        updateVideoDecodingState()

        debugPrint("[PlaybackEngine.open] calling decoder.open(url:)...")
        let info = try await newDecoder.open(url: url)
        debugPrint("[PlaybackEngine.open] ✅ decoder.open succeeded — duration=\(info.duration.seconds)s, videoTracks=\(info.videoTracks.count), audioTracks=\(info.audioTracks.count), naturalSize=\(info.naturalSize)")
        try Task.checkCancellation()
        guard openGeneration == request else { throw CancellationError() }
        populateTracks(from: info)

        if info.duration.isValid, info.duration.seconds.isFinite, info.duration.seconds > 0 {
            duration = .seconds(info.duration.seconds)
        }

        applyAudioSettings()
        state = .buffering
        debugPrint("[PlaybackEngine.open] ✅ done — state=\(state)")
    }

    func play() {
        debugPrint("[PlaybackEngine.play] called — decoder=\(decoder == nil ? "nil" : String(describing: type(of: decoder!)))")
        decoder?.play()
        isPlaying = true
        state = .playing
    }

    func pause() {
        decoder?.pause()
        isPlaying = false
        state = .paused
    }

    func togglePlayPause() {
        if isPlaying { pause() } else { play() }
    }

    func seek(by offset: Duration) {
        guard let decoder else { return }
        let targetSeconds = max(currentTime.playbackSeconds + offset.playbackSeconds, 0)
        let capped: Double
        if let d = duration {
            capped = min(targetSeconds, d.playbackSeconds)
        } else {
            capped = targetSeconds
        }
        currentTime = .seconds(capped)
        onTimeChanged?(currentTime)
        let target = CMTime(seconds: capped, preferredTimescale: 600)
        Task { try? await decoder.seek(to: target) }
    }

    func seek(to time: Duration) {
        guard let decoder else { return }
        currentTime = time
        onTimeChanged?(currentTime)
        let target = CMTime(seconds: time.playbackSeconds, preferredTimescale: 600)
        Task { try? await decoder.seek(to: target) }
    }

    func setRate(_ rate: Float) {
        guard rate.isFinite, rate > 0 else { return }
        playbackRate = min(max(rate, 0.25), 4)
        decoder?.setRate(rate)
        #if os(iOS) || os(macOS)
        pipSource.synchronizePlaybackClock()
        #endif
    }

    func stop() {
        decoder?.close()
        state = .stopped
        isPlaying = false
    }

    func close() {
        openGeneration += 1
        decoder?.close()
        decoder = nil
        ringBuffer.clear()
        videoPresentationTime = .invalid
        enhancementPipeline?.reset()
        frameInterpolator?.reset()
        subtitleEngine.reset()
        subtitleEngine.selectFormat(nil)
        externalSubtitleData.removeAll()
        #if os(iOS) || os(macOS)
        pipSource.detach()
        pipSource.attach(to: self)
        #endif
        state = .idle
        isPlaying = false
        currentTime = .zero
        duration = nil
        playbackRate = 1
        videoTracks = []
        audioTracks = []
        subtitleTracks = []
    }

    // MARK: - External subtitles

    func addExternalTrack(from url: URL, type: ExternalTrackType = .subtitle, select: Bool = true) throws {
        guard type == .subtitle else { return }

        let ext = url.pathExtension.lowercased()
        guard let format = Self.subtitleFormat(for: ext) else {
            throw ExternalSubtitleError.unsupportedFormat(ext)
        }

        let data = try Data(contentsOf: url)
        let content: String
        if data.starts(with: [0xFF, 0xFE]) || data.starts(with: [0xFE, 0xFF]),
           let decoded = String(data: data, encoding: .utf16) {
            content = decoded
        } else if let decoded = String(data: data, encoding: .utf8)
                    ?? String(data: data, encoding: .isoLatin1) {
            content = decoded
        } else {
            throw ExternalSubtitleError.unreadableText
        }

        let index = subtitleTracks.count
        let trackID = "ext-\(index)"

        externalSubtitleData[trackID] = (format: format, content: content)

        let track = PlaybackTrack(
            id: trackID,
            name: url.deletingPathExtension().lastPathComponent,
            language: nil,
            isSelected: false,
            width: nil,
            height: nil,
            channels: nil,
            trackIndex: -1,
            type: .subtitle
        )
        subtitleTracks.append(track)

        if select {
            selectedSubtitleTrack = track
        }
    }

    enum ExternalTrackType { case subtitle, audio }

    enum ExternalSubtitleError: LocalizedError {
        case unsupportedFormat(String)
        case unreadableText

        var errorDescription: String? {
            switch self {
            case .unsupportedFormat(let ext):
                return "Unsupported subtitle format: .\(ext)"
            case .unreadableText:
                return String(localized: "The subtitle file could not be read as text.")
            }
        }
    }

    private static func subtitleFormat(for ext: String) -> SubtitleTrackFormat? {
        switch ext {
        case "srt": return .srt
        case "vtt", "webvtt": return .webvtt
        case "ass": return .ass
        case "ssa": return .ssa
        default: return nil
        }
    }

    private func activateExternalSubtitle(id: String) {
        guard let data = externalSubtitleData[id] else { return }
        subtitleEngine.reset()
        subtitleEngine.selectFormat(data.format)
        switch data.format {
        case .ass, .ssa:
            subtitleEngine.addAssScriptData(Data(data.content.utf8))
        case .srt, .webvtt:
            subtitleEngine.loadTimedText(from: data.content)
        case .pgs, .vobsub:
            break
        }
    }

    // MARK: - Video-track selection
    func selectVideoTrack(_ track: PlaybackTrack) {
        for i in videoTracks.indices { videoTracks[i].isSelected = false }
        if let idx = videoTracks.firstIndex(where: { $0.id == track.id }) {
            videoTracks[idx].isSelected = true
        }
    }

    // MARK: - Private — callback wiring

    private func wireCallbacks(_ decoder: any MediaDecoder) {
        decoder.onStateChanged = { [weak self, weak decoder] decoderState in
            guard let self, self.decoder === decoder else { return }
            self.handleDecoderState(decoderState)
        }
        decoder.onTimeChanged = { [weak self, weak decoder] cmTime in
            guard let self, self.decoder === decoder else { return }
            self.handleTimeChanged(cmTime)
        }
        let receiveFrame: @MainActor (DecodedVideoFrame) -> Void = { [weak self, weak decoder] frame in
                guard let self, self.decoder === decoder else { return }
                self.ringBuffer.push(frame)
                self.videoPresentationTime = frame.presentationTime
                #if os(iOS) || os(macOS)
                self.pipSource.enqueue(
                    pixelBuffer: frame.pixelBuffer,
                    presentationTime: frame.presentationTime,
                    duration: frame.duration
                )
                #endif
        }
        if let avDecoder = decoder as? AVFoundationDecoder {
            avDecoder.onVideoFrame = receiveFrame
            avDecoder.onSubtitleEvent = { [weak self, weak avDecoder] event in
                guard let self, self.decoder === avDecoder,
                      let track = self.selectedSubtitleTrack, !track.id.hasPrefix("ext-") else { return }
                // AVFoundation sends the complete currently visible text, then
                // nil to clear it. Neither event may overwrite a downloaded track.
                self.subtitleEngine.reset()
                if let event {
                    self.subtitleEngine.addEvent(event)
                }
            }
        } else if let ffmpeg = decoder as? FFmpegDecoder {
            ffmpeg.onVideoFrame = receiveFrame
            ffmpeg.onSubtitleConfiguration = { [weak self, weak ffmpeg] format, header in
                guard let self, self.decoder === ffmpeg,
                      let track = self.selectedSubtitleTrack, !track.id.hasPrefix("ext-") else { return }
                self.subtitleEngine.reset()
                self.subtitleEngine.selectFormat(format)
                if format == .ass || format == .ssa { self.subtitleEngine.addAssScriptData(header) }
            }
            ffmpeg.onSubtitleEvent = { [weak self, weak ffmpeg] event in
                guard let self, self.decoder === ffmpeg,
                      let track = self.selectedSubtitleTrack, !track.id.hasPrefix("ext-") else { return }
                self.subtitleEngine.addEvent(event)
            }
            ffmpeg.onImageSubtitleCue = { [weak self, weak ffmpeg] cue in
                guard let self, self.decoder === ffmpeg,
                      self.selectedSubtitleTrack?.trackIndex == cue.trackIndex else { return }
                self.subtitleEngine.addImageCue(cue)
            }
            ffmpeg.onDiscontinuity = { [weak self] in
                self?.ringBuffer.clear()
                self?.videoPresentationTime = .invalid
                self?.enhancementPipeline?.reset()
                self?.frameInterpolator?.reset()
                #if os(iOS) || os(macOS)
                self?.pipSource.displayLayer.sampleBufferRenderer.flush()
                #endif
            }
        }
    }

    private func handleDecoderState(_ decoderState: DecoderState) {
        debugPrint("[PlaybackEngine] decoder state changed → \(decoderState)")
        switch decoderState {
        case .idle:
            state = .idle
            isPlaying = false
        case .opening:
            state = .opening
        case .ready:
            state = .buffering
        case .playing:
            state = .playing
            isPlaying = true
        case .paused:
            state = .paused
            isPlaying = false
        case .seeking:
            break
        case .ended:
            state = .stopped
            isPlaying = false
            onEnded?()
        case .error:
            state = .error
            isPlaying = false
        }
    }

    private func handleTimeChanged(_ cmTime: CMTime) {
        guard cmTime.isValid, cmTime.seconds.isFinite else { return }
        currentTime = .seconds(cmTime.seconds)
        #if os(iOS) || os(macOS)
        pipSource.synchronizePlaybackClock()
        #endif

        if let info = decoder?.mediaInfo,
           info.duration.isValid, info.duration.seconds.isFinite, info.duration.seconds > 0 {
            let d = Duration.seconds(info.duration.seconds)
            if d != duration { duration = d }
        }

        onTimeChanged?(currentTime)
    }

    // MARK: - Private — audio

    private func applyAudioSettings() {
        #if os(macOS)
        if let avDecoder = decoder as? AVFoundationDecoder {
            avDecoder.avPlayer?.isMuted = isMuted
            avDecoder.avPlayer?.volume = volume
        } else if let ffmpeg = decoder as? FFmpegDecoder {
            ffmpeg.isMuted = isMuted
            ffmpeg.volume = volume
        }
        #else
        if let avDecoder = decoder as? AVFoundationDecoder {
            avDecoder.avPlayer?.isMuted = isMuted
            avDecoder.avPlayer?.volume = 1.0
        } else if let ffmpeg = decoder as? FFmpegDecoder {
            ffmpeg.isMuted = isMuted
            ffmpeg.volume = 1.0
        }
        #endif
    }

    // MARK: - Private — tracks

    private func populateTracks(from info: MediaInfo) {
        videoTracks = info.videoTracks.enumerated().map { idx, track in
            let name: String
            if track.size.width > 0, track.size.height > 0 {
                name = "\(track.codec.uppercased()) \(Int(track.size.width))×\(Int(track.size.height))"
            } else {
                name = track.codec.uppercased()
            }
            return PlaybackTrack(
                id: "v\(track.index)",
                name: name,
                language: nil,
                isSelected: idx == 0,
                width: Int(track.size.width),
                height: Int(track.size.height),
                channels: nil,
                trackIndex: track.index,
                type: .video
            )
        }
        audioTracks = info.audioTracks.enumerated().map { index, track in
            PlaybackTrack(
                id: "a\(track.index)",
                name: track.title ?? track.language ?? "Track \(track.index + 1)",
                language: track.language,
                isSelected: index == 0,
                width: nil,
                height: nil,
                channels: track.channelCount,
                trackIndex: track.index,
                type: .audio
            )
        }
        subtitleTracks = info.subtitleTracks.map { track in
            PlaybackTrack(
                id: "s\(track.index)",
                name: track.title ?? track.language ?? "Track \(track.index + 1)",
                language: track.language,
                isSelected: false,
                width: nil,
                height: nil,
                channels: nil,
                trackIndex: track.index,
                type: .subtitle
            )
        }
    }
}
