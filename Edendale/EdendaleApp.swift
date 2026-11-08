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
    @State private var cloudAccounts: CloudAccountStore
    /// File-based sync with the user's other devices (FEATURES-27.1.md §5).
    @State private var sync: SyncCoordinator
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
        let cloudAccounts = CloudAccountStore()
        // Google Drive moved to drive.file (ENHANCEMENT.md J.14, D1): older
        // Google accounts and sources go, with a notice. The unit-test host
        // leaves the Keychain and library alone.
        if !Persistence.isRunningUnitTests {
            cloudAccounts.migrateGoogleDriveToFileAccess(library: library)
        }
        _cloudAccounts = State(initialValue: cloudAccounts)
        _watchStore = State(initialValue: ws)
        _watchlistStore = State(initialValue: watchlistStore)
        let userMediaStore = UserMediaStore()
        _userMediaStore = State(initialValue: userMediaStore)
        // The watchlist flag travels in the user-media sync record: a change
        // made here stamps that record, and one that arrives (from a file
        // or through CloudKit) reaches the watchlist's cards and TMDB mirror.
        watchlistStore.onLocalChange = { [userMediaStore] ref, inWatchlist in
            userMediaStore.setWatchlistFlag(inWatchlist, for: ref)
        }
        userMediaStore.onWatchlistSynced = { [watchlistStore] ref, inWatchlist in
            watchlistStore.mirror(inWatchlist, for: ref)
        }
        // Before the first sync can write the flag out: rows from before
        // 27.1 take it from the local watchlist, and the watchlist follows
        // rows another device changed while nothing was listening.
        let listed = watchlistStore.items.filter(\.isInWatchlist)
        let mirrors = userMediaStore.reconcileWatchlist(
            listed: Set(listed.map(\.ref)),
            dates: Dictionary(listed.map { ($0.ref, $0.updatedAt) }, uniquingKeysWith: { first, _ in first })
        )
        for (ref, inWatchlist) in mirrors {
            watchlistStore.mirror(inWatchlist, for: ref)
        }
        let sync = SyncCoordinator(watchStore: ws, userMediaStore: userMediaStore)
        _sync = State(initialValue: sync)
        if !Persistence.isRunningUnitTests {
            sync.start()
            #if os(iOS)
            SyncBackgroundRefresh.coordinator = sync
            #endif
        }
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
        mainWindow
            .modelContainer(Persistence.sharedModelContainer)
            #if os(macOS)
            .commands { EdendaleCommands() }
            #endif

        #if os(visionOS)
        // The player's Theater menu opens this around the viewer. Its
        // controls panel hosts the player's own panels, so it gets the
        // environment the player window has.
        ImmersiveSpace(id: VisionTheaterController.spaceID) {
            VisionTheaterSpace()
                .environment(library)
                .environment(watchStore)
                .environment(watchlistStore)
                .environment(userMediaStore)
                .environment(tmdbAccount)
                .environment(cloudAccounts)
                .environment(sync)
                .environment(wyzieKeys)
                .environment(youngAudienceFilter)
                .environment(audioEnhancement)
                .environment(videoAdjustment)
                .environment(playerSession)
                .environment(appRouter)
                .environment(\.ratingProviders, [TMDBRatingsProvider()])
                .modelContainer(Persistence.sharedModelContainer)
        }
        .immersionStyle(selection: .constant(.full), in: .full)
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

        // Edendale ▸ Settings… (⌘,) opens this window; the library sidebar
        // has no Settings row. A Settings window can't be resized, so it
        // opens tall enough for several sections and scrolls the rest.
        Settings {
            SettingsView()
                // The sidebar takes 200 points; the open page keeps the width it had
                // when Settings was one page.
                .frame(width: 960, height: 680)
                .tint(Theme.gold)
                .preferredColorScheme(.dark)
                .environment(library)
                .environment(watchStore)
                .environment(watchlistStore)
                .environment(userMediaStore)
                .environment(tmdbAccount)
                .environment(cloudAccounts)
                .environment(sync)
                .environment(wyzieKeys)
                .environment(youngAudienceFilter)
                .environment(audioEnhancement)
                .environment(videoAdjustment)
                .environment(playerSession)
                .environment(appRouter)
                .environment(\.ratingProviders, [TMDBRatingsProvider()])
        }
        .modelContainer(Persistence.sharedModelContainer)
        #endif
    }

    /// The library, with the player over it on every platform but macOS.
    /// visionOS names the window, so the theater can reopen it after
    /// closing it (see VisionTheaterController).
    private var mainWindow: some Scene {
        #if os(visionOS)
        WindowGroup(id: VisionTheaterController.playerWindowID) { mainContent }
        #else
        WindowGroup { mainContent }
        #endif
    }

    private var mainContent: some View {
        ContentView()
            .environment(library)
            .environment(watchStore)
            .environment(watchlistStore)
            .environment(userMediaStore)
            .environment(tmdbAccount)
            .environment(cloudAccounts)
            .environment(sync)
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
}

#if os(iOS)
final class EdendaleAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Background app refresh for sync (FEATURES-27.1.md §8); the
        // handler must be registered before launching finishes.
        if !Persistence.isRunningUnitTests {
            SyncBackgroundRefresh.register()
        }
        return true
    }

    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        OrientationLock.effectiveMask
    }
}
#endif
