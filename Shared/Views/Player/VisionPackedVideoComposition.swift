//
//  VisionPackedVideoComposition.swift
//  Edendale
//
//  Presents an untagged packed stereo frame to AVKit without transcoding it.
//  The compositor reuses AVFoundation's decoded pixel buffer and adds the
//  view-packing metadata visionOS needs to interpret it in real time.
//

#if os(visionOS)
import AVFoundation
import CoreImage
import CoreMedia
import CoreVideo
import Foundation

@available(visionOS 26.0, *)
nonisolated enum VisionPackedVideoComposition {
    /// Builds a custom composition that keeps the source's full packed frame
    /// size and describes how visionOS should divide and project that frame.
    static func make(
        asset: AVAsset,
        layout: VisionFormatLayout
    ) async throws -> AVVideoComposition {
        let outputTags = try layout.outputTags
        var configuration = try await AVVideoComposition.Configuration(for: asset)
        let preferredTransform = try await asset.loadTracks(withMediaType: .video)
            .first?.load(.preferredTransform) ?? .identity

        // Configuration(for:) carries the asset's oriented natural render size.
        // Keep the full packed frame; the packing tag, rather than a crop or
        // intermediate render, tells visionOS how the two views are arranged.
        let packedRenderSize = configuration.renderSize
        configuration.customVideoCompositorClass = VisionPackedVideoCompositor.self
        configuration.instructions = configuration.instructions.map {
            VisionPackedVideoCompositionInstruction(
                wrapping: $0,
                outputTags: outputTags,
                preferredTransform: preferredTransform
            )
        }
        configuration.outputBufferDescription = [outputTags]
        configuration.renderSize = packedRenderSize

        return AVVideoComposition(configuration: configuration)
    }
}

@available(visionOS 26.0, *)
nonisolated final class VisionPackedVideoCompositor: NSObject, AVVideoCompositing {
    private let imageContext = CIContext(options: [.cacheIntermediates: false])

    func startRequest(_ request: AVAsynchronousVideoCompositionRequest) {
        guard let instruction = request.videoCompositionInstruction
            as? VisionPackedVideoCompositionInstruction,
              let sourceTrackNumber = request.sourceTrackIDs.first
        else {
            request.finish(with: VisionPackedVideoCompositionError.invalidRequest)
            return
        }

        let sourceTrackID = CMPersistentTrackID(truncating: sourceTrackNumber)
        guard let sourcePixelBuffer = request.sourceFrame(byTrackID: sourceTrackID) else {
            request.finish(with: VisionPackedVideoCompositionError.missingSourceFrame)
            return
        }

        let requiredSize = request.renderContext.size
        let sourceMatchesRenderSize = CVPixelBufferGetWidth(sourcePixelBuffer)
                == Int(requiredSize.width.rounded())
            && CVPixelBufferGetHeight(sourcePixelBuffer)
                == Int(requiredSize.height.rounded())

        let outputPixelBuffer: CVPixelBuffer
        if instruction.preferredTransform.isIdentity && sourceMatchesRenderSize {
            // Fast path: no crop, scale, copy, or duplicate decode. The same
            // read-only decoder output is handed back with spatial tags.
            outputPixelBuffer = sourcePixelBuffer
        } else {
            // AVVideoComposition's render size is already display-oriented.
            // Apply the source track matrix for rotated packed files (or
            // reconcile a clean-aperture size mismatch) so the returned frame
            // agrees with that render size.
            guard let transformedPixelBuffer = request.renderContext.newPixelBuffer() else {
                request.finish(
                    with: VisionPackedVideoCompositionError.failedToCreateOutputFrame
                )
                return
            }

            let sourceImage = CIImage(cvPixelBuffer: sourcePixelBuffer)
                .transformed(by: instruction.preferredTransform)
            let normalizedImage = sourceImage.transformed(
                by: CGAffineTransform(
                    translationX: -sourceImage.extent.minX,
                    y: -sourceImage.extent.minY
                )
            )
            let outputBounds = CGRect(
                x: 0,
                y: 0,
                width: CVPixelBufferGetWidth(transformedPixelBuffer),
                height: CVPixelBufferGetHeight(transformedPixelBuffer)
            )
            imageContext.render(
                normalizedImage,
                to: transformedPixelBuffer,
                bounds: outputBounds,
                colorSpace: nil
            )
            outputPixelBuffer = transformedPixelBuffer
        }

        let outputBuffer = CMTaggedDynamicBuffer(
            tags: instruction.outputTags,
            content: CVReadOnlyPixelBuffer(unsafeBuffer: outputPixelBuffer)
        )
        request.finish(withComposedTaggedBuffers: [outputBuffer])
    }

    func renderContextChanged(_ newRenderContext: AVVideoCompositionRenderContext) {
        // Requests obtain any transform buffer from their current render
        // context, so there is no context-specific state to rebuild here.
    }

    let supportsHDRSourceFrames = true
    let supportsSourceTaggedBuffers = false

    var sourcePixelBufferAttributes: [String: any Sendable]? {
        VisionPackedVideoCompositor.pixelBufferAttributes
    }

    var requiredPixelBufferAttributesForRenderContext: [String: any Sendable] {
        VisionPackedVideoCompositor.pixelBufferAttributes
    }

    private static let pixelBufferAttributes: [String: any Sendable] = [
        kCVPixelBufferPixelFormatTypeKey as String: [
            kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
            kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange,
            kCVPixelFormatType_420YpCbCr10BiPlanarFullRange,
            kCVPixelFormatType_32BGRA
        ]
    ]
}

