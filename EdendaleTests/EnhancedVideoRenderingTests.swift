//
//  EnhancedVideoRenderingTests.swift
//  EdendaleTests
//
//  Tests for Section D: Metal Rendering Surface
//  Verifies FrameRingBuffer, PixelBufferTextureCache, aspect-ratio quad geometry,
//  and EnhancedVideoView rendering.
//

import AVFoundation
import CoreGraphics
import CoreMedia
import CoreVideo
import Metal
import MetalKit
import Testing
@testable import Edendale

@Suite("Enhanced Video Rendering Surface Tests")
struct EnhancedVideoRenderingTests {

    // MARK: - FrameRingBuffer Tests

    @Test("FrameRingBuffer respects capacity and evicts oldest frames")
    func ringBufferCapacityAndEviction() {
        let buffer = FrameRingBuffer(capacity: 3)
        #expect(buffer.isEmpty)
        #expect(buffer.count == 0)

        guard let pb = PixelBufferTextureCache.createTestPixelBuffer(width: 64, height: 64) else {
            Issue.record("Failed to create test pixel buffer")
            return
        }

        let f1 = DecodedVideoFrame(pixelBuffer: pb, presentationTime: CMTime(value: 10, timescale: 10), duration: CMTime(value: 1, timescale: 10))
        let f2 = DecodedVideoFrame(pixelBuffer: pb, presentationTime: CMTime(value: 20, timescale: 10), duration: CMTime(value: 1, timescale: 10))
        let f3 = DecodedVideoFrame(pixelBuffer: pb, presentationTime: CMTime(value: 30, timescale: 10), duration: CMTime(value: 1, timescale: 10))
        let f4 = DecodedVideoFrame(pixelBuffer: pb, presentationTime: CMTime(value: 40, timescale: 10), duration: CMTime(value: 1, timescale: 10))

        buffer.push(f1)
        buffer.push(f2)
        buffer.push(f3)
        #expect(buffer.count == 3)
        #expect(!buffer.isEmpty)

        // Pushing 4th frame should evict f1
        buffer.push(f4)
        #expect(buffer.count == 3)

        let latest = buffer.latestFrame()
        #expect(latest?.presentationTime.value == 40)

        let after25 = buffer.latestFrame(atOrBefore: CMTime(value: 25, timescale: 10))
        #expect(after25?.presentationTime.value == 20)
    }

    @Test("FrameRingBuffer queries by time and prunes stale frames")
    func ringBufferTimeQueriesAndPruning() {
        let buffer = FrameRingBuffer(capacity: 5)
        guard let pb = PixelBufferTextureCache.createTestPixelBuffer(width: 64, height: 64) else {
            Issue.record("Failed to create test pixel buffer")
            return
        }

        for i in 1...5 {
            let frame = DecodedVideoFrame(
                pixelBuffer: pb,
                presentationTime: CMTime(value: CMTimeValue(i * 100), timescale: 1000),
                duration: CMTime(value: 100, timescale: 1000)
            )
            buffer.push(frame)
        }

        // Query at PTS 250ms -> should return frame with PTS 200ms
        let candidate = buffer.latestFrame(atOrBefore: CMTime(value: 250, timescale: 1000))
        #expect(candidate?.presentationTime.value == 200)

        // Stale frame at 100ms should have been pruned, remaining: 200, 300, 400, 500
        #expect(buffer.count == 4)

        buffer.clear()
        #expect(buffer.isEmpty)
        #expect(buffer.count == 0)
    }

    @Test("FrameRingBuffer is thread-safe under concurrent pushes and reads")
    func ringBufferThreadSafety() async {
        let buffer = FrameRingBuffer(capacity: 4)
        guard let pb = PixelBufferTextureCache.createTestPixelBuffer(width: 32, height: 32) else {
            Issue.record("Failed to create test pixel buffer")
            return
        }

        await withTaskGroup(of: Void.self) { group in
            // Writer task
            group.addTask {
                for i in 0..<100 {
                    let frame = DecodedVideoFrame(
                        pixelBuffer: pb,
                        presentationTime: CMTime(value: CMTimeValue(i), timescale: 60),
                        duration: CMTime(value: 1, timescale: 60)
                    )
                    buffer.push(frame)
                }
            }

            // Reader task
            group.addTask {
                for i in 0..<100 {
                    _ = buffer.latestFrame(atOrBefore: CMTime(value: CMTimeValue(i), timescale: 60))
                    _ = buffer.latestFrame()
                }
            }
        }

        #expect(buffer.count <= 4)
    }

    // MARK: - PixelBufferTextureCache Tests

