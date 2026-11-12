// Watch progress on Windows lives in %LOCALAPPDATA%\Edendale\watch-progress.json
// and, when sync is on, in this device's file in the sync space
// (FEATURES-27.1.md §5, SyncEngine). TMDB has no watch-progress API, so this
// never syncs through the TMDB account connector. Records use the
// Windows-owned WatchProgress model: movies by TMDB id ("movie:603"),
// episodes by their episode id ("episode:62085") for the UI and by show,
// season, and episode ("tv:1399:1:2") for the sync, which is also how
// episodes from other devices arrive, possibly before this device knows the
// episode's own id. Every local write takes a monotonic stamp from the
// SyncClock and this device's ID; a removal writes a tombstone.

using System.Text.Json;
using Edendale.Windows.Core.Sync;
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

    /// <summary>Every record by its sync key (movie, episode with show info, or local-only episode).</summary>
    private readonly Dictionary<string, WatchProgress> _records = [];

    /// <summary>Episodes this device knows the TMDB id of, by that id.</summary>
    private readonly Dictionary<int, WatchProgress> _episodes = [];

    private readonly object _gate = new();
    private readonly string _path;
    private readonly SyncClock _clock;
    private readonly Func<string?> _deviceId;

    /// <summary>Any change to the collection (local edit, merge, or import).</summary>
    public event EventHandler? Changed;

    /// <summary>Only writes made on this device: what the sync engine must publish.</summary>
    public event EventHandler? EditedLocally;

    public WatchProgressStore(string? path = null, SyncClock? clock = null, Func<string?>? deviceId = null)
    {
        _path = path ?? AppPaths.WatchProgressFile;
        _clock = clock ?? new SyncClock();
        _deviceId = deviceId ?? (() => null);
        try
        {
            if (File.Exists(_path))
            {
                var stored = JsonSerializer.Deserialize<List<WatchProgress>>(File.ReadAllText(_path), JsonOptions) ?? [];
                foreach (var record in stored) Put(record);
            }
        }
        catch
        {
            // A corrupt store starts fresh rather than blocking launch.
        }
        foreach (var record in _records.Values) _clock.Observe(record.LastWatchedEpochMillis);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /// <summary>Live records: no tombstones.</summary>
    public IReadOnlyList<WatchProgress> All
    {
        get { lock (_gate) return [.. _records.Values.Where(record => !record.IsCleared)]; }
    }

    /// <summary>Every record, tombstones included, for the sync engine.</summary>
    public IReadOnlyList<WatchProgress> AllIncludingCleared
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
                    .Where(record => !record.IsCleared && !record.IsCompleted && record.Position > 0.005)
                    .OrderByDescending(record => record.LastWatchedEpochMillis)];
            }
        }
    }

    /// <summary>The live record for a movie or an episode by TMDB id; null when absent or cleared.</summary>
    public WatchProgress? Get(int tmdbId, string mediaType)
    {
        lock (_gate)
        {
            var record = mediaType == "episode"
                ? _episodes.GetValueOrDefault(tmdbId) ?? _records.GetValueOrDefault($"episode:{tmdbId}")
                : _records.GetValueOrDefault($"movie:{tmdbId}");
            return record is { IsCleared: false } ? record : null;
        }
    }

    /// <summary>The live record for an episode by show, season, and episode; null when absent or cleared.</summary>
    public WatchProgress? GetEpisode(int showTmdbId, int season, int episode)
    {
        lock (_gate)
        {
            var record = _records.GetValueOrDefault(SyncProgressRecord.EpisodeKey(showTmdbId, season, episode));
            return record is { IsCleared: false } ? record : null;
        }
    }

    public bool IsWatched(int tmdbId, string mediaType) =>
        Get(tmdbId, mediaType)?.IsCompleted == true;

    public bool IsEpisodeWatched(int showTmdbId, int season, int episode) =>
        GetEpisode(showTmdbId, season, episode)?.IsCompleted == true;

    // ------------------------------------------------------------------
    // Local writes (stamped, published by the sync engine)
    // ------------------------------------------------------------------

    public void Update(
        int tmdbId,
        string mediaType,
        double position,
        double watchedSeconds,
        int? showTmdbId = null,
        int? seasonNumber = null,
        int? episodeNumber = null,
        double durationSeconds = 0)
    {
        var normalized = Math.Clamp(position, 0, 1);
        var record = new WatchProgress
        {
            TmdbId = tmdbId,
            MediaType = mediaType,
            Position = normalized,
            NormalizedPosition = normalized,
            WatchedSeconds = watchedSeconds,
            DurationSeconds = durationSeconds > 0 ? durationSeconds : watchedSeconds > 0 && normalized > 0 ? watchedSeconds / normalized : 0,
            IsCompleted = normalized >= CompletionThreshold,
            ShowTmdbId = showTmdbId,
            SeasonNumber = seasonNumber,
            EpisodeNumber = episodeNumber,
        };
        lock (_gate)
        {
            InheritShowInfo(record);
            Stamp(record);
            Put(record);
        }
        Save();
        RaiseChanged(local: true);
    }

    public void MarkCompleted(int tmdbId, string mediaType, int? showTmdbId = null, int? seasonNumber = null, int? episodeNumber = null)
    {
        lock (_gate)
        {
            var existing = Find(tmdbId, mediaType, showTmdbId, seasonNumber, episodeNumber);
            var (show, season, episode) = Context(mediaType, existing, showTmdbId, seasonNumber, episodeNumber);
            var record = new WatchProgress
            {
                TmdbId = tmdbId,
                MediaType = mediaType,
                Position = 1,
                NormalizedPosition = 1,
                WatchedSeconds = existing?.DurationSeconds > 0 ? existing.DurationSeconds : existing?.WatchedSeconds ?? 0,
                DurationSeconds = existing?.DurationSeconds ?? 0,
                IsCompleted = true,
                ShowTmdbId = show,
                SeasonNumber = season,
                EpisodeNumber = episode,
            };
            InheritShowInfo(record);
            Stamp(record);
            Put(record);
        }
        Save();
        RaiseChanged(local: true);
    }

    /// <summary>
    /// Clears a position on purpose. The record becomes a tombstone with a
    /// new stamp, so an older copy on another device cannot bring it back.
    /// </summary>
    public void Remove(int tmdbId, string mediaType, int? showTmdbId = null, int? seasonNumber = null, int? episodeNumber = null)
    {
        lock (_gate)
        {
            var existing = Find(tmdbId, mediaType, showTmdbId, seasonNumber, episodeNumber);
            if (existing is null && mediaType == "episode" && (showTmdbId is null || seasonNumber is null || episodeNumber is null))
            {
                return; // Nothing to clear, and nothing another device could know about.
            }
            var (show, season, episode) = Context(mediaType, existing, showTmdbId, seasonNumber, episodeNumber);
            var tombstone = new WatchProgress
            {
                TmdbId = tmdbId,
                MediaType = mediaType,
                IsCleared = true,
                ShowTmdbId = show,
                SeasonNumber = season,
                EpisodeNumber = episode,
            };
            InheritShowInfo(tombstone);
            Stamp(tombstone);
            Put(tombstone);
        }
        Save();
        RaiseChanged(local: true);
    }

    // ------------------------------------------------------------------
    // Sync and import
    // ------------------------------------------------------------------

    /// <summary>Replaces the whole collection with a merged one (import or sync). Not a local edit.</summary>
    public void ReplaceAll(IEnumerable<WatchProgress> records)
    {
        lock (_gate)
        {
            _records.Clear();
            _episodes.Clear();
            foreach (var record in records) Put(record);
            foreach (var record in _records.Values) _clock.Observe(record.LastWatchedEpochMillis);
        }
        Save();
        RaiseChanged(local: false);
    }

    /// <summary>Whether an episode received from another device still waits for this PC's id.</summary>
    public bool HasUnresolvedEpisodes
    {
        get
        {
            lock (_gate) return _records.Values.Any(record => record is { MediaType: "episode", TmdbId: 0, IsSyncable: true });
        }
    }

    /// <summary>
    /// Fills in the TMDB ids of episodes received from other devices from
    /// the library's <paramref name="index"/> by sync key
    /// (<c>tv:&lt;show&gt;:&lt;season&gt;:&lt;episode&gt;</c>, SyncEpisodeIndex). Returns
    /// how many were matched.
    /// </summary>
    public int ResolveEpisodeIds(IReadOnlyDictionary<string, int> index)
    {
        var resolved = 0;
        lock (_gate)
        {
            foreach (var record in _records.Values)
            {
                if (record is not { MediaType: "episode", TmdbId: 0, IsSyncable: true }) continue;
                if (!index.TryGetValue(record.SyncKey, out var id) || id <= 0) continue;
                record.TmdbId = id;
                _episodes[id] = record;
                resolved++;
            }
        }
        if (resolved > 0)
        {
            Save();
            RaiseChanged(local: false);
        }
        return resolved;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /// <summary>
    /// The show, season, and episode a mark or a removal writes. For an
    /// episode, what its row already has wins and the caller's fills only
    /// what it lacks (WatchProgressStore.markCompleted on apple-27.1:
    /// "context a row already has is kept"), so a mark never re-keys a row
    /// and leaves its old sync key behind without a tombstone, which another
    /// device's copy would bring back.
    /// </summary>
    private static (int? Show, int? Season, int? Episode) Context(
        string mediaType, WatchProgress? existing, int? showTmdbId, int? seasonNumber, int? episodeNumber) =>
        mediaType == "episode"
            ? (existing?.ShowTmdbId is > 0 ? existing.ShowTmdbId : showTmdbId ?? existing?.ShowTmdbId,
                existing?.SeasonNumber ?? seasonNumber,
                existing?.EpisodeNumber ?? episodeNumber)
            : (showTmdbId ?? existing?.ShowTmdbId, seasonNumber ?? existing?.SeasonNumber, episodeNumber ?? existing?.EpisodeNumber);

    private WatchProgress? Find(int tmdbId, string mediaType, int? showTmdbId, int? seasonNumber, int? episodeNumber)
    {
        if (mediaType == "movie") return _records.GetValueOrDefault($"movie:{tmdbId}");
        if (tmdbId > 0 && _episodes.TryGetValue(tmdbId, out var byId)) return byId;
        if (showTmdbId is int show && seasonNumber is int season && episodeNumber is int episode
            && _records.TryGetValue(SyncProgressRecord.EpisodeKey(show, season, episode), out var byPosition))
        {
            return byPosition;
        }
        return _records.GetValueOrDefault($"episode:{tmdbId}");
    }

    /// <summary>An episode write without show info takes it from the record it replaces, so the sync key stays stable.</summary>
    private void InheritShowInfo(WatchProgress record)
    {
        if (record.MediaType != "episode" || record.IsSyncable || record.TmdbId <= 0) return;
        if (_episodes.TryGetValue(record.TmdbId, out var known) && known.IsSyncable)
        {
            record.ShowTmdbId = known.ShowTmdbId;
            record.SeasonNumber = known.SeasonNumber;
            record.EpisodeNumber = known.EpisodeNumber;
        }
    }

    private void Stamp(WatchProgress record)
    {
        record.LastWatchedEpochMillis = _clock.Stamp();
        record.Device = _deviceId();
    }

    /// <summary>Stores a record under its sync key and keeps the episode index in step.</summary>
    private void Put(WatchProgress record)
    {
        var key = record.SyncKey;
        if (_records.TryGetValue(key, out var previous) && previous.MediaType == "episode" && previous.TmdbId > 0 && previous.TmdbId != record.TmdbId)
        {
            _episodes.Remove(previous.TmdbId);
        }
        if (record.MediaType == "episode" && record.TmdbId > 0)
        {
            // A write that now knows the show retires the local-only copy.
            if (record.IsSyncable && _records.Remove($"episode:{record.TmdbId}", out var legacy) && legacy != record)
            {
                _episodes.Remove(record.TmdbId);
            }
            // An episode received by show/season/episode may already exist
            // under its id from a local play: the sync key wins.
            if (_episodes.TryGetValue(record.TmdbId, out var sameEpisode) && sameEpisode.SyncKey != key)
            {
                _records.Remove(sameEpisode.SyncKey);
            }
            _episodes[record.TmdbId] = record;
        }
        _records[key] = record;
    }

    private void RaiseChanged(bool local)
    {
        Changed?.Invoke(this, EventArgs.Empty);
        if (local) EditedLocally?.Invoke(this, EventArgs.Empty);
    }

    private void Save()
    {
        try
        {
            List<WatchProgress> snapshot;
            lock (_gate) snapshot = [.. _records.Values];
            var temporary = _path + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(snapshot, JsonOptions));
            File.Move(temporary, _path, overwrite: true);
        }
        catch
        {
            // Progress is convenience data; a failed write must never crash playback.
        }
    }
}
