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
    public var presentationTime: CMTime
    public var currentPixelBuffer: CVPixelBuffer?
    public var currentTexture: MTLTexture?
    public var aspectMode: VideoAspectMode
    public var isPaused: Bool
    public var testPatternEnabled: Bool
    var enhancementPipeline: EnhancementPipeline?
    var subtitleEngine: SubtitleEngine?
    var pipSource: SampleBufferPiPSource?
    public var onSurfaceReady: ((EnhancedVideoView) -> Void)?

    init(
        ringBuffer: FrameRingBuffer? = nil,
        presentationTime: CMTime = .zero,
        currentPixelBuffer: CVPixelBuffer? = nil,
        currentTexture: MTLTexture? = nil,
        aspectMode: VideoAspectMode = .fit,
        isPaused: Bool = false,
        testPatternEnabled: Bool = false,
        enhancementPipeline: EnhancementPipeline? = nil,
        subtitleEngine: SubtitleEngine? = nil,
        pipSource: SampleBufferPiPSource? = nil,
        onSurfaceReady: ((EnhancedVideoView) -> Void)? = nil
    ) {
        self.ringBuffer = ringBuffer
        self.presentationTime = presentationTime
        self.currentPixelBuffer = currentPixelBuffer
        self.currentTexture = currentTexture
        self.aspectMode = aspectMode
        self.isPaused = isPaused
        self.testPatternEnabled = testPatternEnabled
        self.enhancementPipeline = enhancementPipeline
        self.subtitleEngine = subtitleEngine
        self.pipSource = pipSource
        self.onSurfaceReady = onSurfaceReady
    }

    public var body: some View {
        EnhancedVideoPlayerRepresentable(
            ringBuffer: ringBuffer,
            presentationTime: presentationTime,
            currentPixelBuffer: currentPixelBuffer,
            currentTexture: currentTexture,
            aspectMode: aspectMode,
            isPaused: isPaused,
            testPatternEnabled: testPatternEnabled,
            enhancementPipeline: enhancementPipeline,
            subtitleEngine: subtitleEngine,
            subtitleRevision: subtitleEngine?.revision ?? 0,
            pipSource: pipSource,
            onSurfaceReady: onSurfaceReady
        )
    }
}

#if os(macOS)
private struct EnhancedVideoPlayerRepresentable: NSViewRepresentable {
    let ringBuffer: FrameRingBuffer?
    let presentationTime: CMTime
    let currentPixelBuffer: CVPixelBuffer?
    let currentTexture: MTLTexture?
    let aspectMode: VideoAspectMode
    let isPaused: Bool
    let testPatternEnabled: Bool
    let enhancementPipeline: EnhancementPipeline?
    let subtitleEngine: SubtitleEngine?
    let subtitleRevision: UInt
    let pipSource: SampleBufferPiPSource?
    let onSurfaceReady: ((EnhancedVideoView) -> Void)?

    func makeNSView(context: Context) -> EnhancedVideoView {
        let view = EnhancedVideoView()
        view.onReady = onSurfaceReady
        apply(to: view)
        return view
    }

    func updateNSView(_ nsView: EnhancedVideoView, context: Context) {
        apply(to: nsView)
    }

    static func dismantleNSView(_ nsView: EnhancedVideoView, coordinator: ()) {
        nsView.pipSource = nil
        nsView.isPlaybackPaused = true
        nsView.isPaused = true
    }

    private func apply(to view: EnhancedVideoView) {
        view.ringBuffer = ringBuffer
        let timeChanged = view.currentDisplayTime != presentationTime
        view.currentDisplayTime = presentationTime
        view.aspectMode = aspectMode
        view.isPlaybackPaused = isPaused
        view.testPatternEnabled = testPatternEnabled
        view.enhancementPipeline = enhancementPipeline
        view.subtitleEngine = subtitleEngine
        let subtitleChanged = view.subtitleRevision != subtitleRevision
        view.subtitleRevision = subtitleRevision
        view.pipSource = pipSource
        if let currentPixelBuffer {
            view.currentPixelBuffer = currentPixelBuffer
        }
        if let currentTexture {
            view.currentTexture = currentTexture
        }
        if isPaused && (timeChanged || subtitleChanged) { view.draw() }
    }
}
#else
private struct EnhancedVideoPlayerRepresentable: UIViewRepresentable {
    let ringBuffer: FrameRingBuffer?
    let presentationTime: CMTime
    let currentPixelBuffer: CVPixelBuffer?
    let currentTexture: MTLTexture?
    let aspectMode: VideoAspectMode
    let isPaused: Bool
    let testPatternEnabled: Bool
    let enhancementPipeline: EnhancementPipeline?
    let subtitleEngine: SubtitleEngine?
    let subtitleRevision: UInt
    let pipSource: SampleBufferPiPSource?
    let onSurfaceReady: ((EnhancedVideoView) -> Void)?

    func makeUIView(context: Context) -> EnhancedVideoView {
        let view = EnhancedVideoView()
        view.onReady = onSurfaceReady
        apply(to: view)
        return view
    }

    func updateUIView(_ uiView: EnhancedVideoView, context: Context) {
        apply(to: uiView)
    }

    static func dismantleUIView(_ uiView: EnhancedVideoView, coordinator: ()) {
        uiView.pipSource = nil
        uiView.isPlaybackPaused = true
        uiView.isPaused = true
    }

    private func apply(to view: EnhancedVideoView) {
        view.ringBuffer = ringBuffer
        let timeChanged = view.currentDisplayTime != presentationTime
        view.currentDisplayTime = presentationTime
        view.aspectMode = aspectMode
        view.isPlaybackPaused = isPaused
        view.testPatternEnabled = testPatternEnabled
        view.enhancementPipeline = enhancementPipeline
        view.subtitleEngine = subtitleEngine
        let subtitleChanged = view.subtitleRevision != subtitleRevision
        view.subtitleRevision = subtitleRevision
        view.pipSource = pipSource
        if let currentPixelBuffer {
            view.currentPixelBuffer = currentPixelBuffer
        }
        if let currentTexture {
            view.currentTexture = currentTexture
        }
        if isPaused && (timeChanged || subtitleChanged) { view.draw() }
    }
}
#endif
