import Foundation
import Metal

// MetalFX is missing from the simulator SDKs, and its frame interpolator needs
// tvOS 26 (the TV target deploys to 18) and is unavailable on visionOS.
#if canImport(MetalFX) && (os(macOS) || os(iOS))
import MetalFX

/// Optional backend that uses Apple's `MTLFXFrameInterpolator` (macOS 26+ / iOS 26+)
/// for the final warp/blend pass instead of the custom bidirectional warp shader.
///
/// The motion vectors are still produced by our GPU block-matching pipeline —
/// this backend only replaces the interpolation step (Pass 4).  A flat depth
/// texture is synthesised because video content has no 3D depth information.
@available(macOS 26.0, iOS 26.0, *)
final class MetalFXInterpolatorBackend: @unchecked Sendable {

    let device: MTLDevice
    private var interpolator: (any MTLFXFrameInterpolator)?
    private var flatDepthTexture: MTLTexture?
    private var outputTexture: MTLTexture?
    private var prevColorTexture: MTLTexture?
    private var configuredWidth: Int = 0
    private var configuredHeight: Int = 0

    private let lock = NSLock()

    init?(device: MTLDevice) {
        guard MTLFXFrameInterpolatorDescriptor.supportsDevice(device) else { return nil }
        self.device = device
    }

    static func isSupported(device: MTLDevice) -> Bool {
        MTLFXFrameInterpolatorDescriptor.supportsDevice(device)
    }

    /// Encode frame interpolation using the MetalFX backend.
    ///
    /// - Parameters:
    ///   - prev: The previous (already committed) color texture.
    ///   - current: The current color texture.
    ///   - motion: Per-pixel motion vector texture (RG16Float, normalised to frame dimensions).
    ///   - frameRate: Source content frame rate (used for deltaTime).
    ///   - resetHistory: Pass `true` on first frame or scene cut.
    ///   - commandBuffer: The command buffer to encode into.
    /// - Returns: The interpolated output texture, or `nil` on failure.
    func interpolate(
        prev: MTLTexture,
        current: MTLTexture,
        motion: MTLTexture,
        frameRate: Float,
        resetHistory: Bool,
        commandBuffer: MTLCommandBuffer
    ) -> MTLTexture? {
        lock.lock()
        defer { lock.unlock() }

        let w = current.width
        let h = current.height

        if w != configuredWidth || h != configuredHeight {
            guard reconfigure(width: w, height: h, colorFormat: current.pixelFormat) else {
                return nil
            }
        }

        guard let interp = interpolator else { return nil }

        let depth = ensureFlatDepth(width: w, height: h, commandBuffer: commandBuffer)

        interp.colorTexture = current
        interp.prevColorTexture = prev
        interp.depthTexture = depth
        interp.motionTexture = motion
        interp.outputTexture = outputTexture

        interp.motionVectorScaleX = Float(w)
        interp.motionVectorScaleY = Float(h)

        interp.nearPlane = 0.1
        interp.farPlane = 1000.0
        interp.fieldOfView = 90.0
        interp.aspectRatio = Float(w) / Float(h)
        interp.deltaTime = frameRate > 0 ? 1.0 / frameRate : 1.0 / 24.0
        interp.shouldResetHistory = resetHistory
        interp.isDepthReversed = false

        interp.encode(commandBuffer: commandBuffer)

        return outputTexture
    }

    func reset() {
        lock.lock()
        defer { lock.unlock() }
        configuredWidth = 0
        configuredHeight = 0
        interpolator = nil
    }

    // MARK: - Private

    private func reconfigure(width: Int, height: Int, colorFormat: MTLPixelFormat) -> Bool {
        let desc = MTLFXFrameInterpolatorDescriptor()
        desc.colorTextureFormat = colorFormat
        desc.outputTextureFormat = colorFormat
        desc.depthTextureFormat = .depth32Float
        desc.motionTextureFormat = .rg16Float
        desc.inputWidth = width
        desc.inputHeight = height
        desc.outputWidth = width
        desc.outputHeight = height

        guard let interp = desc.makeFrameInterpolator(device: device) else {
            return false
        }

        let outDesc = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: colorFormat, width: width, height: height, mipmapped: false
        )
        outDesc.usage = MTLTextureUsage(rawValue: interp.outputTextureUsage.rawValue | MTLTextureUsage.shaderRead.rawValue)
        outDesc.storageMode = .private

        guard let out = device.makeTexture(descriptor: outDesc) else { return false }

        self.interpolator = interp
        self.outputTexture = out
        self.configuredWidth = width
        self.configuredHeight = height
        self.flatDepthTexture = nil

        return true
    }

    private func ensureFlatDepth(width: Int, height: Int, commandBuffer: MTLCommandBuffer) -> MTLTexture {
        if let existing = flatDepthTexture, existing.width == width, existing.height == height {
            return existing
        }

        let desc = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .depth32Float, width: width, height: height, mipmapped: false
        )
        desc.usage = [.shaderRead, .shaderWrite, .renderTarget]
        desc.storageMode = .private

        let tex = device.makeTexture(descriptor: desc)!

        if let blit = commandBuffer.makeBlitCommandEncoder() {
            blit.label = "MetalFX — Fill Flat Depth"
            blit.fill(buffer: device.makeBuffer(length: 1, options: .storageModeShared)!, range: 0..<1, value: 0)
            blit.endEncoding()
        }

        flatDepthTexture = tex
        return tex
    }
}
#endif
