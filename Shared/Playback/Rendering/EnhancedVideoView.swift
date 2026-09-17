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

    // Internal animation state for test pattern
    private var testPatternPhase: Double = 0.0
    private var cachedTestTexture: MTLTexture?

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
        self.contentScaleFactor = 2.0
        #endif

        #if os(tvOS)
        // Disable screen saver / idle dimming while video view is active
        UIApplication.shared.isIdleTimerDisabled = true
        #endif
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

    public func draw(in view: MTKView) {
        guard let drawable = currentDrawable,
              let renderPassDescriptor = currentRenderPassDescriptor,
              let commandBuffer = metalContext.commandQueue.makeCommandBuffer()
        else {
            return
        }

        guard let texture = resolveTextureToRender() else {
            // Nothing to render; clear the surface to black
            guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPassDescriptor) else {
                return
            }
            encoder.endEncoding()
            commandBuffer.present(drawable)
            commandBuffer.commit()
            return
        }

        let containerSize = drawableSize
        let textureSize = CGSize(width: texture.width, height: texture.height)
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
        encoder.setFragmentTexture(texture, index: 0)
        encoder.setFragmentSamplerState(metalContext.samplerState, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: vertices.count)
        encoder.endEncoding()

        // Paced presentation
        #if os(macOS)
        let frameInterval = 1.0 / Double(max(targetFrameRate, 1.0))
        commandBuffer.present(drawable, afterMinimumDuration: frameInterval)
        #else
        commandBuffer.present(drawable)
        #endif
        commandBuffer.commit()

        onFramePresented?(currentDisplayTime)
    }

    private func resolveTextureToRender() -> MTLTexture? {
        if testPatternEnabled {
            testPatternPhase += 0.03
            if let pb = PixelBufferTextureCache.createGradientTestPixelBuffer(
                width: 1920,
                height: 1080,
                phaseOffset: testPatternPhase
            ) {
                return textureCache.texture(from: pb)
            }
            return cachedTestTexture
        }

        // 1. Check direct pixel buffer
        if let currentPixelBuffer {
            return textureCache.texture(from: currentPixelBuffer)
        }

        // 2. Check frame ring buffer from decoder
        if let ringBuffer {
            if let frame = ringBuffer.latestFrame(atOrBefore: currentDisplayTime) ?? ringBuffer.latestFrame() {
                return textureCache.texture(from: frame.pixelBuffer)
            }
        }

        // 3. Fallback to direct MTLTexture
        return currentTexture
    }
}
