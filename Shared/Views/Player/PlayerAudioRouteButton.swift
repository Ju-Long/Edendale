//
//  PlayerAudioRouteButton.swift
//  Edendale
//
//  System audio-output picker styled to match the player toolbar chips.
//  Tapping presents the OS route chooser (AirPlay, Bluetooth, speaker).
//

import AVKit
import SwiftUI

#if canImport(UIKit)
import UIKit
#endif

struct PlayerAudioRouteButton: View {
    var onFocus: (() -> Void)?

    var body: some View {
        RoutePicker(onFocus: onFocus)
            .frame(width: 40, height: 40)
            .glassBackground(in: Circle())
            .contentShape(Circle())
            .accessibilityLabel("Audio Output")
    }
}

// MARK: - iOS / tvOS / visionOS

#if os(iOS) || os(tvOS) || os(visionOS)
private struct RoutePicker: UIViewRepresentable {
    var onFocus: (() -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(onFocus: onFocus)
    }

    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.delegate = context.coordinator
        picker.prioritizesVideoDevices = false
        picker.tintColor = UIColor(Theme.textPrimary)
        picker.activeTintColor = UIColor(Theme.gold)
        picker.backgroundColor = .clear
        return picker
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {
        context.coordinator.onFocus = onFocus
        uiView.tintColor = UIColor(Theme.textPrimary)
        uiView.activeTintColor = UIColor(Theme.gold)
    }

    final class Coordinator: NSObject, AVRoutePickerViewDelegate {
        var onFocus: (() -> Void)?

        init(onFocus: (() -> Void)?) {
            self.onFocus = onFocus
        }

        nonisolated func routePickerViewWillBeginPresentingRoutes(_ routePickerView: AVRoutePickerView) {
            Task { @MainActor [weak self] in
                self?.onFocus?()
            }
        }
    }
}

// MARK: - macOS

#elseif os(macOS)
private struct RoutePicker: NSViewRepresentable {
    var onFocus: (() -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(onFocus: onFocus)
    }

    func makeNSView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.delegate = context.coordinator
        picker.isRoutePickerButtonBordered = false
        picker.setRoutePickerButtonColor(NSColor(Theme.textPrimary), for: .normal)
        picker.setRoutePickerButtonColor(NSColor(Theme.gold), for: .active)
        return picker
    }

    func updateNSView(_ nsView: AVRoutePickerView, context: Context) {
        context.coordinator.onFocus = onFocus
        nsView.setRoutePickerButtonColor(NSColor(Theme.textPrimary), for: .normal)
        nsView.setRoutePickerButtonColor(NSColor(Theme.gold), for: .active)
    }

    final class Coordinator: NSObject, AVRoutePickerViewDelegate {
        var onFocus: (() -> Void)?

        init(onFocus: (() -> Void)?) {
            self.onFocus = onFocus
        }

        nonisolated func routePickerViewWillBeginPresentingRoutes(_ routePickerView: AVRoutePickerView) {
            Task { @MainActor [weak self] in
                self?.onFocus?()
            }
        }
    }
}
#endif
