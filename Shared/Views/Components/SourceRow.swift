//
//  SourceRow.swift
//  Edendale
//
//  One linked source — local folder, network share, or cloud folder —
//  shared by the Settings source manager and the Downloaded screen's list,
//  so both read identically. A source that couldn't be scanned says why
//  (offline, or sign in again). Remove always confirms first: the copy says
//  files are left alone and that a saved login or account stays in
//  Settings → Accounts.
//

import SwiftUI

struct SourceRow: View {
    @Environment(LibraryController.self) private var library
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
                .padding(.horizontal, SettingsMetrics.highlightBleed)
                .padding(.vertical, verticalInset)
        }
        // Full-width data row: a quiet surface fill + gold border on focus,
        // not the button style's solid-gold flood (which would swallow the
        // name/path) and not tvOS's default white platter (illegible content).
        .archiveRowStyle()
        // On the Settings page the fill reaches past the text into the card
        // margin while the text lines up with the rows around it. The
        // grouped List insets rows itself, so there the bleed is zero.
        .padding(-SettingsMetrics.highlightBleed)
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
                // Credential-free by the model's contract: a file path, a
                // readable provider path, or `smb://host/share/folder`.
                // Never a userinfo URL.
                Text(folder.locationDescription)
                    .font(Typography.bodySM)
                    .foregroundStyle(Theme.textSecondary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .truncationMode(.middle)
                if let state {
                    Text(state.message)
                        .font(Typography.bodySM)
                        .foregroundStyle(Theme.gold)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            
            Spacer()
        }
        // Icon, name, kind, count, and path are one source. Rescan and
        // Remove reach VoiceOver as custom actions through the swipe
        // actions and context menu already attached to this element.
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(folder.name)
        .accessibilityValue(accessibilityValue)
    }

    private var state: LibraryController.SourceState? {
        library.sourceStates[folder.id]
    }

    private var accessibilityValue: String {
        [subtitle, folder.locationDescription, state?.message].compactMap(\.self).joined(separator: ", ")
    }

    /// The grouped List spaces sources 12 points apart. On the Settings page
    /// the card's row padding does that, leaving only the highlight's reach.
    private var verticalInset: CGFloat {
        #if os(macOS) || os(tvOS)
        SettingsMetrics.highlightBleed
        #else
        12
        #endif
    }

    /// Kind badge, account, and item count, e.g. "SMB · 12 items" or
    /// "Google Drive · you@example.com · 12 items".
    private var subtitle: String {
        let items = folder.totalItemCount == 1
            ? String(localized: "1 item")
            : String(localized: "\(folder.totalItemCount) items")
        var parts = [folder.sourceKind.displayName]
        if folder.sourceKind.isCloudAccount, let account = folder.username {
            parts.append(account)
        }
        parts.append(items)
        return parts.joined(separator: " · ")
    }

    /// Says the two things a user needs before unlinking: nothing where the
    /// files live is touched, and a saved login or linked account stays
    /// (deleting a synced one would sign the user's other devices out).
    private var removeMessage: String {
        let kind = folder.sourceKind
        switch kind {
        case .local:
            return String(localized: "“\(folder.name)” is removed from your library. Nothing on your disk is deleted.")
        case .googleDrive, .oneDrive, .dropbox:
            return String(localized: "“\(folder.name)” is removed from your library. Nothing in \(kind.displayName) is deleted, and your account stays linked in Settings → Accounts.")
        case .s3:
            return String(localized: "“\(folder.name)” is removed from your library. Nothing in the bucket is deleted, and its access key stays in Settings → Accounts.")
        case .nfs:
            return String(localized: "“\(folder.name)” is removed from your library. Nothing on the server is deleted.")
        case .smb, .sftp, .webdav:
            let host = folder.remoteURL?.host() ?? kind.displayName
            return String(localized: "“\(folder.name)” is removed from your library. Nothing on the server is deleted, and the saved login for \(host) stays in Settings → Accounts.")
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
