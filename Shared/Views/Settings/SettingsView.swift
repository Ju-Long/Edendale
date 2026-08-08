//
//  SettingsView.swift
//  Edendale
//
//  Placeholder settings surface. Reached from the sidebar bottom bar
//  (iPad/macOS), a toolbar item (iPhone), or its own tab (visionOS).
//

import SwiftUI
import UniformTypeIdentifiers

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(LibraryController.self) private var library
    @State private var showImporter = false
    @State private var showLinkSource = false
    #if os(macOS)
    @State private var loginItem = LoginItemController()
    #endif

    var body: some View {
        NavigationStack {
            List {
                Section {
                    LabeledContent("Version", value: appVersion)
                    LabeledContent("Watch Progress", value: String(localized: "Synced via your iCloud"))
                } header: {
                    Text("About").labelCaps()
                }
                
                #if os(macOS)
                Section {
                    Toggle("Launch at Login", isOn: launchAtLogin)
                        .disabled(!loginItem.isAvailable)

                    Text(loginItem.statusMessage)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)

                    if loginItem.requiresApproval {
                        Button("Open Login Items Settings") {
                            loginItem.openSystemSettings()
                        }
                    }

                    if let message = loginItem.errorMessage {
                        Text(message)
                            .font(Typography.bodySM)
                            .foregroundStyle(Theme.textSecondary)
                    }
                } header: {
                    Text("Startup").labelCaps()
                }
                #endif

                SourcesSection(
                    showImporter: $showImporter,
                    showLinkSource: $showLinkSource
                )

                TMDBAccountSection()

                WyzieSubtitlesSection()

                Section {
                    Text("This product uses the TMDB API but is not endorsed or certified by TMDB.")
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                    Text("Online subtitles are provided by Wyzie Subs.")
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                } header: {
                    Text("Attribution").labelCaps()
                }
            }
            #if !os(tvOS)
            .scrollContentBackground(.hidden)
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
            .navigationTitle("Settings")
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
        .frame(minWidth: 480, minHeight: 420)
        .onAppear { loginItem.refresh() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                loginItem.refresh()
            }
        }
        #endif
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

/// On iPhone the settings entry lives in the navigation bar; iPad/macOS use
/// the sidebar bottom bar and visionOS has a dedicated tab (see RootView).
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
