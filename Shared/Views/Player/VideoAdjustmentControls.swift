import SwiftUI

/// The same picture controls appear in Settings and the player's side panel.
struct VideoAdjustmentControls: View {
    @Bindable var controller: VideoAdjustmentController

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            ForEach(VideoAdjustment.allCases) { adjustment in
                VideoAdjustmentSlider(
                    adjustment: adjustment,
                    value: Binding(
                        get: { controller.values[adjustment] },
                        set: { controller.set(adjustment, to: $0) }
                    )
                )
            }

            Text("Increase gamma to brighten dark and middle tones. High settings can reveal noise or wash out the picture.")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)

            ArchiveToggle(isOn: Binding(
                get: { controller.isShowingOriginal },
                set: { controller.showOriginal($0) }
            )) {
                Text("Show Original")
                    .font(Typography.bodyLG)
                    .foregroundStyle(Theme.textPrimary)
            }
            .disabled(controller.values.isNeutral)

            Button("Reset Picture") { controller.reset() }
                .archiveButtonStyle(.ghost)
                .disabled(controller.values.isNeutral)
        }
    }
}

private struct VideoAdjustmentSlider: View {
    let adjustment: VideoAdjustment
    @Binding var value: Float
    #if os(tvOS)
    @FocusState private var isFocused: Bool
    #endif

    var body: some View {
        #if os(tvOS)
        // SwiftUI Slider is unavailable on tvOS. A focusable track supports
        // remote left/right and VoiceOver's adjustable action independently.
        VStack(alignment: .leading, spacing: 12) {
            label
            GeometryReader { geometry in
                let fraction = CGFloat((value - adjustment.range.lowerBound)
                    / (adjustment.range.upperBound - adjustment.range.lowerBound))
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
        .background(isFocused ? Theme.surface : .clear,
                    in: RoundedRectangle(cornerRadius: Theme.Radius.soft))
        .overlay {
            RoundedRectangle(cornerRadius: Theme.Radius.soft)
                .strokeBorder(isFocused ? Theme.gold : .clear, lineWidth: 2)
        }
        .focusable()
        .focused($isFocused)
        .onMoveCommand { direction in
            switch direction {
            case .left: change(by: -adjustment.step)
            case .right: change(by: adjustment.step)
            default: break
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(adjustment.title)
        .accessibilityValue(adjustment.label(for: value))
        .accessibilityHint("Use left and right to adjust.")
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: change(by: adjustment.step)
            case .decrement: change(by: -adjustment.step)
            @unknown default: break
            }
        }
        #else
        VStack(alignment: .leading, spacing: 8) {
            label.accessibilityHidden(true)
            Slider(value: $value, in: adjustment.range, step: adjustment.step) {
                Text(adjustment.title)
            }
            .labelsHidden()
            .tint(Theme.gold)
            .accessibilityValue(adjustment.label(for: value))
        }
        #endif
    }

    private var label: some View {
        HStack {
            Text(adjustment.title)
                .foregroundStyle(Theme.textPrimary)
            Spacer(minLength: 8)
            Text(adjustment.label(for: value))
                .monospacedDigit()
                .foregroundStyle(Theme.gold)
        }
        .font(Typography.bodyLG)
    }

    private func change(by delta: Float) {
        value = adjustment.normalized(value + delta)
    }
}
