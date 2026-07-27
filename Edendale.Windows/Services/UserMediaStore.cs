// Favourite / watchlist / rating state, keyed like watch progress by TMDB id
// + media type ("movie:603" / "tv:1399") using the Windows-owned
// UserMediaRecord model. Local edits stamp per-field timestamps (for the
// OneDrive replica merge) and dirty flags (for the TMDB account push).
// ReplaceAll applies merged or synchronized collections without re-marking
// anything dirty.

using System.Text.Json;
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

    /// <summary>Any change to the collection (local edit, merge, or sync).</summary>
    public event EventHandler? Changed;

    /// <summary>Only user edits on this device — drives the debounced TMDB push.</summary>
    public event EventHandler? EditedLocally;

    public UserMediaStore()
    {
        try
        {
            if (File.Exists(AppPaths.UserMediaFile))
            {
                var stored = JsonSerializer.Deserialize<List<UserMediaRecord>>(
                    File.ReadAllText(AppPaths.UserMediaFile), JsonOptions) ?? [];
                foreach (var record in stored) _records[record.StorageKey] = record;
            }
        }
        catch
        {
            // A corrupt store starts fresh rather than blocking launch.
        }
    }

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

    public IReadOnlyList<UserMediaRecord> WatchlistItems
    {
        get
        {
            lock (_gate)
            {
                return [.. _records.Values
                    .Where(record => record.Watchlist)
                    .OrderByDescending(record => record.WatchlistUpdatedAt)];
            }
        }
    }

    public UserMediaRecord? Get(int tmdbId, string mediaType)
    {
        lock (_gate) return _records.GetValueOrDefault($"{mediaType}:{tmdbId}");
    }

    public bool IsFavourite(int tmdbId, string mediaType) =>
        Get(tmdbId, mediaType)?.Favourite == true;

    public bool IsWatchlisted(int tmdbId, string mediaType) =>
        Get(tmdbId, mediaType)?.Watchlist == true;

    /// <summary>The stored TMDB rating (0.5–10), or null when unrated.</summary>
    public double? RatingFor(int tmdbId, string mediaType) =>
        Get(tmdbId, mediaType)?.Rating;

    public void SetFavourite(int tmdbId, string mediaType, bool value, string? title = null, string? posterPath = null) =>
        Edit(tmdbId, mediaType, title, posterPath, record =>
        {
            record.Favourite = value;
            record.FavouriteUpdatedAt = NowMillis();
            record.FavouriteDirty = true;
        });

    public void SetWatchlist(int tmdbId, string mediaType, bool value, string? title = null, string? posterPath = null) =>
        Edit(tmdbId, mediaType, title, posterPath, record =>
        {
            record.Watchlist = value;
            record.WatchlistUpdatedAt = NowMillis();
            record.WatchlistDirty = true;
        });

    /// <summary>value is on TMDB's 0.5–10 scale; null clears the rating.</summary>
    public void SetRating(int tmdbId, string mediaType, double? value, string? title = null, string? posterPath = null) =>
        Edit(tmdbId, mediaType, title, posterPath, record =>
        {
            record.Rating = value is null ? null : Math.Clamp(Math.Round(value.Value * 2) / 2, 0.5, 10);
            record.RatingUpdatedAt = NowMillis();
            record.RatingDirty = true;
        });

    /// <summary>Applies a merged or synchronized collection.</summary>
    public void ReplaceAll(IEnumerable<UserMediaRecord> records)
    {
        lock (_gate)
        {
            _records.Clear();
            foreach (var record in records.Where(r => r.HasState))
            {
                _records[record.StorageKey] = record;
            }
        }
        Save();
        Changed?.Invoke(this, EventArgs.Empty);
    }

    private void Edit(int tmdbId, string mediaType, string? title, string? posterPath, Action<UserMediaRecord> mutate)
    {
        lock (_gate)
        {
            var key = $"{mediaType}:{tmdbId}";
            var record = _records.GetValueOrDefault(key)
                ?? new UserMediaRecord { TmdbId = tmdbId, MediaType = mediaType };
            record.Title ??= title;
            record.PosterPath ??= posterPath;
            mutate(record);
            if (record.HasState) _records[key] = record;
            else _records.Remove(key);
        }
        Save();
        Changed?.Invoke(this, EventArgs.Empty);
        EditedLocally?.Invoke(this, EventArgs.Empty);
    }

    private static long NowMillis() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    private void Save()
    {
        try
        {
            List<UserMediaRecord> snapshot;
            lock (_gate) snapshot = [.. _records.Values];
            var temporary = AppPaths.UserMediaFile + ".tmp";
            File.WriteAllText(temporary, JsonSerializer.Serialize(snapshot, JsonOptions));
            File.Move(temporary, AppPaths.UserMediaFile, overwrite: true);
        }
        catch
        {
            // User-media state is convenience data; a failed write must never crash the UI.
        }
    }
}
