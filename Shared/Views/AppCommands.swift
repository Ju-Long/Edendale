//
//  AppCommands.swift
//  Edendale
//
//  macOS menu bar commands. The library window publishes what they act on
//  as focused scene values, so each command is enabled only while the key
//  window can perform it: the File menu's library items on a Downloaded
//  page, and the sidebar toggle in the library window (not the player).
//

#if os(macOS)
import SwiftUI

/// What the visible Downloaded page lets the File menu do.
struct LibraryCommands {
    let addFolder: () -> Void
    let linkSource: () -> Void
    /// `nil` when there is nothing to rescan or a rescan is running.
    let rescan: (() -> Void)?
}

extension FocusedValues {
    /// The key library window's sidebar visibility.
    @Entry var sidebarVisibility: Binding<NavigationSplitViewVisibility>?
    /// The library actions of the Downloaded page on screen.
    @Entry var libraryCommands: LibraryCommands?
}

struct EdendaleCommands: Commands {
    @FocusedValue(\.sidebarVisibility) private var sidebarVisibility
    @FocusedValue(\.libraryCommands) private var library

    var body: some Commands {
        // Replaces File ▸ New Window, whose ⌘N adds media instead: the
        // library is a single window, reopened from the Dock.
        CommandGroup(replacing: .newItem) {
            Button("Add Media Folder…") { library?.addFolder() }
                .keyboardShortcut("n")
                .disabled(library == nil)

            Button("Link Network Source…") { library?.linkSource() }
                .keyboardShortcut("n", modifiers: [.command, .option])
                .disabled(library == nil)

            Divider()

            Button("Rescan Library") { library?.rescan?() }
                .keyboardShortcut("r")
                .disabled(library?.rescan == nil)
        }

        CommandGroup(before: .sidebar) {
            Button(
                isSidebarHidden
                    ? String(localized: "Show Sidebar")
                    : String(localized: "Hide Sidebar")
            ) {
                sidebarVisibility?.wrappedValue = isSidebarHidden ? .all : .detailOnly
            }
            .keyboardShortcut("b")
            .disabled(sidebarVisibility == nil)
        }
    }

    private var isSidebarHidden: Bool {
        sidebarVisibility?.wrappedValue == .detailOnly
    }
}
#endif
