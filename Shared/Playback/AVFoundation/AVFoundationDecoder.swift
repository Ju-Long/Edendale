//
//  AVFoundationDecoder.swift
//  Edendale
//
//  AVPlayerItemVideoOutput-backed frame decoder conforming to MediaDecoder.
//

import AVFoundation
import CoreMedia
import CoreVideo
import Foundation
import MediaToolbox
import QuartzCore
import VideoToolbox

#if os(macOS)
import AppKit
#endif
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Pixel Format Configuration

public enum PixelFormatPreference: Sendable {
    /// Allows the system to negotiate hardware YUV formats (420v/420f/10-bit) with BGRA fallback.
    case automatic
    /// Requests 8-bit YUV 4:2:0 bi-planar video range.
    case yuv420
    /// Requests 32-bit BGRA output.
    case bgra
}

// MARK: - Errors

public enum AVFoundationDecoderError: LocalizedError {
    case unplayableAsset
    case trackLoadingFailed(String)
    case playbackFailed(Error?)

    public var errorDescription: String? {
        switch self {
        case .unplayableAsset:
            return "The media asset is not playable by AVFoundation."
        case .trackLoadingFailed(let reason):
            return "Failed to load media tracks: \(reason)"
        case .playbackFailed(let error):
            return error?.localizedDescription ?? "Playback failed."
        }
    }
}

// MARK: - Display Link Driver

final class DisplayLinkDriver: NSObject, @unchecked Sendable {
    private let onTick: @Sendable () -> Void
    private var isRunning = false

    #if os(macOS)
    private var caDisplayLink: CADisplayLink?
    private var fallbackTimer: DispatchSourceTimer?
    #else
    private var caDisplayLink: CADisplayLink?
    private var backgroundTimer: DispatchSourceTimer?
    private nonisolated(unsafe) var backgroundObservers: [NSObjectProtocol] = []
    #endif

    init(onTick: @escaping @Sendable () -> Void) {
        self.onTick = onTick
        super.init()
    }

    func start() {
        guard !isRunning else { return }
        isRunning = true

        #if os(macOS)
        let screen = NSScreen.main ?? NSScreen.screens.first
        if let link = screen?.displayLink(target: self, selector: #selector(handleTick)) {
            link.add(to: .main, forMode: .common)
            self.caDisplayLink = link
        } else {
            let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
            timer.schedule(deadline: .now(), repeating: .milliseconds(16))
            timer.setEventHandler { [weak self] in
                self?.handleTick()
            }
            timer.resume()
            self.fallbackTimer = timer
        }
        #else
        let link = CADisplayLink(target: self, selector: #selector(handleTick))
        link.add(to: .main, forMode: .common)
        self.caDisplayLink = link
        setupBackgroundHandling()
        #endif
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false
        caDisplayLink?.invalidate()
        caDisplayLink = nil
        #if os(macOS)
        fallbackTimer?.cancel()
        fallbackTimer = nil
        #else
        stopBackgroundTimer()
        for obs in backgroundObservers {
            NotificationCenter.default.removeObserver(obs)
        }
        backgroundObservers.removeAll()
        #endif
    }

    #if !os(macOS)
    private func setupBackgroundHandling() {
        guard backgroundObservers.isEmpty else { return }
        let bgObs = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleBackgroundTransition(isBackground: true)
        }
        let fgObs = NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleBackgroundTransition(isBackground: false)
        }
        backgroundObservers = [bgObs, fgObs]
    }

    private func handleBackgroundTransition(isBackground: Bool) {
        guard isRunning else { return }
        if isBackground {
            startBackgroundTimer()
        } else {
            stopBackgroundTimer()
        }
    }

    private func startBackgroundTimer() {
        guard backgroundTimer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.main)
        timer.schedule(deadline: .now(), repeating: .milliseconds(16))
        timer.setEventHandler { [weak self] in
            self?.handleTick()
        }
        timer.resume()
        backgroundTimer = timer
    }

    private func stopBackgroundTimer() {
        backgroundTimer?.cancel()
        backgroundTimer = nil
    }
    #endif

    @objc private func handleTick() {
        guard isRunning else { return }
        onTick()
    }

    deinit {
        stop()
    }
}

