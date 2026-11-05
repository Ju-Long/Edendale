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
        #expect(TheaterLayout.canvasRows == 1080)
        #expect(TheaterLayout.canvasSize(screenAspect: 16.0 / 9.0) == (1920, 1080))
        #expect(TheaterLayout.canvasSize(screenAspect: 2.39) == (2582, 1080))
        #expect(TheaterLayout.canvasSize(screenAspect: 4.0 / 3.0) == (1440, 1080))
    }

    @Test func canvasSidesAreEven() {
        let size = TheaterLayout.canvasSize(screenAspect: 1.85)
        #expect(size.width % 2 == 0)
        #expect(size.height % 2 == 0)
    }

    @Test func canvasFallsBackToSixteenByNineWithoutAScreenShape() {
        #expect(TheaterLayout.canvasSize(screenAspect: 0) == (1920, 1080))
        #expect(TheaterLayout.canvasSize(screenAspect: -1) == (1920, 1080))
        #expect(TheaterLayout.canvasSize(screenAspect: .nan) == (1920, 1080))
        #expect(TheaterLayout.canvasSize(screenAspect: .infinity) == (1920, 1080))
    }

    // MARK: - Screen and seat

    @Test func screenQuadSitsJustInFrontOfTheScreen() throws {
        // The movie theatre's screen, as its export reports it.
        let placement = try #require(TheaterLayout.screenPlacement(
            boundsMin: SIMD3(-6, -0.975, -14),
            boundsMax: SIMD3(6, 5.775, -14)
        ))
        #expect(placement.width == 12)
        #expect(placement.height == 6.75)
        #expect(placement.center.x == 0)
        #expect(abs(placement.center.y - 2.4) < 0.0001)
        #expect(placement.center.z == -14 + TheaterLayout.screenLift)
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
        #expect(seat + TheaterLayout.roomOffset(seat: seat) == .zero)
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
        }
    }
    #endif
}
