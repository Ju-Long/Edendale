//
//  EnhancedVideoView.swift
//  Edendale
//
//  Core Metal rendering surface presenting video frames with zero-copy texture
//  intake, aspect ratio fitting/filling, and frame pacing.
//

import CoreGraphics
import CoreMedia
import CoreVideo
import Metal
import MetalKit

#if os(macOS)
import AppKit
public typealias PlatformView = NSView
#else
import UIKit
public typealias PlatformView = UIView
#endif

/// High-performance Metal rendering view for video presentation.
/// Conforms to `MTKViewDelegate` and displays frames pulled from a `FrameRingBuffer`,
/// directly supplied `CVPixelBuffer`s, or test patterns.
@MainActor
public class EnhancedVideoView: MTKView, MTKViewDelegate {
    public let metalContext: MetalContext
    public let textureCache: PixelBufferTextureCache

    /// The GPU enhancement pipeline (upscale, sharpen, denoise, color adjustments).
    var enhancementPipeline: EnhancementPipeline?

    /// The frame intake ring buffer populated by media decoders.
    public var ringBuffer: FrameRingBuffer?

    /// Aspect ratio presentation mode (.fit for letterbox/pillarbox, .fill for cropped full bleed).
    public var aspectMode: VideoAspectMode = .fit

    /// Target frame rate in Hz for frame presentation pacing. Default 60.0.
    public var targetFrameRate: Float = 60.0 {
        didSet {
            self.preferredFramesPerSecond = Int(targetFrameRate)
        }
    }

    /// Current media presentation time used to dequeue frames from the ring buffer.
    public var currentDisplayTime: CMTime = .zero

    /// Directly rendered texture (used if ringBuffer is nil or empty).
    public var currentTexture: MTLTexture?

    /// Directly supplied CVPixelBuffer to render.
    public var currentPixelBuffer: CVPixelBuffer?

    /// When true, renders a continuous 60fps procedural test pattern for testing without decoders.
    public var testPatternEnabled: Bool = false

    /// Callback invoked when a frame is presented.
    public var onFramePresented: ((CMTime) -> Void)?

    /// Fired once when the view has non-zero bounds and is ready for rendering.
    public var onReady: ((EnhancedVideoView) -> Void)?

    // Internal animation state for test pattern
    private var testPatternPhase: Double = 0.0
    private var cachedTestTexture: MTLTexture?
    private var hasReportedReady = false

    public init(
        frame: CGRect = .zero,
        metalContext: MetalContext = MetalContext.shared ?? MetalContext()!
    ) {
        self.metalContext = metalContext
        self.textureCache = PixelBufferTextureCache(device: metalContext.device)
        super.init(frame: frame, device: metalContext.device)
        configureView()
    }

    public required init(coder: NSCoder) {
        let context = MetalContext.shared ?? MetalContext()!
        self.metalContext = context
        self.textureCache = PixelBufferTextureCache(device: context.device)
        super.init(coder: coder)
        self.device = context.device
        configureView()
    }

    private func configureView() {
        self.colorPixelFormat = .bgra8Unorm
        self.clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        self.autoResizeDrawable = true
        self.isPaused = false
        self.enableSetNeedsDisplay = false
        self.preferredFramesPerSecond = Int(targetFrameRate)
        self.delegate = self

        #if os(macOS)
        self.wantsLayer = true
        self.layer?.isOpaque = true
        #else
        self.isOpaque = true
        #endif

        #if os(tvOS)
        UIApplication.shared.isIdleTimerDisabled = true
        #endif
    }

    // MARK: - Layout & Surface Ready

    #if os(macOS)
    public override func layout() {
        super.layout()
        reportReadyIfNeeded()
    }
    #else
    public override func layoutSubviews() {
        super.layoutSubviews()
        reportReadyIfNeeded()
    }

    public override func didMoveToWindow() {
        super.didMoveToWindow()
        #if !os(visionOS)
        if let window {
            contentScaleFactor = window.screen.scale
        }
        #endif
    }
    #endif

    private func reportReadyIfNeeded() {
        guard !hasReportedReady, bounds.width > 0, bounds.height > 0 else { return }
        hasReportedReady = true
        debugPrint("[EnhancedVideoView] ✅ surface ready — bounds=\(bounds.size)")
        onReady?(self)
    }

    #if os(tvOS)
    override public var canBecomeFocused: Bool {
        false
    }

