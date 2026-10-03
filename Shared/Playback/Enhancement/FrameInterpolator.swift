import Foundation
import Metal

/// GPU-accelerated frame interpolation using motion-compensated bidirectional
/// warping.  Synthesises an intermediate frame between two consecutive enhanced
/// video frames without requiring game-engine motion vectors or depth buffers.
///
/// Typical usage in the draw loop, once a new frame has been enhanced:
/// ```
/// if let synthetic = interpolator.interpolate(current: enhanced, commandBuffer: cb) {
///     present(synthetic)            // display interpolated frame first
/// }
/// interpolator.commitFrame(enhanced, commandBuffer: cb)
/// present(enhanced)                 // then the real frame, on the next refresh
/// ```
final class FrameInterpolator: @unchecked Sendable {

    enum InterpolationBackend: Sendable {
        case custom
        case metalFX
    }

    /// Faster sources are shown as-is; doubling them exceeds common refresh rates.
    static let maxSourceFrameRate: Float = 30

    let device: MTLDevice

    /// Which backend to use for the final warp/blend pass.
    /// `.metalFX` requires macOS 26+ / iOS 26+ and a supported GPU.
    var backend: InterpolationBackend = .custom {
        didSet { configureMetalFXBackend() }
    }

    // MARK: - Pipeline States

    private var coarseMEState: MTLComputePipelineState?
    private var refineMEState: MTLComputePipelineState?
    private var densifyState: MTLComputePipelineState?
    private var interpolateState: MTLComputePipelineState?
    private var sceneCutHoldState: MTLComputePipelineState?
    private var downscaleState: MTLComputePipelineState?
    private var mvUpscaleState: MTLComputePipelineState?

    private var metalFXBackend: AnyObject?
    private var metalFXResetNeeded: Bool = true

    // MARK: - Frame History

    private var previousFrame: MTLTexture?
    private var hasValidPrevious: Bool = false

    // MARK: - Cached Intermediate Textures

    private var coarseMotionTexture: MTLTexture?
    private var refinedMotionTexture: MTLTexture?
    private var pixelMotionTexture: MTLTexture?
    private var interpolatedFrame: MTLTexture?

    // Half-res ME textures (allocated only for 4K+ sources).
    private var halfPrevTexture: MTLTexture?
    private var halfCurrTexture: MTLTexture?
    private var halfPixelMVTexture: MTLTexture?

    // Coarse blocks without a good match, counted on the GPU for scene-cut
    // detection.  Cleared and read inside each interpolation's command buffer,
    // so the CPU never waits on it.
    private var unmatchedBlockCounter: MTLBuffer?

    private let lock = NSLock()

    /// Coarse block size for motion estimation (pixels).
    let coarseBlockSize: UInt32 = 16
    /// Refinement sub-block size (pixels).
    let refineBlockSize: UInt32 = 4
    /// Coarse search radius (pixels).
    let coarseSearchRadius: UInt32 = 16

    /// A 16×16 block is unmatched when even its best motion-compensated match
    /// differs by more than this mean luma difference (0–1).  Pans, zooms,
    /// grain and fades up to ~8% per frame stay below it.
    var sceneCutBlockError: Float = 0.06

    /// When at least this fraction of blocks is unmatched, the pair is treated
    /// as a scene cut and the previous frame is shown instead of a blend.
    /// Kept low because flat areas (letterbox bars, dark scenes) always match.
    /// Detailed content moving beyond the search radius can also trip it.
    var sceneCutBlockFraction: Float = 0.3

    /// Sources wider than this run motion estimation at half resolution.
    var halfResMEThreshold: Int = 1920

    // MARK: - Performance Stats

    struct PerformanceStats: Sendable {
        var lastFrameMs: Double = 0
        var averageMs: Double = 0
        var frameCount: Int = 0
        var isHalfRes: Bool = false
    }

    private var _stats = PerformanceStats()
    private let statsLock = NSLock()
    private static let statsWindow = 60

    var stats: PerformanceStats {
        statsLock.lock()
        defer { statsLock.unlock() }
        return _stats
    }

