//
//  SubtitleAppearanceSection.swift
//  Edendale
//
//  Settings for how text subtitles look: the typeface, the text colour,
//  and the colour and opacity of the box behind each cue, above a preview
//  frame that draws a sample cue exactly as the player will. The Settings
//  page (macOS/tvOS) offers the choices as chips; the grouped list
//  (iOS/visionOS) keeps menu pickers. tvOS has no slider, so opacity steps
//  with − and + buttons there, beneath its name like the chip rows.
//

import SwiftUI

struct SubtitleAppearanceSection: View {
    @Environment(PlayerSession.self) private var session

    private var appearance: SubtitleAppearance { session.subtitleAppearance }

    var body: some View {
        SettingsSection(String(localized: "Subtitles")) {
            SubtitleAppearancePreview(appearance: appearance)

            choiceRow(
                String(localized: "Font"),
                options: SubtitleFontStyle.allCases,
                name: \.displayName,
                selection: Binding(
                    get: { appearance.font },
                    set: { appearance.font = $0 }
                )
            )
            choiceRow(
                String(localized: "Text Colour"),
                options: SubtitleTextColor.allCases,
                name: \.displayName,
                selection: Binding(
                    get: { appearance.textColor },
                    set: { appearance.textColor = $0 }
                )
            )
            choiceRow(
                String(localized: "Background Colour"),
                options: SubtitleBackgroundColor.allCases,
                name: \.displayName,
                selection: Binding(
                    get: { appearance.backgroundColor },
                    set: { appearance.backgroundColor = $0 }
                )
            )
            opacityRow

            if !appearance.isDefault {
                resetButton
            }
        } footer: {
            #if !os(macOS) && !os(tvOS)
            Text("Applies to text subtitles. Image-based subtitles keep the look they were authored with.")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
            #endif
        }
    }

    // MARK: - Choices

    @ViewBuilder
    private func choiceRow<Option: Identifiable & Hashable>(
        _ title: String,
        options: [Option],
        name: KeyPath<Option, String>,
        selection: Binding<Option>
    ) -> some View {
        #if os(macOS) || os(tvOS)
        VStack(alignment: .leading, spacing: 14) {
            Text(title)
                .font(SettingsMetrics.titleFont)
                .foregroundStyle(Theme.textPrimary)
            FlowLayout(spacing: 10, lineSpacing: 10) {
                ForEach(options) { option in
                    FilterChip(
                        title: option[keyPath: name],
                        isSelected: selection.wrappedValue == option,
                        size: chipSize
                    ) {
                        selection.wrappedValue = option
                    }
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel(title)
        }
        #else
        Picker(title, selection: selection) {
            ForEach(options) { option in
                Text(option[keyPath: name]).tag(option)
            }
        }
        .pickerStyle(.menu)
        .tint(Theme.gold)
        #endif
    }

    private var chipSize: FilterChip.Size {
        #if os(tvOS)
        .large
        #else
        .regular
        #endif
    }

    // MARK: - Opacity

    private var opacityTitle: String { String(localized: "Background Opacity") }

    private var opacityLabel: String {
        appearance.backgroundOpacity.formatted(.percent.precision(.fractionLength(0)))
    }

    @ViewBuilder
    private var opacityRow: some View {
        #if os(tvOS)
        // Laid out like the choices above: the name, then the control
        // beneath it at the leading edge.
        VStack(alignment: .leading, spacing: 14) {
            Text(opacityTitle)
                .font(SettingsMetrics.titleFont)
                .foregroundStyle(Theme.textPrimary)
            HStack(spacing: 16) {
                // Archive chrome: the system focus platter turns white under
                // parchment glyphs and hides them.
                Button("−") { stepOpacity(by: -1) }
                    .archiveButtonStyle(.secondary)
                Text(opacityLabel)
                    .font(SettingsMetrics.titleFont)
                    .monospacedDigit()
                    .foregroundStyle(Theme.gold)
                    .frame(minWidth: 110)
                Button("+") { stepOpacity(by: 1) }
                    .archiveButtonStyle(.secondary)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(opacityTitle)
            .accessibilityValue(opacityLabel)
            .accessibilityAdjustableAction { direction in
                switch direction {
                case .increment: stepOpacity(by: 1)
                case .decrement: stepOpacity(by: -1)
                @unknown default: break
                }
            }
        }
        #else
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(opacityTitle)
                    #if os(macOS)
                    .font(SettingsMetrics.titleFont)
                    .foregroundStyle(Theme.textPrimary)
                    #endif
                Spacer()
                Text(opacityLabel)
                    .font(Typography.bodySM)
                    .monospacedDigit()
                    .foregroundStyle(Theme.gold)
            }
            Slider(
                value: Binding(
                    get: { appearance.backgroundOpacity },
                    set: { appearance.setBackgroundOpacity($0) }
                ),
                in: 0...1,
                step: SubtitleAppearance.backgroundOpacityStep
            )
            .tint(Theme.gold)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(opacityTitle)
        .accessibilityValue(opacityLabel)
        #endif
    }

    #if os(tvOS)
    private func stepOpacity(by steps: Double) {
        appearance.setBackgroundOpacity(
            appearance.backgroundOpacity + steps * SubtitleAppearance.backgroundOpacityStep
        )
    }
    #endif

    // MARK: - Reset

    private var resetButton: some View {
        #if os(macOS) || os(tvOS)
        Button("Reset Subtitle Style") {
            appearance.reset()
        }
        .archiveButtonStyle(.ghost)
        #else
        Button {
            appearance.reset()
        } label: {
            Text("Reset Subtitle Style")
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.gold)
        }
        #endif
    }
}

// MARK: - Preview

/// A still "frame" with a sample cue drawn through the player's own cue
/// view and sizing, so what the viewer sees here is what plays.
private struct SubtitleAppearancePreview: View {
    let appearance: SubtitleAppearance

