//
//  PlayerIdlePointer.swift
//  Edendale
//
//  The macOS player hides the pointer once it has rested over the video for
//  five seconds, as the controls fade after five seconds of inactivity; the
//  next move brings it straight back.
//

#if os(macOS)
import AppKit
import SwiftUI

@MainActor
@Observable
final class PlayerIdlePointer {

    /// Rest before the pointer hides.
    nonisolated static let defaultDelay: Duration = .seconds(5)

    /// Whether the resting pointer is hidden.
    private(set) var isHidden = false

    @ObservationIgnored private let delay: Duration
    @ObservationIgnored private let isButtonHeld: () -> Bool
    /// Where the pointer last stood over the video; nil while it is elsewhere.
    @ObservationIgnored private var location: CGPoint?
    @ObservationIgnored private var countdown: Task<Void, Never>?

    init(
        delay: Duration = defaultDelay,
        isButtonHeld: @escaping () -> Bool = { NSEvent.pressedMouseButtons != 0 }
    ) {
        self.delay = delay
        self.isButtonHeld = isButtonHeld
    }

    /// The pointer is over the video at `point`. Hover also reports a pointer
    /// standing still while the view under it changes (the controls fading,
    /// say), which must not bring a hidden pointer back.
    func pointerMoved(to point: CGPoint) {
        guard point != location else { return }
        location = point
        restartCountdown()
    }

    /// The pointer left the video.
    func pointerLeft() {
        countdown?.cancel()
        countdown = nil
        location = nil
        isHidden = false
    }

    /// Shows the pointer and starts the countdown afresh while it is over
    /// the video.
    func restartCountdown() {
        countdown?.cancel()
        isHidden = false
        guard location != nil else { return }
        countdown = Task { [weak self, delay] in
            try? await Task.sleep(for: delay)
            guard let self, !Task.isCancelled else { return }
            // A held button is a drag or a hold-speed press in progress;
            // the pointer stays until it comes up.
            if self.isButtonHeld() {
                self.restartCountdown()
            } else {
                self.isHidden = true
            }
        }
    }
}

private struct IdlePointerHiding: ViewModifier {
    let isEnabled: Bool
    @State private var pointer = PlayerIdlePointer()

    func body(content: Content) -> some View {
        content
            .pointerVisibility(isEnabled && pointer.isHidden ? .hidden : .automatic)
            .onContinuousHover { phase in
                switch phase {
                case .active(let point): pointer.pointerMoved(to: point)
                case .ended: pointer.pointerLeft()
                }
            }
            // Coming back from the guide counts as fresh activity.
            .onChange(of: isEnabled) { _, _ in pointer.restartCountdown() }
            .onDisappear { pointer.pointerLeft() }
    }
}

extension View {
    /// Hides the pointer after it rests over this view for five seconds.
    func hidesIdlePointer(_ isEnabled: Bool = true) -> some View {
        modifier(IdlePointerHiding(isEnabled: isEnabled))
    }
}
#endif
