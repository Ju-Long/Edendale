//
//  QRCodeView.swift
//  Edendale
//
//  Generates a scannable QR code for a short-lived URL. The QR bitmap is
//  necessarily created at runtime; its quiet-zone color still comes from the
//  asset catalog like every other UI color.
//

import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI

struct QRCodeView: View {
    let url: URL

    private let image: CGImage?

    init(url: URL) {
        self.url = url
        image = Self.makeImage(from: url.absoluteString)
    }

    var body: some View {
        Group {
            if let image {
                Image(decorative: image, scale: 1)
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 208, height: 208)
                    .padding(20)
                    .background(Theme.qrCodeBackground)
                    .clipShape(.rect(cornerRadius: Theme.Radius.soft))
            } else {
                Text("QR code unavailable")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("TMDB account approval QR code")
        .accessibilityValue(url.absoluteString)
    }

    private static func makeImage(from value: String) -> CGImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(value.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage else { return nil }
        return CIContext().createCGImage(outputImage, from: outputImage.extent)
    }
}
