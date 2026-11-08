//
//  TheaterLayout.swift
//  Edendale
//
//  Geometry for the visionOS immersive theater, kept free of RealityKit so
//  it is unit-tested on every platform.
//

import CoreGraphics
import simd

nonisolated enum TheaterLayout {
    /// Rows of the texture the picture is drawn into for the theater screen.
    /// From the seat each bundled screen spans about 54–62°, roughly
    /// 2,000–2,300 display pixels across on Apple Vision Pro, so a 1440-row
    /// canvas is close to one texel per pixel: sharper sources are filtered
    /// down once, as they're drawn in, and nothing has to be mipmapped.
    static let canvasRows = 1440

    /// Pixel size of the texture drawn onto a screen of this shape. Both
    /// sides are even.
    static func canvasSize(screenAspect: Double) -> (width: Int, height: Int) {
        let aspect = screenAspect.isFinite && screenAspect > 0 ? screenAspect : 16.0 / 9.0
        return (even(Double(canvasRows) * aspect), canvasRows)
    }

    /// The flat quad that carries the video, built from the world bounds of
    /// a scene's `Screen` mesh. Every exported screen is upright and faces
    /// the seat along +Z, so its bounds are flat in Z.
    struct ScreenPlacement: Equatable {
        var center: SIMD3<Float>
        var width: Float
        var height: Float

        var aspect: Double { Double(width / height) }
    }

    /// How far in front of the scene's own screen surface the video quad
    /// sits, so the two never fight for the same depth.
    static let screenLift: Float = 0.005

    /// Nil when the bounds have no area, so the room still loads without a
    /// picture rather than with a broken one.
    static func screenPlacement(boundsMin: SIMD3<Float>, boundsMax: SIMD3<Float>) -> ScreenPlacement? {
        let size = boundsMax - boundsMin
        guard size.x > 0.01, size.y > 0.01, size.x.isFinite, size.y.isFinite else { return nil }
        var center = (boundsMin + boundsMax) / 2
        center.z = boundsMax.z + screenLift
        return ScreenPlacement(center: center, width: size.x, height: size.y)
    }

    /// Where a room goes so the viewer sits at `seat`, the floor under one of
    /// its seats (see `TheaterSeatMap`). The seat lands on the immersive
    /// space's origin, the floor under the person wearing the headset, and
    /// the room turns about the vertical until the screen's centre is
    /// straight ahead, along -Z. From a seat at the side the rows then look
    /// angled, as if the viewer had turned in their chair, and nobody has to
    /// watch over their shoulder. Without a screen the room only moves.
    static func roomPose(seat: SIMD3<Float>, screenCenter: SIMD3<Float>?) -> RoomPose {
        var yaw: Float = 0
        if let screenCenter {
            let ahead = SIMD2(screenCenter.x - seat.x, screenCenter.z - seat.z)
            if ahead.x.isFinite, ahead.y.isFinite, simd_length(ahead) > 0.01 {
                // The turn about +Y from -Z that faces the screen.
                yaw = atan2(-ahead.x, -ahead.y)
            }
        }
        let rotation = simd_quatf(angle: -yaw, axis: SIMD3(0, 1, 0))
        return RoomPose(rotation: rotation, translation: -rotation.act(seat))
    }

    /// A turn and then a move: where a room goes in the immersive space,
    /// or where the whole theater goes when the viewer recenters it.
    struct RoomPose: Equatable {
        var rotation: simd_quatf
        var translation: SIMD3<Float>

        static let identity = RoomPose(rotation: simd_quatf(ix: 0, iy: 0, iz: 0, r: 1), translation: .zero)

        /// Where a point ends up.
        func apply(_ point: SIMD3<Float>) -> SIMD3<Float> {
            rotation.act(point) + translation
        }

        /// The pose that undoes this one.
        var inverse: RoomPose {
            let back = rotation.inverse
            return RoomPose(rotation: back, translation: -back.act(translation))
        }
    }

    // MARK: - Recentering

    /// Where the theater goes when the viewer recenters it: turned the way
    /// they face and, when they lie back or recline, tilted back with them,
    /// so the screen is in front of their eyes as it is from the seat.
    /// `head` is the headset's pose in the immersive space. A head tipped to
    /// one side doesn't tip the theater: its left and right stay level.
    ///
    /// Within `Recenter.levelPitch` of level the theater only turns, and its
    /// floor stays on the real floor, so a seated or standing viewer gets
    /// the theater upright again in front of them. Tilted, the seat's eye
    /// point (`Recenter.seatedEyeHeight` above its floor) lands on the eyes.
    static func recenterPose(head: simd_float4x4) -> RoomPose? {
        let eye = SIMD3(head.columns.3.x, head.columns.3.y, head.columns.3.z)
        let headRight = SIMD3(head.columns.0.x, head.columns.0.y, head.columns.0.z)
        let forward = simd_normalize(-SIMD3(head.columns.2.x, head.columns.2.y, head.columns.2.z))
        guard eye.x.isFinite, eye.y.isFinite, eye.z.isFinite,
              forward.x.isFinite, forward.y.isFinite, forward.z.isFinite
        else { return nil }
        // Level and square to the gaze, whichever way the head is tipped.
        // Looking almost straight up or down the gaze gives no left and
        // right, and the head's own right, made level, says which way the
        // viewer lies.
        var right = simd_cross(forward, SIMD3(0, 1, 0))
        if simd_length(right) < Recenter.nearVertical {
            right = SIMD3(headRight.x, 0, headRight.z)
        }
        guard simd_length(right) > 0.1 else { return nil }
        right = simd_normalize(right)
        let ahead = simd_normalize(forward - simd_dot(forward, right) * right)

        if abs(asin(min(max(ahead.y, -1), 1))) < Recenter.levelPitch {
            let level = simd_cross(SIMD3<Float>(0, 1, 0), right)
            let rotation = simd_quatf(angle: atan2(-level.x, -level.z), axis: SIMD3(0, 1, 0))
            return RoomPose(rotation: rotation, translation: SIMD3(eye.x, 0, eye.z))
        }
        let back = -ahead
        let up = simd_cross(back, right)
        let rotation = simd_quatf(simd_float3x3(columns: (right, up, back)))
        let seatedEye = SIMD3<Float>(0, Recenter.seatedEyeHeight, 0)
        return RoomPose(rotation: rotation, translation: eye - rotation.act(seatedEye))
    }

    enum Recenter {
        /// Looking less than this far up or down counts as level.
        static let levelPitch: Float = 20 * .pi / 180

        /// The eyes of someone sitting in a theater seat, above its floor.
        static let seatedEyeHeight: Float = 1.2

        /// A gaze within about 15° of straight up or down: the sine of that.
        static let nearVertical: Float = 0.26
    }

    /// Subtitles are a SwiftUI overlay laid out at this size, then scaled
    /// onto the screen, so cue placement matches the 2D player's.
    static func subtitleCanvas(for placement: ScreenPlacement) -> CGSize {
        let width: CGFloat = 1600
        return CGSize(width: width, height: (width / CGFloat(placement.aspect)).rounded())
    }

    // MARK: - Controls dock

    /// Where the theater's controls float: below the viewer's line of sight,
    /// in whichever direction they face. Looking 45° down meets the panel
    /// centred, and turning around brings it along.
    enum Dock {
        /// From the eyes to the panel's centre, in metres: within reach of
        /// a hand, like a remote held in the lap.
        static let distance: Float = 0.65

        /// How far below eye level the panel sits, in radians.
        static let declination: Float = .pi / 4

        /// The panel's width in metres. At `distance` its points look about
        /// as big as a window's at its usual distance.
        static let width: Float = 0.4

        /// Turning the head less than this leaves the panel where it is, so
        /// it holds still while the viewer looks across it. To the left the
        /// seat panel sits beside it, so turning left holds them both still
        /// up to `sideTurn` further.
        static let turnDeadZone: Float = 12 * .pi / 180

        /// The seat panel's width in metres, at the controls' scale.
        static let sideWidth: Float = 0.28

        /// Between the controls and the seat panel, in metres.
        static let sideGap: Float = 0.03

        /// How far left of the controls the seat panel sits, as a turn about
        /// the eyes: its centre is as far round the circle the panels sit on
        /// as half of each panel and the gap between them.
        static var sideTurn: Float {
            let radius = distance * cos(declination)
            return (width / 2 + sideGap + sideWidth / 2) / radius
        }

        /// Moving the head less than this, leaning in to touch the panel for
        /// instance, leaves it where it is too.
        static let moveDeadZone: Float = 0.15

        /// Once the panel moves, the share of the way left that it covers
        /// per second, as an exponential rate: it settles in about half a
        /// second.
        static let followRate: Float = 8

        /// The turn about +Y from -Z that faces `forward`, or nil when it
        /// points almost straight up or down and has no clear heading.
        static func yaw(facing forward: SIMD3<Float>) -> Float? {
            let horizontal = SIMD2(forward.x, forward.z)
            guard horizontal.x.isFinite, horizontal.y.isFinite, simd_length(horizontal) > 0.2 else { return nil }
            return atan2(-forward.x, -forward.z)
        }

        /// The panel's pose for eyes at `eye` facing `yaw`: `distance` ahead
        /// and `declination` below, tilted back to face the eyes.
        static func pose(eye: SIMD3<Float>, yaw: Float) -> Pose {
            let turn = simd_quatf(angle: yaw, axis: SIMD3(0, 1, 0))
            let offset = SIMD3<Float>(0, -sin(declination), -cos(declination)) * distance
            let tilt = simd_quatf(angle: -declination, axis: SIMD3(1, 0, 0))
            return Pose(position: eye + turn.act(offset), orientation: turn * tilt)
        }

        struct Pose: Equatable {
            var position: SIMD3<Float>
            var orientation: simd_quatf
        }
    }

    /// Moves the dock after the viewer's head, lazily: small turns and
    /// leans leave it still, a larger one brings it smoothly back in front.
    struct DockFollower {
        /// The heading the panel is placed for; nil until the first update.
        private(set) var yaw: Float?
        /// The eye position the panel is placed for.
        private(set) var eye: SIMD3<Float>?
        private var isTurning = false
        private var isMoving = false

        /// Within this the panel counts as caught up and stops moving.
        private static let settledTurn: Float = .pi / 180
        private static let settledMove: Float = 0.005

        var pose: Dock.Pose? {
            guard let yaw, let eye else { return nil }
            return Dock.pose(eye: eye, yaw: yaw)
        }

        /// The seat panel, to the left of the controls.
        var sidePose: Dock.Pose? {
            guard let yaw, let eye else { return nil }
            return Dock.pose(eye: eye, yaw: yaw + Dock.sideTurn)
        }

        /// One frame: `headYaw` is nil when the head points too far up or
        /// down to have a heading, which keeps the current one.
        mutating func update(headYaw: Float?, eye headEye: SIMD3<Float>, deltaTime: Float) {
            let step = min(max(1 - exp(-Dock.followRate * max(deltaTime, 0)), 0), 1)

            if let headYaw {
                if let yaw {
                    let gap = (headYaw - yaw).remainder(dividingBy: 2 * .pi)
                    // A positive gap is a turn to the left.
                    if gap > Dock.turnDeadZone + Dock.sideTurn || gap < -Dock.turnDeadZone {
                        isTurning = true
                    }
                    if isTurning {
                        if abs(gap) <= Self.settledTurn {
                            self.yaw = headYaw
                            isTurning = false
                        } else {
                            self.yaw = (yaw + gap * step).remainder(dividingBy: 2 * .pi)
                        }
                    }
                } else {
                    yaw = headYaw
                }
            }

            if let eye {
                let gap = headEye - eye
                if simd_length(gap) > Dock.moveDeadZone { isMoving = true }
                if isMoving {
                    if simd_length(gap) <= Self.settledMove {
                        self.eye = headEye
                        isMoving = false
                    } else {
                        self.eye = eye + gap * step
                    }
                }
            } else {
                eye = headEye
            }
        }
    }

    private static func even(_ value: Double) -> Int {
        let rounded = Int(value.rounded())
        return rounded + (rounded % 2)
    }
}
