//
//  SettingsView.swift
//  Edendale
//
//  The settings surface, grouped into categories: General, Player Controls,
//  Audio, Subtitles, Library, Sync, Accounts, and About. tvOS and visionOS
//  give it its own tab, and macOS its own window from the app menu
//  (Edendale ▸ Settings…, ⌘,); iPhone and iPad reach it from a toolbar item
//  as a sheet. macOS lists the categories in a sidebar beside the open one,
//  the way System Settings does; every other platform lists them on a first
//  page that each category's own page pushes onto. macOS and tvOS draw the
//  pages as full archive pages, iOS and visionOS as grouped lists.
//

import SwiftUI
import UniformTypeIdentifiers

/// One page of Settings.
enum SettingsCategory: String, CaseIterable, Identifiable, Hashable {
    case general
    case playerControls
    case audio
    case subtitles
    case library
    case sync
    case accounts
    case about

    var id: Self { self }

    var title: String {
        switch self {
        case .general: String(localized: "General")
        case .playerControls: String(localized: "Player Controls")
        case .audio: String(localized: "Audio")
        case .subtitles: String(localized: "Subtitles")
        case .library: String(localized: "Library")
        case .sync: String(localized: "Sync")
        case .accounts: String(localized: "Accounts")
        case .about: String(localized: "About")
        }
    }

    var icon: ImageResource {
        switch self {
        case .general: .gearComplex
        case .playerControls: .play
        case .audio: .fileMusic
        case .subtitles: .subtitles
        case .library: .folderTree
        case .sync: .arrowRotateRight
        case .accounts: .circleUserFill
        case .about: .filmCircleInfo
        }
    }
}

struct SettingsView: View {
    #if os(iOS)
    /// Closes the sheet. Read here, outside the navigation stack: inside it,
    /// a pushed page's `dismiss` would only pop back to the category list.
    @Environment(\.dismiss) private var dismiss
    #endif
    @Environment(LibraryController.self) private var library
    @State private var showImporter = false
    @State private var showLinkSource = false
    #if os(macOS)
    @State private var selection: SettingsCategory = .general
    #endif

    var body: some View {
        container
            // Sheet on every platform — see DownloadedView; tvOS presents it
            // full screen and the Menu button walks back out of it. Both
            // pickers hang off the whole stack, so the Library page can open
            // them wherever it sits.
            .sheet(isPresented: $showLinkSource) { AddNetworkSourceView() }
            #if !os(tvOS)
            .fileImporter(
                isPresented: $showImporter,
                allowedContentTypes: [.folder],
                allowsMultipleSelection: true
            ) { result in
                guard case .success(let urls) = result else { return }
                Task {
                    for url in urls {
                        await library.importFolder(url: url)
                    }
                }
            }
            #endif
    }

    @ViewBuilder
    private var container: some View {
        #if os(macOS)
        HStack(spacing: 0) {
            List(selection: $selection) {
                ForEach(SettingsCategory.allCases) { category in
                    SettingsCategoryLabel(category: category)
                        .tag(category)
                }
            }
            .listStyle(.sidebar)
            .frame(width: 200)

            Rectangle()
                .fill(Theme.hairline)
                .frame(width: 1)
                .accessibilityHidden(true)

            page(selection)
                .id(selection)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Theme.background)
        .navigationTitle(selection.title)
        #else
        NavigationStack {
            categoryList
                .background(Theme.background)
                .settingsCloseButton(closeSettings)
                // tvOS renders a navigation title over a scrolling page as a
                // giant mid-screen overlay; its sidebar already names the tab.
                #if !os(tvOS)
                .navigationTitle("Settings")
                #endif
                .navigationDestination(for: SettingsCategory.self) { category in
                    page(category)
                        .settingsCloseButton(closeSettings)
                        #if !os(tvOS)
                        .navigationTitle(category.title)
                        #endif
                }
        }
        #endif
    }

    private func closeSettings() {
        #if os(iOS)
        dismiss()
        #endif
    }

    private func page(_ category: SettingsCategory) -> some View {
        SettingsCategoryPage(
            category: category,
            showImporter: $showImporter,
            showLinkSource: $showLinkSource
        )
    }

