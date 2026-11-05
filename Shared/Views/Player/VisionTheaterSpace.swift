//
//  VisionTheaterSpace.swift
//  Edendale
//
//  The immersive theater: one of the bundled rooms around the viewer, with
//  the player's picture on its screen and the subtitles in front of it.
//

#if os(visionOS)

import CoreMedia
import RealityKit
import SwiftUI

struct VisionTheaterSpace: View {
    @Environment(PlayerSession.self) private var session
    @Environment(\.dismissImmersiveSpace) private var dismissImmersiveSpace
    @Environment(\.scenePhase) private var scenePhase

    /// Holds the loaded room; replaced when the viewer picks another one.
    @State private var stage = Entity()
    @State private var placement: TheaterLayout.ScreenPlacement?

    private static let subtitlesID = "subtitles"
    private static let roomName = "Room"

    private var theater: VisionTheaterController { session.theater }

    var body: some View {
        RealityView { content, _ in
            content.add(stage)
        } update: { _, attachments in
            guard let subtitles = attachments.entity(for: Self.subtitlesID) else { return }
            placeSubtitles(subtitles)
        } attachments: {
            Attachment(id: Self.subtitlesID) {
                subtitleOverlay
            }
        }
        .task(id: theater.environment) {
            await load(theater.environment)
        }
        .onAppear { theater.spaceDidAppear() }
        .onDisappear { theater.spaceDidDisappear() }
        // visionOS can close the space without tearing this view down (the
        // Digital Crown, or the system taking over), so its phase is the
        // reliable signal.
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active: theater.spaceDidAppear()
            case .background: theater.spaceDidDisappear()
            default: break
            }
        }
        // The screen shows the 2D player's picture, so the room closes with
        // it, and when the next item goes to the system player instead.
        .onChange(of: playerEnded) { _, ended in
            if ended { Task { await dismissImmersiveSpace() } }
        }
    }

    private var playerEnded: Bool {
        !session.isPlayerPresented || session.player == nil || session.visionNativeItem != nil
    }

    // MARK: - Room

    private func load(_ environment: TheaterEnvironment?) async {
        guard let environment,
              let url = Bundle.main.url(forResource: environment.resourceName, withExtension: "usdz")
        else { return }
        let room: Entity
        do {
            room = try await Entity(contentsOf: url)
        } catch {
            debugPrint("[VisionTheaterSpace] couldn't load \(environment.resourceName): \(error)")
            return
        }
        guard !Task.isCancelled else { return }

        if let seat = room.findEntity(named: "Seat") {
            room.position += TheaterLayout.roomOffset(seat: seat.position(relativeTo: nil))
        }

        let container = Entity()
        container.name = Self.roomName
        container.addChild(room)
        var screenPlacement: TheaterLayout.ScreenPlacement?
        if let screen = room.findEntity(named: "Screen") {
            let bounds = screen.visualBounds(relativeTo: container)
            screenPlacement = TheaterLayout.screenPlacement(boundsMin: bounds.min, boundsMax: bounds.max)
            if let screenPlacement, let picture = makePicture(for: screenPlacement) {
                // The room's own screen surface stays behind the picture,
                // in case the picture can't be drawn.
                container.addChild(picture)
            }
        }

        // Only the old room goes; the subtitle layer stays attached.
        for child in stage.children.filter({ $0.name == Self.roomName }) {
            child.removeFromParent()
        }
        stage.addChild(container)
        placement = screenPlacement
    }

    /// The quad the player's frames are drawn onto.
    private func makePicture(for placement: TheaterLayout.ScreenPlacement) -> Entity? {
        theater.screenFeed.screenAspect = placement.aspect
        guard let texture = try? theater.screenFeed.prepareTexture() else { return nil }
        // Unlit: the screen gives off the picture's light instead of being
        // lit by the room, and tone mapping would shift its colours.
        var material = UnlitMaterial(applyPostProcessToneMap: false)
        material.color = .init(tint: .white, texture: .init(texture))
        let picture = ModelEntity(
            mesh: .generatePlane(width: placement.width, height: placement.height),
            materials: [material]
        )
        picture.name = "Picture"
        picture.position = placement.center
        return picture
    }

    // MARK: - Subtitles

    @ViewBuilder
    private var subtitleOverlay: some View {
        if let player = session.player, let placement {
            let canvas = TheaterLayout.subtitleCanvas(for: placement)
            PlayerSubtitleOverlay(
                engine: player.subtitleEngine,
                appearance: session.subtitleAppearance,
                time: CMTime(seconds: player.currentTime.playbackSeconds, preferredTimescale: 60000),
                videoSize: player.decoder?.mediaInfo?.naturalSize ?? .zero
            )
            .frame(width: canvas.width, height: canvas.height)
        }
    }

    /// Scales the subtitle layer to the screen's width and floats it just in
    /// front of the picture.
    private func placeSubtitles(_ subtitles: Entity) {
        guard let placement else {
            subtitles.isEnabled = false
            return
        }
        if subtitles.parent == nil { stage.addChild(subtitles) }
        subtitles.isEnabled = true
        // Measured in the layer's own space, so its current scale doesn't count.
        let width = subtitles.visualBounds(relativeTo: subtitles).extents.x
        if width > 0 { subtitles.scale = SIMD3(repeating: placement.width / width) }
        subtitles.position = placement.center + SIMD3(0, 0, 0.02)
    }
}

#endif
