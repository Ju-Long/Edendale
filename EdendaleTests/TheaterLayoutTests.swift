//
//  TheaterLayoutTests.swift
//  EdendaleTests
//
//  The visionOS immersive theater's geometry and its bundled rooms. The
//  geometry is pure, so it runs on every platform; the room check that
//  loads RealityKit runs only when the tests run on visionOS.
//

import CoreGraphics
import Foundation
import Testing
import simd
@testable import Edendale

#if os(visionOS)
import RealityKit
#endif

struct TheaterLayoutTests {

    // MARK: - Canvas

    @Test func canvasTakesTheScreensShapeAtTheFixedRowCount() {
        #expect(TheaterLayout.canvasRows == 1440)
        #expect(TheaterLayout.canvasSize(screenAspect: 16.0 / 9.0) == (2560, 1440))
        #expect(TheaterLayout.canvasSize(screenAspect: 2.39) == (3442, 1440))
        #expect(TheaterLayout.canvasSize(screenAspect: 4.0 / 3.0) == (1920, 1440))
    }

    @Test func canvasSidesAreEven() {
        let size = TheaterLayout.canvasSize(screenAspect: 1.85)
        #expect(size.width % 2 == 0)
        #expect(size.height % 2 == 0)
    }

    @Test func canvasFallsBackToSixteenByNineWithoutAScreenShape() {
        #expect(TheaterLayout.canvasSize(screenAspect: 0) == (2560, 1440))
        #expect(TheaterLayout.canvasSize(screenAspect: -1) == (2560, 1440))
        #expect(TheaterLayout.canvasSize(screenAspect: .nan) == (2560, 1440))
        #expect(TheaterLayout.canvasSize(screenAspect: .infinity) == (2560, 1440))
    }

    // MARK: - Screen and seat

