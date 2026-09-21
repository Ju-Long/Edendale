import Foundation

struct ContentPlayerPreferences: Codable {
    var speed: Float?
    var audioTrackLanguage: String?
    var audioTrackName: String?
    var subtitleEnabled: Bool?
    var subtitleTrackLanguage: String?
    var subtitleTrackName: String?
    var videoTrackWidth: Int?
    var videoTrackHeight: Int?
}

@MainActor
final class PlayerPreferencesStore {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = AppIdentifiers.defaults) {
        self.defaults = defaults
    }

    func preferences(for item: PlaybackItem) -> ContentPlayerPreferences? {
        guard let key = Self.contentKey(for: item),
              let data = defaults.data(forKey: key)
        else { return nil }
        return try? JSONDecoder().decode(ContentPlayerPreferences.self, from: data)
    }

    func save(_ preferences: ContentPlayerPreferences, for item: PlaybackItem) {
        guard let key = Self.contentKey(for: item),
              let data = try? JSONEncoder().encode(preferences)
        else { return }
        defaults.set(data, forKey: key)
    }

    private static func contentKey(for item: PlaybackItem) -> String? {
        if let movie = item.movie, let tmdbId = movie.tmdbId {
            return "player.content.movie.\(tmdbId)"
        }
        if let episode = item.episode, let showTmdbId = episode.show?.tmdbId {
            return "player.content.show.\(showTmdbId)"
        }
        return nil
    }

    func snapshot(
        chrome: PlayerChromeModel,
        player: PlaybackEngine
    ) -> ContentPlayerPreferences {
        var prefs = ContentPlayerPreferences()
        prefs.speed = chrome.baseRate

        if let audio = player.selectedAudioTrack {
            prefs.audioTrackLanguage = audio.language
            prefs.audioTrackName = audio.name
        }

        if let subtitle = player.selectedSubtitleTrack {
            if !subtitle.id.hasPrefix("ext-") {
                prefs.subtitleEnabled = true
                prefs.subtitleTrackLanguage = subtitle.language
                prefs.subtitleTrackName = subtitle.name
            }
        } else if !player.subtitleTracks.isEmpty {
            prefs.subtitleEnabled = false
        }

        if let video = player.videoTracks.first(where: \.isSelected) {
            prefs.videoTrackWidth = video.width
            prefs.videoTrackHeight = video.height
        }

        return prefs
    }

    func apply(
        _ prefs: ContentPlayerPreferences,
        to chrome: PlayerChromeModel,
        player: PlaybackEngine
    ) {
        if let speed = prefs.speed {
            chrome.restoreRate(speed)
        }

        if let match = bestAudioMatch(prefs, in: player.audioTracks) {
            player.selectedAudioTrack = match
        }

        if let enabled = prefs.subtitleEnabled {
            if !enabled {
                player.selectedSubtitleTrack = nil
            } else if let match = bestSubtitleMatch(prefs, in: player.subtitleTracks) {
                player.selectedSubtitleTrack = match
            }
        }

        if player.videoTracks.count > 1,
           let w = prefs.videoTrackWidth, let h = prefs.videoTrackHeight {
            if let match = player.videoTracks.first(where: {
                $0.width == w && $0.height == h
            }) {
                player.selectVideoTrack(match)
            }
        }
    }

    private func bestAudioMatch(
        _ prefs: ContentPlayerPreferences,
        in tracks: [PlaybackTrack]
    ) -> PlaybackTrack? {
        if let lang = prefs.audioTrackLanguage, !lang.isEmpty,
           let match = tracks.first(where: { $0.language == lang }) {
            return match
        }
        if let name = prefs.audioTrackName,
           let match = tracks.first(where: { $0.name == name }) {
            return match
        }
        return nil
    }

    private func bestSubtitleMatch(
        _ prefs: ContentPlayerPreferences,
        in tracks: [PlaybackTrack]
    ) -> PlaybackTrack? {
        let embedded = tracks.filter { !$0.id.hasPrefix("ext-") }
        if let lang = prefs.subtitleTrackLanguage, !lang.isEmpty,
           let match = embedded.first(where: { $0.language == lang }) {
            return match
        }
        if let name = prefs.subtitleTrackName,
           let match = embedded.first(where: { $0.name == name }) {
            return match
        }
        return nil
    }
}
