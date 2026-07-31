//
//  EdendaleApp.swift
//  Edendale
//
//  Created by Long Ju on 5/20/26.
//

import SwiftUI
import SwiftData

@main
struct EdendaleApp: App {
    @State private var library: LibraryController
    @State private var watchStore = WatchProgressStore()
    @State private var userMediaStore = UserMediaStore()
    @State private var tmdbAccount = TMDBAccountStore()
    @State private var wyzieKeys = WyzieKeyStore()
    @State private var playerSession: PlayerSession
    @State private var appRouter = AppRouter.shared

    #if os(iOS)
    // Reports the player's rotation lock; see OrientationLock.
    @UIApplicationDelegateAdaptor(EdendaleAppDelegate.self) private var appDelegate
    #endif

    init() {
        FontRegistrar.registerAll()
        let library = LibraryController(
            modelContext: Persistence.sharedModelContainer.mainContext
        )
        let ws = WatchProgressStore()
        _library = State(initialValue: library)
        _watchStore = State(initialValue: ws)
        _playerSession = State(initialValue: PlayerSession(library: library, watchStore: ws))
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(library)
                .environment(watchStore)
                .environment(userMediaStore)
                .environment(tmdbAccount)
                .environment(wyzieKeys)
                .environment(playerSession)
                .environment(appRouter)
                .environment(\.ratingProviders, [TMDBRatingsProvider()])
        }
        .modelContainer(Persistence.sharedModelContainer)

        // macOS uses a dedicated player window. iOS, iPadOS, visionOS, and
        // tvOS present the player over ContentView instead (see ContentView).
        #if os(macOS)
        Window("Now Playing", id: PlayerSceneID.window) {
            PlayerWindowRoot()
                .environment(library)
                .environment(watchStore)
                .environment(wyzieKeys)
                .environment(playerSession)
                .environment(appRouter)
        }
        .defaultSize(width: 1280, height: 720)
        #endif
    }
}

#if os(iOS)
final class EdendaleAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        OrientationLock.effectiveMask
    }
}
#endif
