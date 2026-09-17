//
//  ImageSubtitleRenderer.swift
//  Edendale
//
//  Renderer for bitmap/image-based subtitles (PGS, VobSub).
//  Uploads decoded subtitle rects directly to an overlay MTLTexture.
//

import CoreGraphics
import CoreMedia
import Foundation
import Metal

/// Renders bitmap-based subtitles (such as Blu-ray PGS and DVD VobSub) into an overlay texture.
public final class ImageSubtitleRenderer: @unchecked Sendable {
    private let device: MTLDevice?

    private var cues: [ImageSubtitleCue] = []
    private var frameWidth: Int = 1920
    private var frameHeight: Int = 1080

    private var activeCueIndex: Int?
    private var cachedTexture: MTLTexture?

    public init(device: MTLDevice? = MTLCreateSystemDefaultDevice()) {
        self.device = device
    }

    /// Set rendering frame resolution.
    public func setFrameSize(_ size: CGSize) {
        let width = max(1, Int(size.width))
        let height = max(1, Int(size.height))
        guard width != frameWidth || height != frameHeight else { return }

        frameWidth = width
        frameHeight = height
        cachedTexture = nil
        activeCueIndex = nil
    }

    /// Add an image subtitle cue.
    public func addCue(_ cue: ImageSubtitleCue) {
        cues.append(cue)
        cues.sort { $0.start < $1.start }
        cachedTexture = nil
        activeCueIndex = nil
    }

    /// Reset all loaded cues.
    public func reset() {
        cues.removeAll()
        cachedTexture = nil
        activeCueIndex = nil
    }

    /// Render subtitles at the specified playback time into an overlay texture.
    /// Returns `nil` if no image cues are active at this time.
    public func render(at time: CMTime) -> MTLTexture? {
        guard let device else { return nil }

        let matchingIndex = cues.firstIndex { $0.contains(time: time) }
        guard let index = matchingIndex else {
            activeCueIndex = nil
            cachedTexture = nil
            return nil
        }

        if activeCueIndex == index, let cached = cachedTexture {
            return cached
        }

        let cue = cues[index]
        let texture = renderCueToTexture(cue, device: device)
        self.activeCueIndex = index
        self.cachedTexture = texture
        return texture
    }

    // MARK: - Texture Construction

    private func renderCueToTexture(_ cue: ImageSubtitleCue, device: MTLDevice) -> MTLTexture? {
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

        // Initialize texture with transparent black pixels
        let zeroBytes = [UInt8](repeating: 0, count: frameWidth * 4)
        for y in 0..<frameHeight {
            zeroBytes.withUnsafeBytes { raw in
                guard let base = raw.baseAddress else { return }
                texture.replace(
                    region: MTLRegionMake2D(0, y, frameWidth, 1),
                    mipmapLevel: 0,
                    withBytes: base,
                    bytesPerRow: frameWidth * 4
                )
            }
        }

        let sourceCanvas = cue.canvasSize
        let scaleX = sourceCanvas.width > 0 ? Double(frameWidth) / sourceCanvas.width : 1.0
        let scaleY = sourceCanvas.height > 0 ? Double(frameHeight) / sourceCanvas.height : 1.0

        for rect in cue.rects {
            guard rect.width > 0, rect.height > 0, !rect.data.isEmpty else { continue }

            if abs(scaleX - 1.0) < 0.001 && abs(scaleY - 1.0) < 0.001 {
                // Direct 1:1 placement
                let dstX = max(0, min(rect.x, frameWidth - rect.width))
                let dstY = max(0, min(rect.y, frameHeight - rect.height))
                let copyWidth = min(rect.width, frameWidth - dstX)
                let copyHeight = min(rect.height, frameHeight - dstY)

                rect.data.withUnsafeBytes { raw in
                    guard let base = raw.baseAddress else { return }
                    texture.replace(
                        region: MTLRegionMake2D(dstX, dstY, copyWidth, copyHeight),
                        mipmapLevel: 0,
                        withBytes: base,
                        bytesPerRow: rect.width * 4
                    )
                }
            } else {
                // Scaled placement
                let scaledX = Int(Double(rect.x) * scaleX)
                let scaledY = Int(Double(rect.y) * scaleY)
                let scaledWidth = max(1, Int(Double(rect.width) * scaleX))
                let scaledHeight = max(1, Int(Double(rect.height) * scaleY))

                guard let scaledData = resizeRGBA(
                    srcData: rect.data,
                    srcW: rect.width,
                    srcH: rect.height,
                    dstW: scaledWidth,
                    dstH: scaledHeight
                ) else { continue }

                let dstX = max(0, min(scaledX, frameWidth - scaledWidth))
                let dstY = max(0, min(scaledY, frameHeight - scaledHeight))
                let copyWidth = min(scaledWidth, frameWidth - dstX)
                let copyHeight = min(scaledHeight, frameHeight - dstY)

                scaledData.withUnsafeBytes { raw in
                    guard let base = raw.baseAddress else { return }
                    texture.replace(
                        region: MTLRegionMake2D(dstX, dstY, copyWidth, copyHeight),
                        mipmapLevel: 0,
                        withBytes: base,
                        bytesPerRow: scaledWidth * 4
                    )
                }
            }
        }

        return texture
    }

    /// Bilinear interpolation scaler for RGBA32 bitmap data.
    private func resizeRGBA(
        srcData: Data,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int
    ) -> Data? {
        guard srcW > 0, srcH > 0, dstW > 0, dstH > 0 else { return nil }
        var result = Data(count: dstW * dstH * 4)

        srcData.withUnsafeBytes { srcBytes in
            guard let srcPtr = srcBytes.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }
            result.withUnsafeMutableBytes { dstBytes in
                guard let dstPtr = dstBytes.baseAddress?.assumingMemoryBound(to: UInt8.self) else { return }

                let xRatio = Double(srcW) / Double(dstW)
                let yRatio = Double(srcH) / Double(dstH)

                for dy in 0..<dstH {
                    let sy = min(Double(dy) * yRatio, Double(srcH - 1))
                    let yFloor = Int(floor(sy))
                    let yCeil = min(yFloor + 1, srcH - 1)
                    let yWeight = sy - Double(yFloor)

                    let dstRowOffset = dy * dstW * 4
                    let srcRow1 = yFloor * srcW * 4
                    let srcRow2 = yCeil * srcW * 4

                    for dx in 0..<dstW {
                        let sx = min(Double(dx) * xRatio, Double(srcW - 1))
                        let xFloor = Int(floor(sx))
                        let xCeil = min(xFloor + 1, srcW - 1)
                        let xWeight = sx - Double(xFloor)

                        let idx11 = srcRow1 + xFloor * 4
                        let idx12 = srcRow1 + xCeil * 4
                        let idx21 = srcRow2 + xFloor * 4
                        let idx22 = srcRow2 + xCeil * 4

                        let dstIdx = dstRowOffset + dx * 4

                        for c in 0..<4 {
                            let p11 = Double(srcPtr[idx11 + c])
                            let p12 = Double(srcPtr[idx12 + c])
                            let p21 = Double(srcPtr[idx21 + c])
                            let p22 = Double(srcPtr[idx22 + c])

                            let top = p11 * (1.0 - xWeight) + p12 * xWeight
                            let bottom = p21 * (1.0 - xWeight) + p22 * xWeight
                            let val = top * (1.0 - yWeight) + bottom * yWeight

                            dstPtr[dstIdx + c] = UInt8(max(0, min(255, round(val))))
                        }
                    }
                }
            }
        }

        return result
    }
}
