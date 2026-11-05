import SwiftUI

/// Settings → Player Controls ▸ Playback: whether the player offers to skip
/// intros, recaps, and credits. What TheIntroDB receives is in the privacy
/// policy; About credits the timestamps.
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
            #if os(visionOS)
            SettingsNote(String(localized: "Skip prompts are available in standard playback. Spatial playback in the system player is not supported yet."))
            #endif
        }
    }
}
