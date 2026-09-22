import Foundation
import CoreGraphics
import Metal
import Observation

/// Preset configurations for the Metal video enhancement pipeline.
enum EnhancementPreset: String, CaseIterable, Identifiable, Sendable {
    case off            // passthrough
    case sharpenOnly    // CAS only, no upscale
    case balanced       // MetalFX + CAS
    case quality        // MetalFX + CAS + temporal denoise

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .off: String(localized: "Off")
        case .sharpenOnly: String(localized: "Sharpen Only")
        case .balanced: String(localized: "Balanced")
        case .quality: String(localized: "High Quality")
        }
    }
}

/// Uniform structure matching `ColorAdjustment.metal`
struct MetalVideoAdjustmentUniforms {
    var brightness: Float
    var contrast: Float
    var gamma: Float
    var saturation: Float
    var hue: Float
}

/// The GPU compute enhancement pipeline that upscales and enhances video frames in real time.
/// Operates entirely on `MTLTexture` instances and is independent of decoders and presentation surfaces.
@Observable
final class EnhancementPipeline: @unchecked Sendable {
    var isEnabled: Bool = true
    var preset: EnhancementPreset = .balanced {
        didSet {
            if preset != oldValue {
                resetHistory()
            }
        }
    }

    /// Sharpness amount in range 0.0 (no sharpening) to 1.0 (maximum). Default 0.5.
    var sharpness: Float = 0.5 {
        didSet {
            let clamped = min(max(sharpness, 0.0), 1.0)
            if sharpness != clamped {
                sharpness = clamped
            }
        }
    }

    /// Denoise strength in range 0.0 (off) to 1.0 (maximum). Default 0.5.
    var denoiseStrength: Float = 0.5 {
        didSet {
            let clamped = min(max(denoiseStrength, 0.0), 1.0)
            if denoiseStrength != clamped {
                denoiseStrength = clamped
            }
        }
    }

    /// Motion sensitivity threshold for temporal denoising. Default 0.08.
    var motionThreshold: Float = 0.08

    /// When enabled, the draw loop synthesises intermediate frames via
    /// motion-compensated interpolation, doubling the display framerate.
    /// Independent of the enhancement preset — works with any preset.
    var frameInterpolationEnabled: Bool = false

    /// Current display or viewport size. Used by the upscaler to target display resolution.
    var displaySize: CGSize = CGSize(width: 3840, height: 2160)

    /// Optional explicit target resolution override (e.g. for testing 720p -> 4K upscaling).
    var targetSizeOverride: CGSize? = nil

    /// Picture adjustments (brightness, contrast, gamma, saturation, hue) from VideoAdjustmentValues.
    var adjustments: VideoAdjustmentValues = VideoAdjustmentValues()

    // MARK: - Metal GPU State

    let device: MTLDevice
    private let upscaler: SpatialUpscaler

    private var casPipelineState: MTLComputePipelineState?
    private var denoisePipelineState: MTLComputePipelineState?
    private var colorAdjustmentPipelineState: MTLComputePipelineState?

    // Cached intermediate textures
    private var cachedUpscaledTexture: MTLTexture?
    private var cachedColorTexture: MTLTexture?
    private var cachedSharpenedTexture: MTLTexture?

    // Temporal denoise ping-pong textures
    private var historyTextureA: MTLTexture?
    private var historyTextureB: MTLTexture?
    private var isHistoryAActive: Bool = false
    private var hasValidHistory: Bool = false

    private let pipelineLock = NSLock()

    init?(device: MTLDevice? = MTLCreateSystemDefaultDevice()) {
        guard let device else { return nil }
        self.device = device

        let library = MetalShaderSource.library(for: device)
        self.upscaler = SpatialUpscaler(device: device, defaultLibrary: library)

        setupPipelines(library: library)
    }

