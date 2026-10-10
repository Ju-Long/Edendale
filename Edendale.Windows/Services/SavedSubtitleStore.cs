// Downloaded subtitles kept per title. The online search already caches each
// downloaded file in %LOCALAPPDATA%\Edendale\Subtitles; this store remembers
// which title each file was downloaded for, so the next playback of that
// title (any copy, from any source) attaches it again without a network
// request, and turns on the one that was on last time.
//
// A subtitle counts as used whenever it is on during playback. One that
// hasn't been used for 30 days is deleted, along with files no entry refers
// to, unless the reader turns that off in Settings → Subtitles. Everything
// stays on this device: neither the index nor the files enter the OneDrive
// replica.

using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using Edendale.Windows.Core;

namespace Edendale.Windows.Services;

/// <summary>A saved subtitle whose file is still on disk.</summary>
public sealed record SavedSubtitleFile(SavedSubtitle Subtitle, string FilePath)
{
    /// <summary>The file:// URI LibVLC attaches.</summary>
    public string FileUri => new Uri(FilePath).AbsoluteUri;
}

public sealed class SavedSubtitleStore
{
    /// <summary>Settings → Subtitles: remove subtitles unused for a month. On by default.</summary>
    public const string RemoveUnusedKey = "subtitles.removeUnusedDownloads";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        WriteIndented = true,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly string _indexPath;
    private readonly Func<string> _directory;
    private readonly PlayerSettingsStore? _settings;
    private readonly Func<DateTimeOffset> _clock;
    private readonly object _gate = new();
    private readonly SavedSubtitleIndex _index;

    /// <summary>Raised after the saved set changes (a download, a prune, a removal).</summary>
    public event EventHandler? Changed;

    /// <param name="indexPath">Defaults to <see cref="AppPaths.SavedSubtitlesFile"/>.</param>
    /// <param name="directory">The subtitle folder; defaults to <see cref="AppPaths.SubtitleCacheDirectory"/>.</param>
    /// <param name="settings">Holds the remove-unused switch; without it the switch reads on.</param>
    /// <param name="clock">The time source for the 30-day expiry.</param>
    public SavedSubtitleStore(
        string? indexPath = null,
        Func<string>? directory = null,
        PlayerSettingsStore? settings = null,
        Func<DateTimeOffset>? clock = null)
    {
        _indexPath = indexPath ?? AppPaths.SavedSubtitlesFile;
        _directory = directory ?? (() => AppPaths.SubtitleCacheDirectory);
        _settings = settings;
        _clock = clock ?? (() => DateTimeOffset.UtcNow);
        _index = Load(_indexPath);
    }

    /// <summary>Removes subtitles unused for <see cref="SavedSubtitleRules.RetentionPeriod"/>.</summary>
    public bool RemoveUnusedEnabled
    {
        get => _settings?.GetBool(RemoveUnusedKey, fallback: true) ?? true;
        set
        {
            if (_settings is null || value == RemoveUnusedEnabled) return;
            _settings.SetBool(RemoveUnusedKey, value);
            if (value) PruneInBackground();
        }
    }

    /// <summary>The title key for a playback request, or null for unidentified files.</summary>
    public static string? TitleKeyFor(PlaybackRequest? request) => request is null
        ? null
        : SavedSubtitleRules.TitleKey(request.MediaType, request.TmdbId, request.ShowTmdbId, request.SeasonNumber, request.EpisodeNumber);

    // ------------------------------------------------------------------
    // Recording and use
    // ------------------------------------------------------------------

    /// <summary>
    /// Keeps a subtitle downloaded while <paramref name="request"/> played.
    /// The download turned it on, so it is also the title's selected one.
    /// </summary>
    public void Record(PlaybackRequest request, DownloadedSubtitle downloaded)
    {
        if (TitleKeyFor(request) is not { } key || FileNameIn(downloaded.FilePath) is not { } name) return;
        var candidate = downloaded.Candidate;
        var now = _clock();
        lock (_gate)
        {
            _index.Record(new SavedSubtitle
            {
                TitleKey = key,
                FileName = name,
                CandidateId = candidate.Id,
                Language = candidate.Language,
                LanguageLabel = candidate.LanguageLabel,
                Release = string.IsNullOrWhiteSpace(candidate.Release) ? candidate.FileName : candidate.Release,
                Source = candidate.Source,
                HearingImpaired = candidate.IsHearingImpaired,
                DownloadedAt = now,
                LastUsedAt = now,
                Selected = true,
            });
            Save();
        }
        RaiseChanged();
    }

