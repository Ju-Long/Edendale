//
//  PixelBufferTextureCache.swift
//  Edendale
//
//  Zero-copy bridge converting CVPixelBuffer to MTLTexture via IOSurface.
//

import CoreGraphics
import CoreVideo
import Foundation
import Metal

/// Result of mapping a CVPixelBuffer to Metal textures.
/// For packed formats (BGRA), only `lumaTexture` is populated.
/// For bi-planar YCbCr (420v/420f/p010), both planes are mapped.
public struct VideoTexture {
    public let lumaTexture: MTLTexture
    public let chromaTexture: MTLTexture?
    public let isVideoRange: Bool

    public var isBiPlanarYCbCr: Bool { chromaTexture != nil }
}

/// Converts `CVPixelBuffer` to `MTLTexture` zero-copy via IOSurface / CoreVideo Metal texture cache.
/// Avoids CPU/GPU copy on Apple Silicon by mapping the pixel buffer's underlying memory directly.
public final class PixelBufferTextureCache: @unchecked Sendable {
    public let device: MTLDevice
    private var textureCache: CVMetalTextureCache?
    private let cacheLock = NSLock()

    private var ycbcrPipelineState: MTLComputePipelineState?
    private var cachedBGRATexture: MTLTexture?

    public init(device: MTLDevice = MetalContext.shared?.device ?? MTLCreateSystemDefaultDevice()!) {
        self.device = device
        setupTextureCache()
    }

    deinit {
        flush()
    }

    private func setupTextureCache() {
        var cache: CVMetalTextureCache?
        let result = CVMetalTextureCacheCreate(
            kCFAllocatorDefault,
            nil,
            device,
            nil,
            &cache
        )
        if result == kCVReturnSuccess {
            self.textureCache = cache
        }
        setupYCbCrPipeline()
    }

    // MARK: - Texture Mapping

    /// Maps a CVPixelBuffer to a `VideoTexture`, handling both packed (BGRA) and
    /// bi-planar YCbCr formats including 10-bit P010.
    public func videoTexture(from pixelBuffer: CVPixelBuffer) -> VideoTexture? {
        cacheLock.lock()
        defer { cacheLock.unlock() }

        guard let textureCache else { return nil }

        let pixelFormatType = CVPixelBufferGetPixelFormatType(pixelBuffer)

        switch pixelFormatType {
        case kCVPixelFormatType_32BGRA:
            let w = CVPixelBufferGetWidth(pixelBuffer)
            let h = CVPixelBufferGetHeight(pixelBuffer)
            guard let tex = createCVTexture(cache: textureCache, pixelBuffer: pixelBuffer,
                                            format: .bgra8Unorm, width: w, height: h, planeIndex: 0)
            else { return nil }
            return VideoTexture(lumaTexture: tex, chromaTexture: nil, isVideoRange: false)

        case kCVPixelFormatType_32RGBA:
            let w = CVPixelBufferGetWidth(pixelBuffer)
            let h = CVPixelBufferGetHeight(pixelBuffer)
            guard let tex = createCVTexture(cache: textureCache, pixelBuffer: pixelBuffer,
                                            format: .rgba8Unorm, width: w, height: h, planeIndex: 0)
            else { return nil }
            return VideoTexture(lumaTexture: tex, chromaTexture: nil, isVideoRange: false)

        case kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
             kCVPixelFormatType_420YpCbCr8BiPlanarFullRange:
            let lumaW = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
            let lumaH = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
            let chromaW = CVPixelBufferGetWidthOfPlane(pixelBuffer, 1)
            let chromaH = CVPixelBufferGetHeightOfPlane(pixelBuffer, 1)
            guard let luma = createCVTexture(cache: textureCache, pixelBuffer: pixelBuffer,
                                             format: .r8Unorm, width: lumaW, height: lumaH, planeIndex: 0),
                  let chroma = createCVTexture(cache: textureCache, pixelBuffer: pixelBuffer,
                                               format: .rg8Unorm, width: chromaW, height: chromaH, planeIndex: 1)
            else { return nil }
            let isVideo = pixelFormatType == kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
            return VideoTexture(lumaTexture: luma, chromaTexture: chroma, isVideoRange: isVideo)

        case kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange,
             kCVPixelFormatType_420YpCbCr10BiPlanarFullRange:
            let lumaW = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
            let lumaH = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
            let chromaW = CVPixelBufferGetWidthOfPlane(pixelBuffer, 1)
            let chromaH = CVPixelBufferGetHeightOfPlane(pixelBuffer, 1)
            guard let luma = createCVTexture(cache: textureCache, pixelBuffer: pixelBuffer,
                                             format: .r16Unorm, width: lumaW, height: lumaH, planeIndex: 0),
                  let chroma = createCVTexture(cache: textureCache, pixelBuffer: pixelBuffer,
                                               format: .rg16Unorm, width: chromaW, height: chromaH, planeIndex: 1)
            else { return nil }
            let isVideo = pixelFormatType == kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
            return VideoTexture(lumaTexture: luma, chromaTexture: chroma, isVideoRange: isVideo)

        default:
            let w = CVPixelBufferGetWidth(pixelBuffer)
            let h = CVPixelBufferGetHeight(pixelBuffer)
            guard let tex = createCVTexture(cache: textureCache, pixelBuffer: pixelBuffer,
                                            format: .bgra8Unorm, width: w, height: h, planeIndex: 0)
            else { return nil }
            return VideoTexture(lumaTexture: tex, chromaTexture: nil, isVideoRange: false)
        }
    }

