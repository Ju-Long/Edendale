//
//  SyncSection.swift
//  Edendale
//
//  Settings → Sync: the one sync location this device writes to, the
//  space name, when it last synced, warnings naming devices whose files
//  were skipped, and the ways in: choose a location here, hand this
//  device's location to another device, or receive one. Off by default;
//  turning it off keeps the local history and offers to delete this
//  device's file from the old location (FEATURES-27.1.md §5.1).
//

import SwiftUI

struct SyncSection: View {
    @Environment(SyncCoordinator.self) private var sync
    @Environment(CloudAccountStore.self) private var accounts

    @State private var showSetup = false
    @State private var handoffRole: HandoffV2.Role?
    @State private var confirmTurnOff = false
    @State private var isDeletingOldFile = false
    @State private var oldFileError: String?
    @State private var deviceToRemove: (fileName: String, name: String)?

    var body: some View {
        SettingsSection(String(localized: "Sync")) {
            if let location = sync.location {
                SettingsRow(
                    String(localized: "Location"),
                    value: location.provider.displayName,
                    detail: location.detail(accounts: accounts.vault)
                )
                SettingsRow(String(localized: "Space"), value: location.space, detail: lastSyncedDetail)
                if let error = sync.status.lastError {
                    SettingsNote(error, color: Theme.gold)
                }
                ForEach(sync.status.warnings, id: \.self) { warning in
                    SettingsNote(warning, color: Theme.gold)
                }
                if !sync.status.devices.isEmpty {
                    SettingsRow(
                        String(localized: "Other Devices"),
                        value: sync.status.devices.joined(separator: ", "),
                        detail: String(localized: "Removing a device deletes its file from the space; its records are already in every other file. To cut it off, also revoke Edendale's access in your storage account.")
                    ) {
                        ForEach(sync.knownDevices, id: \.fileName) { device in
                            Button("Remove \(device.name)", role: .destructive) {
                                deviceToRemove = device
                            }
                            .archiveButtonStyle(.ghost)
                        }
                    }
                }
                SettingsActions {
                    Button(sync.status.isSyncing ? String(localized: "Syncing…") : String(localized: "Sync Now")) {
                        sync.syncNow()
                    }
                    .archiveButtonStyle(.secondary)
                    .disabled(sync.status.isSyncing)
                    Button("Change Location…") { showSetup = true }
                        .archiveButtonStyle(.ghost)
                    Button("Turn Off", role: .destructive) { confirmTurnOff = true }
                        .archiveButtonStyle(.ghost)
                }
            } else {
                SettingsNote(String(localized: "Keep your watch history in step across your devices through storage you own: a folder, iCloud Drive, Google Drive, OneDrive, Dropbox, or a WebDAV, SMB, or SFTP server. Off until you choose a location."))
                SettingsActions {
                    Button("Choose Location…") { showSetup = true }
                        .archiveButtonStyle(.secondary)
                }
            }

            SettingsNote(String(localized: "Sync files are readable JSON: anyone who can open the sync folder can read your watch history. Logins never go into the folder."))

            SettingsActions {
                Button("Set Up Another Device…") { handoffRole = .sender }
                    .archiveButtonStyle(.ghost)
                Button("Receive from Another Device…") { handoffRole = .receiver }
                    .archiveButtonStyle(.ghost)
            }

            if let previous = sync.previousLocation {
                SettingsNote(String(localized: "This device's file is still in \(previous.provider.displayName) (\(previous.space)). Delete it, or keep it there."))
                if let oldFileError {
                    SettingsNote(oldFileError, color: Theme.gold)
                }
                SettingsActions {
                    Button(isDeletingOldFile ? String(localized: "Deleting…") : String(localized: "Delete My File There"), role: .destructive) {
                        deleteOldFile()
                    }
                    .archiveButtonStyle(.ghost)
                    .disabled(isDeletingOldFile)
                    Button("Keep It") { sync.forgetPreviousLocation() }
                        .archiveButtonStyle(.ghost)
                }
            }
        }
        .sheet(isPresented: $showSetup) { SyncLocationSetupView() }
        .sheet(item: $handoffRole) { role in SyncHandoffView(role: role) }
        .confirmationDialog("Turn Off Sync", isPresented: $confirmTurnOff) {
            Button("Turn Off", role: .destructive) { sync.setLocation(nil) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your watch history stays on this device. Your file stays in the sync folder until you delete it.")
        }
        .confirmationDialog(
            "Remove Device",
            isPresented: Binding(get: { deviceToRemove != nil }, set: { if !$0 { deviceToRemove = nil } }),
            presenting: deviceToRemove
        ) { device in
            Button("Remove \(device.name)", role: .destructive) {
                Task { try? await sync.removeDevice(fileName: device.fileName) }
            }
            Button("Cancel", role: .cancel) {}
        } message: { device in
            Text("Deletes \(device.name)'s file from the space. If it syncs again it writes a new one, so also sign Edendale out there or revoke its access at your storage provider.")
        }
    }

    private var lastSyncedDetail: String {
        if let date = sync.status.lastSyncedAt {
            return String(localized: "Last synced \(date.formatted(date: .abbreviated, time: .shortened)).")
        }
        return String(localized: "Not synced yet.")
    }

    private func deleteOldFile() {
        isDeletingOldFile = true
        oldFileError = nil
        Task {
            do {
                try await sync.deleteFileFromPreviousLocation()
            } catch {
                oldFileError = error.localizedDescription
            }
            isDeletingOldFile = false
        }
    }
}

extension HandoffV2.Role: Identifiable {
    var id: String { rawValue }
}
