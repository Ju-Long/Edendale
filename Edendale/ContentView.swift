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
    @Environment(CloudAccountStore.self) private var cloudAccounts
    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif
    #if os(iOS)
    @Environment(AccountHandoffCenter.self) private var accountHandoff
    #endif

    var body: some View {
        RootView()
            .onOpenURL(perform: handleIncomingURL)
            // Once, after the move to drive.file removed old Google Drive
            // sources (ENHANCEMENT.md J.14, D1).
            .alert("Link Google Drive Again", isPresented: googleDriveRelinkNotice) {
                Button("OK") {}
            } message: {
                Text(googleDriveRelinkMessage)
            }
        #if os(iOS)
            // An Apple TV asked for an account or login (AccountHandoff).
            .sheet(item: Binding(get: { accountHandoff.pending }, set: { _ in })) { pending in
                AccountHandoffRequestView(pending: pending)
            }
        #endif
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

    private var googleDriveRelinkNotice: Binding<Bool> {
        Binding(
            get: { cloudAccounts.needsGoogleDriveRelinkNotice },
            set: { if !$0 { cloudAccounts.needsGoogleDriveRelinkNotice = false } }
        )
    }

    private var googleDriveRelinkMessage: String {
        #if os(tvOS)
        String(localized: "Edendale now sees only the Google Drive videos you choose, so your earlier Google Drive sources were removed. Choose your videos on an iPhone, iPad, or Mac, then link Google Drive here from Link Source.")
        #else
        String(localized: "Edendale now sees only the Google Drive videos you choose, so your earlier Google Drive sources were removed. Open Link Source → Google Drive, sign in, and choose your videos.")
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
            get: { session.isPlayerPresented },
            set: { presented in
                if !presented && !session.isHiddenForPictureInPicture { session.end() }
            }
        )
    }
    #endif
}
