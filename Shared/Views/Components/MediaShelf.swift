//
//  MediaShelf.swift
//  Edendale
//
//  Horizontal poster shelf: SectionHeader whose rule scrubs the scroll
//  position, spotlight glow for the hero's current title, and zoomable
//  cards that push into the detail view. Scroll clipping is disabled so
//  the poster glow isn't cut off at the shelf edges.
//

import SwiftUI

/// Identifies the exact shelf card a push started from, so a zoom
/// transition anchors to it even when a title appears on several shelves.
struct ShelfZoomID: Hashable {
    let shelf: String
    let ref: MediaRef
}

struct MediaShelf: View {
    let title: String
    let items: [TMDBMediaItem]
    /// Card matching this ref glows with the accent color.
    var spotlightRef: MediaRef?
    var posterWidth: CGFloat = 180
    var edgeMargin: CGFloat = 48
    /// Namespace for zoom-to-detail transitions; nil disables them.
    var zoomNamespace: Namespace.ID?
    /// Called when a card is tapped; the owner handles navigation.
    let onSelect: (TMDBMediaItem) -> Void

    @State private var scrollPosition = ScrollPosition()
    @State private var metrics = Metrics()
    @Environment(WatchProgressStore.self) private var watchStore

    /// tvOS slots are the focused size and resting cards shrink inside them
    /// (see CardFocusButtonStyle), so tighter slot spacing keeps the resting
    /// shelf at the same visual rhythm as the other platforms.
    private var cardSpacing: CGFloat {
        #if os(tvOS)
        8
        #else
        20
        #endif
    }

    /// Scroll geometry mirrored into the header scrubber.
    private struct Metrics: Equatable {
        var offset: CGFloat = 0
        /// Scrollable distance: content width - container width.
        var range: CGFloat = 0
        var visibleFraction: Double = 1

        var progress: Double {
            range > 0 ? min(max(offset / range, 0), 1) : 0
        }
    }

    var body: some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 18) {
                SectionHeader(
                    title: title,
                    scrubber: SectionScrubber(
                        progress: metrics.progress,
                        visibleFraction: metrics.visibleFraction
                    ) { fraction in
                        scrollPosition.scrollTo(x: fraction * metrics.range)
                    }
                )
                .padding(.horizontal, edgeMargin)

                ScrollView(.horizontal, showsIndicators: false) {
                    LazyHStack(alignment: .top, spacing: cardSpacing) {
                        ForEach(items) { item in
                            card(item)
                        }
                    }
                    .padding(.horizontal, edgeMargin)
                    .padding(.vertical, 14)
                }
                .scrollPosition($scrollPosition)
                // The hover/spotlight glow may bleed past the shelf bounds
                // instead of being clipped to a hard edge.
                .scrollClipDisabled()
                .onScrollGeometryChange(for: Metrics.self) { geometry in
                    Metrics(
                        offset: geometry.contentOffset.x + geometry.contentInsets.leading,
                        range: geometry.contentSize.width - geometry.containerSize.width,
                        visibleFraction: geometry.contentSize.width > 0
                            ? geometry.containerSize.width / geometry.contentSize.width
                            : 1
                    )
                } action: { _, new in
                    metrics = new
                }
            }
        }
    }

    @ViewBuilder
    private func card(_ item: TMDBMediaItem) -> some View {
        let poster = Button {
            onSelect(item)
        } label: {
            PosterCard(
                title: item.title,
                subtitle: item.detailedDateText,
                posterURL: item.posterURL,
                placeholderIcon: item.mediaType == .tv ? "tv" : "film",
                width: posterWidth,
                isWatched: item.mediaType == .movie ? watchStore.isWatched(item.id, mediaType: .movie) : false,
                isSpotlit: item.ref == spotlightRef
            )
        }
        // tvOS: reserved-bounds focus — the card rests small and expands to
        // fill its slot, so the growth can never clip. `.plain` elsewhere
        // avoids extra chrome around the poster.
        #if os(tvOS)
        .buttonStyle(CardFocusButtonStyle())
        #else
        .buttonStyle(.plain)
        #endif

        // tvOS: `matchedTransitionSource` clips the card to its original
        // bounds, which cuts off the focus expansion — no zoom source there.
        #if os(macOS) || os(tvOS)
        poster
        #else
        if let zoomNamespace {
            poster.matchedTransitionSource(
                id: ShelfZoomID(shelf: title, ref: item.ref),
                in: zoomNamespace
            )
        } else {
            poster
        }
        #endif
    }
}
