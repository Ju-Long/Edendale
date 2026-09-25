//
//  SettingsLayout.swift
//  Edendale
//
//  The blocks every Settings section is built from. macOS and tvOS host
//  Settings as a tab, so there it is a full page like the other tabs: each
//  section is a Bebas SectionHeader over a dim archive card whose rows are
//  divided by hairlines. iOS (a sheet) and visionOS (an ornament tab) keep
//  the grouped system List, where each block breaks back down into the plain
//  rows that List has always shown.
//

import SwiftUI

// MARK: - Metrics

enum SettingsMetrics {
    /// Setting names. tvOS reads from across the room, so it steps up from
    /// the body size used everywhere else.
    static var titleFont: Font {
        #if os(tvOS)
        Typography.text(26, weight: .medium)
        #else
        Typography.bodyLG
        #endif
    }

    /// Explanations and notes beneath or beside a setting.
    static var detailFont: Font {
        #if os(tvOS)
        Typography.text(21)
        #else
        Typography.bodySM
        #endif
    }

    /// How far a highlighted row's fill reaches past its text into the card
    /// margin, so the text still lines up with the static rows around it.
    /// The grouped List supplies its own row insets.
    static var highlightBleed: CGFloat {
        #if os(tvOS)
        12
        #elseif os(macOS)
        8
        #else
        0
        #endif
    }
}

// MARK: - Page

#if os(macOS) || os(tvOS)
/// The scrolling column that holds the sections on the Settings tab.
struct SettingsPage<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 40) {
                content
            }
            // A settings row keeps its control within reach of its name
            // instead of stretching across a wide window.
            .frame(maxWidth: columnWidth, alignment: .leading)
            .padding(.horizontal, 48)
            .padding(.top, 24)
            .padding(.bottom, 64)
            .frame(maxWidth: .infinity)
        }
    }

    private var columnWidth: CGFloat {
        #if os(tvOS)
        1200
        #else
        820
        #endif
    }
}
#endif

// MARK: - Section

struct SettingsSection<Content: View, Footer: View>: View {
    private let title: String
    private let content: Content
    private let footer: Footer?

    init(
        _ title: String,
        @ViewBuilder content: () -> Content,
        @ViewBuilder footer: () -> Footer
    ) {
        self.title = title
        self.content = content()
        self.footer = footer()
    }

    var body: some View {
        #if os(macOS) || os(tvOS)
        VStack(alignment: .leading, spacing: 18) {
            SectionHeader(title: title)
            SettingsCard { content }
            if let footer {
                footer
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(title)
        #else
        if let footer {
            Section {
                content
            } header: {
                Text(title).labelCaps()
            } footer: {
                footer
            }
        } else {
            Section {
                content
            } header: {
                Text(title).labelCaps()
            }
        }
        #endif
    }
}

extension SettingsSection where Footer == EmptyView {
    init(_ title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
        self.footer = nil
    }
}

#if os(macOS) || os(tvOS)
/// One section's rows on a dim archive surface, divided by hairlines.
private struct SettingsCard<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Group(subviews: content) { rows in
                ForEach(rows) { row in
                    if row.id != rows.first?.id {
                        Rectangle()
                            .fill(Theme.hairline)
                            .frame(height: 1)
                            .accessibilityHidden(true)
                    }
                    row
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, rowPadding.width)
                        .padding(.vertical, rowPadding.height)
                }
            }
        }
        .background(Theme.surfaceLow, in: RoundedRectangle(cornerRadius: Theme.Radius.card))
        .overlay {
            RoundedRectangle(cornerRadius: Theme.Radius.card)
                .strokeBorder(Theme.hairline, lineWidth: 1)
        }
        #if os(tvOS)
        // Moving up or down into a card lands on its nearest row even when
        // the focused control sits off to one side.
        .focusSection()
        #endif
    }

    private var rowPadding: CGSize {
        #if os(tvOS)
        CGSize(width: 28, height: 18)
        #else
        CGSize(width: 20, height: 14)
        #endif
    }
}

/// A setting's name with its explanation beneath.
private struct SettingsLabel: View {
    let title: String?
    let detail: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let title {
                Text(title)
                    .font(SettingsMetrics.titleFont)
                    .foregroundStyle(Theme.textPrimary)
            }
            if let detail {
                Text(detail)
                    .font(SettingsMetrics.detailFont)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}
#endif

// MARK: - Rows

/// A setting with an optional current value, explanation, and trailing
/// control or action. A page draws it as one row; the grouped List shows the
/// labeled value, then the explanation, then the accessory, each on its own row.
struct SettingsRow<Accessory: View>: View {
    private let title: String?
    private let value: String?
    private let detail: String?
    private let accessory: Accessory

