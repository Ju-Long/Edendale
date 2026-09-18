//
//  SubtitleEngineTests.swift
//  EdendaleTests
//
//  Unit tests covering Section F of the Video Enhancement Pipeline:
//  ASS/SSA, SRT/WebVTT Core Text rendering, PGS/VobSub image subtitles,
//  Metal compute compositing, and unified SubtitleEngine.
//

import CoreGraphics
import CoreMedia
@testable import Edendale
import Metal
import Testing

#if canImport(AppKit) && !targetEnvironment(macCatalyst)
import AppKit
#elseif canImport(UIKit)
import UIKit
#endif

@Suite("Subtitle Engine Tests")
struct SubtitleEngineTests {
    @Test func assPacketFallbackRendersOnlyDialogueText() {
        let renderer = TimedTextRenderer(device: device)
        renderer.setFrameSize(CGSize(width: 320, height: 180))
        let start = CMTime(seconds: 1, preferredTimescale: 600)
        let end = CMTime(seconds: 3, preferredTimescale: 600)
        renderer.addAssEvent(DecodedSubtitleEvent(
            text: "0,0,Default,,0,0,0,,Hello, world!", start: start, end: end))
        let actual = renderer.render(at: start)
        let reference = TimedTextRenderer(device: device)
        reference.setFrameSize(CGSize(width: 320, height: 180))
        reference.addEvent(DecodedSubtitleEvent(text: "Hello, world!", start: start, end: end))
        let expected = reference.render(at: start)
        var actualPixels = [UInt8](repeating: 0, count: 320 * 180 * 4)
        var expectedPixels = actualPixels
        actual?.getBytes(&actualPixels, bytesPerRow: 320 * 4, from: MTLRegionMake2D(0, 0, 320, 180), mipmapLevel: 0)
        expected?.getBytes(&expectedPixels, bytesPerRow: 320 * 4, from: MTLRegionMake2D(0, 0, 320, 180), mipmapLevel: 0)
        #expect(actual != nil)
        #expect(actualPixels == expectedPixels)
    }

    @Test func assPacketWithEmptyLayerRendersOnlyDialogueText() {
        let renderer = TimedTextRenderer(device: device)
        renderer.setFrameSize(CGSize(width: 320, height: 180))
        let start = CMTime(seconds: 1, preferredTimescale: 600)
        let end = CMTime(seconds: 3, preferredTimescale: 600)
        // In Matroska / FFmpeg ASS streams, Layer is frequently empty (e.g. "45,,Default,,0,0,0,,")
        renderer.addAssEvent(DecodedSubtitleEvent(
            text: "45,,Default,,0,0,0,,Only the dialogue text", start: start, end: end))

        let cues = renderer.activeCues(at: start)
        #expect(cues.count == 1)
        #expect(cues.first?.rawText == "Only the dialogue text")

        let attr = renderer.buildAttributedString(from: cues.first!.rawText)
        #expect(attr.string == "Only the dialogue text")

        let actual = renderer.render(at: start)
        let reference = TimedTextRenderer(device: device)
        reference.setFrameSize(CGSize(width: 320, height: 180))
        reference.addEvent(DecodedSubtitleEvent(text: "Only the dialogue text", start: start, end: end))
        let expected = reference.render(at: start)

        var actualPixels = [UInt8](repeating: 0, count: 320 * 180 * 4)
        var expectedPixels = actualPixels
        actual?.getBytes(&actualPixels, bytesPerRow: 320 * 4, from: MTLRegionMake2D(0, 0, 320, 180), mipmapLevel: 0)
        expected?.getBytes(&expectedPixels, bytesPerRow: 320 * 4, from: MTLRegionMake2D(0, 0, 320, 180), mipmapLevel: 0)
        #expect(actual != nil)
        #expect(actualPixels == expectedPixels)
    }

    @Test func assPacketWithTagsAndHardSpaceExtractsCleanText() {
        let renderer = TimedTextRenderer(device: device)
        let cleaned = TimedTextRenderer.extractDialogueText(from: "45,,Default,,0,0,0,,{\\b1}Bold text{\\b0}\\hwith\\Nnewline")
        #expect(cleaned == "{\\b1}Bold text{\\b0}\\hwith\\Nnewline")

        let attr = renderer.buildAttributedString(from: cleaned)
        #expect(attr.string == "Bold text with\nnewline")
    }

