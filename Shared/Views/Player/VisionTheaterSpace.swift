//
//  VisionTheaterSpace.swift
//  Edendale
//
//  The immersive theater: one of the bundled rooms around the viewer, with
//  the player's picture on its screen, the subtitles in front of it, and
//  the controls below the viewer's line of sight (see VisionTheaterDock).
//  The player window closes while it is open, so the theater draws the
//  picture with its own video view. The seat panel beside the controls
//  picks a seat, the lights, and recenters the theater.
//

#if os(visionOS)

import CoreMedia
import RealityKit
import SwiftUI

struct VisionTheaterSpace: View {
    @Environment(PlayerSession.self) private var session
    @Environment(\.dismissImmersiveSpace) private var dismissImmersiveSpace
    @Environment(\.openWindow) private var openWindow
    @Environment(\.scenePhase) private var scenePhase

    @State private var stage = Entity()
    /// Holds the loaded room, replaced when the viewer picks another one,
    /// and carries where the viewer last recentered the theater.
    @State private var recentering = Entity()
    @State private var room: LoadedRoom?
    @State private var placement: TheaterLayout.ScreenPlacement?
    @State private var dockTracker = VisionTheaterDockTracker()

    private static let subtitlesID = "subtitles"
    private static let dockID = "controls"
    private static let pictureSourceID = "picture-source"
    private static let seatPanelID = "seats"
    private static let roomName = "Room"

    private var theater: VisionTheaterController { session.theater }

    var body: some View {
        RealityView { content, _ in
            content.add(stage)
            stage.addChild(recentering)
            dockTracker.follow(in: content)
        } update: { _, attachments in
            if let subtitles = attachments.entity(for: Self.subtitlesID) {
                placeSubtitles(subtitles)
            }
            if let dock = attachments.entity(for: Self.dockID) {
                placeDock(dock)
            }
            if let source = attachments.entity(for: Self.pictureSourceID) {
                placePictureSource(source)
            }
            if let seats = attachments.entity(for: Self.seatPanelID) {
                placeSeatPanel(seats)
            }
        } attachments: {
            Attachment(id: Self.subtitlesID) {
                subtitleOverlay
            }
            Attachment(id: Self.dockID) {
                dock
            }
            Attachment(id: Self.pictureSourceID) {
                pictureSource
            }
            Attachment(id: Self.seatPanelID) {
                seatPanel
            }
        }
        .task(id: theater.environment) {
            await load(theater.environment)
        }
        .task(id: theater.currentSeat) {
            await move(to: theater.currentSeat)
        }
        .onChange(of: theater.lights) { _, lights in
            room?.lighting.apply(lights)
        }
        .task(id: theater.recenterRequests) {
            guard theater.recenterRequests > 0 else { return }
            await recenter()
        }
        .task {
            await dockTracker.run()
        }
        .onAppear {
            theater.reopenPlayerWindow = { [openWindow] in
                openWindow(id: VisionTheaterController.playerWindowID)
            }
            theater.spaceDidAppear()
        }
        .onDisappear {
            dockTracker.stop()
            theater.spaceDidDisappear()
        }
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
            guard ended else { return }
            Task {
                await dismissImmersiveSpace()
                // Brings the window back, to the library or the system
                // player.
                theater.spaceDidDisappear()
            }
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

        // Everything is measured in the room's own space; the container
        // carries the chosen seat's pose.
        let markers = room.findEntity(named: TheaterSeatMap.markerParentName)?.children
            .map { (name: $0.name, position: $0.position(relativeTo: room)) } ?? []
        let bestSeat = room.findEntity(named: "Seat")?.position(relativeTo: room)

        let container = Entity()
        container.name = Self.roomName
        container.addChild(room)
        var screenPlacement: TheaterLayout.ScreenPlacement?
        if let screen = room.findEntity(named: "Screen") {
            let bounds = screen.visualBounds(relativeTo: room)
            screenPlacement = TheaterLayout.screenPlacement(boundsMin: bounds.min, boundsMax: bounds.max)
            if let screenPlacement, let picture = makePicture(for: screenPlacement) {
                // The room's own screen surface stays behind the picture,
                // in case the picture can't be drawn.
                container.addChild(picture)
            }
        }

        let loaded = LoadedRoom(
            environment: environment,
            container: container,
            map: TheaterSeatMap(markers: markers, bestSeat: bestSeat),
            bestSeat: bestSeat,
            screen: screenPlacement,
            lighting: RoomLighting(room)
        )
        loaded.lighting.apply(theater.lights)
        container.transform = Transform(loaded.pose(for: theater.seat(in: environment)))

        // Only the old room goes; the subtitle layer moves into the new one.
        for child in recentering.children.filter({ $0.name == Self.roomName }) {
            child.removeFromParent()
        }
        recentering.addChild(container)
        self.room = loaded
        placement = screenPlacement
    }

