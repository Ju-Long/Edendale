//
//  ReleaseHeatmapView.swift
//  Edendale
//
//  GitHub-style yearly release grid for the search tab's date filter:
//  one column per week, one square per day, brightness scaling with how
//  many notable releases landed on that date. Tapping squares drives the
//  owner's two-tap range selection; `< year >` steps between years and
//  the forward chevron hides once there is no more future year.
//

import SwiftUI

/// Calendar every heatmap date passes through — the grid, the selection
/// math in `SearchModel`, and the "yyyy-MM-dd" keys all agree on it.
enum HeatmapCalendar {
    static var current: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = .autoupdatingCurrent
        calendar.timeZone = .autoupdatingCurrent
        return calendar
    }
}

struct ReleaseHeatmapView: View {
    let year: Int
    /// Releases per day keyed "yyyy-MM-dd" — drives square brightness.
    let counts: [String: Int]
    /// Committed day range (start-of-day bounds).
    let selection: ClosedRange<Date>?
    /// First square of an in-progress selection awaiting its second tap.
    let pendingAnchor: Date?
    let isLoading: Bool
    let canGoBack: Bool
    /// False once the displayed year is the newest allowed — hides the
    /// forward chevron entirely.
    let canGoForward: Bool
    /// Owner-formatted summary of the committed selection.
    let summary: String?
    let onPreviousYear: () -> Void
    let onNextYear: () -> Void
    let onSelectDay: (Date) -> Void
    let onClear: () -> Void
    let onDone: () -> Void

    private let grid: YearGrid

    /// Day currently hovered (macOS/visionOS) or focused (tvOS). Drives the
    /// white highlight border and the footer read-out of that day's releases.
    @State private var highlightedKey: String?
    #if os(tvOS)
    @FocusState private var focusedKey: String?
    #endif

    init(
        year: Int,
        counts: [String: Int],
        selection: ClosedRange<Date>?,
        pendingAnchor: Date?,
        isLoading: Bool,
        canGoBack: Bool,
        canGoForward: Bool,
        summary: String?,
        onPreviousYear: @escaping () -> Void,
        onNextYear: @escaping () -> Void,
        onSelectDay: @escaping (Date) -> Void,
        onClear: @escaping () -> Void,
        onDone: @escaping () -> Void
    ) {
        self.year = year
        self.counts = counts
        self.selection = selection
        self.pendingAnchor = pendingAnchor
        self.isLoading = isLoading
        self.canGoBack = canGoBack
        self.canGoForward = canGoForward
        self.summary = summary
        self.onPreviousYear = onPreviousYear
        self.onNextYear = onNextYear
        self.onSelectDay = onSelectDay
        self.onClear = onClear
        self.onDone = onDone
        self.grid = YearGrid(year: year)
    }

    // tvOS squares are bigger so the focus engine has real targets.
    private var cellSize: CGFloat {
        #if os(tvOS)
        26
        #else
        13
        #endif
    }

    private var cellSpacing: CGFloat {
        #if os(tvOS)
        6
        #else
        3
        #endif
    }

    private var gridWidth: CGFloat {
        CGFloat(grid.weeks.count) * (cellSize + cellSpacing) - cellSpacing
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header
            gridScroll
            footer
        }
        #if os(tvOS)
        // isFocused never lands inside a ButtonStyle on tvOS, so mirror the
        // focused cell out of @FocusState into the shared highlight state.
        .onChange(of: focusedKey) { _, newValue in
            highlightedKey = newValue
        }
        #endif
        .onChange(of: year) { _, _ in
            highlightedKey = nil
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack(spacing: 8) {
            Button(action: onPreviousYear) {
                Image("chevron-left")
            }
            .archiveButtonStyle(.ghost)
            .disabled(!canGoBack)
            .accessibilityLabel("Previous year")

            Text(verbatim: String(year))
                .font(Typography.headlineMD)
                .foregroundStyle(Theme.textPrimary)
                .accessibilityLabel("Year \(year)")
                .accessibilityAddTraits(.isHeader)

            if canGoForward {
                Button(action: onNextYear) {
                    Image("chevron-right")
                }
                .archiveButtonStyle(.ghost)
                .accessibilityLabel("Next year")
            }

            if isLoading {
                ProgressView()
                    .controlSize(.small)
                    .tint(Theme.gold)
                    .padding(.leading, 4)
                    .accessibilityLabel("Loading releases")
            }

            Spacer(minLength: 12)

            if selection != nil || pendingAnchor != nil {
                Button("Clear", action: onClear)
                    .archiveButtonStyle(.ghost)
            }

            Button("Done", action: onDone)
                .archiveButtonStyle(.primary)
        }
        #if os(tvOS)
        // The header's buttons sit only at the far edges with a Spacer
        // between, so without a focus section Up from a middle column finds
        // nothing overhead. As a section, Up jumps here from anywhere in the
        // grid and snaps to the nearest button.
        .focusSection()
        #endif
    }

