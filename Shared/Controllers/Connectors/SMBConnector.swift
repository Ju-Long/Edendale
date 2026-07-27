//
//  SMBConnector.swift
//  Edendale
//
//  MediaConnector for SMB shares, backed by the bundled libvlc's smb2/dsm
//  access modules (via VLCNetworkBrowser). At the root URL (smb://host/)
//  the listing returns the server's shares; below that, folders and files.
//

import Foundation

struct SMBConnector: MediaConnector, Hashable {

    let kind: MediaSourceKind = .smb
    let host: String
    let port: Int?
    let credential: NetworkCredential?

    /// Builds a connector for a host entered by the user. Fails when the
    /// host doesn't form a valid smb:// URL.
    init?(host: String, port: Int? = nil, credential: NetworkCredential?) {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }

        var components = URLComponents()
        components.scheme = MediaSourceKind.smb.scheme
        components.host = trimmed
        components.port = port
        components.path = "/"
        guard components.url != nil else { return nil }

        self.host = trimmed
        self.port = port
        self.credential = credential
    }

    /// Rebuilds the connector for a stored source URL, pulling the
    /// credential back out of the Keychain.
    init?(sourceURL: URL) {
        guard let host = sourceURL.host() else { return nil }
        self.host = host
        self.port = sourceURL.port
        self.credential = NetworkCredentialStore.credential(host: host)
    }

    var root: URL {
        var components = URLComponents()
        components.scheme = MediaSourceKind.smb.scheme
        components.host = host
        components.port = port
        components.path = "/"
        // Validated by the initializers.
        return components.url!
    }

    func validate() async throws {
        _ = try await list(directory: root)
    }

    func list(directory: URL) async throws -> [ConnectorEntry] {
        try await VLCNetworkBrowser.shared.list(directory: directory, credential: credential)
    }
}
