//
//  SourcesSection.swift
//  Edendale
//
//  The "Sources" section of SettingsView: every linked source with its
//  kind, item count and credential-free location, plus the two ways in.
//  @Query-driven, so linking or removing a source updates the list live.
//  Local folders are hidden on tvOS, which has no browsable file system.
//

import SwiftUI
import SwiftData

struct SourcesSection: View {
    @Environment(LibraryController.self) private var library
    @Query(sort: \VideoFolder.dateAdded) private var folders: [VideoFolder]

    /// Owned by SettingsView: the pickers must hang off its NavigationStack,
    /// not off a Section inside the List.
    @Binding var showImporter: Bool
    @Binding var showLinkSource: Bool

    var body: some View {
        Section {
            if folders.isEmpty {
                Text("No sources linked")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            } else {
                ForEach(folders) { folder in
                    SourceRow(
                        folder: folder,
                        showsActions: true,
                        onRescan: { Task { await library.rescanFolder(folder) } },
                        onRemove: { library.removeFolder(folder) }
                    )
                }
                
            }

            // tvOS has no user-browsable local folders; network shares are
            // the only way in there.
            #if !os(tvOS)
            Button {
                showImporter = true
            } label: {
                // A bare Label, never a hand-built HStack with its own
                // foreground: the focus flood recolors the whole label to
                // OnGold, and a label-level color would defeat it (the gold
                // caps would vanish into the gold fill). See ArchiveButtonStyle.
                Label("Add Local Folder…", image: .folderCirclePlus)
            }
            .archiveButtonStyle(.ghost)
            #endif

            Button {
                showLinkSource = true
            } label: {
                Label("Link Network Source…", image: .link)
            }
            .archiveButtonStyle(.ghost)
        } header: {
            Text("Sources").labelCaps()
        }
    }
}
