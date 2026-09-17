//
//  TimedTextRenderer.swift
//  Edendale
//
//  Core Text-based renderer for SRT and WebVTT subtitles.
//  Renders formatted text cues with high-legibility styling into an MTLTexture.
//

import CoreGraphics
import CoreMedia
import CoreText
import Foundation
import Metal

#if canImport(AppKit) && !targetEnvironment(macCatalyst)
import AppKit
typealias PlatformFont = NSFont
typealias PlatformColor = NSColor
#elseif canImport(UIKit)
import UIKit
typealias PlatformFont = UIFont
typealias PlatformColor = UIColor
#endif

/// Renders SRT and WebVTT subtitles using Core Text with styling, outline stroke, and positioning.
public final class TimedTextRenderer: @unchecked Sendable {
    private let device: MTLDevice?

    private var cues: [TimedTextCue] = []
    private var frameWidth: Int = 1920
    private var frameHeight: Int = 1080
    private var bottomMargin: CGFloat = 60.0
    private var fontSizeRatio: CGFloat = 0.045 // 4.5% of frame height

    private var activeCueIndex: Int?
    private var cachedTexture: MTLTexture?

    public struct TimedTextCue: Sendable, Equatable {
        public let id: Int
        public let start: CMTime
        public let end: CMTime
        public let rawText: String

        public init(id: Int = 0, start: CMTime, end: CMTime, rawText: String) {
            self.id = id
            self.start = start
            self.end = end
            self.rawText = rawText
        }

        public func contains(time: CMTime) -> Bool {
            return time >= start && time <= end
        }
    }

    public init(device: MTLDevice? = MTLCreateSystemDefaultDevice()) {
        self.device = device
    }

    /// Set rendering frame size.
    public func setFrameSize(_ size: CGSize) {
        let width = max(1, Int(size.width))
        let height = max(1, Int(size.height))
        guard width != frameWidth || height != frameHeight else { return }

        frameWidth = width
        frameHeight = height
        bottomMargin = CGFloat(height) * 0.06
        cachedTexture = nil
        activeCueIndex = nil
    }

    /// Add a parsed subtitle cue.
    public func addCue(_ cue: TimedTextCue) {
        cues.append(cue)
        cues.sort { $0.start < $1.start }
        cachedTexture = nil
        activeCueIndex = nil
    }

    /// Add a decoded subtitle event.
    public func addEvent(_ event: DecodedSubtitleEvent) {
        let cue = TimedTextCue(
            id: cues.count + 1,
            start: event.start,
            end: event.end,
            rawText: event.text
        )
        addCue(cue)
    }

    /// Parse and load a full SRT or WebVTT string.
    public func loadSubtitles(from string: String) {
        cues = parse(string)
        cachedTexture = nil
        activeCueIndex = nil
    }

    /// Reset all loaded cues.
    public func reset() {
        cues.removeAll()
        cachedTexture = nil
        activeCueIndex = nil
    }

    /// Render subtitles at the specified playback time into an overlay texture.
    /// Returns `nil` if no cues are active at this time.
    public func render(at time: CMTime) -> MTLTexture? {
        guard let device else { return nil }

        // Find active cue
        let matchingIndex = cues.firstIndex { $0.contains(time: time) }

        guard let index = matchingIndex else {
            activeCueIndex = nil
            cachedTexture = nil
            return nil
        }

        // Return cached texture if same cue is still active
        if activeCueIndex == index, let cached = cachedTexture {
            return cached
        }

        let cue = cues[index]
        let texture = renderCueToTexture(cue, device: device)
        self.activeCueIndex = index
        self.cachedTexture = texture
        return texture
    }

    // MARK: - Core Text Rendering

