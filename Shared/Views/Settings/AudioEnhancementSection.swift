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
//  iPhone, iPad, Mac, and Vision Pro always show the bands as an
//  EqualizerGraph whose center line is 0 dB: a profile moves the points
//  to its curve, and the user drags them from there. Apple TV keeps
//  remote-operated steppers behind its equalizer button.
//
//  visionOS native AVKit playback does not route through VLC, so
//  equalizer profiles have no effect when the system player is active.
//

import SwiftUI

struct AudioEnhancementSection: View {
    @Environment(AudioEnhancementController.self) private var controller
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    #if os(tvOS)
    /// Apple TV opens the stepper rows from its equalizer button.
    @State private var isExpanded = false
    #endif

    var body: some View {
        SettingsSection(String(localized: "Audio Enhancement")) {
            profilePicker
            #if os(tvOS)
            if isExpanded {
                SettingsRowGroup {
                    preampControl
                    bandRows
                    if controller.hasUserAdjustments {
                        resetButton
                    }
                }
            }
            #else
            SettingsRowGroup {
                equalizerGraph
                preampControl
                if controller.hasUserAdjustments {
                    resetButton
                }
            }
            #endif
        } footer: {
            VStack(alignment: .leading, spacing: 8) {
                Text("E-AC-3 surround tracks play through the system’s decoder without enhancement, keeping their full surround and spatial audio.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                #if os(visionOS)
                Text("Audio enhancement is unavailable during spatial and multiview playback in the system player.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                #endif
            }
        }
    }

    // MARK: - Profile

    @ViewBuilder
    private var profilePicker: some View {
        #if os(macOS) || os(tvOS)
        VStack(alignment: .leading, spacing: 14) {
            profileTitle

            FlowLayout(spacing: 10, lineSpacing: 10) {
                ForEach(AudioEnhancementProfile.allCases) { profile in
                    FilterChip(
                        title: profile.displayName,
                        isSelected: controller.selectedProfile == profile,
                        size: profileChipSize
                    ) {
                        animateEqualizer { controller.selectProfile(profile) }
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
                set: { profile in
                    animateEqualizer { controller.selectProfile(profile) }
                }
            )) {
                ForEach(AudioEnhancementProfile.allCases) { profile in
                    Text(profile.displayName).tag(profile)
                }
            }
            .labelsHidden()
            .pickerStyle(.menu)
            .tint(Theme.gold)
        }
        #endif
    }

    /// Profile changes and resets sweep the graph's points to their new
    /// levels; drags move them directly.
    private func animateEqualizer(_ change: () -> Void) {
        if reduceMotion {
            change()
        } else {
            withAnimation(.snappy(duration: 0.3)) { change() }
        }
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

    #if os(tvOS)
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
        }
        // Gold while the equalizer is open. The style owns the color so its
        // focused state can recolor the glyph.
        .archiveButtonStyle(.ghost, active: isExpanded)
        .accessibilityLabel(isExpanded
            ? String(localized: "Hide equalizer")
            : String(localized: "Show equalizer")
        )
    }
    #endif

    // MARK: - Preamp

    private var preampControl: some View {
        // Absolute levels, like the graph: the profile sets the preamp and
        // the user moves it from there. The player's booster adds its gain
        // on top without moving this.
        #if os(tvOS)
        stepperRow(
            label: String(localized: "Preamp"),
            effectiveValue: controller.preampLevel,
            onDecrement: {
                controller.setPreampLevel(controller.preampLevel - 1)
            },
            onIncrement: {
                controller.setPreampLevel(controller.preampLevel + 1)
            }
        )
        #else
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("Preamp")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                Spacer()
                Text(decibelLabel(controller.preampLevel))
                    .font(Typography.bodySM)
                    .monospacedDigit()
                    .foregroundStyle(Theme.gold)
            }
            Slider(
                value: Binding(
                    get: { controller.preampLevel },
                    set: { controller.setPreampLevel($0) }
                ),
                in: AudioEnhancementProfile.preampRange,
                step: 1
            )
            .tint(Theme.gold)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Preamp")
        .accessibilityValue(decibelLabel(controller.preampLevel))
        #endif
    }

    // MARK: - Bands

    #if os(tvOS)
    private var bandRows: some View {
        ForEach(0..<AudioEnhancementProfile.bandCount, id: \.self) { index in
            stepperRow(
                label: AudioEnhancementProfile.bandFrequencyLabels[index] + " Hz",
                effectiveValue: controller.effectiveBands[index],
                onDecrement: {
                    controller.setBandLevel(controller.effectiveBands[index] - 1, at: index)
                },
                onIncrement: {
                    controller.setBandLevel(controller.effectiveBands[index] + 1, at: index)
                }
            )
        }
    }
    #else
    private var equalizerGraph: some View {
        EqualizerGraph(
            levels: controller.effectiveBands,
            frequencyLabels: AudioEnhancementProfile.bandFrequencyLabels,
            range: AudioEnhancementProfile.amplificationRange
        ) { band, level in
            controller.setBandLevel(level, at: band)
        }
    }
    #endif

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
            animateEqualizer { controller.resetUserAdjustments() }
        }
        .archiveButtonStyle(.ghost)
        #else
        Button {
            animateEqualizer { controller.resetUserAdjustments() }
        } label: {
            Text("Reset Adjustments")
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.gold)
        }
        #endif
    }

    // MARK: - Formatting

    private func decibelLabel(_ value: Float) -> String {
        DecibelLabel.text(value)
    }
}
