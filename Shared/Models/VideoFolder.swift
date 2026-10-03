//
//  VideoFolder.swift
//  Edendale
//

import Foundation
import SwiftData

@Model
final class VideoFolder {
    var id: UUID
    var name: String
    /// Local sources: the folder's file-system path. Network sources: the
    /// credential-free source URL (e.g. `smb://host/share/folder`).
    var folderPath: String
    var bookmarkData: Data?
    var dateAdded: Date
    /// `MediaSourceKind` raw value; inline default migrates pre-existing
    /// rows as local folders.
    var sourceKindRaw: String = MediaSourceKind.local.rawValue
    /// Username or account email the network source was linked with (the
    /// password or token is in the Keychain — see `NetworkCredentialStore`
    /// and `CloudAccountVault`).
    var username: String?
    /// A readable location for the source's row, e.g.
    /// "Google Drive › My Drive › Movies". Provider URLs hold IDs, not names.
    /// Optional attributes like this one migrate existing rows as `nil`.
    var displayPath: String?
    /// The account key of a cloud or S3 source (also its URL host).
    var accountKey: String?
    /// When the source was last listed. The automatic rescan on each library
    /// visit skips remote sources scanned within the last 15 minutes.
    var lastScannedAt: Date?
    /// A provider change cursor for incremental rescans (Dropbox
    /// `list_folder/continue`, OneDrive `delta`, Drive `changes.list`).
    /// Reserved: rescans still list the whole source.
    var changeCursor: String?

    @Relationship(deleteRule: .cascade, inverse: \Movie.folder)
    var movies: [Movie]

    @Relationship(deleteRule: .cascade, inverse: \TVShow.folder)
    var tvShows: [TVShow]

    init(
        name: String,
        folderPath: String,
        bookmarkData: Data? = nil,
        sourceKind: MediaSourceKind = .local,
        username: String? = nil
    ) {
        self.id = UUID()
        self.name = name
        self.folderPath = folderPath
        self.bookmarkData = bookmarkData
        self.dateAdded = Date()
        self.sourceKindRaw = sourceKind.rawValue
        self.username = username
        self.movies = []
        self.tvShows = []
    }

    var sourceKind: MediaSourceKind {
        MediaSourceKind(rawValue: sourceKindRaw) ?? .local
    }

    var isRemote: Bool { sourceKind != .local }

    /// The network source's credential-free URL; `nil` for local folders.
    var remoteURL: URL? {
        isRemote ? URL(string: folderPath) : nil
    }

    /// What a source row shows as its location: the readable path when the
    /// link flow recorded one, otherwise the stored path or URL.
    var locationDescription: String {
        displayPath ?? folderPath
    }

    func resolvedURL() -> URL? {
        if isRemote { return remoteURL }
        if let bookmarkData {
            var isStale = false
            if let url = try? URL(
                resolvingBookmarkData: bookmarkData,
                options: .securityScoped,
                relativeTo: nil,
                bookmarkDataIsStale: &isStale
            ) { return url }
        }
        return URL(fileURLWithPath: folderPath)
    }

    var totalItemCount: Int { movies.count + tvShows.reduce(0) { $0 + $1.episodes.count } }
}
