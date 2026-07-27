#if os(visionOS)

import AVFoundation
import Foundation

/// AVFoundation playback capabilities that matter when choosing the native
/// visionOS player instead of Edendale's broad-format VLC player.
struct VisionMediaInspection: Equatable, Sendable {
    let isPlayable: Bool
    let isStereoVideo: Bool
    let isStereoMultiviewVideo: Bool
    let isSpatialVideo: Bool
    let usesNonRectilinearProjection: Bool
    let isAppleImmersiveVideo: Bool

    /// Native AVKit is required for the system's stereoscopic, spatial, and
    /// immersive presentation paths. Ordinary 2D and unsupported files can
    /// continue through SwiftVLC.
    var prefersNativeAVKitPlayback: Bool {
        isPlayable
            && (isStereoVideo
                || isStereoMultiviewVideo
                || isSpatialVideo
                || usesNonRectilinearProjection
                || isAppleImmersiveVideo)
    }
}

enum VisionMediaInspectionError: LocalizedError, Sendable {
    case missingURL

    var errorDescription: String? {
        switch self {
        case .missingURL:
            String(localized: "The selected item has no playable URL.")
        }
    }
}

/// Creates every visionOS playback asset with the same metadata policy. On
/// visionOS 26, AVFoundation can translate compatible Spherical Metadata V1/V2
/// tags into Apple Projected Media Profile signalling in memory.
enum VisionMediaAssetFactory {
    static func make(url: URL) -> AVURLAsset {
        if #available(visionOS 26.0, *) {
            return AVURLAsset(
                url: url,
                options: [AVURLAssetShouldParseExternalSphericalTagsKey: true]
            )
        }
        return AVURLAsset(url: url)
    }
}

/// Inspects an asset without starting playback. Keep the `PlaybackItem` alive
/// until routing completes so its security-scoped access remains active.
enum VisionMediaInspector {
    static func inspect(_ playbackItem: PlaybackItem) async throws -> VisionMediaInspection {
        guard let url = playbackItem.url else {
            throw VisionMediaInspectionError.missingURL
        }
        return try await inspect(url: url)
    }

    static func inspect(url: URL) async throws -> VisionMediaInspection {
        let asset = VisionMediaAssetFactory.make(url: url)
        let isPlayable = try await asset.load(.isPlayable)
        let assistant = AVAssetPlaybackAssistant(asset: asset)
        let options = await assistant.playbackConfigurationOptions

        let usesNonRectilinearProjection: Bool
        let isAppleImmersiveVideo: Bool
        if #available(visionOS 26.0, *) {
            usesNonRectilinearProjection = options.contains(.nonRectilinearProjection)
            isAppleImmersiveVideo = options.contains(.appleImmersiveVideo)
        } else {
            usesNonRectilinearProjection = false
            isAppleImmersiveVideo = false
        }

        return VisionMediaInspection(
            isPlayable: isPlayable,
            isStereoVideo: options.contains(.stereoVideo),
            isStereoMultiviewVideo: options.contains(.stereoMultiviewVideo),
            isSpatialVideo: options.contains(.spatialVideo),
            usesNonRectilinearProjection: usesNonRectilinearProjection,
            isAppleImmersiveVideo: isAppleImmersiveVideo
        )
    }
}

#endif
