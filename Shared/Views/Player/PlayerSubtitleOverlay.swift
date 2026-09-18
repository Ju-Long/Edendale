import CoreMedia
import SwiftUI

/// A native subtitle surface above the video view (including its PiP source
/// layer). Both embedded tracks and downloaded files feed the same cue engine.
struct PlayerSubtitleOverlay: View {
    let engine: SubtitleEngine
    let time: CMTime
    let videoSize: CGSize
    var aspectFill = false
    var controlsVisible = false

    @ScaledMetric(relativeTo: .body) private var fontScale: CGFloat = 1

    var body: some View {
        GeometryReader { geometry in
            let videoRect = Self.videoRect(in: geometry.size, videoSize: videoSize, aspectFill: aspectFill)
            let visibleRect = videoRect.intersection(CGRect(origin: .zero, size: geometry.size))
            let textCues = engine.activeTextCues(at: time)
            let imageCues = engine.activeImageCues(at: time)
            let fontSize = min(48, max(16, visibleRect.height * 0.055)) * fontScale

            ZStack(alignment: .topLeading) {
                ForEach(Array(imageCues.enumerated()), id: \.offset) { entry in
                    SubtitleBitmapCueView(cue: entry.element, videoRect: videoRect)
                }

                if !textCues.isEmpty {
                    VStack(spacing: 0) {
                        Spacer(minLength: 0)
                        VStack(spacing: 4) {
                            ForEach(Array(textCues.enumerated()), id: \.offset) { entry in
                                let text = engine.timedTextRenderer.buildAttributedString(
                                    from: entry.element.rawText,
                                    fontSize: fontSize,
                                    textColor: PlatformColor(Theme.textPrimary),
                                    outlineColor: PlatformColor(Theme.background)
                                )
                                Text(AttributedString(text))
                                    .multilineTextAlignment(.center)
                                    .fixedSize(horizontal: false, vertical: true)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 5)
                                    .background(Theme.background, in: RoundedRectangle(cornerRadius: Theme.Radius.soft))
                                    .accessibilityIdentifier("player.subtitle.text")
                            }
                        }
                        .padding(.horizontal, max(16, visibleRect.width * 0.05))
                        .padding(.bottom, bottomInset(in: geometry, videoRect: visibleRect))
                    }
                    .frame(width: visibleRect.width, height: visibleRect.height)
                    .position(x: visibleRect.midX, y: visibleRect.midY)
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .clipped()
        }
        .allowsHitTesting(false)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("player.subtitles")
        // Cue boundaries follow media time exactly, including seeks and pauses.
        .transaction { $0.animation = nil }
    }

    private func bottomInset(in geometry: GeometryProxy, videoRect: CGRect) -> CGFloat {
        #if os(tvOS)
        let controlsHeight: CGFloat = 180
        #else
        let controlsHeight: CGFloat = 110
        #endif
        let unobscuredBottom = geometry.size.height - geometry.safeAreaInsets.bottom
            - (controlsVisible ? controlsHeight : 0)
        return min(videoRect.height * 0.4,
                   max(videoRect.height * 0.06, videoRect.maxY - unobscuredBottom + 12))
    }

    /// Match the video's fit/fill geometry; bitmap subtitles retain their
    /// authored coordinates while text stays inside the visible part of it.
    static func videoRect(in container: CGSize, videoSize: CGSize, aspectFill: Bool) -> CGRect {
        guard container.width > 0, container.height > 0,
              videoSize.width > 0, videoSize.height > 0 else {
            return CGRect(origin: .zero, size: container)
        }
        let horizontal = container.width / videoSize.width
        let vertical = container.height / videoSize.height
        let scale = aspectFill ? max(horizontal, vertical) : min(horizontal, vertical)
        let size = CGSize(width: videoSize.width * scale, height: videoSize.height * scale)
        return CGRect(x: (container.width - size.width) / 2,
                      y: (container.height - size.height) / 2,
                      width: size.width, height: size.height)
    }
}

private struct SubtitleBitmapCueView: View {
    let cue: ImageSubtitleCue
    let videoRect: CGRect

    var body: some View {
        if cue.canvasSize.width > 0, cue.canvasSize.height > 0 {
            let scaleX = videoRect.width / cue.canvasSize.width
            let scaleY = videoRect.height / cue.canvasSize.height
            ForEach(Array(cue.rects.enumerated()), id: \.offset) { entry in
                let rect = entry.element
                if let image = Self.image(for: rect) {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .frame(width: CGFloat(rect.width) * scaleX, height: CGFloat(rect.height) * scaleY)
                        .position(x: videoRect.minX + (CGFloat(rect.x) + CGFloat(rect.width) / 2) * scaleX,
                                  y: videoRect.minY + (CGFloat(rect.y) + CGFloat(rect.height) / 2) * scaleY)
                }
            }
        }
    }

    private static func image(for rect: ImageSubtitleRect) -> CGImage? {
        guard rect.width > 0, rect.height > 0,
              rect.width <= 8192, rect.height <= 8192,
              rect.data.count >= rect.width * rect.height * 4,
              let provider = CGDataProvider(data: rect.data as CFData) else { return nil }
        return CGImage(width: rect.width, height: rect.height,
                       bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: rect.width * 4,
                       space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue)
                        .union(.byteOrder32Big),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}
