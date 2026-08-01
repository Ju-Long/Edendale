//
//  PlayerControlsOverlay.swift
//  Edendale
//
//  Auto-hiding chrome over the video: back + title + tools on top, big
//  play/pause in the center, timeline along the bottom, side panels on the
//  trailing edge. Taps on empty overlay space hide the controls; on tvOS an
//  invisible focus catcher brings them back.
//

import SwiftUI
import SwiftVLC

struct PlayerControlsOverlay: View {
    let chrome: PlayerChromeModel
    let player: Player
    let item: PlaybackItem
    let exit: () -> Void

    #if os(iOS) || os(macOS)
    let pipController: PiPController?
    #endif

    #if os(tvOS)
    /// Re-seeded onto the play/pause control every time the chrome reappears.
    /// The reveal catcher otherwise hands focus back in an undefined spot, so
    /// Up couldn't reliably climb to the top-bar tools; pinning focus to the
    /// center on each reveal — with each row wrapped in its own `focusSection`
    /// — makes Up/Down move predictably between top tools, center, and timeline.
    private enum ControlFocus: Hashable {
        case center
        /// The top-bar chip owning a side panel. Focus collapses back onto it
        /// whenever that panel is dismissed.
        case tool(PlayerChromeModel.SidePanel)
        /// The invisible strip along an open panel's leading edge.
        case panelEscape
    }
    @FocusState private var focusedControl: ControlFocus?
    /// Siri Remote touch reader for the press-and-hold speed gesture. Lives
    /// for the whole session; created and torn down with the overlay.
    @State private var remoteInput: TVRemoteInput?
    #endif

    var body: some View {
        ZStack {
            #if os(macOS)
            // Clicks on empty space toggle the chrome (touch platforms do
            // this in the gesture layer).
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture { chrome.toggleControls() }
            #endif

            if chrome.controlsVisible {
                scrims
                controls
                    .transition(.opacity)
                    #if os(tvOS)
                    .onAppear { seedFocus() }
                    #endif
            }

            #if os(tvOS)
            if !chrome.controlsVisible {
                revealCatcher
            }
            #endif

            panelHost
        }
        .animation(.easeInOut(duration: 0.2), value: chrome.controlsVisible)
        .animation(.easeInOut(duration: 0.25), value: chrome.activePanel)
        #if os(tvOS)
        .onAppear {
            if remoteInput == nil { remoteInput = TVRemoteInput(chrome: chrome) }
        }
        .onDisappear {
            remoteInput?.invalidate()
            remoteInput = nil
        }
        // However a panel is dismissed — Menu, its header chip, or a left move
        // onto the escape guide — focus collapses back onto the tool that owns
        // it. Left to itself the focus engine picks an arbitrary control once
        // the panel (and whatever inside it held focus) leaves the hierarchy.
        .onChange(of: chrome.activePanel) { previous, current in
            guard current == nil, let previous else { return }
            Task { @MainActor in focusedControl = .tool(previous) }
        }
        #endif
    }

    #if os(tvOS)
    /// Focus the center control on the next runloop tick — setting it inside
    /// `onAppear` synchronously lands before the focus system is ready to
    /// accept it, and the move is silently dropped.
    private func seedFocus() {
        Task { @MainActor in focusedControl = .center }
    }
    #endif

    // MARK: - Chrome

    private var controls: some View {
        #if os(tvOS)
        // Each row is its own focus section, and the center button sits in
        // the layout flow (not an overlapping `.overlay`, which confuses
        // directional focus) so Up/Down step cleanly between the three tiers.
        VStack(spacing: 0) {
            topBar
                .focusSection()
            Spacer()
            centerButton
            Spacer()
            bottomBar
                .focusSection()
        }
        .padding(60)
        #else
        VStack(spacing: 0) {
            topBar
            Spacer()
            bottomBar
        }
        .overlay { centerButton }
        .padding(20)
        #endif
    }

