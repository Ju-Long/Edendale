//
//  VideoEnhancementControls.swift
//  Edendale
//
//  Enhancement preset picker, sharpness/denoise sliders, show-original
//  toggle, and resolution info label for the Metal video enhancement
//  pipeline. Follows the same pattern as VideoAdjustmentControls.
//

import SwiftUI

struct VideoEnhancementControls: View {
    @Bindable var pipeline: EnhancementPipeline
    var sourceSize: CGSize = .zero

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            presetPicker
            sharpnessSlider
            denoiseSlider
            showOriginalToggle
            if sourceSize.width > 0, sourceSize.height > 0 {
                resolutionInfo
            }
        }
    }

    // MARK: - Preset

    private var presetPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Enhancement")
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.textPrimary)

            Picker("Enhancement Preset", selection: $pipeline.preset) {
                ForEach(EnhancementPreset.allCases) { preset in
                    Text(preset.displayName).tag(preset)
                }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        }
    }

    // MARK: - Sharpness

    private var sharpnessSlider: some View {
        enhancementSlider(
            title: String(localized: "Sharpness"),
            value: $pipeline.sharpness,
            range: 0 ... 1,
            step: 0.05
        )
        .disabled(pipeline.preset == .off)
    }

    // MARK: - Denoise

    private var denoiseSlider: some View {
        enhancementSlider(
            title: String(localized: "Denoise"),
            value: $pipeline.denoiseStrength,
            range: 0 ... 1,
            step: 0.05
        )
        .disabled(pipeline.preset != .quality)
    }

    // MARK: - Show original

    private var showOriginalToggle: some View {
        ArchiveToggle(isOn: Binding(
            get: { !pipeline.isEnabled },
            set: { pipeline.isEnabled = !$0 }
        )) {
            Text("Show Original")
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.textPrimary)
        }
        .disabled(pipeline.preset == .off)
    }

    // MARK: - Resolution info

    private var resolutionInfo: some View {
        let target = targetResolutionLabel
        return HStack(spacing: 4) {
            Text("\(Int(sourceSize.width))×\(Int(sourceSize.height))")
                .foregroundStyle(Theme.textSecondary)
            if let target {
                Image(systemName: "arrow.right")
                    .font(.system(size: 10, weight: .bold))
                    .foregroundStyle(Theme.textSecondary)
                Text(target)
                    .foregroundStyle(Theme.gold)
            }
        }
        .font(Typography.bodySM)
        .monospacedDigit()
    }

    private var targetResolutionLabel: String? {
        guard pipeline.isEnabled, pipeline.preset != .off, pipeline.preset != .sharpenOnly else {
            return nil
        }
        let target = SpatialUpscaler.targetResolution(
            for: sourceSize,
            displaySize: pipeline.displaySize
        )
        guard Int(target.width) != Int(sourceSize.width)
                || Int(target.height) != Int(sourceSize.height) else {
            return nil
        }
        return "\(Int(target.width))×\(Int(target.height))"
    }

    // MARK: - Slider builder

    @ViewBuilder
    private func enhancementSlider(
        title: String,
        value: Binding<Float>,
        range: ClosedRange<Float>,
        step: Float
    ) -> some View {
        #if os(tvOS)
        TVEnhancementSlider(
            title: title,
            value: value,
            range: range,
            step: step
        )
        #else
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title)
                    .foregroundStyle(Theme.textPrimary)
                Spacer(minLength: 8)
                Text(value.wrappedValue.formatted(.number.precision(.fractionLength(2))))
                    .monospacedDigit()
                    .foregroundStyle(Theme.gold)
            }
            .font(Typography.bodyLG)
            .accessibilityHidden(true)

            Slider(value: value, in: range, step: step) {
                Text(title)
            }
            .labelsHidden()
            .tint(Theme.gold)
            .accessibilityValue(
                value.wrappedValue.formatted(.number.precision(.fractionLength(2)))
            )
        }
        #endif
    }
}

// MARK: - tvOS slider

#if os(tvOS)
private struct TVEnhancementSlider: View {
    let title: String
    @Binding var value: Float
    let range: ClosedRange<Float>
    let step: Float

    @FocusState private var isFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text(title)
                    .foregroundStyle(Theme.textPrimary)
                Spacer(minLength: 8)
                Text(value.formatted(.number.precision(.fractionLength(2))))
                    .monospacedDigit()
                    .foregroundStyle(Theme.gold)
            }
            .font(Typography.bodyLG)

            GeometryReader { geometry in
                let fraction = CGFloat(
                    (value - range.lowerBound) / (range.upperBound - range.lowerBound)
                )
                ZStack(alignment: .leading) {
                    Capsule().fill(Theme.surfaceHigh)
                    Capsule().fill(Theme.gold)
                        .frame(width: max(0, geometry.size.width * fraction))
                    Circle().fill(Theme.gold)
                        .frame(width: 18, height: 18)
                        .offset(x: max(0, (geometry.size.width - 18) * fraction))
                }
                .frame(height: 6)
                .frame(maxHeight: .infinity)
            }
            .frame(height: 24)
        }
        .padding(12)
        .background(
            isFocused ? Theme.surface : .clear,
            in: RoundedRectangle(cornerRadius: Theme.Radius.soft)
        )
        .overlay {
            RoundedRectangle(cornerRadius: Theme.Radius.soft)
                .strokeBorder(isFocused ? Theme.gold : .clear, lineWidth: 2)
        }
        .focusable()
        .focused($isFocused)
        .onMoveCommand { direction in
            switch direction {
            case .left: change(by: -step)
            case .right: change(by: step)
            default: break
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(title)
        .accessibilityValue(value.formatted(.number.precision(.fractionLength(2))))
        .accessibilityHint("Use left and right to adjust.")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: change(by: step)
            case .decrement: change(by: -step)
            @unknown default: break
            }
        }
    }

    private func change(by delta: Float) {
        let newValue = value + delta
        value = min(max(newValue, range.lowerBound), range.upperBound)
    }
}
#endif