    init(
        _ title: String? = nil,
        value: String? = nil,
        detail: String? = nil,
        @ViewBuilder accessory: () -> Accessory
    ) {
        self.title = title
        self.value = value
        self.detail = detail
        self.accessory = accessory()
    }

    var body: some View {
        #if os(macOS) || os(tvOS)
        HStack(spacing: 24) {
            HStack(spacing: 24) {
                SettingsLabel(title: title, detail: detail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if let value {
                    Text(value)
                        .font(SettingsMetrics.titleFont)
                        .foregroundStyle(Theme.textSecondary)
                        .multilineTextAlignment(.trailing)
                }
            }
            // Name, value, and explanation are one setting; the accessory
            // stays a separate control.
            .accessibilityElement(children: .combine)
            accessory
        }
        #else
        if let title, let value {
            LabeledContent(title, value: value)
        } else if let title {
            Text(title)
        }
        if let detail {
            SettingsNote(detail)
        }
        accessory
        #endif
    }
}

extension SettingsRow where Accessory == EmptyView {
    init(_ title: String? = nil, value: String? = nil, detail: String? = nil) {
        self.init(title, value: value, detail: detail) { EmptyView() }
    }
}

/// An on/off setting. On a page the explanation sits under the name with the
/// switch at the trailing edge; the grouped List keeps the system toggle row
/// followed by the explanation.
struct SettingsToggleRow: View {
    private let title: String
    private let detail: String?
    @Binding private var isOn: Bool

    init(_ title: String, detail: String? = nil, isOn: Binding<Bool>) {
        self.title = title
        self.detail = detail
        self._isOn = isOn
    }

    var body: some View {
        #if os(macOS)
        Toggle(isOn: $isOn) {
            SettingsLabel(title: title, detail: detail)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .toggleStyle(.switch)
        .tint(Theme.gold)
        #elseif os(tvOS)
        // A row button rather than the system toggle, for the reasons in
        // ArchiveToggle, with the state sized to match the row's name.
        Button {
            isOn.toggle()
        } label: {
            HStack(spacing: 24) {
                SettingsLabel(title: title, detail: detail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(isOn ? "On" : "Off")
                    .font(Typography.text(22, weight: .bold))
                    .textCase(.uppercase)
                    .kerning(1.2)
                    .foregroundStyle(isOn ? Theme.gold : Theme.textSecondary)
                    // Spoken as the element's value below.
                    .accessibilityHidden(true)
            }
            .padding(SettingsMetrics.highlightBleed)
            .contentShape(Rectangle())
        }
        .archiveRowStyle()
        .padding(-SettingsMetrics.highlightBleed)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isToggle)
        .accessibilityValue(isOn ? Text("On") : Text("Off"))
        #else
        Toggle(title, isOn: $isOn)
        if let detail {
            SettingsNote(detail)
        }
        #endif
    }
}

/// Explanatory copy. A row of its own in both layouts.
struct SettingsNote: View {
    private let text: String
    private let color: Color

    init(_ text: String, color: Color = Theme.textSecondary) {
        self.text = text
        self.color = color
    }

    var body: some View {
        Text(text)
            .font(SettingsMetrics.detailFont)
            .foregroundStyle(color)
            #if os(macOS) || os(tvOS)
            .fixedSize(horizontal: false, vertical: true)
            #endif
    }
}

/// Related buttons and links: side by side on a page, one per row in the
/// grouped List.
struct SettingsActions<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        #if os(macOS) || os(tvOS)
        FlowLayout(spacing: 16, lineSpacing: 12) {
            content
        }
        #else
        content
        #endif
    }
}

/// Controls that read as one unit on a page, such as the equalizer's
/// sliders, but keep one row each in the grouped List.
struct SettingsRowGroup<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        #if os(macOS) || os(tvOS)
        VStack(alignment: .leading, spacing: 18) {
            content
        }
        #else
        content
        #endif
    }
}

extension View {
    /// Links on a page are ghost archive buttons laid out beside each other;
    /// the grouped List keeps its tinted link rows.
    @ViewBuilder
    func settingsLinkStyle() -> some View {
        #if os(macOS) || os(tvOS)
        archiveButtonStyle(.ghost)
        #else
        self
        #endif
    }
}
