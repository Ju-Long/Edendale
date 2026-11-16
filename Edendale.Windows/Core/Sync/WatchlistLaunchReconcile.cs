// The watchlist flag at launch: brings the user-media records' `watchlist`
// flag (what the sync record carries, SYNC-PROTOCOL.md §6.2) and the
// watchlist store into step before the first sync writes the flag out.
// Ports UserMediaStore.reconcileWatchlist on origin/apple-27.1 (K.7):
//
// - A row never stamped takes the watchlist's flag without a fresh stamp,
//   so the first sync can't tell other devices these titles left their
//   watchlists. A listed title with no row gets one.
// - A row another device stamped wins: it changed there while nothing here
//   was listening, so the watchlist follows it.
// - No stamp is ever lowered, and running it twice changes nothing.
//
// Windows adds two cases Apple doesn't have. The first run after 27.1 (the
// replica import has signed this PC's old rows with its ID) treats this PC's
// own rows like rows never stamped. After that, an own row whose flag
// differs means the watchlist changed after this PC's last mirror (a crash,
// say), so it takes a fresh stamp and goes out. Pure, so the tests run it
// without a store.

using Edendale.Windows.Models;

namespace Edendale.Windows.Core.Sync;

/// <summary>A title the watchlist store lists, and when it joined the watchlist.</summary>
public sealed record WatchlistListing(int TmdbId, string MediaType, long ChangedAt, string? Title = null, string? PosterPath = null)
{
    public string Key => $"{MediaType}:{TmdbId}";
}

/// <summary>
/// One watchlist flag to set. <see cref="UpdateTime"/> is the stamp a seeded
/// row takes, or 0 to keep its own; it is never lower than the row's.
/// </summary>
public sealed record WatchlistFlagUpdate(
    int TmdbId,
    string MediaType,
    bool InWatchlist,
    long UpdateTime = 0,
    string? Title = null,
    string? PosterPath = null,
    string? PosterUrl = null)
{
    public MediaRef Ref => new() { Id = TmdbId, MediaType = MediaType };
}

public static class WatchlistLaunchReconcile
{
    public sealed class Outcome
    {
        /// <summary>Rows that take the watchlist's flag without a fresh stamp, and rows for titles only the watchlist lists.</summary>
        public List<WatchlistFlagUpdate> Seeds { get; } = [];

        /// <summary>Rows another device stamped: the watchlist store follows their flag.</summary>
        public List<WatchlistFlagUpdate> WatchlistFollows { get; } = [];

        /// <summary>This PC's own stamped rows the watchlist changed after: the flag takes a fresh stamp.</summary>
        public List<WatchlistFlagUpdate> FreshStamps { get; } = [];

        public bool IsEmpty => Seeds.Count == 0 && WatchlistFollows.Count == 0 && FreshStamps.Count == 0;
    }

    /// <param name="rows">Every user-media record, tombstones included.</param>
    /// <param name="listed">The titles the watchlist store lists.</param>
    /// <param name="thisDevice">This PC's sync device ID, or null when it has none yet.</param>
    /// <param name="now">This PC's clock, which caps a listing's time.</param>
    /// <param name="firstRun">Whether the reconcile has never completed on this PC.</param>
    public static Outcome Plan(
        IReadOnlyCollection<UserMediaRecord> rows,
        IReadOnlyCollection<WatchlistListing> listed,
        string? thisDevice,
        long now,
        bool firstRun)
    {
        var outcome = new Outcome();
        var listings = new Dictionary<string, WatchlistListing>(StringComparer.Ordinal);
        foreach (var listing in listed) listings.TryAdd(listing.Key, listing);
        var seen = new HashSet<string>(StringComparer.Ordinal);

        foreach (var row in rows)
        {
            seen.Add(row.StorageKey);
            listings.TryGetValue(row.StorageKey, out var listing);
            var isListed = listing is not null;
            if (row.Watchlist == isListed)
            {
                // A listed flag with no time of its own (a row from before
                // per-field times) would go out as an invalid stamp: it
                // takes the watchlist's.
                if (listing is not null && row.EffectiveUpdateTime == 0)
                {
                    outcome.Seeds.Add(Seed(row, true, Capped(listing.ChangedAt, now), listing));
                }
                continue;
            }

            var stampedElsewhere = row.UpdateTime > 0 && row.Device is { Length: > 0 } device && device != thisDevice;
            if (row.UpdateTime == 0 || (firstRun && !stampedElsewhere))
            {
                // Listed: the row is dated no earlier than the title joined
                // the watchlist. Unlisted: the flag goes without a stamp, so
                // another device's newer add still wins.
                long stamp = 0;
                if (listing is not null)
                {
                    var joined = Capped(listing.ChangedAt, now);
                    if (joined > row.EffectiveUpdateTime) stamp = joined;
                }
                outcome.Seeds.Add(Seed(row, isListed, stamp, listing));
            }
            else if (stampedElsewhere)
            {
                outcome.WatchlistFollows.Add(new WatchlistFlagUpdate(
                    row.TmdbId, row.MediaType, row.Watchlist, Title: row.Title, PosterPath: row.PosterPath, PosterUrl: row.PosterUrl));
            }
            else
            {
                outcome.FreshStamps.Add(new WatchlistFlagUpdate(
                    row.TmdbId, row.MediaType, isListed, Title: listing?.Title ?? row.Title, PosterPath: listing?.PosterPath ?? row.PosterPath));
            }
        }

        foreach (var listing in listings.Values)
        {
            if (seen.Contains(listing.Key)) continue;
            outcome.Seeds.Add(new WatchlistFlagUpdate(
                listing.TmdbId, listing.MediaType, true, Capped(listing.ChangedAt, now), listing.Title, listing.PosterPath));
        }
        return outcome;
    }

    private static WatchlistFlagUpdate Seed(UserMediaRecord row, bool inWatchlist, long stamp, WatchlistListing? listing) =>
        new(row.TmdbId, row.MediaType, inWatchlist, stamp, listing?.Title ?? row.Title, listing?.PosterPath ?? row.PosterPath);

    /// <summary>A listing's time, never after this PC's clock; now when it has none.</summary>
    private static long Capped(long changedAt, long now) => changedAt > 0 ? Math.Min(changedAt, now) : now;
}
