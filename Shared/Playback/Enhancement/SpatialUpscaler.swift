import Foundation
import CoreGraphics
import Metal
#if canImport(MetalFX)
import MetalFX
#endif

/// Handles GPU-based spatial upscaling using `MTLFXSpatialScaler` with a custom Lanczos
/// compute shader fallback when MetalFX is unavailable on the device or platform.
final class SpatialUpscaler: @unchecked Sendable {
    let device: MTLDevice

    #if canImport(MetalFX)
    private var spatialScaler: MTLFXSpatialScaler?
    #endif
    private var cachedScalerInputWidth: Int = 0
    private var cachedScalerInputHeight: Int = 0
    private var cachedScalerOutputWidth: Int = 0
    private var cachedScalerOutputHeight: Int = 0
    private var cachedScalerPixelFormat: MTLPixelFormat = .invalid

    private var lanczosPipelineState: MTLComputePipelineState?
    private let pipelineLock = NSLock()

    init(device: MTLDevice, defaultLibrary: MTLLibrary? = nil) {
        self.device = device
        setupLanczosPipeline(library: defaultLibrary)
    }

    /// Determines target output resolution based on Section E.2:
    /// - Source < 1080p -> upscale to 1080p or display resolution, whichever is smaller
    /// - Source 1080p -> upscale to display resolution if display is 4K
    /// - Source >= display resolution -> skip upscale, CAS-only mode
    static func targetResolution(
        for sourceSize: CGSize,
        displaySize: CGSize,
        targetSizeOverride: CGSize? = nil
    ) -> CGSize {
        if let override = targetSizeOverride, override.width > 0, override.height > 0 {
            return override
        }

        guard sourceSize.width > 0, sourceSize.height > 0 else {
            return sourceSize
        }

        let isBelow1080p = sourceSize.width < 1920 && sourceSize.height < 1080
        let isDisplay4K = displaySize.width >= 3840 || displaySize.height >= 2160
        let isAtOrAboveDisplay = sourceSize.width >= displaySize.width && sourceSize.height >= displaySize.height

        if isAtOrAboveDisplay {
            return sourceSize
        }

        if isBelow1080p {
            // Upscale to 1080p bounding box or display resolution, whichever is smaller
            let boundingW = min(1920.0, displaySize.width)
            let boundingH = min(1080.0, displaySize.height)
            let scale = min(boundingW / sourceSize.width, boundingH / sourceSize.height)
            if scale > 1.0 {
                return makeEvenSize(CGSize(width: sourceSize.width * scale, height: sourceSize.height * scale))
            }
            return sourceSize
        }

        // Source is 1080p tier (>= 1080p, < 4K)
        if isDisplay4K {
            let scale = min(displaySize.width / sourceSize.width, displaySize.height / sourceSize.height)
            if scale > 1.0 {
                return makeEvenSize(CGSize(width: sourceSize.width * scale, height: sourceSize.height * scale))
            }
        }

        return sourceSize
    }

    private static func makeEvenSize(_ size: CGSize) -> CGSize {
        let w = (Int(round(size.width)) + 1) & ~1
        let h = (Int(round(size.height)) + 1) & ~1
        return CGSize(width: max(2, w), height: max(2, h))
    }

    /// Upscales `source` into `destination` on `commandBuffer`.
    /// Uses `MTLFXSpatialScaler` if supported, falling back to custom Lanczos compute kernel.
    func encode(
        source: MTLTexture,
        destination: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) {
        pipelineLock.lock()
        defer { pipelineLock.unlock() }

        if encodeMetalFX(source: source, destination: destination, commandBuffer: commandBuffer) {
            return
        }

        encodeLanczos(source: source, destination: destination, commandBuffer: commandBuffer)
    }

    private func encodeMetalFX(
        source: MTLTexture,
        destination: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) -> Bool {
        #if canImport(MetalFX)
        guard MTLFXSpatialScalerDescriptor.supportsDevice(device) else {
            return false
        }

        let inW = source.width
        let inH = source.height
        let outW = destination.width
        let outH = destination.height
        let format = source.pixelFormat

        // Reuse cached scaler if configuration matches
        if spatialScaler == nil ||
            cachedScalerInputWidth != inW ||
            cachedScalerInputHeight != inH ||
            cachedScalerOutputWidth != outW ||
            cachedScalerOutputHeight != outH ||
            cachedScalerPixelFormat != format {

            let descriptor = MTLFXSpatialScalerDescriptor()
            descriptor.inputWidth = inW
            descriptor.inputHeight = inH
            descriptor.outputWidth = outW
            descriptor.outputHeight = outH
            descriptor.colorTextureFormat = format
            descriptor.outputTextureFormat = destination.pixelFormat
            descriptor.colorProcessingMode = .perceptual

            guard let newScaler = descriptor.makeSpatialScaler(device: device) else {
                return false
            }

            spatialScaler = newScaler
            cachedScalerInputWidth = inW
            cachedScalerInputHeight = inH
            cachedScalerOutputWidth = outW
            cachedScalerOutputHeight = outH
            cachedScalerPixelFormat = format
        }

        guard let scaler = spatialScaler else { return false }
        // MetalFX requires its advertised usage flags and a private output texture.
        // Incompatible caller-owned textures can still use the Lanczos compute pass.
        guard source.usage.contains(scaler.colorTextureUsage),
              destination.usage.contains(scaler.outputTextureUsage),
              destination.storageMode == .private else {
            return false
        }
        scaler.colorTexture = source
        scaler.outputTexture = destination
        scaler.encode(commandBuffer: commandBuffer)
        return true
        #else
        return false
        #endif
    }

    private func encodeLanczos(
        source: MTLTexture,
        destination: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) {
        guard let pipelineState = lanczosPipelineState else { return }
        guard let encoder = commandBuffer.makeComputeCommandEncoder() else { return }

        encoder.label = "Lanczos Upscale Pass"
        encoder.setComputePipelineState(pipelineState)
        encoder.setTexture(source, index: 0)
        encoder.setTexture(destination, index: 1)

        let w = 16
        let h = 16
        let threadsPerGroup = MTLSize(width: w, height: h, depth: 1)
        let threadgroups = MTLSize(
            width: (destination.width + w - 1) / w,
            height: (destination.height + h - 1) / h,
            depth: 1
        )

        encoder.dispatchThreadgroups(threadgroups, threadsPerThreadgroup: threadsPerGroup)
        encoder.endEncoding()
    }

    private func setupLanczosPipeline(library: MTLLibrary?) {
        let functionName = "lanczosUpscale"
        let lib = library ?? MetalShaderSource.library(for: device)

        if let lib, let function = lib.makeFunction(name: functionName) {
            lanczosPipelineState = try? device.makeComputePipelineState(function: function)
        }
    }
}
