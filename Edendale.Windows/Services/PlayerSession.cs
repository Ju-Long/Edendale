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

    /// <summary>
    /// The player is on screen (Apple's isPlayerPresented). The pages under
    /// the overlay stay live, so work they do for show, like Home's rotating
    /// hero, waits while this is true (apple-27.1 e90a988).
    /// </summary>
    public bool IsPresented { get; private set; }

    /// <summary>Raised when <see cref="IsPresented"/> changes, never for a repeated value.</summary>
    public event EventHandler? PresentedChanged;

    /// <summary>The shell reports the player opening and closing.</summary>
    public void SetPresented(bool presented)
    {
        if (presented == IsPresented) return;
        IsPresented = presented;
        PresentedChanged?.Invoke(this, EventArgs.Empty);
    }

    public void Play(LibraryMovie movie) => PlaybackRequested?.Invoke(this, new PlaybackRequest
    {
        FilePath = movie.FilePath,
        Title = movie.Title,
        Subtitle = movie.Year?.ToString(),
        TmdbId = movie.TmdbId,
        MediaType = "movie",
    });

    public void Play(LibraryShow show, LibraryEpisode episode) => PlaybackRequested?.Invoke(this, RequestFor(show, episode));

    /// <summary>The request for one stored episode; also what auto-advance and Up Next present.</summary>
    public static PlaybackRequest RequestFor(LibraryShow show, LibraryEpisode episode) => new()
    {
        FilePath = episode.FilePath,
        Title = show.Name,
        Subtitle = $"{episode.EpisodeCode} · {episode.DisplayTitle}",
        TmdbId = episode.TmdbId,
        MediaType = "episode",
        ShowTmdbId = show.TmdbId,
        SeasonNumber = episode.Season,
        EpisodeNumber = episode.Episode,
    };

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
