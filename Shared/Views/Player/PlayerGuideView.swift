//
//  PlayerGuideView.swift
//  Edendale
//
//  The player guide: a short run of pages, each pairing a looping
//  illustration of one control with what it does. It covers the video on
//  the viewer's first playback on the device (PlayerScreen), and opens on
//  request from the Adjustments panel or Settings ▸ App Controls. Swipe,
//  the arrow keys, or the Back and Next buttons move between pages.
//

import SwiftUI
#if os(iOS) || os(visionOS)
import GameController
#endif

struct PlayerGuideView: View {
    let controls: PlayerControlPreferences
    /// The last page's button: "Start Watching" while a first playback
    /// waits for the guide, "Done" elsewhere.
    let finishTitle: String
    let onFinish: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var index = 0
    /// Which way the last page turn went, so pages slide the right way.
    @State private var forward = true
    @State private var hasKeyboard = Self.keyboardConnected
    @AccessibilityFocusState private var titleFocused: Bool
    @FocusState private var focus: Focus?

    private enum Focus: Hashable {
        /// The whole guide, which takes the arrow keys.
        case guide
        case next
    }

    private var platform: PlayerGuidePlatform { .current }

    private var pages: [PlayerGuidePage] {
        PlayerGuide.pages(for: platform, controls: controls, hasKeyboard: hasKeyboard)
    }

    var body: some View {
        let pages = self.pages
        // The keyboard page can come and go while the guide is open.
        let current = min(index, pages.count - 1)
        let page = pages[current]

        GeometryReader { geo in
            let wide = geo.size.width >= geo.size.height * 1.2
            VStack(spacing: GuideLayout.sectionSpacing) {
                header
                ZStack {
                    pageBody(page, wide: wide)
                        .id(page.id)
                        .transition(pageTransition)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .clipped()
                #if os(iOS) || os(visionOS)
                .contentShape(Rectangle())
                .gesture(swipeToTurn(current: current, count: pages.count))
                #endif
                footer(current: current, count: pages.count)
            }
            .padding(.horizontal, GuideLayout.horizontalPadding)
            .padding(.vertical, GuideLayout.verticalPadding)
            .frame(maxWidth: GuideLayout.maxWidth)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Theme.background.ignoresSafeArea())
        // A modal layer: what it covers leaves the reading order.
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isModal)
        #if os(tvOS)
        .onAppear {
            Task { @MainActor in focus = .next }
        }
        #else
        .focusable()
        .focused($focus, equals: .guide)
        .focusEffectDisabled()
        .onKeyPress(keys: [.leftArrow, .rightArrow, .return, .escape], phases: .down) { press in
            switch press.key {
            case .leftArrow:
                if current > 0 { turn(to: current - 1) }
            case .rightArrow:
                if current < pages.count - 1 { turn(to: current + 1) }
            case .return:
                advance(from: current, count: pages.count)
            default:
                onFinish()
            }
            return .handled
        }
        .onAppear { focus = .guide }
        #endif
        #if os(iOS) || os(visionOS)
        .onReceive(NotificationCenter.default.publisher(for: .GCKeyboardDidConnect)) { _ in
            hasKeyboard = Self.keyboardConnected
        }
        .onReceive(NotificationCenter.default.publisher(for: .GCKeyboardDidDisconnect)) { _ in
            hasKeyboard = Self.keyboardConnected
        }
        #endif
    }

    private static var keyboardConnected: Bool {
        #if os(iOS) || os(visionOS)
        GCKeyboard.coalesced != nil
        #else
        false
        #endif
    }

    // MARK: - Header and footer

    private var header: some View {
        HStack {
            Text("Player Guide")
                .font(GuideLayout.labelFont)
                .textCase(.uppercase)
                .kerning(1.2)
                .foregroundStyle(Theme.gold)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            PlayerIconChip(
                icon: .xmark,
                label: String(localized: "Close Guide"),
                diameter: GuideLayout.closeChip,
                glyphSize: GuideLayout.closeGlyph
            ) {
                onFinish()
            }
        }
    }

    private func footer(current: Int, count: Int) -> some View {
        let isLast = current == count - 1
        return HStack {
            Button("Back") { turn(to: current - 1) }
                .archiveButtonStyle(.ghost)
                // Held in place on the first page so the row doesn't shift.
                .opacity(current == 0 ? 0 : 1)
                .disabled(current == 0)
                .accessibilityHidden(current == 0)
            Spacer(minLength: 12)
            // Between the buttons rather than over the row's center, so a
            // long translation of either can never run into them.
            pageDots(current: current, count: count)
            Spacer(minLength: 12)
            Button(isLast ? finishTitle : String(localized: "Next")) {
                advance(from: current, count: count)
            }
            .archiveButtonStyle(.primary)
            .focused($focus, equals: .next)
        }
    }

