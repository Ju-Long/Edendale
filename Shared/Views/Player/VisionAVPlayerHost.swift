#if os(visionOS)

import AVFoundation
import AVKit
import Foundation
import SwiftUI

struct VisionAVPlayerFailure: Error, Equatable, LocalizedError, Sendable {
    enum Stage: String, Sendable {
        case preparation
        case playback
    }

    let stage: Stage
    let message: String

    var errorDescription: String? { message }
}

/// Lifecycle and progress emitted by `VisionAVPlayerHost` on the main actor.
enum VisionAVPlayerEvent: Equatable, Sendable {
    case ready(duration: TimeInterval?)
    case started
    case progress(currentTime: TimeInterval, duration: TimeInterval?)
    case ended
    case dismissalRequested
    case stopped
    case failed(VisionAVPlayerFailure)
}

/// SwiftUI may move a representable value between isolation domains while its
/// coordinator remains main-actor bound. This wrapper keeps callback execution
/// on the main actor without requiring callers' captured UI state to be
/// `Sendable`.
struct VisionAVPlayerEventHandler: @unchecked Sendable {
    private let body: @MainActor (VisionAVPlayerEvent) -> Void

    init(_ body: @escaping @MainActor (VisionAVPlayerEvent) -> Void) {
        self.body = body
    }

    @MainActor
    func callAsFunction(_ event: VisionAVPlayerEvent) {
        body(event)
    }
}

struct VisionAVPlayerConfiguration: Equatable, Sendable {
    /// Starts playback after AVPlayerItem reports that it is ready.
    var automaticallyStartsPlayback = true

    /// Optional saved position in the inclusive range 0...1.
    var initialPosition: Double?

    /// Frequency of progress events. Values below 0.25 seconds are clamped.
    var progressUpdateInterval: TimeInterval = 1

    /// Explicit interpretation for an otherwise untagged packed source.
    /// `nil` preserves the asset's native AVFoundation signalling.
    var packedLayout: VisionFormatLayout?

    init(
        automaticallyStartsPlayback: Bool = true,
        initialPosition: Double? = nil,
        progressUpdateInterval: TimeInterval = 1,
        packedLayout: VisionFormatLayout? = nil
    ) {
        self.automaticallyStartsPlayback = automaticallyStartsPlayback
        self.initialPosition = initialPosition.map { min(max($0, 0), 1) }
        self.progressUpdateInterval = max(progressUpdateInterval, 0.25)
        self.packedLayout = packedLayout
    }
}

/// A native, full-window visionOS player surface.
///
/// Place this representable as the root content of Edendale's existing
/// `WindowGroup` while it is active. The coordinator strongly retains the
/// supplied `PlaybackItem` until the AVPlayer is stopped and detached, which
/// keeps its security-scoped file access valid through expanded or docked
/// playback.
struct VisionAVPlayerHost: UIViewControllerRepresentable {
    let playbackItem: PlaybackItem
    var configuration: VisionAVPlayerConfiguration
    private var eventHandler: VisionAVPlayerEventHandler

    init(
        playbackItem: PlaybackItem,
        configuration: VisionAVPlayerConfiguration = .init(),
        onEvent: @escaping @MainActor (VisionAVPlayerEvent) -> Void = { _ in }
    ) {
        self.playbackItem = playbackItem
        self.configuration = configuration
        self.eventHandler = VisionAVPlayerEventHandler(onEvent)
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(eventHandler: eventHandler)
    }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.showsPlaybackControls = true
        controller.requiresMonoscopicViewingMode = false
        controller.delegate = context.coordinator