// MARK: - AVFoundationDecoder

@MainActor
public final class AVFoundationDecoder: NSObject, MediaDecoder {

    // MARK: - MediaDecoder Properties

    public private(set) var state: DecoderState = .idle {
        didSet {
            if state != oldValue {
                onStateChanged?(state)
            }
        }
    }

    public var currentTime: CMTime {
        player?.currentTime() ?? .zero
    }

    public private(set) var mediaInfo: MediaInfo?

    public var onStateChanged: (@MainActor (DecoderState) -> Void)?
    public var onTimeChanged: (@MainActor (CMTime) -> Void)?
    public var onVideoFrame: (@MainActor (DecodedVideoFrame) -> Void)?
    public var onSubtitleEvent: (@MainActor (DecodedSubtitleEvent?) -> Void)?

    public let pixelFormatPreference: PixelFormatPreference

    /// Exposes the underlying AVPlayer for volume/mute control by
    /// `PlaybackEngine`. Read-only outside this class.
    public var avPlayer: AVPlayer? { player }

    // MARK: - Internal AVFoundation Components

    private var player: AVPlayer?
    private var playerItem: AVPlayerItem?
    private var videoOutput: AVPlayerItemVideoOutput?
    private var legibleOutput: AVPlayerItemLegibleOutput?
    private var displayLinkDriver: DisplayLinkDriver?

    private var timeObserverToken: Any?
    private var itemStatusObservation: NSKeyValueObservation?
    private var timeControlStatusObservation: NSKeyValueObservation?
    private var didPlayToEndObserver: NSObjectProtocol?
    private var failedToPlayToEndObserver: NSObjectProtocol?

    private var currentVideoFormatDescription: CMFormatDescription?
    private var nominalFrameDuration: CMTime = CMTime(value: 1, timescale: 60)
    private var currentPlaybackRate: Float = 1.0

    private var isExplicitlyPaused: Bool = false
    private var audibleSelectionGroup: AVMediaSelectionGroup?
    private var legibleSelectionGroup: AVMediaSelectionGroup?

    var audioProcessor: AudioEQProcessor?

    // MARK: - Initialization & Cleanup

    public init(pixelFormatPreference: PixelFormatPreference = .automatic) {
        self.pixelFormatPreference = pixelFormatPreference
        super.init()

        self.displayLinkDriver = DisplayLinkDriver { [weak self] in
            Task { @MainActor [weak self] in
                self?.handleDisplayLinkTick()
            }
        }
    }

    deinit {
        displayLinkDriver?.stop()
    }

    // MARK: - MediaDecoder Protocol Methods

    public func open(url: URL) async throws -> MediaInfo {
        debugPrint("[AVFoundationDecoder.open] called — url=\(url.lastPathComponent)")
        close()
        state = .opening

        let asset = AVURLAsset(
            url: url,
            options: [AVURLAssetPreferPreciseDurationAndTimingKey: true]
        )

        let isPlayable = (try? await asset.load(.isPlayable)) ?? false
        debugPrint("[AVFoundationDecoder.open] isPlayable=\(isPlayable)")
        guard isPlayable else {
            debugPrint("[AVFoundationDecoder.open] ❌ asset not playable")
            let error = AVFoundationDecoderError.unplayableAsset
            state = .error(error)
            throw error
        }

        do {
            debugPrint("[AVFoundationDecoder.open] extracting media info...")
            let info = try await extractMediaInfo(from: asset)
            debugPrint("[AVFoundationDecoder.open] ✅ info: duration=\(info.duration.seconds)s, video=\(info.videoTracks.count), audio=\(info.audioTracks.count), size=\(info.naturalSize), fps=\(info.frameRate)")
            self.mediaInfo = info

            if info.frameRate > 0 {
                let timescale = CMTimeScale(round(info.frameRate * 1000))
                self.nominalFrameDuration = CMTime(value: 1000, timescale: max(timescale, 1000))
            } else {
                self.nominalFrameDuration = CMTime(value: 1, timescale: 60)
            }

            let item = AVPlayerItem(asset: asset)
            let output = makeVideoOutput()
            item.add(output)
            output.setDelegate(self, queue: .main)

            let legible = AVPlayerItemLegibleOutput()
            legible.suppressesPlayerRendering = true
            legible.setDelegate(self, queue: .main)
            item.add(legible)

            let player = AVPlayer(playerItem: item)
            player.actionAtItemEnd = .pause

            self.player = player
            self.playerItem = item
            self.videoOutput = output
            self.legibleOutput = legible

            setupObservations(for: player, item: item)

            // Cache media selection groups for track selection
            self.audibleSelectionGroup = try? await asset.loadMediaSelectionGroup(for: .audible)
            self.legibleSelectionGroup = try? await asset.loadMediaSelectionGroup(for: .legible)

            state = .ready
            debugPrint("[AVFoundationDecoder.open] ✅ state=ready")
            return info
        } catch {
            debugPrint("[AVFoundationDecoder.open] ❌ ERROR: \(error)")
            let mappedError = (error as? AVFoundationDecoderError) ?? .playbackFailed(error)
            state = .error(mappedError)
            throw mappedError
        }
    }

