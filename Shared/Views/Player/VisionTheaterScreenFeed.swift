//
//  VisionTheaterScreenFeed.swift
//  Edendale
//
//  Carries the player's picture onto the immersive theater's screen.
//

#if os(visionOS)

import Metal
import RealityKit

/// Copies each frame the player draws onto the theater screen.
///
/// The player's `EnhancedVideoView` hands over its finished frame (after
/// Video Enhancement) inside its own command buffer. Once the theater has
/// closed the player window, that view is the theater's own (see
/// `VisionTheaterSpace`). The feed draws the frame, fitted and letterboxed,
/// into a RealityKit `LowLevelTexture` in the screen's shape (see
/// `TheaterLayout.canvasRows`), which the screen's unlit material samples.
/// RealityKit shows the new contents once that command buffer completes.
/// The screen therefore follows the player exactly, paused frames and
/// seeks included, with no second decoder.
@MainActor
final class VisionTheaterScreenFeed {
    /// What the screen's material samples.
    private(set) var textureResource: TextureResource?

    /// The video view draws into the canvas only while a theater is open.
    var isActive = false {
        didSet {
            // A paused player draws only on request, so the screen would
            // stay black until the next seek.
            if isActive, !oldValue { requestRedraw() }
        }
    }

    /// The video view drawing onto the screen, set by the view itself.
    weak var source: EnhancedVideoView?

    /// Asks the video view to draw once more while it is paused.
    private func requestRedraw() {
        source?.setNeedsPausedRedraw()
    }

    /// The screen's width over its height, from the loaded scene.
    var screenAspect: Double = 16.0 / 9.0

    private var canvas: LowLevelTexture?

    /// The texture the screen material should show, creating a black
    /// canvas the first time.
    func prepareTexture() throws -> TextureResource {
        let size = TheaterLayout.canvasSize(screenAspect: screenAspect)
        if let textureResource, let canvas,
           canvas.descriptor.width == size.width, canvas.descriptor.height == size.height {
            return textureResource
        }
        let canvas = try makeCanvas(width: size.width, height: size.height)
        clear(canvas)
        let resource = try TextureResource(from: canvas)
        self.canvas = canvas
        textureResource = resource
        // The room loads after the theater opened; a paused player has to
        // draw its frame again now there is a canvas to draw it on.
        if isActive { requestRedraw() }
        return resource
    }

