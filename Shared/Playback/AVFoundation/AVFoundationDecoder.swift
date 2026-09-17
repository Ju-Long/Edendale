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
import QuartzCore
import VideoToolbox

#if os(macOS)
import AppKit
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
        #endif
    }

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

    public let pixelFormatPreference: PixelFormatPreference

    /// Exposes the underlying AVPlayer for volume/mute control by
    /// `PlaybackEngine`. Read-only outside this class.
    public var avPlayer: AVPlayer? { player }

    // MARK: - Internal AVFoundation Components

    private var player: AVPlayer?
    private var playerItem: AVPlayerItem?
    private var videoOutput: AVPlayerItemVideoOutput?
    private var displayLinkDriver: DisplayLinkDriver?

    private var timeObserverToken: Any?
    private var itemStatusObservation: NSKeyValueObservation?
    private var timeControlStatusObservation: NSKeyValueObservation?
    private var didPlayToEndObserver: NSObjectProtocol?
    private var failedToPlayToEndObserver: NSObjectProtocol?

    private var currentVideoFormatDescription: CMFormatDescription?
    private var nominalFrameDuration: CMTime = CMTime(value: 1, timescale: 60)
    private var currentPlaybackRate: Float = 1.0

    private var audibleSelectionGroup: AVMediaSelectionGroup?
    private var legibleSelectionGroup: AVMediaSelectionGroup?

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
        close()
        state = .opening

        let asset = AVURLAsset(
            url: url,
            options: [AVURLAssetPreferPreciseDurationAndTimingKey: true]
        )

        let isPlayable = (try? await asset.load(.isPlayable)) ?? false
        guard isPlayable else {
            let error = AVFoundationDecoderError.unplayableAsset
            state = .error(error)
            throw error
        }

        do {
            let info = try await extractMediaInfo(from: asset)
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

            let player = AVPlayer(playerItem: item)
            player.actionAtItemEnd = .pause

            self.player = player
            self.playerItem = item
            self.videoOutput = output

            setupObservations(for: player, item: item)

            // Cache media selection groups for track selection
            self.audibleSelectionGroup = try? await asset.loadMediaSelectionGroup(for: .audible)
            self.legibleSelectionGroup = try? await asset.loadMediaSelectionGroup(for: .legible)

            state = .ready
            return info
        } catch {
            let mappedError = (error as? AVFoundationDecoderError) ?? .playbackFailed(error)
            state = .error(mappedError)
            throw mappedError
        }
    }

    public func play() {
        guard let player else { return }
        let rate = currentPlaybackRate > 0 ? currentPlaybackRate : 1.0
        player.rate = rate
        state = .playing
        displayLinkDriver?.start()
    }

    public func pause() {
        guard let player else { return }
        player.pause()
        state = .paused
        displayLinkDriver?.stop()
    }

    public func seek(to time: CMTime) async throws {
        guard let player else { return }
        let previousState = state
        state = .seeking

        let finished = await player.seek(
            to: time,
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )

        if finished {
            tryFetchFrame(at: time)
        }

        if previousState == .playing {
            state = .playing
            player.rate = currentPlaybackRate > 0 ? currentPlaybackRate : 1.0
            displayLinkDriver?.start()
        } else {
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

        if let item = playerItem, let output = videoOutput {
            item.remove(output)
        }

        player?.pause()
        player?.replaceCurrentItem(with: nil)

        player = nil
        playerItem = nil
        videoOutput = nil
        mediaInfo = nil
        currentVideoFormatDescription = nil
        audibleSelectionGroup = nil
        legibleSelectionGroup = nil

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
                    if self.state != .playing && self.state != .seeking {
                        self.state = .playing
                    }
                case .paused:
                    if self.state == .playing {
                        self.state = .paused
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