    private func renderCueToTexture(_ cue: TimedTextCue, device: MTLDevice) -> MTLTexture? {
        let attributedString = buildAttributedString(from: cue.rawText)
        guard attributedString.length > 0 else { return nil }

        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bytesPerRow = frameWidth * 4
        guard let context = CGContext(
            data: nil,
            width: frameWidth,
            height: frameHeight,
            bitsPerComponent: 8,
            bytesPerRow: bytesPerRow,
            space: colorSpace,
            bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue
        ) else {
            return nil
        }

        // Clear background
        context.clear(CGRect(x: 0, y: 0, width: frameWidth, height: frameHeight))

        let framesetter = CTFramesetterCreateWithAttributedString(attributedString as CFAttributedString)

        let maxWidth = CGFloat(frameWidth) * 0.82
        let maxHeight = CGFloat(frameHeight) * 0.35
        let constraintSize = CGSize(width: maxWidth, height: maxHeight)
        let suggestedSize = CTFramesetterSuggestFrameSizeWithConstraints(
            framesetter,
            CFRangeMake(0, attributedString.length),
            nil,
            constraintSize,
            nil
        )

        // Center horizontally at bottom margin
        let textX = (CGFloat(frameWidth) - suggestedSize.width) / 2.0
        let textY = bottomMargin

        let textRect = CGRect(
            x: textX,
            y: textY,
            width: ceil(suggestedSize.width),
            height: ceil(suggestedSize.height)
        )

        let path = CGPath(rect: textRect, transform: nil)
        let frame = CTFramesetterCreateFrame(framesetter, CFRangeMake(0, attributedString.length), path, nil)

        CTFrameDraw(frame, context)

        // Upload context bitmap to MTLTexture
        guard let pixelData = context.data else { return nil }

        let descriptor = MTLTextureDescriptor.texture2DDescriptor(
            pixelFormat: .rgba8Unorm,
            width: frameWidth,
            height: frameHeight,
            mipmapped: false
        )
        descriptor.usage = [.shaderRead]

        guard let texture = device.makeTexture(descriptor: descriptor) else {
            return nil
        }

        texture.replace(
            region: MTLRegionMake2D(0, 0, frameWidth, frameHeight),
            mipmapLevel: 0,
            withBytes: pixelData,
            bytesPerRow: bytesPerRow
        )

        return texture
    }

    // MARK: - Attributed String & Tag Parsing

    public func buildAttributedString(from rawText: String) -> NSAttributedString {
        let baseFontSize = max(18.0, CGFloat(frameHeight) * fontSizeRatio)
        let baseFont = makeFont(size: baseFontSize, bold: false, italic: false)

        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.alignment = .center
        paragraphStyle.lineBreakMode = .byWordWrapping
        paragraphStyle.lineSpacing = baseFontSize * 0.15

        // High legibility styling: white fill + black stroke outline
        let baseAttributes: [NSAttributedString.Key: Any] = [
            .font: baseFont,
            .foregroundColor: PlatformColor.white,
            .strokeColor: PlatformColor.black,
            .strokeWidth: -3.0, // Negative strokeWidth applies both fill and stroke
            .paragraphStyle: paragraphStyle
        ]

        let parsedNodes = parseMarkup(rawText)
        let result = NSMutableAttributedString()

        for node in parsedNodes {
            var attributes = baseAttributes

            let font = makeFont(size: baseFontSize, bold: node.isBold, italic: node.isItalic)
            attributes[.font] = font

            if let color = node.color {
                attributes[.foregroundColor] = color
            }
            if node.isUnderline {
                attributes[.underlineStyle] = NSUnderlineStyle.single.rawValue
            }

            result.append(NSAttributedString(string: node.text, attributes: attributes))
        }

        return result
    }

    private struct MarkupNode {
        let text: String
        let isBold: Bool
        let isItalic: Bool
        let isUnderline: Bool
        let color: PlatformColor?
    }

