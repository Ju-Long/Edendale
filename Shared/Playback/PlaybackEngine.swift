//
//  PlaybackEngine.swift
//  Edendale
//
//  Observable playback bridge wrapping `MediaDecoder` for the player UI layer.
//  Views bind to this instead of the raw VLC `Player`, providing transport
//  state, track information, volume/mute, and a `FrameRingBuffer` for the
//  Metal rendering surface.
//

import AVFoundation
import CoreMedia
import Foundation
import Observation

// MARK: - Playback state

/// Transport state exposed to views. Maps from `DecoderState` to names the
/// player chrome checks by identity comparison.
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

/// Unified track descriptor replacing SwiftVLC's `Track`.
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

    /// The playback clock in `Duration` — same unit the VLC `Player` used.
    private(set) var currentTime: Duration = .zero
    /// Total duration of the loaded media.
    private(set) var duration: Duration?
    /// Whether the decoder is actively outputting.
    private(set) var isPlaying: Bool = false
    /// Whether the loaded media supports seeking.
    private(set) var isSeekable: Bool = true

    /// Normalised position (0 ... 1).  Settable — the setter seeks.
    var position: Double {
        get {
            guard let d = duration, d > .zero else { return 0 }
            return min(max(currentTime.playbackSeconds / d.playbackSeconds, 0), 1)
        }
        set {
            guard let d = duration, d > .zero else { return }
            let clamped = min(max(newValue, 0), 1)
            let target = CMTime(
                seconds: d.playbackSeconds * clamped,
                preferredTimescale: 600
            )
            Task { [weak self] in try? await self?.decoder?.seek(to: target) }
        }
    }

    // MARK: - Audio (applied to the underlying AVPlayer)

    var isMuted: Bool = false {
        didSet { applyAudioSettings() }
    }
    var volume: Float = 1.0 {
        didSet { applyAudioSettings() }
    }

    // MARK: - Track arrays

    private(set) var videoTracks: [PlaybackTrack] = []
    private(set) var audioTracks: [PlaybackTrack] = []
    private(set) var subtitleTracks: [PlaybackTrack] = []

    /// Settable audio track selection — mirrors SwiftVLC's pattern.
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
            for i in subtitleTracks.indices { subtitleTracks[i].isSelected = false }
            guard let track = newValue else {
                decoder?.selectSubtitleTrack(nil)
                return
            }
            if let idx = subtitleTracks.firstIndex(where: { $0.id == track.id }) {
                subtitleTracks[idx].isSelected = true
            }
            decoder?.selectSubtitleTrack(track.trackIndex)
        }
    }

    // MARK: - Pipeline components

    private(set) var decoder: (any MediaDecoder)?
    let ringBuffer = FrameRingBuffer()
    let enhancementPipeline: EnhancementPipeline?

    #if os(iOS) || os(macOS)
    let pipSource = SampleBufferPiPSource()
    #endif

    // MARK: - Session callbacks

    /// Fired when the decoder reaches the end of the media naturally.
    var onEnded: (() -> Void)?
    /// Fired on every periodic time tick from the decoder.
    var onTimeChanged: ((Duration) -> Void)?

    // MARK: - Init

    init() {
        self.enhancementPipeline = EnhancementPipeline()
        #if os(iOS) || os(macOS)
        pipSource.attach(to: self)
        #endif
    }

    // MARK: - Lifecycle

    /// Routes the URL through `FormatRouter`, creates the appropriate decoder,
    /// opens the media, and populates track information.
    func open(url: URL) async throws {
        close()
        state = .opening

        let kind = await FormatRouter.route(url)
        let newDecoder: any MediaDecoder
        switch kind {
        case .avFoundation:
            newDecoder = AVFoundationDecoder()
        case .ffmpeg:
            newDecoder = FFmpegDecoder()
        }

        decoder = newDecoder
        wireCallbacks(newDecoder)

        let info = try await newDecoder.open(url: url)
        populateTracks(from: info)

        if info.duration.isValid, info.duration.seconds.isFinite, info.duration.seconds > 0 {
            duration = .seconds(info.duration.seconds)
        }

        applyAudioSettings()
        state = .buffering
    }

    func play() {
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
        let target = CMTime(seconds: capped, preferredTimescale: 600)
        Task { try? await decoder.seek(to: target) }
    }

    func seek(to time: Duration) {
        guard let decoder else { return }
        let target = CMTime(seconds: time.playbackSeconds, preferredTimescale: 600)
        Task { try? await decoder.seek(to: target) }
    }

    func setRate(_ rate: Float) {
        decoder?.setRate(rate)
    }

    func stop() {
        decoder?.close()
        state = .stopped
        isPlaying = false
    }

    func close() {
        decoder?.close()
        decoder = nil
        ringBuffer.clear()
        enhancementPipeline?.reset()
        #if os(iOS) || os(macOS)
        pipSource.detach()
        pipSource.attach(to: self)
        #endif
        state = .idle
        isPlaying = false
        currentTime = .zero
        duration = nil
        videoTracks = []
        audioTracks = []
        subtitleTracks = []
    }

    // MARK: - External subtitles (stub — VLC addExternalTrack replacement)

    /// Placeholder for loading an external subtitle file. With the new
    /// pipeline, subtitle rendering goes through `SubtitleEngine`; this
    /// method exists so the `OnlineSubtitlesModel` download flow compiles.
    func addExternalTrack(from url: URL, type: ExternalTrackType = .subtitle, select: Bool = true) throws {
        // The AVFoundation path does not support adding external tracks to a
        // live AVPlayerItem the way VLC does.  When FFmpeg or the subtitle
        // engine is wired up, this will load the file through the appropriate
        // decoder path.  For now, append a placeholder subtitle track.
        let index = subtitleTracks.count
        let track = PlaybackTrack(
            id: "ext-\(index)",
            name: url.deletingPathExtension().lastPathComponent,
            language: nil,
            isSelected: select,
            width: nil,
            height: nil,
            channels: nil,
            trackIndex: index,
            type: .subtitle
        )
        if select {
            for i in subtitleTracks.indices { subtitleTracks[i].isSelected = false }
        }
        subtitleTracks.append(track)
    }

    enum ExternalTrackType { case subtitle, audio }

    // MARK: - Video-track selection (settings panel workaround)

    /// The settings panel uses `selectedAudioTrack = videoTrack` as a hack
    /// to route video track selection through libVLC.  This provides a
    /// cleaner path.
    func selectVideoTrack(_ track: PlaybackTrack) {
        for i in videoTracks.indices { videoTracks[i].isSelected = false }
        if let idx = videoTracks.firstIndex(where: { $0.id == track.id }) {
            videoTracks[idx].isSelected = true
        }
    }

    // MARK: - Private — callback wiring

    private func wireCallbacks(_ decoder: any MediaDecoder) {
        decoder.onStateChanged = { [weak self] decoderState in
            self?.handleDecoderState(decoderState)
        }
        decoder.onTimeChanged = { [weak self] cmTime in
            self?.handleTimeChanged(cmTime)
        }
        if let avDecoder = decoder as? AVFoundationDecoder {
            avDecoder.onVideoFrame = { [weak self] frame in
                guard let self else { return }
                self.ringBuffer.push(frame)
                #if os(iOS) || os(macOS)
                self.pipSource.enqueue(
                    pixelBuffer: frame.pixelBuffer,
                    presentationTime: frame.presentationTime,
                    duration: frame.duration
                )
                #endif
            }
        }
    }

    private func handleDecoderState(_ decoderState: DecoderState) {
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

        if let info = decoder?.mediaInfo,
           info.duration.isValid, info.duration.seconds.isFinite, info.duration.seconds > 0 {
            let d = Duration.seconds(info.duration.seconds)
            if d != duration { duration = d }
        }

        onTimeChanged?(currentTime)
    }

    // MARK: - Private — audio

    private func applyAudioSettings() {
        guard let avDecoder = decoder as? AVFoundationDecoder else { return }
        avDecoder.avPlayer?.isMuted = isMuted
        avDecoder.avPlayer?.volume = volume
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
        audioTracks = info.audioTracks.map { track in
            PlaybackTrack(
                id: "a\(track.index)",
                name: track.title ?? track.language ?? "Track \(track.index + 1)",
                language: track.language,
                isSelected: track.index == 0,
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
