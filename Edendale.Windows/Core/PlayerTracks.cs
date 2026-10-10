using System.Globalization;
using System.Text.Json.Serialization;

namespace Edendale.Windows.Core;

/// <summary>
/// One elementary stream as the player reports it, joined from LibVLC's
/// track descriptions (id and name) and the media's track list (language,
/// size, channels). WinUI- and LibVLC-free so the rules below are testable.
/// </summary>
public sealed record PlayerTrack
{
    /// <summary>LibVLC's elementary-stream id, used to select the track.</summary>
    public required int Id { get; init; }

    /// <summary>The track's own title, when the file names it.</summary>
    public string? Name { get; init; }

    /// <summary>ISO 639 code (two or three letters) when the file declares one.</summary>
    public string? Language { get; init; }

    public int Width { get; init; }
    public int Height { get; init; }
    public int Channels { get; init; }

    /// <summary>A subtitle attached after opening (a download or a side file), not one inside the file.</summary>
    public bool IsExternal { get; init; }
}

/// <summary>Row labels for the Player Adjustments track pickers (DIFF.md §3.9).</summary>
public static class TrackLabels
{
    /// <summary>"Mono", "Stereo", "5.1", "7.1", or "Nch"; empty when unknown.</summary>
    public static string ChannelLabel(int channels) => channels switch
    {
        <= 0 => "",
        1 => AppText.Get("Track_Mono"),
        2 => AppText.Get("Track_Stereo"),
        6 => "5.1",
        8 => "7.1",
        _ => AppText.Format("Track_Channels", channels),
    };