    /// Parses HTML tags (`<b>`, `<i>`, `<u>`, `<font color="...">`) and ASS tags (`{\b1}`, `{\i1}`).
    private func parseMarkup(_ text: String) -> [MarkupNode] {
        // Normalize line breaks
        let cleaned = text.replacingOccurrences(of: "\\N", with: "\n")
                          .replacingOccurrences(of: "\\n", with: "\n")
                          .replacingOccurrences(of: "<br>", with: "\n")
                          .replacingOccurrences(of: "<br/>", with: "\n")

        var nodes: [MarkupNode] = []
        var currentText = ""
        var isBold = false
        var isItalic = false
        var isUnderline = false
        var activeColor: PlatformColor? = nil

        var index = cleaned.startIndex

        func flushText() {
            if !currentText.isEmpty {
                nodes.append(MarkupNode(
                    text: currentText,
                    isBold: isBold,
                    isItalic: isItalic,
                    isUnderline: isUnderline,
                    color: activeColor
                ))
                currentText = ""
            }
        }

        while index < cleaned.endIndex {
            let char = cleaned[index]

            if char == "<" {
                if let closeTagIndex = cleaned[index...].firstIndex(of: ">") {
                    flushText()
                    let tagContent = String(cleaned[cleaned.index(after: index)..<closeTagIndex]).trimmingCharacters(in: .whitespaces)
                    let lowerTag = tagContent.lowercased()

                    if lowerTag == "b" {
                        isBold = true
                    } else if lowerTag == "/b" {
                        isBold = false
                    } else if lowerTag == "i" {
                        isItalic = true
                    } else if lowerTag == "/i" {
                        isItalic = false
                    } else if lowerTag == "u" {
                        isUnderline = true
                    } else if lowerTag == "/u" {
                        isUnderline = false
                    } else if lowerTag.starts(with: "font") {
                        if let colorStr = extractColorAttribute(from: tagContent) {
                            activeColor = parseColor(colorStr)
                        }
                    } else if lowerTag == "/font" {
                        activeColor = nil
                    }

                    index = cleaned.index(after: closeTagIndex)
                    continue
                }
            } else if char == "{" {
                // Handle ASS style overrides like {\b1}, {\i1}, {\b0}, {\i0}
                if let closeTagIndex = cleaned[index...].firstIndex(of: "}") {
                    flushText()
                    let tagContent = String(cleaned[cleaned.index(after: index)..<closeTagIndex])
                    if tagContent.contains("\\b1") {
                        isBold = true
                    } else if tagContent.contains("\\b0") {
                        isBold = false
                    }
                    if tagContent.contains("\\i1") {
                        isItalic = true
                    } else if tagContent.contains("\\i0") {
                        isItalic = false
                    }
                    index = cleaned.index(after: closeTagIndex)
                    continue
                }
            }

            currentText.append(char)
            index = cleaned.index(after: index)
        }

        flushText()
        return nodes
    }

