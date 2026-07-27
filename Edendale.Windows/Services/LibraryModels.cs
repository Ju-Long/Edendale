using Edendale.Windows.Core;
// Local library records persisted to %LOCALAPPDATA%\Edendale\library.json.
// Windows-only shell state: file paths never leave this machine, mirroring
// the local-only SwiftData store on Apple platforms.

namespace Edendale.Windows.Services;

public sealed class LibraryFolder
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Path { get; set; } = "";
    public string Name { get; set; } = "";
    public DateTimeOffset DateAdded { get; set; } = DateTimeOffset.UtcNow;
}

public sealed class LibraryMovie
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid FolderId { get; set; }
    public string FilePath { get; set; } = "";
    public string Title { get; set; } = "";
    public int? Year { get; set; }
    public int? TmdbId { get; set; }
    public string? PosterUrl { get; set; }
    public string? BackdropUrl { get; set; }
    public string? Overview { get; set; }
    public int? RuntimeMinutes { get; set; }
    public DateTimeOffset DateAdded { get; set; } = DateTimeOffset.UtcNow;

    public string DisplaySubtitle
    {
        get
        {
            var parts = new List<string>();
            if (Year is int year) parts.Add(year.ToString());
            if (RuntimeMinutes is int minutes && minutes > 0) parts.Add($"{minutes} min");
            return parts.Count > 0 ? string.Join(" · ", parts) : AppText.Get("Library_AwaitingMetadata");
        }
    }
}

public sealed class LibraryEpisode
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid FolderId { get; set; }
    public string FilePath { get; set; } = "";
    public int Season { get; set; }
    public int Episode { get; set; }
    public string? Title { get; set; }
    public int? TmdbId { get; set; }
    public string? StillUrl { get; set; }
    public int? RuntimeMinutes { get; set; }
    public DateTimeOffset DateAdded { get; set; } = DateTimeOffset.UtcNow;

    public string EpisodeCode => $"S{Season:00}E{Episode:00}";
    public string DisplayTitle => string.IsNullOrWhiteSpace(Title) ? EpisodeCode : Title!;
}

public sealed class LibraryShow
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = "";
    public int? TmdbId { get; set; }
    public string? PosterUrl { get; set; }
    public string? BackdropUrl { get; set; }
    public string? Overview { get; set; }
    public int? FirstAirYear { get; set; }
    public List<LibraryEpisode> Episodes { get; set; } = [];
    public DateTimeOffset DateAdded { get; set; } = DateTimeOffset.UtcNow;

    public IReadOnlyList<int> AvailableSeasons =>
        [.. Episodes.Select(episode => episode.Season).Distinct().Order()];

    public IReadOnlyList<LibraryEpisode> EpisodesFor(int season) =>
        [.. Episodes.Where(episode => episode.Season == season).OrderBy(episode => episode.Episode)];

    public string DisplaySubtitle
    {
        get
        {
            var seasons = AvailableSeasons.Count;
            var episodes = Episodes.Count;
            var seasonText = seasons == 1 ? "1 season" : $"{seasons} seasons";
            var episodeText = episodes == 1 ? "1 episode" : $"{episodes} episodes";
            return $"{seasonText} · {episodeText}";
        }
    }
}

public sealed class LibraryData
{
    public List<LibraryFolder> Folders { get; set; } = [];
    public List<LibraryMovie> Movies { get; set; } = [];
    public List<LibraryShow> Shows { get; set; } = [];
}