    public func play() {
        debugPrint("[AVFoundationDecoder.play] called — player=\(player == nil ? "nil" : "exists"), rate=\(currentPlaybackRate)")
        guard let player else {
            debugPrint("[AVFoundationDecoder.play] ❌ no player")
            return
        }
        isExplicitlyPaused = false
        let rate = currentPlaybackRate > 0 ? currentPlaybackRate : 1.0
        player.rate = rate
        state = .playing
        displayLinkDriver?.start()
        debugPrint("[AVFoundationDecoder.play] ✅ playing at rate=\(rate)")
    }

    public func pause() {
        isExplicitlyPaused = true
        guard let player else { return }
        player.pause()
        state = .paused
        displayLinkDriver?.stop()
    }

    public func seek(to time: CMTime) async throws {
        guard let player else { return }
        let previousState = state
        let wasPlaying = !isExplicitlyPaused && (previousState == .playing || state == .playing)
        state = .seeking

        let finished = await player.seek(
            to: time,
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )

        if finished {
            tryFetchFrame(at: time)
            onTimeChanged?(time)
        }

        if wasPlaying {
            isExplicitlyPaused = false
            state = .playing
            player.rate = currentPlaybackRate > 0 ? currentPlaybackRate : 1.0
            displayLinkDriver?.start()
        } else {
            isExplicitlyPaused = true
            state = .paused
            displayLinkDriver?.stop()
        }
    }

    public func setRate(_ rate: Float) {
        currentPlaybackRate = rate
        if state == .playing {
            player?.rate = rate
        }
    }

    public func selectAudioTrack(_ index: Int) {
        guard let item = playerItem, let group = audibleSelectionGroup else { return }
        guard index >= 0 && index < group.options.count else { return }
        item.select(group.options[index], in: group)
    }

    public func selectSubtitleTrack(_ index: Int?) {
        guard let item = playerItem, let group = legibleSelectionGroup else { return }
        if let index {
            guard index >= 0 && index < group.options.count else { return }
            item.select(group.options[index], in: group)
        } else {
            item.select(nil, in: group)
        }
    }

    public func close() {
        displayLinkDriver?.stop()
        removeObservations()

        playerItem?.audioMix = nil

        if let item = playerItem, let output = videoOutput {
            item.remove(output)
        }

        if let item = playerItem, let legible = legibleOutput {
            item.remove(legible)
        }

        player?.pause()
        player?.replaceCurrentItem(with: nil)

        player = nil
        playerItem = nil
        videoOutput = nil
        legibleOutput = nil
        mediaInfo = nil
        currentVideoFormatDescription = nil
        audibleSelectionGroup = nil
        legibleSelectionGroup = nil
        isExplicitlyPaused = false

        if state != .idle {
            state = .idle
        }
    }

    // MARK: - Frame Delivery & Display Link

