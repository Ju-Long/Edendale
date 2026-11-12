//
//  VisionTheaterDock.swift
//  Edendale
//
//  The immersive theater's controls: a panel that stays up for as long as
//  the theater is open, below the viewer's line of sight in whichever
//  direction they face (see `TheaterLayout.Dock`), with the seat panel
//  (`VisionTheaterSeatPanel`) beside it on the left. The player window
//  can't follow the viewer around, so while the theater is open its
//  controls move here and the window only says where the video went.
//

#if os(visionOS)

import ARKit
import QuartzCore
import RealityKit
import SwiftUI

// MARK: - Placement

/// Keeps the controls panel, and the seat panel beside it, in front of the
/// viewer. ARKit's world tracking reports the head's pose every frame and
/// asks no permission; it runs only while the immersive space is open.
@MainActor
final class VisionTheaterDockTracker {
    /// The attachment entity carrying the controls.
    weak var panel: Entity? {
        didSet { if panel !== oldValue { isPanelSized = false } }
    }
    private var isPanelSized = false

    /// The attachment entity carrying the seat panel.
    weak var sidePanel: Entity? {
        didSet { if sidePanel !== oldValue { isSidePanelSized = false } }
    }
    private var isSidePanelSized = false

    /// Where the viewer last recentered the theater. The panels follow the
    /// head within it, so a viewer lying back finds them below their line
    /// of sight as they lie, towards their chest.
    var frame = TheaterLayout.RoomPose.identity {
        didSet { follower = TheaterLayout.DockFollower() }
    }

    /// The headset's last tracked pose in the immersive space.
    private(set) var head: simd_float4x4?

    private let session = ARKitSession()
    private let worldTracking = WorldTrackingProvider()
    private var follower = TheaterLayout.DockFollower()
    private var updates: EventSubscription?
    private var isTrackingUnavailable = false
    private var framesWithoutHead = 0

    /// Used when the head's pose can't be known: the eyes of a viewer
    /// seated in the room's seat, facing its screen.
    private static let restingEye = SIMD3<Float>(0, 1.2, 0)

    /// Frames to wait for world tracking to start before falling back to
    /// `restingEye`, about half a second; sooner, the panel would appear
    /// there and then glide to the real head.
    private static let headWaitFrames = 45

    /// Moves the panel once per rendered frame.
    func follow(in content: RealityViewContent) {
        guard updates == nil else { return }
        updates = content.subscribe(to: SceneEvents.Update.self) { [weak self] event in
            MainActor.assumeIsolated {
                self?.step(deltaTime: Float(event.deltaTime))
            }
        }
    }

    func run() async {
        guard WorldTrackingProvider.isSupported else {
            isTrackingUnavailable = true
            return
        }
        do {
            try await session.run([worldTracking])
        } catch {
            isTrackingUnavailable = true
            debugPrint("[VisionTheaterDock] world tracking didn't start: \(error)")
        }
    }

    func stop() {
        session.stop()
    }

    private func step(deltaTime: Float) {
        if let panel, !isPanelSized {
            isPanelSized = Self.size(panel, toWidth: TheaterLayout.Dock.width)
        }
        if let sidePanel, !isSidePanelSized {
            isSidePanelSized = Self.size(sidePanel, toWidth: TheaterLayout.Dock.sideWidth)
        }
        if worldTracking.state == .running,
           let device = worldTracking.queryDeviceAnchor(atTimestamp: CACurrentMediaTime()),
           device.isTracked {
            let head = device.originFromAnchorTransform
            self.head = head
            // The head as the recentered theater sees it.
            let back = frame.inverse
            let eye = back.apply(SIMD3(head.columns.3.x, head.columns.3.y, head.columns.3.z))
            let forward = back.rotation.act(-SIMD3(head.columns.2.x, head.columns.2.y, head.columns.2.z))
            follower.update(
                headYaw: TheaterLayout.Dock.yaw(facing: forward),
                eye: eye,
                deltaTime: deltaTime
            )
            framesWithoutHead = 0
        } else {
            framesWithoutHead += 1
            if follower.pose == nil, isTrackingUnavailable || framesWithoutHead > Self.headWaitFrames {
                follower.update(headYaw: 0, eye: Self.restingEye, deltaTime: deltaTime)
            }
        }
        if let panel, let pose = follower.pose {
            place(panel, at: pose)
        }
        if let sidePanel, let pose = follower.sidePose {
            place(sidePanel, at: pose)
        }
    }

    private func place(_ entity: Entity, at pose: TheaterLayout.Dock.Pose) {
        entity.setPosition(frame.apply(pose.position), relativeTo: nil)
        entity.setOrientation(frame.rotation * pose.orientation, relativeTo: nil)
    }

    /// The attachment has no size until SwiftUI has laid it out. Measured in
    /// its own space, so its current scale doesn't count.
    private static func size(_ entity: Entity, toWidth width: Float) -> Bool {
        let measured = entity.visualBounds(relativeTo: entity).extents.x
        guard measured > 0 else { return false }
        entity.scale = SIMD3(repeating: width / measured)
        return true
    }
}

// MARK: - Panel

/// The player's controls while the theater is open. They never hide: the
/// panel sits below the viewer's line of sight, so it covers nothing on the
/// screen. An open playlist or adjustments panel takes the controls' place
/// until it is closed.
struct VisionTheaterDock: View {
    @Environment(PlayerSession.self) private var session

    let chrome: PlayerChromeModel
    let player: PlaybackEngine
    let item: PlaybackItem

