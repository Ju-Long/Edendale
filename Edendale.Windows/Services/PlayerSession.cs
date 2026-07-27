namespace Edendale.Windows.Services;

/// <summary>One playable local file plus the identity used for progress writes.</summary>
public sealed class PlaybackRequest
{
    public required string FilePath { get; init; }
    public required string Title { get; init; }
    public string? Subtitle { get; init; }
    /// <summary>TMDB id of the movie or episode; null files play without progress tracking.</summary>
    public int? TmdbId { get; init; }
    /// <summary>"movie" or "episode", matching the Windows watch-progress model.</summary>
    public string MediaType { get; init; } = "movie";
    public int? ShowTmdbId { get; init; }
    public int? SeasonNumber { get; init; }
    public int? EpisodeNumber { get; init; }
}

/// <summary>
/// Routes play requests from any page to the shell's full-window player
/// overlay — the single presentation point, like PlayerSession on Apple.
/// </summary>
public sealed class PlayerSession
{
    public event EventHandler<PlaybackRequest>? PlaybackRequested;

    public void Play(LibraryMovie movie) => PlaybackRequested?.Invoke(this, new PlaybackRequest
    {
        FilePath = movie.FilePath,
        Title = movie.Title,
        Subtitle = movie.Year?.ToString(),
        TmdbId = movie.TmdbId,
        MediaType = "movie",
    });

    public void Play(LibraryShow show, LibraryEpisode episode) => PlaybackRequested?.Invoke(this, new PlaybackRequest
    {
        FilePath = episode.FilePath,
        Title = show.Name,
        Subtitle = $"{episode.EpisodeCode} · {episode.DisplayTitle}",
        TmdbId = episode.TmdbId,
        MediaType = "episode",
        ShowTmdbId = show.TmdbId,
        SeasonNumber = episode.Season,
        EpisodeNumber = episode.Episode,
    });

    /// <summary>
    /// Open With / command-line playback: straight to the player, never
    /// imported into the library (Apple Phase 11). No TMDB id, so no
    /// progress writes.
    /// </summary>
    public void PlayFile(string path) => PlaybackRequested?.Invoke(this, new PlaybackRequest
    {
        FilePath = path,
        Title = Path.GetFileNameWithoutExtension(path),
    });
}