    // MARK: - YCbCr Conversion

    /// Converts a bi-planar YCbCr `VideoTexture` to a single BGRA texture via a
    /// BT.709 compute shader. Returns the luma texture unchanged for packed formats.
    public func convertToBGRA(_ videoTexture: VideoTexture, commandBuffer: MTLCommandBuffer) -> MTLTexture? {
        guard videoTexture.isBiPlanarYCbCr,
              let chromaTexture = videoTexture.chromaTexture,
              let pipeline = ycbcrPipelineState
        else {
            return videoTexture.lumaTexture
        }

        let width = videoTexture.lumaTexture.width
        let height = videoTexture.lumaTexture.height

        cacheLock.lock()
        if cachedBGRATexture == nil
            || cachedBGRATexture!.width != width
            || cachedBGRATexture!.height != height {
            let desc = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: .bgra8Unorm, width: width, height: height, mipmapped: false)
            desc.usage = [.shaderRead, .shaderWrite]
            desc.storageMode = .private
            cachedBGRATexture = device.makeTexture(descriptor: desc)
        }
        let output = cachedBGRATexture
        cacheLock.unlock()

        guard let output,
              let encoder = commandBuffer.makeComputeCommandEncoder()
        else { return nil }

        encoder.label = "YCbCr to BGRA"
        encoder.setComputePipelineState(pipeline)
        encoder.setTexture(videoTexture.lumaTexture, index: 0)
        encoder.setTexture(chromaTexture, index: 1)
        encoder.setTexture(output, index: 2)

        var isVideoRange: UInt32 = videoTexture.isVideoRange ? 1 : 0
        encoder.setBytes(&isVideoRange, length: MemoryLayout<UInt32>.size, index: 0)

        let tgSize = MTLSize(width: 16, height: 16, depth: 1)
        let tgCount = MTLSize(width: (width + 15) / 16, height: (height + 15) / 16, depth: 1)
        encoder.dispatchThreadgroups(tgCount, threadsPerThreadgroup: tgSize)
        encoder.endEncoding()

