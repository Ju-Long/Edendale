//
//  ArchiveToggle.swift
//  Edendale
//
//  An on/off option row. Everywhere but tvOS this is the system `Toggle`
//  with a gold switch.
//
//  tvOS draws a system toggle as a *filled* row — the tint color at rest,
//  the white focus platter when focused — and leaves the label's own color
//  alone, so parchment copy sits on gold and disappears entirely on white.
//  There the option becomes a plain row button instead: the state is spelled
//  out beside the label, and `archiveRowStyle` supplies the focus treatment
//  (surface fill, gold border, glow) that keeps the copy legible.
//

import SwiftUI

struct ArchiveToggle<Label: View>: View {
    @Binding private var isOn: Bool
    private let label: Label

    init(isOn: Binding<Bool>, @ViewBuilder label: () -> Label) {
        self._isOn = isOn
        self.label = label()
    }

    var body: some View {
        #if os(tvOS)
        Button {
            isOn.toggle()
        } label: {
            HStack(spacing: 16) {
                label
                Spacer(minLength: 12)
                // Colored here rather than on the label so the two never
                // fight over the row's foreground.
                Text(isOn ? "On" : "Off")
                    .font(Typography.bodyLG)
                    .textCase(.uppercase)
                    .foregroundStyle(isOn ? Theme.gold : Theme.textSecondary)
                    // Spoken as the element's value below, not as a second
                    // word tacked onto its label.
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .archiveRowStyle()
        // A row button standing in for a switch still has to sound like one.
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isToggle)
        .accessibilityValue(isOn ? Text("On") : Text("Off"))
        #else
        Toggle(isOn: $isOn) { label }
            .tint(Theme.gold)
        #endif
    }
}