    /// <summary>
    /// The title's saved subtitles still on disk, the most recently used
    /// first. An entry whose file has gone (deleted by hand, say) is forgotten,
    /// and one past its 30 days counts as gone already, so a playback never
    /// attaches a file the prune is about to delete.
    /// </summary>
    public IReadOnlyList<SavedSubtitleFile> ForTitle(PlaybackRequest? request)
    {
        if (TitleKeyFor(request) is not { } key) return [];
        var files = new List<SavedSubtitleFile>();
        var forgotten = false;
        var removeUnused = RemoveUnusedEnabled;
        var now = _clock();
        lock (_gate)
        {
            var directory = _directory();
            foreach (var subtitle in _index.ForTitle(key))
            {
                if (removeUnused && SavedSubtitleRules.IsExpired(subtitle, now, SavedSubtitleRules.RetentionPeriod)) continue;
                var path = Path.Combine(directory, subtitle.FileName);
                if (File.Exists(path))
                {
                    files.Add(new SavedSubtitleFile(subtitle, path));
                }
                else
                {
                    _index.Remove(key, subtitle.FileName);
                    forgotten = true;
                }
            }
            if (forgotten) Save();
        }
        if (forgotten) RaiseChanged();
        return files;
    }

    /// <summary>The saved entry behind an attached file, or null for a side file or a missing entry.</summary>
    public SavedSubtitle? Find(PlaybackRequest? request, string fileUriOrPath)
    {
        if (TitleKeyFor(request) is not { } key || FileNameIn(fileUriOrPath) is not { } name) return null;
        lock (_gate) return _index.Find(key, name);
    }

    /// <summary>True when the search result is already saved for this title, so choosing it needs no download.</summary>
    public bool IsSaved(PlaybackRequest? request, string candidateId)
    {
        if (TitleKeyFor(request) is not { } key || string.IsNullOrEmpty(candidateId)) return false;
        lock (_gate) return _index.ForTitle(key).Any(subtitle => subtitle.CandidateId == candidateId);
    }

    /// <summary>
    /// The subtitle is on: its 30 days start again and it becomes the one the
    /// title turns on next time. A file that isn't saved is ignored.
    /// </summary>
    public void MarkUsed(PlaybackRequest? request, string fileUriOrPath)
    {
        if (TitleKeyFor(request) is not { } key || FileNameIn(fileUriOrPath) is not { } name) return;
        bool changed;
        lock (_gate)
        {
            changed = _index.MarkUsed(key, name, _clock());
            if (changed) Save();
        }
        if (changed) RaiseChanged();
    }

    /// <summary>The title was left with another track, or none, on.</summary>
    public void ClearSelection(PlaybackRequest? request)
    {
        if (TitleKeyFor(request) is not { } key) return;
        lock (_gate)
        {
            if (_index.ClearSelection(key)) Save();
        }
    }

    // ------------------------------------------------------------------
    // Size, removal, and the 30-day prune
    // ------------------------------------------------------------------

    /// <summary>The files in the subtitle folder and their total size: what Remove All frees.</summary>
    public (int Count, long Bytes) Summary()
    {
        lock (_gate)
        {
            var count = 0;
            long bytes = 0;
            foreach (var (name, _, length) in ListFiles())
            {
                if (!SavedSubtitleRules.IsSafeFileName(name)) continue;
                count++;
                bytes += length;
            }
            return (count, bytes);
        }
    }

    /// <summary>Deletes every saved subtitle and every other file in the subtitle folder.</summary>
    public void RemoveAll()
    {
        lock (_gate)
        {
            var removal = _index.RemoveAll();
            foreach (var name in removal.FilesToDelete)
            {
                if (!TryDelete(name)) _index.DeferDelete(name);
            }
            foreach (var (name, _, _) in ListFiles())
            {
                if (!SavedSubtitleRules.IsSafeFileName(name)) continue;
                if (TryDelete(name)) _index.DeleteCompleted(name);
                else _index.DeferDelete(name);
            }
            Save();
        }
        RaiseChanged();
    }

    /// <summary>
    /// Retries deletes that failed before and, while the switch is on,
    /// removes subtitles unused for 30 days and orphaned files older than
    /// that. Files in <paramref name="inUse"/> (attached to the playback on
    /// screen) are left alone. Returns how many files were deleted.
    /// </summary>
    public int Prune(IEnumerable<string>? inUse = null)
    {
        var protectedFiles = (inUse ?? [])
            .Select(FileNameIn)
            .OfType<string>()
            .ToHashSet(StringComparer.OrdinalIgnoreCase);
        var now = _clock();
        var deleted = 0;
        var changed = false;
        lock (_gate)
        {
            foreach (var name in _index.PendingDeletes.ToList())
            {
                if (protectedFiles.Contains(name) || !TryDelete(name)) continue;
                _index.DeleteCompleted(name);
                changed = true;
                deleted++;
            }

            if (RemoveUnusedEnabled)
            {
                var removal = _index.Prune(now, SavedSubtitleRules.RetentionPeriod, protectedFiles);
                changed |= removal.Removed.Count > 0;
                foreach (var name in removal.FilesToDelete)
                {
                    if (TryDelete(name)) deleted++;
                    else _index.DeferDelete(name);
                }

                var files = ListFiles().Select(file => (file.Name, file.LastWrite));
                foreach (var name in _index.OrphanFiles(files, now, SavedSubtitleRules.RetentionPeriod, protectedFiles))
                {
                    // An orphan that can't go now is still an orphan next time.
                    if (TryDelete(name)) deleted++;
                }
            }

            if (changed) Save();
        }
        if (changed || deleted > 0) RaiseChanged();
        return deleted;
    }