    /// Legibility gradients behind the top and bottom control rows.
    private var scrims: some View {
        VStack(spacing: 0) {
            LinearGradient(
                stops: [
                    .init(color: Theme.background, location: 0),
                    .init(color: .clear, location: 1)
                ],
                startPoint: .top, endPoint: .bottom
            )
            .frame(height: 160)
            Spacer()
            LinearGradient(
                stops: [
                    .init(color: .clear, location: 0),
                    .init(color: Theme.background, location: 1)
                ],
                startPoint: .top, endPoint: .bottom
            )
            .frame(height: 160)
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }

    // MARK: - Top bar

    private var topBar: some View {
        ZStack {
            HStack(alignment: .center) {
                PlayerExitButton(action: exit, onFocus: chipDidFocus)
                Spacer()
                trailingTools
            }

            titleBlock
                .offset(y: 42)
                .frame(maxWidth: 480)
        }
    }

    /// Focus moving between controls counts as activity: restart the
    /// auto-hide countdown so the chrome never vanishes mid-navigation.
    private func chipDidFocus() {
        #if os(tvOS)
        // Every control that reports focus here sits outside the side panels,
        // so focus landing on one means it navigated out of an open panel:
        // dismiss it rather than stranding focus on a chip the panel covers.
        // `closePanel` restarts the auto-hide countdown too.
        chrome.closePanel()
        #else
        chrome.showControls()
        #endif
    }

    private var titleBlock: some View {
        VStack(spacing: 2) {
            Text(item.displayTitle)
                .font(Typography.titleLG)
                .foregroundStyle(Theme.textPrimary)
                .lineLimit(1)
            if let subtitle = item.subtitle {
                Text(subtitle)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(1)
            }
        }
        .allowsHitTesting(false)
    }

    private var trailingTools: some View {
        HStack(spacing: 12) {
            #if os(iOS) || os(macOS)
            PlayerIconChip(
                icon: .pictureInPicture,
               isActive: pipController?.isActive == true,
               onFocus: chipDidFocus
            ) {
                pipController?.toggle()
            }
            .disabled(pipController?.isPossible != true)
            .accessibilityLabel("Picture in Picture")
            #endif

            #if os(iOS)
            PlayerIconChip(
                icon: chrome.isOrientationLocked ? .mobileRotateLock : .mobileRotateUnlock,
                isActive: chrome.isOrientationLocked,
                onFocus: chipDidFocus
            ) {
                chrome.toggleOrientationLock()
            }
            #endif

            PlayerIconChip(
                icon: .listTree,
                isActive: chrome.activePanel == .playlist,
                onFocus: chipDidFocus
            ) {
                chrome.openPanel(.playlist)
            }
            #if os(tvOS)
            .focused($focusedControl, equals: .tool(.playlist))
            #endif

            PlayerIconChip(
                icon: .sidebarRight,
                isActive: chrome.activePanel == .settings,
                onFocus: chipDidFocus
            ) {
                chrome.openPanel(.settings)
            }
            #if os(tvOS)
            .focused($focusedControl, equals: .tool(.settings))
            #endif
        }
    }

    // MARK: - Center

    @ViewBuilder
    private var centerButton: some View {
        if player.state == .opening || player.state == .buffering {
            ProgressView()
                .controlSize(.large)
                .tint(Theme.gold)
        } else {
            Button {
                chrome.togglePlayPause()
            } label: {
                Image(player.isPlaying ? .pause : .play)
                    .font(.system(size: 34, weight: .bold))
                    .frame(width: 88, height: 88)
                    .glassBackground(in: Circle())
                    .contentShape(Circle())
            }
            .playerChipStyle(onFocus: chipDidFocus)
            #if os(tvOS)
            .focused($focusedControl, equals: .center)
            #endif
        }
    }

    // MARK: - Bottom bar

    private var bottomBar: some View {
        HStack(spacing: 16) {
            Text(PlayerLogic.timestamp(displayedTime))
                .font(Typography.bodySM)
                .monospacedDigit()
                .foregroundStyle(Theme.textPrimary)

            PlayerTimeline(chrome: chrome, player: player)

            Text(PlayerLogic.timestamp(player.duration ?? .zero))
                .font(Typography.bodySM)
                .monospacedDigit()
                .foregroundStyle(Theme.textSecondary)
        }
    }

    private var displayedTime: Double {
        if chrome.isScrubbing, let duration = player.duration {
            return chrome.scrubPosition * duration.playbackSeconds
        }
        return player.currentTime.playbackSeconds
    }

    // MARK: - Side panels

    @ViewBuilder
    private var panelHost: some View {
        GeometryReader { proxy in
            ZStack(alignment: .trailing) {
                // Tap anywhere outside the panel to dismiss it.
                if chrome.activePanel != nil {
                    Color.clear
                        .contentShape(Rectangle())
                        .onTapGesture { chrome.closePanel() }
                }

                #if os(tvOS)
                if chrome.activePanel != nil {
                    panelEscapeGuide(trailingInset: proxy.safeAreaInsets.trailing)
                }
                #endif

                // Keyed by panel identity so switching directly between the
                // playlist and settings cross-slides instead of hard-swapping;
                // the move transition slides each panel in/out along the
                // trailing edge, animated by the overlay's `activePanel` block.
                if let panel = chrome.activePanel {
                    panelContent(panel)
                        .padding(.vertical, proxy.safeAreaInsets.top)
                        .padding(.trailing, proxy.safeAreaInsets.trailing)
                        .frame(width: panelWidth + proxy.safeAreaInsets.trailing)
                        .frame(maxHeight: .infinity)
                        .glassBackground(in: Rectangle())
                        #if os(tvOS)
                        // Group the panel so vertical moves keep stepping
                        // through its own rows. Without a section the top bar
                        // — a section itself — outranks the row directly above
                        // and Up leaves the panel (dismissing it) mid-list.
                        .focusSection()
                        #endif
                        .id(panel)
                        .transition(.move(edge: .trailing).combined(with: .opacity))
                }
            }
            .ignoresSafeArea()
        }
    }

    @ViewBuilder
    private func panelContent(_ panel: PlayerChromeModel.SidePanel) -> some View {
        switch panel {
        case .settings:
            PlayerSettingsPanel(chrome: chrome, player: player, item: item)
        case .playlist:
            PlayerPlaylistPanel(chrome: chrome, item: item)
        }
    }

    private var panelWidth: CGFloat {
        #if os(tvOS)
        520
        #else
        340
        #endif
    }

    #if os(tvOS)
    /// Invisible focus target hugging an open panel's leading edge: moving
    /// left out of the panel lands here and dismisses it.
    ///
    /// The focus engine always takes the *nearest* candidate in the direction
    /// of travel, so a control that genuinely sits to the left inside the
    /// panel — the −/+ speed chips, the Fit/Fill segments — wins over this
    /// strip and the panel stays open. Focus reaching the guide therefore
    /// means the move had nowhere left to go inside the panel.
    ///
    /// Reading the remote directly can't make that distinction: an
    /// `onMoveCommand` attached to the panel fires even when the focus engine
    /// *does* move focus within it, so it would dismiss on every left press.
    private func panelEscapeGuide(trailingInset: CGFloat) -> some View {
        Color.clear
            .frame(width: 12)
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .focusable()
            .focused($focusedControl, equals: .panelEscape)
            .focusEffectDisabled()
            .accessibilityHidden(true)
            .padding(.trailing, panelWidth + trailingInset)
            .onChange(of: focusedControl) { _, focus in
                if focus == .panelEscape { chrome.closePanel() }
            }
    }
    #endif

    // MARK: - tvOS reveal

    #if os(tvOS)
    /// Invisible focused surface shown while the chrome is hidden. It owns
    /// the remote while nothing else is on screen: a left/right *swipe* seeks
    /// (with the lightweight timeline overlay), up/select reveal the controls,
    /// down peeks the timeline. While the controls are visible this view is
    /// gone, so the same swipes move focus between the actual buttons instead.
    /// A left/right press-and-*hold* is read separately by `TVRemoteInput`,
    /// which drives slow/fast playback for as long as the thumb rests there.
    private var revealCatcher: some View {
        Button {
            chrome.showControls()
        } label: {
            Color.clear
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .contentShape(Rectangle())
        }
        .buttonStyle(RevealCatcherButtonStyle())
        .onMoveCommand { direction in
            switch direction {
            case .left:
                chrome.remoteSeek(bySeconds: -10)
            case .right:
                chrome.remoteSeek(bySeconds: 10)
            case .down:
                chrome.showTimeline()
            case .up:
                chrome.showControls()
            @unknown default:
                break
            }
        }
    }
    #endif
}

#if os(tvOS)
/// Renders only the label so the full-screen reveal catcher stays invisible
/// while focused — tvOS's built-in styles paint a focus platter over it.
private struct RevealCatcherButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
    }
}
#endif

