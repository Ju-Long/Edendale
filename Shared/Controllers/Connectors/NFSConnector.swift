//
//  NFSConnector.swift
//  Edendale
//
//  NFS exports (NFSv3, AUTH_SYS, no login). Browsing goes through libvlc's
//  NFS access module (VLCNetworkBrowser), which lists a server's exports at
//  `nfs://host/`; playback reads through libnfs (NFSConnection) as a byte
//  source. iOS can't bind privileged ports, so the export needs the
//  `insecure` option, which errors say.
//

import Foundation

nonisolated struct NFSConnector: MediaConnector, Hashable {
    let kind: MediaSourceKind = .nfs
    let host: String
    /// Where browsing starts, e.g. `/volume1/video`; `nil` lists the exports.
    let exportPath: String?

    init?(host: String, exportPath: String? = nil) {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              SourceURL.server(scheme: "nfs", host: trimmed, pathSegments: []) != nil
        else { return nil }
        self.host = trimmed
        let path = exportPath?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        self.exportPath = path.isEmpty ? nil : path
    }

    init?(sourceURL: URL) {
        guard MediaSourceKind(url: sourceURL) == .nfs, let host = sourceURL.host() else { return nil }
        self.host = host
        self.exportPath = nil
    }

    var root: URL {
        let segments = (exportPath ?? "").split(separator: "/").map(String.init)
        return SourceURL.server(scheme: "nfs", host: host, pathSegments: segments, isDirectory: true)!
    }

    func list(directory: URL) async throws -> [ConnectorEntry] {
        do {
            return try await VLCNetworkBrowser.shared.list(directory: directory, credential: nil)
        } catch ConnectorError.unreachable {
            throw ConnectorError.nfsMountFailed(
                host: host,
                reason: String(localized: "the server didn't answer or refused the connection")
            )
        }
    }

    /// Reads a file with libnfs; the connection opens on the first read.
    static func byteSource(for itemURL: URL) -> RemoteFileByteSource? {
        guard MediaSourceKind(url: itemURL) == .nfs, let host = itemURL.host() else { return nil }
        let path = "/" + SourceURL.pathSegments(of: itemURL).joined(separator: "/")
        return RemoteFileByteSource(kind: .nfs, host: host) {
            try NFSConnection(host: host, filePath: path, timeoutMilliseconds: 15_000)
        }
    }
}
