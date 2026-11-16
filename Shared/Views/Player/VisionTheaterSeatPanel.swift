//
//  VisionTheaterSeatPanel.swift
//  Edendale
//
//  The immersive theater's second panel, to the left of the controls: the
//  room's seats seen from above, to move to any of them, the lights, and
//  Recenter. `VisionTheaterDockTracker` keeps it beside the controls.
//

#if os(visionOS)

import SwiftUI

struct VisionTheaterSeatPanel: View {
    let theater: VisionTheaterController
    let map: TheaterSeatMap
    /// The screen's centre across the room and its width, in metres, for
    /// the bar at the top of the map.
    let screenCenterX: Float
    let screenWidth: Float

    /// Points across the panel: `TheaterLayout.Dock.sideWidth` at the
    /// controls' scale (`VisionTheaterDock.width` across `Dock.width`), so
    /// text is the same size on both panels.
    static let width = CGFloat(TheaterLayout.Dock.sideWidth / TheaterLayout.Dock.width) * VisionTheaterDock.width

    /// The seat under the viewer's pinch while they drag across the map.
    @State private var pointed: TheaterSeatMap.SeatID?

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            header
            seatMap
                .frame(height: 300)
            mapFooter
            Divider()
            lightsPicker
            recenterButton
        }
        .padding(24)
        .frame(width: Self.width)
        // Dark like the controls, so the panel doesn't glow at the edge of
        // view in the dimmed room.
        .background(Theme.background.opacity(0.7), in: RoundedRectangle(cornerRadius: 36, style: .continuous))
        .glassBackgroundEffect(in: RoundedRectangle(cornerRadius: 36, style: .continuous))
    }

    /// Where the viewer sits now.
    private var seated: TheaterSeatMap.SeatID? {
        theater.currentSeat ?? map.best
    }

    // MARK: - Seats

    private var header: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("Seats", tableName: "Theater",
                 comment: "Heading of the immersive theater's seat map, where the viewer picks a seat.")
                .font(Typography.titleLG)
                .foregroundStyle(Theme.textPrimary)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 12)
            if let shown = pointed ?? seated {
                Text(Self.title(of: shown))
                    .font(Typography.bodySM)
                    .monospacedDigit()
                    .foregroundStyle(pointed == nil ? Theme.textSecondary : Theme.gold)
                    .accessibilityHidden(true)
            }
        }
    }

    /// Pinch a seat to sit in it. Holding the pinch and moving the hand
    /// slides along the seats, the one under it lit and named above, and
    /// letting go sits there: seats are smaller than a glance can pick out
    /// in the larger rooms.
    private var seatMap: some View {
        GeometryReader { geometry in
            let layout = map.layout(in: geometry.size, screenCenterX: screenCenterX, screenWidth: screenWidth)
            Canvas { context, _ in
                context.fill(
                    Path(roundedRect: layout.screen, cornerRadius: layout.screen.height / 2),
                    with: .color(Theme.textPrimary.opacity(0.85))
                )
                let size = layout.seatSize
                for seat in map.seats {
                    guard let point = layout.points[seat.id] else { continue }
                    let rect = CGRect(x: point.x - size / 2, y: point.y - size / 2, width: size, height: size)
                    let shape = Path(roundedRect: rect, cornerRadius: size * 0.3)
                    if seat.id == pointed {
                        context.fill(shape, with: .color(Theme.textPrimary))
                    } else if seat.id == seated {
                        context.fill(shape, with: .color(Theme.gold))
                    } else {
                        context.fill(shape, with: .color(Theme.textSecondary.opacity(0.35)))
                    }
                    if seat.id == map.best, seat.id != seated {
                        context.stroke(shape, with: .color(Theme.gold), lineWidth: max(size * 0.14, 1))
                    }
                }
            }
            .overlay(alignment: .topLeading) {
                Text("Screen", tableName: "Theater",
                     comment: "Label under the screen at the top of the immersive theater's seat map.")
                    .font(Typography.labelCaps)
                    .textCase(.uppercase)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize()
                    .position(x: layout.screen.midX, y: layout.screen.maxY + 14)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        pointed = map.nearestSeat(to: value.location, in: layout)
                    }
                    .onEnded { value in
                        pointed = nil
                        if let seat = map.nearestSeat(to: value.location, in: layout) {
                            sit(in: seat)
                        }
                    }
            )
        }
        .accessibilityElement()
        .accessibilityLabel(Text("Seats", tableName: "Theater"))
        .accessibilityValue(seated.map(Self.title(of:)) ?? "")
        .accessibilityAdjustableAction { direction in
            let order = map.seats.map(\.id)
            guard !order.isEmpty else { return }
            let index = seated.flatMap(order.firstIndex(of:)) ?? 0
            switch direction {
            case .increment: sit(in: order[min(index + 1, order.count - 1)])
            case .decrement: sit(in: order[max(index - 1, 0)])
            @unknown default: break
            }
        }
    }

    /// The legend for the map's gold seat, and the way back to the room's
    /// best seat, drawn as the map draws it.
    private var mapFooter: some View {
        HStack(spacing: 16) {
            HStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 4)
                    .fill(Theme.gold)
                    .frame(width: 14, height: 14)
                Text("Your Seat", tableName: "Theater",
                     comment: "Legend for the highlighted seat on the immersive theater's seat map: where the viewer sits.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
            .accessibilityElement(children: .combine)
            Spacer(minLength: 8)
            Button {
                theater.choose(seat: nil)
            } label: {
                HStack(spacing: 8) {
                    RoundedRectangle(cornerRadius: 4)
                        .strokeBorder(Theme.gold, lineWidth: 2)
                        .frame(width: 14, height: 14)
                    Text("Best Seat", tableName: "Theater",
                         comment: "Button that moves the viewer back to the immersive theater's recommended seat, which the seat map outlines in gold.")
                        .font(Typography.bodySM)
                        .fontWeight(.semibold)
                        .foregroundStyle(Theme.textPrimary)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Theme.surface, in: Capsule())
                .contentShape(.hoverEffect, Capsule())
            }
            .buttonStyle(.plain)
            .hoverEffect()
            .disabled(theater.currentSeat == nil)
        }
    }

    private func sit(in seat: TheaterSeatMap.SeatID) {
        theater.choose(seat: seat == map.best ? nil : seat)
    }

    static func title(of seat: TheaterSeatMap.SeatID) -> String {
        String(localized: "Row \(seat.row), Seat \(seat.number)", table: "Theater",
               comment: "A seat on the immersive theater's seat map. Rows count from the front, seats from the left.")
    }

    // MARK: - Lights and recentering

    private var lightsPicker: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Lights", tableName: "Theater",
                 comment: "Heading of the control that sets how bright the immersive theater's room is.")
                .font(Typography.bodyLG)
                .fontWeight(.semibold)
                .foregroundStyle(Theme.textPrimary)
            Picker(selection: Binding(get: { theater.lights }, set: { theater.choose($0) })) {
                ForEach(TheaterLights.allCases) { lights in
                    Text(lights.title).tag(lights)
                }
            } label: {
                Text("Lights", tableName: "Theater")
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        }
    }

    private var recenterButton: some View {
        Button {
            theater.recenter()
        } label: {
            HStack(spacing: 14) {
                Image(systemName: "scope")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundStyle(Theme.gold)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Recentre", tableName: "Theater",
                         comment: "Button that turns the immersive theater so its screen is where the viewer is looking.")
                        .font(Typography.bodyLG)
                        .fontWeight(.semibold)
                        .foregroundStyle(Theme.textPrimary)
                    Text("Puts the screen where you're looking, even lying down.", tableName: "Theater",
                         comment: "Explains the Recenter button in the immersive theater: the theater tilts to face a viewer who lies back.")
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }
            .padding(16)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: Theme.Radius.card))
            .contentShape(.hoverEffect, RoundedRectangle(cornerRadius: Theme.Radius.card))
        }
        .buttonStyle(.plain)
        .hoverEffect()
    }
}

#endif
