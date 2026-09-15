//
//  PlayerWindowRoot.swift
//  Edendale
//
//  Content of the dedicated macOS "Now Playing" window scene. Ends the
//  session when the window closes and closes the window when the session ends.
//

#if os(macOS)
import AppKit
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
        .background { PlayerWindowConfigurator(
            title: session.item?.displayTitle ?? String(localized: "Now Playing")
        )}
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

/// Sets the window title via NSWindow (avoiding `.navigationTitle` which
/// creates toolbar state that persists in fullscreen) and configures
/// collection behavior so the green button enters a fullscreen Space.
private struct PlayerWindowConfigurator: NSViewRepresentable {
    let title: String

    func makeNSView(context: Context) -> NSView {
        let view = NSView()
        let initialTitle = title
        DispatchQueue.main.async {
            guard let window = view.window else { return }
            window.title = initialTitle
            window.collectionBehavior.insert(.fullScreenPrimary)
            if #available(macOS 15, *) {
                window.collectionBehavior.remove(.fullScreenAllowsTiling)
            }
        }
        return view
    }

    func updateNSView(_ nsView: NSView, context: Context) {
        nsView.window?.title = title
    }
}
#endif
