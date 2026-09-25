//
//  AudioEnhancementSection.swift
//  Edendale
//
//  Settings section for selecting and adjusting audio enhancement
//  profiles. Each profile applies a tuned 10-band equalizer curve;
//  the user can further adjust individual bands and the preamp.
//  The Settings page (macOS/tvOS) offers the profiles as chips; the
//  grouped list (iOS/visionOS) keeps a menu picker.
//
//  visionOS native AVKit playback does not route through VLC, so
//  equalizer profiles have no effect when the system player is active.
//

import SwiftUI

struct AudioEnhancementSection: View {
    @Environment(AudioEnhancementController.self) private var controller
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @State private var isExpanded = false

    var body: some View {
        SettingsSection(String(localized: "Audio Enhancement")) {
            profilePicker
            if isExpanded {
                SettingsRowGroup {
                    preampControl
                    bandControls
                    if controller.hasUserAdjustments {
                        resetButton
                    }
                }
            }
        } footer: {
            #if os(visionOS)
            Text("Audio enhancement is unavailable during spatial and multiview playback in the system player.")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
            #endif
        }
    }

    // MARK: - Profile

    @ViewBuilder
    private var profilePicker: some View {
        #if os(macOS) || os(tvOS)
        VStack(alignment: .leading, spacing: 14) {
            #if os(macOS)
            HStack {
                profileTitle
                Spacer()
                equalizerButton
            }
            #else
            profileTitle
            #endif

            FlowLayout(spacing: 10, lineSpacing: 10) {
                ForEach(AudioEnhancementProfile.allCases) { profile in
                    FilterChip(
                        title: profile.displayName,
                        isSelected: controller.selectedProfile == profile,
                        size: profileChipSize
                    ) {
                        controller.selectProfile(profile)
                    }
                }
                #if os(tvOS)
                // Beside the chips, so left/right reaches it from any of them.
                equalizerButton
                #endif
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Profile")
        }
        #else
        HStack {
            profileTitle
            Spacer()
            Picker("Profile", selection: Binding(
                get: { controller.selectedProfile },
                set: { controller.selectProfile($0) }
            )) {
                ForEach(AudioEnhancementProfile.allCases) { profile in
                    Text(profile.displayName).tag(profile)
                }
            }
            .labelsHidden()
            .pickerStyle(.menu)
            .tint(Theme.gold)

            equalizerButton
        }
        #endif
    }

    private var profileChipSize: FilterChip.Size {
        #if os(tvOS)
        .large
        #else
        .regular
        #endif
    }

    private var profileTitle: some View {
        Text("Profile")
            .font(SettingsMetrics.titleFont)
            .foregroundStyle(Theme.textPrimary)
    }

    private var equalizerButton: some View {
        Button {
            if reduceMotion {
                isExpanded.toggle()
            } else {
                withAnimation(.easeInOut(duration: 0.2)) {
                    isExpanded.toggle()
                }
            }
        } label: {
            Image(systemName: "slider.horizontal.3")
                #if !os(macOS) && !os(tvOS)
                .foregroundStyle(isExpanded ? Theme.gold : Theme.textSecondary)
                #endif
        }
        #if os(macOS) || os(tvOS)
        // Gold while the equalizer is open. The style owns the color so its
        // focused state can recolor the glyph.
        .archiveButtonStyle(.ghost, active: isExpanded)
        #else
        .buttonStyle(.plain)
        #endif
        .accessibilityLabel(isExpanded
            ? String(localized: "Hide equalizer")
            : String(localized: "Show equalizer")
        )
    }

    // MARK: - Preamp

    private var preampControl: some View {
        #if os(tvOS)
        stepperRow(
            label: String(localized: "Preamp"),
            effectiveValue: controller.effectivePreamp,
            onDecrement: {
                controller.setUserPreampAdjustment(controller.userPreampAdjustment - 1)
            },
            onIncrement: {
                controller.setUserPreampAdjustment(controller.userPreampAdjustment + 1)
            }
        )
        #else
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Preamp")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                Spacer()
                Text(decibelLabel(controller.effectivePreamp))
                    .font(Typography.bodySM)
                    .monospacedDigit()
                    .foregroundStyle(Theme.gold)
            }
            Slider(
                value: Binding(
                    get: { controller.userPreampAdjustment },
                    set: { controller.setUserPreampAdjustment($0) }
                ),
                in: AudioEnhancementProfile.preampRange,
                step: 1
            )
            .tint(Theme.gold)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Preamp")
        .accessibilityValue(decibelLabel(controller.effectivePreamp))
        #endif
    }

