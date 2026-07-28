//
//  View.swift
//  Edendale
//
//  Created by Long Ju on 7/9/26.
//

import SwiftUI

extension View {
    @ViewBuilder
    func modify(@ViewBuilder _ transform: (Self) -> (some View)?) -> some View {
        if let view = transform(self), !(view is EmptyView) {
            view
        } else {
            self
        }
    }
    
    /// A glass surface behind `self`, filling `shape`.
    ///
    /// `.contentShape(shape)` is what makes the surface *touch-opaque*, and it
    /// is not optional: `.background(.ultraThinMaterial)` inserts a real view
    /// that hit-tests, but `.glassEffect` only draws — it contributes no
    /// interaction region. Without the content shape, a padded label on glass
    /// hit-tests as its glyph alone and every tap on the surrounding surface
    /// falls through to whatever sits behind it.
    func glassBackground<S: Shape>(in shape: S) -> some View {
        self.modify { view in
            #if os(visionOS)
            view.background(.ultraThinMaterial).clipShape(shape)
            #else
            if #available(iOS 26.0, *), #available(watchOS 26.0, *), #available(macOS 26.0, *), #available(tvOS 26.0, *) {
                view.glassEffect(.regular.interactive(), in: shape)
            } else {
                #if os(iOS)
                    view.background(.ultraThinMaterial).clipShape(shape)
                #else
                    view.background(Color.gray.opacity(0.8)).clipShape(shape)
                #endif
            }
            #endif
        }
        .contentShape(shape)
    }
    
    func labelCaps(_ color: Color = Theme.textSecondary) -> some View {
        modifier(LabelCaps(color: color))
    }
}
