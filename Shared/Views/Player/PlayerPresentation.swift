//
//  PlayerPresentation.swift
//  Edendale
//
//  Playback request types. The presented item owns the security-scoped
//  resource for the session, so sandboxed files (external volumes, iCloud
//  Drive) can actually be opened by VLC. Presentation itself is scene-based:
//  see `PlayerSession` and the per-platform hosts in `EdendaleApp`/`ContentView`.
//

import Foundation

/// Holds one started security-scoped access and stops it exactly once when
/// the last owner releases it. Shared (reference-counted) between a playback
/// item and any sibling items spawned from it — e.g. playing another file
/// from the same folder — so switching files inside one folder scope never
/// double-stops or prematurely stops access.
final class SecurityScopedAccess {
    /// URL whose scope was started (the file itself, or its parent folder).
    private let url: URL

    /// `startAccessingSecurityScopedResource()` has already been called on
    /// `startedURL` (see `LibraryController.resolveScope`).
    init(startedURL: URL) {
        self.url = startedURL
    }

    deinit {
        url.stopAccessingSecurityScopedResource()
    }
}

/// A playable URL plus the security-scoped access that makes it readable.
final class PlaybackScope {
    /// URL handed to the player.
    let url: URL
    /// Shared access token; `nil` when no scoped access was needed.
    private let access: SecurityScopedAccess?

    init(playURL: URL, accessedURL: URL?) {
        self.url = playURL
        self.access = accessedURL.map(SecurityScopedAccess.init(startedURL:))
    }

    private init(playURL: URL, access: SecurityScopedAccess?) {
        self.url = playURL
        self.access = access
    }

    /// A scope for another file covered by the same access grant — a sibling
    /// in the same folder. The underlying access stays alive until both
    /// scopes are gone.
    func sibling(playURL: URL) -> PlaybackScope {
        PlaybackScope(playURL: playURL, access: access)
    }
}

/// A request to present the player. Carries either a resolved, access-started
/// `PlaybackScope` or a human-readable failure reason, so a failed resolve
/// shows an error inside the player instead of a silent black screen.
/// Also carries the library metadata the player chrome displays (title,
/// episode context); all metadata is optional so plain files still play.
struct PlaybackItem: Identifiable {
    let id = UUID()
    let scope: PlaybackScope?
    let errorMessage: String?

    /// Metadata title (movie or show name); `nil` when the library has no
    /// TMDB match — the chrome falls back to the file name.
    let title: String?
    /// Secondary line under the title, e.g. "S01E03 · Winter Is Coming".
    let subtitle: String?
    /// Library episode backing this item, when playing a show — drives the
    /// episode-list sidebar and next-episode switching.
    let episode: Episode?
    /// Library movie backing this item, when playing a movie.
    let movie: Movie?

    init(scope: PlaybackScope, movie: Movie? = nil, episode: Episode? = nil) {
        self.scope = scope
        self.errorMessage = nil
        self.movie = movie
        self.episode = episode

        if let movie, movie.tmdbId != nil {
            self.title = movie.displayTitle
            self.subtitle = movie.releaseYear.map(String.init)
        } else if let episode, let show = episode.show, show.tmdbId != nil || episode.tmdbId != nil {
            self.title = show.displayName
            self.subtitle = "\(episode.episodeCode) · \(episode.displayTitle)"
        } else {
            self.title = nil
            self.subtitle = nil
        }
    }

    init(failed message: String) {
        self.scope = nil
        self.errorMessage = message
        self.title = nil
        self.subtitle = nil
        self.episode = nil
        self.movie = nil
    }

    var url: URL? { scope?.url }

    /// Name shown when no metadata is present.
    var fileName: String? { scope?.url.lastPathComponent }

    /// What the player's top-center title displays: metadata name when
    /// present, otherwise the file name.
    var displayTitle: String { title ?? fileName ?? String(localized: "Now Playing") }

    /// Identifiers for an online subtitle lookup: the TMDB id plus, for a show,
    /// the season/episode pair. `nil` when the file has no TMDB match, which is
    /// what the panel uses to explain that online search is unavailable.
    var subtitleLookup: (id: String, season: Int?, episode: Int?)? {
        if let tmdbId = movie?.tmdbId {
            return (String(tmdbId), nil, nil)
        }
        if let episode, let showTmdbId = episode.show?.tmdbId {
            return (
                String(showTmdbId),
                episode.seasonNumber,
                episode.episodeNumber
            )
        }
        return nil
    }
}
