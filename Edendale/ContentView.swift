//
//  ContentView.swift
//  Edendale
//
//  Created by Long Ju on 5/20/26.
//

import SwiftUI

struct ContentView: View {
    @Environment(PlayerSession.self) private var session
    @Environment(AppRouter.self) private var appRouter
    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif

    var body: some View {
        RootView()
            .onOpenURL(perform: handleIncomingURL)
        #if os(iOS) || os(tvOS) || os(visionOS)
            // The player takes over the entire screen; RootView stays alive
            // (its tab/session state survives) but is fully hidden beneath.
            .fullScreenCover(isPresented: playerPresented) {
                #if os(visionOS)
                if let item = session.visionNativeItem {
                    VisionNativePlayerScreen(item: item) {
                        session.end()
                    }
                } else {
                    PlayerScreen {
                        session.end()
                    }
                }
                #else
                PlayerScreen {
                    session.end()
                }
                #endif
            }
        #elseif os(macOS)
            .onChange(of: session.isPresented) { wasPresented, isPresented in
                if !wasPresented && isPresented {
                    openWindow(id: PlayerSceneID.window)
                }
            }
        #endif
    }

    private func handleIncomingURL(_ url: URL) {
        if url.isFileURL {
            Task { await session.play(fileURL: url) }
        } else {
            appRouter.open(url)
        }
    }

    #if os(iOS) || os(tvOS) || os(visionOS)
    private var playerPresented: Binding<Bool> {
        Binding(
            get: { session.isPresented },
            set: { presented in
                if !presented { session.end() }
            }
        )
    }
    #endif
}