    @Test func subtitleEngineAutoDetectsAndStripsAssChunk() {
        let engine = SubtitleEngine(device: device)
        let start = CMTime(seconds: 1, preferredTimescale: 600)
        let end = CMTime(seconds: 3, preferredTimescale: 600)
        engine.selectFormat(.ass)
        engine.addEvent(DecodedSubtitleEvent(
            text: "45,,Default,,0,0,0,,Subtitle without metadata prefix",
            start: start,
            end: end
        ))

        let cues = engine.activeTextCues(at: start)
        #expect(cues.count == 1)
        #expect(cues.first?.rawText == "Subtitle without metadata prefix")
    }

    private var device: MTLDevice {
        guard let dev = MTLCreateSystemDefaultDevice() else {
            fatalError("Metal device required for Subtitle Engine tests")
        }
        return dev
    }

    // MARK: - TimedTextRenderer (SRT / WebVTT) Tests

    @Test func srtParserExtractsCuesAndTimestamps() {
        let renderer = TimedTextRenderer(device: device)
        let srtContent = """
        1
        00:00:01,500 --> 00:00:04,200
        Hello <b>world</b>!

        2
        00:00:05,000 --> 00:00:08,750
        Second line of subtitles.
        With a second line.
        """

        let cues = renderer.parse(srtContent)
        #expect(cues.count == 2)

        #expect(cues[0].id == 1)
        #expect(abs(cues[0].start.seconds - 1.5) < 0.001)
        #expect(abs(cues[0].end.seconds - 4.2) < 0.001)
        #expect(cues[0].rawText == "Hello <b>world</b>!")

        #expect(cues[1].id == 2)
        #expect(abs(cues[1].start.seconds - 5.0) < 0.001)
        #expect(abs(cues[1].end.seconds - 8.75) < 0.001)
        #expect(cues[1].rawText.contains("Second line of subtitles."))
    }

    @Test func webVttParserExtractsCuesWithDecimalPoints() {
        let renderer = TimedTextRenderer(device: device)
        let vttContent = """
        WEBVTT - Sample WebVTT file

        00:01:10.100 --> 00:01:15.500
        This is <i>italic</i> and <font color="#ff0000">red</font>.
        """

        let cues = renderer.parse(vttContent)
        #expect(cues.count == 1)
        #expect(abs(cues[0].start.seconds - 70.1) < 0.001)
        #expect(abs(cues[0].end.seconds - 75.5) < 0.001)
        #expect(cues[0].rawText.contains("This is <i>italic</i>"))
    }

    @Test func timedTextMarkupBuildsStyledAttributedString() {
        let renderer = TimedTextRenderer(device: device)
        renderer.setFrameSize(CGSize(width: 1920, height: 1080))

        let rawMarkup = "Normal <b>Bold</b> <i>Italic</i> <font color=\"#ff0000\">Red</font> {\\b1}ASSBold{\\b0}"
        let attr = renderer.buildAttributedString(from: rawMarkup)

        #expect(attr.length > 0)
        #expect(attr.string.contains("Normal"))
        #expect(attr.string.contains("Bold"))
        #expect(attr.string.contains("Italic"))
        #expect(attr.string.contains("Red"))
        #expect(attr.string.contains("ASSBold"))

        // Verify stroke outline is configured for background legibility
        let baseAttrs = attr.attributes(at: 0, effectiveRange: nil)
        #expect(baseAttrs[.strokeColor] != nil)
        if let strokeWidth = baseAttrs[.strokeWidth] as? NSNumber {
            #expect(strokeWidth.doubleValue < 0) // Negative strokeWidth applies both fill and stroke
        }
    }

    @Test func timedTextRendersTextureDuringActiveWindow() {
        let renderer = TimedTextRenderer(device: device)
        renderer.setFrameSize(CGSize(width: 640, height: 360))

        let cue = TimedTextRenderer.TimedTextCue(
            id: 1,
            start: CMTime(seconds: 2.0, preferredTimescale: 600),
            end: CMTime(seconds: 5.0, preferredTimescale: 600),
            rawText: "Sample Subtitle Text"
        )
        renderer.addCue(cue)

        // Before start: nil
        let beforeTexture = renderer.render(at: CMTime(seconds: 1.0, preferredTimescale: 600))
        #expect(beforeTexture == nil)

        // During cue: valid texture
        let activeTexture = renderer.render(at: CMTime(seconds: 3.5, preferredTimescale: 600))
        #expect(activeTexture != nil)
        #expect(activeTexture?.width == 640)
        #expect(activeTexture?.height == 360)

        // Cached texture returned on same cue
        let sameTexture = renderer.render(at: CMTime(seconds: 4.0, preferredTimescale: 600))
        #expect(sameTexture === activeTexture)

        // After end: nil
        let afterTexture = renderer.render(at: CMTime(seconds: 6.0, preferredTimescale: 600))
        #expect(afterTexture == nil)
    }

