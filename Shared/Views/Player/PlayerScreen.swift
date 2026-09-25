//
//  PlayerScreen.swift
//  Edendale
//
//  The full-screen playback surface: video underneath, gesture layer on
//  touch platforms, platform-specific input, and transient HUD feedback.
//  Hosted full screen on iOS/visionOS/tvOS and in the "Now Playing" window
//  on macOS.
//

import CoreMedia
import SwiftUI

struct PlayerScreen: View {
    @Environment(PlayerSession.self) private var session
    @Environment(VideoAdjustmentController.self) private var videoAdjustment
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    #if os(iOS) || os(macOS)
    private var pipSource: SampleBufferPiPSource? { session.player?.pipSource }
    #endif

    #if !os(tvOS)
    @FocusState private var keyboardFocused: Bool
    #endif

    /// Dismisses the hosting scene (cover or window) and ends the session.
    let exit: () -> Void

    var body: some View {
        ZStack {
            Theme.background
                .ignoresSafeArea()

            if let player = session.player,
               let item = session.item, item.scope != nil,
               player.state != .error {
//                let _ = debugPrint("[PlayerScreen] ✅ showing playback — state=\(player.state), url=\(item.url?.lastPathComponent ?? "nil")")
                playback(player: player, item: item)
            } else {
//                let _ = debugPrint("[PlayerScreen] ❌ showing failure — player=\(session.player == nil ? "nil" : "exists") state=\(session.player?.state ?? .idle), item=\(session.item == nil ? "nil" : "exists"), scope=\(session.item?.scope == nil ? "nil" : "exists"), error=\(session.item?.errorMessage ?? "none")")
                failure
            }
        }
        .preferredColorScheme(.dark)
        #if !os(tvOS)
        .focusable()
        .focused($keyboardFocused)
        .focusEffectDisabled()
        .onKeyPress(
            keys: [.leftArrow, .rightArrow, .upArrow, .downArrow, "f", "m", .space, .escape],
            phases: [.down, .repeat],
            action: handleKeyPress
        )
        .onAppear { keyboardFocused = true }
        #endif
        #if os(iOS)
        .statusBarHidden(!(session.chrome?.controlsVisible ?? true))
        .persistentSystemOverlays(
            (session.chrome?.controlsVisible ?? true) ? .automatic : .hidden
        )
        .onChange(of: session.chrome?.autoPiP ?? true, initial: true) { _, enabled in
            pipSource?.automaticallyStartsFromInline = enabled
        }
        #endif
        #if os(tvOS)
        // Focus lives on the actual controls (or the reveal catcher while
        // they're hidden); these commands bubble up from whichever control
        // is focused, so the screen never has to own focus itself.
        .onPlayPauseCommand {
            session.chrome?.togglePlayPause()
        }
        .onExitCommand { handleExitCommand() }
        #endif
        .onDisappear {
            session.surfaceDidDetach()
            // The host closed underneath us (macOS red button or cover
            // dismissal), so release the player and its file access. On
            // visionOS, changing a packed-video override can swap the decoder
            // surface for AVKit inside the same cover; that is not a session
            // dismissal.
            #if os(visionOS)
            if session.isPresented, session.visionNativeItem == nil {
                session.end()
            }
            #else
            if session.isPlayerPresented { session.end() }
            #endif
        }
    }

    // MARK: - Layers

