//
//  AssRenderer.swift
//  Edendale
//
//  libass subtitle renderer for SSA/ASS subtitles.
//  Renders styled vector/bitmap subtitles into an MTLTexture for Metal compositing.
//

import CoreGraphics
import CoreMedia
import Foundation
import Metal

#if canImport(CoreText)
import CoreText
#endif

#if canImport(AppKit) && !targetEnvironment(macCatalyst)
import AppKit
#elseif canImport(UIKit)
import UIKit
#endif

#if canImport(libass)
import libass
#endif

/// Renders Advanced SubStation Alpha (ASS) and SubStation Alpha (SSA) subtitles using libass.
public final class AssRenderer: @unchecked Sendable {
    public static var isAvailable: Bool {
        #if canImport(SwiftLibass) || canImport(libass)
        return true
        #else
        return false
        #endif
    }

    private let device: MTLDevice?

    #if canImport(SwiftLibass) || canImport(libass)
    private var library: OpaquePointer?
    private var renderer: OpaquePointer?
    private var track: UnsafeMutablePointer<ASS_Track>?
    #endif

    private var frameWidth: Int = 1920
    private var frameHeight: Int = 1080
    private var cachedTexture: MTLTexture?
    private var lastRenderedTimeMs: Int64 = -1
    private var pixelBuffer: [UInt8] = []

    public init(device: MTLDevice? = MTLCreateSystemDefaultDevice()) {
        self.device = device
        #if canImport(SwiftLibass) || canImport(libass)
        initializeLibass()
        #endif
    }

    deinit {
        #if canImport(SwiftLibass) || canImport(libass)
        if let track {
            ass_free_track(track)
        }
        if let renderer {
            ass_renderer_done(renderer)
        }
        if let library {
            ass_library_done(library)
        }
        #endif
    }

    #if canImport(SwiftLibass) || canImport(libass)
    private func initializeLibass() {
        guard let lib = ass_library_init() else { return }
        self.library = lib

        ass_set_extract_fonts(lib, 1)

        guard let rend = ass_renderer_init(lib) else { return }
        self.renderer = rend

        ass_set_frame_size(rend, Int32(frameWidth), Int32(frameHeight))
        ass_set_storage_size(rend, Int32(frameWidth), Int32(frameHeight))

        configureDefaultFont(renderer: rend)

        self.track = ass_new_track(lib)
    }

    private func configureDefaultFont(renderer: OpaquePointer) {
        let (fontName, familyName) = resolveSystemFontNames()

        fontName.withCString { fontStr in
            familyName.withCString { famStr in
                // Set default font name and family, enable fontconfig/CoreText provider
                ass_set_fonts(renderer, fontStr, famStr, 1, nil, 1)
            }
        }
    }
    #endif

    private func resolveSystemFontNames() -> (fontName: String, familyName: String) {
        #if canImport(AppKit) && !targetEnvironment(macCatalyst)
        let font = NSFont.systemFont(ofSize: 24)
        return (font.fontName, font.familyName ?? "Helvetica")
        #elseif canImport(UIKit)
        let font = UIFont.systemFont(ofSize: 24)
        return (font.fontName, font.familyName)
        #else
        return ("Helvetica", "Helvetica")
        #endif
    }

    /// Update target rendering resolution.
    public func setFrameSize(_ size: CGSize) {
        let width = max(1, Int(size.width))
        let height = max(1, Int(size.height))
        guard width != frameWidth || height != frameHeight else { return }

        frameWidth = width
        frameHeight = height
        cachedTexture = nil
        lastRenderedTimeMs = -1

        #if canImport(SwiftLibass) || canImport(libass)
        if let renderer {
            ass_set_frame_size(renderer, Int32(width), Int32(height))
            ass_set_storage_size(renderer, Int32(width), Int32(height))
        }
        #endif
    }

    /// Ingest a full ASS/SSA script header or file content.
    public func addScriptData(_ data: Data) {
        #if canImport(SwiftLibass) || canImport(libass)
        guard let track else { return }
        data.withUnsafeBytes { (rawBuffer: UnsafeRawBufferPointer) in
            guard let baseAddress = rawBuffer.baseAddress?.assumingMemoryBound(to: CChar.self) else { return }
            ass_process_data(track, UnsafeMutablePointer(mutating: baseAddress), Int32(rawBuffer.count))
        }
        cachedTexture = nil
        lastRenderedTimeMs = -1
        #endif
    }

    /// Ingest a single decoded subtitle event from the demuxer/decoder.
    public func addEvent(_ event: DecodedSubtitleEvent) {
        #if canImport(SwiftLibass) || canImport(libass)
        guard let track else { return }
        let timecodeMs = Int64(event.start.seconds * 1000)
        let durationMs = Int64(max(0, (event.end - event.start).seconds * 1000))

        var eventString = event.text
        eventString.withUTF8 { utf8Buffer in
            guard let base = utf8Buffer.baseAddress else { return }
            let mutablePtr = UnsafeMutablePointer<CChar>(mutating: UnsafeRawPointer(base).assumingMemoryBound(to: CChar.self))
            ass_process_chunk(track, mutablePtr, Int32(utf8Buffer.count), timecodeMs, durationMs)
        }
        cachedTexture = nil
        lastRenderedTimeMs = -1
        #endif
    }