    // MARK: - ImageSubtitleRenderer (PGS / VobSub) Tests

    @Test func imageSubtitleRendererPlacesBitmapsCorrectly() {
        let renderer = ImageSubtitleRenderer(device: device)
        renderer.setFrameSize(CGSize(width: 100, height: 100))

        // Create a 10x10 solid red RGBA bitmap
        var redPixels = [UInt8](repeating: 0, count: 10 * 10 * 4)
        for i in 0..<(10 * 10) {
            redPixels[i * 4] = 255     // R
            redPixels[i * 4 + 1] = 0   // G
            redPixels[i * 4 + 2] = 0   // B
            redPixels[i * 4 + 3] = 255 // A
        }

        let rect = ImageSubtitleRect(
            x: 20,
            y: 30,
            width: 10,
            height: 10,
            data: Data(redPixels)
        )

        let cue = ImageSubtitleCue(
            start: CMTime(seconds: 1.0, preferredTimescale: 600),
            end: CMTime(seconds: 3.0, preferredTimescale: 600),
            rects: [rect],
            canvasSize: CGSize(width: 100, height: 100)
        )
        renderer.addCue(cue)

        // Before start: nil
        #expect(renderer.render(at: CMTime(seconds: 0.5, preferredTimescale: 600)) == nil)

        // During cue: valid texture
        let texture = renderer.render(at: CMTime(seconds: 2.0, preferredTimescale: 600))
        #expect(texture != nil)
        #expect(texture?.width == 100)
        #expect(texture?.height == 100)

        // Verify pixel data at placed location
        var readPixels = [UInt8](repeating: 0, count: 100 * 100 * 4)
        texture?.getBytes(
            &readPixels,
            bytesPerRow: 100 * 4,
            from: MTLRegionMake2D(0, 0, 100, 100),
            mipmapLevel: 0
        )

        // Check pixel at (x: 25, y: 35) inside the rect -> should be red
        let targetIndex = (35 * 100 + 25) * 4
        #expect(readPixels[targetIndex] == 255)     // R
        #expect(readPixels[targetIndex + 1] == 0)   // G
        #expect(readPixels[targetIndex + 2] == 0)   // B
        #expect(readPixels[targetIndex + 3] == 255) // A

        // Check pixel outside rect (e.g. x: 5, y: 5) -> should be transparent
        let emptyIndex = (5 * 100 + 5) * 4
        #expect(readPixels[emptyIndex + 3] == 0)
    }

    // MARK: - AssRenderer Tests

    @Test func assRendererLifecycleAndEvents() {
        guard AssRenderer.isAvailable else {
            // libass is not linked in this target; test SubtitleEngine fallback for ASS
            let engine = SubtitleEngine(device: device)
            engine.setCanvasSize(CGSize(width: 640, height: 360))
            engine.selectFormat(.ass)
            let assHeader = """
            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Hello from ASS fallback!
            """
            engine.addAssScriptData(Data(assHeader.utf8))
            let texture = engine.renderSubtitleTexture(at: CMTime(seconds: 2.0, preferredTimescale: 600))
            #expect(texture != nil)
            #expect(texture?.width == 640)
            #expect(texture?.height == 360)
            return
        }

        let renderer = AssRenderer(device: device)
        renderer.setFrameSize(CGSize(width: 640, height: 360))

        let assHeader = """
        [Script Info]
        Title: Test Subtitle
        ScriptType: v4.00+
        PlayResX: 640
        PlayResY: 360

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Default,Arial,24,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:04.00,Default,,0,0,0,,Hello from libass!
        """

        renderer.addScriptData(Data(assHeader.utf8))

        // Render at active time
        let texture = renderer.render(at: CMTime(seconds: 2.0, preferredTimescale: 600))
        #expect(texture != nil)
        #expect(texture?.width == 640)
        #expect(texture?.height == 360)

        // Render before start -> nil
        let beforeTexture = renderer.render(at: CMTime(seconds: 0.5, preferredTimescale: 600))
        #expect(beforeTexture == nil)

        // Reset clears state
        renderer.reset()
        #expect(renderer.render(at: CMTime(seconds: 2.0, preferredTimescale: 600)) == nil)
    }

