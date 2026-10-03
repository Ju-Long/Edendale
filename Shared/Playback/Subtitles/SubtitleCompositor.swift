//
//  SubtitleCompositor.swift
//  Edendale
//
//  Metal compute pipeline coordinator for compositing subtitle overlay textures
//  onto video frames.
//

import Foundation
import Metal

/// Manages the Metal compute pipeline for alpha-blending subtitle overlay textures onto video frames.
public final class SubtitleCompositor: @unchecked Sendable {
    private let device: MTLDevice
    private var pipelineState: MTLComputePipelineState?
    private var passthroughPipelineState: MTLComputePipelineState?

    private static let metalShaderSource = """
    #include <metal_stdlib>
    using namespace metal;

    kernel void compositeSubtitles(
        texture2d<float, access::read>  video     [[texture(0)]],
        texture2d<float, access::read>  subtitle  [[texture(1)]],
        texture2d<float, access::write> output    [[texture(2)]],
        uint2 gid [[thread_position_in_grid]])
    {
        if (gid.x >= output.get_width() || gid.y >= output.get_height()) {
            return;
        }

        float4 v = video.read(gid);
        float4 s = subtitle.read(gid);

        output.write(float4(mix(v.rgb, s.rgb, s.a), 1.0), gid);
    }

    kernel void passthroughVideo(
        texture2d<float, access::read>  video  [[texture(0)]],
        texture2d<float, access::write> output [[texture(1)]],
        uint2 gid [[thread_position_in_grid]])
    {
        if (gid.x >= output.get_width() || gid.y >= output.get_height()) {
            return;
        }
        output.write(video.read(gid), gid);
    }
    """

    public init(device: MTLDevice = MTLCreateSystemDefaultDevice()!) {
        self.device = device
        setupPipeline()
    }

    private func setupPipeline() {
        // Try loading from default library first; fall back to compiling embedded source
        var library = device.makeDefaultLibrary()
        if library?.makeFunction(name: "compositeSubtitles") == nil {
            library = try? device.makeLibrary(source: Self.metalShaderSource, options: nil)
        }

        guard let lib = library else {
            return
        }

        if let compositeFunc = lib.makeFunction(name: "compositeSubtitles") {
            pipelineState = try? device.makeComputePipelineState(function: compositeFunc)
        }
        if let passthroughFunc = lib.makeFunction(name: "passthroughVideo") {
            passthroughPipelineState = try? device.makeComputePipelineState(function: passthroughFunc)
        }
    }

    /// Composite a subtitle overlay texture over a video texture into an output texture.
    /// If `subtitle` is nil, passes the video frame through to output directly.
    public func composite(
        video: MTLTexture,
        subtitle: MTLTexture?,
        output: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) {
        if let subtitle, let pipelineState {
            guard let encoder = commandBuffer.makeComputeCommandEncoder() else { return }
            encoder.setComputePipelineState(pipelineState)
            encoder.setTexture(video, index: 0)
            encoder.setTexture(subtitle, index: 1)
            encoder.setTexture(output, index: 2)

            dispatch(encoder: encoder, pipelineState: pipelineState, width: output.width, height: output.height)
            encoder.endEncoding()
        } else if let passthroughPipelineState {
            guard let encoder = commandBuffer.makeComputeCommandEncoder() else { return }
            encoder.setComputePipelineState(passthroughPipelineState)
            encoder.setTexture(video, index: 0)
            encoder.setTexture(output, index: 1)

            dispatch(encoder: encoder, pipelineState: passthroughPipelineState, width: output.width, height: output.height)
            encoder.endEncoding()
        } else {
            // Fallback blit
            guard let blitEncoder = commandBuffer.makeBlitCommandEncoder() else { return }
            let copyWidth = min(video.width, output.width)
            let copyHeight = min(video.height, output.height)
            blitEncoder.copy(
                from: video,
                sourceSlice: 0,
                sourceLevel: 0,
                sourceOrigin: MTLOrigin(x: 0, y: 0, z: 0),
                sourceSize: MTLSize(width: copyWidth, height: copyHeight, depth: 1),
                to: output,
                destinationSlice: 0,
                destinationLevel: 0,
                destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0)
            )
            blitEncoder.endEncoding()
        }
    }

    private func dispatch(
        encoder: MTLComputeCommandEncoder,
        pipelineState: MTLComputePipelineState,
        width: Int,
        height: Int
    ) {
        let threadgroupSize = MTLSize(
            width: min(16, pipelineState.threadExecutionWidth),
            height: min(16, pipelineState.maxTotalThreadsPerThreadgroup / max(1, pipelineState.threadExecutionWidth)),
            depth: 1
        )
        let gridSize = MTLSize(width: width, height: height, depth: 1)

        if device.supportsFamily(.apple4) || device.supportsFamily(.mac2) {
            encoder.dispatchThreads(gridSize, threadsPerThreadgroup: threadgroupSize)
        } else {
            let threadgroupsPerGrid = MTLSize(
                width: (width + threadgroupSize.width - 1) / threadgroupSize.width,
                height: (height + threadgroupSize.height - 1) / threadgroupSize.height,
                depth: 1
            )
            encoder.dispatchThreadgroups(threadgroupsPerGrid, threadsPerThreadgroup: threadgroupSize)
        }
    }
}
