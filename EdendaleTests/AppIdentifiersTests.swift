//
//  AppIdentifiersTests.swift
//  EdendaleTests
//

import Foundation
import Testing
@testable import Edendale

@MainActor
struct AppIdentifiersTests {
    /// The unsigned macOS test host is unsandboxed, so the App Group suite would
    /// be the user's machine-wide preferences. Only the host suite is written here.
    @Test func hostedTestsUseProcessLocalDefaults() throws {
        let key = "AppIdentifiersTests.\(UUID().uuidString)"
        let hostSuite = try #require(UserDefaults(suiteName: AppIdentifiers.unitTestHostSuiteName))
        hostSuite.set(true, forKey: key)
        defer { hostSuite.removeObject(forKey: key) }

        #expect(AppIdentifiers.defaults.bool(forKey: key))
    }
}