    /// <summary>
    /// The language's name in the reader's language, from a two- or
    /// three-letter code; null for an unknown or undetermined code.
    /// </summary>
    public static string? LanguageName(string? code)
    {
        if (string.IsNullOrWhiteSpace(code)) return null;
        var trimmed = code.Trim();
        if (trimmed.Equals("und", StringComparison.OrdinalIgnoreCase)
            || trimmed.Equals("mul", StringComparison.OrdinalIgnoreCase)
            || trimmed.Equals("zxx", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        try
        {
            if (trimmed.Length == 2)
            {
                return CultureInfo.GetCultureInfo(trimmed).DisplayName;
            }
            if (trimmed.Length == 3)
            {
                var culture = CultureInfo.GetCultures(CultureTypes.NeutralCultures).FirstOrDefault(candidate =>
                    candidate.ThreeLetterISOLanguageName.Equals(trimmed, StringComparison.OrdinalIgnoreCase)
                    || BibliographicCode(candidate.TwoLetterISOLanguageName) is { } bibliographic
                        && bibliographic.Equals(trimmed, StringComparison.OrdinalIgnoreCase));
                if (culture is not null && !string.IsNullOrEmpty(culture.Name)) return culture.DisplayName;
            }
        }
        catch (CultureNotFoundException)
        {
        }
        return trimmed;
    }

    /// <summary>
    /// ISO 639-2/B codes that differ from the terminology codes .NET reports
    /// (Matroska files usually carry the bibliographic form, "fre" not "fra").
    /// </summary>
    private static string? BibliographicCode(string twoLetter) => twoLetter switch
    {
        "fr" => "fre",
        "de" => "ger",
        "nl" => "dut",
        "zh" => "chi",
        "cs" => "cze",
        "el" => "gre",
        "fa" => "per",
        "is" => "ice",
        "hy" => "arm",
        "ka" => "geo",
        "mk" => "mac",
        "ms" => "may",
        "ro" => "rum",
        "sk" => "slo",
        "sq" => "alb",
        "eu" => "baq",
        "cy" => "wel",
        "my" => "bur",
        "bo" => "tib",
        "mi" => "mao",
        _ => null,
    };

    /// <summary>
    /// "Name (Language)": the language is appended only when the name doesn't
    /// already contain it. A nameless track falls back to "Track N".
    /// </summary>
    public static string BaseLabel(PlayerTrack track, int index)
    {
        var name = string.IsNullOrWhiteSpace(track.Name)
            ? AppText.Format("Track_Numbered", index + 1)
            : track.Name.Trim();
        var language = LanguageName(track.Language);
        if (language is null) return name;
        if (name.Contains(language, StringComparison.CurrentCultureIgnoreCase)) return name;
        if (track.Language is { } code && ContainsWord(name, code)) return name;
        return $"{name} ({language})";
    }

    /// <summary>"name (language) — W×H"; the size is omitted when unknown.</summary>
    public static string VideoLabel(PlayerTrack track, int index)
    {
        var label = BaseLabel(track, index);
        return track.Width > 0 && track.Height > 0
            ? string.Create(CultureInfo.CurrentCulture, $"{label} — {track.Width}×{track.Height}")
            : label;
    }

    /// <summary>"name (language) — Mono | Stereo | 5.1 | 7.1 | Nch".</summary>
    public static string AudioLabel(PlayerTrack track, int index)
    {
        var label = BaseLabel(track, index);
        var channels = ChannelLabel(track.Channels);
        return channels.Length > 0 ? $"{label} — {channels}" : label;
    }

    private static readonly char[] WordSeparators = [' ', '[', ']', '(', ')', '-', '·', ','];

    private static bool ContainsWord(string text, string word) =>
        text.Split(WordSeparators, StringSplitOptions.RemoveEmptyEntries)
            .Any(part => part.Equals(word, StringComparison.OrdinalIgnoreCase));
}

/// <summary>What the player remembers per title (DIFF.md §3.3). Property names match Apple's.</summary>
public sealed class ContentPlayerPreferences
{
    [JsonPropertyName("speed")] public double? Speed { get; set; }
    [JsonPropertyName("audioTrackLanguage")] public string? AudioTrackLanguage { get; set; }
    [JsonPropertyName("audioTrackName")] public string? AudioTrackName { get; set; }
    [JsonPropertyName("subtitleEnabled")] public bool? SubtitleEnabled { get; set; }
    [JsonPropertyName("subtitleTrackLanguage")] public string? SubtitleTrackLanguage { get; set; }
    [JsonPropertyName("subtitleTrackName")] public string? SubtitleTrackName { get; set; }
    [JsonPropertyName("videoTrackWidth")] public int? VideoTrackWidth { get; set; }
    [JsonPropertyName("videoTrackHeight")] public int? VideoTrackHeight { get; set; }
}

/// <summary>
/// Per-title speed and track memory. A movie keeps its own entry and a whole
/// show shares one, so a language picked in episode 1 carries to episode 2.
/// Files without a TMDB id store nothing.
/// </summary>
public static class TitlePlaybackMemory
{
    public const string KeyPrefix = "player.content.";

    /// <summary>
    /// <c>player.content.movie.&lt;tmdbId&gt;</c> for a movie, or
    /// <c>player.content.show.&lt;showTmdbId&gt;</c> for any episode of a show.
    /// </summary>
    public static string? ContentKey(string mediaType, int? tmdbId, int? showTmdbId) => mediaType switch
    {
        "movie" when tmdbId is > 0 => $"{KeyPrefix}movie.{tmdbId}",
        "episode" when showTmdbId is > 0 => $"{KeyPrefix}show.{showTmdbId}",
        _ => null,
    };

    /// <summary>
    /// Captures the current choices. Only embedded subtitles are remembered;
    /// "off" is remembered only when the file has subtitle tracks to turn off.
    /// </summary>
    public static ContentPlayerPreferences Snapshot(
        double rate,
        PlayerTrack? audio,
        PlayerTrack? subtitle,
        bool hasSubtitleTracks,
        PlayerTrack? video)
    {
        var preferences = new ContentPlayerPreferences { Speed = PlayerLogic.NormalizedRate(rate) };
        if (audio is not null)
        {
            preferences.AudioTrackLanguage = audio.Language;
            preferences.AudioTrackName = audio.Name;
        }

        if (subtitle is not null)
        {
            if (!subtitle.IsExternal)
            {
                preferences.SubtitleEnabled = true;
                preferences.SubtitleTrackLanguage = subtitle.Language;
                preferences.SubtitleTrackName = subtitle.Name;
            }
        }
        else if (hasSubtitleTracks)
        {
            preferences.SubtitleEnabled = false;
        }

        if (video is not null && video.Width > 0 && video.Height > 0)
        {
            preferences.VideoTrackWidth = video.Width;
            preferences.VideoTrackHeight = video.Height;
        }
        return preferences;
    }

    /// <summary>Audio is matched by language first, then by track name.</summary>
    public static PlayerTrack? BestAudioMatch(ContentPlayerPreferences preferences, IReadOnlyList<PlayerTrack> tracks) =>
        Match(preferences.AudioTrackLanguage, preferences.AudioTrackName, tracks);

    /// <summary>Subtitles likewise, among embedded tracks only.</summary>
    public static PlayerTrack? BestSubtitleMatch(ContentPlayerPreferences preferences, IReadOnlyList<PlayerTrack> tracks) =>
        Match(preferences.SubtitleTrackLanguage, preferences.SubtitleTrackName, [.. tracks.Where(track => !track.IsExternal)]);

    /// <summary>A video track is matched by width × height, and only when the file has more than one.</summary>
    public static PlayerTrack? VideoMatch(ContentPlayerPreferences preferences, IReadOnlyList<PlayerTrack> tracks)
    {
        if (tracks.Count <= 1 || preferences.VideoTrackWidth is not int width || preferences.VideoTrackHeight is not int height)
        {
            return null;
        }
        return tracks.FirstOrDefault(track => track.Width == width && track.Height == height);
    }

    private static PlayerTrack? Match(string? language, string? name, IReadOnlyList<PlayerTrack> tracks)
    {
        if (!string.IsNullOrEmpty(language)
            && tracks.FirstOrDefault(track => string.Equals(track.Language, language, StringComparison.OrdinalIgnoreCase)) is { } byLanguage)
        {
            return byLanguage;
        }
        if (name is not null && tracks.FirstOrDefault(track => track.Name == name) is { } byName)
        {
            return byName;
        }
        return null;
    }
}
