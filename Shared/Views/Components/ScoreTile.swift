//
//  ScoreTile.swift
//  Edendale
//
//  One cell of the Critical Consensus scorecard: label-caps source
//  name over a Bebas score.
//

import SwiftUI

struct ScoreTile: View {
    let source: String
    let value: String
    var suffix: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(source).labelCaps()
            HStack(alignment: .firstTextBaseline, spacing: 3) {
                Text(value)
                    .font(Typography.headlineMD)
                    .foregroundStyle(Theme.textPrimary)
                if let suffix {
                    Text(suffix)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.textSecondary)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
