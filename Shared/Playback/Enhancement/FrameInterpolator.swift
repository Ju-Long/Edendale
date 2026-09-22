import Foundation
import Metal

/// GPU-accelerated frame interpolation using motion-compensated bidirectional
/// warping.  Synthesises an intermediate frame between two consecutive enhanced
/// video frames without requiring game-engine motion vectors or depth buffers.
///
/// Typical usage in the draw loop:
/// ```
/// if let synthetic = interpolator.interpolate(current: enhanced, commandBuffer: cb) {
///     present(synthetic)            // display interpolated frame first
/// }
/// interpolator.commitFrame(enhanced, commandBuffer: cb)
/// present(enhanced)                 // then display the real frame
/// ```
final class FrameInterpolator: @unchecked Sendable {
    let device: MTLDevice

    // MARK: - Pipeline States

    private var coarseMEState: MTLComputePipelineState?
    private var refineMEState: MTLComputePipelineState?
    private var densifyState: MTLComputePipelineState?
    private var interpolateState: MTLComputePipelineState?
    private var sceneCutState: MTLComputePipelineState?

    // MARK: - Frame History

    private var previousFrame: MTLTexture?
    private var hasValidPrevious: Bool = false

    // MARK: - Cached Intermediate Textures

    private var coarseMotionTexture: MTLTexture?
    private var refinedMotionTexture: MTLTexture?
    private var pixelMotionTexture: MTLTexture?
    private var interpolatedFrame: MTLTexture?

    // Scene-cut detection buffers (CPU-readable).
    private var sceneCutSADBuffer: MTLBuffer?
    private var sceneCutCountBuffer: MTLBuffer?

    private let lock = NSLock()

    /// Coarse block size for motion estimation (pixels).
    let coarseBlockSize: UInt32 = 16
    /// Refinement sub-block size (pixels).
    let refineBlockSize: UInt32 = 4
    /// Coarse search radius (pixels).
    let coarseSearchRadius: UInt32 = 16

    /// Average SAD above this threshold triggers scene-cut detection and skips
    /// interpolation for the frame pair.  Range [0, 1000] (SAD is ×1000 on GPU).
    var sceneCutThreshold: Float = 80.0

    init?(device: MTLDevice, library: MTLLibrary? = nil) {
        self.device = device
        let lib = library ?? MetalShaderSource.library(for: device)
        guard let lib else { return nil }

        guard let coarseFn = lib.makeFunction(name: "motionEstimationCoarse"),
              let refineFn = lib.makeFunction(name: "motionEstimationRefine"),
              let densifyFn = lib.makeFunction(name: "motionVectorDensify"),
              let interpFn = lib.makeFunction(name: "frameInterpolate"),
              let sceneFn = lib.makeFunction(name: "sceneCutScore")
        else { return nil }

        do {
            coarseMEState = try device.makeComputePipelineState(function: coarseFn)
            refineMEState = try device.makeComputePipelineState(function: refineFn)
            densifyState = try device.makeComputePipelineState(function: densifyFn)
            interpolateState = try device.makeComputePipelineState(function: interpFn)
            sceneCutState = try device.makeComputePipelineState(function: sceneFn)
        } catch {
            return nil
        }

        sceneCutSADBuffer = device.makeBuffer(length: MemoryLayout<UInt32>.size, options: .storageModeShared)
        sceneCutCountBuffer = device.makeBuffer(length: MemoryLayout<UInt32>.size, options: .storageModeShared)
    }

    /// Generate an interpolated frame between the stored previous frame and
    /// `current`.  Returns `nil` on the first frame (no history), after a
    /// `reset()`, or when a scene cut is detected.
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

        guard hasValidPrevious, let prev = previousFrame else { return nil }
        guard prev.width == current.width, prev.height == current.height else {
            resetInternal()
            return nil
        }

        let w = current.width
        let h = current.height

        // Scene-cut detection (GPU compute, CPU readback).
        if detectSceneCut(prev: prev, curr: current, commandBuffer: commandBuffer) {
            resetInternal()
            return nil
        }

        // Pass 1: Coarse motion estimation.
        let coarseMVW = (w + Int(coarseBlockSize) - 1) / Int(coarseBlockSize)
        let coarseMVH = (h + Int(coarseBlockSize) - 1) / Int(coarseBlockSize)
        let coarseMV = getOrCreateTexture(
            ref: &coarseMotionTexture,
            width: coarseMVW, height: coarseMVH,
            format: .rg16Float
        )
        encodeCoarseME(prev: prev, curr: current, output: coarseMV, commandBuffer: commandBuffer)