    /// Reset all loaded subtitle events and cached state.
    public func reset() {
        cachedTexture = nil
        lastRenderedTimeMs = -1
        #if canImport(SwiftLibass) || canImport(libass)
        if let oldTrack = track {
            ass_free_track(oldTrack)
        }
        if let library {
            self.track = ass_new_track(library)
        }
        #endif
    }

    /// Render subtitles at the specified playback time into an overlay texture.
    /// Returns `nil` if no subtitles are active at this time.
    public func render(at time: CMTime) -> MTLTexture? {
        #if canImport(SwiftLibass) || canImport(libass)
        guard let renderer, let track, let device else { return nil }

        let nowMs = Int64(time.seconds * 1000)
        var detectChange: Int32 = 0

        guard let headImage = ass_render_frame(renderer, track, nowMs, &detectChange) else {
            // No subtitle images active at this time
            cachedTexture = nil
            lastRenderedTimeMs = nowMs
            return nil
        }

        // Fast path: if libass detected no visual change and we already cached the texture
        if detectChange == 0, let cached = cachedTexture, lastRenderedTimeMs >= 0 {
            return cached
        }

        // Render ASS_Image list into RGBA pixel buffer
        let texture = compositeAssImages(headImage, device: device)
        self.cachedTexture = texture
        self.lastRenderedTimeMs = nowMs
        return texture
        #else
        return nil
        #endif
    }

    #if canImport(SwiftLibass) || canImport(libass)
    /// Blit linked list of ASS_Image items into an RGBA texture matching frameWidth x frameHeight.
    private func compositeAssImages(
        _ head: UnsafeMutablePointer<ASS_Image>,
        device: MTLDevice
    ) -> MTLTexture? {
        let totalBytes = frameWidth * frameHeight * 4
        if pixelBuffer.count != totalBytes {
            pixelBuffer = [UInt8](repeating: 0, count: totalBytes)
        } else {
            pixelBuffer.withUnsafeMutableBytes { raw in
                _ = memset(raw.baseAddress, 0, totalBytes)
            }
        }

        var current: UnsafeMutablePointer<ASS_Image>? = head
        while let img = current?.pointee {
            let w = Int(img.w)
            let h = Int(img.h)
            let stride = Int(img.stride)
            let dstX = Int(img.dst_x)
            let dstY = Int(img.dst_y)
            let color = img.color

            let r = UInt32((color >> 24) & 0xFF)
            let g = UInt32((color >> 16) & 0xFF)
            let b = UInt32((color >> 8) & 0xFF)
            let a = UInt32(255 - (color & 0xFF)) // In libass, 0 = opaque, 255 = transparent

            if a > 0 && img.bitmap != nil && w > 0 && h > 0 {
                let bitmap = img.bitmap!

                for row in 0..<h {
                    let targetY = dstY + row
                    guard targetY >= 0 && targetY < frameHeight else { continue }
                    let srcRowOffset = row * stride
                    let dstRowOffset = targetY * frameWidth

                    for col in 0..<w {
                        let targetX = dstX + col
                        guard targetX >= 0 && targetX < frameWidth else { continue }

                        let alphaFactor = UInt32(bitmap[srcRowOffset + col])
                        if alphaFactor == 0 { continue }

                        let pixelA = (a * alphaFactor) / 255
                        if pixelA == 0 { continue }

                        let dstIndex = (dstRowOffset + targetX) * 4
                        let invA = 255 - pixelA

                        let currentR = UInt32(pixelBuffer[dstIndex])
                        let currentG = UInt32(pixelBuffer[dstIndex + 1])
                        let currentB = UInt32(pixelBuffer[dstIndex + 2])
                        let currentA = UInt32(pixelBuffer[dstIndex + 3])

                        let outR = (r * pixelA + currentR * invA) / 255
                        let outG = (g * pixelA + currentG * invA) / 255
                        let outB = (b * pixelA + currentB * invA) / 255
                        let outA = pixelA + (currentA * invA) / 255

                        pixelBuffer[dstIndex] = UInt8(min(255, outR))
                        pixelBuffer[dstIndex + 1] = UInt8(min(255, outG))
                        pixelBuffer[dstIndex + 2] = UInt8(min(255, outB))
                        pixelBuffer[dstIndex + 3] = UInt8(min(255, outA))
                    }
                }
            }
            current = img.next
        }

        // Allocate or reuse texture
        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba8Unorm,
            width: frameWidth,
            height: frameHeight,
            mipmapped: false
        )
        descriptor.usage = [.shaderRead]

        guard let texture = device.makeTexture(descriptor: descriptor) else {
            return nil
        }

        pixelBuffer.withUnsafeBytes { raw in
            guard let baseAddress = raw.baseAddress else { return }
            texture.replace(
                region: MTLRegionMake2D(0, 0, frameWidth, frameHeight),
                mipmapLevel: 0,
                withBytes: baseAddress,
                bytesPerRow: frameWidth * 4
            )
        }

        return texture
    }
    #endif
}
