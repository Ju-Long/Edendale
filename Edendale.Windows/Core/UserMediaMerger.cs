using Edendale.Windows.Models;

namespace Edendale.Windows.Core;

internal static class UserMediaMerger
{
    public static List<WatchProgress> MergeWatchProgress(
        IReadOnlyList<WatchProgress> first,
        IReadOnlyList<WatchProgress> second)
    {
        var byKey = new Dictionary<string, WatchProgress>(StringComparer.Ordinal);
        var order = new List<string>();
        foreach (var record in first.Concat(second))
        {
            if (!byKey.TryGetValue(record.StorageKey, out var current))
            {
                order.Add(record.StorageKey);
                byKey[record.StorageKey] = record;
            }
            else if (record.LastWatchedEpochMillis > current.LastWatchedEpochMillis)
            {
                byKey[record.StorageKey] = record;
            }
        }
        return order.Select(key => byKey[key]).ToList();
    }

    public static List<UserMediaRecord> MergeUserMedia(
        IReadOnlyList<UserMediaRecord> first,
        IReadOnlyList<UserMediaRecord> second)
    {
        var byKey = new Dictionary<string, UserMediaRecord>(StringComparer.Ordinal);
        var order = new List<string>();
        foreach (var record in first)
        {
            if (!byKey.ContainsKey(record.StorageKey)) order.Add(record.StorageKey);
            byKey[record.StorageKey] = record;
        }
        foreach (var record in second)
        {
            if (byKey.TryGetValue(record.StorageKey, out var current))
            {
                byKey[record.StorageKey] = MergeRecord(current, record);
            }
            else
            {
                order.Add(record.StorageKey);
                byKey[record.StorageKey] = record;
            }
        }
        return order.Select(key => byKey[key]).Where(record => record.HasState).ToList();
    }

    private static UserMediaRecord MergeRecord(UserMediaRecord own, UserMediaRecord other)
    {
        var favourite = Winner(
            own, other,
            own.FavouriteUpdatedAt, own.FavouriteDirty,
            other.FavouriteUpdatedAt, other.FavouriteDirty);
        var watchlist = Winner(
            own, other,
            own.WatchlistUpdatedAt, own.WatchlistDirty,
            other.WatchlistUpdatedAt, other.WatchlistDirty);
        var rating = Winner(
            own, other,
            own.RatingUpdatedAt, own.RatingDirty,
            other.RatingUpdatedAt, other.RatingDirty);

        return new UserMediaRecord
        {
            TmdbId = own.TmdbId,
            MediaType = own.MediaType,
            Title = own.Title ?? other.Title,
            PosterPath = own.PosterPath ?? other.PosterPath,
            PosterUrl = own.PosterUrl ?? other.PosterUrl,
            Favourite = favourite.Favourite,
            FavouriteUpdatedAt = favourite.FavouriteUpdatedAt,
            FavouriteDirty = favourite.FavouriteDirty,
            Watchlist = watchlist.Watchlist,
            WatchlistUpdatedAt = watchlist.WatchlistUpdatedAt,
            WatchlistDirty = watchlist.WatchlistDirty,
            Rating = rating.Rating,
            RatingUpdatedAt = rating.RatingUpdatedAt,
            RatingDirty = rating.RatingDirty,
        };
    }

    private static UserMediaRecord Winner(
        UserMediaRecord own,
        UserMediaRecord other,
        long ownUpdatedAt,
        bool ownDirty,
        long otherUpdatedAt,
        bool otherDirty)
    {
        if (ownUpdatedAt > otherUpdatedAt) return own;
        if (ownUpdatedAt < otherUpdatedAt) return other;
        return otherDirty && !ownDirty ? other : own;
    }
}
