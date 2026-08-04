//
//  PlayerScreen.swift
//  Edendale
//
//  The full-screen playback surface: video underneath, gesture layer on
//  touch platforms, platform-specific input, and transient HUD feedback.
//  Hosted full screen on iOS/visionOS/tvOS and in the "Now Playing" window
//  on macOS.
//

import SwiftUI
import SwiftVLC

struct PlayerScreen: View {
    @Environment(PlayerSession.self) private var session

    #if os(iOS) || os(macOS)
    @State private var pipController: PiPController?
    #endif

    #if os(iOS)
    @Environment(\.scenePhase) private var scenePhase
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
                playback(player: player, item: item)
            } else {
                failure
            }
        }
        .preferredColorScheme(.dark)
        #if !os(tvOS)
        .focusable()
        .focused($keyboardFocused)
        .focusEffectDisabled()
        .onKeyPress(
            keys: [.leftArrow, .rightArrow, .upArrow, .downArrow, "m", .space, .escape],
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
        // SwiftVLC always arms auto-PiP (`canStartPictureInPictureAutomatically
        // FromInline`). When the user has turned the preference off, cancel a
        // window the system opened as we left the foreground. Gating on
        // `scenePhase != .active` leaves a foreground button-press PiP alone —
        // that fires while the app is still active, so it never trips here.
        .onChange(of: pipController?.isActive ?? false) { _, active in
            guard active,
                  scenePhase != .active,
                  session.chrome?.autoPiP == false
            else { return }
            pipController?.stop()
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
            // The host closed underneath us (macOS red button or cover
            // dismissal), so release the player and its file access. On
            // visionOS, changing a packed-video override can swap this VLC
            // surface for AVKit inside the same cover; that is not a session
            // dismissal.
            #if os(visionOS)
            if session.isPresented, session.visionNativeItem == nil {
                session.end()
            }
            #else
            if session.isPresented { session.end() }
            #endif
        }
    }

    // MARK: - Layers

    @ViewBuilder
    private func playback(player: Player, item: PlaybackItem) -> some View {
        ZStack {
            GeometryReader { geo in
                let scale = videoFillScale(player: player, container: geo.size)
                videoSurface(player: player)
                    .scaleEffect(scale)
                    .frame(width: geo.size.width, height: geo.size.height)
                    .clipped()
                    .animation(.easeInOut(duration: 0.25), value: scale)
            }
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
                    pipController: pipController
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

                PlayerHUDView(chrome: chrome)
            }
        }
        #if os(tvOS)
        .animation(.easeInOut(duration: 0.2), value: session.chrome?.timelineVisible)
        #endif
    }

    /// The raw VLC video surface. Fit/fill is layered on top by the caller
    /// as a scale-and-clip transform, so this stays a plain host view.
    @ViewBuilder
    private func videoSurface(player: Player) -> some View {
        #if os(iOS) || os(macOS)
        VideoPlayer(
            player: player,
            pipController: $pipController,
            onSurfaceReady: session.surfaceDidAttach
        )
        #else
        VideoPlayer(
            player: player,
            onSurfaceReady: session.surfaceDidAttach
        )
        #endif
    }

    /// Scale that makes the letterboxed surface cover `container` when the
    /// user picks "Fill"; 1 (fit) otherwise or until the video size is known.
    private func videoFillScale(player: Player, container: CGSize) -> CGFloat {
        guard session.chrome?.aspectFill == true else { return 1 }
        return PlayerLogic.aspectFillScale(
            container: container,
            video: videoNaturalSize(player)
        )
    }

    /// The active video track's coded pixel size, or `.zero` before tracks
    /// resolve (which yields a fit scale of 1).
    private func videoNaturalSize(_ player: Player) -> CGSize {
        let track = player.videoTracks.first { $0.isSelected }
            ?? player.videoTracks.first
        guard let width = track?.width, let height = track?.height else {
            return .zero
        }
        return CGSize(width: width, height: height)
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
                chrome.adjustBrightness(by: PlayerLogic.levelStep)
            } else {
                chrome.adjustVolume(by: PlayerLogic.levelStep)
            }
        case .downArrow:
            if commandPressed {
                chrome.adjustBrightness(by: -PlayerLogic.levelStep)
            } else {
                chrome.adjustVolume(by: -PlayerLogic.levelStep)
            }
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
