import Foundation
import MediaPlayer

/// Bridges `PlaybackEngine` state to `MPNowPlayingInfoCenter` and
/// `MPRemoteCommandCenter` so the lock screen, Control Center, and external
/// displays show the correct title, artwork, elapsed time, and transport.
@MainActor
final class NowPlayingBridge {
    private weak var engine: PlaybackEngine?
    private var registeredCommands = false
    private var title: String?
    private var artworkURL: URL?
    /// Seconds the skip commands offer; see `setSkipIntervals`.
    private var skipIntervals = (backward: 10, forward: 10)

    /// Offers the viewer's skip lengths to the Lock Screen, Control Center,
    /// and headphone controls: immediately while attached, otherwise when
    /// the next playback registers its commands.
    func setSkipIntervals(backward: Int, forward: Int) {
        skipIntervals = (backward, forward)
        guard registeredCommands else { return }
        applySkipIntervals()
    }

    func attach(to engine: PlaybackEngine, title: String?, artworkURL: URL?) {
        self.engine = engine
        self.title = title
        self.artworkURL = artworkURL
        registerRemoteCommands()
        updateNowPlayingInfo()
    }

    func detach() {
        engine = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        if registeredCommands {
            unregisterRemoteCommands()
        }
    }

    func updateNowPlayingInfo() {
        guard let engine else { return }

        var info = [String: Any]()
        info[MPMediaItemPropertyTitle] = title ?? "Edendale"
        info[MPMediaItemPropertyMediaType] = MPNowPlayingInfoMediaType.video.rawValue

        if let d = engine.duration {
            info[MPMediaItemPropertyPlaybackDuration] = d.playbackSeconds
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = engine.currentTime.playbackSeconds
        info[MPNowPlayingInfoPropertyPlaybackRate] = engine.isPlaying ? 1.0 : 0.0
        info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = 1.0

        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    func updateElapsedTime() {
        guard var info = MPNowPlayingInfoCenter.default().nowPlayingInfo else {
            updateNowPlayingInfo()
            return
        }
        guard let engine else { return }

        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = engine.currentTime.playbackSeconds
        info[MPNowPlayingInfoPropertyPlaybackRate] = engine.isPlaying ? 1.0 : 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // MARK: - Remote commands

    private func registerRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.isEnabled = true
        center.playCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            return MainActor.assumeIsolated {
                self.engine?.play()
                self.updateElapsedTime()
                return .success
            }
        }

        center.pauseCommand.isEnabled = true
        center.pauseCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            return MainActor.assumeIsolated {
                self.engine?.pause()
                self.updateElapsedTime()
                return .success
            }
        }

        center.togglePlayPauseCommand.isEnabled = true
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            return MainActor.assumeIsolated {
                self.engine?.togglePlayPause()
                self.updateElapsedTime()
                return .success
            }
        }

        center.skipForwardCommand.isEnabled = true
        center.skipForwardCommand.addTarget { [weak self] event in
            guard let self,
                  let cmd = event as? MPSkipIntervalCommandEvent
            else { return .commandFailed }
            return MainActor.assumeIsolated {
                self.engine?.seek(by: .seconds(cmd.interval))
                self.updateElapsedTime()
                return .success
            }
        }

        center.skipBackwardCommand.isEnabled = true
        center.skipBackwardCommand.addTarget { [weak self] event in
            guard let self,
                  let cmd = event as? MPSkipIntervalCommandEvent
            else { return .commandFailed }
            return MainActor.assumeIsolated {
                self.engine?.seek(by: .seconds(-cmd.interval))
                self.updateElapsedTime()
                return .success
            }
        }

        center.changePlaybackPositionCommand.isEnabled = true
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self,
                  let cmd = event as? MPChangePlaybackPositionCommandEvent
            else { return .commandFailed }
            return MainActor.assumeIsolated {
                self.engine?.seek(to: .seconds(cmd.positionTime))
                self.updateElapsedTime()
                return .success
            }
        }

        registeredCommands = true
        applySkipIntervals()
    }

    private func applySkipIntervals() {
        let center = MPRemoteCommandCenter.shared()
        center.skipBackwardCommand.preferredIntervals = [NSNumber(value: skipIntervals.backward)]
        center.skipForwardCommand.preferredIntervals = [NSNumber(value: skipIntervals.forward)]
    }

    private func unregisterRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.removeTarget(nil)
        center.pauseCommand.removeTarget(nil)
        center.togglePlayPauseCommand.removeTarget(nil)
        center.skipForwardCommand.removeTarget(nil)
        center.skipBackwardCommand.removeTarget(nil)
        center.changePlaybackPositionCommand.removeTarget(nil)
        registeredCommands = false
    }
}
