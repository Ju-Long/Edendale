//
//  ContinueWatchingWidget.swift
//  WidgetsExtension
//

import SwiftUI
import WidgetKit

struct ContinueWatchingWidget: Widget {
    static let kind = "com.BaBaSaMa.Edendale.widget.continue-watching"

    var body: some WidgetConfiguration {
        StaticConfiguration(
            kind: Self.kind,
            provider: EdendaleWidgetProvider(content: .continueWatching)
        ) { entry in
            EdendaleWidgetContentView(entry: entry, content: .continueWatching)
        }
        .configurationDisplayName("Continue Watching")
        .description("Resume the movies and episodes you recently watched.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