    /// <summary>Runs <see cref="Prune"/> off the UI thread; failures wait for the next run.</summary>
    public void PruneInBackground() => _ = Task.Run(() =>
    {
        try
        {
            Prune();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            // The folder is busy or unreadable; nothing is lost by waiting.
        }
    });

    // ------------------------------------------------------------------
    // Files
    // ------------------------------------------------------------------

    /// <summary>
    /// The file's name when it sits directly in the subtitle folder (a path or
    /// a file:// URI); null for anything else, such as a side file next to
    /// the video.
    /// </summary>
    private string? FileNameIn(string fileUriOrPath)
    {
        if (string.IsNullOrWhiteSpace(fileUriOrPath)) return null;
        string path;
        if (System.Uri.TryCreate(fileUriOrPath, UriKind.Absolute, out var uri) && uri.IsFile)
        {
            path = uri.LocalPath;
        }
        else if (Path.IsPathFullyQualified(fileUriOrPath))
        {
            path = fileUriOrPath;
        }
        else
        {
            return null;
        }

        try
        {
            var full = Path.GetFullPath(path);
            var folder = Path.GetFullPath(_directory());
            var parent = Path.GetDirectoryName(full);
            if (parent is null || !string.Equals(
                Path.TrimEndingDirectorySeparator(parent),
                Path.TrimEndingDirectorySeparator(folder),
                StringComparison.OrdinalIgnoreCase))
            {
                return null;
            }
            var name = Path.GetFileName(full);
            return SavedSubtitleRules.IsSafeFileName(name) ? name : null;
        }
        catch (Exception error) when (error is ArgumentException or NotSupportedException or PathTooLongException)
        {
            return null;
        }
    }

    private IEnumerable<(string Name, DateTimeOffset LastWrite, long Length)> ListFiles()
    {
        DirectoryInfo folder;
        FileInfo[] files;
        try
        {
            folder = new DirectoryInfo(_directory());
            files = folder.Exists ? folder.GetFiles() : [];
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            yield break;
        }

        foreach (var file in files)
        {
            yield return (file.Name, new DateTimeOffset(file.LastWriteTimeUtc, TimeSpan.Zero), file.Length);
        }
    }

    /// <summary>True when the file is gone afterwards; false when Windows refused (it's open, say).</summary>
    private bool TryDelete(string name)
    {
        if (!SavedSubtitleRules.IsSafeFileName(name)) return true;
        try
        {
            var folder = Path.GetFullPath(_directory());
            var path = Path.GetFullPath(Path.Combine(folder, name));
            if (!string.Equals(Path.GetDirectoryName(path), Path.TrimEndingDirectorySeparator(folder), StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
            if (File.Exists(path)) File.Delete(path);
            return true;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // The index file
    // ------------------------------------------------------------------

    /// <summary>
    /// Reads the index one entry at a time, so a damaged entry costs only
    /// itself. An unreadable file starts empty; its subtitles then age out as
    /// orphans rather than vanishing at once.
    /// </summary>
    private static SavedSubtitleIndex Load(string path)
    {
        try
        {
            if (!File.Exists(path) || JsonNode.Parse(File.ReadAllText(path)) is not JsonObject root)
            {
                return new SavedSubtitleIndex();
            }

            var subtitles = new List<SavedSubtitle>();
            if (root["subtitles"] is JsonArray entries)
            {
                foreach (var entry in entries)
                {
                    try
                    {
                        if (entry?.Deserialize<SavedSubtitle>(JsonOptions) is { } subtitle) subtitles.Add(subtitle);
                    }
                    catch (JsonException)
                    {
                        // Skip just this entry.
                    }
                }
            }

            var pending = new List<string>();
            if (root["pendingDeletes"] is JsonArray names)
            {
                foreach (var name in names)
                {
                    if (name is JsonValue value && value.TryGetValue<string>(out var text)) pending.Add(text);
                }
            }
            return new SavedSubtitleIndex(subtitles, pending);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        {
            return new SavedSubtitleIndex();
        }
    }

    /// <summary>Writes through a temporary file and a rename, like the other device-local stores.</summary>
    private void Save()
    {
        try
        {
            var folder = Path.GetDirectoryName(_indexPath);
            if (!string.IsNullOrEmpty(folder)) Directory.CreateDirectory(folder);
            var root = new JsonObject
            {
                ["version"] = 1,
                ["subtitles"] = JsonSerializer.SerializeToNode(_index.Entries, JsonOptions),
                ["pendingDeletes"] = JsonSerializer.SerializeToNode(_index.PendingDeletes, JsonOptions),
            };
            var temporary = _indexPath + ".tmp";
            File.WriteAllText(temporary, root.ToJsonString(JsonOptions));
            File.Move(temporary, _indexPath, overwrite: true);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            // Saved subtitles are a convenience; the next change writes again.
        }
    }

    private void RaiseChanged() => Changed?.Invoke(this, EventArgs.Empty);
}
