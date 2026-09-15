//
//  AudioEnhancementSection.swift
//  Edendale
//
//  Settings section for selecting and adjusting audio enhancement
//  profiles. Each profile applies a tuned 10-band equalizer curve;
//  the user can further adjust individual bands and the preamp.
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
        Section {
            profilePicker
            if isExpanded {
                preampControl
                bandControls
                if controller.hasUserAdjustments {
                    resetButton
                }
            }
        } header: {
            Text("Audio Enhancement").labelCaps()
        } footer: {
            #if os(visionOS)
            Text("Audio enhancement is unavailable during spatial and multiview playback in the system player.")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
            #endif
        }
    }

    private var profilePicker: some View {
        HStack {
            Text("Profile")
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.textPrimary)
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
            #if os(tvOS)
            .pickerStyle(.automatic)
            #else
            .pickerStyle(.menu)
            #endif
            .tint(Theme.gold)

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
                    .foregroundStyle(isExpanded ? Theme.gold : Theme.textSecondary)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isExpanded
                ? String(localized: "Hide equalizer")
                : String(localized: "Show equalizer")
            )
        }
    }

    // MARK: - Preamp

    private var preampControl: some View {
        #if os(tvOS)
        stepperRow(
            label: "Preamp",
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

    private var bandControls: some View {
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
        HStack(spacing: 12) {
            Text(label)
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
                .frame(width: 52, alignment: .leading)
            Spacer()
            Button("−") { onDecrement() }
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.textPrimary)
            Text(decibelLabel(effectiveValue))
                .font(Typography.bodySM)
                .monospacedDigit()
                .foregroundStyle(Theme.gold)
                .frame(minWidth: 60)
            Button("+") { onIncrement() }
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.textPrimary)
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
        Button {
            controller.resetUserAdjustments()
        } label: {
            Text("Reset Adjustments")
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.gold)
        }
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
