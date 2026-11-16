//
//  DownloadedSectionPicker.swift
//  Edendale
//
//  Chooses what the Downloaded tab shows: the whole library, or Continue
//  Watching, Movies, or TV Shows on its own, as the macOS sidebar's
//  Downloaded child rows do. On iPhone and iPad (iOS 26.1 and later) the
//  choices extend out of the tab bar, as its bottom accessory, while the
//  Downloaded tab's root page is showing; the sections never become
//  sidebar rows. Apple TV, Apple Vision Pro, and earlier iOS have no tab
//  bar accessory, so there the page shows the same choices as chips above
//  its content.
//

import SwiftUI

enum DownloadedSectionPicker {
    /// The choices in order: the whole page (`nil`), then each section
    /// that has something to show.
    static func choices(for sections: [DownloadedSection]) -> [DownloadedSection?] {
        [nil] + sections.map(Optional.some)
    }

    /// Offered once two sections have something to show; with one, the
    /// whole page and that section list the same titles.
    static func isOffered(for sections: [DownloadedSection]) -> Bool {
        sections.count > 1
    }

    /// Whether this device's tab bar carries the picker; otherwise the
    /// Downloaded page shows it.
    static var ridesOnTabBar: Bool {
        #if os(iOS)
        if #available(iOS 26.1, *) { return true }
        #endif
        return false
    }

    static func title(_ choice: DownloadedSection?) -> String {
        choice?.title ?? String(localized: "All")
    }

    static func shortTitle(_ choice: DownloadedSection?) -> String {
        choice?.shortTitle ?? String(localized: "All")
    }
}

// MARK: - On the tab bar (iOS)

#if os(iOS)
/// Puts the section picker on the tab bar while `isEnabled` (the Downloaded
/// tab's root page is showing and there is a choice to make). Before iOS
/// 26.1 the tab bar can't show an accessory for one tab only, and the page
/// shows the picker instead (see `DownloadedSectionPicker.ridesOnTabBar`).
struct DownloadedSectionAccessory: ViewModifier {
    let isEnabled: Bool
    @Binding var selection: DownloadedSection?
    let sections: [DownloadedSection]

    func body(content: Content) -> some View {
        if #available(iOS 26.1, *) {
            content.tabViewBottomAccessory(isEnabled: isEnabled) {
                DownloadedSectionTabBarPicker(selection: $selection, sections: sections)
            }
        } else {
            content
        }
    }
}

/// The picker as the tab bar's accessory: a row of segments above the tab
/// bar (at the foot of the screen on iPad), or a menu naming the current
/// choice while the tab bar is minimized beside it.
@available(iOS 26.0, *)
private struct DownloadedSectionTabBarPicker: View {
    @Binding var selection: DownloadedSection?
    let sections: [DownloadedSection]
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement

    private var choices: [DownloadedSection?] {
        DownloadedSectionPicker.choices(for: sections)
    }

    var body: some View {
        Group {
            if placement == .inline {
                menu
            } else {
                segments
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Downloaded sections")
    }

    private var segments: some View {
        HStack(spacing: 2) {
            ForEach(choices, id: \.self) { choice in
                segment(choice)
            }
        }
        .padding(.horizontal, 6)
    }

    private func segment(_ choice: DownloadedSection?) -> some View {
        let isSelected = choice == selection
        return Button {
            selection = choice
        } label: {
            Text(DownloadedSectionPicker.shortTitle(choice))
                .font(Typography.text(13, weight: .semibold))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .foregroundStyle(isSelected ? Theme.gold : Theme.textSecondary)
                .padding(.horizontal, 8)
                .padding(.vertical, 7)
                .frame(maxWidth: .infinity)
                .background(isSelected ? Theme.surface : .clear, in: Capsule())
                .overlay {
                    Capsule().strokeBorder(isSelected ? Theme.gold : .clear, lineWidth: 1)
                }
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        // The short label is for the row's width; say the section's name.
        .accessibilityLabel(DownloadedSectionPicker.title(choice))
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
    }

    /// Beside the minimized tab bar there is room for one label.
    private var menu: some View {
        Menu {
            Picker("Show", selection: $selection) {
                ForEach(choices, id: \.self) { choice in
                    Text(DownloadedSectionPicker.title(choice)).tag(choice)
                }
            }
        } label: {
            HStack(spacing: 6) {
                Text(DownloadedSectionPicker.title(selection))
                    .font(Typography.text(13, weight: .semibold))
                    .foregroundStyle(Theme.gold)
                    .lineLimit(1)
                Image(.chevronDown)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Theme.textSecondary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
        }
        .accessibilityLabel("Show")
        .accessibilityValue(DownloadedSectionPicker.title(selection))
    }
}
#endif

// MARK: - On the page

/// The section picker heading the Downloaded page where the tab bar can't
/// carry it: the curated-collection chips' look, one chip per choice.
struct DownloadedSectionChips: View {
    let selection: Binding<DownloadedSection?>
    let sections: [DownloadedSection]
    let edgeMargin: CGFloat

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(DownloadedSectionPicker.choices(for: sections), id: \.self) { choice in
                    FilterChip(
                        title: DownloadedSectionPicker.title(choice),
                        isSelected: selection.wrappedValue == choice
                    ) {
                        selection.wrappedValue = choice
                    }
                }
            }
            .padding(.horizontal, edgeMargin)
            // Room for the tvOS focus lift so it isn't clipped.
            #if os(tvOS)
            .padding(.vertical, 16)
            #endif
        }
        .scrollClipDisabled()
        #if os(tvOS)
        .focusSection()
        #endif
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Downloaded sections")
    }
}
