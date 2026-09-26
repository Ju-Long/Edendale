//
//  RootSidebar.swift
//  Edendale
//
//  The macOS library window's sidebar. Each root tab is a row; the
//  Watchlist and Downloaded rows also list their page's sections as child
//  rows, so a section opens directly instead of scrolling the whole page.
//  Search sits above Settings.
//

#if os(macOS)
import SwiftUI

/// A macOS sidebar row: a root tab, or one section of the Watchlist or
/// Downloaded page. A `nil` section opens the whole page.
enum SidebarItem: Hashable {
    case movies
    case watchlist(WatchlistSection?)
    case downloaded(DownloadedSection?)
    case search
    case settings

    init(_ tab: RootTab) {
        switch tab {
        case .movies: self = .movies
        case .watchlist: self = .watchlist(nil)
        case .downloaded: self = .downloaded(nil)
        case .search: self = .search
        case .settings: self = .settings
        }
    }

    var tab: RootTab {
        switch self {
        case .movies: .movies
        case .watchlist: .watchlist
        case .downloaded: .downloaded
        case .search: .search
        case .settings: .settings
        }
    }

    /// A section row whose section has emptied falls back to its whole page.
    func resolved(
        watchlistSections: [WatchlistSection],
        downloadedSections: [DownloadedSection]
    ) -> SidebarItem {
        switch self {
        case .watchlist(let section?) where !watchlistSections.contains(section):
            .watchlist(nil)
        case .downloaded(let section?) where !downloadedSections.contains(section):
            .downloaded(nil)
        default:
            self
        }
    }
}

struct RootSidebar: View {
    @Binding var selection: SidebarItem
    let showsWatchlist: Bool
    /// Child rows, listed only while their section has something to show.
    let watchlistSections: [WatchlistSection]
    let downloadedSections: [DownloadedSection]

    @SceneStorage("sidebar.watchlistExpanded") private var watchlistExpanded = true
    @SceneStorage("sidebar.downloadedExpanded") private var downloadedExpanded = true

    var body: some View {
        List(selection: listSelection) {
            row(String(localized: "Movies & Shows"), image: "clapperboard", item: .movies)

            if showsWatchlist {
                parentRow(
                    String(localized: "Watchlist"),
                    image: "film-stack",
                    item: .watchlist(nil),
                    isExpanded: $watchlistExpanded,
                    children: watchlistSections.map {
                        ($0.title, $0.icon, SidebarItem.watchlist($0))
                    }
                )
            }

            parentRow(
                String(localized: "Downloaded"),
                image: "folder-closed",
                item: .downloaded(nil),
                isExpanded: $downloadedExpanded,
                children: downloadedSections.map {
                    ($0.title, $0.icon, SidebarItem.downloaded($0))
                }
            )

            row(String(localized: "Search"), image: "magnifying-glass-play", item: .search)
            row(String(localized: "Settings"), image: "gear-complex", item: .settings)
        }
        .navigationSplitViewColumnWidth(min: 200, ideal: 220, max: 300)
    }

    /// Clicking empty sidebar space would clear a plain binding and leave
    /// the detail column blank; keep the current page instead.
    private var listSelection: Binding<SidebarItem?> {
        Binding(
            get: { selection },
            set: { if let item = $0 { selection = item } }
        )
    }

    private func row(_ title: String, image: String, item: SidebarItem) -> some View {
        Label(title, image: image)
            .tag(item)
    }

    /// A page row that is itself selectable and discloses its sections.
    @ViewBuilder
    private func parentRow(
        _ title: String,
        image: String,
        item: SidebarItem,
        isExpanded: Binding<Bool>,
        children: [(title: String, image: String, item: SidebarItem)]
    ) -> some View {
        if children.isEmpty {
            row(title, image: image, item: item)
        } else {
            DisclosureGroup(isExpanded: isExpanded) {
                ForEach(children, id: \.item) { child in
                    row(child.title, image: child.image, item: child.item)
                }
            } label: {
                row(title, image: image, item: item)
            }
        }
    }
}
#endif