    @Test("PixelBufferTextureCache converts CVPixelBuffer to MTLTexture zero-copy")
    func textureCacheConversion() {
        guard let device = MetalContext.shared?.device else {
            Issue.record("Metal is not supported on this device")
            return
        }

        let cache = PixelBufferTextureCache(device: device)

        guard let pixelBuffer = PixelBufferTextureCache.createTestPixelBuffer(width: 128, height: 128) else {
            Issue.record("Failed to create test pixel buffer")
            return
        }

        guard let videoTex = cache.videoTexture(from: pixelBuffer) else {
            Issue.record("Failed to convert pixel buffer to texture")
            return
        }

        #expect(videoTex.lumaTexture.width == 128)
        #expect(videoTex.lumaTexture.height == 128)
        #expect(videoTex.lumaTexture.pixelFormat == .bgra8Unorm)

        // Second call should return cached texture
        let videoTex2 = cache.videoTexture(from: pixelBuffer)
        #expect(videoTex2 != nil)

        cache.flush()
    }

    @Test("Gradient test pattern generator creates valid CVPixelBuffer")
    func gradientTestPattern() {
        guard let pb = PixelBufferTextureCache.createGradientTestPixelBuffer(width: 256, height: 256, phaseOffset: 0.5) else {
            Issue.record("Failed to create gradient test pixel buffer")
            return
        }

        #expect(CVPixelBufferGetWidth(pb) == 256)
        #expect(CVPixelBufferGetHeight(pb) == 256)
        #expect(CVPixelBufferGetPixelFormatType(pb) == kCVPixelFormatType_32BGRA)
    }

    // MARK: - MetalContext & Aspect Ratio Quad Geometry Tests

    @Test("Aspect fit pillarboxes 4:3 content in 16:9 container")
    func aspectFitPillarbox() {
        let container = CGSize(width: 1920, height: 1080) // 16:9
        let content = CGSize(width: 1440, height: 1080)   // 4:3

        let vertices = MetalContext.computeQuadVertices(
            containerSize: container,
            contentSize: content,
            aspectMode: .fit
        )

        #expect(vertices.count == 6)
        // In pillarboxing: scaleY == 1.0, scaleX < 1.0
        let vTopRight = vertices[4]
        #expect(vTopRight.position.y == 1.0)
        #expect(vTopRight.position.x < 1.0)
        #expect(vTopRight.position.x > 0.5)
    }

    @Test("Aspect fit letterboxes 16:9 content in 4:3 container")
    func aspectFitLetterbox() {
        let container = CGSize(width: 1440, height: 1080) // 4:3
        let content = CGSize(width: 1920, height: 1080)   // 16:9

        let vertices = MetalContext.computeQuadVertices(
            containerSize: container,
            contentSize: content,
            aspectMode: .fit
        )

        #expect(vertices.count == 6)
        // In letterboxing: scaleX == 1.0, scaleY < 1.0
        let vTopRight = vertices[4]
        #expect(vTopRight.position.x == 1.0)
        #expect(vTopRight.position.y < 1.0)
        #expect(vTopRight.position.y > 0.5)
    }

    @Test("Aspect fill expands overflow beyond container bounds")
    func aspectFillOverflow() {
        let container = CGSize(width: 1440, height: 1080) // 4:3
        let content = CGSize(width: 1920, height: 1080)   // 16:9

        let vertices = MetalContext.computeQuadVertices(
            containerSize: container,
            contentSize: content,
            aspectMode: .fill
        )

        #expect(vertices.count == 6)
        // In fill: content is wider than container, so scaleX > 1.0 and scaleY == 1.0
        let vTopRight = vertices[4]
        #expect(vTopRight.position.x > 1.0)
        #expect(vTopRight.position.y == 1.0)
    }

    @Test("MetalContext compiles quad render pipeline state")
    func metalContextPipelineCompilation() {
        guard let context = MetalContext.shared else {
            Issue.record("MetalContext.shared is nil")
            return
        }

        let pipeline = context.renderPipelineState(for: .bgra8Unorm)
        #expect(pipeline != nil)
    }

    // MARK: - EnhancedVideoView Tests

    @MainActor
    @Test("EnhancedVideoView initializes and configures rendering properties")
    func enhancedVideoViewInitialization() {
        let view = EnhancedVideoView(frame: CGRect(x: 0, y: 0, width: 640, height: 480))
        #expect(view.colorPixelFormat == .bgra8Unorm)
        #expect(view.aspectMode == .fit)
        #expect(view.targetFrameRate == 60.0)
        #expect(!view.isPaused)

        view.aspectMode = .fill
        #expect(view.aspectMode == .fill)

        view.setTestPattern(enabled: true)
        #expect(view.testPatternEnabled)
    }