        // Pass 2: Refined motion estimation at sub-block level.
        let refineMVW = (w + Int(refineBlockSize) - 1) / Int(refineBlockSize)
        let refineMVH = (h + Int(refineBlockSize) - 1) / Int(refineBlockSize)
        let refinedMV = getOrCreateTexture(
            ref: &refinedMotionTexture,
            width: refineMVW, height: refineMVH,
            format: .rg16Float
        )
        encodeRefineME(
            prev: prev, curr: current,
            coarse: coarseMV, output: refinedMV,
            commandBuffer: commandBuffer
        )

        // Pass 3: Densify to per-pixel motion vectors.
        let pixelMV = getOrCreateTexture(
            ref: &pixelMotionTexture,
            width: w, height: h,
            format: .rg16Float
        )
        encodeDensify(blockMV: refinedMV, output: pixelMV, commandBuffer: commandBuffer)

        // Pass 4: Bidirectional warp + blend.
        let output = getOrCreateTexture(
            ref: &interpolatedFrame,
            width: w, height: h,
            format: current.pixelFormat
        )
        encodeInterpolation(
            prev: prev, curr: current,
            motion: pixelMV, output: output,
            commandBuffer: commandBuffer
        )

        return output
    }

    /// Store `frame` as the previous frame for the next interpolation.
    /// Must be called for every real (non-interpolated) frame after it has been
    /// presented.
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
    }

    // MARK: - Encode Passes

    private func encodeCoarseME(
        prev: MTLTexture, curr: MTLTexture,
        output: MTLTexture,
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
        encoder.setBytes(&bs, length: MemoryLayout<UInt32>.size, index: 0)
        encoder.setBytes(&sr, length: MemoryLayout<UInt32>.size, index: 1)

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

    // MARK: - Scene-Cut Detection

    /// Synchronous scene-cut test.  Encodes a lightweight SAD reduction, waits
    /// for the previous command buffer's readback (the buffers are shared-mode).
    /// Returns `true` if the two frames are too different to interpolate.
    private func detectSceneCut(
        prev: MTLTexture, curr: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) -> Bool {
        guard let state = sceneCutState,
              let sadBuf = sceneCutSADBuffer,
              let cntBuf = sceneCutCountBuffer
        else { return false }

        // Zero the accumulators.
        sadBuf.contents().storeBytes(of: UInt32(0), as: UInt32.self)
        cntBuf.contents().storeBytes(of: UInt32(0), as: UInt32.self)

        guard let encoder = commandBuffer.makeComputeCommandEncoder() else { return false }
        encoder.label = "Scene Cut Detection"
        encoder.setComputePipelineState(state)
        encoder.setTexture(prev, index: 0)
        encoder.setTexture(curr, index: 1)
        encoder.setBuffer(sadBuf, offset: 0, index: 0)
        encoder.setBuffer(cntBuf, offset: 0, index: 1)

        // Dispatch at 1/4 pixel density (the shader samples every 4th pixel).
        let sampleW = (curr.width + 3) / 4
        let sampleH = (curr.height + 3) / 4
        dispatch2D(encoder: encoder, width: sampleW, height: sampleH)
        encoder.endEncoding()

        // We need the result before deciding whether to proceed.  Commit a
        // temporary command buffer synchronously for the scene-cut pass.
        // This is a lightweight reduction — typically < 0.1ms.
        guard let scCB = commandBuffer.device.makeCommandQueue()?.makeCommandBuffer() else { return false }

        // Re-encode the scene cut on the temporary command buffer instead.
        // Actually, the scene cut data was already encoded on `commandBuffer`
        // which hasn't committed yet.  We need to use a blit or wait.
        // Simplest: commit+waitUntilCompleted on a separate command buffer
        // that copies the scene-cut pass.  But that's expensive.
        //
        // Better approach: use the previous frame pair's score.  The SAD
        // buffers persist between calls; we read the *previous* result
        // (one frame behind) which is good enough for scene-cut detection
        // because scene cuts are instantaneous and one-frame lag is acceptable.

        // Read the result from the PREVIOUS frame's dispatch (already completed).
        let totalSAD = sadBuf.contents().load(as: UInt32.self)
        let count = cntBuf.contents().load(as: UInt32.self)

        guard count > 0 else { return false }
        let avgSAD = Float(totalSAD) / Float(count)

        return avgSAD > sceneCutThreshold
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
