//
//  PlayerWindowRoot.swift
//  Edendale
//
//  Content of the dedicated macOS "Now Playing" window scene. Ends the
//  session when the window closes and closes the window when the session ends.
//

#if os(macOS)
import SwiftUI

struct PlayerWindowRoot: View {
    @Environment(PlayerSession.self) private var session
    @Environment(AppRouter.self) private var appRouter
    @Environment(\.dismissWindow) private var dismissWindow

    var body: some View {
        Group {
            if session.isPresented {
                playerScreen
            } else {
                idlePlaceholder
            }
        }
        .frame(minWidth: 960, minHeight: 540)
        // Window title follows the playing title (file name when there is
        // no metadata; see `PlaybackItem.displayTitle`).
        .navigationTitle(session.item?.displayTitle ?? String(localized: "Now Playing"))
        .onChange(of: session.isPresented) { _, isPresented in
            if !isPresented {
                dismissWindow(id: PlayerSceneID.window)
            }
        }
        .onOpenURL { url in
            if url.isFileURL {
                Task { await session.play(fileURL: url) }
            } else {
                appRouter.open(url)
            }
        }
    }

    private var playerScreen: some View {
        PlayerScreen {
            session.end()
        }
    }

    /// Shown if the system restores/opens the window with nothing playing.
    private var idlePlaceholder: some View {
        VStack(spacing: 12) {
            Image(.clapperboard)
                .font(.system(size: 40))
                .foregroundStyle(Theme.surfaceHigh)
            Text("Nothing Playing")
                .font(Typography.headlineMD)
                .textCase(.uppercase)
                .foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.background)
    }

}
#endif