    /// Draws `texture` onto the screen. Called by the video view after its
    /// own drawing, before it commits `commandBuffer`.
    func draw(_ texture: MTLTexture, commandBuffer: MTLCommandBuffer, metalContext: MetalContext) {
        guard isActive, let canvas else { return }
        let size = (width: canvas.descriptor.width, height: canvas.descriptor.height)

        guard let pipeline = Self.pipeline(device: metalContext.device) else { return }
        let target = canvas.replace(using: commandBuffer)

        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = target
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        pass.colorAttachments[0].storeAction = .store

        let vertices = MetalContext.computeQuadVertices(
            containerSize: CGSize(width: size.width, height: size.height),
            contentSize: CGSize(width: texture.width, height: texture.height),
            aspectMode: .fit
        )
        guard let vertexBuffer = metalContext.device.makeBuffer(
                bytes: vertices,
                length: MemoryLayout<MetalQuadVertex>.stride * vertices.count,
                options: .storageModeShared
              ),
              let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass)
        else { return }
        encoder.label = "Theater Screen"
        encoder.setRenderPipelineState(pipeline)
        encoder.setVertexBuffer(vertexBuffer, offset: 0, index: 0)
        encoder.setFragmentTexture(texture, index: 0)
        encoder.setFragmentSamplerState(metalContext.samplerState, index: 0)
        encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: vertices.count)
        encoder.endEncoding()
    }

    // MARK: - Colour

    /// RealityKit reads a `LowLevelTexture`'s colours as Display P3, unlike
    /// an image texture, which it colour-manages: the player's sRGB pixels
    /// stored as they are came out oversaturated (sRGB red as P3 red). The
    /// pass decodes them, moves them to P3 primaries, and the sRGB canvas
    /// encodes the result, so the screen matches the 2D window. Measured on
    /// the visionOS 27.2 simulator against an sRGB image texture.
    private static let shaderSource = """
    #include <metal_stdlib>
    using namespace metal;

    struct QuadVertex {
        float2 position;
        float2 texCoords;
    };

    struct VertexOut {
        float4 position [[position]];
        float2 texCoords;
    };

    vertex VertexOut theaterScreenVertex(
        uint vertexID [[vertex_id]],
        constant QuadVertex *vertices [[buffer(0)]]
    ) {
        VertexOut out;
        out.position = float4(vertices[vertexID].position, 0.0, 1.0);
        out.texCoords = vertices[vertexID].texCoords;
        return out;
    }

    static float3 srgbToLinear(float3 c) {
        return select(pow((c + 0.055) / 1.055, 2.4), c / 12.92, c <= 0.04045);
    }

    fragment float4 theaterScreenFragment(
        VertexOut in [[stage_in]],
        texture2d<float> frame [[texture(0)]],
        sampler frameSampler [[sampler(0)]]
    ) {
        float3 encoded = frame.sample(frameSampler, in.texCoords).rgb;
        // Linear Rec. 709 / sRGB to linear Display P3, both D65.
        const float3x3 toP3 = float3x3(
            float3(0.822462, 0.033194, 0.017083),
            float3(0.177538, 0.966806, 0.072397),
            float3(0.0,      0.0,      0.910520)
        );
        return float4(toP3 * srgbToLinear(encoded), 1.0);
    }
    """

    private static var pipelines: [ObjectIdentifier: MTLRenderPipelineState] = [:]

    private static func pipeline(device: MTLDevice) -> MTLRenderPipelineState? {
        let key = ObjectIdentifier(device)
        if let pipeline = pipelines[key] { return pipeline }
        do {
            let library = try device.makeLibrary(source: shaderSource, options: nil)
            let descriptor = MTLRenderPipelineDescriptor()
            descriptor.label = "Theater Screen"
            descriptor.vertexFunction = library.makeFunction(name: "theaterScreenVertex")
            descriptor.fragmentFunction = library.makeFunction(name: "theaterScreenFragment")
            descriptor.colorAttachments[0].pixelFormat = .bgra8Unorm_srgb
            let pipeline = try device.makeRenderPipelineState(descriptor: descriptor)
            pipelines[key] = pipeline
            return pipeline
        } catch {
            debugPrint("[VisionTheaterScreenFeed] screen shader failed: \(error)")
            return nil
        }
    }

    /// Lets the screen go dark and frees the canvas once the theater closes.
    func reset() {
        isActive = false
        canvas = nil
        textureResource = nil
    }

    /// No mipmaps: `TheaterLayout.canvasRows` already matches the screen's
    /// size on the display, and RealityKit sampled the lower levels of a
    /// mipmapped canvas as blank.
    private func makeCanvas(width: Int, height: Int) throws -> LowLevelTexture {
        let descriptor = LowLevelTexture.Descriptor(
            textureType: .type2D,
            pixelFormat: .bgra8Unorm_srgb,
            width: width,
            height: height,
            mipmapLevelCount: 1,
            textureUsage: [.shaderRead, .renderTarget]
        )
        return try LowLevelTexture(descriptor: descriptor)
    }

    private func clear(_ canvas: LowLevelTexture) {
        guard let commandBuffer = MetalContext.shared?.commandQueue.makeCommandBuffer() else { return }
        let target = canvas.replace(using: commandBuffer)
        let pass = MTLRenderPassDescriptor()
        pass.colorAttachments[0].texture = target
        pass.colorAttachments[0].loadAction = .clear
        pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
        pass.colorAttachments[0].storeAction = .store
        commandBuffer.makeRenderCommandEncoder(descriptor: pass)?.endEncoding()
        commandBuffer.commit()
    }
}

#endif
