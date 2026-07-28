//
//  TrendingWidget.swift
//  WidgetsExtension
//

import SwiftUI
import WidgetKit

struct TrendingWidget: Widget {
    static let kind = "com.BaBaSaMa.Edendale.widget.trending"

    var body: some WidgetConfiguration {
        StaticConfiguration(
            kind: Self.kind,
            provider: EdendaleWidgetProvider(content: .trending)
        ) { entry in
            EdendaleWidgetContentView(entry: entry, content: .trending)
        }
        .configurationDisplayName("Trending")
        .description("See movies and shows trending on Edendale.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}
