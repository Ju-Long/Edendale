#if os(visionOS)

import SwiftUI

/// Reusable format selector shown by both the VLC controls and the native
/// AVKit player. Packed MP4 has no reliable visual signature, so the user can
/// always override metadata-first Automatic routing without leaving playback.
struct VisionFormatMenu: View {
    @Environment(PlayerSession.self) private var session

    var showsTitle = false

    var body: some View {
        Menu {
            Section("Video Format") {
                ForEach(VisionFormatPreset.allCases, id: \.self) { preset in
                    choice(preset.title, selected: selection.preset == preset) {
                        session.selectVisionFormat(preset)
                    }
                    .disabled(preset.requiresPackedPlayback && !supportsPackedPlayback)
                }
            }

            if let layout = selection.resolvedLayout, layout.packing != .none {
                Menu("Frame Packing") {
                    choice(
                        VisionFramePacking.sideBySide.title,
                        selected: layout.packing == .sideBySide
                    ) {
                        session.setVisionPacking(.sideBySide)
                    }
                    choice(
                        VisionFramePacking.overUnder.title,
                        selected: layout.packing == .overUnder
                    ) {
                        session.setVisionPacking(.overUnder)
                    }
                }

                Menu("Projection") {
                    ForEach(VisionVideoProjection.allCases, id: \.self) { projection in
                        choice(projection.title, selected: layout.projection == projection) {
                            session.setVisionProjection(projection)
                        }
                    }
                }

                Menu("Eye Order") {
                    ForEach(VisionEyeOrder.allCases, id: \.self) { order in
                        choice(order.title, selected: layout.eyeOrder == order) {
                            session.setVisionEyeOrder(order)
                        }
                    }
                }
            }
        } label: {
            HStack(spacing: 10) {
                Image(.eye)
                    .font(.system(size: 15, weight: .bold))
                    .accessibilityHidden(true)
                if showsTitle {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Video Format")
                            .font(Typography.bodyLG)
                        Text(selection.preset.title)
                            .font(Typography.bodySM)
                            .foregroundStyle(Theme.textSecondary)
                    }
                    Spacer(minLength: 12)
                }
            }
            .foregroundStyle(Theme.textPrimary)
            .padding(.horizontal, showsTitle ? 12 : 0)
            .frame(
                minWidth: showsTitle ? 0 : 40,
                minHeight: 40,
                alignment: .leading
            )
            .glassBackground(in: showsTitle ? AnyShape(Capsule()) : AnyShape(Circle()))
            .contentShape(showsTitle ? AnyShape(Capsule()) : AnyShape(Circle()))
        }
        .playerChipStyle()
        .accessibilityLabel("Video Format")
        // In the icon-only form the current preset is not shown at all.
        .accessibilityValue(selection.preset.title)
    }

    private var selection: VisionFormatSelection {
        session.visionFormatSelection
    }

    private var supportsPackedPlayback: Bool {
        if #available(visionOS 26.0, *) { return true }
        return false
    }

    @ViewBuilder
    private func choice(
        _ title: String,
        selected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            HStack {
                Text(title)
                if selected {
                    Image(.check)
                }
            }
        }
    }
}

private extension VisionFormatPreset {
    var requiresPackedPlayback: Bool {
        switch self {
        case .automatic, .twoDimensional: false
        case .sideBySide, .topBottom, .vr180, .threeD360: true
        }
    }
}

private extension VisionFramePacking {
    var title: String {
        switch self {
        case .none: String(localized: "None")
        case .sideBySide: String(localized: "Side-by-Side")
        case .overUnder: String(localized: "Top-Bottom")
        }
    }
}

private extension VisionVideoProjection {
    var title: String {
        switch self {
        case .rectilinear: String(localized: "Flat 3D")
        case .halfEquirectangular: "VR180"
        case .equirectangular: "360°"
        }
    }
}

private extension VisionEyeOrder {
    var title: String {
        switch self {
        case .leftFirst: String(localized: "Left Eye First")
        case .reversed: String(localized: "Right Eye First")
        }
    }
}

#endif
