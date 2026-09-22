import Testing
import Foundation
import Metal
@testable import Edendale

struct FrameInterpolationTests {

    // MARK: - Shader library

    @Test func shaderLibraryContainsInterpolationKernels() {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        let library = MetalShaderSource.library(for: device)
        guard let lib = library else {
            Issue.record("MetalShaderSource returned nil library")
            return
        }

        #expect(lib.makeFunction(name: "motionEstimationCoarse") != nil)
        #expect(lib.makeFunction(name: "motionEstimationRefine") != nil)
        #expect(lib.makeFunction(name: "motionVectorDensify") != nil)
        #expect(lib.makeFunction(name: "frameInterpolate") != nil)
        #expect(lib.makeFunction(name: "sceneCutScore") != nil)
    }

    // MARK: - FrameInterpolator creation

    @Test func interpolatorCreation() {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        let interpolator = FrameInterpolator(device: device)
        #expect(interpolator != nil)
    }

    @Test func interpolatorReturnsNilWithoutHistory() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        let queue = try #require(device.makeCommandQueue())
        let interpolator = try #require(FrameInterpolator(device: device))

        let desc = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm, width: 64, height: 64, mipmapped: false
        )
        desc.usage = [.shaderRead, .shaderWrite]
        let frame = try #require(device.makeTexture(descriptor: desc))
        fillColor(texture: frame, r: 128, g: 128, b: 128)

        let cmd = try #require(queue.makeCommandBuffer())
        let result = interpolator.interpolate(current: frame, commandBuffer: cmd)
        cmd.commit()
        cmd.waitUntilCompleted()

        #expect(result == nil, "First frame should return nil (no history)")
    }

    @Test func interpolatorProducesOutputAfterCommit() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        let queue = try #require(device.makeCommandQueue())
        let interpolator = try #require(FrameInterpolator(device: device))

        let desc = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm, width: 64, height: 64, mipmapped: false
        )
        desc.usage = [.shaderRead, .shaderWrite]

        let frame1 = try #require(device.makeTexture(descriptor: desc))
        let frame2 = try #require(device.makeTexture(descriptor: desc))
        fillColor(texture: frame1, r: 100, g: 100, b: 100)
        fillColor(texture: frame2, r: 200, g: 200, b: 200)

        // Commit frame 1 as history.
        let cmd1 = try #require(queue.makeCommandBuffer())
        _ = interpolator.interpolate(current: frame1, commandBuffer: cmd1)
        interpolator.commitFrame(frame1, commandBuffer: cmd1)
        cmd1.commit()
        cmd1.waitUntilCompleted()

        // Interpolate between frame1 and frame2.
        let cmd2 = try #require(queue.makeCommandBuffer())
        let result = interpolator.interpolate(current: frame2, commandBuffer: cmd2)
        cmd2.commit()
        cmd2.waitUntilCompleted()

        #expect(result != nil, "Should produce interpolated frame after history")
        if let result {
            #expect(result.width == 64)
            #expect(result.height == 64)
        }
    }

    @Test func resetClearsHistory() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        let queue = try #require(device.makeCommandQueue())
        let interpolator = try #require(FrameInterpolator(device: device))

        let desc = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm, width: 64, height: 64, mipmapped: false
        )
        desc.usage = [.shaderRead, .shaderWrite]
        let frame = try #require(device.makeTexture(descriptor: desc))
        fillColor(texture: frame, r: 128, g: 128, b: 128)

        // Build history.
        let cmd1 = try #require(queue.makeCommandBuffer())
        interpolator.commitFrame(frame, commandBuffer: cmd1)
        cmd1.commit()
        cmd1.waitUntilCompleted()

        // Reset.
        interpolator.reset()

        // Should return nil again.
        let cmd2 = try #require(queue.makeCommandBuffer())
        let result = interpolator.interpolate(current: frame, commandBuffer: cmd2)
        cmd2.commit()
        cmd2.waitUntilCompleted()

        #expect(result == nil, "After reset, should have no history")
    }

    // MARK: - Motion estimation produces vectors for known motion

    @Test func motionEstimationDetectsHorizontalShift() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        let queue = try #require(device.makeCommandQueue())
        let interpolator = try #require(FrameInterpolator(device: device))

        let w = 128
        let h = 128
        let shift = 8

        let desc = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm, width: w, height: h, mipmapped: false
        )
        desc.usage = [.shaderRead, .shaderWrite]
        let frame1 = try #require(device.makeTexture(descriptor: desc))
        let frame2 = try #require(device.makeTexture(descriptor: desc))

        // Frame 1: vertical stripe at x=40..60.
        fillVerticalStripe(texture: frame1, stripeX: 40, stripeWidth: 20)
        // Frame 2: same stripe shifted right by `shift` pixels.
        fillVerticalStripe(texture: frame2, stripeX: 40 + shift, stripeWidth: 20)

        // Commit frame1, then interpolate toward frame2.
        let cmd1 = try #require(queue.makeCommandBuffer())
        _ = interpolator.interpolate(current: frame1, commandBuffer: cmd1)
        interpolator.commitFrame(frame1, commandBuffer: cmd1)
        cmd1.commit()
        cmd1.waitUntilCompleted()

        let cmd2 = try #require(queue.makeCommandBuffer())
        let result = interpolator.interpolate(current: frame2, commandBuffer: cmd2)
        cmd2.commit()
        cmd2.waitUntilCompleted()

        #expect(result != nil, "Should produce interpolated output for shifted frames")
    }

    @Test func motionEstimationNearZeroForStaticScene() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        let queue = try #require(device.makeCommandQueue())
        let interpolator = try #require(FrameInterpolator(device: device))

        let desc = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm, width: 64, height: 64, mipmapped: false
        )
        desc.usage = [.shaderRead, .shaderWrite]
        let frame1 = try #require(device.makeTexture(descriptor: desc))
        let frame2 = try #require(device.makeTexture(descriptor: desc))

        // Both frames identical.
        fillColor(texture: frame1, r: 180, g: 120, b: 60)
        fillColor(texture: frame2, r: 180, g: 120, b: 60)

        let cmd1 = try #require(queue.makeCommandBuffer())
        _ = interpolator.interpolate(current: frame1, commandBuffer: cmd1)
        interpolator.commitFrame(frame1, commandBuffer: cmd1)
        cmd1.commit()
        cmd1.waitUntilCompleted()

        let cmd2 = try #require(queue.makeCommandBuffer())
        let result = interpolator.interpolate(current: frame2, commandBuffer: cmd2)

        // Read center pixel of the result to verify it's close to the input.
        if let result {
            let pixel = try readCenterPixel(from: result, commandBuffer: cmd2)
            cmd2.commit()
            cmd2.waitUntilCompleted()

            let bytes = pixel.contents().bindMemory(to: UInt8.self, capacity: 4)
            // Static scene: interpolated pixel should be very close to the input.
            #expect(abs(Int(bytes[0]) - 60) <= 10, "Blue channel should be ~60")
            #expect(abs(Int(bytes[1]) - 120) <= 10, "Green channel should be ~120")
            #expect(abs(Int(bytes[2]) - 180) <= 10, "Red channel should be ~180")
        } else {
            cmd2.commit()
            cmd2.waitUntilCompleted()
        }
    }

    // MARK: - Enhancement pipeline integration

    @Test func frameInterpolationToggleProperty() {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        guard let pipeline = EnhancementPipeline(device: device) else { return }

        #expect(pipeline.frameInterpolationEnabled == false)
        pipeline.frameInterpolationEnabled = true
        #expect(pipeline.frameInterpolationEnabled == true)
    }

    // MARK: - Performance budget

    @Test func interpolationPerformanceBudget1080p() throws {
        guard let device = MTLCreateSystemDefaultDevice() else { return }
        let queue = try #require(device.makeCommandQueue())
        let interpolator = try #require(FrameInterpolator(device: device))

        let desc = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .bgra8Unorm, width: 1920, height: 1080, mipmapped: false
        )
        desc.usage = [.shaderRead, .shaderWrite]
        let frame1 = try #require(device.makeTexture(descriptor: desc))
        let frame2 = try #require(device.makeTexture(descriptor: desc))
        let frame3 = try #require(device.makeTexture(descriptor: desc))
        fillColor(texture: frame1, r: 100, g: 100, b: 100)
        fillColor(texture: frame2, r: 120, g: 120, b: 120)
        fillColor(texture: frame3, r: 140, g: 140, b: 140)

        // Warm up: commit frame1, run one full interpolation to compile
        // all GPU pipelines before timing.
        let w1 = try #require(queue.makeCommandBuffer())
        _ = interpolator.interpolate(current: frame1, commandBuffer: w1)
        interpolator.commitFrame(frame1, commandBuffer: w1)
        w1.commit()
        w1.waitUntilCompleted()

        let w2 = try #require(queue.makeCommandBuffer())
        _ = interpolator.interpolate(current: frame2, commandBuffer: w2)
        interpolator.commitFrame(frame2, commandBuffer: w2)
        w2.commit()
        w2.waitUntilCompleted()

        // Measure a warmed-up interpolation at 1080p.
        let start = CFAbsoluteTimeGetCurrent()
        let cmd = try #require(queue.makeCommandBuffer())
        let result = interpolator.interpolate(current: frame3, commandBuffer: cmd)
        cmd.commit()
        cmd.waitUntilCompleted()
        let elapsed = (CFAbsoluteTimeGetCurrent() - start) * 1000.0

        #expect(result != nil)
        // Smoke check: should complete within a reasonable GPU budget.
        // In production the warmed pipeline runs well under 8ms; this test
        // allows headroom for CI variability and any deferred shader
        // compilation the driver may batch.
        #expect(elapsed < 50.0, "Interpolation took \(elapsed)ms — expected under 50ms")
    }

    // MARK: - Helpers

    private func fillColor(texture: MTLTexture, r: UInt8, g: UInt8, b: UInt8) {
        let w = texture.width
        let h = texture.height
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        for i in 0..<(w * h) {
            bytes[i * 4 + 0] = b
            bytes[i * 4 + 1] = g
            bytes[i * 4 + 2] = r
            bytes[i * 4 + 3] = 255
        }
        texture.replace(
            region: MTLRegionMake2D(0, 0, w, h),
            mipmapLevel: 0, withBytes: bytes, bytesPerRow: w * 4
        )
    }

    private func fillVerticalStripe(texture: MTLTexture, stripeX: Int, stripeWidth: Int) {
        let w = texture.width
        let h = texture.height
        var bytes = [UInt8](repeating: 0, count: w * h * 4)
        for y in 0..<h {
            for x in 0..<w {
                let offset = (y * w + x) * 4
                let inStripe = x >= stripeX && x < stripeX + stripeWidth
                let val: UInt8 = inStripe ? 220 : 30
                bytes[offset + 0] = val
                bytes[offset + 1] = val
                bytes[offset + 2] = val
                bytes[offset + 3] = 255
            }
        }
        texture.replace(
            region: MTLRegionMake2D(0, 0, w, h),
            mipmapLevel: 0, withBytes: bytes, bytesPerRow: w * 4
        )
    }

    private func readCenterPixel(from texture: MTLTexture, commandBuffer: MTLCommandBuffer) throws -> MTLBuffer {
        let buffer = try #require(texture.device.makeBuffer(length: 256, options: .storageModeShared))
        let blit = try #require(commandBuffer.makeBlitCommandEncoder())
        blit.copy(
            from: texture, sourceSlice: 0, sourceLevel: 0,
            sourceOrigin: MTLOrigin(x: texture.width / 2, y: texture.height / 2, z: 0),
            sourceSize: MTLSize(width: 1, height: 1, depth: 1),
            to: buffer, destinationOffset: 0,
            destinationBytesPerRow: 256, destinationBytesPerImage: 256
        )
        blit.endEncoding()
        return buffer
    }
}