    @ViewBuilder
    private func playback(player: PlaybackEngine, item: PlaybackItem) -> some View {
        ZStack {
            videoSurface(player: player)
                .ignoresSafeArea()

            PlayerSubtitleOverlay(
                engine: player.subtitleEngine,
                time: CMTime(seconds: player.currentTime.playbackSeconds, preferredTimescale: 60000),
                videoSize: player.decoder?.mediaInfo?.naturalSize ?? .zero,
                aspectFill: session.chrome?.aspectFill == true,
                controlsVisible: session.chrome?.controlsVisible == true
            )
            .ignoresSafeArea()

            #if os(iOS) || os(visionOS)
            if let chrome = session.chrome {
                PlayerGestureLayer(chrome: chrome, player: player)
                    .ignoresSafeArea()
            }
            #endif

            if let chrome = session.chrome {
                #if os(iOS) || os(macOS)
                PlayerControlsOverlay(
                    chrome: chrome,
                    player: player,
                    item: item,
                    exit: exit,
                    pipSource: pipSource
                )
                #else
                PlayerControlsOverlay(
                    chrome: chrome,
                    player: player,
                    item: item,
                    exit: exit
                )
                #endif

                #if os(tvOS)
                // Lightweight remote-seek timeline; the full controls have
                // their own timeline in the bottom bar, so never show both.
                if !chrome.controlsVisible, chrome.timelineVisible || chrome.isScrubbing {
                    PlayerTVTimelineOverlay(chrome: chrome, player: player)
                        .transition(.opacity)
                }
                #endif

                if let upcoming = chrome.upcomingEpisode,
                   chrome.activePanel == nil, !chrome.isScrubbing {
                    VStack {
                        HStack {
                            Spacer()
                            PlayerUpNextView(episode: upcoming) {
                                Task { await session.play(episode: upcoming) }
                            }
                            .padding(.top, chrome.controlsVisible ? upNextControlsInset : 16)
                            .padding(.trailing, upNextTrailingInset)
                        }
                        Spacer()
                    }
                    .allowsHitTesting(true)
                    .transaction { if reduceMotion { $0.animation = nil } }
                }

                PlayerHUDView(chrome: chrome)
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.3), value: session.chrome?.upcomingEpisode?.id)
        #if os(tvOS)
        .animation(.easeInOut(duration: 0.2), value: session.chrome?.timelineVisible)
        #endif
    }

    // Keep the card below the visible toolbar, with the same safe margin as
    // the native controls. It stays reachable when a remote reveals controls.
    private var upNextControlsInset: CGFloat {
        #if os(tvOS)
        180
        #else
        88
        #endif
    }

    private var upNextTrailingInset: CGFloat {
        #if os(tvOS)
        60
        #else
        20
        #endif
    }

    /// The Metal video surface. Aspect mode (fit/fill) is handled natively
    /// by the `EnhancedVideoView` renderer, so no scale-and-clip hack is
    /// needed here.
    @ViewBuilder
    private func videoSurface(player: PlaybackEngine) -> some View {
        #if os(iOS) || os(macOS)
        EnhancedVideoPlayer(
            ringBuffer: player.ringBuffer,
            presentationTime: player.videoPresentationTime,
            aspectMode: (session.chrome?.aspectFill == true) ? .fill : .fit,
            isPaused: !player.isPlaying,
            enhancementPipeline: player.enhancementPipeline,
            frameInterpolator: player.frameInterpolator,
            sourceFrameRate: player.sourceFrameRate,
            pipSource: player.pipSource,
            onSurfaceReady: { _ in session.surfaceDidAttach() }
        )
        #else
        EnhancedVideoPlayer(
            ringBuffer: player.ringBuffer,
            presentationTime: player.videoPresentationTime,
            aspectMode: (session.chrome?.aspectFill == true) ? .fill : .fit,
            isPaused: !player.isPlaying,
            enhancementPipeline: player.enhancementPipeline,
            frameInterpolator: player.frameInterpolator,
            sourceFrameRate: player.sourceFrameRate,
            onSurfaceReady: { _ in session.surfaceDidAttach() }
        )
        #endif
    }

    private var failure: some View {
        ZStack(alignment: .topLeading) {
            PlaybackErrorView(
                message: session.item?.errorMessage
                    ?? String(localized: "This title could not be opened.")
            )
            PlayerExitButton(action: exit)
                .padding(24)
        }
    }

    // MARK: - Keyboard

    #if !os(tvOS)
    private func handleKeyPress(_ press: KeyPress) -> KeyPress.Result {
        guard let chrome = session.chrome else { return .ignored }
        let commandPressed = press.modifiers.contains(.command)

        switch press.key {
        case .leftArrow where !commandPressed:
            chrome.seek(bySeconds: -10)
        case .rightArrow where !commandPressed:
            chrome.seek(bySeconds: 10)
        case .upArrow:
            if commandPressed {
                let current = videoAdjustment.values[.brightness]
                videoAdjustment.set(.brightness, to: current + VideoAdjustment.brightness.step)
                chrome.showHUD(.brightness(Double(videoAdjustment.effectiveValues[.brightness] / 2)))
            } else {
                chrome.adjustVolume(by: PlayerLogic.levelStep)
            }
        case .downArrow:
            if commandPressed {
                let current = videoAdjustment.values[.brightness]
                videoAdjustment.set(.brightness, to: current - VideoAdjustment.brightness.step)
                chrome.showHUD(.brightness(Double(videoAdjustment.effectiveValues[.brightness] / 2)))
            } else {
                chrome.adjustVolume(by: -PlayerLogic.levelStep)
            }
        #if os(macOS)
        case "f" where !commandPressed && press.phase == .down:
            NSApp.keyWindow?.toggleFullScreen(nil)
        #endif
        case "m" where !commandPressed && press.phase == .down:
            chrome.toggleMute()
        case .space:
            chrome.togglePlayPause()
        case .escape:
            exit()
        default:
            return .ignored
        }
        return .handled
    }
    #endif

