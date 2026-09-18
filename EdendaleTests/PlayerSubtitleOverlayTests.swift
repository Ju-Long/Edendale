import CoreMedia
import ImageIO
import Observation
import SwiftUI
import Synchronization
import Testing
import UniformTypeIdentifiers
@testable import Edendale

private final class SubtitleOverlayTestResources: NSObject { }

@MainActor
@Suite(.serialized)
struct PlayerSubtitleOverlayTests {
    private func time(_ seconds: Double) -> CMTime {
        CMTime(seconds: seconds, preferredTimescale: 600)
    }

    @Test func windowsLineEndingsAndBOMPreserveDownloadedCues() {
        let renderer = TimedTextRenderer()
        let cues = renderer.parse("\u{FEFF}1\r\n00:00:01,000 --> 00:00:03,000\r\nHello\r\nSecond line\r\n\r\n2\r\n00:00:03,000 --> 00:00:05,000\r\nNext cue\r\n")
        #expect(cues.count == 2)
        #expect(cues.first?.rawText == "Hello\nSecond line")
        #expect(cues.last?.rawText == "Next cue")
        #expect(cues.first?.contains(time: time(3)) == false)
        #expect(cues.last?.contains(time: time(3)) == true)
    }

    @Test func overlappingCuesObservePausedSelectionAndDisappearWhenDisabled() {
        let engine = SubtitleEngine()
        engine.selectFormat(.srt)
        engine.addEvent(DecodedSubtitleEvent(text: "First speaker", start: time(1), end: time(3)))
        engine.addEvent(DecodedSubtitleEvent(text: "Second speaker", start: time(2), end: time(4)))
        #expect(engine.activeTextCues(at: time(2)).map(\.rawText) == ["First speaker", "Second speaker"])
        #expect(engine.activeTextCues(at: .invalid).isEmpty)
        #expect(engine.activeTextCues(at: time(4)).isEmpty)
        let invalidated = Mutex(false)
        withObservationTracking {
            _ = engine.activeTextCues(at: time(2))
        } onChange: {
            invalidated.withLock { $0 = true }
        }
        engine.reset()
        #expect(invalidated.withLock { $0 })
        #expect(engine.activeTextCues(at: time(2)).isEmpty)
        engine.addEvent(DecodedSubtitleEvent(text: "Replacement", start: time(1), end: time(3)))
        engine.isEnabled = false
        #expect(engine.activeTextCues(at: time(2)).isEmpty)
    }

    @Test(arguments: ["srt", "vtt", "ass", "utf16"])
    func downloadedFilesRenderVisibleTextThroughThePlayerRoute(format: String) throws {
        let player = PlaybackEngine()
        defer { player.close() }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        // This is the same cache-file import used by OnlineSubtitlesModel.download.
        let url = directory.appendingPathComponent("wyzie-test.\(format == "utf16" ? "srt" : format)")
        let content: String
        switch format {
        case "vtt":
            content = "WEBVTT\r\n\r\n00:01.000 --> 00:03.000\r\nDownloaded <b>subtitle</b>\r\n"
        case "ass":
            content = "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Downloaded {\\i1}subtitle{\\i0}\n"
        default:
            content = "1\r\n00:00:01,000 --> 00:00:03,000\r\nDownloaded <i>subtitle</i>\r\n"
        }
        try content.data(using: format == "utf16" ? .utf16 : .utf8)!.write(to: url)
        try player.addExternalTrack(from: url)
        #expect(player.selectedSubtitleTrack?.id.hasPrefix("ext-") == true)
        #expect(player.subtitleEngine.activeTextCues(at: time(2)).count == 1)
        #expect(try visiblePixels(render(player.subtitleEngine, at: 0)).bright == 0)
        let pixels = try visiblePixels(render(player.subtitleEngine, at: 2))
        #expect(pixels.opaque > 500)
        #expect(pixels.bright > 50, "Verify actual text pixels, not just the subtitle backdrop")
        #expect(try visiblePixels(render(player.subtitleEngine, at: 3)).bright == 0)
        player.selectedSubtitleTrack = nil
        #expect(try visiblePixels(render(player.subtitleEngine, at: 2)).bright == 0)
    }

