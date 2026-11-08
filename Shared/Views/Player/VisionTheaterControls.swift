//
//  VisionTheaterControls.swift
//  Edendale
//
//  The player's Theater menu on visionOS, and what the player window shows
//  while the picture is on a theater screen.
//

#if os(visionOS)

import SwiftUI

/// Toolbar chip that opens one of the theaters, swaps rooms, or leaves.
/// The theater only ever opens from here, by the viewer's choice; its seat
/// panel (`VisionTheaterSeatPanel`) picks the seat and the lights.
struct VisionTheaterMenu: View {
    @Environment(PlayerSession.self) private var session
    @Environment(\.openImmersiveSpace) private var openImmersiveSpace
    @Environment(\.dismissImmersiveSpace) private var dismissImmersiveSpace

    var onFocus: (() -> Void)?

    private var theater: VisionTheaterController { session.theater }

    var body: some View {
        Menu {
            Section(String(localized: "Theatre", table: "Theater",
                           comment: "Heading of the menu that puts the video on a screen in an immersive room.")) {
                ForEach(TheaterEnvironment.allCases) { environment in
                    Button {
                        Task { await theater.choose(environment, open: openImmersiveSpace) }
                    } label: {
                        HStack {
                            Text(environment.title)
                            if theater.environment == environment {
                                Image(.check)
                            }
                        }
                    }
                }
            }
            if theater.isImmersed {
                Button {
                    Task { await theater.leave(dismiss: dismissImmersiveSpace) }
                } label: {
                    Text("Leave Theatre", tableName: "Theater",
                         comment: "Closes the immersive theater and returns the video to the player window.")
                }
            }
        } label: {
            Image(.clapperboard)
                .font(.system(size: 22, weight: .bold))
                .frame(width: 40, height: 40)
                .glassBackground(in: Circle())
                .overlay {
                    if theater.isImmersed {
                        Circle().strokeBorder(Theme.gold, lineWidth: 1.5)
                    }
                }
                .shadow(color: theater.isImmersed ? Theme.goldGlow : .clear, radius: 8)
                .contentShape(Circle())
        }
        .disabled(theater.isOpening)
        .playerChipStyle(isActive: theater.isImmersed, onFocus: onFocus)
        .accessibilityLabel(Text("Theatre", tableName: "Theater"))
        .accessibilityValue(theater.environment?.title ?? "")
    }
}

/// Covers the window's own picture while the theater shows it, until the
/// window closes (see `VisionTheaterController`), or for good if visionOS
/// keeps it open. The controls are on the theater's panel below the
/// viewer's line of sight (`VisionTheaterDock`), so the window only says
/// where everything went.
struct VisionTheaterRemoteBackdrop: View {
    let environment: TheaterEnvironment?

    var body: some View {
        ZStack {
            Theme.background
            label
        }
    }

    private var label: some View {
        VStack(spacing: 12) {
            Image(.clapperboard)
                .font(.system(size: 40))
                .foregroundStyle(Theme.gold)
                .accessibilityHidden(true)
            if let environment {
                Text("Playing in the \(environment.title)", tableName: "Theater",
                     comment: "Shown in the player window while the video plays in an immersive theater. The argument is the theater's name.")
                    .font(Typography.headlineMD)
                    .foregroundStyle(Theme.textPrimary)
                    .multilineTextAlignment(.center)
            }
            Text("Look down for the controls.", tableName: "Theater",
                 comment: "Hint under the theater name in the player window while the video plays in an immersive theater. The controls float below the viewer's line of sight and follow the way they face.")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
        }
        .padding(32)
        .accessibilityElement(children: .combine)
    }
}

#endif
