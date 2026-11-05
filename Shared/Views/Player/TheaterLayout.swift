//
//  TheaterLayout.swift
//  Edendale
//
//  Geometry for the visionOS immersive theater, kept free of RealityKit so
//  it is unit-tested on every platform.
//

import CoreGraphics
import simd

enum TheaterLayout {
    /// Rows of the texture the picture is drawn into for the theater screen.
    /// From the seat each bundled screen spans about 40–46°, roughly 1,700
    /// display pixels across on Apple Vision Pro, so a 1080-row canvas is
    /// close to one texel per pixel: sharper sources are filtered down once,
    /// as they're drawn in, and nothing has to be mipmapped.
    static let canvasRows = 1080

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

    /// Moves a scene so its `Seat` (the floor under the viewer) lands on the
    /// immersive space's origin, the floor under the person wearing the
    /// headset.
    static func roomOffset(seat: SIMD3<Float>) -> SIMD3<Float> {
        -seat
    }

    /// Subtitles are a SwiftUI overlay laid out at this size, then scaled
    /// onto the screen, so cue placement matches the 2D player's.
    static func subtitleCanvas(for placement: ScreenPlacement) -> CGSize {
        let width: CGFloat = 1600
        return CGSize(width: width, height: (width / CGFloat(placement.aspect)).rounded())
    }

    private static func even(_ value: Double) -> Int {
        let rounded = Int(value.rounded())
        return rounded + (rounded % 2)
    }
}