    // MARK: - Seat

    /// Moves the viewer to `seat` in the open room behind a quick fade:
    /// sliding a whole room past someone sitting still makes them queasy.
    private func move(to seat: TheaterSeatMap.SeatID?) async {
        // A room that is still loading takes the seat as it opens.
        guard let room, room.environment == theater.environment else { return }
        let pose = room.pose(for: seat)
        if !room.isPlaced(at: pose) {
            await fade(room.container, to: 0, over: .milliseconds(200))
            guard !Task.isCancelled else { return }
            room.container.transform = Transform(pose)
        }
        // Also finishes a fade that another choice cut short.
        await fade(room.container, to: 1, over: .milliseconds(300))
    }

    // MARK: - Recentering

    /// Turns the theater to where the viewer looks, tilting it back with
    /// them when they lie back (`TheaterLayout.recenterPose`), behind the
    /// same fade as a change of seat. The panels follow the head within the
    /// recentered theater.
    private func recenter() async {
        guard let head = dockTracker.head, let pose = TheaterLayout.recenterPose(head: head) else { return }
        let room = room?.container
        if let room { await fade(room, to: 0, over: .milliseconds(200)) }
        guard !Task.isCancelled else { return }
        recentering.transform = Transform(pose)
        dockTracker.frame = pose
        if let room { await fade(room, to: 1, over: .milliseconds(300)) }
    }