    private func handleDisplayLinkTick() {
        guard let output = videoOutput, state == .playing else { return }
        let hostTime = CACurrentMediaTime()
        let itemTime = output.itemTime(forHostTime: hostTime)
        guard itemTime.isValid else { return }

        deliverFrameIfAvailable(from: output, at: itemTime)
    }

    @discardableResult
    private func deliverFrameIfAvailable(from output: AVPlayerItemVideoOutput, at itemTime: CMTime) -> Bool {
        guard output.hasNewPixelBuffer(forItemTime: itemTime) else { return false }

        var displayTime = CMTime.invalid
        guard let pixelBuffer = output.copyPixelBuffer(
            forItemTime: itemTime,
            itemTimeForDisplay: &displayTime
        ) else {
            return false
        }

        ensureColorAttachments(on: pixelBuffer, from: currentVideoFormatDescription)

        let pts = displayTime.isValid ? displayTime : itemTime
        let frame = DecodedVideoFrame(
            pixelBuffer: pixelBuffer,
            presentationTime: pts,
            duration: nominalFrameDuration
        )
        onVideoFrame?(frame)
        return true
    }

    private func tryFetchFrame(at time: CMTime) {
        guard let output = videoOutput else { return }
        deliverFrameIfAvailable(from: output, at: time)
    }

    // MARK: - Video Output Setup

