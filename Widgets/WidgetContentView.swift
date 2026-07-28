//
//  WidgetContentView.swift
//  WidgetsExtension
//

import SwiftUI
import WidgetKit

#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

struct EdendaleWidgetContentView: View {
    let entry: EdendaleWidgetEntry
    let content: EdendaleWidgetContent

    @Environment(\.widgetFamily) private var family

    private var items: [EdendaleWidgetItem] {
        guard let snapshot = entry.snapshot else { return [] }
        return Array(
            content.items(in: snapshot)
                .prefix(content.maximumItemCount(for: family))
        )
    }

    var body: some View {
        Group {
            if entry.snapshot == nil {
                unavailableState
            } else if items.isEmpty {
                emptyState
            } else {
                dataState
            }
        }
        .redacted(reason: entry.isPlaceholder ? .placeholder : [])
        .containerBackground(Color("Background"), for: .widget)
    }

    @ViewBuilder
    private var dataState: some View {
        switch family {
        case .systemSmall:
            if let item = items.first {
                smallContent(item)
            }
        case .systemMedium:
            mediumContent
        case .systemLarge:
            largeContent
        default:
            if let item = items.first {
                smallContent(item)
            }
        }
    }

    private func smallContent(_ item: EdendaleWidgetItem) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            header

            EdendaleWidgetPoster(data: entry.posterData[item.id])
                .frame(maxWidth: .infinity)
                .frame(height: 62)

            Text(item.title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color("TextPrimary"))
                .lineLimit(2)

            if content == .continueWatching, let progress = item.progress {
                EdendaleWidgetProgressBar(progress: progress)
            } else if let subtitle = item.subtitle {
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(Color("TextSecondary"))
                    .lineLimit(1)
            }
        }
        .padding(12)
        .contentShape(Rectangle())
        .widgetURL(item.deepLink)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel(for: item))
    }

    private var mediumContent: some View {
        VStack(alignment: .leading, spacing: 10) {
            header

            HStack(spacing: 10) {
                ForEach(items) { item in
                    Link(destination: item.deepLink) {
                        HStack(spacing: 9) {
                            EdendaleWidgetPoster(data: entry.posterData[item.id])
                                .frame(width: 48, height: 74)

                            VStack(alignment: .leading, spacing: 5) {
                                Text(item.title)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(Color("TextPrimary"))
                                    .lineLimit(2)

                                if let subtitle = item.subtitle {
                                    Text(subtitle)
                                        .font(.caption2)
                                        .foregroundStyle(Color("TextSecondary"))
                                        .lineLimit(2)
                                }

                                if content == .continueWatching, let progress = item.progress {
                                    EdendaleWidgetProgressBar(progress: progress)
                                }

                                Spacer(minLength: 0)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .padding(8)
                        .background(Color("Surface"))
                        .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(accessibilityLabel(for: item))
                }
            }
        }
        .padding(12)
    }

    private var largeContent: some View {
        VStack(alignment: .leading, spacing: 9) {
            header

            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                Link(destination: item.deepLink) {
                    HStack(spacing: 12) {
                        EdendaleWidgetPoster(data: entry.posterData[item.id])
                            .frame(width: 48, height: 68)

                        VStack(alignment: .leading, spacing: 5) {
                            Text(item.title)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Color("TextPrimary"))
                                .lineLimit(1)

                            if let subtitle = item.subtitle {
                                Text(subtitle)
                                    .font(.caption)
                                    .foregroundStyle(Color("TextSecondary"))
                                    .lineLimit(1)
                            }

                            if content == .continueWatching, let progress = item.progress {
                                EdendaleWidgetProgressBar(progress: progress)
                            }
                        }

                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(accessibilityLabel(for: item))

                if index < items.count - 1 {
                    Rectangle()
                        .fill(Color("Outline"))
                        .frame(height: 1)
                }
            }

            Spacer(minLength: 0)
        }
        .padding(14)
    }

    private var header: some View {
        HStack(spacing: 8) {
            Text(content.title.uppercased())
                .font(.caption2.weight(.bold))
                .tracking(1.1)
                .foregroundStyle(Color("AccentColor"))
                .lineLimit(1)

            Spacer(minLength: 4)

            Text("EDENDALE")
                .font(.caption2.weight(.semibold))
                .tracking(0.8)
                .foregroundStyle(Color("TextSecondary"))
                .lineLimit(1)
        }
    }

    private var unavailableState: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            Spacer()
            Text("Open Edendale")
                .font(.headline)
                .foregroundStyle(Color("TextPrimary"))
            Text("Launch the app once to prepare this widget.")
                .font(.caption)
                .foregroundStyle(Color("TextSecondary"))
                .lineLimit(3)
            Spacer()
        }
        .padding(14)
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 8) {
            header
            Spacer()
            Text(content.emptyTitle)
                .font(.headline)
                .foregroundStyle(Color("TextPrimary"))
            Text(content.emptyMessage)
                .font(.caption)
                .foregroundStyle(Color("TextSecondary"))
                .lineLimit(3)
            Spacer()
        }
        .padding(14)
    }

    private func accessibilityLabel(for item: EdendaleWidgetItem) -> String {
        var parts = [item.title]
        if let subtitle = item.subtitle { parts.append(subtitle) }
        if content == .continueWatching, let progress = item.progress {
            let percentage = Int((min(max(progress, 0), 1) * 100).rounded())
            parts.append(String(localized: "\(percentage) percent watched"))
        }
        return parts.joined(separator: ", ")
    }
}

private struct EdendaleWidgetPoster: View {
    let data: Data?

    var body: some View {
        ZStack {
            Color("SurfaceHigh")
            posterImage
        }
        .clipShape(RoundedRectangle(cornerRadius: 7))
    }

    @ViewBuilder
    private var posterImage: some View {
        #if canImport(UIKit)
        if let data, let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .clipped()
        } else {
            placeholder
        }
        #elseif canImport(AppKit)
        if let data, let image = NSImage(data: data) {
            Image(nsImage: image)
                .resizable()
                .scaledToFill()
                .clipped()
        } else {
            placeholder
        }
        #else
        placeholder
        #endif
    }

    private var placeholder: some View {
        Text("E")
            .font(.title2.weight(.bold))
            .foregroundStyle(Color("AccentColor"))
    }
}

private struct EdendaleWidgetProgressBar: View {
    let progress: Double

    private var clampedProgress: Double {
        min(max(progress, 0), 1)
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .leading) {
                Capsule().fill(Color("Outline"))
                Capsule()
                    .fill(Color("AccentColor"))
                    .frame(width: geometry.size.width * clampedProgress)
            }
        }
        .frame(height: 4)
        .accessibilityHidden(true)
    }
}