    @Test func embeddedMKVTracksReachTheSwiftUIRenderer() async throws {
        let bundle = Bundle(for: SubtitleOverlayTestResources.self)
        let url = try #require(bundle.url(forResource: "decoder-subtitles", withExtension: "mkv")
            ?? bundle.url(forResource: "decoder-subtitles", withExtension: "mkv", subdirectory: "Fixtures"))
        let player = PlaybackEngine()
        player.isMuted = true
        defer { player.close() }
        try await player.open(url: url)
        let decoder = try #require(player.decoder as? FFmpegDecoder)
        player.pause()
        try await decoder.seek(to: time(0.5))
        for track in player.subtitleTracks {
            player.selectedSubtitleTrack = track
            let deadline = Date().addingTimeInterval(5)
            while player.subtitleEngine.activeTextCues(at: time(0.5)).isEmpty && Date() < deadline {
                try await Task.sleep(for: .milliseconds(20))
            }
            #expect(!player.subtitleEngine.activeTextCues(at: time(0.5)).isEmpty)
            #expect(try visiblePixels(render(player.subtitleEngine, at: 0.5)).bright > 50)
            #expect(!player.isPlaying)
        }
    }

    @Test func bitmapCueRendersAtItsVideoCoordinates() throws {
        let engine = SubtitleEngine()
        engine.selectFormat(.pgs)
        let data = Data(repeating: 255, count: 20 * 10 * 4)
        engine.addImageCue(ImageSubtitleCue(start: time(1), end: time(3),
            rects: [ImageSubtitleRect(x: 150, y: 150, width: 20, height: 10, data: data)],
            canvasSize: CGSize(width: 320, height: 180)))
        let image = try render(engine, at: 2)
        #expect(try visiblePixels(image).bright >= 800)
        #expect(try visiblePixels(render(engine, at: 3)).bright == 0)
        #expect(engine.activeTextCues(at: time(2)).isEmpty)
    }

    @Test func videoGeometryMatchesLetterboxingAndFill() {
        let container = CGSize(width: 400, height: 800)
        let video = CGSize(width: 1920, height: 1080)
        let fit = PlayerSubtitleOverlay.videoRect(in: container, videoSize: video, aspectFill: false)
        #expect(fit == CGRect(x: 0, y: 287.5, width: 400, height: 225))
        let fill = PlayerSubtitleOverlay.videoRect(in: container, videoSize: video, aspectFill: true)
        #expect(abs(fill.height - 800) < 0.01)
        #expect(abs(fill.midX - 200) < 0.01)
        #expect(fill.width > container.width)
    }

    @Test func rendersPreviewForVisualInspection() throws {
        let engine = SubtitleEngine()
        engine.selectFormat(.ass)
        engine.addEvent(DecodedSubtitleEvent(text: "0,0,Default,,0,0,0,,A subtitle above the video.\\N{\\i1}Embedded and downloaded captions.{\\i0}", start: time(1), end: time(3)))
        let overlay = PlayerSubtitleOverlay(engine: engine, time: time(2),
                                           videoSize: CGSize(width: 1920, height: 1080))
        let renderer = ImageRenderer(content: overlay
            .background(LinearGradient(colors: [Theme.surfaceHigh, Theme.outlineBright], startPoint: .topLeading, endPoint: .bottomTrailing))
            .frame(width: 720, height: 405))
        let image = try #require(renderer.cgImage)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("edendale-subtitle-overlay-preview.png")
        let destination = try #require(CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, nil)
        #expect(CGImageDestinationFinalize(destination))
        print("Subtitle overlay preview: \(url.path)")
    }

    private func render(_ engine: SubtitleEngine, at seconds: Double) throws -> CGImage {
        let view = PlayerSubtitleOverlay(engine: engine, time: time(seconds), videoSize: CGSize(width: 320, height: 180))
            .frame(width: 640, height: 360)
            // The player always has a video/backdrop beneath the overlay. An
            // empty transparent ImageRenderer surface has no pixels to paint.
            .background(Theme.background)
        let renderer = ImageRenderer(content: view)
        renderer.scale = 1
        return try #require(renderer.cgImage)
    }

    private func visiblePixels(_ image: CGImage) throws -> (opaque: Int, bright: Int) {
        var pixels = [UInt8](repeating: 0, count: image.width * image.height * 4)
        try pixels.withUnsafeMutableBytes { bytes in
            let context = try #require(CGContext(data: bytes.baseAddress, width: image.width, height: image.height,
                bitsPerComponent: 8, bytesPerRow: image.width * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue))
            context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        }
        var opaque = 0, bright = 0
        for index in stride(from: 0, to: pixels.count, by: 4) {
            if pixels[index + 3] > 0 { opaque += 1 }
            if pixels[index] > 150 && pixels[index + 1] > 150 && pixels[index + 2] > 150 { bright += 1 }
        }
        return (opaque, bright)
    }
}
