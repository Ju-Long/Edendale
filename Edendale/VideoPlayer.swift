//
//  VideoPlayer.swift
//  Edendale
//
//  Created by Long Ju on 5/21/26.
//
//  Thin SwiftVLC wrapper: renders the session's `Player` into a view.
//  Playback lifecycle lives in `PlayerSession`; chrome in `PlayerScreen`.
//

import SwiftUI
import SwiftVLC

struct VideoPlayer: View {
    let player: Player
    /// Called once this view's surface has attached a drawable to the
    /// player. `VideoView` publishes the drawable to libVLC during the
    /// commit that inserts it, so one runloop hop after `onAppear`
    /// guarantees the attach has landed.
    var onSurfaceReady: (() -> Void)?

    #if os(iOS) || os(macOS)
    /// SwiftVLC's PiP-capable surface owns the rendering path required by
    /// Picture in Picture (sample-buffer on iOS, native drawable on macOS).
    /// `PiPVideoView` must replace `VideoView`; the two cannot share a player.
    @Binding private var pipController: PiPController?

    init(
        player: Player,
        pipController: Binding<PiPController?>,
        onSurfaceReady: (() -> Void)? = nil
    ) {
        self.player = player
        _pipController = pipController
        self.onSurfaceReady = onSurfaceReady
    }
    #else
    init(player: Player, onSurfaceReady: (() -> Void)? = nil) {
        self.player = player
        self.onSurfaceReady = onSurfaceReady
    }
    #endif

    var body: some View {
        #if os(iOS) || os(macOS)
        PiPVideoView(player, controller: $pipController)
            .onAppear(perform: reportSurfaceReady)
        #else
        VideoView(player)
            .onAppear(perform: reportSurfaceReady)
        #endif
    }

    private func reportSurfaceReady() {
        guard let onSurfaceReady else { return }
        DispatchQueue.main.async(execute: onSurfaceReady)
    }
}