    private func makeVideoOutput() -> AVPlayerItemVideoOutput {
        var attributes: [String: Any] = [
            kCVPixelBufferIOSurfacePropertiesKey as String: [:] as [String: Any]
        ]

        switch pixelFormatPreference {
        case .automatic:
            attributes[kCVPixelBufferPixelFormatTypeKey as String] = [
                Int(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
                Int(kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange),
                Int(kCVPixelFormatType_32BGRA)
            ]
        case .yuv420:
            attributes[kCVPixelBufferPixelFormatTypeKey as String] = Int(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
        case .bgra:
            attributes[kCVPixelBufferPixelFormatTypeKey as String] = Int(kCVPixelFormatType_32BGRA)
        }

        return AVPlayerItemVideoOutput(pixelBufferAttributes: attributes)
    }

    // MARK: - HDR & Color Attachments

    private func ensureColorAttachments(on pixelBuffer: CVPixelBuffer, from formatDesc: CMFormatDescription?) {
        guard let formatDesc else { return }
        guard let extensions = CMFormatDescriptionGetExtensions(formatDesc) as? [CFString: Any] else { return }

        if CVBufferGetAttachment(pixelBuffer, kCVImageBufferColorPrimariesKey, nil) == nil,
           let primaries = extensions[kCVImageBufferColorPrimariesKey] {
            CVBufferSetAttachment(pixelBuffer, kCVImageBufferColorPrimariesKey, primaries as CFTypeRef, .shouldPropagate)
        }

        if CVBufferGetAttachment(pixelBuffer, kCVImageBufferTransferFunctionKey, nil) == nil,
           let transfer = extensions[kCVImageBufferTransferFunctionKey] {
            CVBufferSetAttachment(pixelBuffer, kCVImageBufferTransferFunctionKey, transfer as CFTypeRef, .shouldPropagate)
        }

        if CVBufferGetAttachment(pixelBuffer, kCVImageBufferYCbCrMatrixKey, nil) == nil,
           let matrix = extensions[kCVImageBufferYCbCrMatrixKey] {
            CVBufferSetAttachment(pixelBuffer, kCVImageBufferYCbCrMatrixKey, matrix as CFTypeRef, .shouldPropagate)
        }
    }

    // MARK: - Metadata Extraction

    private func extractMediaInfo(from asset: AVURLAsset) async throws -> MediaInfo {
        let duration = (try? await asset.load(.duration)) ?? .zero

        let rawVideoTracks = try await asset.loadTracks(withMediaType: .video)
        let rawAudioTracks = try await asset.loadTracks(withMediaType: .audio)
        let rawSubtitleTracks = try await asset.loadTracks(withMediaType: .subtitle)

        var videoTracks: [VideoTrackInfo] = []
        var maxNaturalSize: CGSize = .zero
        var detectedFrameRate: Float = 0
        var isHDR = false

        for (index, track) in rawVideoTracks.enumerated() {
            let naturalSize = (try? await track.load(.naturalSize)) ?? .zero
            let transform = (try? await track.load(.preferredTransform)) ?? .identity
            let transformedSize = naturalSize.applying(transform)
            let visualSize = CGSize(width: abs(transformedSize.width), height: abs(transformedSize.height))

            if visualSize.width > maxNaturalSize.width {
                maxNaturalSize = visualSize
            }

            let nominalRate = (try? await track.load(.nominalFrameRate)) ?? 0
            if nominalRate > detectedFrameRate {
                detectedFrameRate = nominalRate
            }

            let formatDescriptions = (try? await track.load(.formatDescriptions)) ?? []
            var codecString = "unknown"
            var bitDepth = 8
            var isHardwareDecodable = false

            if let firstDesc = formatDescriptions.first {
                self.currentVideoFormatDescription = firstDesc
                let subType = CMFormatDescriptionGetMediaSubType(firstDesc)
                codecString = FourCC.videoCodecName(from: subType)
                isHardwareDecodable = VTIsHardwareDecodeSupported(subType)

                if let extensions = CMFormatDescriptionGetExtensions(firstDesc) as? [CFString: Any] {
                    if let depth = extensions[kCMFormatDescriptionExtension_Depth] as? Int {
                        bitDepth = depth
                    } else if let bitsPerComp = extensions[kCMFormatDescriptionExtension_BitsPerComponent] as? Int {
                        bitDepth = bitsPerComp
                    }
                }
            }

            let characteristics = (try? await track.load(.mediaCharacteristics)) ?? []
            if characteristics.contains(.containsHDRVideo) || bitDepth > 8 {
                isHDR = true
            }

            videoTracks.append(
                VideoTrackInfo(
                    index: index,
                    codec: codecString,
                    size: visualSize,
                    bitDepth: bitDepth,
                    isHardwareDecodable: isHardwareDecodable
                )
            )
        }

        // Audio track inspection
        let audibleGroup = try? await asset.loadMediaSelectionGroup(for: .audible)
        var audioTracks: [AudioTrackInfo] = []

        if let audibleGroup, !audibleGroup.options.isEmpty {
            for (index, option) in audibleGroup.options.enumerated() {
                let lang = option.locale?.identifier ?? option.extendedLanguageTag
                let title = option.displayName

                var codec = "aac"
                var channels = 2
                var sampleRate = 48000

                if index < rawAudioTracks.count {
                    let track = rawAudioTracks[index]
                    let formatDescriptions = (try? await track.load(.formatDescriptions)) ?? []
                    if let desc = formatDescriptions.first {
                        let subType = CMFormatDescriptionGetMediaSubType(desc)
                        codec = FourCC.audioCodecName(from: subType)
                        if let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(desc) {
                            channels = max(Int(asbd.pointee.mChannelsPerFrame), 1)
                            sampleRate = max(Int(asbd.pointee.mSampleRate), 0)
                        }
                    }
                }

                audioTracks.append(
                    AudioTrackInfo(
                        index: index,
                        codec: codec,
                        channelCount: channels,
                        sampleRate: sampleRate,
                        language: lang,
                        title: title
                    )
                )
            }
        } else {
            for (index, track) in rawAudioTracks.enumerated() {
                let lang = try? await track.load(.extendedLanguageTag)
                var title: String?
                if let metadata = try? await track.load(.commonMetadata) {
                    for item in metadata {
                        if item.commonKey == AVMetadataKey.commonKeyTitle {
                            title = try? await item.load(.stringValue)
                        }
                    }
                }

                var codec = "aac"
                var channels = 2
                var sampleRate = 48000

                let formatDescriptions = (try? await track.load(.formatDescriptions)) ?? []
                if let desc = formatDescriptions.first {
                    let subType = CMFormatDescriptionGetMediaSubType(desc)
                    codec = FourCC.audioCodecName(from: subType)
                    if let asbd = CMAudioFormatDescriptionGetStreamBasicDescription(desc) {
                        channels = max(Int(asbd.pointee.mChannelsPerFrame), 1)
                        sampleRate = max(Int(asbd.pointee.mSampleRate), 0)
                    }
                }

                audioTracks.append(
                    AudioTrackInfo(
                        index: index,
                        codec: codec,
                        channelCount: channels,
                        sampleRate: sampleRate,
                        language: lang,
                        title: title
                    )
                )
            }
        }

        // Subtitle track inspection
        let legibleGroup = try? await asset.loadMediaSelectionGroup(for: .legible)
        var subtitleTracks: [SubtitleTrackInfo] = []

        if let legibleGroup, !legibleGroup.options.isEmpty {
            for (index, option) in legibleGroup.options.enumerated() {
                let lang = option.locale?.identifier ?? option.extendedLanguageTag
                let title = option.displayName
                var codec = "webvtt"

                if index < rawSubtitleTracks.count {
                    let track = rawSubtitleTracks[index]
                    let formatDescriptions = (try? await track.load(.formatDescriptions)) ?? []
                    if let desc = formatDescriptions.first {
                        let subType = CMFormatDescriptionGetMediaSubType(desc)
                        codec = FourCC.subtitleCodecName(from: subType)
                    }
                }

                subtitleTracks.append(
                    SubtitleTrackInfo(
                        index: index,
                        codec: codec,
                        language: lang,
                        title: title,
                        isImageBased: codec == "pgs" || codec == "dvdsub"
                    )
                )
            }
        } else {
            for (index, track) in rawSubtitleTracks.enumerated() {
                let lang = try? await track.load(.extendedLanguageTag)
                var title: String?
                if let metadata = try? await track.load(.commonMetadata) {
                    for item in metadata {
                        if item.commonKey == AVMetadataKey.commonKeyTitle {
                            title = try? await item.load(.stringValue)
                        }
                    }
                }

                var codec = "webvtt"
                let formatDescriptions = (try? await track.load(.formatDescriptions)) ?? []
                if let desc = formatDescriptions.first {
                    let subType = CMFormatDescriptionGetMediaSubType(desc)
                    codec = FourCC.subtitleCodecName(from: subType)
                }

                subtitleTracks.append(
                    SubtitleTrackInfo(
                        index: index,
                        codec: codec,
                        language: lang,
                        title: title,
                        isImageBased: codec == "pgs" || codec == "dvdsub"
                    )
                )
            }
        }

        return MediaInfo(
            duration: duration,
            videoTracks: videoTracks,
            audioTracks: audioTracks,
            subtitleTracks: subtitleTracks,
            naturalSize: maxNaturalSize,
            frameRate: detectedFrameRate > 0 ? detectedFrameRate : 24.0,
            isHDR: isHDR
        )
    }

    // MARK: - Observations

    private func setupObservations(for player: AVPlayer, item: AVPlayerItem) {
        itemStatusObservation = item.observe(\.status, options: [.new]) { [weak self] observedItem, _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                switch observedItem.status {
                case .readyToPlay:
                    if self.state == .opening {
                        self.state = .ready
                    }
                case .failed:
                    let error = observedItem.error ?? AVFoundationDecoderError.playbackFailed(nil)
                    self.state = .error(error)
                default:
                    break
                }
            }
        }

        timeControlStatusObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] observedPlayer, _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                switch observedPlayer.timeControlStatus {
                case .playing:
                    guard !self.isExplicitlyPaused else { return }
                    if self.state != .playing && self.state != .seeking {
                        self.state = .playing
                        self.displayLinkDriver?.start()
                    }
                case .paused:
                    if self.state != .paused && self.state != .seeking {
                        self.state = .paused
                        self.displayLinkDriver?.stop()
                    }
                default:
                    break
                }
            }
        }

        didPlayToEndObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.displayLinkDriver?.stop()
                self.state = .ended
            }
        }

        failedToPlayToEndObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemFailedToPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] notification in
            let underlyingError = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.displayLinkDriver?.stop()
                self.state = .error(AVFoundationDecoderError.playbackFailed(underlyingError))
            }
        }

        let interval = CMTime(value: 1, timescale: 10)
        timeObserverToken = player.addPeriodicTimeObserver(forInterval: interval, queue: .main) { [weak self] time in
            Task { @MainActor [weak self] in
                self?.onTimeChanged?(time)
            }
        }
    }

    private func removeObservations() {
        itemStatusObservation?.invalidate()
        itemStatusObservation = nil

        timeControlStatusObservation?.invalidate()
        timeControlStatusObservation = nil

        if let token = timeObserverToken {
            player?.removeTimeObserver(token)
            timeObserverToken = nil
        }

        if let observer = didPlayToEndObserver {
            NotificationCenter.default.removeObserver(observer)
            didPlayToEndObserver = nil
        }

        if let observer = failedToPlayToEndObserver {
            NotificationCenter.default.removeObserver(observer)
            failedToPlayToEndObserver = nil
        }
    }
}

