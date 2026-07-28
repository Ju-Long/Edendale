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
    /// Username the network source was linked with (password is in the
    /// Keychain, keyed by host — see `NetworkCredentialStore`).
    var username: String?

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