    private func setupPipelines(library: MTLLibrary?) {
        guard let library else { return }

        if let casFunction = library.makeFunction(name: "contrastAdaptiveSharpening") {
            casPipelineState = try? device.makeComputePipelineState(function: casFunction)
        }
        if let denoiseFunction = library.makeFunction(name: "temporalDenoise") {
            denoisePipelineState = try? device.makeComputePipelineState(function: denoiseFunction)
        }
        if let colorFunction = library.makeFunction(name: "applyColorAdjustments") {
            colorAdjustmentPipelineState = try? device.makeComputePipelineState(function: colorFunction)
        }
    }

    /// Resets temporal history and cached textures (e.g. after a seek, media switch, or cut).
    func reset() {
        pipelineLock.lock()
        defer { pipelineLock.unlock() }
        resetHistory()
        cachedUpscaledTexture = nil
        cachedColorTexture = nil
        cachedSharpenedTexture = nil
    }

    private func resetHistory() {
        hasValidHistory = false
        historyTextureA = nil
        historyTextureB = nil
        isHistoryAActive = false
    }

    /// Process a source frame and return the enhanced texture.
    /// Encodes GPU compute passes into the provided `commandBuffer`.
    func process(
        source: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) -> MTLTexture {
        pipelineLock.lock()
        defer { pipelineLock.unlock() }

        guard isEnabled && preset != .off else {
            return source
        }

        let sourceSize = CGSize(width: source.width, height: source.height)
        let targetSize: CGSize
        if preset == .sharpenOnly {
            targetSize = sourceSize
        } else {
            targetSize = SpatialUpscaler.targetResolution(
                for: sourceSize,
                displaySize: displaySize,
                targetSizeOverride: targetSizeOverride
            )
        }

        var currentTexture = source

        // Pass 1: Spatial Upscale (MetalFX or Lanczos fallback)
        let needsUpscale = (preset == .balanced || preset == .quality) &&
            (Int(targetSize.width) != source.width || Int(targetSize.height) != source.height)

        if needsUpscale {
            let outW = Int(targetSize.width)
            let outH = Int(targetSize.height)
            let upscaleDest = getOrCreateTexture(
                ref: &cachedUpscaledTexture,
                width: outW,
                height: outH,
                format: source.pixelFormat,
                // MetalFX writes its output through a render pass.
                usage: [.shaderRead, .shaderWrite, .renderTarget]
            )
            upscaler.encode(source: currentTexture, destination: upscaleDest, commandBuffer: commandBuffer)
            currentTexture = upscaleDest
        }

        // Pass 2: Color Adjustments (if non-neutral)
        if !adjustments.isNeutral, let colorPipeline = colorAdjustmentPipelineState {
            let colorDest = getOrCreateTexture(
                ref: &cachedColorTexture,
                width: currentTexture.width,
                height: currentTexture.height,
                format: currentTexture.pixelFormat
            )
            encodeColorAdjustments(
                source: currentTexture,
                destination: colorDest,
                pipeline: colorPipeline,
                commandBuffer: commandBuffer
            )
            currentTexture = colorDest
        }

        // Pass 3: Contrast Adaptive Sharpening (CAS)
        if sharpness > 0, let casPipeline = casPipelineState {
            let sharpDest = getOrCreateTexture(
                ref: &cachedSharpenedTexture,
                width: currentTexture.width,
                height: currentTexture.height,
                format: currentTexture.pixelFormat
            )
            encodeCAS(
                source: currentTexture,
                destination: sharpDest,
                pipeline: casPipeline,
                commandBuffer: commandBuffer
            )
            currentTexture = sharpDest
        }

        // Pass 4: Temporal Denoise
        if preset == .quality, denoiseStrength > 0, let denoisePipeline = denoisePipelineState {
            currentTexture = encodeTemporalDenoise(
                source: currentTexture,
                pipeline: denoisePipeline,
                commandBuffer: commandBuffer
            )
        } else {
            hasValidHistory = false
        }

        return currentTexture
    }

    // MARK: - Pass Encoders

