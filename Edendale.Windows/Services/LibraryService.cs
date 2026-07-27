// Import pipeline, Apple-parity ordering (LibraryController): classify locally
// first in C# (no network on the import fast path), persist immediately so the
// UI lists files at once, then enrich from TMDB in the background.

using System.Text.Json;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services;

public sealed class LibraryService
{
    /// <summary>One list for import scans and the Open With registration (ActivationService).</summary>
    public static readonly string[] SupportedVideoExtensions =
    [
        ".mkv", ".mp4", ".m4v", ".mov", ".avi", ".wmv", ".webm",
        ".ts", ".m2ts", ".mpg", ".mpeg", ".flv", ".3gp",
    ];

    private static readonly HashSet<string> VideoExtensions =
        new(SupportedVideoExtensions, StringComparer.OrdinalIgnoreCase);

    public static bool IsSupportedVideoFile(string path) =>
        VideoExtensions.Contains(Path.GetExtension(path));

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true,
    };

    private readonly object _gate = new();
    private readonly SmbCredentialsStore _smbCredentials;
    private LibraryData _data = new();

    public event EventHandler? Changed;

    public bool IsImporting { get; private set; }
    public bool IsEnriching { get; private set; }
    public string? ErrorMessage { get; private set; }

    public LibraryService(SmbCredentialsStore smbCredentials)
    {
        _smbCredentials = smbCredentials;
        try
        {
            if (File.Exists(AppPaths.LibraryFile))
            {
                _data = JsonSerializer.Deserialize<LibraryData>(
                    File.ReadAllText(AppPaths.LibraryFile), JsonOptions) ?? new LibraryData();
            }
        }
        catch
        {
            _data = new LibraryData();
        }
    }

    public IReadOnlyList<LibraryFolder> Folders { get { lock (_gate) return [.. _data.Folders]; } }

    public IReadOnlyList<LibraryMovie> Movies
    {
        get { lock (_gate) return [.. _data.Movies.OrderByDescending(m => m.DateAdded)]; }
    }

    public IReadOnlyList<LibraryShow> Shows
    {
        get { lock (_gate) return [.. _data.Shows.OrderByDescending(s => s.DateAdded)]; }
    }

    public bool IsEmpty
    {
        get { lock (_gate) return _data.Folders.Count == 0 && _data.Movies.Count == 0 && _data.Shows.Count == 0; }
    }

    public int ItemCount(LibraryFolder folder)
    {
        lock (_gate)
        {
            return _data.Movies.Count(m => m.FolderId == folder.Id)
                + _data.Shows.Sum(s => s.Episodes.Count(e => e.FolderId == folder.Id));
        }
    }

    public LibraryMovie? MovieByTmdbId(int tmdbId)
    {
        lock (_gate) return _data.Movies.FirstOrDefault(m => m.TmdbId == tmdbId);
    }

    public LibraryShow? ShowByTmdbId(int tmdbId)
    {
        lock (_gate) return _data.Shows.FirstOrDefault(s => s.TmdbId == tmdbId);
    }

    public LibraryEpisode? EpisodeByTmdbId(int tmdbId)
    {
        lock (_gate)
        {
            return _data.Shows.SelectMany(s => s.Episodes).FirstOrDefault(e => e.TmdbId == tmdbId);
        }
    }

    public LibraryShow? ShowForEpisode(LibraryEpisode episode)
    {
        lock (_gate) return _data.Shows.FirstOrDefault(s => s.Episodes.Any(e => e.Id == episode.Id));
    }

    // ------------------------------------------------------------------
    // Import & rescan
    // ------------------------------------------------------------------

    public async Task ImportFolderAsync(string path)
    {
        LibraryFolder folder;
        lock (_gate)
        {
            var existing = _data.Folders.FirstOrDefault(f =>
                string.Equals(f.Path, path, StringComparison.OrdinalIgnoreCase));
            if (existing is null)
            {
                folder = new LibraryFolder { Path = path, Name = Path.GetFileName(path) is { Length: > 0 } name ? name : path };
                _data.Folders.Add(folder);
            }
            else
            {
                folder = existing;
            }
        }
        await ScanFolderAsync(folder);
    }

    public Task RescanFolderAsync(LibraryFolder folder) => ScanFolderAsync(folder);

    public async Task RescanAllFoldersAsync()
    {
        foreach (var folder in Folders) await ScanFolderAsync(folder);
    }

    public void RemoveFolder(LibraryFolder folder)
    {
        bool hostStillUsed;
        var host = SmbCredentialsStore.HostFromUncPath(folder.Path);
        lock (_gate)
        {
            _data.Folders.RemoveAll(f => f.Id == folder.Id);
            _data.Movies.RemoveAll(m => m.FolderId == folder.Id);
            foreach (var show in _data.Shows) show.Episodes.RemoveAll(e => e.FolderId == folder.Id);
            _data.Shows.RemoveAll(s => s.Episodes.Count == 0);
            hostStillUsed = host is not null && _data.Folders.Any(f =>
                string.Equals(SmbCredentialsStore.HostFromUncPath(f.Path), host, StringComparison.OrdinalIgnoreCase));
        }
        if (host is not null && !hostStillUsed) _smbCredentials.Remove(host);
        Save();
    }

    public void RemoveMovie(LibraryMovie movie)
    {
        lock (_gate) _data.Movies.RemoveAll(m => m.Id == movie.Id);
        Save();
    }

    public void RemoveShow(LibraryShow show)
    {
        lock (_gate) _data.Shows.RemoveAll(s => s.Id == show.Id);
        Save();
    }

    private async Task ScanFolderAsync(LibraryFolder folder)
    {
        IsImporting = true;
        ErrorMessage = null;
        Changed?.Invoke(this, EventArgs.Empty);
        try
        {
            var imported = await Task.Run(() => Scan(folder));
            if (imported) Save();
        }
        catch (Exception failure)
        {
            ErrorMessage = $"Import failed: {failure.Message}";
        }
        finally
        {
            IsImporting = false;
            Changed?.Invoke(this, EventArgs.Empty);
        }

        _ = EnrichAsync();
    }

    /// <summary>Classify-before-network: only the local filename parser runs here.</summary>
    private bool Scan(LibraryFolder folder)
    {
        // A network folder may need its SMB session re-established (with the
        // stored username/password) before it is visible again.
        if (folder.Path.StartsWith(@"\\", StringComparison.Ordinal))
        {
            NetworkShare.TryConnect(folder.Path, _smbCredentials);
        }

        if (!Directory.Exists(folder.Path))
        {
            ErrorMessage = $"Folder not found: {folder.Path}";
            return false;
        }

        // Walk the tree by hand (EnumerateVideoFiles) so one unreadable
        // directory costs that directory, not the whole import. IgnoreInaccessible
        // only forgives access-denied, and a recursive EnumerateFiles tears the
        // entire scan down the instant a folder answers with any other I/O error
        // — a macOS SMB share returning "Invalid Signature." (STATUS_INVALID_SIGNATURE)
        // for one subfolder took every file on the share down with it.
        var files = EnumerateVideoFiles(folder.Path);

        var changed = false;
        lock (_gate)
        {
            // Drop records whose file disappeared since the last scan.
            changed |= _data.Movies.RemoveAll(m => m.FolderId == folder.Id && !File.Exists(m.FilePath)) > 0;
            foreach (var show in _data.Shows)
            {
                changed |= show.Episodes.RemoveAll(e => e.FolderId == folder.Id && !File.Exists(e.FilePath)) > 0;
            }
            changed |= _data.Shows.RemoveAll(s => s.Episodes.Count == 0) > 0;

            var knownPaths = new HashSet<string>(
                _data.Movies.Select(m => m.FilePath)
                    .Concat(_data.Shows.SelectMany(s => s.Episodes).Select(e => e.FilePath)),
                StringComparer.OrdinalIgnoreCase);

            foreach (var file in files)
            {
                if (knownPaths.Contains(file)) continue;
                var parsed = WindowsCore.ParseMediaFile(Path.GetFileName(file));
                if (parsed.IsEpisode)
                {
                    var showName = parsed.ShowName ?? Path.GetFileNameWithoutExtension(file);
                    var show = _data.Shows.FirstOrDefault(s =>
                        string.Equals(s.Name, showName, StringComparison.OrdinalIgnoreCase));
                    if (show is null)
                    {
                        show = new LibraryShow { Name = showName };
                        _data.Shows.Add(show);
                    }
                    show.Episodes.Add(new LibraryEpisode
                    {
                        FolderId = folder.Id,
                        FilePath = file,
                        Season = parsed.Season ?? 1,
                        Episode = parsed.Episode ?? 1,
                    });
                }
                else
                {
                    _data.Movies.Add(new LibraryMovie
                    {
                        FolderId = folder.Id,
                        FilePath = file,
                        Title = parsed.Title ?? Path.GetFileNameWithoutExtension(file),
                        Year = parsed.Year,
                    });
                }
                changed = true;
            }
        }
        return changed;
    }

    /// <summary>
    /// Every supported video file under <paramref name="root"/>, gathered with a
    /// hand-rolled recursion so a single directory the filesystem refuses to
    /// enumerate is skipped rather than aborting the walk. Each directory is read
    /// non-recursively inside its own try/catch; hidden/system entries (the
    /// .DocumentRevisions-V100-style metadata macOS scatters on SMB shares) and
    /// access-denied ones are still skipped by <see cref="EnumerationOptions"/>,
    /// exactly as the old recursive call did — but an IOException such as an SMB
    /// "Invalid Signature." now costs only its own folder.
    /// </summary>
    private static List<string> EnumerateVideoFiles(string root)
    {
        var options = new EnumerationOptions
        {
            IgnoreInaccessible = true,
            AttributesToSkip = FileAttributes.Hidden | FileAttributes.System,
        };

        var videos = new List<string>();
        var pending = new Stack<string>();
        pending.Push(root);
        while (pending.Count > 0)
        {
            var directory = pending.Pop();
            try
            {
                foreach (var file in Directory.GetFiles(directory, "*", options))
                {
                    if (VideoExtensions.Contains(Path.GetExtension(file))) videos.Add(file);
                }
                foreach (var subdirectory in Directory.GetDirectories(directory, "*", options))
                {
                    pending.Push(subdirectory);
                }
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                // Unreadable directory (an SMB signing failure, a dropped mount, a
                // folder we lack rights to): skip it and keep walking the rest.
            }
        }
        return videos;
    }

    // ------------------------------------------------------------------
    // Background TMDB enrichment — never blocks the import fast path.
    // ------------------------------------------------------------------

    private int _enrichmentRunning;

    private async Task EnrichAsync()
    {
        if (!WindowsCore.HasTmdbCredentials) return;
        if (Interlocked.Exchange(ref _enrichmentRunning, 1) == 1) return;

        IsEnriching = true;
        Changed?.Invoke(this, EventArgs.Empty);
        try
        {
            foreach (var movie in Movies.Where(m => m.TmdbId is null))
            {
                await EnrichMovieAsync(movie);
            }
            foreach (var show in Shows)
            {
                if (show.TmdbId is null) await EnrichShowAsync(show);
                if (show.TmdbId is int showId)
                {
                    foreach (var episode in show.Episodes.Where(e => e.TmdbId is null).ToList())
                    {
                        await EnrichEpisodeAsync(showId, episode);
                    }
                }
            }
        }
        finally
        {
            Interlocked.Exchange(ref _enrichmentRunning, 0);
            IsEnriching = false;
            Save();
        }
    }

    private async Task EnrichMovieAsync(LibraryMovie movie)
    {
        try
        {
            var results = await WindowsCore.SearchMediaAsync(movie.Title);
            var match = results
                .Where(item => item.MediaType == "movie")
                .OrderByDescending(item => movie.Year is int year && item.Year == year)
                .FirstOrDefault();
            if (match is null) return;

            movie.TmdbId = match.Id;
            movie.PosterUrl = match.PosterUrl;
            movie.BackdropUrl = match.BackdropUrl;
            movie.Overview ??= match.Overview;
            movie.Year ??= match.Year;

            var detail = await WindowsCore.LoadMediaDetailAsync(match.Id, "movie");
            movie.RuntimeMinutes = detail.RuntimeMinutes;
            movie.Overview = detail.Overview ?? movie.Overview;
        }
        catch
        {
            // Enrichment is best-effort; the file stays usable without it.
        }
    }

    private async Task EnrichShowAsync(LibraryShow show)
    {
        try
        {
            var results = await WindowsCore.SearchMediaAsync(show.Name);
            var match = results.FirstOrDefault(item => item.MediaType == "tv");
            if (match is null) return;

            show.TmdbId = match.Id;
            show.PosterUrl = match.PosterUrl;
            show.BackdropUrl = match.BackdropUrl;
            show.Overview = match.Overview;
            show.FirstAirYear = match.Year;
        }
        catch
        {
            // Best-effort.
        }
    }

    private async Task EnrichEpisodeAsync(int showTmdbId, LibraryEpisode episode)
    {
        try
        {
            var detail = await WindowsCore.LoadEpisodeDetailAsync(showTmdbId, episode.Season, episode.Episode);
            if (detail.Id == 0) return;
            episode.TmdbId = detail.Id;
            episode.Title = detail.Name;
            episode.StillUrl = detail.StillUrl;
            episode.RuntimeMinutes = detail.RuntimeMinutes;
        }
        catch
        {
            // Best-effort.
        }
    }

    private void Save()
    {
        try
        {
            LibraryData snapshot;
            lock (_gate)
            {
                snapshot = new LibraryData
                {
                    Folders = [.. _data.Folders],
                    Movies = [.. _data.Movies],
                    Shows = [.. _data.Shows],
                };
            }
            var temporary = AppPaths.LibraryFile + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(snapshot, JsonOptions));
            File.Move(temporary, AppPaths.LibraryFile, overwrite: true);
        }
        catch
        {
            // Library persistence failures surface on next launch, not as crashes.
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }
}
