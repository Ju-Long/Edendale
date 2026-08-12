// Cloud-priority reconciliation for the watchlist. The connected account's
// complete movie and TV watchlists are authoritative, except for rows that
// still carry an unconfirmed local mutation — a pending add/remove wins until
// TMDB's own list reflects it. Ports WatchlistStore.reconcile(with:) from the
// Apple branch. Pure and deterministic so it is unit-tested without a store.

using Edendale.Windows.Models;

namespace Edendale.Windows.Core;

public static class WatchlistReconciler
{
    /// <summary>Rows with a pending mutation, oldest edit first — the push order.</summary>
    public static List<WatchlistRecord> PendingPushOrder(IEnumerable<WatchlistRecord> records) =>
        [.. records
            .Where(record => record.PendingAction != WatchlistPendingAction.None)
            .OrderBy(record => record.UpdatedAt)];

    /// <summary>
    /// Makes confirmed local rows match the account's complete watchlists.
    /// Returns the records to persist. Successful writes stay pending until this
    /// pull confirms them, so a briefly stale TMDB list never undoes a local tap.
    /// </summary>
    public static List<WatchlistRecord> Reconcile(
        IReadOnlyList<WatchlistRecord> local,
        IReadOnlyList<MediaItem> remote)
    {
        var byKey = new Dictionary<string, WatchlistRecord>(StringComparer.Ordinal);
        var order = new List<string>();
        foreach (var record in local)
        {
            if (byKey.TryAdd(record.StorageKey, record)) order.Add(record.StorageKey);
        }

        var remoteKeys = new HashSet<string>(StringComparer.Ordinal);
        foreach (var item in remote)
        {
            var key = WatchlistRecord.Key(item.Ref);
            remoteKeys.Add(key);

            if (!byKey.TryGetValue(key, out var record))
            {
                record = WatchlistRecord.Create(
                    item.Ref,
                    WatchlistMetadata.From(item),
                    inWatchlist: true,
                    pending: WatchlistPendingAction.None);
                byKey[key] = record;
                order.Add(key);
            }
            else
            {
                record.Apply(WatchlistMetadata.From(item));
            }

            switch (record.PendingAction)
            {
                case WatchlistPendingAction.Remove:
                    // A local removal has not appeared on TMDB yet — keep it off.
                    record.InWatchlist = false;
                    break;
                default: // Add or None: remote presence confirms membership.
                    record.InWatchlist = true;
                    record.PendingAction = WatchlistPendingAction.None;
                    break;
            }
        }

        var removed = new HashSet<string>(StringComparer.Ordinal);
        foreach (var record in local)
        {
            if (remoteKeys.Contains(record.StorageKey)) continue;
            switch (record.PendingAction)
            {
                case WatchlistPendingAction.Add:
                    // Keep the local addition until TMDB confirms it.
                    record.InWatchlist = true;
                    break;
                default:
                    // A pending removal is now confirmed; a confirmed row absent
                    // remotely was removed outside Edendale.
                    removed.Add(record.StorageKey);
                    break;
            }
        }

        return [.. order.Where(key => !removed.Contains(key)).Select(key => byKey[key])];
    }
}
