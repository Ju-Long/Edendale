import AVFoundation
import Foundation

#if !os(macOS)
#if canImport(MediaPlayer)
import MediaPlayer
#endif
#if canImport(UIKit)
import UIKit
#endif

/// Routes volume control through the device's system volume on non-macOS
/// platforms instead of `AVPlayer.volume`. This overrides the silent switch
/// because the `.playback` audio session category treats volume as media
/// output rather than ringer volume.
///
/// On iOS the controller can both read and write the system volume (via a
/// hidden `MPVolumeView` slider). On tvOS and visionOS the system volume is
/// read-only — hardware controls (Siri Remote, Digital Crown) are the sole
/// input.
@MainActor
final class SystemVolumeController {

    private(set) var level: Float

    var onLevelChanged: ((Float) -> Void)?

    private var volumeObservation: NSKeyValueObservation?

    #if os(iOS)
    private var volumeView: MPVolumeView?
    private weak var volumeSlider: UISlider?
    #endif

    init() {
        level = AVAudioSession.sharedInstance().outputVolume
        observeOutputVolume()
        #if os(iOS)
        installSliderIfNeeded()
        #endif
    }

    func setLevel(_ value: Float) {
        let clamped = min(max(value, 0), 1)
        level = clamped
        #if os(iOS)
        installSliderIfNeeded()
        volumeSlider?.value = clamped
        #endif
    }

    // MARK: - Private

    private func observeOutputVolume() {
        volumeObservation = AVAudioSession.sharedInstance().observe(
            \.outputVolume,
            options: [.new]
        ) { [weak self] _, change in
            guard let newValue = change.newValue else { return }
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.level = newValue
                self.onLevelChanged?(newValue)
            }
        }
    }

    #if os(iOS)
    private func installSliderIfNeeded() {
        guard volumeView == nil else { return }
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }),
              let window = scene.windows.first
        else { return }

        let view = MPVolumeView(frame: CGRect(x: -2000, y: -2000, width: 1, height: 1))
        view.alpha = 0.001
        view.isUserInteractionEnabled = false
        window.addSubview(view)
        volumeView = view
        volumeSlider = view.subviews.compactMap { $0 as? UISlider }.first
    }
    #endif
}
#endif