    @MainActor
    @Test("EnhancedVideoView ingests direct CVPixelBuffer and MTLTexture")
    func enhancedVideoViewIngest() {
        let view = EnhancedVideoView(frame: CGRect(x: 0, y: 0, width: 640, height: 480))

        if let pb = PixelBufferTextureCache.createTestPixelBuffer(width: 64, height: 64) {
            view.render(pixelBuffer: pb, presentationTime: CMTime(value: 5, timescale: 1))
            #expect(view.currentPixelBuffer != nil)
            #expect(view.currentDisplayTime.value == 5)
        }

        if let tex = PixelBufferTextureCache.createTestTexture(device: view.metalContext.device, width: 64, height: 64) {
            view.render(texture: tex)
            #expect(view.currentTexture != nil)
        }
    }

    @MainActor
    @Test("EnhancedVideoView respects playback pause and app background state")
    func enhancedVideoViewPauseAndBackgroundState() {
        let view = EnhancedVideoView(frame: CGRect(x: 0, y: 0, width: 640, height: 480))
        #expect(!view.isPlaybackPaused)
        #expect(!view.isAppBackgrounded)
        #expect(!view.isPaused)

        view.isPlaybackPaused = true
        #expect(view.isPlaybackPaused)
        #expect(view.isPaused)

        view.isPlaybackPaused = false
        #expect(!view.isPlaybackPaused)
        #expect(!view.isPaused)
    }

    @MainActor
    @Test("Motion smoothing shows each real frame one refresh after its synthetic frame")
    func motionSmoothingPresentsSyntheticFrameFirst() throws {
        let view = EnhancedVideoView(frame: CGRect(x: 0, y: 0, width: 320, height: 180))
        view.isPaused = true // draw manually, one refresh per call
        let device = view.metalContext.device
        let pipeline = try #require(EnhancementPipeline(device: device))
        pipeline.preset = .off
        pipeline.frameInterpolationEnabled = true
        view.enhancementPipeline = pipeline
        view.frameInterpolator = try #require(FrameInterpolator(device: device))
        view.sourceFrameRate = 24
        let ringBuffer = FrameRingBuffer()
        view.ringBuffer = ringBuffer

        // Real frames report their presentation time; synthetic frames don't.
        var presented: [Int] = []
        view.onFramePresented = { time in presented.append(Int((time.seconds * 24).rounded())) }

        func deliver(_ index: Int) throws {
            let pixelBuffer = try #require(
                PixelBufferTextureCache.createTestPixelBuffer(width: 64, height: 64, red: UInt8(index * 40))
            )
            let time = CMTime(value: CMTimeValue(index), timescale: 24)
            ringBuffer.push(DecodedVideoFrame(
                pixelBuffer: pixelBuffer,
                presentationTime: time,
                duration: CMTime(value: 1, timescale: 24)
            ))
            view.currentDisplayTime = time
        }

        try deliver(0)
        view.draw() // first frame: nothing to blend with, shown directly
        #expect(presented == [0])
        view.draw() // nothing new decoded
        #expect(presented == [0])

        try deliver(1)
        view.draw() // the frame between 0 and 1 goes out first...
        #expect(presented == [0])
        view.draw() // ...then the held real frame
        #expect(presented == [0, 1])

        try deliver(3) // frame 2 was dropped: nothing to blend, shown directly
        view.draw()
        #expect(presented == [0, 1, 3])
    }

    @MainActor
    @Test("EnhancedVideoView manages PiP source layer attachment")
    func enhancedVideoViewPiPLayerAttachment() {
        let view = EnhancedVideoView(frame: CGRect(x: 0, y: 0, width: 640, height: 480))
        let pip = SampleBufferPiPSource()

        #expect(pip.displayLayer.superlayer == nil)

        view.pipSource = pip
        #expect(pip.displayLayer.superlayer != nil)
        #expect(pip.displayLayer.frame == view.bounds)

        view.aspectMode = .fill
        #if os(iOS) || os(macOS)
        if let sbLayer = pip.displayLayer as? AVSampleBufferDisplayLayer {
            #expect(sbLayer.videoGravity == .resizeAspectFill)
        }
        #endif

        view.pipSource = nil
        #expect(pip.displayLayer.superlayer == nil)
    }

    #if os(macOS)
    @MainActor
    @Test("PiP display layer takes the panel's size while still covering the player")
    func pictureInPicturePanelSizesDisplayLayer() {
        let view = EnhancedVideoView(frame: CGRect(x: 0, y: 0, width: 960, height: 540))
        let pip = SampleBufferPiPSource()
        view.pipSource = pip

        // AVKit lays out the panel's video from these bounds.
        pip.setPanelContentSize(CGSize(width: 640, height: 360))
        #expect(pip.displayLayer.bounds.size == CGSize(width: 640, height: 360))
        #expect(pip.displayLayer.frame == view.bounds)

        view.aspectMode = .fill
        #expect(pip.displayLayer.bounds.size == CGSize(width: 640, height: 360))

        pip.setPanelContentSize(nil)
        #expect(CATransform3DIsIdentity(pip.displayLayer.transform))
        #expect(pip.displayLayer.frame == view.bounds)
        view.pipSource = nil
    }
    #endif
}