    private func fade(_ entity: Entity, to target: Float, over duration: Duration) async {
        let start = entity.components[OpacityComponent.self]?.opacity ?? 1
        let clock = ContinuousClock()
        let began = clock.now
        while true {
            let progress = min(Float((clock.now - began) / duration), 1)
            let opacity = start + (target - start) * progress
            if opacity >= 1 {
                entity.components.remove(OpacityComponent.self)
            } else {
                entity.components.set(OpacityComponent(opacity: opacity))
            }
            if progress >= 1 || Task.isCancelled { return }
            try? await Task.sleep(for: .milliseconds(10))
        }
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
    /// front of the picture, inside the room so it turns, moves, and fades
    /// with the seat.
    private func placeSubtitles(_ subtitles: Entity) {
        guard let placement, let room else {
            subtitles.isEnabled = false
            return
        }
        if subtitles.parent !== room.container { room.container.addChild(subtitles) }
        subtitles.isEnabled = true
        // Measured in the layer's own space, so its current scale doesn't count.
        let width = subtitles.visualBounds(relativeTo: subtitles).extents.x
        if width > 0 { subtitles.scale = SIMD3(repeating: placement.width / width) }
        subtitles.position = placement.center + SIMD3(0, 0, 0.02)
    }

    // MARK: - Picture

    /// Draws the player's frames onto the screen once the player window,
    /// whose video view drew them until then, has closed. Hidden under the
    /// floor: only its copy on the screen is meant to be seen, but a view
    /// that isn't on screen at all can stall waiting for its next drawable.
    @ViewBuilder
    private var pictureSource: some View {
        if theater.isPlayerWindowClosed, let player = session.player {
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
            .theaterFeed(theater.screenFeed)
            .frame(width: 64, height: 36)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
        }
    }

    private func placePictureSource(_ source: Entity) {
        if source.parent == nil { stage.addChild(source) }
        source.position = SIMD3(0, -1, 0)
    }

    // MARK: - Controls

    @ViewBuilder
    private var dock: some View {
        if let chrome = session.chrome, let player = session.player, let item = session.item {
            VisionTheaterDock(chrome: chrome, player: player, item: item)
        }
    }

    /// The tracker sizes the controls panel for the room and keeps it in
    /// front of the viewer.
    private func placeDock(_ dock: Entity) {
        if dock.parent == nil { stage.addChild(dock) }
        dockTracker.panel = dock
    }

    @ViewBuilder
    private var seatPanel: some View {
        if let room, !room.map.seats.isEmpty {
            VisionTheaterSeatPanel(
                theater: theater,
                map: room.map,
                screenCenterX: room.screen?.center.x ?? 0,
                screenWidth: room.screen?.width ?? 0
            )
        }
    }

    /// The tracker keeps the seat panel beside the controls, on the left.
    private func placeSeatPanel(_ panel: Entity) {
        if panel.parent == nil { stage.addChild(panel) }
        dockTracker.sidePanel = panel
    }
}

/// The open room: the container that carries the seat's pose, and what the
/// theater found in the room.
@MainActor
private struct LoadedRoom {
    let environment: TheaterEnvironment
    let container: Entity
    let map: TheaterSeatMap
    /// The room's own `Seat`, in the room's space.
    let bestSeat: SIMD3<Float>?
    let screen: TheaterLayout.ScreenPlacement?
    let lighting: RoomLighting

    /// Nil, or a seat the room doesn't have, is its best seat.
    func pose(for seat: TheaterSeatMap.SeatID?) -> TheaterLayout.RoomPose {
        let position = seat.flatMap { map.seat($0)?.position } ?? bestSeat ?? .zero
        return TheaterLayout.roomPose(seat: position, screenCenter: screen?.center)
    }

    func isPlaced(at pose: TheaterLayout.RoomPose) -> Bool {
        let transform = container.transform
        return simd_distance(transform.translation, pose.translation) < 0.001
            && abs(simd_dot(transform.rotation.vector, pose.rotation.vector)) > 0.99999
    }
}

/// The room's baked light, which the lights choice scales. The rooms draw
/// their light as emission (see the `scenes` branch), so dimming a material
/// is lowering its emissive intensity.
@MainActor
private struct RoomLighting {
    private var parts: [(entity: Entity, intensities: [Float?])] = []

    /// Lit by something other than the house lights: the projector's beam
    /// stays on, and the screen surface is behind the picture.
    private static let keptLit: Set<String> = ["Projector_beam", "Screen"]

    init(_ room: Entity) {
        collect(room)
    }

    private mutating func collect(_ entity: Entity) {
        guard !Self.keptLit.contains(entity.name) else { return }
        if let model = entity.components[ModelComponent.self] {
            let intensities = model.materials.map { ($0 as? PhysicallyBasedMaterial)?.emissiveIntensity }
            if intensities.contains(where: { $0 != nil }) {
                parts.append((entity, intensities))
            }
        }
        for child in entity.children {
            collect(child)
        }
    }

    func apply(_ lights: TheaterLights) {
        for part in parts {
            guard var model = part.entity.components[ModelComponent.self] else { continue }
            model.materials = zip(model.materials, part.intensities).map { material, baked in
                guard let baked, var lit = material as? PhysicallyBasedMaterial else { return material }
                lit.emissiveIntensity = baked * lights.brightness
                return lit
            }
            part.entity.components.set(model)
        }
    }
}

private extension Transform {
    init(_ pose: TheaterLayout.RoomPose) {
        self.init(scale: .one, rotation: pose.rotation, translation: pose.translation)
    }
}

#endif
