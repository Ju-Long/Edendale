import AVFoundation
import AVKit
import CoreMedia
import CoreVideo
import Observation

#if os(iOS) || os(macOS)

/// Thread-safe enqueue state for the PiP sample buffer layer. Lives outside
/// the `@MainActor` class so `enqueue` can run on any thread without hopping.
private final class PiPEnqueueState: @unchecked Sendable {
    private let lock = NSLock()
    private var formatDescription: CMFormatDescription?
    private var lastSize: CGSize = .zero

    func formatDescription(for pixelBuffer: CVPixelBuffer) -> CMFormatDescription? {
        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let size = CGSize(width: width, height: height)

        lock.lock()
        defer { lock.unlock() }

        if formatDescription == nil || lastSize != size {
            var desc: CMFormatDescription?
            CMVideoFormatDescriptionCreateForImageBuffer(
                allocator: kCFAllocatorDefault,
                imageBuffer: pixelBuffer,
                formatDescriptionOut: &desc
            )
            formatDescription = desc
            lastSize = size
        }
        return formatDescription
    }

    func reset() {
        lock.lock()
        formatDescription = nil
        lastSize = .zero
        lock.unlock()
    }
}

/// Feeds processed video frames to an `AVSampleBufferDisplayLayer` that serves
/// as the content source for `AVPictureInPictureController`. The layer stays
/// hidden behind the Metal rendering surface during normal playback and becomes
/// visible only when PiP is active.
@MainActor
@Observable
final class SampleBufferPiPSource: NSObject, @unchecked Sendable {

    let displayLayer = AVSampleBufferDisplayLayer()
    private(set) var pipController: AVPictureInPictureController?
    private(set) var isActive: Bool = false

    private weak var engine: PlaybackEngine?
    private let enqueueState = PiPEnqueueState()
    private var timebase: CMTimebase?
    #if os(iOS)
    var automaticallyStartsFromInline = true {
        didSet { pipController?.canStartPictureInPictureAutomaticallyFromInline = automaticallyStartsFromInline }
    }
    #endif

    var onWillStart: (() -> Void)?
    var onDidStart: (() -> Void)?
    var onWillStop: (() -> Void)?
    var onDidStop: (() -> Void)?
    var onRestoreUI: ((@escaping (Bool) -> Void) -> Void)?

    override init() {
        super.init()
        CMTimebaseCreateWithSourceClock(
            allocator: kCFAllocatorDefault,
            sourceClock: CMClockGetHostTimeClock(),
            timebaseOut: &timebase
        )
        displayLayer.controlTimebase = timebase
        displayLayer.videoGravity = .resizeAspect
        #if os(iOS)
        displayLayer.preventsDisplaySleepDuringVideoPlayback = true
        #endif
    }

    func attach(to engine: PlaybackEngine) {
        self.engine = engine
        setupPiPController()
        invalidatePlaybackState()
    }

    func detach() {
        pipController?.stopPictureInPicture()
        pipController?.delegate = nil
        pipController = nil
        engine = nil
        displayLayer.sampleBufferRenderer.flush()
        enqueueState.reset()
        isActive = false
        if let timebase { CMTimebaseSetRate(timebase, rate: 0) }
    }

    /// AVKit caches these delegate values; refresh after transport/duration changes.
    func invalidatePlaybackState() {
        synchronizePlaybackClock()
        pipController?.invalidatePlaybackState()
    }

    func synchronizePlaybackClock() {
        guard let timebase, let engine else { return }
        CMTimebaseSetTime(timebase, time: CMTime(seconds: engine.currentTime.playbackSeconds, preferredTimescale: 60000))
        CMTimebaseSetRate(timebase, rate: engine.isPlaying ? Double(engine.playbackRate) : 0)
    }

    /// Enqueue a processed frame into the sample buffer layer for PiP.
    /// Called from the engine's frame callback on the main actor.
    func enqueue(pixelBuffer: CVPixelBuffer, presentationTime: CMTime, duration: CMTime) {
        guard let formatDesc = enqueueState.formatDescription(for: pixelBuffer) else { return }

        var timingInfo = CMSampleTimingInfo(
            duration: duration,
            presentationTimeStamp: presentationTime,
            decodeTimeStamp: .invalid
        )

        var sampleBuffer: CMSampleBuffer?
        CMSampleBufferCreateReadyWithImageBuffer(
            allocator: kCFAllocatorDefault,
            imageBuffer: pixelBuffer,
            formatDescription: formatDesc,
            sampleTiming: &timingInfo,
            sampleBufferOut: &sampleBuffer
        )

        guard let sampleBuffer else { return }

        let renderer = displayLayer.sampleBufferRenderer
        if renderer.status == .failed {
            renderer.flush()
        }
        if renderer.isReadyForMoreMediaData {
            renderer.enqueue(sampleBuffer)
        }
    }

    // MARK: - PiP controls

    func start() {
        invalidatePlaybackState()
        pipController?.startPictureInPicture()
    }

    func stop() {
        pipController?.stopPictureInPicture()
    }

    func toggle() {
        if isActive { stop() } else { start() }
    }

