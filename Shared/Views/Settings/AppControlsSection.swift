//
//  AppControlsSection.swift
//  Edendale
//
//  Settings for the player's quick controls: how far a skip jumps back and
//  forward, chosen with segmented 10 / 15 / 30-second controls drawn with
//  the matching arrow-rotate glyphs, and the speed each side of the video
//  plays at while held, set with a stepper. On tvOS both are archive
//  chrome: there is no system stepper, so speeds step with − and + buttons,
//  and the system segmented control's white focus platter hides the glyphs.
//  The section closes with the player guide, which shows every control.
//

import SwiftUI
#if canImport(UIKit)
import UIKit
#else
import AppKit
#endif

struct AppControlsSection: View {
    @Environment(PlayerSession.self) private var session
    @State private var showGuide = false

    private var controls: PlayerControlPreferences { session.controls }

    var body: some View {
        SettingsSection(String(localized: "App Controls")) {
            skipRow(.backward)
            skipRow(.forward)
            #if !os(macOS) && !os(tvOS)
            SettingsNote(skipNote)
            #endif
            holdRow(.left)
            holdRow(.right)
            #if !os(macOS) && !os(tvOS)
            SettingsNote(holdNote)
            #endif
            guideRow
        }
    }

    // MARK: - Player guide

    @ViewBuilder
    private var guideRow: some View {
        #if os(macOS) || os(tvOS)
        SettingsRow(String(localized: "Player Guide"), detail: guideDetail) {
            Button("Show") { showGuide = true }
                .archiveButtonStyle(.secondary)
                .accessibilityLabel("Show Player Guide")
                .sheet(isPresented: $showGuide) { guide }
        }
        #else
        Button("Show Player Guide") { showGuide = true }
            .sheet(isPresented: $showGuide) { guide }
        #endif
    }

    private var guide: some View {
        PlayerGuideView(controls: controls, finishTitle: String(localized: "Done")) {
            showGuide = false
        }
        #if os(macOS)
        .frame(minWidth: 760, idealWidth: 880, minHeight: 540, idealHeight: 600)
        #endif
    }

    #if os(macOS) || os(tvOS)
    private var guideDetail: String {
        #if os(macOS)
        String(localized: "See how the pointer and keyboard control playback.")
        #else
        String(localized: "See how the Siri Remote controls playback.")
        #endif
    }
    #endif

    // MARK: - Skip lengths

    @ViewBuilder
    private func skipRow(_ direction: SkipDirection) -> some View {
        #if os(macOS) || os(tvOS)
        SettingsRow(skipTitle(direction), detail: skipDetail(direction)) {
            skipPicker(direction)
        }
        #else
        LabeledContent(skipTitle(direction)) {
            skipPicker(direction)
        }
        #endif
    }