    // MARK: - Grid

    private var gridScroll: some View {
        ScrollView(.horizontal) {
            VStack(alignment: .leading, spacing: 6) {
                monthLabels
                LazyHStack(alignment: .top, spacing: cellSpacing) {
                    ForEach(grid.weeks) { week in
                        VStack(spacing: cellSpacing) {
                            ForEach(Array(week.days.enumerated()), id: \.offset) { _, day in
                                if let day {
                                    dayCell(day)
                                } else {
                                    Color.clear
                                        .frame(width: cellSize, height: cellSize)
                                }
                            }
                        }
                    }
                }
            }
            // Keeps the scroll indicator off the bottom row of squares.
            .padding(.bottom, 10)
        }
        .scrollIndicators(.visible)
        .scrollIndicatorsFlash(onAppear: true)
        .opacity(isLoading ? 0.45 : 1)
        .animation(.easeOut(duration: 0.2), value: isLoading)
        // One named container around 365 day squares, so the rotor can step
        // past the whole grid in a single move.
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Release calendar, \(year)")
        #if os(tvOS)
        // Pair to the header section so vertical moves cross cleanly between
        // the two instead of getting trapped in the horizontal scroll.
        .focusSection()
        #endif
    }

    /// Month abbreviations pinned above the week column where each month
    /// first appears, GitHub-style.
    private var monthLabels: some View {
        ZStack(alignment: .topLeading) {
            ForEach(grid.weeks) { week in
                if let label = week.monthLabel {
                    Text(label)
                        .font(Typography.text(10, weight: .semibold))
                        .foregroundStyle(Theme.textSecondary)
                        .fixedSize()
                        .offset(x: CGFloat(week.id) * (cellSize + cellSpacing))
                } else {
                    EmptyView()
                }
            }
        }
        .frame(width: gridWidth, height: 12, alignment: .topLeading)
        // Column guides for the eye; every square already announces its
        // full date.
        .accessibilityHidden(true)
    }

    private func dayCell(_ day: YearGrid.Day) -> some View {
        let count = counts[day.key] ?? 0
        let selected = isSelected(day.date)
        let highlighted = highlightedKey == day.key
        return Button {
            onSelectDay(day.date)
        } label: {
            RoundedRectangle(cornerRadius: 3)
                .fill(selected && count == 0 ? Theme.surfaceHigh : fill(for: count))
                .frame(width: cellSize, height: cellSize)
                .overlay {
                    if selected {
                        RoundedRectangle(cornerRadius: 3)
                            .strokeBorder(Theme.textPrimary, lineWidth: 1.5)
                    }
                }
                .overlay {
                    // Hover (macOS/visionOS) and focus (tvOS) affordance —
                    // replaces tvOS's default focus highlight, suppressed by
                    // the no-op button style below.
                    if highlighted {
                        RoundedRectangle(cornerRadius: 3)
                            .strokeBorder(.white, lineWidth: highlightLineWidth)
                    }
                }
        }
        .buttonStyle(HeatmapCellButtonStyle())
        #if os(tvOS)
        .focused($focusedKey, equals: day.key)
        #else
        .onHover { hovering in
            if hovering { highlightedKey = day.key }
            else if highlightedKey == day.key { highlightedKey = nil }
        }
        #endif
        .accessibilityLabel(accessibilityText(for: day, count: count))
        // Membership in the range is drawn as a border, and the two-tap
        // ritual is not discoverable from the square alone.
        .accessibilityAddTraits(selected ? [.isButton, .isSelected] : .isButton)
        .accessibilityHint(
            pendingAnchor == nil
                ? Text("Starts a date range.")
                : Text("Finishes the date range.")
        )
    }

    // tvOS squares are larger and read from across the room, so the focus
    // border needs more weight than a pointer hover.
    private var highlightLineWidth: CGFloat {
        #if os(tvOS)
        3
        #else
        2
        #endif
    }

    private func isSelected(_ date: Date) -> Bool {
        if let pendingAnchor, pendingAnchor == date { return true }
        guard let selection else { return false }
        return selection.contains(date)
    }

    /// Density ramp: nothing → dim single release → very bright busy dates.
    private func fill(for count: Int) -> Color {
        switch count {
        case 0: Theme.surface
        case 1: Theme.heatLow
        case 2: Theme.heatMid
        case 3...4: Theme.goldDeep
        default: Theme.gold
        }
    }

    private func accessibilityText(for day: YearGrid.Day, count: Int) -> String {
        let date = day.date.formatted(.dateTime.day().month(.wide).year())
        switch count {
        case 0: return String(localized: "\(date), no releases")
        case 1: return String(localized: "\(date), 1 release")
        default: return String(localized: "\(date), \(count) releases")
        }
    }

    // MARK: - Footer