// MARK: - AVPlayerItemOutputPullDelegate

extension AVFoundationDecoder: AVPlayerItemOutputPullDelegate {
    public nonisolated func outputMediaDataWillChange(_ sender: AVPlayerItemOutput) {
        // Notifies when new sample buffers will become available.
    }

    public nonisolated func outputSequenceWasFlushed(_ sender: AVPlayerItemOutput) {
        // Handled on seek or discontinuity.
    }
}

// MARK: - AVPlayerItemLegibleOutputPushDelegate

extension AVFoundationDecoder: AVPlayerItemLegibleOutputPushDelegate {
    public func legibleOutput(
        _ output: AVPlayerItemLegibleOutput,
        didOutputAttributedStrings strings: [NSAttributedString],
        nativeDurationForSample duration: [NSValue],
        atItemTime itemTime: CMTime
    ) {
        guard !strings.isEmpty else {
            onSubtitleEvent?(nil)
            return
        }

        let fullText = strings.map(\.string).joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !fullText.isEmpty else {
            onSubtitleEvent?(nil)
            return
        }

        let dur = duration.first?.timeValue ?? CMTime(seconds: 4, preferredTimescale: 600)
        let validDuration = (dur.isValid && dur.seconds > 0) ? dur : CMTime(seconds: 4, preferredTimescale: 600)
        let end = itemTime + validDuration

        let event = DecodedSubtitleEvent(
            text: fullText,
            start: itemTime,
            end: end
        )
        onSubtitleEvent?(event)
    }
}

