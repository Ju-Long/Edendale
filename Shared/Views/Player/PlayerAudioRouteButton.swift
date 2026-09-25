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
    var diameter: CGFloat = 40
    var onFocus: (() -> Void)?

    var body: some View {
        RoutePicker(onFocus: onFocus)
            #if os(tvOS)
            // The plain-style glyph fills over half the picker's frame (~50 pt
            // at 88 pt); scale it to the other toolbar chips' glyph size.
            .scaleEffect(0.75)
            #endif
            .frame(width: diameter, height: diameter)
            .glassBackground(in: Circle())
            .contentShape(Circle())
            .accessibilityLabel("Audio Output")
    }
}

// MARK: - iOS / tvOS

#if os(iOS) || os(tvOS)
private struct RoutePicker: UIViewRepresentable {
    var onFocus: (() -> Void)?

    func makeCoordinator() -> Coordinator {
        Coordinator(onFocus: onFocus)
    }

    func makeUIView(context: Context) -> AVRoutePickerView {
        let picker = AVRoutePickerView()
        picker.delegate = context.coordinator
        picker.prioritizesVideoDevices = false
        #if os(tvOS)
        // The system style adds a fixed 80 pt blurred platter; drop it so
        // the chip's glass sets the size, like the other toolbar chips.
        picker.routePickerButtonStyle = .plain
        #endif
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

// MARK: - visionOS

#elseif os(visionOS)
private struct RoutePicker: View {
    var onFocus: (() -> Void)?

    @State private var currentRoute = AVAudioSession.sharedInstance().currentRoute

    var body: some View {
        Button {
            onFocus?()
        } label: {
            Image(systemName: outputIcon)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .frame(width: 20, height: 20)
                .foregroundStyle(Theme.textPrimary)
        }
        .buttonStyle(.plain)
        .onReceive(
            NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)
        ) { _ in
            currentRoute = AVAudioSession.sharedInstance().currentRoute
        }
    }

    private var outputIcon: String {
        guard let output = currentRoute.outputs.first else {
            return "speaker.wave.2"
        }
        switch output.portType {
        case .bluetoothA2DP, .bluetoothLE, .bluetoothHFP:
            return "wave.3.right"
        case .headphones:
            return "headphones"
        case .airPlay:
            return "airplayaudio"
        default:
            return "speaker.wave.2"
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