    private var footer: some View {
        HStack(alignment: .firstTextBaseline, spacing: 16) {
            Text(statusText)
                .font(Typography.bodySM)
                .foregroundStyle(statusColor)
                .lineLimit(2)
                // Rewritten by hover, focus, and every selection step.
                .accessibilityAddTraits(.updatesFrequently)

            Spacer(minLength: 12)

            legend
        }
    }

    private var highlightedDay: YearGrid.Day? {
        guard let highlightedKey else { return nil }
        return grid.byKey[highlightedKey]
    }

    private var statusColor: Color {
        if highlightedDay != nil { return Theme.textPrimary }
        if pendingAnchor == nil, summary != nil { return Theme.gold }
        return Theme.textSecondary
    }

    private var statusText: String {
        // The hovered/focused day takes over the read-out so it always says
        // what's under the cursor; guidance and the summary fall through.
        if let highlightedDay {
            let count = counts[highlightedDay.key] ?? 0
            let date = highlightedDay.date.formatted(.dateTime.day().month(.abbreviated).year())
            let releases: String
            switch count {
            case 0: releases = String(localized: "no releases")
            case 1: releases = String(localized: "1 release")
            default: releases = String(localized: "\(count) releases")
            }
            if pendingAnchor != nil {
                return String(localized: "\(date) · \(releases) — tap to finish the range")
            }
            return String(localized: "\(date) · \(releases)")
        }
        if let pendingAnchor {
            let start = pendingAnchor.formatted(.dateTime.day().month(.abbreviated).year())
            return String(localized: "\(start) — tap the same square for a single day, or another square to finish the range.")
        }
        if let summary { return summary }
        return String(localized: "Tap a square to start a selection. Brighter squares hold more releases.")
    }

    private var legend: some View {
        HStack(spacing: 4) {
            Text("Less")
                .font(Typography.text(11))
                .foregroundStyle(Theme.textSecondary)
            ForEach([0, 1, 2, 3, 5], id: \.self) { sample in
                RoundedRectangle(cornerRadius: 2)
                    .fill(fill(for: sample))
                    .frame(width: 10, height: 10)
            }
            Text("More")
                .font(Typography.text(11))
                .foregroundStyle(Theme.textSecondary)
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Year grid layout

/// The year unrolled GitHub-style: week columns of seven day slots, padded
/// with empty slots before January 1 and after December 31.
private struct YearGrid {
    struct Day {
        /// Start of the day in `HeatmapCalendar`.
        let date: Date
        /// "yyyy-MM-dd" — matches TMDB release dates and the counts keys.
        let key: String
    }

    struct Week: Identifiable {
        let id: Int
        /// Exactly seven slots; nil outside the year.
        let days: [Day?]
        /// Month abbreviation when this column is the month's first.
        let monthLabel: String?
    }

    let weeks: [Week]
    /// Every real day keyed "yyyy-MM-dd", for the footer read-out.
    let byKey: [String: Day]

    init(year: Int) {
        let calendar = HeatmapCalendar.current
        guard let jan1 = calendar.date(from: DateComponents(year: year, month: 1, day: 1)) else {
            weeks = []
            byKey = [:]
            return
        }

        let leading = (calendar.component(.weekday, from: jan1) - calendar.firstWeekday + 7) % 7
        var slots: [Day?] = Array(repeating: nil, count: leading)
        var byKey: [String: Day] = [:]

        for month in 1...12 {
            guard
                let monthStart = calendar.date(from: DateComponents(year: year, month: month, day: 1)),
                let dayRange = calendar.range(of: .day, in: .month, for: monthStart)
            else { continue }
            for dayNumber in dayRange {
                guard let date = calendar.date(byAdding: .day, value: dayNumber - 1, to: monthStart) else { continue }
                let day = Day(
                    date: date,
                    key: String(format: "%04d-%02d-%02d", year, month, dayNumber)
                )
                slots.append(day)
                byKey[day.key] = day
            }
        }
        self.byKey = byKey

        while slots.count % 7 != 0 { slots.append(nil) }

        var weeks: [Week] = []
        var lastLabeledMonth = 0
        for weekIndex in 0..<(slots.count / 7) {
            let days = Array(slots[(weekIndex * 7)..<((weekIndex + 1) * 7)])
            var label: String?
            if let firstDay = days.compactMap({ $0 }).first {
                let month = calendar.component(.month, from: firstDay.date)
                if month != lastLabeledMonth {
                    label = calendar.shortMonthSymbols[month - 1]
                    lastLabeledMonth = month
                }
            }
            weeks.append(Week(id: weekIndex, days: days, monthLabel: label))
        }
        self.weeks = weeks
    }
}

// MARK: - Cell button style

/// No-op style so tvOS renders none of its default focus highlight — the
/// cell's own white border overlay is the only focus/hover affordance. A
/// slight fade on press stands in for the missing default tap feedback.
private struct HeatmapCellButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}
