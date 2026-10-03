import Testing
import Foundation
import CoreGraphics
import Metal
@testable import Edendale

struct MetalEnhancementPipelineTests {

    // MARK: - Preset & Property Tests

    @Test func presetCasesAndDisplayNames() {
        let cases = EnhancementPreset.allCases
        #expect(cases.count == 4)
        #expect(cases.contains(.off))
        #expect(cases.contains(.sharpenOnly))
        #expect(cases.contains(.balanced))
        #expect(cases.contains(.quality))

        for preset in cases {
            #expect(!preset.displayName.isEmpty)
            #expect(preset.id == preset.rawValue)
        }
    }

    // MARK: - Target Resolution Logic (Section E.2)

    @Test func targetResolutionFor720pSource() {
        let source720p = CGSize(width: 1280, height: 720)
        let display1080p = CGSize(width: 1920, height: 1080)
        let display4K = CGSize(width: 3840, height: 2160)

        // Source < 1080p on 1080p display -> upscale to 1080p
        let target1080 = SpatialUpscaler.targetResolution(for: source720p, displaySize: display1080p)
        #expect(target1080 == CGSize(width: 1920, height: 1080))

        // Source < 1080p on 4K display -> upscale to 1080p (smaller of 1080p or display)
        let target4KDisplay = SpatialUpscaler.targetResolution(for: source720p, displaySize: display4K)
        #expect(target4KDisplay == CGSize(width: 1920, height: 1080))

        // Smaller display (e.g. 1440x900) -> upscale to display resolution
        let display900p = CGSize(width: 1440, height: 900)
        let target900 = SpatialUpscaler.targetResolution(for: source720p, displaySize: display900p)
        #expect(target900 == CGSize(width: 1440, height: 810))
    }

    @Test func targetResolutionFor1080pSource() {
        let source1080p = CGSize(width: 1920, height: 1080)
        let display1080p = CGSize(width: 1920, height: 1080)
        let display4K = CGSize(width: 3840, height: 2160)

        // Source 1080p on 1080p display -> at or above display -> skip upscale
        let targetOn1080 = SpatialUpscaler.targetResolution(for: source1080p, displaySize: display1080p)
        #expect(targetOn1080 == source1080p)

        // Source 1080p on 4K display -> upscale to 4K
        let targetOn4K = SpatialUpscaler.targetResolution(for: source1080p, displaySize: display4K)
        #expect(targetOn4K == CGSize(width: 3840, height: 2160))
    }

    @Test func targetResolutionFor4KSource() {
        let source4K = CGSize(width: 3840, height: 2160)
        let display4K = CGSize(width: 3840, height: 2160)

        // Source >= display resolution -> skip upscale
        let target = SpatialUpscaler.targetResolution(for: source4K, displaySize: display4K)
        #expect(target == source4K)
    }

    @Test func targetResolutionWithExplicitOverride() {
        let source720p = CGSize(width: 1280, height: 720)
        let display1080p = CGSize(width: 1920, height: 1080)
        let override4K = CGSize(width: 3840, height: 2160)

        let target = SpatialUpscaler.targetResolution(
            for: source720p,
            displaySize: display1080p,
            targetSizeOverride: override4K
        )
        #expect(target == override4K)
    }

    @Test func targetResolutionMaintainsEvenDimensions() {
        // Odd dimension sources should always round to even numbers
        let oddSource = CGSize(width: 853, height: 480)
        let display = CGSize(width: 1920, height: 1080)
        let target = SpatialUpscaler.targetResolution(for: oddSource, displaySize: display)
        #expect(Int(target.width) % 2 == 0)
        #expect(Int(target.height) % 2 == 0)
    }

    // MARK: - Metal GPU Execution Tests

    @Test func shaderLibraryLoadsSuccessfully() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        let library = MetalShaderSource.library(for: device)
        #expect(library != nil)

