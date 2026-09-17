//
//  EnhancedVideoPlayer.swift
//  Edendale
//
//  SwiftUI wrapper hosting EnhancedVideoView across macOS, iOS, tvOS, and visionOS.
//

import CoreMedia
import CoreVideo
import Metal
import MetalKit
import SwiftUI

/// SwiftUI wrapper presenting video content rendered by `EnhancedVideoView`.
public struct EnhancedVideoPlayer: View {
    public var ringBuffer: FrameRingBuffer?
    public var currentPixelBuffer: CVPixelBuffer?
    public var currentTexture: MTLTexture?
    public var aspectMode: VideoAspectMode
    public var isPaused: Bool
    public var testPatternEnabled: Bool
    public var onSurfaceReady: ((EnhancedVideoView) -> Void)?

    public init(
        ringBuffer: FrameRingBuffer? = nil,
        currentPixelBuffer: CVPixelBuffer? = nil,
        currentTexture: MTLTexture? = nil,
        aspectMode: VideoAspectMode = .fit,
        isPaused: Bool = false,
        testPatternEnabled: Bool = false,
        onSurfaceReady: ((EnhancedVideoView) -> Void)? = nil
    ) {
        self.ringBuffer = ringBuffer
        self.currentPixelBuffer = currentPixelBuffer
        self.currentTexture = currentTexture
        self.aspectMode = aspectMode
        self.isPaused = isPaused
        self.testPatternEnabled = testPatternEnabled
        self.onSurfaceReady = onSurfaceReady
    }

    public var body: some View {
        EnhancedVideoPlayerRepresentable(
            ringBuffer: ringBuffer,
            currentPixelBuffer: currentPixelBuffer,
            currentTexture: currentTexture,
            aspectMode: aspectMode,
            isPaused: isPaused,
            testPatternEnabled: testPatternEnabled,
            onSurfaceReady: onSurfaceReady
        )
    }
}

#if os(macOS)
private struct EnhancedVideoPlayerRepresentable: NSViewRepresentable {
    let ringBuffer: FrameRingBuffer?
    let currentPixelBuffer: CVPixelBuffer?
    let currentTexture: MTLTexture?
    let aspectMode: VideoAspectMode
    let isPaused: Bool
    let testPatternEnabled: Bool
    let onSurfaceReady: ((EnhancedVideoView) -> Void)?

    func makeNSView(context: Context) -> EnhancedVideoView {
        let view = EnhancedVideoView()
        apply(to: view)
        DispatchQueue.main.async {
            onSurfaceReady?(view)
        }
        return view
    }

    func updateNSView(_ nsView: EnhancedVideoView, context: Context) {
        apply(to: nsView)
    }

    static func dismantleNSView(_ nsView: EnhancedVideoView, coordinator: ()) {
        nsView.isPaused = true
    }

    private func apply(to view: EnhancedVideoView) {
        view.ringBuffer = ringBuffer
        view.aspectMode = aspectMode
        view.isPaused = isPaused
        view.testPatternEnabled = testPatternEnabled
        if let currentPixelBuffer {
            view.currentPixelBuffer = currentPixelBuffer
        }
        if let currentTexture {
            view.currentTexture = currentTexture
        }
    }
}
#else
private struct EnhancedVideoPlayerRepresentable: UIViewRepresentable {
    let ringBuffer: FrameRingBuffer?
    let currentPixelBuffer: CVPixelBuffer?
    let currentTexture: MTLTexture?
    let aspectMode: VideoAspectMode
    let isPaused: Bool
    let testPatternEnabled: Bool
    let onSurfaceReady: ((EnhancedVideoView) -> Void)?

    func makeUIView(context: Context) -> EnhancedVideoView {
        let view = EnhancedVideoView()
        apply(to: view)
        DispatchQueue.main.async {
            onSurfaceReady?(view)
        }
        return view
    }

    func updateUIView(_ uiView: EnhancedVideoView, context: Context) {
        apply(to: uiView)
    }

    static func dismantleUIView(_ uiView: EnhancedVideoView, coordinator: ()) {
        uiView.isPaused = true
    }

    private func apply(to view: EnhancedVideoView) {
        view.ringBuffer = ringBuffer
        view.aspectMode = aspectMode
        view.isPaused = isPaused
        view.testPatternEnabled = testPatternEnabled
        if let currentPixelBuffer {
            view.currentPixelBuffer = currentPixelBuffer
        }
        if let currentTexture {
            view.currentTexture = currentTexture
        }
    }
}
#endif
