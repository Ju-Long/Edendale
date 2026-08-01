//
//  PlayerSettingsPanel.swift
//  Edendale
//
//  The ellipsis sidebar: playback speed in 0.05× steps, subtitle track
//  selection, auto-skip toggles, loop, and fit/fill aspect control.
//

import SwiftUI
import SwiftVLC

struct PlayerSettingsPanel: View {
    @Bindable var chrome: PlayerChromeModel
    let player: Player
    let item: PlaybackItem
    @State private var onlineSubtitles = OnlineSubtitlesModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 32) {
                header
                #if os(visionOS)
                visionFormatSection
                #endif
                speedSection
                subtitleSection
                OnlineSubtitlesSection(
                    model: onlineSubtitles,
                    chrome: chrome,
                    player: player,
                    item: item
                )
                playbackSection
                aspectSection
            }
            .padding(24)
        }
        .scrollIndicators(.hidden)
        .onChange(of: item.id) {
            onlineSubtitles.reset()
        }
    }

    #if os(visionOS)
    private var visionFormatSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Spatial Video").labelCaps()
            VisionFormatMenu(showsTitle: true)
        }
    }
    #endif

    // MARK: - Header

    private var header: some View {
        HStack {
            Text("Adjustments")
                .font(Typography.headlineMD)
                .textCase(.uppercase)
                .foregroundStyle(Theme.textPrimary)
            Spacer()
            PlayerIconChip(icon: .sidebarRight, isActive: true) {
                chrome.closePanel()
            }
        }
    }

    // MARK: - Speed

    private var speedSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Speed").labelCaps()

            HStack(spacing: 16) {
                PlayerTextChip(label: "−") {
                    chrome.decreaseRate()
                }
                Text(PlayerLogic.rateLabel(chrome.baseRate))
                    .font(Typography.titleLG)
                    .monospacedDigit()
                    .foregroundStyle(chrome.baseRate == 1.0 ? Theme.textPrimary : Theme.gold)
                    .frame(minWidth: 76)
                PlayerTextChip(label: "+") {
                    chrome.increaseRate()
                }
                Spacer()
                if chrome.baseRate != 1.0 {
                    PlayerTextChip(label: String(localized: "Reset")) {
                        chrome.resetRate()
                    }
                }
            }
        }
    }

    // MARK: - Subtitles

    private var subtitleSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Subtitles").labelCaps()

            if player.subtitleTracks.isEmpty {
                Text("No subtitle tracks in this file.")
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
            } else {
                VStack(spacing: 4) {
                    subtitleRow(name: String(localized: "Off"), isSelected: player.selectedSubtitleTrack == nil) {
                        player.selectedSubtitleTrack = nil
                    }
                    ForEach(player.subtitleTracks) { track in
                        subtitleRow(
                            name: trackLabel(track),
                            isSelected: player.selectedSubtitleTrack?.id == track.id
                        ) {
                            player.selectedSubtitleTrack = track
                        }
                    }
                }
            }
        }
    }

    private func subtitleRow(name: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button {
            action()
            chrome.showControls()
        } label: {
            HStack {
                Text(name)
                    .font(Typography.bodyLG)
                    .foregroundStyle(isSelected ? Theme.gold : Theme.textPrimary)
                    .lineLimit(1)
                Spacer()
                if isSelected {
                    Image(.check)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(Theme.gold)
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .background(isSelected ? Theme.surface : .clear, in: RoundedRectangle(cornerRadius: Theme.Radius.soft))
            .contentShape(Rectangle())
        }
        .playerChipStyle()
    }

    private func trackLabel(_ track: Track) -> String {
        if let language = track.language, !language.isEmpty, !track.name.localizedCaseInsensitiveContains(language) {
            return "\(track.name) (\(language))"
        }
        return track.name
    }

    // MARK: - Playback options

    private var playbackSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Playback").labelCaps()

            ArchiveToggle(isOn: $chrome.skipRecap) {
                optionLabel(
                    String(localized: "Skip Recap"),
                    detail: String(localized: "Jump past the first 90 seconds")
                )
            }
            ArchiveToggle(isOn: $chrome.skipCredits) {
                optionLabel(
                    String(localized: "Skip Credits"),
                    detail: String(localized: "End playback at the final 3 minutes")
                )
            }
            ArchiveToggle(isOn: $chrome.loopEnabled) {
                optionLabel(
                    String(localized: "Loop Video"),
                    detail: String(localized: "Restart playback when it ends")
                )
            }
            #if os(iOS)
            ArchiveToggle(isOn: $chrome.autoPiP) {
                optionLabel(
                    String(localized: "Auto Picture in Picture"),
                    detail: String(localized: "Float the video when you leave the app mid-play")
                )
            }
            #endif
        }
    }

    private func optionLabel(_ title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(Typography.bodyLG)
                .foregroundStyle(Theme.textPrimary)
            Text(detail)
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
        }
    }

    // MARK: - Aspect ratio

    private var aspectSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Aspect Ratio").labelCaps()

            Picker("Aspect Ratio", selection: $chrome.aspectFill) {
                Text("Fit").tag(false)
                Text("Fill").tag(true)
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        }
    }
}