// MARK: - Audio Processing Tap

extension AVFoundationDecoder {

    func installAudioTap() {
        guard let item = playerItem, let processor = audioProcessor else {
            playerItem?.audioMix = nil
            return
        }
        #if os(visionOS)
        guard let audioTrack = item.tracks.first(where: { $0.assetTrack?.mediaType == .audio })?.assetTrack else { return }
        #else
        guard let audioTrack = item.asset.tracks(withMediaType: .audio).first ?? item.tracks.first(where: { $0.assetTrack?.mediaType == .audio })?.assetTrack else { return }
        #endif

        let params = AVMutableAudioMixInputParameters(track: audioTrack)
        let context = Unmanaged.passRetained(processor)

        var callbacks = MTAudioProcessingTapCallbacks(
            version: kMTAudioProcessingTapCallbacksVersion_0,
            clientInfo: context.toOpaque(),
            init: eqTapInit,
            finalize: eqTapFinalize,
            prepare: eqTapPrepare,
            unprepare: nil,
            process: eqTapProcess
        )

        var tap: MTAudioProcessingTap?
        let status = MTAudioProcessingTapCreate(
            kCFAllocatorDefault,
            &callbacks,
            kMTAudioProcessingTapCreationFlag_PostEffects,
            &tap
        )
        guard status == noErr, let tap else {
            context.release()
            return
        }

        params.audioTapProcessor = tap

        let mix = AVMutableAudioMix()
        mix.inputParameters = [params]
        item.audioMix = mix
    }
}

nonisolated private func eqTapInit(
    _ tap: MTAudioProcessingTap,
    _ clientInfo: UnsafeMutableRawPointer?,
    _ tapStorageOut: UnsafeMutablePointer<UnsafeMutableRawPointer?>
) {
    tapStorageOut.pointee = clientInfo
}

