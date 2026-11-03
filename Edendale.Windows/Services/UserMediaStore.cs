// Favourite / rating state (and the watchlist mirror for sync), keyed like
// watch progress by TMDB id + media type ("movie:603" / "tv:1399") using the
// Windows-owned UserMediaRecord model. Every local edit takes one monotonic
// sync stamp from the SyncClock and this device's ID (FEATURES-27.1.md §5.3),
// plus the per-field times and dirty flags the TMDB account push still uses.
// Clearing a value keeps the record as a tombstone. ApplyMerged takes the sync
// engine's merged collection without re-stamping; ApplyTmdbSync takes the TMDB
// round's records and stamps only what TMDB changed (§5.5).

using System.Text.Json;
using Edendale.Windows.Core.Sync;
using Edendale.Windows.Models;

namespace Edendale.Windows.Services;

public sealed class UserMediaStore
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true,
    };

    private readonly Dictionary<string, UserMediaRecord> _records = [];
    private readonly object _gate = new();
    private readonly string _path;
    private readonly SyncClock _clock;
    private readonly Func<string?> _deviceId;

    /// <summary>Any change to the collection (local edit, merge, or sync).</summary>
    public event EventHandler? Changed;

    /// <summary>Only user edits on this device — drives the debounced TMDB push.</summary>
    public event EventHandler? EditedLocally;

    public UserMediaStore(string? path = null, SyncClock? clock = null, Func<string?>? deviceId = null)
    {
        _path = path ?? AppPaths.UserMediaFile;
        _clock = clock ?? new SyncClock();
        _deviceId = deviceId ?? (() => null);
        try
        {
            if (File.Exists(_path))
            {
                var stored = JsonSerializer.Deserialize<List<UserMediaRecord>>(File.ReadAllText(_path), JsonOptions) ?? [];
                foreach (var record in stored) _records[record.StorageKey] = record;
            }
        }
        catch
        {
            // A corrupt store starts fresh rather than blocking launch.
        }
        foreach (var record in _records.Values) _clock.Observe(record.UpdateTime);
    }

    /// <summary>Every record, tombstones included.</summary>
    public IReadOnlyList<UserMediaRecord> All
    {
        get { lock (_gate) return [.. _records.Values]; }
    }

    public IReadOnlyList<UserMediaRecord> Favourites
    {
        get
        {
            lock (_gate)
            {
                return [.. _records.Values
                    .Where(record => record.Favourite)
                    .OrderByDescending(record => record.FavouriteUpdatedAt)];
            }
        }
    }

    public UserMediaRecord? Get(int tmdbId, string mediaType)
    {
        lock (_gate) return _records.GetValueOrDefault($"{mediaType}:{tmdbId}");
    }

    public bool IsFavourite(int tmdbId, string mediaType) =>
        Get(tmdbId, mediaType)?.Favourite == true;

    /// <summary>The stored TMDB rating (0.5–10), or null when unrated.</summary>
    public double? RatingFor(int tmdbId, string mediaType) =>
        Get(tmdbId, mediaType)?.Rating;

    public void SetFavourite(int tmdbId, string mediaType, bool value, string? title = null, string? posterPath = null) =>
        Edit(tmdbId, mediaType, title, posterPath, (record, now) =>
        {
            record.Favourite = value;
            record.FavouriteUpdatedAt = now;
            record.FavouriteDirty = true;
        });

    /// <summary>value is on TMDB's 0.5–10 scale; null clears the rating.</summary>
    public void SetRating(int tmdbId, string mediaType, double? value, string? title = null, string? posterPath = null) =>
        Edit(tmdbId, mediaType, title, posterPath, (record, now) =>
        {
            record.Rating = value is null ? null : Math.Clamp(Math.Round(value.Value * 2) / 2, 0.5, 10);
            record.RatingUpdatedAt = now;
            record.RatingDirty = true;
        });

    /// <summary>
    /// Keeps the watchlist flag of the sync record in step with the watchlist
    /// store. A change takes a fresh stamp (or <paramref name="stamp"/> when
    /// the watchlist's own time is adopted at first run); the TMDB push is not
    /// involved, the watchlist store has its own. Returns whether anything changed.
    /// </summary>
    public bool SetWatchlistMirror(int tmdbId, string mediaType, bool inWatchlist, long? stamp = null, string? title = null, string? posterPath = null)
    {
        lock (_gate)
        {
            var key = $"{mediaType}:{tmdbId}";
            var record = _records.GetValueOrDefault(key);
            if (record is null && !inWatchlist) return false;
            if (record is not null && record.Watchlist == inWatchlist) return false;
            record ??= new UserMediaRecord { TmdbId = tmdbId, MediaType = mediaType };
            record.Title ??= title;
            record.PosterPath ??= posterPath;
            record.Watchlist = inWatchlist;
            record.UpdateTime = stamp is long given && given > 0 ? given : _clock.Stamp();
            if (stamp is long observed) _clock.Observe(observed);
            record.Device = _deviceId();
            _records[key] = record;
        }
        Save();
        Changed?.Invoke(this, EventArgs.Empty);
        return true;
    }

    /// <summary>Replaces the whole collection with the sync engine's merged one; stamps are kept verbatim.</summary>
    public void ApplyMerged(IEnumerable<UserMediaRecord> records)
    {
        lock (_gate)
        {
            _records.Clear();
            foreach (var record in records)
            {
                if (!record.HasState && record.UpdateTime == 0) continue;
                _records[record.StorageKey] = record;
            }
            foreach (var record in _records.Values) _clock.Observe(record.UpdateTime);
        }
        Save();
        Changed?.Invoke(this, EventArgs.Empty);
    }

    /// <summary>The import of the OneDrive replica (§8.4): the merged collection, as given.</summary>
    public void ReplaceAll(IEnumerable<UserMediaRecord> records) => ApplyMerged(records);

    /// <summary>
    /// Applies a TMDB sync round. TMDB keeps no per-field times, so a value
    /// pulled from TMDB that differs from local state becomes a new local
    /// write, stamped now (FEATURES-27.1.md §5.5); unchanged records keep
    /// their stamp and device.
    /// </summary>
    public void ApplyTmdbSync(IEnumerable<UserMediaRecord> records)
    {
        lock (_gate)
        {
            var previous = new Dictionary<string, UserMediaRecord>(_records, StringComparer.Ordinal);
            _records.Clear();
            foreach (var record in records)
            {
                var key = record.StorageKey;
                previous.TryGetValue(key, out var before);
                var changed = before is null
                    ? record.HasState
                    : before.Favourite != record.Favourite || before.Rating != record.Rating;
                if (changed)
                {
                    record.UpdateTime = _clock.Stamp();
                    record.Device = _deviceId();
                }
                else if (before is not null)
                {
                    record.UpdateTime = before.UpdateTime;
                    record.Device = before.Device;
                    record.Watchlist = before.Watchlist;
                    record.Review = before.Review;
                }
                if (!record.HasState && record.UpdateTime == 0) continue;
                _records[key] = record;
            }
            // Tombstones TMDB never saw stay.
            foreach (var (key, before) in previous)
            {
                if (!_records.ContainsKey(key) && before.IsTombstone) _records[key] = before;
            }
        }
        Save();
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private void Edit(int tmdbId, string mediaType, string? title, string? posterPath, Action<UserMediaRecord, long> mutate)
    {
        lock (_gate)
        {
            var key = $"{mediaType}:{tmdbId}";
            var record = _records.GetValueOrDefault(key)
                ?? new UserMediaRecord { TmdbId = tmdbId, MediaType = mediaType };
            record.Title ??= title;
            record.PosterPath ??= posterPath;
            var stamp = _clock.Stamp();
            mutate(record, stamp);
            record.UpdateTime = stamp;
            record.Device = _deviceId();
            // A cleared record stays as a tombstone (§6.2).
            _records[key] = record;
        }
        Save();
        Changed?.Invoke(this, EventArgs.Empty);
        EditedLocally?.Invoke(this, EventArgs.Empty);
    }

    private void Save()
    {
        try
        {
            List<UserMediaRecord> snapshot;
            lock (_gate) snapshot = [.. _records.Values];
            var temporary = _path + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(snapshot, JsonOptions));
            File.Move(temporary, _path, overwrite: true);
        }
        catch
        {
            // User-media state is convenience data; a failed write must never crash the UI.
        }
    }
}
