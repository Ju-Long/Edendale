//
//  TrailerPlayerView.swift
//  Edendale
//
//  In-place trailer playback for the hero. TMDB's /videos endpoint returns
//  YouTube keys, so playback goes through the privacy-enhanced
//  youtube-nocookie embed in a WKWebView; the IFrame API reports when the
//  trailer finishes.
//
//  NOTE: this is a deliberate, user-approved exception to the "TMDB-only
//  network" rule — trailer streams come from YouTube.
//

import SwiftUI

#if canImport(WebKit)
import WebKit

struct TrailerPlayerView {
    /// Whether this platform can actually play embedded trailers.
    static let isSupported = true

    /// YouTube video key from TMDB (`TMDBVideo.key`).
    let youTubeKey: String
    /// Called once when the trailer plays to the end.
    var onFinished: () -> Void = {}

    final class Coordinator: NSObject, WKScriptMessageHandler {
        var onFinished: () -> Void = {}

        func userContentController(
            _ userContentController: WKUserContentController,
            didReceive message: WKScriptMessage
        ) {
            if message.body as? String == "ended" {
                onFinished()
            }
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    fileprivate func makeWebView(coordinator: Coordinator) -> WKWebView {
        coordinator.onFinished = onFinished

        let configuration = WKWebViewConfiguration()
        configuration.mediaTypesRequiringUserActionForPlayback = []
        #if !os(macOS)
        configuration.allowsInlineMediaPlayback = true
        #endif
        configuration.userContentController.add(coordinator, name: "trailer")

        let webView = WKWebView(frame: .zero, configuration: configuration)
        #if !os(macOS)
        webView.scrollView.isScrollEnabled = false
        webView.isOpaque = false
        #endif
        webView.loadHTMLString(
            Self.embedHTML(for: youTubeKey),
            baseURL: URL(string: "https://www.youtube-nocookie.com")
        )
        return webView
    }

    fileprivate static func teardown(_ webView: WKWebView) {
        webView.configuration.userContentController.removeScriptMessageHandler(forName: "trailer")
        webView.stopLoading()
    }

    /// Minimal IFrame-API page: full-bleed player, autoplay, end event
    /// posted back to the "trailer" message handler.
    private static func embedHTML(for key: String) -> String {
        """
        <!doctype html><html><head>
        <meta name="viewport" content="initial-scale=1, maximum-scale=1">
        <style>
          html, body { margin: 0; height: 100%; background: #000; overflow: hidden; }
          #player { position: absolute; inset: 0; width: 100%; height: 100%; }
        </style>
        </head><body>
        <div id="player"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
          function onYouTubeIframeAPIReady() {
            new YT.Player('player', {
              host: 'https://www.youtube-nocookie.com',
              videoId: '\(key)',
              playerVars: { autoplay: 1, playsinline: 1, rel: 0 },
              events: {
                onStateChange: function (e) {
                  if (e.data === YT.PlayerState.ENDED) {
                    window.webkit.messageHandlers.trailer.postMessage('ended');
                  }
                }
              }
            });
          }
        </script>
        </body></html>
        """
    }
}

#if os(macOS)
extension TrailerPlayerView: NSViewRepresentable {
    func makeNSView(context: Context) -> WKWebView {
        makeWebView(coordinator: context.coordinator)
    }

    func updateNSView(_ nsView: WKWebView, context: Context) {
        context.coordinator.onFinished = onFinished
    }

    static func dismantleNSView(_ nsView: WKWebView, coordinator: Coordinator) {
        teardown(nsView)
    }
}
#else
extension TrailerPlayerView: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView {
        makeWebView(coordinator: context.coordinator)
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        context.coordinator.onFinished = onFinished
    }

    static func dismantleUIView(_ uiView: WKWebView, coordinator: Coordinator) {
        teardown(uiView)
    }
}
#endif

#else

#if canImport(UIKit)
import UIKit
#endif

/// tvOS has no WebKit, so embedded YouTube trailers can't play. Report
/// "finished" immediately so callers fall straight through to their
/// countdown instead of stalling on a black screen.
///
/// There's no embedded player, but tvOS *can* hand off to the installed
/// YouTube app via its custom URL scheme, so callers can offer a "Watch on
/// YouTube" action instead (see `canOpenExternally`/`openExternally`).
struct TrailerPlayerView: View {
    /// No WebKit on this platform — callers should not start a trailer.
    static let isSupported = false

    let youTubeKey: String
    var onFinished: () -> Void = {}

    var body: some View {
        Theme.surfaceLow
            .onAppear(perform: onFinished)
    }

    /// Deep link that opens the YouTube tvOS app straight to a video. This is
    /// the YouTube app's undocumented custom scheme — best-effort, not an
    /// Apple/Google API — but the one that works in practice on tvOS.
    static func externalURL(for key: String) -> URL? {
        URL(string: "youtube://www.youtube.com/watch?v=\(key)")
    }

    /// Whether the YouTube app is installed and can play `key`. Relies on
    /// `youtube` being listed in the Info.plist `LSApplicationQueriesSchemes`;
    /// without it `canOpenURL` always reports `false`.
    static func canOpenExternally(_ key: String) -> Bool {
        guard let url = externalURL(for: key) else { return false }
        return UIApplication.shared.canOpenURL(url)
    }

    /// Hand playback off to the YouTube app. Edendale is backgrounded while
    /// YouTube takes over full screen — this is a one-way jump, not embedded
    /// playback, so there's no "trailer finished" callback.
    static func openExternally(_ key: String) {
        guard let url = externalURL(for: key) else { return }
        UIApplication.shared.open(url)
    }
}

#endif
