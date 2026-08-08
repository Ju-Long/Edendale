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
        Menu {
            Button("Rescan", image: .arrowRotateRight, action: onRescan)
                .archiveButtonStyle(.secondary)
                .accessibilityLabel("Rescan")
            Button("Remove", image: .trashCan, action: requestRemoveConfirmation)
                .archiveButtonStyle(.secondary)
                .accessibilityLabel("Remove")
        } label: {
            content
        }
        // Full-width data row: a quiet surface fill + gold border on focus,
        // not the button style's solid-gold flood (which would swallow the
        // name/path) and not tvOS's default white platter (illegible content).
        .archiveRowStyle()
        .modify { view in
            #if !os(tvOS)
            view
                .swipeActions(edge: .leading) {
                    Button(action: onRescan) {
                        Label("Rescan", image: .arrowRotateRight)
                    }
                }
                .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                    Button(role: .destructive, action: requestRemoveConfirmation) {
                        Label("Remove", image: .trashCan)
                    }
                }
            #endif
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
            // The glyph only restates the kind badge in `subtitle`.
            Image(folder.isRemote ? .link : .folderClosed)
                .font(.system(size: 18))
                .foregroundStyle(Theme.textSecondary)
                .accessibilityHidden(true)
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
                    .multilineTextAlignment(.leading)
                    .truncationMode(.middle)
            }
            
            Spacer()
        }
        .padding(.vertical, 12)
        // Icon, name, kind, count, and path are one source. Rescan and
        // Remove reach VoiceOver as custom actions through the swipe
        // actions and context menu already attached to this element.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(folder.name)
        .accessibilityValue("\(subtitle), \(folder.folderPath)")
    }

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
