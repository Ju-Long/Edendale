//
//  VLCNetworkBrowser.swift
//  Edendale
//
//  Directory listing over network protocols (smb://, and later nfs://,
//  sftp://, ftp:// — all access modules ship in the bundled libvlc), built on
//  libvlc's media parsing: parsing a directory MRL with the network flag
//  populates its subitems. SwiftVLC doesn't wrap libvlc_media_subitems, so
//  this file talks to the package's CLibVLC target directly — all raw C
//  interop for browsing stays contained here.
//
//  The browser runs on its own private libvlc instance (SwiftVLC's shared
//  instance doesn't expose its pointer). No dialog callbacks are registered:
//  credentials are injected into the ephemeral browse URL instead, so a bad
//  login fails the parse instead of hanging on a prompt.
//

import Foundation
import CLibVLC

/// Lists directories on network shares through libvlc. One shared actor —
/// the libvlc instance is created lazily on first use and kept for the app's
/// lifetime.
actor VLCNetworkBrowser {

    static let shared = VLCNetworkBrowser()

    private var instance: OpaquePointer?

    /// Lists the entries of a network directory. `directory` must be a
    /// credential-free canonical URL; the credential (if any) is woven into
    /// the URL handed to libvlc and stripped from every returned entry.
    func list(
        directory: URL,
        credential: NetworkCredential?,
        timeout: Duration = .seconds(20)
    ) async throws -> [ConnectorEntry] {
        let instance = try makeInstanceIfNeeded()

        guard let mrl = Self.authenticatedURL(directory, credential: credential),
              let media = libvlc_media_new_location(mrl.absoluteString) else {
            throw ConnectorError.invalidAddress
        }
        defer { libvlc_media_release(media) }

        let flags = libvlc_media_parse_flag_t(
            rawValue: libvlc_media_parse_local.rawValue | libvlc_media_parse_network.rawValue
        )
        let timeoutMs = Int32(clamping: timeout.components.seconds * 1000
            + timeout.components.attoseconds / 1_000_000_000_000_000)
        guard libvlc_media_parse_request(instance, media, flags, timeoutMs) == 0 else {
            throw ConnectorError.listingFailed(path: directory.path)
        }

        // libvlc reports completion via an event; polling the parse status
        // instead keeps this free of C callback plumbing, and 50 ms lag is
        // invisible behind a network round-trip.
        var status = libvlc_media_get_parsed_status(media)
        while status == libvlc_media_parsed_status_none
            || status == libvlc_media_parsed_status_pending {
            if Task.isCancelled {
                libvlc_media_parse_stop(instance, media)
                throw CancellationError()
            }
            try? await Task.sleep(for: .milliseconds(50))
            status = libvlc_media_get_parsed_status(media)
        }

        guard status == libvlc_media_parsed_status_done else {
            throw ConnectorError.unreachable(host: directory.host() ?? directory.absoluteString)
        }

        return Self.readSubitems(of: media)
    }

    // MARK: - libvlc plumbing

    private func makeInstanceIfNeeded() throws -> OpaquePointer {
        if let instance { return instance }
        guard let created = libvlc_new(0, nil) else {
            throw ConnectorError.listingFailed(path: "libvlc")
        }
        libvlc_set_user_agent(created, "Edendale", "Edendale/0.1")
        instance = created
        return created
    }

    private static func readSubitems(of media: OpaquePointer) -> [ConnectorEntry] {
        guard let list = libvlc_media_subitems(media) else { return [] }
        defer { libvlc_media_list_release(list) }

        libvlc_media_list_lock(list)
        defer { libvlc_media_list_unlock(list) }

        let count = libvlc_media_list_count(list)
        var entries: [ConnectorEntry] = []
        entries.reserveCapacity(Int(count))

        for index in 0..<count {
            guard let item = libvlc_media_list_item_at_index(list, index) else { continue }
            defer { libvlc_media_release(item) }

            guard let mrlCString = libvlc_media_get_mrl(item) else { continue }
            let mrl = String(cString: mrlCString)
            libvlc_free(mrlCString)

            // Canonical, credential-free entry URL.
            guard let url = Self.strippingUserinfo(from: mrl) else { continue }

            var name = url.lastPathComponent.removingPercentEncoding ?? url.lastPathComponent
            if let titleCString = libvlc_media_get_meta(item, libvlc_meta_Title) {
                let title = String(cString: titleCString)
                libvlc_free(titleCString)
                if !title.isEmpty { name = title }
            }

            entries.append(ConnectorEntry(
                name: name,
                url: url,
                isDirectory: libvlc_media_get_type(item) == libvlc_media_type_directory
            ))
        }

        return entries.sorted {
            if $0.isDirectory != $1.isDirectory { return $0.isDirectory }
            return $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }

    // MARK: - URL credential handling

    /// The URL with the credential's userinfo injected — used only for the
    /// in-memory MRL handed to libvlc, never persisted or displayed.
    static func authenticatedURL(_ url: URL, credential: NetworkCredential?) -> URL? {
        guard let credential, !credential.isGuest else { return url }
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            return nil
        }
        components.user = credential.username
        components.password = credential.password
        return components.url
    }

    /// The credential-free canonical form of an MRL libvlc handed back
    /// (subitem MRLs inherit the userinfo of the browse URL).
    static func strippingUserinfo(from mrl: String) -> URL? {
        guard var components = URLComponents(string: mrl) else { return nil }
        components.user = nil
        components.password = nil
        return components.url
    }
}