    @ViewBuilder
    private func skipPicker(_ direction: SkipDirection) -> some View {
        #if os(tvOS)
        SkipSegments(direction: direction, selection: skipBinding(direction))
            .accessibilityElement(children: .contain)
            .accessibilityLabel(skipTitle(direction))
        #else
        Picker(skipTitle(direction), selection: skipBinding(direction)) {
            ForEach(SkipInterval.allCases) { interval in
                interval.segmentImage(for: direction)
                    .help(interval.spokenName)
                    .tag(interval)
            }
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .fixedSize()
        #if os(macOS)
        // The large size draws the selection as a pill and gives the
        // glyphs' numerals room to be read.
        .controlSize(.large)
        #endif
        // `labelsHidden` also takes the name off the segments' group.
        .accessibilityLabel(skipTitle(direction))
        #endif
    }

    private func skipBinding(_ direction: SkipDirection) -> Binding<SkipInterval> {
        Binding(
            get: { controls.skipInterval(for: direction) },
            set: { interval in
                switch direction {
                case .backward: controls.skipBackwardInterval = interval
                case .forward: controls.skipForwardInterval = interval
                }
            }
        )
    }

    private func skipTitle(_ direction: SkipDirection) -> String {
        switch direction {
        case .backward: String(localized: "Skip Back")
        case .forward: String(localized: "Skip Forward")
        }
    }

    // MARK: - Hold speeds

    @ViewBuilder
    private func holdRow(_ side: HoldSide) -> some View {
        #if os(tvOS)
        SettingsRow(holdTitle(side), detail: holdDetail(side)) {
            holdButtons(side)
        }
        #elseif os(macOS)
        SettingsRow(holdTitle(side), value: holdRateLabel(side), detail: holdDetail(side)) {
            holdStepper(side)
                .labelsHidden()
        }
        #else
        holdStepper(side) {
            LabeledContent(holdTitle(side), value: holdRateLabel(side))
        }
        #endif
    }

    private func holdRateLabel(_ side: HoldSide) -> String {
        PlayerLogic.rateLabel(controls.holdRate(for: side))
    }

    #if !os(tvOS)
    private func holdStepper(_ side: HoldSide) -> some View {
        holdStepper(side) { Text(holdTitle(side)) }
    }

    private func holdStepper(_ side: HoldSide, @ViewBuilder label: () -> some View) -> some View {
        Stepper(
            value: Binding(
                get: { controls.holdRate(for: side) },
                set: { controls.setHoldRate($0, for: side) }
            ),
            in: PlayerControlPreferences.holdRateRange,
            step: PlayerControlPreferences.holdRateStep,
            label: label
        )
        // The stepper only knows a number; speak it as the speed shown.
        .accessibilityLabel(holdTitle(side))
        .accessibilityValue(holdRateLabel(side))
    }
    #endif

    #if os(tvOS)
    /// A stepper in archive chrome: the system focus platter turns white
    /// under parchment glyphs and hides them.
    private func holdButtons(_ side: HoldSide) -> some View {
        HStack(spacing: 16) {
            Button("−") { stepHoldRate(side, by: -1) }
                .archiveButtonStyle(.secondary)
            Text(holdRateLabel(side))
                .font(SettingsMetrics.titleFont)
                .monospacedDigit()
                .foregroundStyle(Theme.gold)
                .frame(minWidth: 110)
            Button("+") { stepHoldRate(side, by: 1) }
                .archiveButtonStyle(.secondary)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(holdTitle(side))
        .accessibilityValue(holdRateLabel(side))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: stepHoldRate(side, by: 1)
            case .decrement: stepHoldRate(side, by: -1)
            @unknown default: break
            }
        }
    }

    private func stepHoldRate(_ side: HoldSide, by steps: Float) {
        let rate = controls.holdRate(for: side)
        controls.setHoldRate(rate + steps * PlayerControlPreferences.holdRateStep, for: side)
    }
    #endif

    private func holdTitle(_ side: HoldSide) -> String {
        switch side {
        case .left: String(localized: "Hold Left Side")
        case .right: String(localized: "Hold Right Side")
        }
    }

    // MARK: - Explanations

    #if os(macOS) || os(tvOS)
    /// On a page each setting explains itself beneath its name.
    private func skipDetail(_ direction: SkipDirection) -> String {
        #if os(macOS)
        switch direction {
        case .backward: String(localized: "Press the Left Arrow key during playback.")
        case .forward: String(localized: "Press the Right Arrow key during playback.")
        }
        #else
        switch direction {
        case .backward: String(localized: "Swipe left on the remote during playback.")
        case .forward: String(localized: "Swipe right on the remote during playback.")
        }
        #endif
    }

    private func holdDetail(_ side: HoldSide) -> String {
        #if os(macOS)
        switch side {
        case .left: String(localized: "Plays at this speed while you click and hold the left half of the video.")
        case .right: String(localized: "Plays at this speed while you click and hold the right half of the video.")
        }
        #else
        switch side {
        case .left: String(localized: "Plays at this speed while your thumb rests on the left side of the remote’s touch surface.")
        case .right: String(localized: "Plays at this speed while your thumb rests on the right side of the remote’s touch surface.")
        }
        #endif
    }
    #else
    /// The grouped List explains each pair once, below it.
    private var skipNote: String {
        #if os(visionOS)
        String(localized: "Tap the left or right side of the video twice to skip back or forward.")
        #else
        String(localized: "Double-tap the left or right side of the video to skip back or forward. The Lock Screen and Control Center use the same lengths.")
        #endif
    }

