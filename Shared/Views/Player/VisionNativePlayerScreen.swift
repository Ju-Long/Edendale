#if os(visionOS)

import SwiftUI

/// Hosts Apple's spatial-media presentation while keeping Edendale's session,
/// resume, progress, and dismissal lifecycle intact.
struct VisionNativePlayerScreen: View {
    @Environment(PlayerSession.self) private var session

    let item: PlaybackItem
    let exit: () -> Void

    var body: some View {
        ZStack {
            Theme.background
                .ignoresSafeArea()

            VisionAVPlayerHost(
                playbackItem: item,
                configuration: .init(
                    initialPosition: session.visionInitialPosition(for: item),
                    packedLayout: session.visionForcedLayout
                )
            ) { event in
                session.handleVisionPlayerEvent(event, itemID: item.id)
            }
            .ignoresSafeArea()

            VStack {
                HStack {
                    PlayerExitButton(action: exit)
                    Spacer()
                    VisionFormatMenu()
                }
                Spacer()
            }
            .padding(24)
        }
        .preferredColorScheme(.dark)
        .onDisappear {
            if session.visionNativeItem?.id == item.id {
                session.end()
            }
        }
    }
}

#endif
