//
//  PlayerTabletop.swift
//  Edendale
//
//  Tabletop playback on a foldable iPhone (the iPhone Duo): stood half-open
//  in portrait, the upper half shows the picture and the lower half, lying
//  flat, holds the controls, as the system player does.
//
//  UIKit reports the hinge's status but not where the fold lies. The Duo's
//  inner screen folds across its middle in portrait (its 466 × 678 pt outer
//  screen covers one 669 × 475 pt half of the 669 × 951 pt inner one), so
//  the fold is the screen's horizontal midline whenever the window is
//  portrait and fills the screen. In landscape the fold runs top to bottom,
//  like a book's spine, and the player keeps its full-screen layout.
//

import CoreGraphics
#if os(iOS)
import SwiftUI
import UIKit
#endif

/// The hinge's status, without UIKit, so the layout rules can be tested on
/// every platform.
nonisolated enum PlayerHingeStatus: Equatable, Sendable {
    /// No hinge, or one the system can't read.
    case unknown
    case closed
    case partiallyOpen
    case fullyOpen
}

/// What the player knows about the fold: the hinge, its own window, and the
/// screen that window is on.
nonisolated struct PlayerFoldState: Equatable, Sendable {
    var hinge: PlayerHingeStatus = .unknown
    var windowSize: CGSize = .zero
    var screenSize: CGSize = .zero
}

/// The split between picture and controls while the phone stands half-open.
/// Measured in window points, safe areas included.
nonisolated struct PlayerTabletopLayout: Equatable, Sendable {
    /// The fold's distance from the top of the window; the picture fills
    /// everything above it.
    let foldY: CGFloat
    /// The window's full height.
    let height: CGFloat

    /// The flat lower half, below the fold, that holds the controls.
    var deckHeight: CGFloat { height - foldY }

    /// Below this, a half can't hold the picture or the controls.
    static let minimumHalfHeight: CGFloat = 320

    /// The tabletop split for this fold, or `nil` for the full-screen layout.
    static func resolve(_ fold: PlayerFoldState) -> PlayerTabletopLayout? {
        guard fold.hinge == .partiallyOpen else { return nil }
        let window = fold.windowSize
        // Only in portrait does the fold run across the window.
        guard window.height > window.width else { return nil }
        // A window that shares the screen can't tell where the fold
        // crosses it.
        guard abs(window.width - fold.screenSize.width) <= 1,
              abs(window.height - fold.screenSize.height) <= 1
        else { return nil }
        let foldY = (window.height / 2).rounded()
        guard foldY >= minimumHalfHeight, window.height - foldY >= minimumHalfHeight else { return nil }
        return PlayerTabletopLayout(foldY: foldY, height: window.height)
    }
}

#if os(iOS)
/// An invisible view that reports the hinge, its window, and the screen
/// whenever any of them changes. Hinge updates need iOS 27.1; earlier
/// systems, and phones without a hinge, report `.unknown`.
struct PlayerFoldReader: UIViewRepresentable {
    let onChange: (PlayerFoldState) -> Void

    func makeUIView(context: Context) -> ReaderView {
        ReaderView(onChange: onChange)
    }

    func updateUIView(_ view: ReaderView, context: Context) {
        view.onChange = onChange
    }

    final class ReaderView: UIView {
        var onChange: (PlayerFoldState) -> Void
        private var hinge: PlayerHingeStatus = .unknown
        private var reported: PlayerFoldState?

        init(onChange: @escaping (PlayerFoldState) -> Void) {
            self.onChange = onChange
            super.init(frame: .zero)
            isUserInteractionEnabled = false
            isAccessibilityElement = false
            if #available(iOS 27.1, *) {
                // Called with the current state when the view joins a
                // window, and on every change after that.
                addInteraction(UIHingeInteraction { [weak self] _, update in
                    guard let self else { return }
                    self.hinge = PlayerHingeStatus(update.hinge?.status)
                    self.report()
                })
            }
        }

        @available(*, unavailable)
        required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

        override func didMoveToWindow() {
            super.didMoveToWindow()
            report()
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            report()
        }

        private func report() {
            guard let window, let screen = window.windowScene?.screen else { return }
            let state = PlayerFoldState(
                hinge: hinge,
                windowSize: window.bounds.size,
                screenSize: screen.bounds.size
            )
            guard state != reported else { return }
            reported = state
            // Layout passes can run inside a SwiftUI update; hand the change
            // over on the next turn instead of mutating state mid-update.
            DispatchQueue.main.async { [weak self] in
                self?.onChange(state)
            }
        }
    }
}

private extension PlayerHingeStatus {
    @available(iOS 27.1, *)
    init(_ status: UIHinge.Status?) {
        switch status {
        case .closed: self = .closed
        case .partiallyOpen: self = .partiallyOpen
        case .fullyOpen: self = .fullyOpen
        default: self = .unknown
        }
    }
}
#endif
