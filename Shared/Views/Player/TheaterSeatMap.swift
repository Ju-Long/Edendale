//
//  TheaterSeatMap.swift
//  Edendale
//
//  The seats of a visionOS theater room, read from the empties its scene
//  marks every seat with, and the top-down map the seat panel draws. Kept
//  free of RealityKit so it is unit-tested on every platform.
//

import CoreGraphics
import Foundation
import simd

nonisolated struct TheaterSeatMap: Equatable, Sendable {
    /// A seat by its row, counted from the front, and its number in that
    /// row, counted from the left as the viewer faces the screen.
    struct SeatID: Hashable, Comparable, Sendable {
        let row: Int
        let number: Int

        /// The empty that marks this seat in a room: `Seat_06_01` is the
        /// first seat of row 6.
        var markerName: String {
            String(format: "Seat_%02d_%02d", row, number)
        }

        init(row: Int, number: Int) {
            self.row = row
            self.number = number
        }

        init?(markerName: String) {
            let parts = markerName.split(separator: "_")
            guard parts.count == 3, parts[0] == "Seat",
                  let row = Int(parts[1]), let number = Int(parts[2]),
                  row > 0, number > 0
            else { return nil }
            self.init(row: row, number: number)
        }

        /// How a choice is kept on the device: "6-1".
        var storageValue: String {
            "\(row)-\(number)"
        }

        init?(storageValue: String) {
            let parts = storageValue.split(separator: "-")
            guard parts.count == 2, let row = Int(parts[0]), let number = Int(parts[1]),
                  row > 0, number > 0
            else { return nil }
            self.init(row: row, number: number)
        }

        static func < (lhs: SeatID, rhs: SeatID) -> Bool {
            (lhs.row, lhs.number) < (rhs.row, rhs.number)
        }
    }

    struct Seat: Identifiable, Equatable, Sendable {
        let id: SeatID
        /// The floor under the seat in the room's space: Y up, the screen
        /// towards -Z.
        let position: SIMD3<Float>
    }

    /// The empty a room keeps its seat markers under.
    static let markerParentName = "SeatMap"

    /// Front row first, each row from the left.
    let seats: [Seat]

    /// The seat under the room's own `Seat`, where the viewer sits unless
    /// they pick another.
    let best: SeatID?

    /// Markers whose names aren't seats are left out. `bestSeat` is the
    /// position of the room's `Seat`.
    init(markers: [(name: String, position: SIMD3<Float>)], bestSeat: SIMD3<Float>?) {
        seats = markers
            .compactMap { marker in
                SeatID(markerName: marker.name).map { Seat(id: $0, position: marker.position) }
            }
            .sorted { $0.id < $1.id }
        if let bestSeat {
            best = seats
                .min { simd_distance($0.position, bestSeat) < simd_distance($1.position, bestSeat) }
                .flatMap { simd_distance($0.position, bestSeat) < 0.5 ? $0.id : nil }
        } else {
            best = nil
        }
    }

    func seat(_ id: SeatID) -> Seat? {
        seats.first { $0.id == id }
    }

    // MARK: - Map

    /// Where the seat panel draws each seat, seen from above with the screen
    /// at the top. Across and front to back are scaled separately to fill
    /// the map, so a deep room keeps its rows apart; the gap between the
    /// screen and the front row is drawn at a fixed height.
    struct Layout: Equatable {
        var points: [SeatID: CGPoint]
        /// Width and height of a seat.
        var seatSize: CGFloat
        /// The screen's bar along the top.
        var screen: CGRect
    }

    /// Points of map above the front row: the screen's bar and a gap.
    static let screenBand: CGFloat = 40

    func layout(in size: CGSize, screenCenterX: Float, screenWidth: Float) -> Layout {
        guard !seats.isEmpty, size.width > 0, size.height > Self.screenBand else {
            return Layout(points: [:], seatSize: 0, screen: .zero)
        }
        let xs = seats.map(\.position.x)
        let zs = seats.map(\.position.z)
        let screenLeft = screenCenterX - screenWidth / 2
        let screenRight = screenCenterX + screenWidth / 2
        let minX = min(xs.min()!, screenLeft)
        let maxX = max(xs.max()!, screenRight)
        let minZ = zs.min()!
        let maxZ = zs.max()!

        // The seat's size comes from the closest seats in a row, so seats
        // don't overlap.
        let pitch = rowPitch
        let rows = Set(seats.map(\.id.row)).count
        let depth = max(maxZ - minZ, 0.01)
        let width = max(maxX - minX, 0.01)

        // Leave half a seat around the edges.
        var seatSize: CGFloat = 0
        var scaleX: CGFloat = 0
        var scaleZ: CGFloat = 0
        for _ in 0..<2 {
            let margin = seatSize / 2
            scaleX = (size.width - 2 * margin) / CGFloat(width)
            scaleZ = (size.height - Self.screenBand - 2 * margin) / CGFloat(depth)
            let across = CGFloat(pitch) * scaleX
            let along = rows > 1 ? CGFloat(depth) / CGFloat(rows - 1) * scaleZ : across
            seatSize = max(min(across, along, 40) * 0.82, 2)
        }
        let margin = seatSize / 2

        var points: [SeatID: CGPoint] = [:]
        for seat in seats {
            points[seat.id] = CGPoint(
                x: margin + CGFloat(seat.position.x - minX) * scaleX,
                y: Self.screenBand + margin + CGFloat(seat.position.z - minZ) * scaleZ
            )
        }
        let screen = CGRect(
            x: margin + CGFloat(screenLeft - minX) * scaleX,
            y: 0,
            width: CGFloat(screenWidth) * scaleX,
            height: 8
        )
        return Layout(points: points, seatSize: seatSize, screen: screen)
    }

    /// The seat drawn closest to `point`.
    func nearestSeat(to point: CGPoint, in layout: Layout) -> SeatID? {
        layout.points.min { lhs, rhs in
            hypot(lhs.value.x - point.x, lhs.value.y - point.y)
                < hypot(rhs.value.x - point.x, rhs.value.y - point.y)
        }?.key
    }

    /// The usual distance between neighbouring seats in a row, in metres.
    private var rowPitch: Float {
        var gaps: [Float] = []
        for row in Dictionary(grouping: seats, by: \.id.row).values {
            let sorted = row.sorted { $0.id.number < $1.id.number }
            for (left, right) in zip(sorted, sorted.dropFirst()) {
                gaps.append(simd_distance(left.position, right.position))
            }
        }
        guard !gaps.isEmpty else { return 0.6 }
        return gaps.sorted()[gaps.count / 2]
    }
}