    private func encodeColorAdjustments(
        source: MTLTexture,
        destination: MTLTexture,
        pipeline: MTLComputePipelineState,
        commandBuffer: MTLCommandBuffer
    ) {
        guard let encoder = commandBuffer.makeComputeCommandEncoder() else { return }
        encoder.label = "Color Adjustments Pass"
        encoder.setComputePipelineState(pipeline)
        encoder.setTexture(source, index: 0)
        encoder.setTexture(destination, index: 1)

        var uniforms = MetalVideoAdjustmentUniforms(
            brightness: adjustments.brightness,
            contrast: adjustments.contrast,
            gamma: adjustments.gamma,
            saturation: adjustments.saturation,
            hue: adjustments.hue
        )
        encoder.setBytes(&uniforms, length: MemoryLayout<MetalVideoAdjustmentUniforms>.size, index: 0)

        dispatch2D(encoder: encoder, width: destination.width, height: destination.height)
        encoder.endEncoding()
    }

    private func encodeCAS(
        source: MTLTexture,
        destination: MTLTexture,
        pipeline: MTLComputePipelineState,
        commandBuffer: MTLCommandBuffer
    ) {
        guard let encoder = commandBuffer.makeComputeCommandEncoder() else { return }
        encoder.label = "Contrast Adaptive Sharpening Pass"
        encoder.setComputePipelineState(pipeline)
        encoder.setTexture(source, index: 0)
        encoder.setTexture(destination, index: 1)

        var s = sharpness
        encoder.setBytes(&s, length: MemoryLayout<Float>.size, index: 0)

        dispatch2D(encoder: encoder, width: destination.width, height: destination.height)
        encoder.endEncoding()
    }

    private func encodeTemporalDenoise(
        source: MTLTexture,
        pipeline: MTLComputePipelineState,
        commandBuffer: MTLCommandBuffer
    ) -> MTLTexture {
        let w = source.width
        let h = source.height
        let format = source.pixelFormat

        let histA = getOrCreateTexture(ref: &historyTextureA, width: w, height: h, format: format)
        let histB = getOrCreateTexture(ref: &historyTextureB, width: w, height: h, format: format)

        guard hasValidHistory else {
            // First frame: copy source to histA to prime history
            if let blit = commandBuffer.makeBlitCommandEncoder() {
                blit.label = "Prime Denoise History"
                blit.copy(from: source, to: histA)
                blit.endEncoding()
            }
            isHistoryAActive = true
            hasValidHistory = true
            return source
        }

        let historyRead = isHistoryAActive ? histA : histB
        let denoiseWrite = isHistoryAActive ? histB : histA

        guard let encoder = commandBuffer.makeComputeCommandEncoder() else {
            return source
        }
        encoder.label = "Temporal Denoise Pass"
        encoder.setComputePipelineState(pipeline)
        encoder.setTexture(source, index: 0)
        encoder.setTexture(historyRead, index: 1)
        encoder.setTexture(denoiseWrite, index: 2)

        var strength = denoiseStrength
        var threshold = motionThreshold
        encoder.setBytes(&strength, length: MemoryLayout<Float>.size, index: 0)
        encoder.setBytes(&threshold, length: MemoryLayout<Float>.size, index: 1)

        dispatch2D(encoder: encoder, width: w, height: h)
        encoder.endEncoding()

        // Flip active history texture for the next frame
        isHistoryAActive.toggle()

        return denoiseWrite
    }

    // MARK: - Helpers

    private func dispatch2D(encoder: MTLComputeCommandEncoder, width: Int, height: Int) {
        let threadgroupSize = MTLSize(width: 16, height: 16, depth: 1)
        let threadgroups = MTLSize(
            width: (width + 15) / 16,
            height: (height + 15) / 16,
            depth: 1
        )
        encoder.dispatchThreadgroups(threadgroups, threadsPerThreadgroup: threadgroupSize)
    }

    private func getOrCreateTexture(
        ref: inout MTLTexture?,
        width: Int,
        height: Int,
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
            width: width,
            height: height,
            mipmapped: false
        )
        desc.usage = usage
        desc.storageMode = .private

        let newTexture = device.makeTexture(descriptor: desc)!
        ref = newTexture
        return newTexture
    }
}
