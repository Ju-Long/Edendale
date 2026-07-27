// Watch progress on Windows lives in %LOCALAPPDATA% and, when OneDrive is
// set up, in a cloud replica merged by CloudSyncService (newest write wins
// per title). TMDB has no watch-progress API, so unlike favourites/watchlist/
// ratings this never syncs through the TMDB account connector; CloudKit sync
// remains Apple-only. Records use the Windows-owned WatchProgress model keyed
// by TMDB id + media type ("movie:603" / "episode:62085"). The home catalog
// uses these records for the Continue Watching hero.

using System.Text.Json;
using Edendale.Windows.Models;

namespace Edendale.Windows.Services;

public sealed class WatchProgressStore
{
    /// <summary>Past this fraction a title counts as finished (Apple parity).</summary>
    public const double CompletionThreshold = 0.95;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true,
    };

    private readonly Dictionary<string, WatchProgress> _records = [];
    private readonly object _gate = new();

    public event EventHandler? Changed;

    public WatchProgressStore()
    {
        try
        {
            if (File.Exists(AppPaths.WatchProgressFile))
            {
                var stored = JsonSerializer.Deserialize<List<WatchProgress>>(
                    File.ReadAllText(AppPaths.WatchProgressFile), JsonOptions) ?? [];
                foreach (var record in stored) _records[record.StorageKey] = record;
            }
        }
        catch
        {
            // A corrupt store starts fresh rather than blocking launch.
        }
    }

    public IReadOnlyList<WatchProgress> All
    {
        get { lock (_gate) return [.. _records.Values]; }
    }

    /// <summary>Half-watched records, most recently watched first.</summary>
    public IReadOnlyList<WatchProgress> InProgress
    {
        get
        {
            lock (_gate)
            {
                return [.. _records.Values
                    .Where(record => !record.IsCompleted && record.Position > 0.005)
                    .OrderByDescending(record => record.LastWatchedEpochMillis)];
            }
        }
    }

    public WatchProgress? Get(int tmdbId, string mediaType)
    {
        lock (_gate) return _records.GetValueOrDefault($"{mediaType}:{tmdbId}");
    }

    public bool IsWatched(int tmdbId, string mediaType) =>
        Get(tmdbId, mediaType)?.IsCompleted == true;

    public void Update(
        int tmdbId,
        string mediaType,
        double position,
        double watchedSeconds,
        int? showTmdbId = null,
        int? seasonNumber = null,
        int? episodeNumber = null)
    {
        var normalized = Math.Clamp(position, 0, 1);
        var record = new WatchProgress
        {
            TmdbId = tmdbId,
            MediaType = mediaType,
            Position = normalized,
            NormalizedPosition = normalized,
            WatchedSeconds = watchedSeconds,
            LastWatchedEpochMillis = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
            IsCompleted = normalized >= CompletionThreshold,
            ShowTmdbId = showTmdbId,
            SeasonNumber = seasonNumber,
            EpisodeNumber = episodeNumber,
        };
        lock (_gate) _records[record.StorageKey] = record;
        Save();
    }

    public void MarkCompleted(int tmdbId, string mediaType)
    {
        var existing = Get(tmdbId, mediaType);
        var record = existing ?? new WatchProgress { TmdbId = tmdbId, MediaType = mediaType };
        record.Position = 1;
        record.NormalizedPosition = 1;
        record.IsCompleted = true;
        record.LastWatchedEpochMillis = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        lock (_gate) _records[record.StorageKey] = record;
        Save();
    }

    public void Remove(int tmdbId, string mediaType)
    {
        lock (_gate) _records.Remove($"{mediaType}:{tmdbId}");
        Save();
    }

    /// <summary>Applies a merged collection from the cloud-replica merge.</summary>
    public void ReplaceAll(IEnumerable<WatchProgress> records)
    {
        lock (_gate)
        {
            _records.Clear();
            foreach (var record in records) _records[record.StorageKey] = record;
        }
        Save();
    }

    private void Save()
    {
        try
        {
            List<WatchProgress> snapshot;
            lock (_gate) snapshot = [.. _records.Values];
            var temporary = AppPaths.WatchProgressFile + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(snapshot, JsonOptions));
            File.Move(temporary, AppPaths.WatchProgressFile, overwrite: true);
        }
        catch
        {
            // Progress is convenience data; a failed write must never crash playback.
        }
        Changed?.Invoke(this, EventArgs.Empty);
    }
}
