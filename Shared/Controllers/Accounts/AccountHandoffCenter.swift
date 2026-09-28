//
//  AccountHandoffCenter.swift
//  Edendale
//
//  The iPhone and iPad side of AccountHandoff: an application-service
//  listener started at launch (DeviceDiscoveryUI connects only to a
//  listener that is already running), which holds one Apple TV request at a
//  time until the user approves or declines it in AccountHandoffRequestView.
//  When a request arrives while Edendale is in the background, a local
//  notification brings the user back to it.
//

#if os(iOS)
import Foundation
import Network
import UIKit
import UserNotifications

@Observable
@MainActor
final class AccountHandoffCenter {

    struct PendingRequest: Identifiable {
        let id = UUID()
        let request: AccountHandoff.Request
        fileprivate let connection: NWConnection
    }

    private(set) var pending: PendingRequest?

    @ObservationIgnored private var listener: NWListener?
    @ObservationIgnored private let queue = DispatchQueue(label: "Edendale.AccountHandoff.listener")

    /// Starts listening for Apple TVs. Safe to call more than once.
    func start() {
        guard listener == nil else { return }
        do {
            let listener = try NWListener(applicationService: AccountHandoff.serviceName)
            listener.newConnectionHandler = { [weak self] connection in
                guard let center = self else {
                    connection.cancel()
                    return
                }
                Task { @MainActor in center.accept(connection) }
            }
            listener.stateUpdateHandler = { [weak self] state in
                guard case .failed = state, let center = self else { return }
                Task { @MainActor in center.restart() }
            }
            listener.start(queue: queue)
            self.listener = listener
        } catch {
            // Devices without application services (the Simulator) simply
            // don't offer the handoff.
        }
    }

    private func restart() {
        listener?.cancel()
        listener = nil
        Task {
            try? await Task.sleep(for: .seconds(2))
            start()
        }
    }

    private func accept(_ connection: NWConnection) {
        guard pending == nil else {
            // One request at a time; the TV shows the decline.
            Task { await Self.send(.declined, over: connection, startingOn: queue) }
            return
        }
        let queue = self.queue
        Task {
            do {
                try await connection.startAndWaitUntilReady(queue: queue)
                let request = try AccountHandoff.decodeRequest(try await connection.receiveFrame())
                present(PendingRequest(request: request, connection: connection))
            } catch {
                connection.cancel()
            }
        }
    }

    private func present(_ request: PendingRequest) {
        guard pending == nil else {
            Task { await Self.send(.declined, over: request.connection, startingOn: nil) }
            return
        }
        pending = request
        if UIApplication.shared.applicationState != .active {
            Task { await notify(request.request) }
        }
    }

    // MARK: - Answering

    func approve(account: CloudAccount) async {
        await answer(AccountHandoff.Response(status: .approved, account: AccountHandoff.Account(account)))
    }

    func approve(login: AccountHandoff.Login) async {
        await answer(AccountHandoff.Response(status: .approved, login: login))
    }

    func decline() async {
        await answer(.declined)
    }

    private func answer(_ response: AccountHandoff.Response) async {
        guard let pending else { return }
        self.pending = nil
        await Self.send(response, over: pending.connection, startingOn: nil)
    }

    /// Sends the response, then lets the TV close the connection.
    private nonisolated static func send(
        _ response: AccountHandoff.Response,
        over connection: NWConnection,
        startingOn queue: DispatchQueue?
    ) async {
        do {
            if let queue {
                try await connection.startAndWaitUntilReady(queue: queue)
            }
            try await connection.sendFrame(AccountHandoff.frame(response))
        } catch {}
        connection.cancel()
    }

    // MARK: - Background

    private func notify(_ request: AccountHandoff.Request) async {
        let center = UNUserNotificationCenter.current()
        guard (try? await center.requestAuthorization(options: [.alert, .sound])) == true else { return }
        let content = UNMutableNotificationContent()
        content.title = String(localized: "Link \(request.kind.displayName) on \(request.deviceName)?")
        content.body = String(localized: "Open Edendale to approve or decline.")
        content.interruptionLevel = .timeSensitive
        try? await center.add(UNNotificationRequest(
            identifier: "account-handoff-\(request.kind.rawValue)",
            content: content,
            trigger: nil
        ))
    }
}
#endif
