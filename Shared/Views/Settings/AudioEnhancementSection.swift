//
//  AudioEnhancementSection.swift
//  Edendale
//
//  Settings section for selecting and adjusting audio enhancement
//  profiles. Each profile applies a tuned 10-band equalizer curve;
//  the user can further adjust individual bands and the preamp.
//  The Settings page (macOS) offers the profiles as chips; the grouped
//  list (iOS/tvOS/visionOS) keeps a menu picker.
//
//  iPhone, iPad, Mac, and Vision Pro always show the bands as an
//  EqualizerGraph whose center line is 0 dB: a profile moves the points
//  to its curve, and the user drags them from there. Apple TV has no
//  slider or drag, so its equalizer button opens a decibel picker per band.
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
            equalizerButton
            if isExpanded {
                preampControl
                bandRows
                if controller.hasUserAdjustments {
                    resetButton
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
                    .font(SettingsMetrics.detailFont)
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
        #if os(macOS)
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
            }
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Profile")
        }
        #elseif os(tvOS)
        SettingsChoiceRow(
            String(localized: "Profile"),
            selection: profileBinding,
            options: AudioEnhancementProfile.allCases,
            name: \.displayName
        )
        #else
        HStack {
            profileTitle
            Spacer()
            Picker("Profile", selection: profileBinding) {
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

    private var profileBinding: Binding<AudioEnhancementProfile> {
        Binding(
            get: { controller.selectedProfile },
            set: { profile in
                animateEqualizer { controller.selectProfile(profile) }
            }
        )
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

    #if os(macOS)
    private let profileChipSize: FilterChip.Size = .regular
    #endif

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
            Text(isExpanded ? String(localized: "Hide equalizer") : String(localized: "Show equalizer"))
        }
    }
    #endif

    // MARK: - Preamp

    private var preampControl: some View {
        // Absolute levels, like the graph: the profile sets the preamp and
        // the user moves it from there. The player's booster adds its gain
        // on top without moving this.
        #if os(tvOS)
        decibelPicker(
            String(localized: "Preamp"),
            level: controller.preampLevel,
            range: AudioEnhancementProfile.preampRange
        ) { controller.setPreampLevel($0) }
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
            decibelPicker(
                AudioEnhancementProfile.bandFrequencyLabels[index] + " Hz",
                level: controller.effectiveBands[index],
                range: AudioEnhancementProfile.amplificationRange
            ) { controller.setBandLevel($0, at: index) }
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

    // MARK: - tvOS decibel picker

    #if os(tvOS)
    /// A level in whole decibels, as a menu of every step in its range.
    private func decibelPicker(
        _ title: String,
        level: Float,
        range: ClosedRange<Float>,
        set: @escaping (Float) -> Void
    ) -> some View {
        SettingsChoiceRow(
            title,
            selection: Binding(
                get: { Int(level.rounded()) },
                set: { set(Float($0)) }
            ),
            options: Array(Int(range.lowerBound.rounded())...Int(range.upperBound.rounded()))
        ) { value in
            decibelLabel(Float(value))
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
