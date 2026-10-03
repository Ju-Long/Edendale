//
//  PlayerPointerSurface.swift
//  Edendale
//
//  The macOS player's mouse input over empty video: a click shows or hides
//  the controls, and pressing and holding either half plays at that side's
//  hold speed (Settings ▸ App Controls) until the button comes up — the
//  pointer counterpart to the touch gesture layer and the Siri Remote hold.
//

#if os(macOS)
import SwiftUI

struct PlayerPointerSurface: View {
    let chrome: PlayerChromeModel

    private enum Press {
        case idle
        /// Button down; still a click until it's held long enough.
        case pending
        case holding
        /// Moved too far to be a click or a hold.
        case dragged
    }

    @State private var press: Press = .idle
    @State private var holdTask: Task<Void, Never>?

    /// Stillness before a press becomes a hold, as on touch screens.
    private static let holdDelay: Duration = .milliseconds(400)
    /// Pointer travel that stops a press from counting as a click or hold.
    private static let dragThreshold: CGFloat = 6

    var body: some View {
        GeometryReader { geo in
            Color.clear
                .contentShape(Rectangle())
                .gesture(pressGesture(width: geo.size.width))
        }
    }

    private func pressGesture(width: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                switch press {
                case .idle:
                    press = .pending
                    scheduleHold(on: value.startLocation.x < width / 2 ? .left : .right)
                case .pending:
                    let travel = hypot(value.translation.width, value.translation.height)
                    if travel > Self.dragThreshold {
                        cancelHold()
                        press = .dragged
                    }
                case .holding, .dragged:
                    break
                }
            }
            .onEnded { _ in
                cancelHold()
                switch press {
                case .pending: chrome.toggleControls()
                case .holding: chrome.endHoldRate()
                case .idle, .dragged: break
                }
                press = .idle
            }
    }

    private func scheduleHold(on side: HoldSide) {
        cancelHold()
        holdTask = Task {
            try? await Task.sleep(for: Self.holdDelay)
            guard !Task.isCancelled, press == .pending else { return }
            press = .holding
            chrome.beginHold(on: side)
        }
    }

    private func cancelHold() {
        holdTask?.cancel()
        holdTask = nil
    }
}
#endif