    deinit {
        MainActor.assumeIsolated {
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }
    #endif

    // MARK: - Direct Input Methods

    /// Ingest and display a CVPixelBuffer immediately.
    public func render(pixelBuffer: CVPixelBuffer, presentationTime: CMTime = .zero) {
        self.currentPixelBuffer = pixelBuffer
        self.currentDisplayTime = presentationTime
    }

    /// Ingest and display a MTLTexture directly.
    public func render(texture: MTLTexture) {
        self.currentTexture = texture
    }

    /// Enable or disable the 60fps procedural test pattern.
    public func setTestPattern(enabled: Bool) {
        self.testPatternEnabled = enabled
    }

    // MARK: - MTKViewDelegate

    public func mtkView(_ view: MTKView, drawableSizeWillChange size: CGSize) {
        // Handled dynamically during draw
    }

    private var drawCallCount = 0
    public func draw(in view: MTKView) {
        guard bounds.width > 0, bounds.height > 0 else { return }

        drawCallCount += 1
        if drawCallCount <= 5 || drawCallCount % 300 == 0 {
            debugPrint("[EnhancedVideoView.draw] frame #\(drawCallCount) — ringBuffer=\(ringBuffer == nil ? "nil" : "exists(\(ringBuffer!.count) frames)"), pixelBuffer=\(currentPixelBuffer == nil ? "nil" : "exists"), texture=\(currentTexture == nil ? "nil" : "exists"), testPattern=\(testPatternEnabled)")
        }

        guard let drawable = currentDrawable,
              let renderPassDescriptor = currentRenderPassDescriptor,
              let commandBuffer = metalContext.commandQueue.makeCommandBuffer()
        else {
            if drawCallCount <= 5 {
                debugPrint("[EnhancedVideoView.draw] ❌ no drawable/renderPass/commandBuffer")
            }
            return
        }

        guard let videoTexture = resolveVideoTexture() else {
            guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPassDescriptor) else {
                return
            }
            encoder.endEncoding()
            commandBuffer.present(drawable)
            commandBuffer.commit()
            return
        }

        // Convert YCbCr to BGRA if needed (compute pass)
        var renderTexture: MTLTexture
        if videoTexture.isBiPlanarYCbCr {
            guard let converted = textureCache.convertToBGRA(videoTexture, commandBuffer: commandBuffer) else {
                return
            }
            renderTexture = converted
        } else {
            renderTexture = videoTexture.lumaTexture
        }

        // Enhancement pipeline: upscale, color adjustments, CAS sharpening, temporal denoise
        if let pipeline = enhancementPipeline {
            pipeline.displaySize = drawableSize
            renderTexture = pipeline.process(source: renderTexture, commandBuffer: commandBuffer)
        }

        // Render to screen
        let containerSize = drawableSize
        let textureSize = CGSize(width: renderTexture.width, height: renderTexture.height)
        let vertices = MetalContext.computeQuadVertices(
            containerSize: containerSize,
            contentSize: textureSize,
            aspectMode: aspectMode
        )

        guard let vertexBuffer = metalContext.device.makeBuffer(
            bytes: vertices,
            length: MemoryLayout<MetalQuadVertex>.stride * vertices.count,
            options: .storageModeShared
        ) else {
            return
        }

        guard let pipelineState = metalContext.renderPipelineState(for: colorPixelFormat) else {
            return
        }

        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPassDescriptor) else {
            return
        }

        encoder.setRenderPipelineState(pipelineState)
        encoder.setVertexBuffer(vertexBuffer, offset: 0, index: 0)
        encoder.setFragmentTexture(renderTexture, index: 0)
        encoder.setFragmentSamplerState(metalContext.samplerState, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: vertices.count)
        encoder.endEncoding()

        #if os(macOS)
        let frameInterval = 1.0 / Double(max(targetFrameRate, 1.0))
        commandBuffer.present(drawable, afterMinimumDuration: frameInterval)
        #else
        commandBuffer.present(drawable)
        #endif
        commandBuffer.commit()

        onFramePresented?(currentDisplayTime)
    }

    private func resolveVideoTexture() -> VideoTexture? {
        if testPatternEnabled {
            testPatternPhase += 0.03
            if let pb = PixelBufferTextureCache.createGradientTestPixelBuffer(
                width: 1920,
                height: 1080,
                phaseOffset: testPatternPhase
            ) {
                return textureCache.videoTexture(from: pb)
            }
            if let cached = cachedTestTexture {
                return VideoTexture(lumaTexture: cached, chromaTexture: nil, isVideoRange: false)
            }
            return nil
        }

        if let currentPixelBuffer {
            return textureCache.videoTexture(from: currentPixelBuffer)
        }

        if let ringBuffer {
            if let frame = ringBuffer.latestFrame(atOrBefore: currentDisplayTime) ?? ringBuffer.latestFrame() {
                return textureCache.videoTexture(from: frame.pixelBuffer)
            }
        }

        if let currentTexture {
            return VideoTexture(lumaTexture: currentTexture, chromaTexture: nil, isVideoRange: false)
        }

        return nil
    }
}
