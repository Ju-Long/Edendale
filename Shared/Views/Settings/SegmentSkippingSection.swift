import SwiftUI

struct SegmentSkippingSection: View {
    @Environment(PlayerSession.self) private var session

    var body: some View {
        Section {
            Toggle("Skip Prompts", isOn: Binding(
                get: { session.segmentSkipping.isEnabled },
                set: { session.segmentSkipping.isEnabled = $0 }
            ))
            Text("Show buttons to skip intros, recaps, and credits when community timestamps are available. Playback continues until you choose to skip.")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
            Text("When enabled, TheIntroDB receives the title’s TMDB ID, episode numbers, video duration, and your IP address. Your video and filename stay on your device. Timestamps are kept only for the playback session.")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
            #if os(visionOS)
            Text("Skip prompts are available in standard playback. Spatial playback in the system player is not supported yet.")
                .font(Typography.bodySM)
                .foregroundStyle(Theme.textSecondary)
            #endif
            Link("Timestamps by TheIntroDB", destination: URL(string: "https://theintrodb.org")!)
            Link("TheIntroDB Privacy Policy", destination: URL(string: "https://theintrodb.org/docs/privacy")!)
        } header: {
            Text("Playback").labelCaps()
        }
    }
}
