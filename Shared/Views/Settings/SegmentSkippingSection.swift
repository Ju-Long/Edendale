import SwiftUI

struct SegmentSkippingSection: View {
    @Environment(PlayerSession.self) private var session

    var body: some View {
        SettingsSection(String(localized: "Playback")) {
            SettingsToggleRow(
                String(localized: "Skip Prompts"),
                detail: String(localized: "Show buttons to skip intros, recaps, and credits when community timestamps are available. Playback continues until you choose to skip."),
                isOn: Binding(
                    get: { session.segmentSkipping.isEnabled },
                    set: { session.segmentSkipping.isEnabled = $0 }
                )
            )
            SettingsNote(String(localized: "When enabled, TheIntroDB receives the title’s TMDB ID, episode numbers, video duration, and your IP address. Your video and filename stay on your device. Timestamps are kept only for the playback session."))
            #if os(visionOS)
            SettingsNote(String(localized: "Skip prompts are available in standard playback. Spatial playback in the system player is not supported yet."))
            #endif
            SettingsActions {
                Link("Timestamps by TheIntroDB", destination: URL(string: "https://theintrodb.org")!)
                    .settingsLinkStyle()
                Link("TheIntroDB Privacy Policy", destination: URL(string: "https://theintrodb.org/docs/privacy")!)
                    .settingsLinkStyle()
            }
        }
    }
}
