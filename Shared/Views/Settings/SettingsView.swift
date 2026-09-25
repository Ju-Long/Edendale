//
//  SettingsView.swift
//  Edendale
//
//  The settings surface. macOS, tvOS, and visionOS give it its own tab;
//  macOS and tvOS draw it as a full archive page, visionOS as a grouped
//  list. iPad reaches it from the sidebar bottom bar and iPhone from a
//  toolbar item, both as a sheet (see RootView).
//

import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    #if os(iOS)
    @Environment(\.dismiss) private var dismiss
    #endif
    @Environment(\.scenePhase) private var scenePhase
    @Environment(LibraryController.self) private var library
    @Environment(YoungAudienceFilter.self) private var youngAudienceFilter
    @State private var showImporter = false
    @State private var showLinkSource = false
    #if os(macOS)
    @State private var loginItem = LoginItemController()
    #endif

    var body: some View {
        NavigationStack {
            content
            #if os(iOS)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("", image: .xmark) { dismiss() }
                        .archiveButtonStyle(.ghost)
                        // The title is empty so the glyph can stand alone.
                        .accessibilityLabel("Close")
                }
            }
            #endif
            .background(Theme.background)
            // tvOS renders a navigation title over a scrolling page as a giant
            // mid-screen overlay; its sidebar already names the tab.
            #if !os(tvOS)
            .navigationTitle("Settings")
            #endif
            // Sheet on every platform — see DownloadedView; tvOS presents it
            // full screen and the Menu button walks back out of it.
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
        #if os(macOS)
        .onAppear { loginItem.refresh() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                loginItem.refresh()
            }
        }
        #endif
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
        SettingsSection(String(localized: "About")) {
            SettingsRow(String(localized: "Version"), value: appVersion)
            SettingsRow(
                String(localized: "Watch Progress"),
                value: String(localized: "Synced via your iCloud")
            )
            #if os(macOS) || os(tvOS)
            // Kept at the top of the page: tvOS scrolls by focus, so text
            // below the last control might never come into view.
            attribution
            #endif
        }

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
                isOn: launchAtLogin
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
        #endif

        AudioEnhancementSection()

        SegmentSkippingSection()

        SourcesSection(
            showImporter: $showImporter,
            showLinkSource: $showLinkSource
        )

        TMDBAccountSection()

//        WyzieSubtitlesSection()

        #if !os(macOS) && !os(tvOS)
        SettingsSection(String(localized: "Attribution")) {
            attribution
        }
        #endif
    }

    @ViewBuilder
    private var attribution: some View {
        SettingsNote(String(localized: "This product uses the TMDB API but is not endorsed or certified by TMDB."))
//        SettingsNote(String(localized: "Online subtitles are provided by Wyzie Subs."))
    }

    #if os(macOS)
    private var launchAtLogin: Binding<Bool> {
        Binding(
            get: { loginItem.isRegistered },
            set: { loginItem.setEnabled($0) }
        )
    }
    #endif

    private var appVersion: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0.1"
    }
}

// MARK: - iPhone toolbar entry point

/// On iPhone the settings entry lives in the navigation bar; iPad uses the
/// sidebar bottom bar, and macOS, tvOS, and visionOS have a dedicated tab
/// (see RootView).
struct SettingsToolbarModifier: ViewModifier {
    @State private var showSettings = false

    func body(content: Content) -> some View {
        #if os(iOS)
        content
            .toolbar {
                if UIDevice.current.userInterfaceIdiom == .phone {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            showSettings = true
                        } label: {
                            Image(.gearComplex)
                        }
                        .accessibilityLabel("Settings")
                    }
                }
            }
            .sheet(isPresented: $showSettings) { SettingsView() }
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
