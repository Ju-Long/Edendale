//
//  ConnectorTests.swift
//  EdendaleTests
//
//  Connector-layer tests: URL credential handling stays pure, and the
//  libvlc-backed directory listing is smoke-tested against a local temp
//  directory — libvlc's directory parsing is scheme-agnostic, so this
//  exercises the exact machinery SMB browsing uses, minus the network leg.
//

import Testing
import Foundation
@testable import Edendale

struct ConnectorURLTests {

    @Test func authenticatedURLInjectsPercentEncodedUserinfo() throws {
        let url = try #require(URL(string: "smb://nas.local/Media/Films"))
        let credential = NetworkCredential(username: "long ju", password: "p@ss:w rd")

        let authed = try #require(VLCNetworkBrowser.authenticatedURL(url, credential: credential))
        let components = try #require(URLComponents(url: authed, resolvingAgainstBaseURL: false))

        #expect(components.user == "long ju")
        #expect(components.password == "p@ss:w rd")
        #expect(components.host == "nas.local")
        #expect(components.path == "/Media/Films")
        // The raw string must be a valid MRL: no bare spaces or colons leak in.
        #expect(!authed.absoluteString.contains(" "))
    }

    @Test func authenticatedURLPassesGuestThroughUntouched() throws {
        let url = try #require(URL(string: "smb://nas.local/Media"))
        #expect(VLCNetworkBrowser.authenticatedURL(url, credential: nil) == url)
        let guest = NetworkCredential(username: "", password: "")
        #expect(VLCNetworkBrowser.authenticatedURL(url, credential: guest) == url)
    }

    @Test func strippingUserinfoRemovesCredentials() throws {
        let stripped = try #require(
            VLCNetworkBrowser.strippingUserinfo(from: "smb://user:secret@nas.local/Media/file.mkv")
        )
        #expect(stripped.absoluteString == "smb://nas.local/Media/file.mkv")
    }

    @Test func smbConnectorBuildsRootFromHost() throws {
        let connector = try #require(SMBConnector(host: " nas.local ", credential: nil))
        #expect(connector.root.absoluteString == "smb://nas.local/")
        #expect(SMBConnector(host: "   ", credential: nil) == nil)
    }

    @Test func smbConnectorRebuildsFromStoredSourceURL() throws {
        let sourceURL = try #require(URL(string: "smb://nas.local:1445/Media/Films"))
        let connector = try #require(SMBConnector(sourceURL: sourceURL))
        #expect(connector.host == "nas.local")
        #expect(connector.port == 1445)
    }
}

struct VLCNetworkBrowserListingTests {

    /// Lists a real directory through libvlc (instance, parse request,
    /// subitems) and checks folders and video files come back typed and
    /// sorted folders-first.
    @Test func listsLocalDirectoryEntries() async throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("edendale-connector-test-\(UUID().uuidString)", isDirectory: true)
        let season = root.appendingPathComponent("Season 1", isDirectory: true)
        try FileManager.default.createDirectory(at: season, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }

        for name in ["The.Matrix.1999.mkv", "Show.S01E01.mp4"] {
            try Data("stub".utf8).write(to: root.appendingPathComponent(name))
        }

        let entries = try await VLCNetworkBrowser.shared.list(directory: root, credential: nil)

        let directories = entries.filter(\.isDirectory).map(\.name)
        let files = entries.filter { !$0.isDirectory }.map(\.name)
        #expect(directories == ["Season 1"])
        #expect(files.sorted() == ["Show.S01E01.mp4", "The.Matrix.1999.mkv"])
        // Folders sort ahead of files.
        #expect(entries.first?.isDirectory == true)
    }
}