    private func pageDots(current: Int, count: Int) -> some View {
        HStack(spacing: GuideLayout.dot * 0.75) {
            ForEach(0..<count, id: \.self) { page in
                #if os(tvOS)
                dot(isSelected: page == current)
                #else
                Button {
                    turn(to: page)
                } label: {
                    dot(isSelected: page == current)
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                #endif
            }
        }
        // A row of dots is one page control.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Guide Page")
        .accessibilityValue(String(localized: "\(current + 1) of \(count)"))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: if current < count - 1 { turn(to: current + 1) }
            case .decrement: if current > 0 { turn(to: current - 1) }
            @unknown default: break
            }
        }
    }

    private func dot(isSelected: Bool) -> some View {
        Capsule()
            .fill(isSelected ? Theme.gold : Theme.outline)
            .frame(width: GuideLayout.dot * (isSelected ? 2 : 1), height: GuideLayout.dot)
    }

    // MARK: - Page

    @ViewBuilder
    private func pageBody(_ page: PlayerGuidePage, wide: Bool) -> some View {
        let illustration = PlayerGuideIllustration(topic: page.topic, platform: platform, controls: controls)
        if wide {
            HStack(alignment: .center, spacing: GuideLayout.columnSpacing) {
                illustration
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                copy(page)
                    .frame(maxWidth: GuideLayout.copyWidth, maxHeight: .infinity)
            }
        } else {
            VStack(alignment: .leading, spacing: GuideLayout.columnSpacing) {
                illustration
                    .frame(maxWidth: .infinity)
                    .layoutPriority(1)
                copy(page)
            }
            .frame(maxHeight: .infinity)
        }
    }

    /// The page's words, scrolling only when large text or a long
    /// translation outgrows the space.
    private func copy(_ page: PlayerGuidePage) -> some View {
        ViewThatFits(in: .vertical) {
            copyText(page)
            ScrollView {
                copyText(page)
            }
            // Show that more is below rather than leave it cut off.
            .scrollIndicators(.visible)
            .scrollIndicatorsFlash(onAppear: true)
        }
    }

    private func copyText(_ page: PlayerGuidePage) -> some View {
        VStack(alignment: .leading, spacing: GuideLayout.copySpacing) {
            Text(page.title)
                .font(GuideMetrics.titleFont)
                .textCase(.uppercase)
                .foregroundStyle(Theme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
                .accessibilityFocused($titleFocused)
            Text(page.message)
                .font(GuideMetrics.bodyFont)
                .foregroundStyle(Theme.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            if let footnote = page.footnote {
                Text(footnote)
                    .font(GuideMetrics.noteFont)
                    .foregroundStyle(Theme.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Turning pages

    private var pageTransition: AnyTransition {
        guard !reduceMotion else { return .opacity }
        return .asymmetric(
            insertion: .move(edge: forward ? .trailing : .leading).combined(with: .opacity),
            removal: .move(edge: forward ? .leading : .trailing).combined(with: .opacity)
        )
    }

    private func advance(from current: Int, count: Int) {
        if current < count - 1 {
            turn(to: current + 1)
        } else {
            onFinish()
        }
    }

    private func turn(to newIndex: Int) {
        guard newIndex != index, newIndex >= 0 else { return }
        forward = newIndex > index
        // The outgoing page takes its slide direction from the render
        // before it leaves, so the direction lands one update earlier.
        Task { @MainActor in
            withAnimation(reduceMotion ? .easeInOut(duration: 0.2) : .smooth(duration: 0.35)) {
                index = newIndex
            }
            titleFocused = true
        }
    }

    #if os(iOS) || os(visionOS)
    private func swipeToTurn(current: Int, count: Int) -> some Gesture {
        DragGesture(minimumDistance: 24)
            .onEnded { value in
                let dx = value.translation.width
                guard abs(dx) > 60, abs(dx) > abs(value.translation.height) else { return }
                if dx < 0, current < count - 1 {
                    turn(to: current + 1)
                } else if dx > 0, current > 0 {
                    turn(to: current - 1)
                }
            }
    }
    #endif
}

// MARK: - Layout

private enum GuideLayout {
    #if os(tvOS)
    static let horizontalPadding: CGFloat = 80
    static let verticalPadding: CGFloat = 60
    static let maxWidth: CGFloat = 1600
    static let sectionSpacing: CGFloat = 40
    static let columnSpacing: CGFloat = 64
    static let copyWidth: CGFloat = 620
    static let copySpacing: CGFloat = 20
    static let closeChip: CGFloat = 72
    static let closeGlyph: CGFloat = 28
    static let labelFont = Typography.text(24, weight: .bold)
    static let dot: CGFloat = 14
    #else
    static let horizontalPadding: CGFloat = 24
    static let verticalPadding: CGFloat = 16
    static let maxWidth: CGFloat = 1100
    static let sectionSpacing: CGFloat = 16
    static let columnSpacing: CGFloat = 28
    static let copyWidth: CGFloat = 380
    static let copySpacing: CGFloat = 10
    static let closeChip: CGFloat = 40
    static let closeGlyph: CGFloat = 18
    static let labelFont = Typography.labelCaps
    static let dot: CGFloat = 8
    #endif
}