        return output
    }

    /// Flushes the underlying texture cache to reclaim unused textures.
    public func flush() {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        if let textureCache {
            CVMetalTextureCacheFlush(textureCache, 0)
        }
        cachedBGRATexture = nil
    }

    // MARK: - Private

    private func createCVTexture(
        cache: CVMetalTextureCache,
        pixelBuffer: CVPixelBuffer,
        format: MTLPixelFormat,
        width: Int,
        height: Int,
        planeIndex: Int
    ) -> MTLTexture? {
        var cvTexture: CVMetalTexture?
        let status = CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault, cache, pixelBuffer, nil,
            format, width, height, planeIndex, &cvTexture)
        guard status == kCVReturnSuccess, let cvTexture else { return nil }
        return CVMetalTextureGetTexture(cvTexture)
    }

    private func setupYCbCrPipeline() {
        guard let library = try? device.makeLibrary(source: Self.ycbcrShaderSource, options: nil),
              let function = library.makeFunction(name: "ycbcrToBGRA")
        else { return }
        ycbcrPipelineState = try? device.makeComputePipelineState(function: function)
    }

    private static let ycbcrShaderSource = """
    #include <metal_stdlib>
    using namespace metal;

    kernel void ycbcrToBGRA(
        texture2d<float, access::read>  lumaTexture   [[texture(0)]],
        texture2d<float, access::read>  chromaTexture  [[texture(1)]],
        texture2d<float, access::write> outputTexture  [[texture(2)]],
        constant uint &isVideoRange [[buffer(0)]],
        uint2 gid [[thread_position_in_grid]])
    {
        if (gid.x >= outputTexture.get_width() || gid.y >= outputTexture.get_height()) return;

        float y  = lumaTexture.read(gid).r;
        float2 cbcr = chromaTexture.read(gid / 2).rg;
        float cb, cr;

        if (isVideoRange) {
            y  = (y  - 16.0f/255.0f) * (255.0f/219.0f);
            cb = (cbcr.r - 16.0f/255.0f) * (255.0f/224.0f) - 0.5f;
            cr = (cbcr.g - 16.0f/255.0f) * (255.0f/224.0f) - 0.5f;
        } else {
            cb = cbcr.r - 0.5f;
            cr = cbcr.g - 0.5f;
        }

        // BT.709
        float r = y + 1.5748f * cr;
        float g = y - 0.1873f * cb - 0.4681f * cr;
        float b = y + 1.8556f * cb;

        outputTexture.write(float4(clamp(float3(r, g, b), 0.0f, 1.0f), 1.0f), gid);
    }
    """

    // MARK: - Test Pattern Generators

    /// Creates a solid-color test `CVPixelBuffer` in 32BGRA format for testing without a decoder.
    public static func createTestPixelBuffer(
        width: Int = 1920,
        height: Int = 1080,
        red: UInt8 = 0,
        green: UInt8 = 180,
        blue: UInt8 = 240,
        alpha: UInt8 = 255
    ) -> CVPixelBuffer? {
        var pixelBuffer: CVPixelBuffer?
        let attrs = [
            kCVPixelBufferMetalCompatibilityKey: true,
            kCVPixelBufferCGImageCompatibilityKey: true,
            kCVPixelBufferCGBitmapContextCompatibilityKey: true,
            kCVPixelBufferIOSurfacePropertiesKey: [:]
        ] as CFDictionary

        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_32BGRA,
            attrs,
            &pixelBuffer
        )

        guard status == kCVReturnSuccess, let pixelBuffer else { return nil }

        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }

        guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else { return nil }
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)

        for y in 0..<height {
            let rowPtr = baseAddress.advanced(by: y * bytesPerRow).assumingMemoryBound(to: UInt8.self)
            for x in 0..<width {
                let pixelOffset = x * 4
                rowPtr[pixelOffset + 0] = blue
                rowPtr[pixelOffset + 1] = green
                rowPtr[pixelOffset + 2] = red
                rowPtr[pixelOffset + 3] = alpha
            }
        }

        return pixelBuffer
    }

    /// Creates a gradient test `CVPixelBuffer` in 32BGRA format (animated with `phaseOffset`).
    public static func createGradientTestPixelBuffer(
        width: Int = 1920,
        height: Int = 1080,
        phaseOffset: Double = 0.0
    ) -> CVPixelBuffer? {
        var pixelBuffer: CVPixelBuffer?
        let attrs = [
            kCVPixelBufferMetalCompatibilityKey: true,
            kCVPixelBufferIOSurfacePropertiesKey: [:]
        ] as CFDictionary

        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            width,
            height,
            kCVPixelFormatType_32BGRA,
            attrs,
            &pixelBuffer
        )

        guard status == kCVReturnSuccess, let pixelBuffer else { return nil }

        CVPixelBufferLockBaseAddress(pixelBuffer, [])
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, []) }

        guard let baseAddress = CVPixelBufferGetBaseAddress(pixelBuffer) else { return nil }
        let bytesPerRow = CVPixelBufferGetBytesPerRow(pixelBuffer)

        let wf = Double(max(width - 1, 1))
        let hf = Double(max(height - 1, 1))

        for y in 0..<height {
            let rowPtr = baseAddress.advanced(by: y * bytesPerRow).assumingMemoryBound(to: UInt8.self)
            let v = Double(y) / hf
            for x in 0..<width {
                let u = Double(x) / wf
                let r = UInt8((sin(u * .pi * 2 + phaseOffset) * 0.5 + 0.5) * 255.0)
                let g = UInt8((cos(v * .pi * 2 + phaseOffset) * 0.5 + 0.5) * 255.0)
                let b = UInt8((sin((u + v) * .pi + phaseOffset) * 0.5 + 0.5) * 255.0)

                let pixelOffset = x * 4
                rowPtr[pixelOffset + 0] = b
                rowPtr[pixelOffset + 1] = g
                rowPtr[pixelOffset + 2] = r
                rowPtr[pixelOffset + 3] = 255
            }
        }

        return pixelBuffer
    }

    /// Creates an `MTLTexture` containing a test color or pattern directly.
    public static func createTestTexture(
        device: MTLDevice,
        width: Int = 1920,
        height: Int = 1080,
        color: SIMD4<Float> = SIMD4<Float>(0.1, 0.5, 0.9, 1.0)
    ) -> MTLTexture? {
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm,
            width: width,
            height: height,
            mipmapped: false
        )
        descriptor.usage = [.shaderRead, .shaderWrite, .renderTarget]

        guard let texture = device.makeTexture(descriptor: descriptor) else { return nil }

        var pixelData = [UInt8](repeating: 0, count: width * height * 4)
        let b = UInt8(min(max(color.z, 0), 1) * 255)
        let g = UInt8(min(max(color.y, 0), 1) * 255)
        let r = UInt8(min(max(color.x, 0), 1) * 255)
        let a = UInt8(min(max(color.w, 0), 1) * 255)

        for i in 0..<(width * height) {
            let offset = i * 4
            pixelData[offset + 0] = b
            pixelData[offset + 1] = g
            pixelData[offset + 2] = r
            pixelData[offset + 3] = a
        }

        texture.replace(
            region: MTLRegionMake2D(0, 0, width, height),
            mipmapLevel: 0,
            withBytes: pixelData,
            bytesPerRow: width * 4
        )

        return texture
    }
}
