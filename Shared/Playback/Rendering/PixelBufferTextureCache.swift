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

/// Converts `CVPixelBuffer` to `MTLTexture` zero-copy via IOSurface / CoreVideo Metal texture cache.
/// Avoids CPU/GPU copy on Apple Silicon by mapping the pixel buffer's underlying memory directly.
public final class PixelBufferTextureCache: @unchecked Sendable {
    public let device: MTLDevice
    private var textureCache: CVMetalTextureCache?
    private let cacheLock = NSLock()

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
    }

    /// Converts a CVPixelBuffer to an MTLTexture zero-copy.
    /// Handles 32BGRA, 32RGBA, and planar formats.
    public func texture(from pixelBuffer: CVPixelBuffer) -> MTLTexture? {
        cacheLock.lock()
        defer { cacheLock.unlock() }

        guard let textureCache else { return nil }

        let width = CVPixelBufferGetWidth(pixelBuffer)
        let height = CVPixelBufferGetHeight(pixelBuffer)
        let pixelFormatType = CVPixelBufferGetPixelFormatType(pixelBuffer)

        let metalFormat: MTLPixelFormat
        let planeIndex: Int

        switch pixelFormatType {
        case kCVPixelFormatType_32BGRA:
            metalFormat = .bgra8Unorm
            planeIndex = 0
        case kCVPixelFormatType_32RGBA:
            metalFormat = .rgba8Unorm
            planeIndex = 0
        case kCVPixelFormatType_32ARGB:
            metalFormat = .bgra8Unorm
            planeIndex = 0
        case kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
             kCVPixelFormatType_420YpCbCr8BiPlanarFullRange:
            // For bi-planar YUV, map the Y luminance plane as r8Unorm for grayscale fallback
            metalFormat = .r8Unorm
            planeIndex = 0
        default:
            metalFormat = .bgra8Unorm
            planeIndex = 0
        }

        var cvTexture: CVMetalTexture?
        let status = CVMetalTextureCacheCreateTextureFromImage(
            kCFAllocatorDefault,
            textureCache,
            pixelBuffer,
            nil,
            metalFormat,
            width,
            height,
            planeIndex,
            &cvTexture
        )

        guard status == kCVReturnSuccess, let cvTexture else {
            return nil
        }

        return CVMetalTextureGetTexture(cvTexture)
    }

    /// Flushes the underlying texture cache to reclaim unused textures.
    public func flush() {
        cacheLock.lock()
        defer { cacheLock.unlock() }
        if let textureCache {
            CVMetalTextureCacheFlush(textureCache, 0)
        }
    }

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
