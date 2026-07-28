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
                HStack {
                    Image(.folderCirclePlus)
                        .font(.system(size: 18))
                        .foregroundStyle(Theme.accent)
                    
                    Text("Add Local Folder…")
                        .font(Typography.text(15, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                }
            }
            .archiveButtonStyle(.ghost)
            #endif

            Button {
                showLinkSource = true
            } label: {
                HStack {
                    Image(.link)
                        .font(.system(size: 18))
                        .foregroundStyle(Theme.accent)
                    
                    Text("Link Network Source…")
                        .font(Typography.text(15, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                }
            }
            .archiveButtonStyle(.ghost)
        } header: {
            Text("Sources").labelCaps()
        }
    }
}