    @Test func screenQuadSitsJustInFrontOfTheScreen() throws {
        // The movie theatre's screen, as its export reports it.
        let placement = try #require(TheaterLayout.screenPlacement(
            boundsMin: SIMD3(-6, 0.225, -10.55),
            boundsMax: SIMD3(6, 6.975, -10.55)
        ))
        #expect(placement.width == 12)
        #expect(abs(placement.height - 6.75) < 0.0001)
        #expect(placement.center.x == 0)
        #expect(abs(placement.center.y - 3.6) < 0.0001)
        #expect(placement.center.z == -10.55 + TheaterLayout.screenLift)
        #expect(abs(placement.aspect - 16.0 / 9.0) < 0.0001)
    }

    @Test func aScreenWithoutAreaGetsNoPicture() {
        #expect(TheaterLayout.screenPlacement(boundsMin: .zero, boundsMax: .zero) == nil)
        #expect(TheaterLayout.screenPlacement(boundsMin: SIMD3(0, 0, 0), boundsMax: SIMD3(4, 0, 0)) == nil)
        #expect(TheaterLayout.screenPlacement(
            boundsMin: SIMD3(-.infinity, 0, 0), boundsMax: SIMD3(0, 2, 0)
        ) == nil)
    }

    @Test func roomMovesItsSeatOntoTheOrigin() {
        let seat = SIMD3<Float>(0.4, 1.2, -3)
        let pose = TheaterLayout.roomPose(seat: seat, screenCenter: SIMD3(0.4, 3.6, -13.55))
        #expect(length(pose.apply(seat)) < 0.0001)
        // Straight in front of the screen, the room only moves.
        #expect(abs(pose.rotation.angle) < 0.0001)
        #expect(length(pose.translation + seat) < 0.0001)
    }

    @Test func roomTurnsAnEndSeatToFaceTheScreen() {
        // The movie theatre's far-left seat, in the room's Y-up space.
        let seat = SIMD3<Float>(-5.4, 1.2, 3.45)
        let screen = SIMD3<Float>(0, 3.6, -10.55)
        let pose = TheaterLayout.roomPose(seat: seat, screenCenter: screen)
        #expect(length(pose.apply(seat)) < 0.0001)
        let ahead = pose.apply(screen)
        // Straight ahead, as far away as before, at the same height.
        #expect(abs(ahead.x) < 0.0001)
        #expect(ahead.z < 0)
        #expect(abs(ahead.y - (screen.y - seat.y)) < 0.0001)
        let distance = length(SIMD2(screen.x - seat.x, screen.z - seat.z))
        #expect(abs(-ahead.z - distance) < 0.0001)
        // Turning only about the vertical keeps the floor level.
        #expect(abs(pose.rotation.act(SIMD3(0, 1, 0)).y - 1) < 0.0001)
    }

    @Test func roomOnlyMovesWithoutAScreen() {
        let seat = SIMD3<Float>(2, 0.5, 1)
        let pose = TheaterLayout.roomPose(seat: seat, screenCenter: nil)
        #expect(abs(pose.rotation.angle) < 0.0001)
        #expect(length(pose.apply(seat)) < 0.0001)
        // A screen right above the seat gives no heading either.
        let above = TheaterLayout.roomPose(seat: seat, screenCenter: SIMD3(2, 4, 1))
        #expect(abs(above.rotation.angle) < 0.0001)
    }

    @Test func poseUndoesItself() {
        let pose = TheaterLayout.roomPose(seat: SIMD3(-5.4, 1.2, 3.45), screenCenter: SIMD3(0, 3.6, -10.55))
        let point = SIMD3<Float>(1, 2, 3)
        #expect(length(pose.inverse.apply(pose.apply(point)) - point) < 0.0001)
        #expect(length(TheaterLayout.RoomPose.identity.apply(point) - point) < 0.0001)
    }

    // MARK: - Seat map

    private typealias SeatID = TheaterSeatMap.SeatID

    @Test func seatsAreNamedByRowAndNumber() throws {
        let seat = try #require(SeatID(markerName: "Seat_06_01"))
        #expect(seat == SeatID(row: 6, number: 1))
        #expect(seat.markerName == "Seat_06_01")
        #expect(SeatID(row: 12, number: 27).markerName == "Seat_12_27")
        #expect(SeatID(storageValue: seat.storageValue) == seat)
        for name in ["Seat", "Seats", "SeatMap", "Seat_06", "Seat_0_3", "Seat_a_b", "Chair_01_02", "Seat_01_02_03"] {
            #expect(SeatID(markerName: name) == nil, "\(name)")
        }
        for value in ["", "6", "6-", "-1", "0-3", "x-y", "1-2-3"] {
            #expect(SeatID(storageValue: value) == nil, "\(value)")
        }
    }

    /// Three rows of five seats, 0.6 m apart, rows 1 m apart and rising
    /// 0.3 m, the room's `Seat` on the middle of the front row.
    private static let smallRoom: [(name: String, position: SIMD3<Float>)] = (1...3).flatMap { row in
        (1...5).map { number in
            (name: SeatID(row: row, number: number).markerName,
             position: SIMD3<Float>(Float(number - 3) * 0.6, Float(row - 1) * 0.3, Float(row - 1)))
        }
    } + [(name: "Seat", position: .zero), (name: "Screen", position: SIMD3(0, 3, -8))]

    @Test func mapKeepsOnlySeatsInOrder() {
        let map = TheaterSeatMap(markers: Self.smallRoom.reversed(), bestSeat: .zero)
        #expect(map.seats.count == 15)
        #expect(map.seats.first?.id == SeatID(row: 1, number: 1))
        #expect(map.seats.last?.id == SeatID(row: 3, number: 5))
        #expect(map.best == SeatID(row: 1, number: 3))
        #expect(map.seat(SeatID(row: 2, number: 4))?.position == SIMD3(0.6, 0.3, 1))
        // No best seat when the room's `Seat` isn't on one.
        #expect(TheaterSeatMap(markers: Self.smallRoom, bestSeat: SIMD3(0, 0, 9)).best == nil)
    }

    @Test func mapDrawsTheScreenOnTopAndTheRowsBelowIt() throws {
        let map = TheaterSeatMap(markers: Self.smallRoom, bestSeat: .zero)
        let size = CGSize(width: 400, height: 300)
        let layout = map.layout(in: size, screenCenterX: 0, screenWidth: 4)
        #expect(layout.points.count == 15)
        #expect(layout.screen.minY == 0)
        #expect(abs(layout.screen.midX - 200) < 0.5)
        for point in layout.points.values {
            #expect(point.x - layout.seatSize / 2 >= -0.01)
            #expect(point.x + layout.seatSize / 2 <= size.width + 0.01)
            #expect(point.y - layout.seatSize / 2 >= TheaterSeatMap.screenBand - 0.01)
            #expect(point.y + layout.seatSize / 2 <= size.height + 0.01)
        }
        let frontLeft = try #require(layout.points[SeatID(row: 1, number: 1)])
        let frontRight = try #require(layout.points[SeatID(row: 1, number: 5)])
        let backLeft = try #require(layout.points[SeatID(row: 3, number: 1)])
        #expect(frontLeft.x < frontRight.x)
        #expect(frontLeft.y < backLeft.y)
        // Neighbouring seats don't touch.
        let next = try #require(layout.points[SeatID(row: 1, number: 2)])
        #expect(next.x - frontLeft.x > layout.seatSize)
        #expect(map.nearestSeat(to: CGPoint(x: frontRight.x + 3, y: frontRight.y - 2), in: layout) == SeatID(row: 1, number: 5))
    }

    @Test func emptyMapDrawsNothing() {
        let map = TheaterSeatMap(markers: [(name: "Seat", position: .zero)], bestSeat: .zero)
        #expect(map.seats.isEmpty)
        #expect(map.best == nil)
        let layout = map.layout(in: CGSize(width: 400, height: 300), screenCenterX: 0, screenWidth: 4)
        #expect(layout.points.isEmpty)
        #expect(map.nearestSeat(to: .zero, in: layout) == nil)
    }

    // MARK: - Recentering

    private static func head(at eye: SIMD3<Float>, right: SIMD3<Float>, up: SIMD3<Float>) -> simd_float4x4 {
        let back = simd_cross(right, up)
        return simd_float4x4(columns: (
            SIMD4(right, 0), SIMD4(up, 0), SIMD4(back, 0), SIMD4(eye, 1)
        ))
    }

    @Test func recentringUprightOnlyTurnsAndKeepsTheFloor() throws {
        // Seated 0.3 m to the right, turned 90° to the right, looking a
        // little down.
        let pitch: Float = -10 * Self.degree
        let up = SIMD3<Float>(sin(pitch) * -1, cos(pitch), 0)
        let head = Self.head(at: SIMD3(0.3, 1.2, -0.2), right: SIMD3(0, 0, 1), up: up)
        let pose = try #require(TheaterLayout.recenterPose(head: head))
        // The seat's floor lands under the eyes, on the real floor.
        #expect(length(pose.apply(.zero) - SIMD3(0.3, 0, -0.2)) < 0.0001)
        // Straight ahead from the seat is now to the right.
        #expect(length(pose.rotation.act(SIMD3(0, 0, -1)) - SIMD3(1, 0, 0)) < 0.0001)
        #expect(abs(pose.rotation.act(SIMD3(0, 1, 0)).y - 1) < 0.0001)
    }

    @Test func recentringLyingBackTiltsTheTheaterOverhead() throws {
        // On the back, 0.5 m up, looking at the ceiling, the top of the
        // head towards +Z.
        let eye = SIMD3<Float>(0, 0.5, 0.4)
        let head = Self.head(at: eye, right: SIMD3(1, 0, 0), up: SIMD3(0, 0, 1))
        let pose = try #require(TheaterLayout.recenterPose(head: head))
        let seatedEye = SIMD3<Float>(0, TheaterLayout.Recenter.seatedEyeHeight, 0)
        #expect(length(pose.apply(seatedEye) - eye) < 0.0001)
        // Ahead from the seat is up, the seat's up is towards the top of
        // the head, and its right stays right.
        #expect(length(pose.rotation.act(SIMD3(0, 0, -1)) - SIMD3(0, 1, 0)) < 0.0001)
        #expect(length(pose.rotation.act(SIMD3(0, 1, 0)) - SIMD3(0, 0, 1)) < 0.0001)
        #expect(length(pose.rotation.act(SIMD3(1, 0, 0)) - SIMD3(1, 0, 0)) < 0.0001)
        // The movie theatre's screen ends up straight above the eyes.
        let screen = pose.apply(SIMD3(0, TheaterLayout.Recenter.seatedEyeHeight, -10.55))
        #expect(abs(screen.x - eye.x) < 0.0001)
        #expect(abs(screen.z - eye.z) < 0.0001)
        #expect(screen.y > eye.y + 10)
    }

    @Test func recentringReclinedIgnoresAHeadTiltedToOneSide() throws {
        // Reclined 40° on a sofa, the head also tipped 15° to the right.
        let recline: Float = 40 * Self.degree
        let tip: Float = 15 * Self.degree
        let level = Self.head(
            at: SIMD3(0, 0.9, 0),
            right: SIMD3(1, 0, 0),
            up: SIMD3(0, cos(recline), sin(recline))
        )
        let tipped = level * simd_float4x4(simd_quatf(angle: -tip, axis: SIMD3(0, 0, 1)))
        let straight = try #require(TheaterLayout.recenterPose(head: level))
        let pose = try #require(TheaterLayout.recenterPose(head: tipped))
        #expect(abs(simd_dot(straight.rotation.vector, pose.rotation.vector)) > 0.9999)
        // Ahead from the seat points 40° up.
        let ahead = pose.rotation.act(SIMD3(0, 0, -1))
        #expect(abs(asin(ahead.y) - recline) < 0.0001)
    }

    @Test func recentringLyingOnASideKeepsTheTheaterUpright() throws {
        // On the right side, looking ahead along -Z.
        let head = Self.head(at: SIMD3(0, 0.4, 0), right: SIMD3(0, -1, 0), up: SIMD3(1, 0, 0))
        let pose = try #require(TheaterLayout.recenterPose(head: head))
        #expect(length(pose.rotation.act(SIMD3(0, 1, 0)) - SIMD3(0, 1, 0)) < 0.0001)
        #expect(length(pose.rotation.act(SIMD3(0, 0, -1)) - SIMD3(0, 0, -1)) < 0.0001)
        #expect(length(pose.apply(.zero) - SIMD3(0, 0, 0)) < 0.0001)
    }

    @Test func recentringNeedsAUsableHead() {
        var head = matrix_identity_float4x4
        head.columns.3 = SIMD4(.nan, 1, 0, 1)
        #expect(TheaterLayout.recenterPose(head: head) == nil)
    }

    // MARK: - Lights

    @Test func lightsGetDarkerDownTheList() {
        let levels = TheaterLights.allCases.map(\.brightness)
        #expect(levels.first == 1)
        #expect(zip(levels, levels.dropFirst()).allSatisfy { $0 > $1 })
        #expect(levels.allSatisfy { $0 > 0 })
        let titles = TheaterLights.allCases.map(\.title)
        #expect(Set(titles).count == titles.count)
    }

    @Test func subtitlesAreLaidOutInTheScreensShape() throws {
        let placement = try #require(TheaterLayout.screenPlacement(
            boundsMin: SIMD3(-2.8, 1.15, -7.4), boundsMax: SIMD3(2.8, 4.3, -7.4)
        ))
        #expect(TheaterLayout.subtitleCanvas(for: placement) == CGSize(width: 1600, height: 900))
    }

    // MARK: - Rooms

    @Test func everyRoomHasItsOwnExport() {
        let names = TheaterEnvironment.allCases.map(\.resourceName)
        #expect(Set(names).count == names.count)
        let titles = TheaterEnvironment.allCases.map(\.title)
        #expect(Set(titles).count == titles.count)
        #expect(!titles.contains(""))

        // The exports live beside the app's other shared resources.
        let theaters = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appending(path: "Shared/Theaters")
        for name in names {
            let file = theaters.appending(path: "\(name).usdz")
            #expect(FileManager.default.fileExists(atPath: file.path), "missing \(file.lastPathComponent)")
        }
    }

    #if os(visionOS)
    /// Every bundled room loads and names the seat and screen the theater
    /// places itself by.
    @Test @MainActor func bundledRoomsLoadWithASeatAndAScreen() async throws {
        for environment in TheaterEnvironment.allCases {
            let url = try #require(
                Bundle.main.url(forResource: environment.resourceName, withExtension: "usdz"),
                "\(environment.resourceName).usdz is not in the app bundle"
            )
            let room = try await Entity(contentsOf: url)
            let seat = try #require(room.findEntity(named: "Seat"))
            let screen = try #require(room.findEntity(named: "Screen"))
            #expect(length(seat.position(relativeTo: nil)) < 0.001)
            let bounds = screen.visualBounds(relativeTo: nil)
            let placement = try #require(TheaterLayout.screenPlacement(boundsMin: bounds.min, boundsMax: bounds.max))
            // In front of the seat, above the floor, in the screen's 16:9.
            #expect(placement.center.z < -3)
            #expect(placement.center.y > 1)
            #expect(abs(placement.aspect - 16.0 / 9.0) < 0.01)
            // Close enough to fill the view like a seat near the front: from
            // the middle rows (41–46°) it looked far away.
            let degrees = 2 * atan(placement.width / 2 / -placement.center.z) * 180 / .pi
            #expect(degrees > 50, "\(environment.resourceName) screen spans \(degrees)°")
            #expect(degrees < 70, "\(environment.resourceName) screen spans \(degrees)°")
        }
    }

    /// Every room marks every seat for the seat map, and from each of them
    /// the screen ends up straight ahead.
    @Test @MainActor func bundledRoomsMarkEverySeat() async throws {
        let expected: [TheaterEnvironment: (rows: Int, seats: Int, best: TheaterSeatMap.SeatID)] = [
            .movieTheatre: (11, 209, TheaterSeatMap.SeatID(row: 3, number: 10)),
            .projectorRoom: (6, 54, TheaterSeatMap.SeatID(row: 2, number: 5)),
            .performanceTheatre: (14, 384, TheaterSeatMap.SeatID(row: 4, number: 14)),
        ]
        for environment in TheaterEnvironment.allCases {
            let name = environment.resourceName
            let url = try #require(Bundle.main.url(forResource: name, withExtension: "usdz"))
            let room = try await Entity(contentsOf: url)
            let screen = try #require(room.findEntity(named: "Screen"))
            let bounds = screen.visualBounds(relativeTo: room)
            let placement = try #require(TheaterLayout.screenPlacement(boundsMin: bounds.min, boundsMax: bounds.max))
            let parent = try #require(room.findEntity(named: TheaterSeatMap.markerParentName), "\(name) has no seat map")
            let best = room.findEntity(named: "Seat")?.position(relativeTo: room)
            let map = TheaterSeatMap(
                markers: parent.children.map { (name: $0.name, position: $0.position(relativeTo: room)) },
                bestSeat: best
            )
            let want = try #require(expected[environment])
            #expect(map.seats.count == want.seats, "\(name)")
            #expect(Set(map.seats.map(\.id.row)).count == want.rows, "\(name)")
            #expect(map.best == want.best, "\(name)")
            for seat in map.seats {
                let pose = TheaterLayout.roomPose(seat: seat.position, screenCenter: placement.center)
                let ahead = pose.apply(placement.center)
                #expect(abs(ahead.x) < 0.001, "\(name) \(seat.id)")
                #expect(-ahead.z > placement.width / 2, "\(name) \(seat.id)")
            }
            // Rows run front to back, seats left to right.
            let first = try #require(map.seats.first)
            let lastOfRow = try #require(map.seats.last { $0.id.row == 1 })
            let back = try #require(map.seats.last)
            #expect(first.position.x < lastOfRow.position.x, "\(name)")
            #expect(length(placement.center - first.position) < length(placement.center - back.position), "\(name)")
        }
    }

    /// The seat in each room and the lights stay on the device.
    @Test @MainActor func theaterRemembersSeatsAndLights() throws {
        let suite = "TheaterLayoutTests.\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }

        let fresh = VisionTheaterController(defaults: defaults)
        #expect(fresh.lights == .normal)
        #expect(fresh.seat(in: .projectorRoom) == nil)
        fresh.choose(TheaterLights.dark)
        // Nothing is open, so there is no room to sit in or recenter.
        fresh.choose(seat: TheaterSeatMap.SeatID(row: 6, number: 1))
        fresh.recenter()
        #expect(fresh.seat(in: .projectorRoom) == nil)
        #expect(fresh.recenterRequests == 0)

        defaults.set("4-9", forKey: VisionTheaterController.seatKey(for: .projectorRoom))
        defaults.set("rubbish", forKey: VisionTheaterController.seatKey(for: .movieTheatre))
        let reopened = VisionTheaterController(defaults: defaults)
        #expect(reopened.lights == .dark)
        #expect(reopened.seat(in: .projectorRoom) == TheaterSeatMap.SeatID(row: 4, number: 9))
        #expect(reopened.seat(in: .movieTheatre) == nil)
    }
    #endif

    // MARK: - Controls dock

    private typealias Dock = TheaterLayout.Dock

    private static let degree: Float = .pi / 180

    @Test func dockSitsFortyFiveDegreesBelowTheEyesStraightAhead() {
        let eye = SIMD3<Float>(0.3, 1.2, -0.4)
        let pose = Dock.pose(eye: eye, yaw: 0)
        let offset = pose.position - eye
        #expect(abs(simd_length(offset) - Dock.distance) < 0.0001)
        #expect(abs(offset.x) < 0.0001)
        // Ahead (-Z) as far as below: 45° down.
        #expect(offset.z < 0)
        #expect(abs(offset.y - offset.z) < 0.0001)
        #expect(abs(atan2(-offset.y, -offset.z) - .pi / 4) < 0.0001)
    }

    @Test func dockFacesTheEyes() {
        let eye = SIMD3<Float>(0, 1.2, 0)
        for yaw: Float in [0, 0.7, -2.5, .pi] {
            let pose = Dock.pose(eye: eye, yaw: yaw)
            // A panel's front faces +Z in its own space.
            let normal = pose.orientation.act(SIMD3(0, 0, 1))
            let toEye = simd_normalize(eye - pose.position)
            #expect(simd_dot(normal, toEye) > 0.9999, "yaw \(yaw)")
        }
    }

    @Test func dockTurnsWithTheHeading() throws {
        let eye = SIMD3<Float>(0, 1.2, 0)
        // Facing +X is a quarter turn clockwise seen from above.
        let yaw = try #require(Dock.yaw(facing: SIMD3(1, 0, 0)))
        #expect(abs(yaw + .pi / 2) < 0.0001)
        let pose = Dock.pose(eye: eye, yaw: yaw)
        #expect(pose.position.x > 0.4)
        #expect(abs(pose.position.z) < 0.0001)
    }

    @Test func headingIgnoresPitchButNotStraightDown() throws {
        // Looking 45° down at the panel still faces straight ahead.
        let down45 = SIMD3<Float>(0, -1, -1) / sqrt(2)
        let yaw = try #require(Dock.yaw(facing: down45))
        #expect(abs(yaw) < 0.0001)
        // Straight down has no heading.
        #expect(Dock.yaw(facing: SIMD3(0, -1, 0)) == nil)
        #expect(Dock.yaw(facing: SIMD3(0.05, -0.998, 0)) == nil)
    }

    @Test func followerStartsWhereTheHeadIs() throws {
        var follower = TheaterLayout.DockFollower()
        #expect(follower.pose == nil)
        follower.update(headYaw: 1, eye: SIMD3(0, 1.5, 0), deltaTime: 1 / 90)
        let pose = try #require(follower.pose)
        #expect(pose == Dock.pose(eye: SIMD3(0, 1.5, 0), yaw: 1))
    }

    @Test func smallTurnsAndLeansLeaveTheDockStill() {
        var follower = TheaterLayout.DockFollower()
        follower.update(headYaw: 0, eye: SIMD3(0, 1.2, 0), deltaTime: 1 / 90)
        for _ in 0..<180 {
            follower.update(headYaw: 10 * Self.degree, eye: SIMD3(0, 1.1, -0.1), deltaTime: 1 / 90)
        }
        #expect(follower.yaw == 0)
        #expect(follower.eye == SIMD3(0, 1.2, 0))
    }

    @Test func turningAroundBringsTheDockBackInFront() throws {
        var follower = TheaterLayout.DockFollower()
        follower.update(headYaw: 0, eye: SIMD3(0, 1.2, 0), deltaTime: 1 / 90)
        // Turned to face behind; within a second the panel is centred again.
        for _ in 0..<90 {
            follower.update(headYaw: .pi, eye: SIMD3(0, 1.2, 0), deltaTime: 1 / 90)
        }
        let yaw = try #require(follower.yaw)
        #expect(abs(abs(yaw) - .pi) < 0.0001)
        // Once caught up it stays put through small turns again.
        follower.update(headYaw: .pi - 5 * Self.degree, eye: SIMD3(0, 1.2, 0), deltaTime: 1 / 90)
        let settled = try #require(follower.yaw)
        #expect(abs(abs(settled) - .pi) < 0.0001)
    }

    @Test func dockTakesTheShortWayRound() throws {
        var follower = TheaterLayout.DockFollower()
        follower.update(headYaw: -170 * Self.degree, eye: SIMD3(0, 1.2, 0), deltaTime: 1 / 90)
        // 170° is a 20° turn to the right, not 340° back to the left.
        follower.update(headYaw: 170 * Self.degree, eye: SIMD3(0, 1.2, 0), deltaTime: 1 / 90)
        let yaw = try #require(follower.yaw)
        let heading = yaw < 0 ? yaw + 2 * .pi : yaw
        #expect(heading > 170 * Self.degree)
        #expect(heading < 190 * Self.degree)
    }

    @Test func standingUpLiftsTheDock() throws {
        var follower = TheaterLayout.DockFollower()
        follower.update(headYaw: 0, eye: SIMD3(0, 1.2, 0), deltaTime: 1 / 90)
        for _ in 0..<90 {
            follower.update(headYaw: 0, eye: SIMD3(0, 1.65, 0), deltaTime: 1 / 90)
        }
        let eye = try #require(follower.eye)
        #expect(abs(eye.y - 1.65) < 0.0001)
    }

    @Test func lookingStraightDownKeepsTheHeading() {
        var follower = TheaterLayout.DockFollower()
        follower.update(headYaw: 0.5, eye: SIMD3(0, 1.2, 0), deltaTime: 1 / 90)
        follower.update(headYaw: nil, eye: SIMD3(0, 1.2, 0), deltaTime: 1 / 90)
        #expect(follower.yaw == 0.5)
    }

    @Test func seatPanelSitsBesideTheControlsOnTheLeft() throws {
        var follower = TheaterLayout.DockFollower()
        follower.update(headYaw: 0, eye: SIMD3(0, 1.2, 0), deltaTime: 1 / 90)
        let dock = try #require(follower.pose)
        let side = try #require(follower.sidePose)
        // Left of the controls, at the same height and distance.
        #expect(side.position.x < dock.position.x - Dock.width / 2)
        #expect(abs(side.position.y - dock.position.y) < 0.0001)
        #expect(abs(length(side.position - SIMD3(0, 1.2, 0)) - Dock.distance) < 0.0001)
        // Seen from the eyes the panels don't overlap: each spans its half
        // width either side of its centre, and there's room to spare.
        let radius = Dock.distance * cos(Dock.declination)
        let spans = atan(Dock.width / 2 / radius) + atan(Dock.sideWidth / 2 / radius)
        #expect(Dock.sideTurn > spans + 2 * Self.degree)
    }

    @Test func lookingLeftAtTheSeatPanelHoldsBothPanels() throws {
        var follower = TheaterLayout.DockFollower()
        follower.update(headYaw: 0, eye: SIMD3(0, 1.2, 0), deltaTime: 1 / 90)
        for _ in 0..<90 {
            follower.update(headYaw: Dock.sideTurn, eye: SIMD3(0, 1.2, 0), deltaTime: 1 / 90)
        }
        #expect(follower.yaw == 0)
        // The same turn to the right brings the panels round.
        for _ in 0..<90 {
            follower.update(headYaw: -Dock.sideTurn, eye: SIMD3(0, 1.2, 0), deltaTime: 1 / 90)
        }
        let yaw = try #require(follower.yaw)
        #expect(abs(yaw + Dock.sideTurn) < 0.0001)
    }
}
