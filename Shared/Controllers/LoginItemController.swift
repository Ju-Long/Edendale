//
//  LoginItemController.swift
//  Edendale
//
//  User-controlled macOS launch-at-login registration. Service Management is
//  the source of truth because users can also change this in System Settings.
//

#if os(macOS)
import Foundation
import ServiceManagement

@MainActor
@Observable
final class LoginItemController {
    private let service: SMAppService

    private(set) var status: SMAppService.Status
    private(set) var errorMessage: String?

    init(service: SMAppService = .mainApp) {
        self.service = service
        self.status = service.status
    }

    var isRegistered: Bool {
        switch status {
        case .enabled, .requiresApproval:
            true
        case .notRegistered, .notFound:
            false
        @unknown default:
            false
        }
    }

    var isAvailable: Bool {
        status != .notFound
    }

    var requiresApproval: Bool {
        status == .requiresApproval
    }

    var statusMessage: String {
        switch status {
        case .notRegistered:
            String(localized: "Open Edendale automatically after you sign in.")
        case .enabled:
            String(localized: "Edendale will open automatically after you sign in.")
        case .requiresApproval:
            String(localized: "Allow Edendale under System Settings > General > Login Items.")
        case .notFound:
            String(localized: "macOS couldn't locate Edendale's login item.")
        @unknown default:
            String(localized: "The launch-at-login status is unavailable.")
        }
    }

    func refresh() {
        status = service.status
    }

    func setEnabled(_ enabled: Bool) {
        errorMessage = nil
        refresh()

        do {
            if enabled {
                guard status != .enabled else { return }
                if status == .requiresApproval {
                    openSystemSettings()
                    return
                }
                try service.register()
            } else {
                guard status != .notRegistered else { return }
                try service.unregister()
            }
        } catch {
            errorMessage = String(localized: "Couldn't update Login Items: \(error.localizedDescription)")
        }

        refresh()
    }

    func openSystemSettings() {
        SMAppService.openSystemSettingsLoginItems()
    }
}
#endif
