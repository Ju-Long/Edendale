//
//  SourceRow.swift
//  Edendale
//
//  One linked source — local folder or network share — shared by the
//  Settings source manager and the Downloaded screen's list, so both
//  read identically. Remove always confirms first: the copy says files
//  are left alone and the server's saved login is forgotten.
//

import SwiftUI

struct SourceRow: View {
    let folder: VideoFolder
    /// Settings shows tappable Rescan/Remove buttons; Downloaded keeps the
    /// row clean and leaves them to the context menu.
    var showsActions: Bool = false
    let onRescan: () -> Void
    let onRemove: () -> Void

    @State private var isConfirmingRemove = false

    var body: some View {
        Group {
            if showsActions {
                #if os(tvOS)
                // tvOS has no swipe actions, so Rescan and Remove ride on the
                // row's trailing edge as focusable buttons instead.
                trailingActionRow
                #else
                content
                    .sourceSwipeActions(
                        onRescan: onRescan,
                        onRequestRemove: requestRemoveConfirmation
                    )
                #endif
            } else {
                content.contextMenu { menuItems }
            }
        }
        .alert("Remove Source", isPresented: $isConfirmingRemove) {
            Button("Cancel", role: .cancel) {}
            Button("Remove", role: .destructive, action: onRemove)
        } message: {
            Text(removeMessage)
        }
    }

    private func requestRemoveConfirmation() {
        isConfirmingRemove = true
    }

    @ViewBuilder
    private var content: some View {
        HStack(spacing: 14) {
            Image(folder.isRemote ? .link : .folderClosed)
                .font(.system(size: 18))
                .foregroundStyle(Theme.textSecondary)
            VStack(alignment: .leading, spacing: 3) {
                Text(folder.name)
                    .font(Typography.text(15, weight: .semibold))
                    .foregroundStyle(Theme.textPrimary)
                Text(subtitle)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                // Credential-free by the model's contract: a file path, or
                // `smb://host/share/folder`. Never a userinfo URL.
                Text(folder.folderPath)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(2)
                    .truncationMode(.middle)
            }
        }
        .padding(.vertical, 12)
    }

    #if os(tvOS)
    /// The row plus its two trailing controls, standing in for the swipe
    /// actions tvOS can't offer. Focus moves left/right between Rescan and
    /// Remove; Remove routes through the same confirmation alert as every
    /// other surface.
    @ViewBuilder
    private var trailingActionRow: some View {
        HStack(spacing: 12) {
            content
            Spacer(minLength: 0)
            Button("", image: .arrowRotateRight, action: onRescan)
                .archiveButtonStyle(.secondary)
                .accessibilityLabel("Rescan")
            Button("", image: .trashCan, action: requestRemoveConfirmation)
                .archiveButtonStyle(.secondary)
                .accessibilityLabel("Remove")
        }
    }
    #endif

    /// Kind badge and item count, e.g. "SMB · 12 items".
    private var subtitle: String {
        let items = folder.totalItemCount == 1
            ? String(localized: "1 item")
            : String(localized: "\(folder.totalItemCount) items")
        return "\(folder.sourceKind.displayName) · \(items)"
    }

    /// Says the two things a user needs before unlinking: nothing on disk is
    /// touched, and the server's saved login goes with it once nothing else
    /// needs it.
    private var removeMessage: String {
        if let host = folder.remoteURL?.host() {
            String(localized: "“\(folder.name)” is removed from your library and the saved login for \(host) is forgotten, unless another source still uses that server. Nothing on the server is deleted.")
        } else {
            String(localized: "“\(folder.name)” is removed from your library. Nothing on your disk is deleted.")
        }
    }
    
    @ViewBuilder
    private var menuItems: some View {
        Button(action: onRescan) {
            Label("Rescan", image: .arrowRotateRight)
        }
        Button(role: .destructive, action: requestRemoveConfirmation) {
            Label("Remove", image: .trashCan)
        }
    }
}

#if !os(tvOS)
private extension View {
    func sourceSwipeActions(
        onRescan: @escaping () -> Void,
        onRequestRemove: @escaping () -> Void
    ) -> some View {
        self
            .swipeActions(edge: .leading) {
                Button(action: onRescan) {
                    Label("Rescan", image: .arrowRotateRight)
                }
            }
            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                Button(role: .destructive, action: onRequestRemove) {
                    Label("Remove", image: .trashCan)
                }
            }
    }
}
#endif
