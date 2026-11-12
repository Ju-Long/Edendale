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

    /// <summary>
    /// Google Drive: ids of picked files removed from the library on this PC.
    /// Google can't unshare one file with an app, so rescans skip these
    /// instead (ENHANCEMENT.md 3.20 D2). Library data, so it never leaves
    /// this PC; picking a file again brings it back.
    /// </summary>
    public List<string>? HiddenItemIds { get; set; }

    [JsonIgnore]
    public MediaSourceKind SourceKind =>
        MediaSourceKinds.FromRawValue(Kind) ?? MediaSourceKinds.FromPath(Path);

    /// <summary>
    /// A Google Drive source from before drive.file: a Drive folder, which
    /// can't be listed any more, rather than "Videos you chose" (3.20 D1).
    /// </summary>
    [JsonIgnore]
    public bool IsFromBeforeDriveFile =>
        SourceKind == MediaSourceKind.GoogleDrive && !Remote.GoogleDriveConnector.IsChosenFolder(Path);

    /// <summary>Whether a scan should skip this item: a Drive file removed on this PC.</summary>
    public bool Hides(string itemPath) =>
        HiddenItemIds is { Count: > 0 } hidden
        && Remote.GoogleDriveConnector.FileId(itemPath) is { } id
        && hidden.Contains(id, StringComparer.Ordinal);

    /// <summary>Remembers removed Drive items so rescans skip them; other kinds keep nothing.</summary>
    public void Hide(IEnumerable<string> itemPaths)
    {
        if (SourceKind != MediaSourceKind.GoogleDrive) return;
        foreach (var id in itemPaths.Select(Remote.GoogleDriveConnector.FileId).OfType<string>())
        {
            HiddenItemIds ??= [];
            if (!HiddenItemIds.Contains(id, StringComparer.Ordinal)) HiddenItemIds.Add(id);
        }
    }

    /// <summary>Picking a hidden file again brings it back.</summary>
    public void Unhide(IEnumerable<string> fileIds)
    {
        if (HiddenItemIds is null) return;
        var picked = fileIds.ToHashSet(StringComparer.Ordinal);
        HiddenItemIds.RemoveAll(picked.Contains);
        if (HiddenItemIds.Count == 0) HiddenItemIds = null;
    }

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

    /// <summary>
    /// When this copy last started playing on this PC. Of several copies of
    /// one title (different sources or endpoints), the one played last is the
    /// default (Movie.lastPlayedAt, apple-27.1 94a8b4f). Library data: it
    /// never syncs. Null in libraries written before 27.1.
    /// </summary>
    public DateTimeOffset? LastPlayedAt { get; set; }
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

    /// <summary>
    /// "1 season · 3 episodes" for this record alone, an episode held by two
    /// sources counted once. A card that stands for several records counts
    /// them together (PlaybackSources.ShowSubtitle).
    /// </summary>
    public string DisplaySubtitle => PlaybackSources.ShowSubtitle([this]);

    /// <summary>
    /// When an episode of this show last started playing on this PC, by the
    /// source (folder id) it played from. One Windows record can span
    /// sources, so this is Apple's per-copy TVShow.lastPlayedAt kept per
    /// source: it remembers the source, or endpoint, the viewer chose for the
    /// show, and outlives the file that was watched. Library data: it never
    /// syncs. Null until something plays, and in libraries before 27.1.
    /// </summary>
    public Dictionary<Guid, DateTimeOffset>? LastPlayedAtBySource { get; set; }
}

public sealed class LibraryData
{
    public List<LibraryFolder> Folders { get; set; } = [];
    public List<LibraryMovie> Movies { get; set; } = [];
    public List<LibraryShow> Shows { get; set; } = [];

    // ------------------------------------------------------------------
    // Titles in several sources (apple-27.1 94a8b4f). LibraryService calls
    // these under its lock; they live here so the tests can reach them.
    // ------------------------------------------------------------------

    public LibraryFolder? FolderById(Guid id) => Folders.FirstOrDefault(folder => folder.Id == id);

    /// <summary>
    /// Every copy of <paramref name="movie"/>'s title (PlaybackSources.CopyKey):
    /// the movie first, then the others local first, then by source name and
    /// path, as Play From lists them.
    /// </summary>
    public IReadOnlyList<LibraryMovie> MovieCopies(LibraryMovie movie)
    {
        var key = PlaybackSources.CopyKey(movie);
        var others = Movies
            .Where(copy => copy.Id != movie.Id && PlaybackSources.CopyKey(copy) == key)
            .OrderByDescending(copy => copy.DateAdded);
        return PlaybackSources.Order(movie, others, copy => FolderById(copy.FolderId));
    }

