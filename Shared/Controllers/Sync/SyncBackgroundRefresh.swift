//
//  SyncBackgroundRefresh.swift
//  Edendale
//
//  Background sync on iOS and iPadOS (FEATURES-27.1.md §8): a
//  BackgroundTasks app-refresh task, scheduled whenever the app leaves the
//  foreground, that runs one sync when the system gives it time, plus the
//  short background task the coordinator's flush gets so a write started
//  on the way to the background can finish. Best effort by design: the
//  user's Background App Refresh setting and Low Power Mode can stop it.
//

#if os(iOS)
import BackgroundTasks
import Foundation
import UIKit

@MainActor
enum SyncBackgroundRefresh {
    /// Listed in Info.plist's `BGTaskSchedulerPermittedIdentifiers`.
    static let identifier = "com.BaBaSaMa.Edendale.sync"
    /// Not before this many minutes after leaving the foreground.
    static let earliestInterval: TimeInterval = 15 * 60

    static weak var coordinator: SyncCoordinator?

    /// Registers the handler; must run before the app finishes launching.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            Task { @MainActor in
                schedule()
                guard let coordinator, coordinator.isOn else {
                    refresh.setTaskCompleted(success: true)
                    return
                }
                let work = Task { await coordinator.runSync(reason: "background") }
                refresh.expirationHandler = { work.cancel() }
                let succeeded = await work.value
                refresh.setTaskCompleted(success: succeeded)
            }
        }
    }

    /// Asks for a refresh once the app is in the background.
    static func schedule() {
        guard coordinator?.isOn == true else { return }
        let request = BGAppRefreshTaskRequest(identifier: identifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: earliestInterval)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            SyncLog.info("background refresh not scheduled: \(SyncLog.describe(error))")
        }
    }

    /// Keeps the process alive long enough for a flush to finish.
    static func keepAlive(_ work: @escaping @MainActor () async -> Void) {
        var identifier = UIBackgroundTaskIdentifier.invalid
        identifier = UIApplication.shared.beginBackgroundTask(withName: "Edendale.Sync.flush") {
            UIApplication.shared.endBackgroundTask(identifier)
            identifier = .invalid
        }
        Task { @MainActor in
            await work()
            if identifier != .invalid {
                UIApplication.shared.endBackgroundTask(identifier)
            }
        }
    }
}
#endif