    var isPossible: Bool {
        pipController?.isPictureInPicturePossible ?? false
    }

    // MARK: - Setup

    private func setupPiPController() {
        guard AVPictureInPictureController.isPictureInPictureSupported() else { return }

        let contentSource = AVPictureInPictureController.ContentSource(
            sampleBufferDisplayLayer: displayLayer,
            playbackDelegate: self
        )
        let controller = AVPictureInPictureController(contentSource: contentSource)
        controller.delegate = self
        #if os(iOS)
        controller.canStartPictureInPictureAutomaticallyFromInline = automaticallyStartsFromInline
        #endif
        pipController = controller
    }
}

// MARK: - AVPictureInPictureControllerDelegate

extension SampleBufferPiPSource: AVPictureInPictureControllerDelegate {
    nonisolated func pictureInPictureControllerWillStartPictureInPicture(
        _ pictureInPictureController: AVPictureInPictureController
    ) {
        Task { @MainActor [weak self] in
            guard let self, self.pipController === pictureInPictureController else { return }
            self.isActive = true
            self.onWillStart?()
        }
    }

    nonisolated func pictureInPictureControllerDidStartPictureInPicture(
        _ pictureInPictureController: AVPictureInPictureController
    ) {
        Task { @MainActor [weak self] in
            guard let self, self.pipController === pictureInPictureController else { return }
            self.invalidatePlaybackState()
            self.onDidStart?()
        }
    }

    nonisolated func pictureInPictureControllerWillStopPictureInPicture(
        _ pictureInPictureController: AVPictureInPictureController
    ) {
        Task { @MainActor [weak self] in
            guard let self, self.pipController === pictureInPictureController else { return }
            self.onWillStop?()
        }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(
        _ pictureInPictureController: AVPictureInPictureController
    ) {
        Task { @MainActor [weak self] in
            guard let self, self.pipController === pictureInPictureController else { return }
            self.isActive = false
            self.onDidStop?()
        }
    }

    nonisolated func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        failedToStartPictureInPictureWithError error: Error
    ) {
        Task { @MainActor [weak self] in
            guard let self, self.pipController === pictureInPictureController else { return }
            self.isActive = false
            self.onDidStop?()
        }
    }

    nonisolated func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void
    ) {
        Task { @MainActor [weak self] in
            guard let self, self.pipController === pictureInPictureController else {
                completionHandler(false)
                return
            }
            if let restore = self.onRestoreUI {
                restore(completionHandler)
            } else {
                // macOS keeps the original player window available.
                completionHandler(self.engine != nil)
            }
        }
    }
}

// MARK: - AVPictureInPictureSampleBufferPlaybackDelegate

extension SampleBufferPiPSource: AVPictureInPictureSampleBufferPlaybackDelegate {
    nonisolated func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        setPlaying playing: Bool
    ) {
        Task { @MainActor [weak self] in
            guard let self, self.pipController === pictureInPictureController else { return }
            guard let engine = self.engine else { return }
            if playing { engine.play() } else { engine.pause() }
        }
    }

    nonisolated func pictureInPictureControllerTimeRangeForPlayback(
        _ pictureInPictureController: AVPictureInPictureController
    ) -> CMTimeRange {
        let dur: CMTime
        if let d = MainActor.assumeIsolated({ self.engine?.duration }), d.playbackSeconds > 0 {
            dur = CMTime(seconds: d.playbackSeconds, preferredTimescale: 600)
        } else {
            dur = .positiveInfinity
        }
        return CMTimeRange(start: .zero, duration: dur)
    }

    nonisolated func pictureInPictureControllerIsPlaybackPaused(
        _ pictureInPictureController: AVPictureInPictureController
    ) -> Bool {
        MainActor.assumeIsolated { !(self.engine?.isPlaying ?? false) }
    }

    nonisolated func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        didTransitionToRenderSize newRenderSize: CMVideoDimensions
    ) {}

    nonisolated func pictureInPictureController(
        _ pictureInPictureController: AVPictureInPictureController,
        skipByInterval skipInterval: CMTime,
        completion: @escaping () -> Void
    ) {
        Task { @MainActor [weak self] in
            guard let self, self.pipController === pictureInPictureController else {
                completion()
                return
            }
            let seconds = skipInterval.seconds
            self.engine?.seek(by: .seconds(seconds))
            completion()
        }
    }
}

#else

@MainActor
final class SampleBufferPiPSource: NSObject, @unchecked Sendable {
    let displayLayer = CALayer()
    var isActive: Bool = false
    var isPossible: Bool = false
    var onWillStart: (() -> Void)?
    var onDidStart: (() -> Void)?
    var onWillStop: (() -> Void)?
    var onDidStop: (() -> Void)?
    var onRestoreUI: ((@escaping (Bool) -> Void) -> Void)?

    func attach(to engine: PlaybackEngine) {}
    func detach() {}
    func enqueue(pixelBuffer: CVPixelBuffer, presentationTime: CMTime, duration: CMTime) {}
    func start() {}
    func stop() {}
    func toggle() {}
}

#endif
