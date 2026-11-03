//
//  SyncStamper.swift
//  Edendale
//
//  This device's identity for sync and the stamp every local write gets
//  (SYNC-PROTOCOL.md §3, §7.2). The device ID is a version-4 UUID made on
//  first use and kept in the this-device-only Keychain, so a backup
//  restored onto another device makes a new one rather than two devices
//  sharing one. The high-water mark, the highest `updateTime` this device
//  has applied or written, lives in the app group defaults (device-local),
//  so a clock that runs behind still stamps after what it just applied.
//

import Foundation

@MainActor
@Observable
final class SyncStamper {

    static let shared: SyncStamper = {
        if Persistence.isRunningUnitTests {
            return SyncStamper(defaults: AppIdentifiers.defaults, secrets: InMemorySecretStore())
        }
        return SyncStamper(defaults: AppIdentifiers.defaults, secrets: KeychainStore.local)
    }()

    static let deviceIDAccount = "sync-device-id"
    static let highWaterKey = "sync.highWater"

    @ObservationIgnored private let defaults: UserDefaults
    @ObservationIgnored private let secrets: any SecretStore
    @ObservationIgnored private var cachedDeviceID: String?

    /// The highest stamp applied or written (§7.2).
    private(set) var highWater: Int64

    init(defaults: UserDefaults, secrets: any SecretStore) {
        self.defaults = defaults
        self.secrets = secrets
        highWater = Int64(defaults.integer(forKey: Self.highWaterKey))
    }

    /// This device's ID, made the first time it is needed.
    var deviceID: String {
        if let cachedDeviceID { return cachedDeviceID }
        if let data = secrets.data(forAccount: Self.deviceIDAccount),
           let stored = String(data: data, encoding: .utf8), SyncFileFormat.isDeviceID(stored) {
            cachedDeviceID = stored
            return stored
        }
        let made = SyncFileFormat.makeDeviceID()
        try? secrets.set(Data(made.utf8), forAccount: Self.deviceIDAccount)
        cachedDeviceID = made
        return made
    }

    /// The stamp for a write happening now: `max(now, highWater + 1)`.
    func stamp(now: Date = Date()) -> SyncStamp {
        let stamp = SyncLedger.nextStamp(now: SyncLedger.milliseconds(now), highWater: highWater)
        setHighWater(stamp)
        return SyncStamp(updateTime: stamp, device: deviceID)
    }

    /// Records a stamp applied from another device (a file, or a CloudKit
    /// record) so later local writes order after it. A stamp more than a
    /// day ahead of this device's clock is ignored (§7.1): a CloudKit row
    /// from a device with a bad clock must not drag this device's stamps
    /// past the point every other device refuses them.
    func observe(_ updateTime: Int64, now: Date = Date()) {
        guard updateTime > highWater,
              updateTime <= SyncLedger.milliseconds(now) + SyncLedger.farFutureAllowance
        else { return }
        setHighWater(updateTime)
    }

    /// `observe` for the highest of `stamps` within a day of this device's
    /// clock, so one far-future stamp doesn't hide the others.
    func observe<S: Sequence<Int64>>(stamps: S, now: Date = Date()) {
        let limit = SyncLedger.milliseconds(now) + SyncLedger.farFutureAllowance
        if let highest = stamps.filter({ $0 <= limit }).max() {
            observe(highest, now: now)
        }
    }

    private func setHighWater(_ value: Int64) {
        highWater = value
        defaults.set(value, forKey: Self.highWaterKey)
    }

    /// Forgets the device ID; a new one is made at the next stamp. Only
    /// for tests and for a user who removes this device from a space.
    func resetDeviceID() {
        secrets.remove(account: Self.deviceIDAccount)
        cachedDeviceID = nil
    }

    // MARK: - This device, as the file names it

    /// `ios`, `ipados`, `macos`, `tvos`, or `visionos` (§3).
    nonisolated static var platformName: String {
        #if os(tvOS)
        "tvos"
        #elseif os(visionOS)
        "visionos"
        #elseif os(macOS)
        "macos"
        #elseif os(iOS)
        if UIDevice.current.userInterfaceIdiom == .pad { "ipados" } else { "ios" }
        #else
        "ios"
        #endif
    }

    /// The device name shown to other devices, 1–64 characters.
    nonisolated static var deviceName: String {
        var name: String
        #if os(macOS)
        name = Host.current().localizedName ?? ProcessInfo.processInfo.hostName
        #else
        name = UIDevice.current.name
        #endif
        name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if name.isEmpty { name = platformDisplayName }
        return String(name.prefix(SyncFileFormat.maxDeviceNameCharacters))
    }

    nonisolated static var platformDisplayName: String {
        #if os(tvOS)
        "Apple TV"
        #elseif os(visionOS)
        "Apple Vision Pro"
        #elseif os(macOS)
        "Mac"
        #else
        UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
        #endif
    }

    nonisolated static var appVersion: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "0"
    }
}

#if canImport(UIKit)
import UIKit
#endif