    private var holdNote: String {
        #if os(visionOS)
        String(localized: "Pinch and hold the left or right side of the video to play at that speed until you let go.")
        #else
        String(localized: "Touch and hold the left or right side of the video to play at that speed until you let go.")
        #endif
    }
    #endif
}

private extension SkipInterval {
    /// What the glyph's numeral says, for VoiceOver and the pointer tooltip.
    var spokenName: String {
        String(localized: "\(seconds) seconds")
    }

    /// The arrow-rotate glyph for this length: counterclockwise going back,
    /// clockwise going forward.
    func icon(for direction: SkipDirection) -> ImageResource {
        switch (direction, self) {
        case (.backward, .ten): .arrowRotateLeft10
        case (.backward, .fifteen): .arrowRotateLeft15
        case (.backward, .thirty): .arrowRotateLeft30
        case (.forward, .ten): .arrowRotateRight10
        case (.forward, .fifteen): .arrowRotateRight15
        case (.forward, .thirty): .arrowRotateRight30
        }
    }

    #if !os(tvOS)
    /// The glyph as a platform image that carries its spoken name. A
    /// segmented control shows only the image of each segment and reads
    /// its name from the image, never from a SwiftUI accessibility label.
    func segmentImage(for direction: SkipDirection) -> Image {
        #if canImport(UIKit)
        let image = UIImage(resource: icon(for: direction))
            // A copy, so the label never lands on the shared cached image.
            .withRenderingMode(.alwaysTemplate)
        image.accessibilityLabel = spokenName
        return Image(uiImage: image)
        #else
        let resource = NSImage(resource: icon(for: direction))
        let image = resource.withSymbolConfiguration(.init(pointSize: 17, weight: .regular))
            ?? resource.copy() as? NSImage
            ?? resource
        image.accessibilityDescription = spokenName
        return Image(nsImage: image)
        #endif
    }
    #endif
}

#if os(tvOS)
/// The skip lengths as a segmented control in archive chrome. The system
/// control floods its focused segment with a white platter that glares
/// across the room and hides the glyph. Here the chosen length sits on a
/// dim pill in gold, and focus is the gold ring and glow the Settings rows
/// use.
private struct SkipSegments: View {
    let direction: SkipDirection
    @Binding var selection: SkipInterval

    var body: some View {
        HStack(spacing: 4) {
            ForEach(SkipInterval.allCases) { interval in
                Button {
                    selection = interval
                } label: {
                    SkipSegmentLabel(
                        icon: interval.icon(for: direction),
                        isSelected: selection == interval
                    )
                }
                // Label-only chrome, as for FilterChip: even `.plain` paints
                // the system platter behind a focused button.
                .buttonStyle(ChipButtonStyle())
                .focusEffectDisabled()
                .accessibilityLabel(interval.spokenName)
                .accessibilityAddTraits(selection == interval ? [.isButton, .isSelected] : .isButton)
            }
        }
        .padding(6)
        .background(Theme.background, in: Capsule())
        .overlay {
            Capsule().strokeBorder(Theme.hairline, lineWidth: 1)
        }
    }
}

/// One segment's face. Kept apart from the button so it can read
/// `\.isFocused`, which reflects the button's focus only inside its label.
private struct SkipSegmentLabel: View {
    let icon: ImageResource
    let isSelected: Bool
    @Environment(\.isFocused) private var isFocused

    var body: some View {
        Image(icon)
            .font(Typography.text(32, weight: .medium))
            .foregroundStyle(foreground)
            .frame(width: 88, height: 64)
            .background(isSelected || isFocused ? Theme.surfaceHigh : .clear, in: Capsule())
            .overlay {
                Capsule().strokeBorder(isFocused ? Theme.gold : .clear, lineWidth: 2)
            }
            .shadow(color: isFocused ? Theme.goldGlow : .clear, radius: 14)
            .scaleEffect(isFocused ? 1.08 : 1)
            .animation(.easeOut(duration: 0.15), value: isFocused)
            .contentShape(Capsule())
    }

    private var foreground: Color {
        if isSelected { return Theme.gold }
        return isFocused ? Theme.textPrimary : Theme.textSecondary
    }
}
#endif