    init?(device: MTLDevice, library: MTLLibrary? = nil) {
        self.device = device
        let lib = library ?? MetalShaderSource.library(for: device)
        guard let lib else { return nil }

        guard let coarseFn = lib.makeFunction(name: "motionEstimationCoarse"),
              let refineFn = lib.makeFunction(name: "motionEstimationRefine"),
              let densifyFn = lib.makeFunction(name: "motionVectorDensify"),
              let interpFn = lib.makeFunction(name: "frameInterpolate"),
              let holdFn = lib.makeFunction(name: "holdPreviousOnSceneCut")
        else { return nil }

        do {
            coarseMEState = try device.makeComputePipelineState(function: coarseFn)
            refineMEState = try device.makeComputePipelineState(function: refineFn)
            densifyState = try device.makeComputePipelineState(function: densifyFn)
            interpolateState = try device.makeComputePipelineState(function: interpFn)
            sceneCutHoldState = try device.makeComputePipelineState(function: holdFn)

            if let dsFn = lib.makeFunction(name: "bilinearDownscale") {
                downscaleState = try device.makeComputePipelineState(function: dsFn)
            }
            if let upFn = lib.makeFunction(name: "motionVectorUpscale") {
                mvUpscaleState = try device.makeComputePipelineState(function: upFn)
            }
        } catch {
            return nil
        }

        guard let counter = device.makeBuffer(length: MemoryLayout<UInt32>.size, options: .storageModePrivate) else {
            return nil
        }
        unmatchedBlockCounter = counter
    }

    /// Generate an interpolated frame between the stored previous frame and
    /// `current`.  Returns `nil` on the first frame (no history) or after a
    /// `reset()`.  On a scene cut the result is a copy of the previous frame,
    /// decided on the GPU (see `sceneCutBlockFraction`).
    ///
    /// The returned texture is valid until the next call to `interpolate` or
    /// `reset`.  The caller should present this *before* the real `current`
    /// frame.
    func interpolate(
        current: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) -> MTLTexture? {
        lock.lock()
        defer { lock.unlock() }

        let startTime = CFAbsoluteTimeGetCurrent()

        guard hasValidPrevious, let prev = previousFrame else { return nil }
        guard prev.width == current.width, prev.height == current.height else {
            resetInternal()
            return nil
        }

        let w = current.width
        let h = current.height

        guard let unmatchedBlocks = unmatchedBlockCounter,
              let clear = commandBuffer.makeBlitCommandEncoder()
        else { return nil }
        clear.label = "Frame Interpolator — Clear Scene-Cut Counter"
        clear.fill(buffer: unmatchedBlocks, range: 0..<unmatchedBlocks.length, value: 0)
        clear.endEncoding()

        let useHalfRes = w > halfResMEThreshold && downscaleState != nil && mvUpscaleState != nil
        let meW: Int, meH: Int
        let mePrev: MTLTexture, meCurr: MTLTexture

        if useHalfRes {
            meW = w / 2
            meH = h / 2
            let halfPrev = getOrCreateTexture(ref: &halfPrevTexture, width: meW, height: meH, format: .bgra8Unorm)
            let halfCurr = getOrCreateTexture(ref: &halfCurrTexture, width: meW, height: meH, format: .bgra8Unorm)
            encodeDownscale(source: prev, output: halfPrev, commandBuffer: commandBuffer)
            encodeDownscale(source: current, output: halfCurr, commandBuffer: commandBuffer)
            mePrev = halfPrev
            meCurr = halfCurr
        } else {
            meW = w
            meH = h
            mePrev = prev
            meCurr = current
        }

        // Pass 1: Coarse motion estimation.
        let coarseMVW = (meW + Int(coarseBlockSize) - 1) / Int(coarseBlockSize)
        let coarseMVH = (meH + Int(coarseBlockSize) - 1) / Int(coarseBlockSize)
        let coarseMV = getOrCreateTexture(
            ref: &coarseMotionTexture,
            width: coarseMVW, height: coarseMVH,
            format: .rg16Float
        )
        encodeCoarseME(
            prev: mePrev, curr: meCurr, output: coarseMV,
            unmatchedBlocks: unmatchedBlocks,
            commandBuffer: commandBuffer
        )

        // Pass 2: Refined motion estimation at sub-block level.
        let refineMVW = (meW + Int(refineBlockSize) - 1) / Int(refineBlockSize)
        let refineMVH = (meH + Int(refineBlockSize) - 1) / Int(refineBlockSize)
        let refinedMV = getOrCreateTexture(
            ref: &refinedMotionTexture,
            width: refineMVW, height: refineMVH,
            format: .rg16Float
        )
        encodeRefineME(
            prev: mePrev, curr: meCurr,
            coarse: coarseMV, output: refinedMV,
            commandBuffer: commandBuffer
        )

        // Pass 3: Densify to per-pixel motion vectors (at ME resolution).
        let halfMV: MTLTexture
        if useHalfRes {
            halfMV = getOrCreateTexture(ref: &halfPixelMVTexture, width: meW, height: meH, format: .rg16Float)
        } else {
            halfMV = getOrCreateTexture(ref: &pixelMotionTexture, width: w, height: h, format: .rg16Float)
        }
        encodeDensify(blockMV: refinedMV, output: halfMV, commandBuffer: commandBuffer)

        // Pass 3b: Upscale MVs to full resolution when using half-res ME.
        let pixelMV: MTLTexture
        if useHalfRes {
            let fullMV = getOrCreateTexture(ref: &pixelMotionTexture, width: w, height: h, format: .rg16Float)
            encodeMVUpscale(halfMV: halfMV, fullMV: fullMV, commandBuffer: commandBuffer)
            pixelMV = fullMV
        } else {
            pixelMV = halfMV
        }

        // Pass 4: Warp + blend (always at full resolution).
        let output: MTLTexture
        if let mfxResult = encodeMetalFXInterpolation(
            prev: prev, curr: current, motion: pixelMV,
            commandBuffer: commandBuffer
        ) {
            output = mfxResult
        } else {
            let dest = getOrCreateTexture(
                ref: &interpolatedFrame,
                width: w, height: h,
                format: current.pixelFormat
            )
            encodeInterpolation(
                prev: prev, curr: current,
                motion: pixelMV, output: dest,
                commandBuffer: commandBuffer
            )
            output = dest
        }

        // Pass 5: On a scene cut, replace the blend with the previous frame.
        let cutBlockCount = UInt32(max(1, (sceneCutBlockFraction * Float(coarseMVW * coarseMVH)).rounded(.up)))
        encodeSceneCutHold(
            prev: prev, output: output,
            unmatchedBlocks: unmatchedBlocks, cutBlockCount: cutBlockCount,
            commandBuffer: commandBuffer
        )

        recordTiming(start: startTime, halfRes: useHalfRes, commandBuffer: commandBuffer)

        return output
    }

