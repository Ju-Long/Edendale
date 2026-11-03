// Between Windows' own watch models and the sync records of
// SYNC-PROTOCOL.md §6. Windows keys episodes by the episode's TMDB id; the
// format keys them by show, season, and episode, so an episode received from
// another device is matched to a library episode when one is known and
// otherwise kept with TmdbId 0 until it is.

using Edendale.Windows.Models;

namespace Edendale.Windows.Core.Sync;

public static class SyncRecordMapping
{
    /// <summary>
    /// The sync record for a local progress record, or null for an episode
    /// whose show, season, and episode are unknown (local-only, never synced).
    /// Records from before sync carry no device: the writing device signs them.
    /// </summary>
    public static SyncProgressRecord? ToSync(WatchProgress record, string thisDevice)
    {
        if (!record.IsSyncable) return null;
        var device = string.IsNullOrEmpty(record.Device) ? thisDevice : record.Device;
        var isMovie = record.MediaType == "movie";
        var common = new
        {
            Type = isMovie ? "movie" : "tv",
            TmdbId = isMovie ? record.TmdbId : record.ShowTmdbId!.Value,
            Season = isMovie ? (int?)null : record.SeasonNumber,
            Episode = isMovie ? (int?)null : record.EpisodeNumber,
        };
        if (common.TmdbId < 1 || (!isMovie && (common.Season < 0 || common.Episode < 0))) return null;
        if (record.IsCleared)
        {
            return new SyncProgressRecord
            {
                Type = common.Type, TmdbId = common.TmdbId, Season = common.Season, Episode = common.Episode,
                Cleared = true, Device = device, UpdateTime = record.LastWatchedEpochMillis,
            };
        }

        // Seconds for the file. A record marked watched by hand knows no
        // duration: position == duration == 1 reads as fully watched everywhere.
        var fraction = Math.Clamp(record.Position, 0, 1);
        double duration;
        double position;
        if (record.DurationSeconds > 0)
        {
            duration = record.DurationSeconds;
            position = record.WatchedSeconds > 0 ? Math.Min(record.WatchedSeconds, duration) : fraction * duration;
        }
        else if (record.WatchedSeconds > 0 && fraction > 0)
        {
            duration = record.WatchedSeconds / fraction;
            position = record.WatchedSeconds;
        }
        else
        {
            duration = 1;
            position = record.IsCompleted ? 1 : fraction;
        }
        if (!double.IsFinite(duration) || duration <= 0) duration = 1;
        if (!double.IsFinite(position) || position < 0) position = 0;
        return new SyncProgressRecord
        {
            Type = common.Type, TmdbId = common.TmdbId, Season = common.Season, Episode = common.Episode,
            Position = position, Duration = duration, Completed = record.IsCompleted,
            Device = device, UpdateTime = record.LastWatchedEpochMillis,
        };
    }

    /// <summary>
    /// The local record for a sync record. <paramref name="episodeTmdbId"/> is
    /// the episode's TMDB id when this device knows it, or null (stored as 0).
    /// </summary>
    public static WatchProgress FromSync(SyncProgressRecord record, int? episodeTmdbId)
    {
        var isMovie = record.Type == "movie";
        var duration = record.Duration ?? 0;
        var position = record.Position ?? 0;
        var fraction = record.Cleared ? 0 : duration > 0 ? Math.Clamp(position / duration, 0, 1) : 0;
        return new WatchProgress
        {
            TmdbId = isMovie ? record.TmdbId : episodeTmdbId ?? 0,
            MediaType = isMovie ? "movie" : "episode",
            Position = fraction,
            NormalizedPosition = fraction,
            WatchedSeconds = record.Cleared ? 0 : position,
            DurationSeconds = record.Cleared ? 0 : duration,
            LastWatchedEpochMillis = record.UpdateTime,
            IsCompleted = record.Completed ?? false,
            IsCleared = record.Cleared,
            ShowTmdbId = isMovie ? null : record.TmdbId,
            SeasonNumber = record.Season,
            EpisodeNumber = record.Episode,
            Device = record.Device,
        };
    }

    public static SyncUserMediaRecord ToSync(UserMediaRecord record, string thisDevice) => new()
    {
        Type = record.MediaType == "tv" ? "tv" : "movie",
        TmdbId = record.TmdbId,
        Favourite = record.Favourite,
        Watchlist = record.Watchlist,
        Rating = record.Rating is double rating && SyncUserMediaRecord.IsValidRating(rating) ? rating : null,
        Review = record.Review is { Length: > 0 } review && SyncUserMediaRecord.IsValidReview(review) ? review : null,
        Device = string.IsNullOrEmpty(record.Device) ? thisDevice : record.Device,
        UpdateTime = record.EffectiveUpdateTime,
    };

    /// <summary>
    /// The local record for a sync record. Title and poster are presentation
    /// data this device may already hold; <paramref name="existing"/> keeps
    /// them. A favourite or rating the merge changed is marked for the TMDB
    /// push, like a local edit: otherwise this device's next TMDB pull would
    /// put the old value back and stamp it newer than the change (§5.5).
    /// </summary>
    public static UserMediaRecord FromSync(SyncUserMediaRecord record, UserMediaRecord? existing) => new()
    {
        TmdbId = record.TmdbId,
        MediaType = record.Type,
        Title = existing?.Title,
        PosterPath = existing?.PosterPath,
        PosterUrl = existing?.PosterUrl,
        Favourite = record.Favourite,
        FavouriteUpdatedAt = record.UpdateTime,
        FavouriteDirty = (existing?.Favourite ?? false) != record.Favourite || existing?.FavouriteDirty == true,
        Rating = record.Rating,
        RatingUpdatedAt = record.UpdateTime,
        RatingDirty = existing?.Rating != record.Rating || existing?.RatingDirty == true,
        Watchlist = record.Watchlist,
        Review = record.Review,
        UpdateTime = record.UpdateTime,
        Device = record.Device,
    };

    /// <summary>The sync record a local record is compared as (§7.1), with this device signing unsigned ones.</summary>
    public static SyncRecord? AsSyncRecord(WatchProgress record, string thisDevice) => ToSync(record, thisDevice);
}