        context.coordinator.attach(
            playbackItem: playbackItem,
            configuration: configuration,
            to: controller
        )
        return controller
    }

    func updateUIViewController(
        _ controller: AVPlayerViewController,
        context: Context
    ) {
        context.coordinator.update(
            eventHandler: eventHandler,
            configuration: configuration
        )
        context.coordinator.attachIfNeeded(
            playbackItem: playbackItem,
            configuration: configuration,
            to: controller
        )
    }

    static func dismantleUIViewController(
        _ controller: AVPlayerViewController,
        coordinator: Coordinator
    ) {
        coordinator.stop(notify: true)
        controller.delegate = nil
        controller.player = nil
    }

    @MainActor
    final class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        private var eventHandler: VisionAVPlayerEventHandler
        private weak var controller: AVPlayerViewController?

        /// This strong reference owns the PlaybackScope and therefore the
        /// security-scoped access for as long as AVPlayer can read the asset.
        private var retainedPlaybackItem: PlaybackItem?
        private var playbackItemID: UUID?
        private var attachedPackedLayout: VisionFormatLayout?
        private var configuration = VisionAVPlayerConfiguration()

        private var player: AVPlayer?
        private var nativeItem: AVPlayerItem?
        private var statusObservation: NSKeyValueObservation?
        private var timeObserver: Any?
        private var notificationObservers: [NSObjectProtocol] = []
        private var preparationTask: Task<Void, Never>?

        private var emittedReady = false
        private var emittedStarted = false
        private var emittedDismissal = false

        init(eventHandler: VisionAVPlayerEventHandler) {
            self.eventHandler = eventHandler
        }

        func update(
            eventHandler: VisionAVPlayerEventHandler,
            configuration: VisionAVPlayerConfiguration
        ) {
            self.eventHandler = eventHandler
            self.configuration = configuration
        }

        func attachIfNeeded(
            playbackItem: PlaybackItem,
            configuration: VisionAVPlayerConfiguration,
            to controller: AVPlayerViewController
        ) {
            guard playbackItem.id != playbackItemID
                    || configuration.packedLayout != attachedPackedLayout
            else { return }
            attach(
                playbackItem: playbackItem,
                configuration: configuration,
                to: controller
            )
        }

        func attach(
            playbackItem: PlaybackItem,
            configuration: VisionAVPlayerConfiguration,
            to controller: AVPlayerViewController
        ) {
            // Retain the new scope before tearing down the old player. Keep the
            // old item in a local until teardown is complete to avoid a gap in
            // security-scoped access while switching files.
            let previousPlaybackItem = retainedPlaybackItem
            retainedPlaybackItem = playbackItem
            tearDownPlayer()
            withExtendedLifetime(previousPlaybackItem) {}

            self.controller = controller
            self.configuration = configuration
            playbackItemID = playbackItem.id
            attachedPackedLayout = configuration.packedLayout
            emittedReady = false
            emittedStarted = false
            emittedDismissal = false

            guard let url = playbackItem.url else {
                let message = playbackItem.errorMessage
                    ?? String(localized: "The selected item has no playable URL.")
                emitFailure(stage: .preparation, message: message)
                return
            }

            let asset = VisionMediaAssetFactory.make(url: url)
            guard let packedLayout = configuration.packedLayout else {
                installPlayerItem(
                    AVPlayerItem(asset: asset),
                    for: playbackItem,
                    on: controller
                )
                return
            }

            guard #available(visionOS 26.0, *) else {
                emitFailure(
                    stage: .preparation,
                    message: String(localized: "Packed spatial playback requires visionOS 26 or later.")
                )
                return
            }

            let expectedItemID = playbackItem.id
            preparationTask = Task { @MainActor [weak self, weak controller] in
                do {
                    let composition = try await VisionPackedVideoComposition.make(
                        asset: asset,
                        layout: packedLayout
                    )
                    guard !Task.isCancelled,
                          let self,
                          let controller,
                          self.playbackItemID == expectedItemID,
                          self.attachedPackedLayout == packedLayout
                    else { return }

                    let nativeItem = AVPlayerItem(asset: asset)
                    nativeItem.videoComposition = composition
                    nativeItem.seekingWaitsForVideoCompositionRendering = true
                    self.installPlayerItem(
                        nativeItem,
                        for: playbackItem,
                        on: controller
                    )
                } catch {
                    guard !Task.isCancelled,
                          let self,
                          self.playbackItemID == expectedItemID,
                          self.attachedPackedLayout == packedLayout
                    else { return }
                    self.emitFailure(
                        stage: .preparation,
                        message: error.localizedDescription
                    )
                }
            }
        }

        private func installPlayerItem(
            _ nativeItem: AVPlayerItem,
            for playbackItem: PlaybackItem,
            on controller: AVPlayerViewController
        ) {
            nativeItem.externalMetadata = metadata(for: playbackItem)

            let player = AVPlayer(playerItem: nativeItem)
            self.nativeItem = nativeItem
            self.player = player
            controller.player = player

            installObservers(for: nativeItem, player: player)
        }

        func stop(notify: Bool) {
            guard player != nil || retainedPlaybackItem != nil else { return }

            if let player {
                emitProgress(player.currentTime())
            }
            tearDownPlayer()
            controller?.player = nil
            retainedPlaybackItem = nil
            playbackItemID = nil
            attachedPackedLayout = nil

            if notify {
                eventHandler(.stopped)
            }
        }

        private func installObservers(for item: AVPlayerItem, player: AVPlayer) {
            statusObservation = item.observe(\.status, options: [.initial, .new]) {
                [weak self, weak item] _, _ in
                guard let item else { return }
                Task { @MainActor [weak self] in
                    self?.handleStatus(of: item)
                }
            }

            let center = NotificationCenter.default
            notificationObservers.append(
                center.addObserver(
                    forName: .AVPlayerItemDidPlayToEndTime,
                    object: item,
                    queue: .main
                ) { [weak self, weak item] _ in
                    guard let item else { return }
                    Task { @MainActor [weak self] in
                        guard let self, self.nativeItem === item else { return }
                        self.emitProgress(item.duration)
                        self.eventHandler(.ended)
                    }
                }
            )
            notificationObservers.append(
                center.addObserver(
                    forName: .AVPlayerItemFailedToPlayToEndTime,
                    object: item,
                    queue: .main
                ) { [weak self, weak item] notification in
                    guard let item else { return }
                    let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey]
                        as? Error
                    Task { @MainActor [weak self] in
                        guard let self, self.nativeItem === item else { return }
                        self.emitFailure(
                            stage: .playback,
                            message: error?.localizedDescription
                                ?? String(localized: "The video could not finish playing.")
                        )
                    }
                }
            )

            let interval = CMTime(
                seconds: configuration.progressUpdateInterval,
                preferredTimescale: 600
            )
            timeObserver = player.addPeriodicTimeObserver(
                forInterval: interval,
                queue: .main
            ) { [weak self] time in
                Task { @MainActor [weak self] in
                    self?.emitProgress(time)
                }
            }
        }

        private func handleStatus(of item: AVPlayerItem) {
            guard nativeItem === item else { return }

            switch item.status {
            case .readyToPlay:
                guard !emittedReady else { return }
                emittedReady = true
                eventHandler(.ready(duration: finiteSeconds(item.duration)))
                seekToInitialPositionIfNeeded(thenStart: configuration.automaticallyStartsPlayback)

            case .failed:
                emitFailure(
                    stage: .preparation,
                    message: item.error?.localizedDescription
                        ?? String(localized: "The video could not be prepared for playback.")
                )

            case .unknown:
                break

            @unknown default:
                break
            }
        }

        private func seekToInitialPositionIfNeeded(thenStart: Bool) {
            guard let player else { return }
            guard let position = configuration.initialPosition,
                  let duration = finiteSeconds(nativeItem?.duration),
                  duration > 0
            else {
                if thenStart { startPlayback() }
                return
            }

            let target = CMTime(seconds: duration * position, preferredTimescale: 600)
            player.seek(to: target, toleranceBefore: .zero, toleranceAfter: .zero) {
                [weak self] completed in
                guard completed, thenStart else { return }
                Task { @MainActor [weak self] in
                    self?.startPlayback()
                }
            }
        }

        private func startPlayback() {
            guard let player, nativeItem?.status == .readyToPlay else { return }
            player.play()
            guard !emittedStarted else { return }
            emittedStarted = true
            eventHandler(.started)
        }

        private func emitProgress(_ time: CMTime) {
            guard let currentTime = finiteSeconds(time) else { return }
            eventHandler(
                .progress(
                    currentTime: currentTime,
                    duration: finiteSeconds(nativeItem?.duration)
                )
            )
        }

        private func emitFailure(stage: VisionAVPlayerFailure.Stage, message: String) {
            player?.pause()
            eventHandler(.failed(.init(stage: stage, message: message)))
        }

        private func tearDownPlayer() {
            preparationTask?.cancel()
            preparationTask = nil

            statusObservation?.invalidate()
            statusObservation = nil

            notificationObservers.forEach(NotificationCenter.default.removeObserver)
            notificationObservers.removeAll()

            if let player, let timeObserver {
                player.removeTimeObserver(timeObserver)
            }
            timeObserver = nil

            player?.pause()
            player?.replaceCurrentItem(with: nil)
            player = nil
            nativeItem = nil
        }

        private func metadata(for playbackItem: PlaybackItem) -> [AVMetadataItem] {
            var items = [
                metadataItem(
                    identifier: .commonIdentifierTitle,
                    value: playbackItem.displayTitle
                )
            ]
            if let subtitle = playbackItem.subtitle, !subtitle.isEmpty {
                items.append(
                    metadataItem(
                        identifier: .iTunesMetadataTrackSubTitle,
                        value: subtitle
                    )
                )
            }
            return items
        }

        private func metadataItem(
            identifier: AVMetadataIdentifier,
            value: String
        ) -> AVMetadataItem {
            let item = AVMutableMetadataItem()
            item.identifier = identifier
            item.value = value as NSString
            item.extendedLanguageTag = "und"
            return item.copy() as! AVMetadataItem
        }

        private func finiteSeconds(_ time: CMTime?) -> TimeInterval? {
            guard let time else { return nil }
            let seconds = time.seconds
            return seconds.isFinite && seconds >= 0 ? seconds : nil
        }

        // The system Back control exits the full-window presentation. Let the
        // app clear its session in the transition completion so it can replace
        // this root view with the library without interrupting the animation.
        func playerViewController(
            _ playerViewController: AVPlayerViewController,
            willEndFullScreenPresentationWithAnimationCoordinator coordinator: any UIViewControllerTransitionCoordinator
        ) {
            coordinator.animate(alongsideTransition: nil) { [weak self] context in
                guard !context.isCancelled else { return }
                Task { @MainActor [weak self] in
                    guard let self, !self.emittedDismissal else { return }
                    self.emittedDismissal = true
                    self.eventHandler(.dismissalRequested)
                }
            }
        }
    }
}

#endif
