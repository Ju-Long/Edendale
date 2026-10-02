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
    @State private var watchlistStore: WatchlistStore
    @State private var userMediaStore: UserMediaStore
    @State private var tmdbAccount = TMDBAccountStore()
    @State private var cloudAccounts = CloudAccountStore()
    #if os(iOS)
    /// Listens for Apple TVs asking for an account (AccountHandoff).
    @State private var accountHandoff = AccountHandoffCenter()
    #endif
    @State private var wyzieKeys = WyzieKeyStore()
    @State private var youngAudienceFilter = YoungAudienceFilter()
    @State private var audioEnhancement = AudioEnhancementController()
    @State private var videoAdjustment = VideoAdjustmentController()
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
        let watchlistStore = WatchlistStore(
            modelContext: Persistence.watchlistModelContainer.mainContext
        )
        _library = State(initialValue: library)
        _watchStore = State(initialValue: ws)
        _watchlistStore = State(initialValue: watchlistStore)
        _userMediaStore = State(initialValue: UserMediaStore())
        let audioEnhancement = AudioEnhancementController()
        let videoAdjustment = VideoAdjustmentController()
        _audioEnhancement = State(initialValue: audioEnhancement)
        _videoAdjustment = State(initialValue: videoAdjustment)
        let subtitleCache = SubtitleCacheStore(
            modelContext: Persistence.subtitleCacheModelContainer.mainContext
        )
        _playerSession = State(initialValue: PlayerSession(library: library, watchStore: ws, audioEnhancement: audioEnhancement, videoAdjustment: videoAdjustment, subtitleCache: subtitleCache))
        // Downloaded subtitles unused for more than a month go at launch,
        // on a later main-actor turn. The unit-test host keeps its files.
        if !Persistence.isRunningUnitTests {
            Task { @MainActor in subtitleCache.prune() }
        }
        #if os(iOS)
        // DeviceDiscoveryUI connects only to a listener that is already
        // running, so start it as early as possible.
        if !Persistence.isRunningUnitTests {
            let handoff = AccountHandoffCenter()
            handoff.start()
            _accountHandoff = State(initialValue: handoff)
        }
        #endif
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(library)
                .environment(watchStore)
                .environment(watchlistStore)
                .environment(userMediaStore)
                .environment(tmdbAccount)
                .environment(cloudAccounts)
                #if os(iOS)
                .environment(accountHandoff)
                #endif
                .environment(wyzieKeys)
                .environment(youngAudienceFilter)
                .environment(audioEnhancement)
                .environment(videoAdjustment)
                .environment(playerSession)
                .environment(appRouter)
                .environment(\.ratingProviders, [TMDBRatingsProvider()])
        }
        .modelContainer(Persistence.sharedModelContainer)
        #if os(macOS)
        .commands { EdendaleCommands() }
        #endif

        // macOS uses a dedicated player window. iOS, iPadOS, visionOS, and
        // tvOS present the player over ContentView instead (see ContentView).
        #if os(macOS)
        Window("Now Playing", id: PlayerSceneID.window) {
            PlayerWindowRoot()
                .environment(library)
                .environment(watchStore)
                .environment(wyzieKeys)
                .environment(audioEnhancement)
                .environment(videoAdjustment)
                .environment(playerSession)
                .environment(appRouter)
        }
        .defaultSize(width: 1280, height: 720)
        .windowStyle(.hiddenTitleBar)
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
