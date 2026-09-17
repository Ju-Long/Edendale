//
//  FormatRouter.swift
//  Edendale
//
//  Probes container formats, network protocols, and stream codecs to select
//  between AVFoundation and FFmpeg playback pipelines.
//

import AVFoundation
import CoreAudio
import CoreMedia
import Foundation

public enum DecoderKind: Sendable, Equatable {
    case avFoundation
    case ffmpeg
}

public struct FormatRouter: Sendable {

    /// Known container extensions that AVFoundation cannot demux or play reliably.
    /// These are routed to FFmpeg immediately without asset probing.
    public static let ffmpegImmediateExtensions: Set<String> = [
        "mkv", "avi", "flv", "webm", "ts", "m2ts", "mts", "wmv",
        "vob", "ogv", "rm", "rmvb", "divx", "xvid", "asf", "f4v",
        "mpg", "mpeg", "3gp"
    ]

    /// Containers that AVFoundation can parse and decode natively.
    public static let avFoundationContainerExtensions: Set<String> = [
        "mp4", "m4v", "mov"
    ]

    /// Probe the URL and decide which decoder to use.
    /// AVFoundation: MP4/MOV/M4V + H.264/HEVC/ProRes/AV1
    /// FFmpeg: everything else (MKV, AVI, TS, VP9, DTS audio, SMB, etc.)
    public static func route(_ url: URL) async -> DecoderKind {
        // 1. Network protocol check: SMB cannot be opened by AVFoundation.
        if isSMB(url: url) {
            return .ffmpeg
        }

        let fileExtension = url.pathExtension.lowercased()

        // 2. Fast container check: non-AVFoundation formats go straight to FFmpeg.
        if ffmpegImmediateExtensions.contains(fileExtension) {
            return .ffmpeg
        }

        // If it is not a known AVFoundation container and has an extension, fallback to FFmpeg.
        if !fileExtension.isEmpty && !avFoundationContainerExtensions.contains(fileExtension) {
            return .ffmpeg
        }

        // 3. Deep probe with AVURLAsset for MP4 / MOV / M4V or extensionless media.
        return await probeAsset(at: url)
    }

    // MARK: - Internal Probing

    public static func isSMB(url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased() else { return false }
        return scheme == "smb" || scheme == "smb2"
    }

    private static func probeAsset(at url: URL) async -> DecoderKind {
        let asset = AVURLAsset(url: url)

        guard let isPlayable = try? await asset.load(.isPlayable), isPlayable else {
            return .ffmpeg
        }

        guard let tracks = try? await asset.load(.tracks), !tracks.isEmpty else {
            return .ffmpeg
        }

        // Inspect video tracks
        let videoTracks = tracks.filter { $0.mediaType == .video }
        for track in videoTracks {
            guard let descriptions = try? await track.load(.formatDescriptions), !descriptions.isEmpty else {
                return .ffmpeg
            }
            for desc in descriptions {
                let subType = desc.mediaSubType.rawValue
                if isUnsupportedVideoCodec(subType) {
                    return .ffmpeg
                }
            }
        }

        // Inspect audio tracks
        let audioTracks = tracks.filter { $0.mediaType == .audio }
        for track in audioTracks {
            guard let descriptions = try? await track.load(.formatDescriptions) else {
                continue
            }
            for desc in descriptions {
                let subType = desc.mediaSubType.rawValue
                if isUnsupportedAudioCodec(subType) {
                    return .ffmpeg
                }
            }
        }

        return .avFoundation
    }

    // MARK: - Codec Evaluation

    public static func isSupportedAVFoundationVideoCodec(_ subType: FourCharCode) -> Bool {
        switch subType {
        case kCMVideoCodecType_H264,
             kCMVideoCodecType_HEVC,
             kCMVideoCodecType_HEVCWithAlpha,
             kCMVideoCodecType_AppleProRes422,
             kCMVideoCodecType_AppleProRes422HQ,
             kCMVideoCodecType_AppleProRes422LT,
             kCMVideoCodecType_AppleProRes422Proxy,
             kCMVideoCodecType_AppleProRes4444,
             kCMVideoCodecType_AppleProRes4444XQ,
             kCMVideoCodecType_AV1,
             kCMVideoCodecType_MPEG4Video:
            return true
        default:
            let code = fourCharCodeToString(subType).lowercased()
            return code == "avc1" || code == "avc3" || code == "hvc1" || code == "hev1" || code == "av01"
        }
    }

    public static func isUnsupportedVideoCodec(_ subType: FourCharCode) -> Bool {
        let code = fourCharCodeToString(subType).lowercased()
        if code == "vp08" || code == "vp09" || code == "vp8" || code == "vp9" {
            return true
        }
        return !isSupportedAVFoundationVideoCodec(subType)
    }

    public static func isDTSAudio(_ subType: FourCharCode) -> Bool {
        let code = fourCharCodeToString(subType).lowercased()
        return code.starts(with: "dts")
    }

    public static func isUnsupportedAudioCodec(_ subType: FourCharCode) -> Bool {
        if isDTSAudio(subType) {
            return true
        }
        let code = fourCharCodeToString(subType).lowercased()
        if code == "trhd" || code == "mlp " || code == "vorb" || code.starts(with: "wma") {
            return true
        }
        return false
    }

    public static func fourCharCodeToString(_ code: FourCharCode) -> String {
        let bytes: [UInt8] = [
            UInt8((code >> 24) & 0xff),
            UInt8((code >> 16) & 0xff),
            UInt8((code >> 8) & 0xff),
            UInt8(code & 0xff)
        ]
        return String(bytes: bytes, encoding: .ascii)?.trimmingCharacters(in: .whitespaces) ?? ""
    }
}