nonisolated private func eqTapFinalize(_ tap: MTAudioProcessingTap) {
    let storage = MTAudioProcessingTapGetStorage(tap)
    Unmanaged<AudioEQProcessor>.fromOpaque(storage).release()
}

nonisolated private func eqTapPrepare(
    _ tap: MTAudioProcessingTap,
    _ maxFrames: CMItemCount,
    _ processingFormat: UnsafePointer<AudioStreamBasicDescription>
) {
    let asbd = processingFormat.pointee
    let processor = Unmanaged<AudioEQProcessor>
        .fromOpaque(MTAudioProcessingTapGetStorage(tap))
        .takeUnretainedValue()
    processor.configure(
        sampleRate: asbd.mSampleRate,
        channelCount: Int(asbd.mChannelsPerFrame)
    )
}

nonisolated private func eqTapProcess(
    _ tap: MTAudioProcessingTap,
    _ numberFrames: CMItemCount,
    _ flags: MTAudioProcessingTapFlags,
    _ bufferListInOut: UnsafeMutablePointer<AudioBufferList>,
    _ numberFramesOut: UnsafeMutablePointer<CMItemCount>,
    _ flagsOut: UnsafeMutablePointer<MTAudioProcessingTapFlags>
) {
    var sourceFlags: MTAudioProcessingTapFlags = 0
    let status = MTAudioProcessingTapGetSourceAudio(
        tap, numberFrames, bufferListInOut,
        &sourceFlags, nil, numberFramesOut
    )
    guard status == noErr else { return }

    let processor = Unmanaged<AudioEQProcessor>
        .fromOpaque(MTAudioProcessingTapGetStorage(tap))
        .takeUnretainedValue()
    processor.process(bufferListInOut, frameCount: Int(numberFramesOut.pointee))
}

// MARK: - FourCC Codec Conversion Helpers

private enum FourCC {
    static func videoCodecName(from subType: FourCharCode) -> String {
        switch subType {
        case kCMVideoCodecType_H264:
            return "h264"
        case kCMVideoCodecType_HEVC, kCMVideoCodecType_HEVCWithAlpha:
            return "hevc"
        case kCMVideoCodecType_VP9:
            return "vp9"
        case kCMVideoCodecType_AV1:
            return "av1"
        case kCMVideoCodecType_AppleProRes422,
             kCMVideoCodecType_AppleProRes4444,
             kCMVideoCodecType_AppleProRes422HQ,
             kCMVideoCodecType_AppleProRes422LT,
             kCMVideoCodecType_AppleProRes422Proxy:
            return "prores"
        default:
            return toString(subType)
        }
    }

    static func audioCodecName(from subType: FourCharCode) -> String {
        switch subType {
        case kAudioFormatMPEG4AAC, kAudioFormatMPEG4AAC_HE, kAudioFormatMPEG4AAC_HE_V2:
            return "aac"
        case kAudioFormatMPEGLayer3:
            return "mp3"
        case kAudioFormatAppleLossless:
            return "alac"
        case kAudioFormatFLAC:
            return "flac"
        case kAudioFormatOpus:
            return "opus"
        case kAudioFormatAC3:
            return "ac3"
        case kAudioFormatEnhancedAC3:
            return "eac3"
        case kAudioFormatLinearPCM:
            return "pcm"
        default:
            return toString(subType)
        }
    }

    static func subtitleCodecName(from subType: FourCharCode) -> String {
        switch subType {
        case kCMSubtitleFormatType_WebVTT:
            return "webvtt"
        case 0x74783367: // 'tx3g'
            return "tx3g"
        default:
            return toString(subType)
        }
    }

    private static func toString(_ code: FourCharCode) -> String {
        let bytes: [CChar] = [
            CChar((code >> 24) & 0xFF),
            CChar((code >> 16) & 0xFF),
            CChar((code >> 8) & 0xFF),
            CChar(code & 0xFF),
            0
        ]
        return String(cString: bytes).trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
