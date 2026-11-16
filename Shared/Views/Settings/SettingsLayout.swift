//
//  SettingsLayout.swift
//  Edendale
//
//  The blocks every Settings section is built from. macOS hosts Settings as
//  a window of full pages: each section is a Bebas SectionHeader over a dim
//  archive card whose rows are divided by hairlines. iOS (a sheet), tvOS
//  and visionOS (their own tabs) keep the grouped system List, where each
//  block breaks back down into the plain rows that List has always shown.
//  On Apple TV the rows also draw archive buttons as system list buttons.
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

#if os(macOS)
/// The scrolling column that holds the sections in the Settings window.
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

    private let columnWidth: CGFloat = 820
}
#endif

// MARK: - Section

/// A titled group of settings. A nil title draws the rows alone, for a
/// page whose navigation title already names them.
struct SettingsSection<Content: View, Footer: View>: View {
    private let title: String?
    private let content: Content
    private let footer: Footer?

    init(
        _ title: String?,
        @ViewBuilder content: () -> Content,
        @ViewBuilder footer: () -> Footer
    ) {
        self.title = title
        self.content = content()
        self.footer = footer()
    }

    var body: some View {
        #if os(macOS)
        let stack = VStack(alignment: .leading, spacing: 18) {
            if let title {
                SectionHeader(title: title)
            }
            SettingsCard { content }
            if let footer {
                footer
            }
        }
        .accessibilityElement(children: .contain)
        if let title {
            stack.accessibilityLabel(title)
        } else {
            stack
        }
        #else
        if let title, let footer {
            Section {
                rows
            } header: {
                sectionHeader(title)
            } footer: {
                footer
            }
        } else if let title {
            Section {
                rows
            } header: {
                sectionHeader(title)
            }
        } else if let footer {
            Section {
                rows
            } footer: {
                footer
            }
        } else {
            Section {
                rows
            }
        }
        #endif
    }

    #if !os(macOS)
    private var rows: some View {
        content
            #if os(tvOS)
            .environment(\.archiveButtonsUseSystemStyle, true)
            #endif
    }

    @ViewBuilder
    private func sectionHeader(_ title: String) -> some View {
        #if os(tvOS)
        // The system header, legible across the room.
        Text(title)
        #else
        Text(title).labelCaps()
        #endif
    }
    #endif
}

extension SettingsSection where Footer == EmptyView {
    init(_ title: String?, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
        self.footer = nil
    }
}

#if os(macOS)
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
    }

    private let rowPadding = CGSize(width: 20, height: 14)
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
        #if os(macOS)
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
            informationRow { LabeledContent(title, value: value) }
        } else if let title {
            informationRow { Text(title) }
        }
        if let detail {
            SettingsNote(detail)
        }
        accessory
        #endif
    }

    #if !os(macOS)
    /// Apple TV focuses information rows too, as its own Settings app does,
    /// so the list scrolls to each one; a list can't otherwise scroll past
    /// its last focusable row.
    @ViewBuilder
    private func informationRow(@ViewBuilder _ row: () -> some View) -> some View {
        #if os(tvOS)
        Button {} label: { row() }
            .accessibilityRemoveTraits(.isButton)
        #else
        row()
        #endif
    }
    #endif
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
        #else
        Toggle(title, isOn: $isOn)
        if let detail {
            SettingsNote(detail)
        }
        #endif
    }
}

#if os(tvOS)
/// A setting's name with its explanation beneath, as the label of a picker
/// or button row in the grouped List. The explanation is secondary, so it
/// stays legible on the focused row's light platter.
struct SettingsRowLabel: View {
    let title: String
    let detail: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
            if let detail {
                Text(detail)
                    .font(SettingsMetrics.detailFont)
                    .foregroundStyle(.secondary)
            }
        }
    }
}
#endif

#if os(tvOS)
/// A choice from a list as a full-width row, the way Apple TV's own Settings
/// offers one: the setting's name, with its explanation beneath, at the
/// leading edge and the current choice at the trailing edge. Selecting it
/// opens a page of the choices that closes once one is picked. (A menu
/// Picker shows only its value, in a capsule without the name; a menu with
/// a row label draws a tinted capsule unlike the list's other rows.)
struct SettingsChoiceRow<Value: Hashable>: View {
    private let title: String
    private let detail: String?
    @Binding private var selection: Value
    private let options: [Value]
    private let name: (Value) -> String

    init(
        _ title: String,
        detail: String? = nil,
        selection: Binding<Value>,
        options: [Value],
        name: @escaping (Value) -> String
    ) {
        self.title = title
        self.detail = detail
        self._selection = selection
        self.options = options
        self.name = name
    }

    var body: some View {
        NavigationLink {
            SettingsChoicePage(title: title, selection: $selection, options: options, name: name)
        } label: {
            HStack(spacing: 24) {
                SettingsRowLabel(title: title, detail: detail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Text(name(selection))
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityValue(name(selection))
    }
}

/// The choices of a SettingsChoiceRow, with focus starting on the current one.
private struct SettingsChoicePage<Value: Hashable>: View {
    let title: String
    @Binding var selection: Value
    let options: [Value]
    let name: (Value) -> String

    @Environment(\.dismiss) private var dismiss
    @FocusState private var focusedOption: Value?

    var body: some View {
        List {
            SettingsPageTitle(title)
            Section {
                ForEach(options, id: \.self) { option in
                    Button {
                        selection = option
                        dismiss()
                    } label: {
                        HStack(spacing: 24) {
                            Text(name(option))
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if option == selection {
                                Image(systemName: "checkmark")
                                    .accessibilityHidden(true)
                            }
                        }
                    }
                    .focused($focusedOption, equals: option)
                    .accessibilityAddTraits(option == selection ? .isSelected : [])
                }
            }
        }
        .defaultFocus($focusedOption, selection)
        .background(Theme.background)
        .settingsPageChrome(title)
    }
}

/// A Settings page's title as the list's first row. The navigation title
/// would stay pinned at the top of the screen and draw over every row
/// scrolled beneath it, so Apple TV Settings pages hide their navigation bar
/// (see settingsPageChrome) and let this scroll away with the rows.
struct SettingsPageTitle: View {
    private let title: String

    init(_ title: String) {
        self.title = title
    }

    var body: some View {
        Text(title)
            .font(.title2.weight(.semibold))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity)
            .padding(.bottom, 12)
            .accessibilityAddTraits(.isHeader)
    }
}
#endif

extension View {
    /// A Settings page's title, and on Apple TV a page pushed onto the stack
    /// whose title scrolls with its rows (see SettingsPageTitle).
    func settingsPageChrome(_ title: String) -> some View {
        #if os(tvOS)
        navigationTitle(title)
            .toolbar(.hidden, for: .navigationBar)
            .pushedPage()
        #else
        navigationTitle(title)
            .pushedPage()
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
            #if os(macOS)
            .fixedSize(horizontal: false, vertical: true)
            #endif
    }
}

/// Related buttons and links: side by side on a page, one per row in the
/// grouped List.
struct SettingsActions<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        #if os(macOS)
        FlowLayout(spacing: 16, lineSpacing: 12) {
            content
        }
        #else
        // The List lays out a Label as a list-row label, and inside the
        // archive button chrome only its icon survives, so keep both parts.
        content
            .labelStyle(.titleAndIcon)
        #endif
    }
}

/// Controls that read as one unit on a page, such as the equalizer's
/// sliders, but keep one row each in the grouped List.
struct SettingsRowGroup<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        #if os(macOS)
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
        #if os(macOS)
        archiveButtonStyle(.ghost)
        #else
        self
        #endif
    }
}
