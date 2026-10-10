using System.Text.Json.Serialization;
using Edendale.Windows.Core;
// Local library records persisted to %LOCALAPPDATA%\Edendale\library.json.
// Windows-only shell state: file paths never leave this machine, mirroring
// the local-only SwiftData store on Apple platforms.

namespace Edendale.Windows.Services;

public sealed class LibraryFolder
{
    public Guid Id { get; set; } = Guid.NewGuid();

    /// <summary>
    /// Local folders: the file-system path. SMB shares: the UNC path. Other
    /// remote sources: the credential-free canonical URL (SourceUrl).
    /// </summary>
    public string Path { get; set; } = "";
    public string Name { get; set; } = "";
    public DateTimeOffset DateAdded { get; set; } = DateTimeOffset.UtcNow;

    /// <summary>
    /// <see cref="MediaSourceKind"/> raw value. Null in libraries written
    /// before 27.0, which held only local folders and UNC shares, so the kind
    /// is then read from the path's shape.
    /// </summary>
    public string? Kind { get; set; }

    /// <summary>Username or account email the source was linked with; never a secret.</summary>
    public string? Username { get; set; }

    /// <summary>
    /// A readable location for the source's row, for example
    /// "OneDrive › Films". Provider URLs hold IDs, not names.
    /// </summary>
    public string? DisplayPath { get; set; }

    /// <summary>The account key of a cloud or S3 source (also its URL host).</summary>
    public string? AccountKey { get; set; }

    /// <summary>
    /// When the source was last listed. The automatic rescan on each library
    /// visit skips remote sources scanned in the last 15 minutes.
    /// </summary>
    public DateTimeOffset? LastScannedAt { get; set; }

    /// <summary>
    /// A provider change cursor for incremental rescans (Dropbox
    /// list_folder/continue, OneDrive delta). Reserved: rescans still list
    /// the whole source.
    /// </summary>
    public string? ChangeCursor { get; set; }

    [JsonIgnore]
    public MediaSourceKind SourceKind =>
        MediaSourceKinds.FromRawValue(Kind) ?? MediaSourceKinds.FromPath(Path);

    [JsonIgnore]
    public bool IsRemote => SourceKind.IsRemote();

    /// <summary>What a source row shows as its location.</summary>
    [JsonIgnore]
    public string LocationDescription => DisplayPath ?? Path;

    /// <summary>
    /// Remote sources scanned more recently than this are skipped by the
    /// automatic sweep on every library visit; Ctrl+R, F5, and Rescan still scan.
    /// </summary>
    public static readonly TimeSpan AutomaticRescanInterval = TimeSpan.FromMinutes(15);

    /// <summary>Local folders always; remote sources once their last scan is older than 15 minutes.</summary>
    public bool NeedsAutomaticRescan(DateTimeOffset now)
    {
        if (!SourceKind.ThrottlesAutomaticRescans() || LastScannedAt is not { } scanned) return true;
        return now - scanned >= AutomaticRescanInterval;
    }
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

/// <summary>Why a linked source couldn't be scanned (DIFF.md §3.12).</summary>
public enum SourceStateKind
{
    /// <summary>Unreachable right now: offline, server down, or rate-limited.</summary>
    Offline,
    /// <summary>Its account or login is gone or refused: sign in again.</summary>
    NeedsSignIn,
}

public sealed record SourceState(SourceStateKind Kind, string Message)
{
    public static SourceState Offline(string message) => new(SourceStateKind.Offline, message);
    public static SourceState NeedsSignIn(string message) => new(SourceStateKind.NeedsSignIn, message);
}
