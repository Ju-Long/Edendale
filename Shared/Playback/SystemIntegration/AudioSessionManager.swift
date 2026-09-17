import AVFoundation
import Foundation

#if !os(macOS)
/// Manages `AVAudioSession` for video playback on iOS, tvOS, and visionOS.
/// macOS does not use AVAudioSession.
@MainActor
final class AudioSessionManager {

    private var isActivated = false
    private var routeChangeObserver: NSObjectProtocol?
    private var interruptionObserver: NSObjectProtocol?
    private weak var engine: PlaybackEngine?

    func activate(for engine: PlaybackEngine) {
        self.engine = engine

        let session = AVAudioSession.sharedInstance()
        do {
            #if os(tvOS)
            try session.setCategory(
                .playback,
                mode: .moviePlayback,
                options: [.allowAirPlay]
            )
            #else
            try session.setCategory(
                .playback,
                mode: .moviePlayback,
                policy: .longFormVideo,
                options: [.allowAirPlay]
            )
            #endif
            try session.setActive(true)
            isActivated = true
        } catch {
            // Non-fatal — playback still works, just no background audio
        }

        observeRouteChanges()
        observeInterruptions()
    }

    func deactivate() {
        engine = nil

        if let observer = routeChangeObserver {
            NotificationCenter.default.removeObserver(observer)
            routeChangeObserver = nil
        }
        if let observer = interruptionObserver {
            NotificationCenter.default.removeObserver(observer)
            interruptionObserver = nil
        }

        guard isActivated else { return }
        isActivated = false

        do {
            try AVAudioSession.sharedInstance().setActive(
                false,
                options: .notifyOthersOnDeactivation
            )
        } catch {
            // Best-effort deactivation
        }
    }

    // MARK: - Route changes

    private func observeRouteChanges() {
        routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            Task { @MainActor [weak self] in
                self?.handleRouteChange(notification)
            }
        }
    }

    private func handleRouteChange(_ notification: Notification) {
        guard let reason = notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
              let changeReason = AVAudioSession.RouteChangeReason(rawValue: reason)
        else { return }

        if changeReason == .oldDeviceUnavailable {
            engine?.pause()
        }
    }

    // MARK: - Interruptions

    private func observeInterruptions() {
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            Task { @MainActor [weak self] in
                self?.handleInterruption(notification)
            }
        }
    }

    private func handleInterruption(_ notification: Notification) {
        guard let typeValue = notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: typeValue)
        else { return }

        switch type {
        case .began:
            engine?.pause()
        case .ended:
            let optionsValue = notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
            let options = AVAudioSession.InterruptionOptions(rawValue: optionsValue)
            if options.contains(.shouldResume) {
                engine?.play()
            }
        @unknown default:
            break
        }
    }
}
#endif