    // MARK: - tvOS remote

    #if os(tvOS)
    /// Menu peels back one layer at a time: panel, scrub/timeline/HUD,
    /// controls, then finally the player itself.
    private func handleExitCommand() {
        guard let chrome = session.chrome else {
            exit()
            return
        }
        if chrome.activePanel != nil {
            chrome.closePanel()
            return
        }
        if chrome.dismissRemotePresentation() { return }
        if chrome.controlsVisible {
            chrome.hideControls()
            return
        }
        exit()
    }
    #endif
}

// MARK: - Exit button

/// Top-left ✕ control shared by the overlay and the failure state; closes
/// the player.
struct PlayerExitButton: View {
    let action: () -> Void
    var onFocus: (() -> Void)? = nil

    var body: some View {
        Button(action: action) {
            Image(.xmark)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .glassBackground(in: Capsule())
        }
        .playerChipStyle(onFocus: onFocus)
        .accessibilityLabel("Close Player")
    }
}

// MARK: - Failure state

/// Shown in place of the player when a file can't be resolved or accessed.
struct PlaybackErrorView: View {
    let message: String

    var body: some View {
        VStack(spacing: 16) {
            Image(.filmCircleExclamation)
                .font(.system(size: 44))
                .foregroundStyle(Theme.surfaceHigh)
                .accessibilityHidden(true)
            Text("Unable to Play")
                .font(Typography.headlineMD)
                .textCase(.uppercase)
                .foregroundStyle(Theme.textPrimary)
            Text(message)
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: 420)
        }
        .padding(48)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        // Heading and cause are one message.
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Chip button style

/// Shared look for the player's floating controls: quiet at rest, gold on
/// hover/focus, slightly sunken when pressed.
///
/// Apply via `.playerChipStyle()`, never `.buttonStyle` directly:
/// `@Environment(\.isFocused)` never updates inside a ButtonStyle on tvOS,
/// so the modifier tracks focus with `@FocusState` outside the button and
/// hands it to the style (same pattern as `archiveButtonStyle`).
struct PlayerChipButtonStyle: ButtonStyle {
    var isFocused = false
    /// A latched control (open panel, engaged toggle) also reads gold. The
    /// color is decided here rather than on the chip's label so a focus
    /// highlight can't be overridden by the label's own `foregroundStyle`.
    var isActive = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(isFocused || isActive ? Theme.gold : Theme.textPrimary)
            .scaleEffect(configuration.isPressed ? 0.94 : (isFocused ? 1.06 : 1))
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
            .animation(.easeOut(duration: 0.12), value: isFocused)
    }
}

extension View {
    /// Player chip chrome with a focus highlight that works on tvOS.
    /// `isActive` latches the gold color for open panels or engaged toggles;
    /// `onFocus` fires when the button gains focus — the controls overlay
    /// uses it to keep the auto-hide countdown from expiring mid-navigation.
    func playerChipStyle(isActive: Bool = false, onFocus: (() -> Void)? = nil) -> some View {
        modifier(PlayerChipFocusModifier(isActive: isActive, onFocus: onFocus))
    }
}

private struct PlayerChipFocusModifier: ViewModifier {
    var isActive = false
    var onFocus: (() -> Void)?
    @FocusState private var isFocused: Bool

    func body(content: Content) -> some View {
        content
            .focused($isFocused)
            .buttonStyle(PlayerChipButtonStyle(isFocused: isFocused, isActive: isActive))
            .onChange(of: isFocused) { _, focused in
                if focused { onFocus?() }
            }
    }
}