// MARK: - Chips

/// Circular icon button used in the player's top toolbar.
struct PlayerIconChip: View {
    let icon: ImageResource
    var isActive = false
    var onFocus: (() -> Void)? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(icon)
                .font(.system(size: 15, weight: .bold))
                .frame(width: 40, height: 40)
                .glassBackground(in: Circle())
                .overlay {
                    if isActive {
                        Circle().strokeBorder(Theme.gold, lineWidth: 1.5)
                    }
                }
                .shadow(color: isActive ? Theme.goldGlow : .clear, radius: 8)
                .contentShape(Circle())
        }
        // Color (gold when focused or active) is owned by the chip style, so
        // the focus highlight isn't overridden by a label-level foreground.
        .playerChipStyle(isActive: isActive, onFocus: onFocus)
    }
}

/// Capsule text button — stand-in for toolbar actions whose Font Awesome
/// icons aren't in the asset catalog yet.
struct PlayerTextChip: View {
    let label: String
    var isActive = false
    var onFocus: (() -> Void)? = nil
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(label)
                .font(Typography.labelCaps)
                .textCase(.uppercase)
                .kerning(1.2)
                .padding(.horizontal, 14)
                .frame(height: 40)
                .glassBackground(in: Capsule())
                .overlay {
                    if isActive {
                        Capsule().strokeBorder(Theme.gold, lineWidth: 1.5)
                    }
                }
                .shadow(color: isActive ? Theme.goldGlow : .clear, radius: 8)
                .contentShape(Capsule())
        }
        .playerChipStyle(isActive: isActive, onFocus: onFocus)
    }
}
