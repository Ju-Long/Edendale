//
//  PushedPage.swift
//  Edendale
//
//  Pages pushed onto a tab's navigation stack, so Back on Apple TV always
//  goes back from one. A swipe off a pushed page's leading edge opens the
//  tab sidebar over it, and tvOS treats Back pressed in the sidebar as a
//  request to leave the app, even with pages still pushed beneath. RootView
//  takes that press while a pushed page is on screen and pops the page
//  instead. On a tab's first page the system behavior is unchanged: Back
//  opens the sidebar, and Back again goes to the Home Screen.
//

import SwiftUI

extension View {
    /// Marks a page pushed onto a navigation stack.
    func pushedPage() -> some View {
        #if os(tvOS)
        modifier(PushedPageModifier())
        #else
        self
        #endif
    }

    /// Lets Back in the tab sidebar return from a pushed page (see above).
    /// Apply to the TabView, below the `PushedPages` it reads.
    func sidebarBackReturnsFromPushedPages() -> some View {
        #if os(tvOS)
        modifier(SidebarBackModifier())
        #else
        self
        #endif
    }
}

#if os(tvOS)
/// The pushed pages on screen, newest last. Each tab keeps its stack while
/// another is selected, but only the selected tab's top page is on screen,
/// so this is the selected tab's page, if it has one.
@MainActor
@Observable
final class PushedPages {
    @ObservationIgnored private var pages: [(id: UUID, goBack: DismissAction)] = []
    private(set) var isShowingPushedPage = false

    func appeared(_ id: UUID, goBack: DismissAction) {
        pages.removeAll { $0.id == id }
        pages.append((id, goBack))
        update()
    }

    func disappeared(_ id: UUID) {
        pages.removeAll { $0.id == id }
        update()
    }

    /// Pops the pushed page on screen. The sidebar stays open: SwiftUI can't
    /// move focus out of it (`resetFocus` leaves it there), so each further
    /// Back walks back another page, and the one on the tab's first page
    /// leaves the app as usual.
    func goBack() {
        pages.last?.goBack()
    }

    private func update() {
        let showing = !pages.isEmpty
        if showing != isShowingPushedPage { isShowingPushedPage = showing }
    }
}

private struct PushedPageModifier: ViewModifier {
    /// Absent outside the tabs (a detail sheet, for one), where the sidebar
    /// can't be reached.
    @Environment(PushedPages.self) private var pushedPages: PushedPages?
    /// Inside a pushed page, dismissing pops it.
    @Environment(\.dismiss) private var dismiss
    @State private var id = UUID()

    func body(content: Content) -> some View {
        content
            .onAppear { pushedPages?.appeared(id, goBack: dismiss) }
            .onDisappear { pushedPages?.disappeared(id) }
    }
}

private struct SidebarBackModifier: ViewModifier {
    @Environment(PushedPages.self) private var pushedPages

    func body(content: Content) -> some View {
        // Back pressed on a pushed page is the navigation stack's: it pops
        // the page and never reaches here. What does reach here is Back
        // pressed in the sidebar, or on a tab's first page. A nil action
        // leaves those to the system.
        content.onExitCommand(
            perform: pushedPages.isShowingPushedPage ? { pushedPages.goBack() } : nil
        )
    }
}
#endif
