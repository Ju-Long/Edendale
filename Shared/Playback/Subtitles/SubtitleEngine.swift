//
//  SubtitleEngine.swift
//  Edendale
//
//  Unified subtitle engine managing text-based (ASS, SSA, SRT, WebVTT)
//  and image-based (PGS, VobSub) subtitles and compositing them into the Metal pipeline.
//

import CoreGraphics
import CoreMedia
import Foundation
import Metal
import Observation

/// Unified controller orchestrating subtitle decoders, renderers, and Metal compositing.
@Observable
public final class SubtitleEngine: @unchecked Sendable {
    public let device: MTLDevice

    public let assRenderer: AssRenderer
    public let timedTextRenderer: TimedTextRenderer
    public let imageSubtitleRenderer: ImageSubtitleRenderer
    public let compositor: SubtitleCompositor

    public private(set) var activeFormat: SubtitleTrackFormat?
    public private(set) var canvasSize: CGSize = CGSize(width: 1920, height: 1080)
    public private(set) var revision: UInt = 0
    public var isEnabled: Bool = true {
        didSet { if isEnabled != oldValue { revision &+= 1 } }
    }

    public init(device: MTLDevice = MTLCreateSystemDefaultDevice()!) {
        self.device = device
        self.assRenderer = AssRenderer(device: device)
        self.timedTextRenderer = TimedTextRenderer(device: device)
        self.imageSubtitleRenderer = ImageSubtitleRenderer(device: device)
        self.compositor = SubtitleCompositor(device: device)
    }

    /// Select or disable the active subtitle track format.
    public func selectFormat(_ format: SubtitleTrackFormat?) {
        guard activeFormat != format else { return }
        activeFormat = format
        revision &+= 1
    }

    /// Update frame / canvas resolution.
    public func setCanvasSize(_ size: CGSize) {
        guard size.width > 0, size.height > 0 else { return }
        canvasSize = size
        assRenderer.setFrameSize(size)
        timedTextRenderer.setFrameSize(size)
        imageSubtitleRenderer.setFrameSize(size)
    }

    /// Ingest a text-based subtitle event.
    public func addEvent(_ event: DecodedSubtitleEvent) {
        revision &+= 1
        switch activeFormat {
        case .ass, .ssa:
            timedTextRenderer.addAssEvent(event)
            if AssRenderer.isAvailable {
                assRenderer.addEvent(event)
            }
        case .srt, .webvtt:
            timedTextRenderer.addEvent(event)
        default:
            // Auto-detect format if unset
            let isAssChunk = TimedTextRenderer.extractDialogueText(from: event.text) != event.text
            if event.text.contains("Dialogue:") || event.text.contains("[Script Info]") || isAssChunk {
                timedTextRenderer.addAssEvent(event)
                if AssRenderer.isAvailable {
                    assRenderer.addEvent(event)
                }
            } else {
                timedTextRenderer.addEvent(event)
            }
        }
    }

    /// Ingest full ASS script file or header data.
    public func addAssScriptData(_ data: Data) {
        revision &+= 1
        if let script = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) {
            timedTextRenderer.loadAssScript(from: script)
        }
        if AssRenderer.isAvailable {
            assRenderer.addScriptData(data)
        }
    }

    /// Ingest full SRT or WebVTT content.
    public func loadTimedText(from string: String) {
        revision &+= 1
        timedTextRenderer.loadSubtitles(from: string)
    }

    /// Ingest an image-based subtitle cue (PGS, VobSub).
    public func addImageCue(_ cue: ImageSubtitleCue) {
        revision &+= 1
        imageSubtitleRenderer.addCue(cue)
    }

    /// Reset all renderers and cues.
    public func reset() {
        revision &+= 1
        assRenderer.reset()
        timedTextRenderer.reset()
        imageSubtitleRenderer.reset()
    }

    /// Reading revision makes paused track/cue changes observable to SwiftUI.
    public func activeTextCues(at time: CMTime) -> [TimedTextRenderer.TimedTextCue] {
        _ = revision
        guard isEnabled, let activeFormat, !activeFormat.isImageBased else { return [] }
        return timedTextRenderer.activeCues(at: time)
    }

    public func activeImageCues(at time: CMTime) -> [ImageSubtitleCue] {
        _ = revision
        guard isEnabled, activeFormat?.isImageBased == true else { return [] }
        return imageSubtitleRenderer.activeCues(at: time)
    }

    /// Render active subtitles at the given playback time into an overlay texture.
    /// Returns `nil` if subtitles are disabled or no cues are active.
    public func renderSubtitleTexture(at time: CMTime) -> MTLTexture? {
        guard isEnabled, time.isValid, time.isNumeric, let activeFormat else { return nil }

        switch activeFormat {
        case .ass, .ssa:
            if AssRenderer.isAvailable {
                return assRenderer.render(at: time)
            } else {
                return timedTextRenderer.render(at: time)
            }
        case .srt, .webvtt:
            return timedTextRenderer.render(at: time)
        case .pgs, .vobsub:
            return imageSubtitleRenderer.render(at: time)
        }
    }

    /// Composite active subtitles onto a video frame texture and write into `output`.
    public func composite(
        video: MTLTexture,
        at time: CMTime,
        output: MTLTexture,
        commandBuffer: MTLCommandBuffer
    ) {
        let subtitleTexture = renderSubtitleTexture(at: time)
        compositor.composite(
            video: video,
            subtitle: subtitleTexture,
            output: output,
            commandBuffer: commandBuffer
        )
    }
}