    // MARK: - SubtitleCompositor Metal Compute Pipeline Tests

    @Test func subtitleCompositorBlendsAlphaCorrectly() {
        let compositor = SubtitleCompositor(device: device)

        let width = 64
        let height = 64
        let desc = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba8Unorm,
            width: width,
            height: height,
            mipmapped: false
        )
        desc.usage = [.shaderRead, .shaderWrite]

        guard let videoTex = device.makeTexture(descriptor: desc),
              let subTex = device.makeTexture(descriptor: desc),
              let outTex = device.makeTexture(descriptor: desc),
              let queue = device.makeCommandQueue(),
              let cmdBuffer = queue.makeCommandBuffer() else {
            Issue.record("Failed to create Metal resources for compositor test")
            return
        }

        // Fill video texture with solid blue (R: 0, G: 0, B: 255, A: 255)
        var videoPixels = [UInt8](repeating: 0, count: width * height * 4)
        for i in 0..<(width * height) {
            videoPixels[i * 4] = 0
            videoPixels[i * 4 + 1] = 0
            videoPixels[i * 4 + 2] = 255
            videoPixels[i * 4 + 3] = 255
        }
        videoTex.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0, withBytes: videoPixels, bytesPerRow: width * 4)

        // Fill subtitle texture: top half transparent (A: 0), bottom half opaque yellow (R: 255, G: 255, B: 0, A: 255)
        var subPixels = [UInt8](repeating: 0, count: width * height * 4)
        for y in (height / 2)..<height {
            for x in 0..<width {
                let idx = (y * width + x) * 4
                subPixels[idx] = 255     // R
                subPixels[idx + 1] = 255 // G
                subPixels[idx + 2] = 0   // B
                subPixels[idx + 3] = 255 // A
            }
        }
        subTex.replace(region: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0, withBytes: subPixels, bytesPerRow: width * 4)

        compositor.composite(video: videoTex, subtitle: subTex, output: outTex, commandBuffer: cmdBuffer)
        cmdBuffer.commit()
        cmdBuffer.waitUntilCompleted()

        var outputPixels = [UInt8](repeating: 0, count: width * height * 4)
        outTex.getBytes(&outputPixels, bytesPerRow: width * 4, from: MTLRegionMake2D(0, 0, width, height), mipmapLevel: 0)

        // Check top half (transparent subtitle): should remain original video blue (R: 0, G: 0, B: 255)
        let topIdx = (10 * width + 10) * 4
        #expect(outputPixels[topIdx] < 5)        // R ~ 0
        #expect(outputPixels[topIdx + 2] > 250)  // B ~ 255

        // Check bottom half (opaque subtitle): should be subtitle yellow (R: 255, G: 255, B: 0)
        let bottomIdx = (50 * width + 10) * 4
        #expect(outputPixels[bottomIdx] > 250)     // R ~ 255
        #expect(outputPixels[bottomIdx + 1] > 250) // G ~ 255
        #expect(outputPixels[bottomIdx + 2] < 5)   // B ~ 0
    }

    // MARK: - SubtitleEngine Unified Pipeline Tests

    @Test func subtitleEngineFormatSwitchingAndDispatch() {
        let engine = SubtitleEngine(device: device)
        engine.setCanvasSize(CGSize(width: 320, height: 240))

        #expect(engine.canvasSize == CGSize(width: 320, height: 240))

        // Select SRT format
        engine.selectFormat(.srt)
        #expect(engine.activeFormat == .srt)

        engine.addEvent(DecodedSubtitleEvent(
            text: "Hello SRT",
            start: CMTime(seconds: 1.0, preferredTimescale: 600),
            end: CMTime(seconds: 3.0, preferredTimescale: 600)
        ))

        let texture = engine.renderSubtitleTexture(at: CMTime(seconds: 2.0, preferredTimescale: 600))
        #expect(texture != nil)

        // Disable subtitle engine
        engine.isEnabled = false
        #expect(engine.renderSubtitleTexture(at: CMTime(seconds: 2.0, preferredTimescale: 600)) == nil)

        engine.isEnabled = true
        #expect(engine.renderSubtitleTexture(at: CMTime(seconds: 2.0, preferredTimescale: 600)) != nil)

        // Switch to nil format
        engine.selectFormat(nil)
        #expect(engine.renderSubtitleTexture(at: CMTime(seconds: 2.0, preferredTimescale: 600)) == nil)
    }
}
