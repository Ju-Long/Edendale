//
//  SettingsView.swift
//  Edendale
//
//  The settings surface, grouped into categories: General (macOS only),
//  Player Controls, Audio, Subtitles, Library, Sync, Accounts, Parental
//  Controls, and About. tvOS and visionOS give it its own tab, and macOS its
//  own window from the app menu (Edendale ▸ Settings…, ⌘,); iPhone and iPad
//  reach it from a toolbar item as a sheet. macOS lists the categories in a
//  sidebar beside the open one, the way System Settings does; every other
//  platform lists them on a first page that each category's own page pushes
//  onto. On iPhone, iPad, Apple TV, and Vision Pro that first page ends with
//  the Parental Controls and About sections themselves rather than links to
//  them. macOS draws the pages as full archive pages; iOS, tvOS, and
//  visionOS as grouped lists.
//

import SwiftUI
import UniformTypeIdentifiers

/// One page of Settings.
enum SettingsCategory: String, CaseIterable, Identifiable, Hashable {
    /// Only the Mac has general settings (starting at login).
    case general
    case playerControls
    case audio
    case subtitles
    case library
    case sync
    case accounts
    case parentalControls
    case about

    var id: Self { self }

    /// The categories this platform lists, in order. The grouped List on
    /// iPhone, iPad, Apple TV, and Vision Pro shows Parental Controls and
    /// About in place at the end of the first page instead of linking to them.
    static var listed: [SettingsCategory] {
        allCases.filter { category in
            switch category {
            case .general:
                #if os(macOS)
                true
                #else
                false
                #endif
            case .parentalControls, .about:
                #if os(macOS)
                true
                #else
                false
                #endif
            default:
                true
            }
        }
    }

    var title: String {
        switch self {
        case .general: String(localized: "General")
        case .playerControls: String(localized: "Player Controls")
        case .audio: String(localized: "Audio")
        case .subtitles: String(localized: "Subtitles")
        case .library: String(localized: "Library")
        case .sync: String(localized: "Sync")
        case .accounts: String(localized: "Accounts")
        case .parentalControls: String(localized: "Parental Controls")
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
        case .parentalControls: .personBreastfeeding
        case .about: .filmCircleInfo
        }
    }
    
    var iconText: String {
        switch self {
        case .general: "gear-complex"
        case .playerControls: "play"
        case .audio: "file-music"
        case .subtitles: "subtitles"
        case .library: "folder-tree"
        case .sync: "arrow-rotate-right"
        case .accounts: "circle-user-fill"
        case .parentalControls: "person-breastfeeding"
        case .about: "film-circle-info"
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
        TabView {
            ForEach(SettingsCategory.listed) { category in
                Tab {
                    page(category)
                } label: {
                    SettingsCategoryLabel(category: category)
                }
            }
        }
//        HStack(spacing: 0) {
//            List(selection: $selection) {
//                ForEach(SettingsCategory.listed) { category in
//                    SettingsCategoryLabel(category: category)
//                        .tag(category)
//                }
//            }
//            .listStyle(.sidebar)
//            .frame(width: 200)
//
//            Rectangle()
//                .fill(Theme.hairline)
//                .frame(width: 1)
//                .accessibilityHidden(true)
//
//            page(selection)
//                .id(selection)
//                .frame(maxWidth: .infinity, maxHeight: .infinity)
//        }
        .background(Theme.background)
        .navigationTitle(selection.title)
        #else
        NavigationStack {
            categoryList
                .background(Theme.background)
                .settingsCloseButton(closeSettings)
                .navigationTitle("Settings")
                #if os(tvOS)
                // The title is the list's first row (see SettingsPageTitle).
                .toolbar(.hidden, for: .navigationBar)
                #endif
                .navigationDestination(for: SettingsCategory.self) { category in
                    page(category)
                        .settingsCloseButton(closeSettings)
                        .settingsPageChrome(category.title)
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
        List {
            #if os(tvOS)
            SettingsPageTitle(String(localized: "Settings"))
            #endif
            Section {
                ForEach(SettingsCategory.listed) { category in
                    NavigationLink(value: category) {
                        SettingsCategoryLabel(category: category)
                    }
                }
            }
            ParentalControlsSection(title: SettingsCategory.parentalControls.title)
            AboutSettingsSection(title: SettingsCategory.about.title)
        }
        #if !os(tvOS)
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
        } icon: {
            Image(category.icon)
                #if os(tvOS)
                // The system grey, which follows the focused row's light
                // platter; the hierarchical `.secondary` would resolve
                // against the gold tint the list gives row icons. A column
                // wide enough for the widest glyph lines the names up.
                .foregroundStyle(Color.secondary)
                .frame(width: 40)
                #else
                .foregroundStyle(Theme.textSecondary)
                #endif
        }
    }
}

// MARK: - Category pages

/// The sections of one category, as a full page (macOS) or a grouped list
/// (iOS, tvOS, visionOS).
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
        #if os(macOS)
        SettingsPage { sections }
        #else
        List {
            #if os(tvOS)
            SettingsPageTitle(category.title)
            #endif
            sections
        }
            #if !os(tvOS)
            .scrollContentBackground(.hidden)
            #endif
        #endif
    }

    @ViewBuilder
    private var sections: some View {
        switch category {
        case .general:
            #if os(macOS)
            StartupSettingsSection()
            #else
            EmptyView()
            #endif
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
        case .parentalControls:
            ParentalControlsSection(title: nil)
        case .about:
            AboutSettingsSection(title: nil)
        }
    }
}

/// Settings → Parental Controls: who the library is for. The preference is
/// this device's own; nothing locks it, and it is never synced. Its own page
/// on the Mac (untitled, under the page's title); a titled section above
/// About on the first page everywhere else.
private struct ParentalControlsSection: View {
    let title: String?
    @Environment(YoungAudienceFilter.self) private var youngAudienceFilter

    var body: some View {
        SettingsSection(title) {
            SettingsToggleRow(
                String(localized: "Young Audience Friendly"),
                detail: String(localized: "Only show movies and series rated PG or PG-13, including equivalent TV labels."),
                isOn: Binding(
                    get: { youngAudienceFilter.isEnabled },
                    set: { youngAudienceFilter.isEnabled = $0 }
                )
            )
        }
    }
}

#if os(macOS)
/// Settings → General on the Mac: starting at login.
private struct StartupSettingsSection: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var loginItem = LoginItemController()

    var body: some View {
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
    }
}
#endif

/// Settings → About: the version and the services Edendale credits. Its own
/// page on the Mac (untitled, under the page's title); a titled section at
/// the end of the first page everywhere else.
private struct AboutSettingsSection: View {
    let title: String?

    var body: some View {
        SettingsSection(title) {
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
            ToolbarItem(placement: .confirmationAction) {
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
