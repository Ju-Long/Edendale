//
//  BackdropImage.swift
//  Edendale
//
//  Full-bleed backdrop with a bottom-up scrim into the archive floor,
//  used by the library hero and the media detail header.
//

import SwiftUI
import Kingfisher

struct BackdropImage: View {
    let url: URL?

    var body: some View {
        // The Color base drives layout: it accepts the proposed size exactly.
        // The aspect-fill image rides as an overlay so its oversized fill
        // dimension can't push the view wider than offered — `.clipped()`
        // then crops the overflow. (An image sibling in a ZStack *would*
        // report its fill width and blow out the whole page's layout.)
        Theme.surfaceLow
            .overlay {
                if let url {
                    KFImage(url)
                        .resizable()
                        .fade(duration: 0.3)
                        .aspectRatio(contentMode: .fill)
                }
            }
            .overlay {
                LinearGradient(
                    stops: [
                        .init(color: .clear, location: 0.35),
                        .init(color: Theme.background, location: 1.0)
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            }
            .clipped()
    }
}