    // MARK: - Bands

    @ViewBuilder
    private var bandControls: some View {
        #if os(macOS)
        // Two columns keep all ten bands in view on the page.
        LazyVGrid(
            columns: [GridItem(.flexible(), spacing: 32), GridItem(.flexible())],
            alignment: .leading,
            spacing: 18
        ) {
            bandRows
        }
        #else
        bandRows
        #endif
    }

    private var bandRows: some View {
        ForEach(0..<AudioEnhancementProfile.bandCount, id: \.self) { index in
            #if os(tvOS)
            stepperRow(
                label: AudioEnhancementProfile.bandFrequencyLabels[index] + " Hz",
                effectiveValue: controller.effectiveBands[index],
                onDecrement: {
                    controller.setUserBandAdjustment(
                        controller.userBandAdjustments[index] - 1,
                        at: index
                    )
                },
                onIncrement: {
                    controller.setUserBandAdjustment(
                        controller.userBandAdjustments[index] + 1,
                        at: index
                    )
                }
            )
            #else
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(AudioEnhancementProfile.bandFrequencyLabels[index] + " Hz")
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                        .frame(width: 52, alignment: .leading)
                    Spacer()
                    Text(decibelLabel(controller.effectiveBands[index]))
                        .font(Typography.bodySM)
                        .monospacedDigit()
                        .foregroundStyle(Theme.gold)
                }
                Slider(
                    value: Binding(
                        get: { controller.userBandAdjustments[index] },
                        set: { controller.setUserBandAdjustment($0, at: index) }
                    ),
                    in: AudioEnhancementProfile.amplificationRange,
                    step: 1
                )
                .tint(Theme.gold)
            }
            .accessibilityElement(children: .combine)
            .accessibilityLabel(AudioEnhancementProfile.bandFrequencyLabels[index] + " Hz")
            .accessibilityValue(decibelLabel(controller.effectiveBands[index]))
            #endif
        }
    }

    // MARK: - tvOS stepper row

    #if os(tvOS)
    private func stepperRow(
        label: String,
        effectiveValue: Float,
        onDecrement: @escaping () -> Void,
        onIncrement: @escaping () -> Void
    ) -> some View {
        HStack(spacing: 16) {
            Text(label)
                .font(SettingsMetrics.detailFont)
                .foregroundStyle(Theme.textSecondary)
            Spacer()
            // Archive chrome: the system focus platter turns white under
            // parchment glyphs and hides them.
            Button("−") { onDecrement() }
                .archiveButtonStyle(.secondary)
            Text(decibelLabel(effectiveValue))
                .font(SettingsMetrics.detailFont)
                .monospacedDigit()
                .foregroundStyle(Theme.gold)
                .frame(minWidth: 100)
            Button("+") { onIncrement() }
                .archiveButtonStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(label)
        .accessibilityValue(decibelLabel(effectiveValue))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: onIncrement()
            case .decrement: onDecrement()
            @unknown default: break
            }
        }
    }
    #endif

    // MARK: - Reset

    private var resetButton: some View {
        #if os(macOS) || os(tvOS)
        Button("Reset Adjustments") {
            controller.resetUserAdjustments()
        }
        .archiveButtonStyle(.ghost)
        #else
        Button {
            controller.resetUserAdjustments()
        } label: {
            Text("Reset Adjustments")
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.gold)
        }
        #endif
    }

    // MARK: - Formatting

    private func decibelLabel(_ value: Float) -> String {
        let rounded = Int(value.rounded())
        if rounded > 0 {
            return "+\(rounded) dB"
        } else if rounded < 0 {
            return "\(rounded) dB"
        }
        return "0 dB"
    }
}