    @State private var renderer = TimedTextRenderer(device: nil)
    @ScaledMetric(relativeTo: .body) private var fontScale: CGFloat = 1

    private var sample: String {
        String(localized: "The archive never forgets a film.\nIt only waits for someone to press play.")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            #if os(macOS) || os(tvOS)
            Text("Preview")
                .font(SettingsMetrics.titleFont)
                .foregroundStyle(Theme.textPrimary)
            #endif

            GeometryReader { geometry in
                ZStack(alignment: .bottom) {
                    scene
                    SubtitleTextCueView(
                        rawText: sample,
                        renderer: renderer,
                        appearance: appearance,
                        // The player's sizing for a picture this tall.
                        fontSize: min(48, max(16, geometry.size.height * 0.055)) * fontScale
                    )
                    .padding(.horizontal, max(16, geometry.size.width * 0.05))
                    .padding(.bottom, geometry.size.height * 0.06)
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
            }
            .aspectRatio(16 / 9, contentMode: .fit)
            .frame(maxWidth: maxPreviewWidth)
            .clipShape(RoundedRectangle(cornerRadius: Theme.Radius.card))
            .overlay {
                RoundedRectangle(cornerRadius: Theme.Radius.card)
                    .strokeBorder(Theme.hairline, lineWidth: 1)
            }
            .frame(maxWidth: .infinity)
            #if !os(macOS) && !os(tvOS)
            .padding(.vertical, 6)
            #endif
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Subtitle preview")
        .accessibilityValue(sample)
    }

    /// A dusk sky over low dark hills. The cue straddles the bright horizon
    /// and the dark ground, so both the box's colour and its opacity show.
    private var scene: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let height = geometry.size.height
            ZStack {
                LinearGradient(
                    colors: [
                        Color(red: 0.18, green: 0.24, blue: 0.42),
                        Color(red: 0.86, green: 0.5, blue: 0.32),
                        Color(red: 0.98, green: 0.82, blue: 0.55)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                Circle()
                    .fill(Color(red: 1, green: 0.93, blue: 0.75))
                    .frame(width: height * 0.2, height: height * 0.2)
                    .position(x: width * 0.68, y: height * 0.62)
                Ellipse()
                    .fill(Color(red: 0.2, green: 0.17, blue: 0.2))
                    .frame(width: width * 1.2, height: height * 0.3)
                    .position(x: width * 0.3, y: height * 1.02)
                Ellipse()
                    .fill(Color(red: 0.1, green: 0.09, blue: 0.11))
                    .frame(width: width * 1.1, height: height * 0.24)
                    .position(x: width * 0.85, y: height * 1.04)
            }
        }
        .clipped()
        .accessibilityHidden(true)
    }

    private var maxPreviewWidth: CGFloat {
        #if os(tvOS)
        960
        #else
        640
        #endif
    }
}
