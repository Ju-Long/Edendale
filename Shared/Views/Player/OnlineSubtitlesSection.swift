//
//  OnlineSubtitlesSection.swift
//  Edendale
//
//  Explicit-search controls and compact Wyzie result rows for the narrow
//  player settings panel.
//

import SwiftUI
import SwiftVLC

struct OnlineSubtitlesSection: View {
    @Environment(WyzieKeyStore.self) private var keys
    @Bindable var model: OnlineSubtitlesModel
    let chrome: PlayerChromeModel
    let player: Player
    let item: PlaybackItem

    private let resultLimit = 25

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Online Subtitles").labelCaps()

            if !keys.isConfigured {
                Text("A Wyzie API key is needed for online subtitle search. Add one in Settings.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            } else if let lookup = item.subtitleLookup {
                searchControls(lookup: lookup)
                searchState
                resultList
            } else {
                Text("Online subtitle search needs a TMDB match for this file.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
        }
    }

    @ViewBuilder
    private func searchControls(
        lookup: (id: String, season: Int?, episode: Int?)
    ) -> some View {
        Menu("Language: \(selectedLanguageLabel)") {
            ForEach(OnlineSubtitlesModel.availableLanguages.indices, id: \.self) { index in
                let option = OnlineSubtitlesModel.availableLanguages[index]
                Button {
                    model.language = option.code
                    chrome.showControls()
                } label: {
                    HStack {
                        Text(option.label)
                        if model.language == option.code {
                            Image(.check)
                        }
                    }
                }
            }
        }
        .archiveButtonStyle(.secondary)

        // Font and color ride on the label, not the control: on tvOS the
        // control also draws the On/Off state, which owns its own color.
        ArchiveToggle(isOn: $model.hearingImpaired) {
            Text("Hearing Impaired")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textPrimary)
        }

        PlayerTextChip(
            label: String(localized: "Search"),
            onFocus: { chrome.showControls() }
        ) {
            chrome.showControls()
            model.search(lookup: lookup, key: keys.resolvedKey)
        }
        .disabled(model.phase == .searching)
    }

    @ViewBuilder
    private var searchState: some View {
        switch model.phase {
        case .idle, .results:
            if model.phase == .results && model.results.isEmpty {
                Text("No subtitles found.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
        case .searching:
            HStack(spacing: 8) {
                ProgressView()
                    .tint(Theme.gold)
                Text("Searching…")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
        case .failed(let message):
            Text(message)
                .font(Typography.bodySM)
                .foregroundStyle(Theme.gold)
        }
    }

    @ViewBuilder
    private var resultList: some View {
        if !model.results.isEmpty {
            VStack(spacing: 4) {
                ForEach(Array(model.results.prefix(resultLimit))) { subtitle in
                    resultRow(subtitle)
                }
            }

            let remaining = model.results.count - resultLimit
            if remaining > 0 {
                Text("\(remaining) more matched.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            }
        }
    }

    private func resultRow(_ subtitle: WyzieSubtitle) -> some View {
        Button {
            chrome.showControls()
            Task {
                await model.download(subtitle, into: player)
            }
        } label: {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(subtitle.display)
                        .font(Typography.bodyLG)
                        .foregroundStyle(Theme.textPrimary)
                        .lineLimit(1)

                    let detail = resultDetail(for: subtitle)
                    if !detail.isEmpty {
                        Text(detail)
                            .font(Typography.bodySM)
                            .foregroundStyle(Theme.textSecondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 8)
                trailingState(for: subtitle)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(
                Theme.surface,
                in: RoundedRectangle(cornerRadius: Theme.Radius.soft)
            )
            .contentShape(Rectangle())
        }
        .playerChipStyle(onFocus: { chrome.showControls() })
        .disabled(
            model.downloadedIDs.contains(subtitle.id)
                || model.downloadingID != nil
        )
    }

    @ViewBuilder
    private func trailingState(for subtitle: WyzieSubtitle) -> some View {
        if model.downloadingID == subtitle.id {
            ProgressView()
                .controlSize(.small)
                .tint(Theme.gold)
        } else if model.downloadedIDs.contains(subtitle.id) {
            Image(.check)
                .font(Typography.bodySM.weight(.bold))
                .foregroundStyle(Theme.gold)
        } else {
            Text("Download")
                .font(Typography.labelCaps)
                .textCase(.uppercase)
                .kerning(1.2)
                .foregroundStyle(Theme.gold)
        }
    }

    private var selectedLanguageLabel: String {
        OnlineSubtitlesModel.availableLanguages.first {
            $0.code == model.language
        }?.label ?? model.language.uppercased()
    }

    private func resultDetail(for subtitle: WyzieSubtitle) -> String {
        var parts: [String] = []
        if let release = subtitle.release?.trimmingCharacters(
            in: .whitespacesAndNewlines
        ), !release.isEmpty {
            parts.append(release)
        } else if let fileName = subtitle.fileName?.trimmingCharacters(
            in: .whitespacesAndNewlines
        ), !fileName.isEmpty {
            parts.append(fileName)
        }
        let format = subtitle.format.trimmingCharacters(
            in: .whitespacesAndNewlines
        )
        if !format.isEmpty {
            parts.append(format.uppercased())
        }
        if subtitle.isHearingImpaired {
            parts.append(String(localized: "HI"))
        }
        return parts.joined(separator: " · ")
    }
}
