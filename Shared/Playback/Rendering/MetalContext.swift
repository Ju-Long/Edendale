//
//  MetalContext.swift
//  Edendale
//
//  Metal rendering context and shared pipeline state for video presentation.
//

import Foundation
import CoreGraphics
import Metal
import MetalKit

/// Aspect ratio scaling mode for video rendering.
public enum VideoAspectMode: String, CaseIterable, Identifiable, Sendable {
    case fit  // Letterbox / pillarbox preserving full video content
    case fill // Aspect fill cropping overflow to cover the entire container

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .fit: String(localized: "Fit")
        case .fill: String(localized: "Fill")
        }
    }
}

/// A 2D vertex containing screen-space normalized device coordinates and texture coordinates.
public struct MetalQuadVertex {
    public var position: SIMD2<Float>  // (-1...1, -1...1)
    public var texCoords: SIMD2<Float> // (0...1, 0...1)

    public init(position: SIMD2<Float>, texCoords: SIMD2<Float>) {
        self.position = position
        self.texCoords = texCoords
    }
}

/// Shared Metal device, command queue, and render pipelines for video rendering surfaces.
public final class MetalContext: @unchecked Sendable {
    public static let shared = MetalContext()

    public let device: MTLDevice
    public let commandQueue: MTLCommandQueue
    public let samplerState: MTLSamplerState

    private let library: MTLLibrary
    private let pipelineLock = NSLock()
    private var pipelineStates: [MTLPixelFormat: MTLRenderPipelineState] = [:]

    public init?(device: MTLDevice? = MTLCreateSystemDefaultDevice()) {
        guard let device else { return nil }
        self.device = device

        guard let queue = device.makeCommandQueue() else { return nil }
        self.commandQueue = queue

        // Sampler for linear texture filtering with clamp-to-edge
        let samplerDescriptor = MTLSamplerDescriptor()
        samplerDescriptor.minFilter = .linear
        samplerDescriptor.magFilter = .linear
        samplerDescriptor.mipFilter = .notMipmapped
        samplerDescriptor.sAddressMode = .clampToEdge
        samplerDescriptor.tAddressMode = .clampToEdge
        guard let sampler = device.makeSamplerState(descriptor: samplerDescriptor) else {
            return nil
        }
        self.samplerState = sampler

        // Compile or load quad blit shader library
        if let defaultLib = device.makeDefaultLibrary(),
           defaultLib.functionNames.contains("quadVertex") {
            self.library = defaultLib
        } else if let compiledLib = try? device.makeLibrary(source: Self.embeddedShaderSource, options: nil) {
            self.library = compiledLib
        } else {
            return nil
        }
    }

    /// Retrieves or builds an `MTLRenderPipelineState` matching the given drawable color pixel format.
    public func renderPipelineState(for pixelFormat: MTLPixelFormat) -> MTLRenderPipelineState? {
        pipelineLock.lock()
        defer { pipelineLock.unlock() }

        if let existing = pipelineStates[pixelFormat] {
            return existing
        }

        guard let vertexFunction = library.makeFunction(name: "quadVertex"),
              let fragmentFunction = library.makeFunction(name: "quadFragment") else {
            return nil
        }

        let pipelineDescriptor = MTLRenderPipelineDescriptor()
        pipelineDescriptor.label = "Video Quad Pipeline (\(pixelFormat))"
        pipelineDescriptor.vertexFunction = vertexFunction
        pipelineDescriptor.fragmentFunction = fragmentFunction
        pipelineDescriptor.colorAttachments[0].pixelFormat = pixelFormat

        // Optional standard alpha blending
        let attachment = pipelineDescriptor.colorAttachments[0]
        attachment?.isBlendingEnabled = true
        attachment?.rgbBlendOperation = .add
        attachment?.alphaBlendOperation = .add
        attachment?.sourceRGBBlendFactor = .sourceAlpha
        attachment?.sourceAlphaBlendFactor = .sourceAlpha
        attachment?.destinationRGBBlendFactor = .oneMinusSourceAlpha
        attachment?.destinationAlphaBlendFactor = .oneMinusSourceAlpha

        guard let pipelineState = try? device.makeRenderPipelineState(descriptor: pipelineDescriptor) else {
            return nil
        }

        pipelineStates[pixelFormat] = pipelineState
        return pipelineState
    }