    /// Points across the panel; `TheaterLayout.Dock.width` sets its size in
    /// the room.
    static let width: CGFloat = 680

    var body: some View {
        VStack(spacing: 16) {
            if let upcoming = chrome.upcomingEpisode, chrome.activePanel == nil, !chrome.isScrubbing {
                PlayerUpNextView(episode: upcoming) {
                    Task { await session.play(episode: upcoming) }
                }
                .frame(maxWidth: .infinity, alignment: .trailing)
                .transition(.opacity)
            }

            if let panel = chrome.activePanel {
                PlayerPanelContent(panel: panel, chrome: chrome, player: player, item: item)
                    .frame(height: 440)
                    .accessibilityElement(children: .contain)
                    .id(panel)
                    .transition(.opacity)
            } else {
                controls
                    .transition(.opacity)
            }
        }
        .padding(24)
        .frame(width: Self.width)
        // Dark like the player window, so the panel doesn't glow at the
        // edge of view in the dimmed room.
        .background(Theme.background.opacity(0.7), in: RoundedRectangle(cornerRadius: 36, style: .continuous))
        .glassBackgroundEffect(in: RoundedRectangle(cornerRadius: 36, style: .continuous))
        .animation(.easeInOut(duration: 0.25), value: chrome.activePanel)
        .animation(.easeInOut(duration: 0.25), value: chrome.upcomingEpisode?.id)
        // The window's overlay, which usually does this, is down while the
        // theater is open.
        .onChange(of: player.currentTime, initial: true) { _, time in
            session.segmentSkipping.update(
                time: time.playbackSeconds, duration: player.duration?.playbackSeconds,
                isSeekable: player.isSeekable
            )
        }
    }

    /// Top to bottom: close and tools, the title, the timeline, then the
    /// transport, as in the system player.
    private var controls: some View {
        VStack(spacing: 18) {
            HStack(alignment: .center) {
                PlayerExitButton(action: session.end)
                Spacer(minLength: 12)
                tools
            }
            .overlay { titleBlock.padding(.horizontal, 150) }

            timeline

            ZStack {
                HStack(spacing: 40) {
                    skipButton(.backward)
                    // Buffering swaps in a smaller spinner; hold the room so
                    // the skip buttons don't jump.
                    centerButton
                        .frame(width: 88, height: 88)
                    skipButton(.forward)
                }
                segmentButton
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
        }
    }

    private var tools: some View {
        HStack(spacing: 12) {
            VisionTheaterMenu()

            PlayerIconChip(
                icon: .listTree,
                label: String(localized: "Playlist"),
                isActive: chrome.activePanel == .playlist
            ) {
                chrome.openPanel(.playlist)
            }

            PlayerIconChip(
                icon: .sidebarRight,
                label: String(localized: "Adjustments"),
                isActive: chrome.activePanel == .settings
            ) {
                chrome.openPanel(.settings)
            }
        }
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
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    private var timeline: some View {
        HStack(spacing: 16) {
            // The timeline speaks both times as its value.
            Text(PlayerLogic.timestamp(displayedTime))
                .font(Typography.bodySM)
                .monospacedDigit()
                .foregroundStyle(Theme.textPrimary)
                .accessibilityHidden(true)

            PlayerTimeline(chrome: chrome, player: player)

            Text(PlayerLogic.timestamp(player.duration ?? .zero))
                .font(Typography.bodySM)
                .monospacedDigit()
                .foregroundStyle(Theme.textSecondary)
                .accessibilityHidden(true)
        }
    }

    private var displayedTime: Double {
        if chrome.isScrubbing, let duration = player.duration {
            return chrome.scrubPosition * duration.playbackSeconds
        }
        return player.currentTime.playbackSeconds
    }

    @ViewBuilder
    private var centerButton: some View {
        if player.state == .opening || player.state == .buffering || player.isRebuffering {
            ProgressView()
                .controlSize(.large)
                .tint(Theme.gold)
                .accessibilityLabel("Buffering")
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
            .playerChipStyle()
            .accessibilityLabel(player.isPlaying ? Text("Pause") : Text("Play"))
        }
    }

    /// Skips by the viewer's length from Settings ▸ Player Controls.
    private func skipButton(_ direction: SkipDirection) -> some View {
        let interval = chrome.controls.skipInterval(for: direction)
        return PlayerIconChip(
            icon: interval.icon(for: direction),
            label: direction == .backward
                ? String(localized: "Back \(interval.seconds) seconds")
                : String(localized: "Forward \(interval.seconds) seconds"),
            diameter: 64,
            glyphSize: 28
        ) {
            chrome.skip(direction)
        }
    }

    /// Skip Intro and the like, while the playhead is in such a segment.
    @ViewBuilder
    private var segmentButton: some View {
        if let segment = session.segmentSkipping.activeSegment, !chrome.isScrubbing {
            Button(action: session.skipCurrentSegment) {
                Text(segment.kind.title)
                    .font(Typography.bodyLG)
                    .fontWeight(.semibold)
                    .foregroundStyle(Theme.textPrimary)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 14)
                    .background(Theme.surface, in: RoundedRectangle(cornerRadius: Theme.Radius.card))
                    .overlay {
                        RoundedRectangle(cornerRadius: Theme.Radius.card)
                            .strokeBorder(Theme.outline, lineWidth: 1)
                    }
            }
            .buttonStyle(.plain)
            .hoverEffect()
            .accessibilityHint("Skip this segment and continue playback")
            .transition(.opacity)
        }
    }
}

#endif