        guard let lib = library else { return }
        #expect(lib.makeFunction(name: "contrastAdaptiveSharpening") != nil)
        #expect(lib.makeFunction(name: "temporalDenoise") != nil)
        #expect(lib.makeFunction(name: "lanczosUpscale") != nil)
        #expect(lib.makeFunction(name: "applyColorAdjustments") != nil)
    }

    @Test(arguments: [
        (MTLTextureUsage([.shaderRead, .shaderWrite, .renderTarget]), MTLStorageMode.private),
        (MTLTextureUsage([.shaderRead, .shaderWrite]), MTLStorageMode.private),
        (MTLTextureUsage([.shaderRead, .shaderWrite, .renderTarget]), MTLStorageMode.shared)
    ])
    func spatialUpscalerEncodesAndExecutes(usage: MTLTextureUsage, storageMode: MTLStorageMode) throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        let commandQueue = try #require(device.makeCommandQueue())

        let upscaler = SpatialUpscaler(device: device)

        let inDesc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 320, height: 180, mipmapped: false)
        inDesc.usage = [.shaderRead, .shaderWrite]
        let source = try #require(device.makeTexture(descriptor: inDesc))

        let outDesc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 640, height: 360, mipmapped: false)
        outDesc.usage = usage
        outDesc.storageMode = storageMode
        let destination = try #require(device.makeTexture(descriptor: outDesc))

        fillColor(texture: source, r: 160, g: 96, b: 48)

        let cmd = try #require(commandQueue.makeCommandBuffer())
        upscaler.encode(source: source, destination: destination, commandBuffer: cmd)
        let pixel = try readCenterPixel(from: destination, commandBuffer: cmd)
        cmd.commit()
        cmd.waitUntilCompleted()

        #expect(cmd.status == .completed)
        #expect(destination.width == 640)
        #expect(destination.height == 360)
        let bytes = pixel.contents().bindMemory(to: UInt8.self, capacity: 4)
        #expect(abs(Int(bytes[0]) - 48) <= 3)
        #expect(abs(Int(bytes[1]) - 96) <= 3)
        #expect(abs(Int(bytes[2]) - 160) <= 3)
    }

    @Test(arguments: [EnhancementPreset.balanced, .quality])
    func pipelineUpscaleTexturesSupportRenderPasses(preset: EnhancementPreset) throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        let commandQueue = try #require(device.makeCommandQueue())
        let pipeline = try #require(EnhancementPipeline(device: device))
        pipeline.preset = preset
        pipeline.sharpness = 0
        pipeline.denoiseStrength = 0

        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 64, height: 36, mipmapped: false)
        desc.usage = .shaderRead
        let source = try #require(device.makeTexture(descriptor: desc))
        fillTestPattern(texture: source)

        // Exercise initial allocation, cached reuse, and allocation after a resize.
        for width in [128, 128, 256] {
            pipeline.targetSizeOverride = CGSize(width: width, height: width * 9 / 16)
            let cmd = try #require(commandQueue.makeCommandBuffer())
            let output = pipeline.process(source: source, commandBuffer: cmd)
            cmd.commit()
            cmd.waitUntilCompleted()

            #expect(cmd.status == .completed)
            #expect(output.width == width)
            #expect(output.height == width * 9 / 16)
            #expect(output.usage.contains(.renderTarget))
            #expect(output.storageMode == .private)
        }
    }

    @Test func casSharpeningEdgeEnhancement() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        guard let commandQueue = device.makeCommandQueue() else { return }
        guard let pipeline = EnhancementPipeline(device: device) else { return }

        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 64, height: 64, mipmapped: false)
        desc.usage = [.shaderRead, .shaderWrite]
        guard let source = device.makeTexture(descriptor: desc) else { return }
        fillStepEdge(texture: source)

        // Test with sharpness 0.0 (passthrough)
        pipeline.preset = .sharpenOnly
        pipeline.sharpness = 0.0

        guard let cmd1 = commandQueue.makeCommandBuffer() else { return }
        let outZero = pipeline.process(source: source, commandBuffer: cmd1)
        cmd1.commit()
        cmd1.waitUntilCompleted()
        #expect(cmd1.status == .completed)
        #expect(outZero.width == source.width)

        // Test with sharpness 1.0 (active CAS)
        pipeline.sharpness = 1.0
        guard let cmd2 = commandQueue.makeCommandBuffer() else { return }
        let outSharp = pipeline.process(source: source, commandBuffer: cmd2)
        cmd2.commit()
        cmd2.waitUntilCompleted()
        #expect(cmd2.status == .completed)
        #expect(outSharp.width == source.width)
    }

    @Test func temporalDenoiseMultipleFrames() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        guard let commandQueue = device.makeCommandQueue() else { return }
        guard let pipeline = EnhancementPipeline(device: device) else { return }

        pipeline.preset = .quality
        pipeline.denoiseStrength = 0.8
        pipeline.motionThreshold = 0.1
        pipeline.targetSizeOverride = CGSize(width: 64, height: 64)

        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 64, height: 64, mipmapped: false)
        desc.usage = [.shaderRead, .shaderWrite]
        guard let frame1 = device.makeTexture(descriptor: desc) else { return }
        guard let frame2 = device.makeTexture(descriptor: desc) else { return }

        fillColor(texture: frame1, r: 128, g: 128, b: 128)
        fillColor(texture: frame2, r: 132, g: 128, b: 128) // minor noise

        // Frame 1 primes history
        guard let cmd1 = commandQueue.makeCommandBuffer() else { return }
        _ = pipeline.process(source: frame1, commandBuffer: cmd1)
        cmd1.commit()
        cmd1.waitUntilCompleted()
        #expect(cmd1.status == .completed)

        // Frame 2 blends with history
        guard let cmd2 = commandQueue.makeCommandBuffer() else { return }
        let out2 = pipeline.process(source: frame2, commandBuffer: cmd2)
        cmd2.commit()
        cmd2.waitUntilCompleted()
        #expect(cmd2.status == .completed)
        #expect(out2.width == frame2.width)

        // Reset clears history
        pipeline.reset()
    }

    @Test func pipelinePassthroughWhenDisabledOrOff() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        guard let commandQueue = device.makeCommandQueue() else { return }
        guard let pipeline = EnhancementPipeline(device: device) else { return }

        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 128, height: 128, mipmapped: false)
        desc.usage = [.shaderRead, .shaderWrite]
        guard let source = device.makeTexture(descriptor: desc) else { return }

        guard let cmd1 = commandQueue.makeCommandBuffer() else { return }
        pipeline.isEnabled = false
        let outDisabled = pipeline.process(source: source, commandBuffer: cmd1)
        #expect(outDisabled === source)

        pipeline.isEnabled = true
        pipeline.preset = .off
        guard let cmd2 = commandQueue.makeCommandBuffer() else { return }
        let outOff = pipeline.process(source: source, commandBuffer: cmd2)
        #expect(outOff === source)
    }

    @Test func pipeline720pTo4KAcceptance() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        guard let commandQueue = device.makeCommandQueue() else { return }
        guard let pipeline = EnhancementPipeline(device: device) else { return }

        pipeline.isEnabled = true
        pipeline.preset = .balanced
        pipeline.sharpness = 0.7
        pipeline.targetSizeOverride = CGSize(width: 3840, height: 2160)

        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 1280, height: 720, mipmapped: false)
        desc.usage = [.shaderRead, .shaderWrite]
        guard let source720p = device.makeTexture(descriptor: desc) else { return }
        fillTestPattern(texture: source720p)

        guard let cmd = commandQueue.makeCommandBuffer() else { return }
        let out4K = pipeline.process(source: source720p, commandBuffer: cmd)
        cmd.commit()
        cmd.waitUntilCompleted()

        #expect(cmd.status == .completed)
        #expect(out4K.width == 3840)
        #expect(out4K.height == 2160)
    }

    @Test func pipelinePresetSwitchingWithoutStalls() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        guard let commandQueue = device.makeCommandQueue() else { return }
        guard let pipeline = EnhancementPipeline(device: device) else { return }

        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 256, height: 144, mipmapped: false)
        desc.usage = [.shaderRead, .shaderWrite]
        guard let source = device.makeTexture(descriptor: desc) else { return }
        fillTestPattern(texture: source)

        for preset in EnhancementPreset.allCases {
            pipeline.preset = preset
            guard let cmd = commandQueue.makeCommandBuffer() else { return }
            let out = pipeline.process(source: source, commandBuffer: cmd)
            cmd.commit()
            cmd.waitUntilCompleted()
            #expect(cmd.status == .completed)
            #expect(out.width > 0 && out.height > 0)
        }
    }

    @Test func pipelinePerformanceBudget4K() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        guard let commandQueue = device.makeCommandQueue() else { return }
        guard let pipeline = EnhancementPipeline(device: device) else { return }

        pipeline.isEnabled = true
        pipeline.preset = .quality
        pipeline.sharpness = 0.5
        pipeline.denoiseStrength = 0.5
        pipeline.targetSizeOverride = CGSize(width: 3840, height: 2160)

        let desc = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .bgra8Unorm, width: 1280, height: 720, mipmapped: false)
        desc.usage = [.shaderRead, .shaderWrite]
        guard let source720p = device.makeTexture(descriptor: desc) else { return }
        fillTestPattern(texture: source720p)

        // Warm up pipeline and prime history
        guard let warmupCmd = commandQueue.makeCommandBuffer() else { return }
        _ = pipeline.process(source: source720p, commandBuffer: warmupCmd)
        warmupCmd.commit()
        warmupCmd.waitUntilCompleted()

        // Measure GPU execution time
        guard let benchCmd = commandQueue.makeCommandBuffer() else { return }
        let out = pipeline.process(source: source720p, commandBuffer: benchCmd)
        benchCmd.commit()
        benchCmd.waitUntilCompleted()

        #expect(benchCmd.status == .completed)
        #expect(out.width == 3840)
        #expect(out.height == 2160)

        if benchCmd.gpuEndTime > benchCmd.gpuStartTime {
            let gpuDurationMs = (benchCmd.gpuEndTime - benchCmd.gpuStartTime) * 1000.0
            print("GPU execution time for 720p -> 4K quality preset: \(gpuDurationMs) ms")
            #expect(gpuDurationMs < 8.0)
        }
    }

    // MARK: - Pixel Helpers

    private func readCenterPixel(from texture: MTLTexture, commandBuffer: MTLCommandBuffer) throws -> MTLBuffer {
        let buffer = try #require(texture.device.makeBuffer(length: 256, options: .storageModeShared))
        let blit = try #require(commandBuffer.makeBlitCommandEncoder())
        blit.copy(
            from: texture,
            sourceSlice: 0,
            sourceLevel: 0,
            sourceOrigin: MTLOrigin(x: texture.width / 2, y: texture.height / 2, z: 0),
            sourceSize: MTLSize(width: 1, height: 1, depth: 1),
            to: buffer,
            destinationOffset: 0,
            destinationBytesPerRow: 256,
            destinationBytesPerImage: 256
        )
        blit.endEncoding()
        return buffer
    }

    private func fillTestPattern(texture: MTLTexture) {
        let w = texture.width
        let h = texture.height
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        for y in 0..<h {
            for x in 0..<w {
                let offset = (y * w + x) * 4
                let isWhite = ((x / 16) + (y / 16)) % 2 == 0
                let val: UInt8 = isWhite ? 240 : 20
                bytes[offset + 0] = val // B
                bytes[offset + 1] = val // G
                bytes[offset + 2] = val // R
                bytes[offset + 3] = 255 // A
            }
        }
        texture.replace(
            region: MTLRegionMake2D(0, 0, w, h),
            mipmapLevel: 0,
            withBytes: bytes,
            bytesPerRow: w * 4
        )
    }

    private func fillStepEdge(texture: MTLTexture) {
        let w = texture.width
        let h = texture.height
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        for y in 0..<h {
            for x in 0..<w {
                let offset = (y * w + x) * 4
                let val: UInt8 = (x < w / 2) ? 40 : 220
                bytes[offset + 0] = val
                bytes[offset + 1] = val
                bytes[offset + 2] = val
                bytes[offset + 3] = 255
            }
        }
        texture.replace(
            region: MTLRegionMake2D(0, 0, w, h),
            mipmapLevel: 0,
            withBytes: bytes,
            bytesPerRow: w * 4
        )
    }

    private func fillColor(texture: MTLTexture, r: UInt8, g: UInt8, b: UInt8) {
        let w = texture.width
        let h = texture.height
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        for y in 0..<h {
            for x in 0..<w {
                let offset = (y * w + x) * 4
                bytes[offset + 0] = b
                bytes[offset + 1] = g
                bytes[offset + 2] = r
                bytes[offset + 3] = 255
            }
        }
        texture.replace(
            region: MTLRegionMake2D(0, 0, w, h),
            mipmapLevel: 0,
            withBytes: bytes,
            bytesPerRow: w * 4
        )
    }
}
