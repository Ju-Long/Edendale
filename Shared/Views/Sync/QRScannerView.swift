//
//  QRScannerView.swift
//  Edendale
//
//  The camera view that reads a handoff code on iPhone and iPad, through
//  VisionKit's data scanner. The first `edendale://handoff` code seen is
//  handed back once; other codes are ignored.
//

#if os(iOS)
import SwiftUI
import Vision
import VisionKit

struct QRScannerView: UIViewControllerRepresentable {
    let onCode: (URL) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onCode: onCode)
    }

    func makeUIViewController(context: Context) -> UIViewController {
        guard DataScannerViewController.isSupported, DataScannerViewController.isAvailable else {
            return UIHostingController(rootView: unavailable)
        }
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        context.coordinator.scanner = scanner
        return scanner
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {
        guard let scanner = controller as? DataScannerViewController, !scanner.isScanning else { return }
        try? scanner.startScanning()
    }

    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Coordinator) {
        (controller as? DataScannerViewController)?.stopScanning()
    }

    private var unavailable: some View {
        VStack(spacing: 12) {
            Text("The camera isn't available. Allow camera access for Edendale in Settings, or show a code on this device instead.")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .padding()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.surfaceLow)
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onCode: (URL) -> Void
        weak var scanner: DataScannerViewController?
        private var delivered = false

        init(onCode: @escaping (URL) -> Void) {
            self.onCode = onCode
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !delivered else { return }
            for item in addedItems {
                guard case .barcode(let barcode) = item, let text = barcode.payloadStringValue,
                      let url = URL(string: text), HandoffV2.QRPayload(url: url) != nil
                else { continue }
                delivered = true
                dataScanner.stopScanning()
                onCode(url)
                return
            }
        }
    }
}
#endif