    /// Computes 4 vertices (two triangles forming a quad) for the given container size and content size,
    /// applying aspect-ratio fitting or filling.
    public static func computeQuadVertices(
        containerSize: CGSize,
        contentSize: CGSize,
        aspectMode: VideoAspectMode
    ) -> [MetalQuadVertex] {
        guard containerSize.width > 0, containerSize.height > 0,
              contentSize.width > 0, contentSize.height > 0 else {
            // Default full-screen quad (-1 to 1)
            return defaultQuad()
        }

        let containerAspect = Float(containerSize.width / containerSize.height)
        let contentAspect = Float(contentSize.width / contentSize.height)

        var scaleX: Float = 1.0
        var scaleY: Float = 1.0

        switch aspectMode {
        case .fit:
            if contentAspect > containerAspect {
                // Video is wider than container: letterbox (top and bottom black bars)
                scaleX = 1.0
                scaleY = containerAspect / contentAspect
            } else {
                // Video is taller than container: pillarbox (left and right black bars)
                scaleX = contentAspect / containerAspect
                scaleY = 1.0
            }

        case .fill:
            if contentAspect > containerAspect {
                // Video is wider than container: scale height to 1.0, width extends outside container
                scaleX = contentAspect / containerAspect
                scaleY = 1.0
            } else {
                // Video is taller than container: scale width to 1.0, height extends outside container
                scaleX = 1.0
                scaleY = containerAspect / contentAspect
            }
        }

        // Two triangles forming a quad: (V0, V1, V2) and (V2, V3, V0)
        // V0: Top-Left (-scaleX, scaleY) -> UV (0, 0)
        // V1: Bottom-Left (-scaleX, -scaleY) -> UV (0, 1)
        // V2: Bottom-Right (scaleX, -scaleY) -> UV (1, 1)
        // V3: Top-Right (scaleX, scaleY) -> UV (1, 0)
        return [
            MetalQuadVertex(position: SIMD2<Float>(-scaleX, scaleY), texCoords: SIMD2<Float>(0.0, 0.0)),
            MetalQuadVertex(position: SIMD2<Float>(-scaleX, -scaleY), texCoords: SIMD2<Float>(0.0, 1.0)),
            MetalQuadVertex(position: SIMD2<Float>(scaleX, -scaleY), texCoords: SIMD2<Float>(1.0, 1.0)),

            MetalQuadVertex(position: SIMD2<Float>(scaleX, -scaleY), texCoords: SIMD2<Float>(1.0, 1.0)),
            MetalQuadVertex(position: SIMD2<Float>(scaleX, scaleY), texCoords: SIMD2<Float>(1.0, 0.0)),
            MetalQuadVertex(position: SIMD2<Float>(-scaleX, scaleY), texCoords: SIMD2<Float>(0.0, 0.0))
        ]
    }

    private static func defaultQuad() -> [MetalQuadVertex] {
        [
            MetalQuadVertex(position: SIMD2<Float>(-1.0, 1.0), texCoords: SIMD2<Float>(0.0, 0.0)),
            MetalQuadVertex(position: SIMD2<Float>(-1.0, -1.0), texCoords: SIMD2<Float>(0.0, 1.0)),
            MetalQuadVertex(position: SIMD2<Float>(1.0, -1.0), texCoords: SIMD2<Float>(1.0, 1.0)),

            MetalQuadVertex(position: SIMD2<Float>(1.0, -1.0), texCoords: SIMD2<Float>(1.0, 1.0)),
            MetalQuadVertex(position: SIMD2<Float>(1.0, 1.0), texCoords: SIMD2<Float>(1.0, 0.0)),
            MetalQuadVertex(position: SIMD2<Float>(-1.0, 1.0), texCoords: SIMD2<Float>(0.0, 0.0))
        ]
    }

    private static let embeddedShaderSource = """
    #include <metal_stdlib>
    using namespace metal;

    struct MetalQuadVertex {
        float2 position;
        float2 texCoords;
    };

    struct VertexOut {
        float4 position [[position]];
        float2 texCoords;
    };

    vertex VertexOut quadVertex(
        uint vertexID [[vertex_id]],
        constant MetalQuadVertex *vertices [[buffer(0)]]
    ) {
        VertexOut out;
        out.position = float4(vertices[vertexID].position, 0.0, 1.0);
        out.texCoords = vertices[vertexID].texCoords;
        return out;
    }

    fragment float4 quadFragment(
        VertexOut in [[stage_in]],
        texture2d<float> colorTexture [[texture(0)]],
        sampler textureSampler        [[sampler(0)]]
    ) {
        return colorTexture.sample(textureSampler, in.texCoords);
    }
    """
}