    private func extractColorAttribute(from tag: String) -> String? {
        let pattern = "color=[\"']?([^\"' >]+)"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: .caseInsensitive),
              let match = regex.firstMatch(in: tag, options: [], range: NSRange(location: 0, length: tag.utf16.count)),
              let range = Range(match.range(at: 1), in: tag) else {
            return nil
        }
        return String(tag[range])
    }

    private func parseColor(_ colorString: String) -> PlatformColor? {
        let lower = colorString.lowercased().trimmingCharacters(in: .whitespaces)
        switch lower {
        case "yellow": return PlatformColor.yellow
        case "red": return PlatformColor.red
        case "cyan": return PlatformColor.cyan
        case "green": return PlatformColor.green
        case "blue": return PlatformColor.blue
        case "white": return PlatformColor.white
        case "black": return PlatformColor.black
        default:
            break
        }

        var hex = lower
        if hex.hasPrefix("#") {
            hex.removeFirst()
        }

        guard hex.count == 6, let rgb = UInt32(hex, radix: 16) else { return nil }
        let r = CGFloat((rgb >> 16) & 0xFF) / 255.0
        let g = CGFloat((rgb >> 8) & 0xFF) / 255.0
        let b = CGFloat(rgb & 0xFF) / 255.0

        #if canImport(AppKit) && !targetEnvironment(macCatalyst)
        return NSColor(red: r, green: g, blue: b, alpha: 1.0)
        #else
        return UIColor(red: r, green: g, blue: b, alpha: 1.0)
        #endif
    }

    private func makeFont(size: CGFloat, bold: Bool, italic: Bool) -> PlatformFont {
        #if canImport(AppKit) && !targetEnvironment(macCatalyst)
        var font = NSFont.systemFont(ofSize: size, weight: bold ? .bold : .medium)
        if italic {
            font = NSFontManager.shared.convert(font, toHaveTrait: .italicFontMask)
        }
        return font
        #else
        var descriptor = UIFont.systemFont(ofSize: size, weight: bold ? .bold : .medium).fontDescriptor
        var traits: UIFontDescriptor.SymbolicTraits = []
        if bold { traits.insert(.traitBold) }
        if italic { traits.insert(.traitItalic) }

        if let newDescriptor = descriptor.withSymbolicTraits(traits) {
            return UIFont(descriptor: newDescriptor, size: size)
        }
        return UIFont.systemFont(ofSize: size)
        #endif
    }

    // MARK: - SRT / WebVTT Parser

    public func parse(_ content: String) -> [TimedTextCue] {
        var result: [TimedTextCue] = []
        let lines = content.components(separatedBy: .newlines)

        var currentIndex = 0
        var currentStart: CMTime?
        var currentEnd: CMTime?
        var currentLines: [String] = []

        func flushCue() {
            if let start = currentStart, let end = currentEnd, !currentLines.isEmpty {
                let cueText = currentLines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
                if !cueText.isEmpty {
                    result.append(TimedTextCue(
                        id: currentIndex,
                        start: start,
                        end: end,
                        rawText: cueText
                    ))
                }
            }
            currentStart = nil
            currentEnd = nil
            currentLines.removeAll()
        }

        for rawLine in lines {
            let line = rawLine.trimmingCharacters(in: .whitespaces)

            // Skip WebVTT header lines
            if line.uppercased().hasPrefix("WEBVTT") || line.uppercased().hasPrefix("NOTE") {
                continue
            }

            // Check for timestamp line: "00:00:20,000 --> 00:00:24,400"
            if line.contains("-->") {
                flushCue()
                let parts = line.components(separatedBy: "-->")
                if parts.count >= 2 {
                    let startStr = parts[0].trimmingCharacters(in: .whitespaces)
                    let endParts = parts[1].trimmingCharacters(in: .whitespaces).components(separatedBy: .whitespaces)
                    let endStr = endParts.first ?? ""

                    currentStart = parseTimestamp(startStr)
                    currentEnd = parseTimestamp(endStr)
                }
            } else if line.isEmpty {
                flushCue()
            } else if let num = Int(line), currentStart == nil {
                // Sequence number in SRT
                currentIndex = num
            } else {
                currentLines.append(rawLine)
            }
        }

        flushCue()
        return result
    }

    /// Parses time strings like "01:23:45,678", "01:23:45.678", or "23:45.678" into CMTime.
    public func parseTimestamp(_ string: String) -> CMTime? {
        let normalized = string.replacingOccurrences(of: ",", with: ".")
        let components = normalized.components(separatedBy: ":")

        var hours: Double = 0
        var minutes: Double = 0
        var seconds: Double = 0

        if components.count == 3 {
            hours = Double(components[0]) ?? 0
            minutes = Double(components[1]) ?? 0
            seconds = Double(components[2]) ?? 0
        } else if components.count == 2 {
            minutes = Double(components[0]) ?? 0
            seconds = Double(components[1]) ?? 0
        } else if components.count == 1 {
            seconds = Double(components[0]) ?? 0
        } else {
            return nil
        }

        let totalSeconds = hours * 3600.0 + minutes * 60.0 + seconds
        guard totalSeconds >= 0 else { return nil }
        return CMTime(seconds: totalSeconds, preferredTimescale: 600)
    }
}
