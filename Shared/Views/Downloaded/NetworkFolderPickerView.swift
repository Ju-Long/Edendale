//
//  NetworkFolderPickerView.swift
//  Edendale
//
//  One level of a network share: subfolders navigate deeper (the view
//  recurses via BrowseLocation pushes), video files preview what an index
//  would pick up, and "Index This Folder" hands the current directory back
//  to the add-source flow. At the connector root the entries are the
//  server's shares.
//

import SwiftUI

/// A spot in a connector's tree — the navigation value the picker pushes
/// for each subfolder.
struct BrowseLocation: Hashable {
    let connector: SMBConnector
    let url: URL
    let name: String
}

struct NetworkFolderPickerView: View {
    let location: BrowseLocation
    /// Called with the picked folder, its connector, and a display name.
    let onIndex: (URL, SMBConnector, String) -> Void

    @State private var entries: [ConnectorEntry]?
    @State private var errorMessage: String?

    private var folders: [ConnectorEntry] { (entries ?? []).filter(\.isDirectory) }
    private var videos: [ConnectorEntry] {
        (entries ?? []).filter {
            !$0.isDirectory && LibraryController.supportedExtensions.contains($0.url.pathExtension.lowercased())
        }
    }

    var body: some View {
        List {
            Section {
                Button {
                    onIndex(location.url, location.connector, location.name)
                } label: {
                    Label("Select \(location.name)", image: .folderOpen)
                }
                .disabled(entries == nil && errorMessage == nil)
            } footer: {
                Text("Adds every video in this folder and its subfolders to your library.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }

            if let errorMessage {
                Section {
                    Text(errorMessage)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                    Button("Try Again") { reload() }
                }
            } else if let entries {
                if entries.isEmpty {
                    Section {
                        Text("Nothing to show in this folder.")
                            .font(Typography.bodySM)
                            .foregroundStyle(Theme.textSecondary)
                    }
                }

                if !folders.isEmpty {
                    Section {
                        ForEach(folders) { folder in
                            NavigationLink(value: BrowseLocation(
                                connector: location.connector,
                                url: folder.url,
                                name: folder.name
                            )) {
                                Text(folder.name)
                                    .foregroundStyle(Theme.textPrimary)
                            }
                        }
                    } header: {
                        Label("Folders", image: .folderTree).labelCaps()
                    }
                }

                if !videos.isEmpty {
                    Section {
                        ForEach(videos) { video in
                            Label(video.name, image: .film)
                                .foregroundStyle(Theme.textSecondary)
                        }
                    } header: {
                        Label("Video", image: .fileVideo).labelCaps()
                    }
                }
            } else {
                Section {
                    HStack(spacing: 12) {
                        ProgressView().tint(Theme.gold)
                        Text("Reading folder…")
                            .font(Typography.bodySM)
                            .foregroundStyle(Theme.textSecondary)
                    }
                    .accessibilityElement(children: .combine)
                }
            }
        }
        #if !os(tvOS)
        .scrollContentBackground(.hidden)
        .navigationTitle(location.name)
        #endif
        .background(Theme.background)
        .task(id: location.url) { await load() }
    }

    // MARK: - Loading

    private func load() async {
        entries = nil
        errorMessage = nil
        do {
            entries = try await location.connector.list(directory: location.url)
        } catch is CancellationError {
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func reload() {
        Task { await load() }
    }
}