    /// Store `frame` as the previous frame for the next interpolation.
    /// Call once for every real frame, after `interpolate(current:)` for that
    /// same frame; committing first would interpolate the frame with itself.
    func commitFrame(_ frame: MTLTexture, commandBuffer: MTLCommandBuffer) {
        lock.lock()
        defer { lock.unlock() }

        let dest = getOrCreateTexture(
            ref: &previousFrame,
            width: frame.width, height: frame.height,
            format: frame.pixelFormat
        )

        if let blit = commandBuffer.makeBlitCommandEncoder() {
            blit.label = "Frame Interpolator — Commit History"
            blit.copy(from: frame, to: dest)
            blit.endEncoding()
        }
        hasValidPrevious = true
    }

    /// Discard history.  Call on seek, track switch, media change, or pause→resume.
    func reset() {
        lock.lock()
        defer { lock.unlock() }
        resetInternal()
    }

    private func resetInternal() {
        hasValidPrevious = false
        metalFXResetNeeded = true
    }

    private func configureMetalFXBackend() {
        lock.lock()
        defer { lock.unlock() }
        if backend == .metalFX {
            if metalFXBackend == nil {
                // Matches the platform guard in MetalFXInterpolatorBackend.swift.
                #if canImport(MetalFX) && (os(macOS) || os(iOS))
                if #available(macOS 26.0, iOS 26.0, *) {
                    metalFXBackend = MetalFXInterpolatorBackend(device: device)
                }
                #endif
            }
        } else {
            metalFXBackend = nil
        }
        metalFXResetNeeded = true
    }

    var isMetalFXAvailable: Bool {
        #if canImport(MetalFX) && (os(macOS) || os(iOS))
        if #available(macOS 26.0, iOS 26.0, *) {
            return MetalFXInterpolatorBackend.isSupported(device: device)
        }
        #endif
        return false
    }

    // MARK: - Encode Passes

    private func encodeCoarseME(
        prev: MTLTexture, curr: MTLTexture,
        output: MTLTexture,
        unmatchedBlocks: MTLBuffer,
        commandBuffer: MTLCommandBuffer
    ) {
        guard let state = coarseMEState,
              let encoder = commandBuffer.makeComputeCommandEncoder() else { return }
        encoder.label = "Coarse Motion Estimation"
        encoder.setComputePipelineState(state)
        encoder.setTexture(prev, index: 0)
        encoder.setTexture(curr, index: 1)
        encoder.setTexture(output, index: 2)

        var bs = coarseBlockSize
        var sr = coarseSearchRadius
        var unmatchedError = sceneCutBlockError
        encoder.setBytes(&bs, length: MemoryLayout<UInt32>.size, index: 0)
        encoder.setBytes(&sr, length: MemoryLayout<UInt32>.size, index: 1)
        encoder.setBuffer(unmatchedBlocks, offset: 0, index: 2)
        encoder.setBytes(&unmatchedError, length: MemoryLayout<Float>.size, index: 3)

        dispatch2D(encoder: encoder, width: output.width, height: output.height)
        encoder.endEncoding()
    }

    private func encodeRefineME(
        prev: MTLTexture, curr: MTLTexture,
        coarse: MTLTexture, output: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) {
        guard let state = refineMEState,
              let encoder = commandBuffer.makeComputeCommandEncoder() else { return }
        encoder.label = "Refined Motion Estimation"
        encoder.setComputePipelineState(state)
        encoder.setTexture(prev, index: 0)
        encoder.setTexture(curr, index: 1)
        encoder.setTexture(coarse, index: 2)
        encoder.setTexture(output, index: 3)

        var bs = refineBlockSize
        var cb = coarseBlockSize
        encoder.setBytes(&bs, length: MemoryLayout<UInt32>.size, index: 0)
        encoder.setBytes(&cb, length: MemoryLayout<UInt32>.size, index: 1)

        dispatch2D(encoder: encoder, width: output.width, height: output.height)
        encoder.endEncoding()
    }

    private func encodeDensify(
        blockMV: MTLTexture, output: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) {
        guard let state = densifyState,
              let encoder = commandBuffer.makeComputeCommandEncoder() else { return }
        encoder.label = "Motion Vector Densify"
        encoder.setComputePipelineState(state)
        encoder.setTexture(blockMV, index: 0)
        encoder.setTexture(output, index: 1)

        var bs = refineBlockSize
        encoder.setBytes(&bs, length: MemoryLayout<UInt32>.size, index: 0)

        dispatch2D(encoder: encoder, width: output.width, height: output.height)
        encoder.endEncoding()
    }

    private func encodeInterpolation(
        prev: MTLTexture, curr: MTLTexture,
        motion: MTLTexture, output: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) {
        guard let state = interpolateState,
              let encoder = commandBuffer.makeComputeCommandEncoder() else { return }
        encoder.label = "Frame Interpolation"
        encoder.setComputePipelineState(state)
        encoder.setTexture(prev, index: 0)
        encoder.setTexture(curr, index: 1)
        encoder.setTexture(motion, index: 2)
        encoder.setTexture(output, index: 3)

        var t: Float = 0.5
        encoder.setBytes(&t, length: MemoryLayout<Float>.size, index: 0)

        dispatch2D(encoder: encoder, width: output.width, height: output.height)
        encoder.endEncoding()
    }

    // MARK: - MetalFX Backend

    /// Returns the MetalFX-interpolated output if the backend is `.metalFX` and available, else `nil`.
    private func encodeMetalFXInterpolation(
        prev: MTLTexture, curr: MTLTexture,
        motion: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) -> MTLTexture? {
        guard backend == .metalFX else { return nil }

        #if canImport(MetalFX) && (os(macOS) || os(iOS))
        if #available(macOS 26.0, iOS 26.0, *) {
            guard let mfx = metalFXBackend as? MetalFXInterpolatorBackend else { return nil }
            let needsReset = metalFXResetNeeded
            metalFXResetNeeded = false
            return mfx.interpolate(
                prev: prev, current: curr, motion: motion,
                frameRate: 24.0,
                resetHistory: needsReset,
                commandBuffer: commandBuffer
            )
        }
        #endif
        return nil
    }

    // MARK: - Scene-Cut Handling

    /// Overwrites `output` with `prev` when the coarse pass counted at least
    /// `cutBlockCount` unmatched blocks.  The decision stays on the GPU, in
    /// order with the passes that produced the count, so it applies to this
    /// frame pair without a CPU readback.
    private func encodeSceneCutHold(
        prev: MTLTexture, output: MTLTexture,
        unmatchedBlocks: MTLBuffer, cutBlockCount: UInt32,
        commandBuffer: MTLCommandBuffer
    ) {
        guard let state = sceneCutHoldState,
              let encoder = commandBuffer.makeComputeCommandEncoder() else { return }
        encoder.label = "Scene Cut Hold"
        encoder.setComputePipelineState(state)
        encoder.setTexture(prev, index: 0)
        encoder.setTexture(output, index: 1)
        encoder.setBuffer(unmatchedBlocks, offset: 0, index: 0)

        var count = cutBlockCount
        encoder.setBytes(&count, length: MemoryLayout<UInt32>.size, index: 1)

        dispatch2D(encoder: encoder, width: output.width, height: output.height)
        encoder.endEncoding()
    }

    // MARK: - Half-res ME Encode Passes

    private func encodeDownscale(
        source: MTLTexture, output: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) {
        guard let state = downscaleState,
              let encoder = commandBuffer.makeComputeCommandEncoder() else { return }
        encoder.label = "Bilinear Downscale"
        encoder.setComputePipelineState(state)
        encoder.setTexture(source, index: 0)
        encoder.setTexture(output, index: 1)
        dispatch2D(encoder: encoder, width: output.width, height: output.height)
        encoder.endEncoding()
    }

    private func encodeMVUpscale(
        halfMV: MTLTexture, fullMV: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) {
        guard let state = mvUpscaleState,
              let encoder = commandBuffer.makeComputeCommandEncoder() else { return }
        encoder.label = "Motion Vector Upscale"
        encoder.setComputePipelineState(state)
        encoder.setTexture(halfMV, index: 0)
        encoder.setTexture(fullMV, index: 1)
        dispatch2D(encoder: encoder, width: fullMV.width, height: fullMV.height)
        encoder.endEncoding()
    }

    // MARK: - Performance Timing

    private func recordTiming(start: CFAbsoluteTime, halfRes: Bool, commandBuffer: MTLCommandBuffer) {
        commandBuffer.addCompletedHandler { [weak self] cb in
            guard let self else { return }
            let gpuMs = (cb.gpuEndTime - cb.gpuStartTime) * 1000.0
            let wallMs = (CFAbsoluteTimeGetCurrent() - start) * 1000.0
            let ms = gpuMs > 0 ? gpuMs : wallMs

            self.statsLock.lock()
            let n = self._stats.frameCount
            let alpha = n < Self.statsWindow ? 1.0 / Double(n + 1) : 2.0 / Double(Self.statsWindow + 1)
            self._stats.averageMs = self._stats.averageMs * (1.0 - alpha) + ms * alpha
            self._stats.lastFrameMs = ms
            self._stats.frameCount = n + 1
            self._stats.isHalfRes = halfRes
            self.statsLock.unlock()
        }
    }

    // MARK: - Helpers

    private func dispatch2D(encoder: MTLComputeCommandEncoder, width: Int, height: Int) {
        let tg = MTLSize(width: 16, height: 16, depth: 1)
        let groups = MTLSize(
            width: (width + 15) / 16,
            height: (height + 15) / 16,
            depth: 1
        )
        encoder.dispatchThreadgroups(groups, threadsPerThreadgroup: tg)
    }

    private func getOrCreateTexture(
        ref: inout MTLTexture?,
        width: Int, height: Int,
        format: MTLPixelFormat,
        usage: MTLTextureUsage = [.shaderRead, .shaderWrite]
    ) -> MTLTexture {
        if let existing = ref,
           existing.width == width,
           existing.height == height,
           existing.pixelFormat == format,
           existing.usage.contains(usage) {
            return existing
        }

        let desc = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: format,
            width: width, height: height,
            mipmapped: false
        )
        desc.usage = usage
        desc.storageMode = .private
        let tex = device.makeTexture(descriptor: desc)!
        ref = tex
        return tex
    }
}
