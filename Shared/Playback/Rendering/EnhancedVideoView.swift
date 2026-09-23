//
//  EnhancedVideoView.swift
//  Edendale
//
//  Core Metal rendering surface presenting video frames with zero-copy texture
//  intake, aspect ratio fitting/filling, and frame pacing.
//

import AVFoundation
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

    /// External subtitle engine for compositing text/image subtitle overlays.
    var subtitleEngine: SubtitleEngine?
    var subtitleRevision: UInt = 0
    private var compositingTexture: MTLTexture?

    /// The frame intake ring buffer populated by media decoders.
    public var ringBuffer: FrameRingBuffer?

    /// Aspect ratio presentation mode (.fit for letterbox/pillarbox, .fill for cropped full bleed).
    public var aspectMode: VideoAspectMode = .fit {
        didSet {
            #if os(iOS) || os(macOS)
            updatePiPLayer()
            #endif
        }
    }

    /// External PiP sample buffer source to keep attached to the active window hierarchy.
    var pipSource: SampleBufferPiPSource? {
        didSet {
            guard oldValue !== pipSource else { return }
            oldValue?.displayLayer.removeFromSuperlayer()
            if let pipSource {
                #if os(macOS)
                wantsLayer = true
                layer?.insertSublayer(pipSource.displayLayer, at: 0)
                #else
                layer.insertSublayer(pipSource.displayLayer, at: 0)
                #endif
                updatePiPLayer()
            }
        }
    }

    private func updatePiPLayer() {
        guard let displayLayer = pipSource?.displayLayer else { return }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        displayLayer.frame = bounds
        #if os(iOS) || os(macOS)
        if let sbLayer = displayLayer as? AVSampleBufferDisplayLayer {
            sbLayer.videoGravity = (aspectMode == .fill) ? .resizeAspectFill : .resizeAspect
        }
        #endif
        CATransaction.commit()
    }

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

    /// Frame interpolator for motion-compensated frame generation.
    var frameInterpolator: FrameInterpolator?

    /// The source content framerate (e.g. 24, 30).  Used to compute the 2×
    /// display rate when frame interpolation is active.
    public var sourceFrameRate: Float = 0.0 {
        didSet {
            updateDisplayRate()
        }
    }

    /// Decides per refresh whether to show a synthetic frame, the real frame
    /// held behind it, or nothing new.
    private var interpolationSchedule = FrameInterpolationScheduler()

    /// Copy of the real frame shown on the refresh after its synthetic frame.
    /// The pipeline and texture cache reuse their outputs, so it must be a copy.
    private var heldFrameTexture: MTLTexture?
    private var heldFrameTime: CMTime = .invalid

    /// Callback invoked when a frame is presented.
    public var onFramePresented: ((CMTime) -> Void)?

    /// Fired once when the view has non-zero bounds and is ready for rendering.
    public var onReady: ((EnhancedVideoView) -> Void)?

    /// Whether playback is paused from the player/view perspective.
    public var isPlaybackPaused: Bool = false {
        didSet {
            updatePauseState()
        }
    }

    /// Whether the application is in the background or hidden.
    public private(set) var isAppBackgrounded: Bool = false {
        didSet {
            updatePauseState()
        }
    }

    private nonisolated(unsafe) var lifecycleObservers: [NSObjectProtocol] = []

    private func updatePauseState() {
        let shouldPause = isPlaybackPaused || isAppBackgrounded
        if self.isPaused != shouldPause {
            self.isPaused = shouldPause
        }
        if shouldPause {
            frameInterpolator?.reset()
            interpolationSchedule.reset()
            if !isAppBackgrounded && bounds.width > 0 && bounds.height > 0 {
                self.draw()
            }
        }
    }

    /// Motion smoothing is switched on and the source is slow enough to double.
    private var isFrameInterpolationEnabled: Bool {
        enhancementPipeline?.frameInterpolationEnabled == true
            && sourceFrameRate > 0
            && sourceFrameRate <= FrameInterpolator.maxSourceFrameRate
    }

    private func updateDisplayRate() {
        if isFrameInterpolationEnabled {
            let displayRate = sourceFrameRate * 2.0
            self.preferredFramesPerSecond = Int(displayRate)
        } else {
            self.preferredFramesPerSecond = Int(targetFrameRate)
            interpolationSchedule.reset()
        }
    }

    // Internal animation state for test pattern
    private var testPatternPhase: Double = 0.0
    private var cachedTestTexture: MTLTexture?
    private var hasReportedReady = false

    /// Consecutive draw calls where no video texture was available.
    /// Used to avoid GPU resource churn (drawable acquisition, command buffer
    /// allocation) when the ring buffer is empty after a seek or track switch.
    private var consecutiveEmptyDraws = 0

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

        setupLifecycleObservers()
    }

    private func setupLifecycleObservers() {
        #if os(macOS)
        let hideObs = NotificationCenter.default.addObserver(
            forName: NSApplication.didHideNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleAppBackground()
        }
        let unhideObs = NotificationCenter.default.addObserver(
            forName: NSApplication.didUnhideNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleAppForeground()
        }
        lifecycleObservers.append(contentsOf: [hideObs, unhideObs])
        #else
        let bgObs = NotificationCenter.default.addObserver(
            forName: UIApplication.didEnterBackgroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleAppBackground()
        }
        let fgObs = NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.handleAppForeground()
        }
        lifecycleObservers.append(contentsOf: [bgObs, fgObs])
        #endif
    }

    private func handleAppBackground() {
        isAppBackgrounded = true
    }

    private func handleAppForeground() {
        isAppBackgrounded = false
    }

    // MARK: - Layout & Surface Ready

    #if os(macOS)
    public override func layout() {
        super.layout()
        updatePiPLayer()
        reportReadyIfNeeded()
    }
    #else
    public override func layoutSubviews() {
        super.layoutSubviews()
        updatePiPLayer()
        reportReadyIfNeeded()
    }

    public override func didMoveToWindow() {
        super.didMoveToWindow()
        #if os(iOS)
        updatePiPLayer()
        #endif
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
    #endif

    deinit {
        #if os(iOS) || os(macOS)
        pipSource?.displayLayer.removeFromSuperlayer()
        #endif
        for obs in lifecycleObservers {
            NotificationCenter.default.removeObserver(obs)
        }
        lifecycleObservers.removeAll()
        #if os(tvOS)
        MainActor.assumeIsolated {
            UIApplication.shared.isIdleTimerDisabled = false
        }
        #endif
    }

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
        guard !isAppBackgrounded, bounds.width > 0, bounds.height > 0 else { return }

        drawCallCount += 1
        if drawCallCount <= 5 || drawCallCount % 300 == 0 {
            debugPrint("[EnhancedVideoView.draw] frame #\(drawCallCount) — ringBuffer=\(ringBuffer == nil ? "nil" : "exists(\(ringBuffer!.count) frames)"), pixelBuffer=\(currentPixelBuffer == nil ? "nil" : "exists"), texture=\(currentTexture == nil ? "nil" : "exists"), testPattern=\(testPatternEnabled), smoothing=\(isFrameInterpolationEnabled)")
        }

        // Motion smoothing applies to decoded playback frames only; scrubbing
        // while paused and the direct/test sources take the regular path.
        if isFrameInterpolationEnabled, !isPlaybackPaused, !testPatternEnabled,
           currentPixelBuffer == nil, let frameInterpolator,
           drawInterpolated(using: frameInterpolator) {
            return
        }
        interpolationSchedule.reset()

        // Resolve content BEFORE acquiring GPU resources.
        guard let videoTexture = resolveVideoTexture() else {
            consecutiveEmptyDraws += 1
            if consecutiveEmptyDraws <= 3 {
                guard let drawable = currentDrawable,
                      let renderPassDescriptor = currentRenderPassDescriptor,
                      let commandBuffer = metalContext.commandQueue.makeCommandBuffer(),
                      let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPassDescriptor)
                else { return }
                encoder.endEncoding()
                commandBuffer.present(drawable)
                commandBuffer.commit()
            }
            return
        }
        consecutiveEmptyDraws = 0

        guard let drawable = currentDrawable,
              let renderPassDescriptor = currentRenderPassDescriptor,
              let commandBuffer = metalContext.commandQueue.makeCommandBuffer()
        else {
            if drawCallCount <= 5 {
                debugPrint("[EnhancedVideoView.draw] ❌ no drawable/renderPass/commandBuffer")
            }
            return
        }

        guard let enhanced = enhancedTexture(for: videoTexture, commandBuffer: commandBuffer) else {
            return
        }
        let renderTexture = compositeSubtitles(onto: enhanced, commandBuffer: commandBuffer)

        presentTexture(renderTexture, drawable: drawable, renderPassDescriptor: renderPassDescriptor, commandBuffer: commandBuffer)
        commandBuffer.commit()

        onFramePresented?(currentDisplayTime)
    }

    /// Motion-smoothing draw. When frame N arrives, shows the frame between
    /// N-1 and N and holds N back for the next refresh (see
    /// `FrameInterpolationScheduler`). Returns false when no decoded frame is
    /// available, so the regular path can handle the empty buffer.
    private func drawInterpolated(using interpolator: FrameInterpolator) -> Bool {
        guard let ringBuffer,
              let frame = ringBuffer.latestFrame(atOrBefore: currentDisplayTime) ?? ringBuffer.latestFrame(),
              frame.presentationTime.isNumeric
        else {
            interpolationSchedule.reset()
            return false
        }

        let action = interpolationSchedule.nextAction(
            for: frame.presentationTime,
            frameDuration: 1.0 / Double(sourceFrameRate)
        )
        guard action != .keepCurrent else { return true }

        guard let drawable = currentDrawable,
              let renderPassDescriptor = currentRenderPassDescriptor,
              let commandBuffer = metalContext.commandQueue.makeCommandBuffer()
        else {
            // Start again from this frame, without a synthetic one, next refresh.
            interpolationSchedule.reset()
            return true
        }

        let texture: MTLTexture
        var realFrameTime: CMTime?
        switch action {
        case .keepCurrent:
            return true
        case .showHeldFrame:
            guard let heldFrameTexture else { return true }
            texture = heldFrameTexture
            realFrameTime = heldFrameTime
        case .showNewFrame(let synthesize):
            currentDisplayTime = frame.presentationTime
            guard let videoTexture = textureCache.videoTexture(from: frame.pixelBuffer),
                  let enhanced = enhancedTexture(for: videoTexture, commandBuffer: commandBuffer)
            else { return true }

            // Interpolate before committing, while the history still holds N-1.
            let synthetic = synthesize
                ? interpolator.interpolate(current: enhanced, commandBuffer: commandBuffer)
                : nil
            interpolator.commitFrame(enhanced, commandBuffer: commandBuffer)

            if let synthetic, holdFrame(enhanced, time: frame.presentationTime, commandBuffer: commandBuffer) {
                interpolationSchedule.didHoldFrame()
                texture = synthetic
            } else {
                texture = enhanced
                realFrameTime = frame.presentationTime
            }
        }

        let renderTexture = compositeSubtitles(onto: texture, commandBuffer: commandBuffer)
        presentTexture(renderTexture, drawable: drawable, renderPassDescriptor: renderPassDescriptor, commandBuffer: commandBuffer)
        commandBuffer.commit()

        if let realFrameTime {
            onFramePresented?(realFrameTime)
        }
        return true
    }

    /// Converts the frame to BGRA if needed, then runs the enhancement pipeline
    /// (upscale, color adjustments, CAS sharpening, temporal denoise).
    private func enhancedTexture(for videoTexture: VideoTexture, commandBuffer: MTLCommandBuffer) -> MTLTexture? {
        var texture: MTLTexture
        if videoTexture.isBiPlanarYCbCr {
            guard let converted = textureCache.convertToBGRA(videoTexture, commandBuffer: commandBuffer) else {
                return nil
            }
            texture = converted
        } else {
            texture = videoTexture.lumaTexture
        }

        if let pipeline = enhancementPipeline {
            pipeline.displaySize = drawableSize
            texture = pipeline.process(source: texture, commandBuffer: commandBuffer)
        }
        return texture
    }

    /// Composites the active subtitle overlay, if any, onto `texture`.
    private func compositeSubtitles(onto texture: MTLTexture, commandBuffer: MTLCommandBuffer) -> MTLTexture {
        guard let subtitleEngine, subtitleEngine.isEnabled, subtitleEngine.activeFormat != nil else {
            return texture
        }
        subtitleEngine.setCanvasSize(CGSize(width: texture.width, height: texture.height))
        guard let subTexture = subtitleEngine.renderSubtitleTexture(at: currentDisplayTime),
              let outputTexture = ensureCompositingTexture(width: texture.width, height: texture.height)
        else { return texture }

        subtitleEngine.compositor.composite(
            video: texture,
            subtitle: subTexture,
            output: outputTexture,
            commandBuffer: commandBuffer
        )
        return outputTexture
    }

    /// Copies `texture` into `heldFrameTexture` for the next refresh.
    /// Returns false if the copy could not be encoded.
    private func holdFrame(_ texture: MTLTexture, time: CMTime, commandBuffer: MTLCommandBuffer) -> Bool {
        if heldFrameTexture?.width != texture.width
            || heldFrameTexture?.height != texture.height
            || heldFrameTexture?.pixelFormat != texture.pixelFormat {
            let desc = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: texture.pixelFormat,
                width: texture.width,
                height: texture.height,
                mipmapped: false
            )
            desc.usage = [.shaderRead]
            desc.storageMode = .private
            heldFrameTexture = metalContext.device.makeTexture(descriptor: desc)
        }
        guard let heldFrameTexture, let blit = commandBuffer.makeBlitCommandEncoder() else {
            return false
        }
        blit.label = "Hold Real Frame"
        blit.copy(from: texture, to: heldFrameTexture)
        blit.endEncoding()
        heldFrameTime = time
        return true
    }

    /// Shared final render pass — blit the texture onto the drawable with
    /// aspect-ratio geometry and frame pacing.
    private func presentTexture(
        _ texture: MTLTexture,
        drawable: CAMetalDrawable,
        renderPassDescriptor: MTLRenderPassDescriptor,
        commandBuffer: MTLCommandBuffer
    ) {
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
        ) else { return }

        guard let pipelineState = metalContext.renderPipelineState(for: colorPixelFormat) else { return }
        guard let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: renderPassDescriptor) else { return }

        encoder.setRenderPipelineState(pipelineState)
        encoder.setVertexBuffer(vertexBuffer, offset: 0, index: 0)
        encoder.setFragmentTexture(texture, index: 0)
        encoder.setFragmentSamplerState(metalContext.samplerState, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: vertices.count)
        encoder.endEncoding()

        let effectiveRate: Double
        if isFrameInterpolationEnabled {
            effectiveRate = Double(sourceFrameRate * 2.0)
        } else {
            effectiveRate = Double(max(targetFrameRate, 1.0))
        }

        #if os(macOS)
        commandBuffer.present(drawable, afterMinimumDuration: 1.0 / effectiveRate)
        #else
        commandBuffer.present(drawable)
        #endif
    }

    private func ensureCompositingTexture(width: Int, height: Int) -> MTLTexture? {
        if let existing = compositingTexture,
           existing.width == width, existing.height == height {
            return existing
        }
        let desc = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm,
            width: width,
            height: height,
            mipmapped: false
        )
        desc.usage = [.shaderRead, .shaderWrite]
        let tex = metalContext.device.makeTexture(descriptor: desc)
        compositingTexture = tex
        return tex
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
                if frame.presentationTime.isValid, frame.presentationTime.isNumeric {
                    self.currentDisplayTime = frame.presentationTime
                }
                return textureCache.videoTexture(from: frame.pixelBuffer)
            }
        }

        if let currentTexture {
            return VideoTexture(lumaTexture: currentTexture, chromaTexture: nil, isVideoRange: false)
        }

        return nil
    }
}