    #if !os(macOS)
    @ViewBuilder
    private var categoryList: some View {
        #if os(tvOS)
        SettingsPage {
            SettingsSection(nil) {
                ForEach(SettingsCategory.allCases) { category in
                    NavigationLink(value: category) {
                        HStack(spacing: 24) {
                            SettingsCategoryLabel(category: category)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            Image(.chevronRight)
                                .foregroundStyle(Theme.textSecondary)
                                .accessibilityHidden(true)
                        }
                        .padding(SettingsMetrics.highlightBleed)
                        .contentShape(Rectangle())
                    }
                    .archiveRowStyle()
                    .padding(-SettingsMetrics.highlightBleed)
                }
            }
        }
        #else
        List {
            Section {
                ForEach(SettingsCategory.allCases) { category in
                    NavigationLink(value: category) {
                        SettingsCategoryLabel(category: category)
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
        #endif
    }
    #endif
}

/// A category's icon and name, in the sidebar and on the first page.
private struct SettingsCategoryLabel: View {
    let category: SettingsCategory

    var body: some View {
        Label {
            Text(category.title)
                #if os(tvOS)
                .font(SettingsMetrics.titleFont)
                .foregroundStyle(Theme.textPrimary)
                #endif
        } icon: {
            Image(category.icon)
                .foregroundStyle(Theme.textSecondary)
        }
    }
}

// MARK: - Category pages

/// The sections of one category, as a full page (macOS, tvOS) or a grouped
/// list (iOS, visionOS).
struct SettingsCategoryPage: View {
    let category: SettingsCategory
    /// Owned by SettingsView: the pickers must hang off its navigation
    /// container, not off a Section inside the List.
    @Binding var showImporter: Bool
    @Binding var showLinkSource: Bool

    var body: some View {
        content
            .background(Theme.background)
    }

    @ViewBuilder
    private var content: some View {
        #if os(macOS) || os(tvOS)
        SettingsPage { sections }
        #else
        List { sections }
            .scrollContentBackground(.hidden)
        #endif
    }

    @ViewBuilder
    private var sections: some View {
        switch category {
        case .general:
            GeneralSettingsSections()
        case .playerControls:
            AppControlsSection()
            SegmentSkippingSection()
        case .audio:
            AudioEnhancementSection()
        case .subtitles:
            SubtitleAppearanceSection()
//            WyzieSubtitlesSection()
        case .library:
            SourcesSection(
                showImporter: $showImporter,
                showLinkSource: $showLinkSource
            )
        case .sync:
            SettingsSection(nil) {
                SettingsRow(
                    String(localized: "Watch Progress"),
                    value: String(localized: "Synced via your iCloud")
                )
            }
            SyncSection()
        case .accounts:
            AccountsSection()
            TMDBAccountSection()
        case .about:
            AboutSettingsSection()
        }
    }
}

/// Settings → General: who the library is for and, on the Mac, starting
/// at login.
private struct GeneralSettingsSections: View {
    @Environment(YoungAudienceFilter.self) private var youngAudienceFilter
    #if os(macOS)
    @Environment(\.scenePhase) private var scenePhase
    @State private var loginItem = LoginItemController()
    #endif

    var body: some View {
        SettingsSection(String(localized: "Audience")) {
            SettingsToggleRow(
                String(localized: "Young Audience Friendly"),
                detail: String(localized: "Only show movies and series rated PG or PG-13, including equivalent TV labels."),
                isOn: Binding(
                    get: { youngAudienceFilter.isEnabled },
                    set: { youngAudienceFilter.isEnabled = $0 }
                )
            )
        }

        #if os(macOS)
        SettingsSection(String(localized: "Startup")) {
            SettingsToggleRow(
                String(localized: "Launch at Login"),
                detail: loginItem.statusMessage,
                isOn: Binding(
                    get: { loginItem.isRegistered },
                    set: { loginItem.setEnabled($0) }
                )
            )
            .disabled(!loginItem.isAvailable)

            if loginItem.requiresApproval {
                SettingsActions {
                    Button("Open Login Items Settings") {
                        loginItem.openSystemSettings()
                    }
                    .archiveButtonStyle(.secondary)
                }
            }

            if let message = loginItem.errorMessage {
                SettingsNote(message)
            }
        }
        .onAppear { loginItem.refresh() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                loginItem.refresh()
            }
        }
        #endif
    }
}

/// Settings → About: the version and the services Edendale credits.
private struct AboutSettingsSection: View {
    var body: some View {
        SettingsSection(nil) {
            SettingsRow(String(localized: "Version"), value: appVersion)
            // TMDB's API terms ask for this notice wherever the app credits
            // its sources.
            SettingsNote(String(localized: "This product uses the TMDB API but is not endorsed or certified by TMDB."))
//            SettingsNote(String(localized: "Online subtitles are provided by Wyzie Subs."))
            #if !os(tvOS)
            SettingsActions {
                Link("Timestamps by TheIntroDB", destination: URL(string: "https://theintrodb.org")!)
                    .settingsLinkStyle()
            }
            #endif
        }
    }

    private var appVersion: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0.1"
    }
}

// MARK: - Closing the sheet

private extension View {
    /// iPhone and iPad show Settings as a sheet; every page offers a way out.
    @ViewBuilder
    func settingsCloseButton(_ close: @escaping () -> Void) -> some View {
        #if os(iOS)
        modifier(SettingsCloseButton(close: close))
        #else
        self
        #endif
    }
}

#if os(iOS)
private struct SettingsCloseButton: ViewModifier {
    let close: () -> Void

    func body(content: Content) -> some View {
        content.toolbar {
            ToolbarItem(placement: .cancellationAction) {
                Button("", image: .xmark) { close() }
                    .archiveButtonStyle(.ghost)
                    // The title is empty so the glyph can stand alone.
                    .accessibilityLabel("Close")
            }
        }
    }
}
#endif

// MARK: - iOS toolbar entry point

/// On iPhone and iPad the settings entry lives in the navigation bar; tvOS
/// and visionOS have a dedicated tab (see RootView), and macOS a Settings
/// window (see EdendaleApp).
struct SettingsToolbarModifier: ViewModifier {
    @State private var showSettings = false

    func body(content: Content) -> some View {
        #if os(iOS)
        content
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showSettings = true
                    } label: {
                        Image(.gearComplex)
                    }
                    .accessibilityLabel("Settings")
                }
            }
            .sheet(isPresented: $showSettings) { SettingsView().presentationSizing(.page) }
        #else
        content
        #endif
    }
}

extension View {
    func settingsToolbar() -> some View {
        modifier(SettingsToolbarModifier())
    }
}
