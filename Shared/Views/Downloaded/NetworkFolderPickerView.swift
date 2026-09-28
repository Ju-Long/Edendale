//
//  NetworkFolderPickerView.swift
//  Edendale
//
//  One level of a remote source: subfolders navigate deeper (the view
//  recurses via BrowseLocation pushes), video files preview what an index
//  would pick up, and "Select" hands the current folder back to the Link
//  Source flow. At an SMB server's root the entries are its shares, at an
//  NFS server's its exports, and at a Google Drive account's My Drive,
//  Shared with me, and Shared drives.
//

import SwiftUI

/// A spot in a connector's tree — the navigation value the picker pushes
/// for each subfolder.
struct BrowseLocation: Hashable {
    let connector: AnyMediaConnector
    let url: URL
    let name: String
    /// Folder names from the source's top down to this one, for the
    /// readable location a linked source shows ("Google Drive › My Drive").
    let trail: [String]

    init(connector: any MediaConnector, url: URL, name: String, trail: [String]? = nil) {
        self.connector = AnyMediaConnector(connector)
        self.url = url
        self.name = name
        self.trail = trail ?? [name]
    }

    /// The location of a subfolder listed here.
    func child(_ entry: ConnectorEntry) -> BrowseLocation {
        BrowseLocation(connector: connector.base, url: entry.url, name: entry.name, trail: trail + [entry.name])
    }

    var displayPath: String {
        trail.joined(separator: " › ")
    }
}

struct NetworkFolderPickerView: View {
    let location: BrowseLocation
    /// Called with the folder the user picked.
    let onIndex: (BrowseLocation) -> Void

    @State private var entries: [ConnectorEntry]?
    @State private var errorMessage: String?

    private var folders: [ConnectorEntry] { (entries ?? []).filter(\.isDirectory) }
    private var videos: [ConnectorEntry] { (entries ?? []).filter(\.isVideo) }
    private var canIndex: Bool { location.connector.base.canIndex(location.url) }

    var body: some View {
        List {
            if canIndex {
                Section {
                    Button {
                        onIndex(location)
                    } label: {
                        Label("Select \(location.name)", image: .folderOpen)
                    }
                    .disabled(entries == nil && errorMessage == nil)
                } footer: {
                    Text("Adds every video in this folder and its subfolders to your library.")
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                }
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
                            NavigationLink(value: location.child(folder)) {
                                Text(folder.name)
                                    .foregroundStyle(Theme.textPrimary)
                                    #if os(tvOS)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(SettingsMetrics.highlightBleed)
                                    #endif
                            }
                            #if os(tvOS)
                            // Keeps the light name legible when focused, where
                            // the system's white platter would wash it out.
                            .archiveRowStyle()
                            .padding(-SettingsMetrics.highlightBleed)
                            #endif
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
            entries = try await location.connector.base.list(directory: location.url)
        } catch is CancellationError {
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func reload() {
        Task { await load() }
    }
}