    /// <summary>
    /// Every record of <paramref name="show"/>'s title: the show first, then
    /// the others newest first. A record can hold several sources, and two
    /// names for one show ("Severance" and "Severance (2022)") make two
    /// records that a TMDB match ties together.
    /// </summary>
    public IReadOnlyList<LibraryShow> ShowCopies(LibraryShow show)
    {
        var key = PlaybackSources.CopyKey(show);
        return
        [
            show,
            .. Shows
                .Where(copy => copy.Id != show.Id && PlaybackSources.CopyKey(copy) == key)
                .OrderByDescending(copy => copy.DateAdded),
        ];
    }

    /// <summary>
    /// Notes that the file at <paramref name="filePath"/> started playing: a
    /// movie copy's own stamp, or for an episode, its show record's stamp for
    /// the episode's source. Paths compare the way scans store them. False
    /// when the file isn't in the library (Open With of a loose file).
    /// </summary>
    public bool NotePlayed(string filePath, DateTimeOffset at)
    {
        var comparison = SourceUrl.IsUrl(filePath) ? StringComparison.Ordinal : StringComparison.OrdinalIgnoreCase;
        if (Movies.FirstOrDefault(movie => string.Equals(movie.FilePath, filePath, comparison)) is { } played)
        {
            played.LastPlayedAt = at;
            return true;
        }
        foreach (var show in Shows)
        {
            if (show.Episodes.FirstOrDefault(episode => string.Equals(episode.FilePath, filePath, comparison)) is { } episode)
            {
                (show.LastPlayedAtBySource ??= [])[episode.FolderId] = at;
                return true;
            }
        }
        return false;
    }

    /// <summary>
    /// The copy of a TMDB movie to play when several sources hold one: the
    /// reachable copy played last, else the first reachable in Play From
    /// order starting from the newest copy, as the Downloaded card's detail
    /// page would pick.
    /// </summary>
    public LibraryMovie? PreferredMovie(int tmdbId, Func<Guid, bool> isUnavailable)
    {
        var newest = Movies.Where(movie => movie.TmdbId == tmdbId).MaxBy(movie => movie.DateAdded);
        return newest is null
            ? null
            : PlaybackSources.Preferred(MovieCopies(newest), movie => isUnavailable(movie.FolderId), movie => movie.LastPlayedAt);
    }

    /// <summary>
    /// The file of a TMDB episode to play when several sources hold it: the
    /// one in the first source of PlaybackSources.SourcePreference (the
    /// reachable source played last, else the first reachable), with the
    /// record holding it.
    /// </summary>
    public ShowEpisode? PreferredEpisode(int episodeTmdbId, Func<Guid, bool> isUnavailable)
    {
        var candidates = Shows
            .OrderByDescending(show => show.DateAdded)
            .SelectMany(show => show.Episodes
                .Where(episode => episode.TmdbId == episodeTmdbId)
                .Select(episode => new ShowEpisode(show, episode)))
            .ToList();
        if (candidates.Count == 0) return null;
        var preference = PlaybackSources.SourcePreference(ShowCopies(candidates[0].Show), FolderById, isUnavailable);
        return PlaybackSources.PreferredEpisode(candidates, preference);
    }

    /// <summary>
    /// Removes one source's episodes from a show title's records (Remove
    /// from "Media (nas)"), and that source's play memory with them. Records
    /// left empty go; a Google Drive source keeps the removed files hidden
    /// from later scans (ENHANCEMENT.md 3.20 D2). The other sources stay.
    /// </summary>
    public bool RemoveShowSource(IReadOnlyCollection<Guid> showIds, Guid folderId)
    {
        var removed = new List<string>();
        foreach (var show in Shows.Where(show => showIds.Contains(show.Id)))
        {
            removed.AddRange(show.Episodes.Where(episode => episode.FolderId == folderId).Select(episode => episode.FilePath));
            show.Episodes.RemoveAll(episode => episode.FolderId == folderId);
            ForgetSource(show, folderId);
        }
        var dropped = Shows.RemoveAll(show => showIds.Contains(show.Id) && show.Episodes.Count == 0);
        FolderById(folderId)?.Hide(removed);
        return removed.Count > 0 || dropped > 0;
    }

    /// <summary>
    /// Removes every record of a show title, as Remove on its card does when
    /// one source holds it; removed Google Drive files stay hidden from later
    /// scans (ENHANCEMENT.md 3.20 D2).
    /// </summary>
    public bool RemoveShows(IReadOnlyCollection<Guid> showIds)
    {
        var removed = Shows.Where(show => showIds.Contains(show.Id)).ToList();
        foreach (var episodes in removed.SelectMany(show => show.Episodes).GroupBy(episode => episode.FolderId))
        {
            FolderById(episodes.Key)?.Hide(episodes.Select(episode => episode.FilePath));
        }
        return Shows.RemoveAll(show => showIds.Contains(show.Id)) > 0;
    }

    /// <summary>Forgets that anything of the show played from a source, as when the source goes.</summary>
    public static void ForgetSource(LibraryShow show, Guid folderId)
    {
        if (show.LastPlayedAtBySource is not { } stamps) return;
        stamps.Remove(folderId);
        if (stamps.Count == 0) show.LastPlayedAtBySource = null;
    }
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