@available(visionOS 26.0, *)
nonisolated private final class VisionPackedVideoCompositionInstruction:
    NSObject,
    AVVideoCompositionInstructionProtocol {
    private let wrappedInstruction: any AVVideoCompositionInstructionProtocol
    let outputTags: [CMTag]
    let preferredTransform: CGAffineTransform

    init(
        wrapping instruction: any AVVideoCompositionInstructionProtocol,
        outputTags: [CMTag],
        preferredTransform: CGAffineTransform
    ) {
        wrappedInstruction = instruction
        self.outputTags = outputTags
        self.preferredTransform = preferredTransform
    }

    var timeRange: CMTimeRange { wrappedInstruction.timeRange }
    var enablePostProcessing: Bool { wrappedInstruction.enablePostProcessing }
    var containsTweening: Bool { wrappedInstruction.containsTweening }
    var requiredSourceTrackIDs: [NSValue]? { wrappedInstruction.requiredSourceTrackIDs }
    var passthroughTrackID: CMPersistentTrackID { wrappedInstruction.passthroughTrackID }
}

@available(visionOS 26.0, *)
nonisolated private extension VisionFormatLayout {
    var outputTags: [CMTag] {
        get throws {
            let packingTag: CMTag
            switch packing {
            case .sideBySide:
                packingTag = .packingType(.sideBySide)
            case .overUnder:
                packingTag = .packingType(.overUnder)
            case .none:
                throw VisionPackedVideoCompositionError.requiresPackedStereoLayout
            }

            let projectionTag: CMTag
            switch projection {
            case .rectilinear:
                projectionTag = .projectionType(.rectangular)
            case .halfEquirectangular:
                projectionTag = .projectionType(.halfEquirectangular)
            case .equirectangular:
                projectionTag = .projectionType(.equirectangular)
            }

            var tags: [CMTag] = [
                .stereoView([.leftEye, .rightEye]),
                packingTag,
                projectionTag,
                .mediaType(.video)
            ]
            if eyeOrder == .reversed {
                tags.append(.stereoViewInterpretation(.stereoOrderReversed))
            }
            return tags
        }
    }
}

@available(visionOS 26.0, *)
nonisolated private enum VisionPackedVideoCompositionError: LocalizedError {
    case requiresPackedStereoLayout
    case invalidRequest
    case missingSourceFrame
    case failedToCreateOutputFrame

    var errorDescription: String? {
        switch self {
        case .requiresPackedStereoLayout:
            String(localized: "The selected vision format is not a packed stereo layout.")
        case .invalidRequest:
            String(localized: "AVFoundation did not provide a valid packed-video composition request.")
        case .missingSourceFrame:
            String(localized: "AVFoundation could not decode the packed-video source frame.")
        case .failedToCreateOutputFrame:
            String(localized: "AVFoundation could not allocate a transformed packed-video frame.")
        }
    }
}
#endif
