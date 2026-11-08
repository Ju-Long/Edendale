//
//  PlayerScreen.swift
//  Edendale
//
//  The full-screen playback surface: video underneath, gesture layer on
//  touch platforms, platform-specific input, and transient HUD feedback.
//  Hosted full screen on iOS/visionOS/tvOS and in the "Now Playing" window
//  on macOS, where the side panels dock beside the video.
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

    #if os(visionOS)
    @Environment(\.dismissWindow) private var dismissWindow
    #endif

    #if !os(tvOS)
    @FocusState private var keyboardFocused: Bool
    #endif

    #if os(iOS)
    /// The hinge, window and screen, read for the iPhone Duo's tabletop
    /// layout.
    @State private var fold = PlayerFoldState()
    #endif
    /// The window's top safe-area inset; the tabletop picture starts below it.
    @State private var safeAreaTop: CGFloat = 0

    /// Dismisses the hosting scene (cover or window) and ends the session.
    let exit: () -> Void

    /// The picture above the fold and the controls below it while an iPhone
    /// Duo stands half-open in portrait; `nil` for the full-screen layout.
    private var tabletop: PlayerTabletopLayout? {
        #if os(iOS)
        PlayerTabletopLayout.resolve(fold)
        #else
        nil
        #endif
    }

    /// False while the visionOS theater is open: its own panel, which
    /// follows the viewer around the room, carries the controls then.
    private var controlsInWindow: Bool {
        #if os(visionOS)
        !session.theater.isImmersed
        #else
        true
        #endif
    }

    var body: some View {
        ZStack {
            Theme.background
                .ignoresSafeArea()

            #if os(iOS)
            PlayerFoldReader { fold = $0 }
                .ignoresSafeArea()
                .accessibilityHidden(true)
            #endif

            if let player = session.player,
               let item = session.item, item.scope != nil,
               player.state != .error {
//                let _ = debugPrint("[PlayerScreen] ✅ showing playback — state=\(player.state), url=\(item.url?.lastPathComponent ?? "nil")")
                playback(player: player, item: item)
            } else {
//                let _ = debugPrint("[PlayerScreen] ❌ showing failure — player=\(session.player == nil ? "nil" : "exists") state=\(session.player?.state ?? .idle), item=\(session.item == nil ? "nil" : "exists"), scope=\(session.item?.scope == nil ? "nil" : "exists"), error=\(session.item?.errorMessage ?? "none")")
                failure
            }

            // Over the failure state too, should the file fail to open
            // while the first-run guide is up.
            if let chrome = session.chrome, chrome.guideVisible {
                PlayerGuideView(
                    controls: session.controls,
                    finishTitle: chrome.guideIsFirstRun
                        ? String(localized: "Start Watching")
                        : String(localized: "Done"),
                    onFinish: chrome.dismissGuide
                )
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.25), value: session.chrome?.guideVisible)
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
        // The guide takes the keys while it is up; hand them back after.
        .onChange(of: session.chrome?.guideVisible ?? false) { _, visible in
            if !visible { keyboardFocused = true }
        }
        #endif
        #if os(iOS)
        // The status bar would sit over the picture, which fills the upper
        // half in tabletop.
        .statusBarHidden(tabletop != nil || !(session.chrome?.controlsVisible ?? true))
        .persistentSystemOverlays(
            (session.chrome?.controlsVisible ?? true) ? .automatic : .hidden
        )
        .onChange(of: session.chrome?.autoPiP ?? true, initial: true) { _, enabled in
            pipSource?.automaticallyStartsFromInline = enabled
        }
        .onGeometryChange(for: CGFloat.self) { $0.safeAreaInsets.top } action: { top in
            safeAreaTop = top
        }
        // A new session brings a new chrome model, which starts out of
        // tabletop.
        .onChange(
            of: TabletopBinding(
                chrome: session.chrome.map(ObjectIdentifier.init),
                isTabletop: tabletop != nil
            ),
            initial: true
        ) { _, binding in
            session.chrome?.isTabletop = binding.isTabletop
        }
        #endif
        #if os(tvOS)
        // Focus lives on the actual controls (or the reveal catcher while
        // they're hidden); these commands bubble up from whichever control
        // is focused, so the screen never has to own focus itself.
        .onPlayPauseCommand {
            // Playback waits for the guide to close.
            guard session.chrome?.guideVisible != true else { return }
            session.chrome?.togglePlayPause()
        }
        .onExitCommand { handleExitCommand() }
        #endif
        #if os(visionOS)
        // The theater draws the picture and carries the controls, so the
        // window closes while it is open (see VisionTheaterController).
        // visionOS can pass over a request, or bring the window back, while
        // the space settles, so ask again until the window has gone.
        .task(id: session.theater.wantsPlayerWindowClosed) {
            for _ in 0..<4 {
                guard !Task.isCancelled, session.theater.wantsPlayerWindowClosed else { return }
                session.theater.playerWindowWillClose()
                dismissWindow()
                try? await Task.sleep(for: .seconds(1.5))
            }
        }
        .onAppear {
            session.theater.playerScreenDidAppear()
        }
        #endif
        .onDisappear {
            #if os(visionOS)
            // The theater closed the window and draws the picture itself.
            if session.theater.playerScreenDidDisappear() { return }
            #endif
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
        #if os(macOS)
        // The playlist and adjustments panels dock as a trailing sidebar:
        // the video narrows beside an open panel instead of sitting under it.
        HStack(spacing: 0) {
            playbackLayers(player: player, item: item)
            if let chrome = session.chrome, let panel = chrome.activePanel {
                PlayerDockedPanel(panel: panel, chrome: chrome, player: player, item: item)
                    .transition(.move(edge: .trailing))
            }
        }
        .animation(
            reduceMotion ? nil : .easeInOut(duration: 0.25),
            value: session.chrome?.activePanel
        )
        #else
        playbackLayers(player: player, item: item)
        #endif
    }

    @ViewBuilder
    private func playbackLayers(player: PlaybackEngine, item: PlaybackItem) -> some View {
        let tabletop = tabletop
        ZStack {
            // In tabletop the picture keeps to the upper half. The frame
            // changes rather than the layers, so the video surface carries
            // on through a fold.
            ZStack {
                videoSurface(player: player)
                    .ignoresSafeArea()

                PlayerSubtitleOverlay(
                    engine: player.subtitleEngine,
                    appearance: session.subtitleAppearance,
                    time: CMTime(seconds: player.currentTime.playbackSeconds, preferredTimescale: 60000),
                    videoSize: player.decoder?.mediaInfo?.naturalSize ?? .zero,
                    aspectFill: session.chrome?.aspectFill == true,
                    controlsVisible: session.chrome?.controlsVisible == true && tabletop == nil
                )
                .ignoresSafeArea()

                #if os(visionOS)
                // The picture, subtitles, and controls are in the theater.
                // The window closes then; until it has, it only says where
                // they went instead of showing them twice.
                if session.theater.isImmersed {
                    VisionTheaterRemoteBackdrop(environment: session.theater.environment)
                        .ignoresSafeArea()
                        .transition(.opacity)
                }
                #endif

                #if os(iOS) || os(visionOS)
                if let chrome = session.chrome, !chrome.guideVisible, controlsInWindow {
                    PlayerGestureLayer(chrome: chrome, player: player)
                        .ignoresSafeArea()
                }
                #endif
            }
            // Clear of the camera and status bar above, which the picture
            // would otherwise run under in the shorter upper half.
            .padding(.top, tabletop == nil ? 0 : safeAreaTop)
            .frame(height: tabletop?.foldY)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .ignoresSafeArea()
            // The video's drawable can't follow an animated resize; the
            // picture jumps while the controls fade.
            .animation(nil, value: tabletop)

            // The guide stands in for every control while it is up.
            if let chrome = session.chrome, !chrome.guideVisible, controlsInWindow {
                #if os(iOS)
                PlayerControlsOverlay(
                    chrome: chrome,
                    player: player,
                    item: item,
                    exit: exit,
                    pipSource: pipSource,
                    tabletop: tabletop
                )
                #elseif os(macOS)
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
                   !chrome.panelCoversVideo, !chrome.isScrubbing {
                    VStack {
                        HStack {
                            Spacer()
                            PlayerUpNextView(episode: upcoming, chrome: chrome) {
                                Task { await session.play(episode: upcoming) }
                            }
                            // Tabletop controls sit below the fold, not over
                            // the picture this card shares.
                            .padding(.top, chrome.controlsVisible && tabletop == nil ? upNextControlsInset : 16)
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
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.3), value: tabletop)
        // The guide waits for the viewer's first playback on this device.
        .onAppear { session.chrome?.presentGuideIfFirstRun() }
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
        #elseif os(visionOS)
        if session.theater.isPlayerWindowClosed {
            // visionOS brought the window back while the theater draws the
            // picture; it closes again at once, and must not take frames
            // or the screen from the theater's own view meanwhile.
            Color.black
        } else {
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
            .theaterFeed(session.theater.screenFeed)
        }
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
        guard let chrome = session.chrome, !chrome.guideVisible else { return .ignored }
        let commandPressed = press.modifiers.contains(.command)

        switch press.key {
        case .leftArrow where !commandPressed:
            chrome.skip(.backward)
        case .rightArrow where !commandPressed:
            chrome.skip(.forward)
        case .upArrow:
            if commandPressed {
                adjustBrightness(by: PlayerLogic.levelStep, chrome: chrome)
            } else {
                chrome.adjustVolume(by: PlayerLogic.levelStep)
            }
        case .downArrow:
            if commandPressed {
                adjustBrightness(by: -PlayerLogic.levelStep, chrome: chrome)
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
        #if os(macOS)
        // A docked panel stays open through clicks on the video, so Escape
        // closes it before it closes the player.
        case .escape where chrome.activePanel != nil:
            chrome.closePanel()
        #endif
        case .escape:
            exit()
        default:
            return .ignored
        }
        return .handled
    }

    /// ⌘↑ / ⌘↓ change the screen's brightness, as the swipe does on iPhone
    /// and iPad. Without one to change (visionOS) they brighten or darken
    /// the picture itself.
    private func adjustBrightness(by delta: Float, chrome: PlayerChromeModel) {
        #if os(iOS) || os(macOS)
        let level = PlayerLogic.adjustedLevel(Float(ScreenBrightness.level), by: delta)
        if ScreenBrightness.set(Double(level)) {
            chrome.showHUD(.brightness(Double(level)))
            return
        }
        #endif
        let step = delta > 0 ? VideoAdjustment.brightness.step : -VideoAdjustment.brightness.step
        videoAdjustment.set(.brightness, to: videoAdjustment.values[.brightness] + step)
        chrome.showHUD(.brightness(Double(videoAdjustment.effectiveValues[.brightness] / 2)))
    }
    #endif

    // MARK: - tvOS remote

    #if os(tvOS)
    /// Menu peels back one layer at a time: the guide or a panel,
    /// scrub/timeline/HUD, controls, then finally the player itself.
    private func handleExitCommand() {
        guard let chrome = session.chrome else {
            exit()
            return
        }
        if chrome.guideVisible {
            chrome.dismissGuide()
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

#if os(iOS)
/// The tabletop flag and the chrome model it belongs to, so a model that
/// replaces the old one mid-fold picks the flag up too.
private struct TabletopBinding: Equatable {
    let chrome: ObjectIdentifier?
    let isTabletop: Bool
}
#endif

// MARK: - Docked panel (macOS)

#if os(macOS)
/// The playlist or adjustments panel as a trailing sidebar of the player
/// window: an opaque dim column with a hairline edge, beside the video
/// rather than over it. Clicks on the video leave it open; its close chip,
/// the toolbar chip that opened it, or Escape close it.
private struct PlayerDockedPanel: View {
    let panel: PlayerChromeModel.SidePanel
    let chrome: PlayerChromeModel
    let player: PlaybackEngine
    let item: PlaybackItem

    var body: some View {
        PlayerPanelContent(panel: panel, chrome: chrome, player: player, item: item)
            .frame(width: 340)
            .frame(maxHeight: .infinity)
            // Nothing in the panel may draw over the video beside it.
            .clipped()
            .background { Theme.surfaceLow.ignoresSafeArea() }
            .overlay(alignment: .leading) {
                Rectangle()
                    .fill(Theme.hairline)
                    .frame(width: 1)
                    .ignoresSafeArea()
                    .accessibilityHidden(true)
            }
            // A sibling of the video, not a modal layer: the transport
            // controls stay in the reading order beside it.
            .accessibilityElement(children: .contain)
    }
}
#endif

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
